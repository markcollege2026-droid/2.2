package com.campmeds.app.ui.importsheet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campmeds.app.auth.Session
import com.campmeds.app.data.entity.Role
import com.campmeds.app.export.ImportTemplateExporter
import com.campmeds.app.repository.SpreadsheetImportRepository
import com.campmeds.app.spreadsheet.Field
import com.campmeds.app.spreadsheet.FieldChange
import com.campmeds.app.spreadsheet.HeaderMapper
import com.campmeds.app.spreadsheet.HeaderMapping
import com.campmeds.app.spreadsheet.ImportDecisions
import com.campmeds.app.spreadsheet.ImportMode
import com.campmeds.app.spreadsheet.ImportPlan
import com.campmeds.app.spreadsheet.IssueKind
import com.campmeds.app.spreadsheet.Issue
import com.campmeds.app.spreadsheet.MedicationChoice
import com.campmeds.app.spreadsheet.PatientChoice
import com.campmeds.app.spreadsheet.RawSheet
import com.campmeds.app.spreadsheet.SpreadsheetPlanner
import com.campmeds.app.spreadsheet.XlsxSpreadsheetReader
import com.campmeds.app.spreadsheet.blockingReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val MAX_SHOWN = 200

private enum class Step { CHOOSE, MAP, PREVIEW, DONE }

/**
 * Settings > Import spreadsheet. Administrator only.
 *
 * Flow: choose the workflow (new records or updates) -> pick an .xlsx from the device -> map columns if the
 * headers were not recognised -> review the preview -> confirm. Cancel at any point before "Confirm" writes
 * nothing. The database is only touched by [SpreadsheetImportRepository.apply], after confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSpreadsheetScreen(
    importRepository: SpreadsheetImportRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUser by Session.currentUser.collectAsState()
    val isAdmin = Session.hasAtLeast(currentUser, Role.ADMIN)

    var step by remember { mutableStateOf(Step.CHOOSE) }
    var mode by remember { mutableStateOf(ImportMode.NEW_RECORDS) }
    var sheet by remember { mutableStateOf<RawSheet?>(null) }
    var columns by remember { mutableStateOf<Map<Field, Int>>(emptyMap()) }
    var decisions by remember { mutableStateOf(ImportDecisions()) }
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<SpreadsheetImportRepository.Outcome?>(null) }

    fun resetToChoose() {
        step = Step.CHOOSE
        sheet = null
        columns = emptyMap()
        decisions = ImportDecisions()
        plan = null
        message = null
        outcome = null
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    XlsxSpreadsheetReader.read(context, uri, displayName(context, uri))
                }
            }
            busy = false
            result.onSuccess { loaded ->
                val mapping: HeaderMapping = HeaderMapper.suggest(loaded.headers, mode)
                sheet = loaded
                columns = mapping.columns
                decisions = ImportDecisions()
                plan = null
                step = if (mapping.needsReview) Step.MAP else Step.PREVIEW
            }.onFailure {
                message = it.message ?: "The file could not be read."
            }
        }
    }

    val templateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = importRepository.loadSnapshot()
                    ImportTemplateExporter.export(context, uri, snapshot.patients, snapshot.medications)
                }
            }
            busy = false
            message = result.fold(
                onSuccess = { "Template saved. Use it with Update existing records (keep the ID columns)." },
                onFailure = { "The template could not be saved: ${it.message}" }
            )
        }
    }

    // Recalculate the preview whenever the file, the mapping or an administrator decision changes.
    LaunchedEffect(step, sheet, columns, mode, decisions) {
        val current = sheet
        if (step == Step.PREVIEW && current != null) {
            busy = true
            val snapshot = importRepository.loadSnapshot()
            plan = withContext(Dispatchers.Default) {
                SpreadsheetPlanner.plan(current, columns, mode, snapshot, decisions)
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import spreadsheet") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (step == Step.CHOOSE || step == Step.DONE) onBack() else resetToChoose()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isAdmin) {
                Text("Only an Administrator can import spreadsheets.", color = MaterialTheme.colorScheme.error)
            } else {
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                when (step) {
                    Step.CHOOSE -> ChooseStep(
                        busy = busy,
                        onNewRecords = {
                            mode = ImportMode.NEW_RECORDS
                            pickLauncher.launch(arrayOf(XLSX_MIME))
                        },
                        onUpdate = {
                            mode = ImportMode.UPDATE_EXISTING
                            pickLauncher.launch(arrayOf(XLSX_MIME))
                        },
                        onTemplate = { templateLauncher.launch("campmeds-import-template.xlsx") }
                    )

                    Step.MAP -> {
                        val current = sheet
                        if (current != null) {
                            MapColumnsStep(
                                mode = mode,
                                headers = current.headers,
                                columns = columns,
                                onChange = { columns = it },
                                onContinue = { step = Step.PREVIEW }
                            )
                        }
                    }

                    Step.PREVIEW -> {
                        val current = plan
                        if (current == null) {
                            CircularProgressIndicator()
                        } else {
                            PreviewStep(
                                plan = current,
                                decisions = decisions,
                                busy = busy,
                                onDecisions = { decisions = it },
                                onCancel = { resetToChoose() },
                                onConfirm = {
                                    val currentSheet = sheet
                                    if (currentSheet != null) {
                                        val reviewedSignature = current.signature()
                                        val appliedDecisions = decisions
                                        busy = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                importRepository.apply(
                                                    actor = Session.currentUser.value,
                                                    sheet = currentSheet,
                                                    columns = columns,
                                                    mode = mode,
                                                    decisions = appliedDecisions,
                                                    reviewedSignature = reviewedSignature
                                                )
                                            }
                                            busy = false
                                            outcome = result
                                            step = Step.DONE
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Step.DONE -> DoneStep(outcome = outcome, onFinished = onBack, onAnother = { resetToChoose() })
                }
            }
        }
    }
}

@Composable
private fun ChooseStep(
    busy: Boolean,
    onNewRecords: () -> Unit,
    onUpdate: () -> Unit,
    onTemplate: () -> Unit
) {
    Text(
        "Nothing is saved until you review the preview and confirm. Cancelling at any point changes nothing. " +
            "Your existing records are never deleted because a row is missing from a spreadsheet.",
        style = MaterialTheme.typography.bodySmall
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Import new records", style = MaterialTheme.typography.titleMedium)
            Text(
                "For a spreadsheet without CampMeds IDs (for example an initial setup list). Adds patients and " +
                    "medications. Possible duplicates must be resolved. Existing records are never overwritten."
            )
            Button(onClick = onNewRecords, enabled = !busy) { Text("Choose spreadsheet") }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Update existing records", style = MaterialTheme.typography.titleMedium)
            Text(
                "Requires a CampMeds template with Patient ID and Medication ID columns. Rows are matched by ID " +
                    "only; blank cells change nothing. Rows with missing, duplicated or invalid IDs are blocked."
            )
            Button(onClick = onUpdate, enabled = !busy) { Text("Choose CampMeds template") }
        }
    }
    OutlinedButton(onClick = onTemplate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Download CampMeds template (with IDs)")
    }
}

@Composable
private fun MapColumnsStep(
    mode: ImportMode,
    headers: List<String>,
    columns: Map<Field, Int>,
    onChange: (Map<Field, Int>) -> Unit,
    onContinue: () -> Unit
) {
    var expanded by remember { mutableStateOf<Field?>(null) }
    val required = Field.required(mode)
    val allRequiredMapped = required.all { it in columns }
    val noDuplicateColumns = columns.values.toSet().size == columns.size

    Text("Some columns were not recognised. Choose the spreadsheet column for each field.", style = MaterialTheme.typography.titleMedium)
    Text("Fields marked * are required for this import. Choose \"Not in file\" for fields your sheet does not have.", style = MaterialTheme.typography.bodySmall)
    Field.values().forEach { field ->
        val selectedIndex = columns[field]
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(field.header + if (field in required) " *" else "", fontWeight = FontWeight.SemiBold)
                Text(
                    selectedIndex?.let { headers.getOrNull(it)?.ifBlank { "(column ${it + 1})" } } ?: "Not in file",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box {
                TextButton(onClick = { expanded = field }) { Text("Choose") }
                DropdownMenu(expanded = expanded == field, onDismissRequest = { expanded = null }) {
                    DropdownMenuItem(
                        text = { Text("Not in file") },
                        onClick = {
                            onChange(columns - field)
                            expanded = null
                        }
                    )
                    headers.forEachIndexed { index, header ->
                        DropdownMenuItem(
                            text = { Text(header.ifBlank { "(column ${index + 1})" }) },
                            onClick = {
                                onChange(columns.filterValues { it != index } + (field to index))
                                expanded = null
                            }
                        )
                    }
                }
            }
        }
        HorizontalDivider()
    }
    if (!noDuplicateColumns) {
        Text("Each spreadsheet column can be used for only one field.", color = MaterialTheme.colorScheme.error)
    }
    Button(
        onClick = onContinue,
        enabled = allRequiredMapped && noDuplicateColumns,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Continue to preview") }
}

@Composable
private fun PreviewStep(
    plan: ImportPlan,
    decisions: ImportDecisions,
    busy: Boolean,
    onDecisions: (ImportDecisions) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val blocking = plan.blockingReason(decisions)
    val conflictIssues = plan.issues.filter { it.kind == IssueKind.CONFLICT }
    val invalidIssues = plan.issues.filter { it.kind == IssueKind.INVALID_ROW }
    val noticeIssues = plan.issues.filter { it.kind == IssueKind.NOTICE }

    Text(if (plan.mode == ImportMode.UPDATE_EXISTING) "Update preview" else "New records preview", style = MaterialTheme.typography.titleLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CountLine("Rows read", plan.rowsRead)
            CountLine("New patients", plan.patientCreates.size)
            CountLine("New medication records", plan.medicationCreates.size)
            CountLine("Patient detail changes", plan.patientUpdates.size)
            CountLine("Medication changes", plan.medicationUpdates.size)
            CountLine("Possible duplicates (decision needed)", plan.patientCandidates.size + plan.medicationCandidates.size)
            CountLine("Invalid rows (skipped)", plan.invalidRowCount)
            CountLine("Conflicts (skipped)", conflictIssues.mapNotNull { it.rowNumber }.distinct().size)
            CountLine("Records not in the spreadsheet (unchanged)", plan.absentRecords.size)
        }
    }

    if (plan.patientCandidates.isNotEmpty() || plan.medicationCandidates.isNotEmpty()) {
        SectionTitle("Possible duplicates: decide each one")
        plan.patientCandidates.forEach { candidate ->
            val choice = decisions.patientChoices[candidate.key]
            Text("${candidate.displayName} (rows ${candidate.rows.joinToString(", ")})", fontWeight = FontWeight.SemiBold)
            RadioRow(
                selected = choice is PatientChoice.CreateNew,
                label = "Different person: create a new patient"
            ) {
                onDecisions(decisions.copy(patientChoices = decisions.patientChoices + (candidate.key to PatientChoice.CreateNew)))
            }
            candidate.matches.take(MAX_SHOWN).forEach { match ->
                val dob = match.dob?.let { ", born $it" }.orEmpty()
                RadioRow(
                    selected = (choice as? PatientChoice.UseExisting)?.patientId == match.id,
                    label = "Same person: add to existing record ${match.name}$dob"
                ) {
                    onDecisions(
                        decisions.copy(
                            patientChoices = decisions.patientChoices +
                                (candidate.key to PatientChoice.UseExisting(match.id))
                        )
                    )
                }
            }
            HorizontalDivider()
        }
        plan.medicationCandidates.forEach { candidate ->
            val choice = decisions.medicationChoices[candidate.key]
            Text("${candidate.displayName} (row ${candidate.rowNumber})", fontWeight = FontWeight.SemiBold)
            Text(candidate.reason, style = MaterialTheme.typography.bodySmall)
            RadioRow(selected = choice == MedicationChoice.ADD, label = "Add as a separate medication record") {
                onDecisions(decisions.copy(medicationChoices = decisions.medicationChoices + (candidate.key to MedicationChoice.ADD)))
            }
            RadioRow(selected = choice == MedicationChoice.SKIP, label = "Skip this row (already in CampMeds)") {
                onDecisions(decisions.copy(medicationChoices = decisions.medicationChoices + (candidate.key to MedicationChoice.SKIP)))
            }
            HorizontalDivider()
        }
    }

    if (plan.patientCreates.isNotEmpty()) {
        SectionTitle("New patients")
        plan.patientCreates.take(MAX_SHOWN).forEach { create ->
            Text("${create.draft.name} (rows ${create.rows.joinToString(", ")})")
        }
    }
    if (plan.medicationCreates.isNotEmpty()) {
        SectionTitle("New medication records")
        plan.medicationCreates.take(MAX_SHOWN).forEach { create ->
            Text("${create.draft.name} for ${create.patientLabel} (row ${create.rowNumber})")
            Text(
                "${create.draft.dosageInstructions} · ${create.draft.scheduleTimes.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (plan.patientUpdates.isNotEmpty()) {
        SectionTitle("Proposed patient-detail changes")
        plan.patientUpdates.take(MAX_SHOWN).forEach { update ->
            Text("${update.displayName} (rows ${update.rows.joinToString(", ")})", fontWeight = FontWeight.SemiBold)
            update.changes.forEach { ChangeLine(it) }
        }
    }
    if (plan.medicationUpdates.isNotEmpty()) {
        SectionTitle("Proposed medication changes")
        plan.medicationUpdates.take(MAX_SHOWN).forEach { update ->
            Text(
                "${update.target.name} for ${update.patientName} (rows ${update.rows.joinToString(", ")})",
                fontWeight = FontWeight.SemiBold
            )
            if (update.identityChanged) {
                Text(
                    "Name, NDC, strength or form changes keep the same sheet, record ID and QR code. Dated history preserves the previous and new values.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            update.changes.forEach { ChangeLine(it) }
        }
    }
    if (invalidIssues.isNotEmpty()) {
        SectionTitle("Invalid rows (skipped)")
        invalidIssues.take(MAX_SHOWN).forEach { IssueLine(it, MaterialTheme.colorScheme.error) }
    }
    if (conflictIssues.isNotEmpty()) {
        SectionTitle("Unresolved conflicts (skipped)")
        conflictIssues.take(MAX_SHOWN).forEach { IssueLine(it, MaterialTheme.colorScheme.error) }
    }
    if (noticeIssues.isNotEmpty()) {
        SectionTitle("Notes")
        noticeIssues.take(MAX_SHOWN).forEach { IssueLine(it, MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (plan.absentRecords.isNotEmpty()) {
        SectionTitle("Not in the spreadsheet (information only, nothing is deleted)")
        plan.absentRecords.take(MAX_SHOWN).forEach { Text("${it.label} · unchanged", style = MaterialTheme.typography.bodySmall) }
    }

    if (plan.requiresConfirmation) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = decisions.confirmSignificantChanges,
                onCheckedChange = { onDecisions(decisions.copy(confirmSignificantChanges = it)) }
            )
            Text("I have reviewed the significant changes above (dose, schedule, dates, names, patient details).")
        }
    }
    blocking?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!plan.hasChanges && blocking == null) {
        Text("Nothing would change. Cancel, or fix the rows listed above.")
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(
            onClick = onConfirm,
            enabled = !busy && blocking == null && plan.hasChanges,
            modifier = Modifier.weight(1f)
        ) { Text("Confirm import") }
    }
}

@Composable
private fun DoneStep(
    outcome: SpreadsheetImportRepository.Outcome?,
    onFinished: () -> Unit,
    onAnother: () -> Unit
) {
    when (outcome) {
        is SpreadsheetImportRepository.Outcome.Completed -> {
            val s = outcome.summary
            Text("Import complete", style = MaterialTheme.typography.titleLarge)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CountLine("New patients added", s.newPatients)
                    CountLine("New medication records added", s.newMedications)
                    CountLine("Patients updated", s.patientsUpdated)
                    CountLine("Medications updated", s.medicationsUpdated)
                    CountLine("Fields changed", s.fieldsChanged)
                    CountLine("Rows skipped", s.rowsSkipped)
                    s.backupFileName?.let { Text("Recovery copy saved on this device: $it", style = MaterialTheme.typography.bodySmall) }
                    Text("Import ID: ${s.importId.take(8)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Medication administration history was not changed. Imported medications have no doses recorded.", style = MaterialTheme.typography.bodySmall)
        }
        is SpreadsheetImportRepository.Outcome.Rejected -> Text(outcome.message, color = MaterialTheme.colorScheme.error)
        is SpreadsheetImportRepository.Outcome.Failed -> Text(outcome.message, color = MaterialTheme.colorScheme.error)
        null -> Text("No import was performed.")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onAnother, modifier = Modifier.weight(1f)) { Text("Import another file") }
        Button(onClick = onFinished, modifier = Modifier.weight(1f)) { Text("Done") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CountLine(label: String, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(count.toString(), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

@Composable
private fun ChangeLine(change: FieldChange) {
    val before = change.oldValue.ifBlank { "(empty)" }
    val after = if (change.clears) "(cleared)" else change.newValue.ifBlank { "(empty)" }
    Column {
        Text("${change.field}: $before → $after")
        if (change.significant) {
            Text(
                if (change.clears) "Significant: this will empty the value." else "Significant change",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun IssueLine(issue: Issue, color: androidx.compose.ui.graphics.Color) {
    Text(
        "Row ${issue.rowNumber ?: "-"}${issue.field?.let { " · $it" }.orEmpty()}: ${issue.message}",
        color = color,
        style = MaterialTheme.typography.bodySmall
    )
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "spreadsheet.xlsx"
