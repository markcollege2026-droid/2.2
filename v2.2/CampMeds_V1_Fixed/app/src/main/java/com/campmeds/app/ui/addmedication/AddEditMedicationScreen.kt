package com.campmeds.app.ui.addmedication

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campmeds.app.data.entity.AdministrationTime
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.domain.AdministrationTimeRules
import com.campmeds.app.domain.InputValidation
import com.campmeds.app.domain.MedicationIdentity
import com.campmeds.app.domain.NdcNormalizer
import com.campmeds.app.domain.PrepackagedGroups
import com.campmeds.app.domain.Validated
import com.campmeds.app.network.NdcLookupResult
import com.campmeds.app.repository.CampMedsRepository
import com.campmeds.app.ui.common.QrLabelPanel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * Screen 4 (spec section 4): NDC entry field → lookup button → shows matched name/strength/form
 * for staff to confirm against the physical bottle → dosage, schedule, dates → generates QR code.
 *
 * V1 Fixed changes vs. the original screen (all in this file):
 *  - schedule times / dates are validated strictly and NEVER silently dropped (bugs 3, 4, 5)
 *  - end date must not precede start date (bug 6)
 *  - changing the NDC discards the old NDC's lookup/manual details; a new lookup (or an explicit
 *    manual-exception entry) is required before saving (bug 8)
 *  - manual entry is an explicit action, and only exception-flagged data is ever manual (bug 9 partner)
 *
 * Update changes in this file:
 *  - the schedule is picked from the administrator's named administration times (dropdown/chips), which
 *    also shows the name of each scheduled dose on the patient's due screen; custom times are still possible
 *    so existing medications and camps without configured names keep working;
 *  - "Require QR scan when selecting this medication." (per medication, default on) and
 *    "Administer together with other selected medications." (prepackaged groups) are saved with the record;
 *  - the lookup result distinguishes a record that was found, no record, ambiguous input, invalid input,
 *    a network failure and an API failure — and manual entry stays available in every one of those cases.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditMedicationScreen(
    repository: CampMedsRepository,
    patientId: String,
    medicationId: String?,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isEditing = medicationId != null

    var ndc by remember { mutableStateOf("") }
    /** The exact NDC that name/strength/form currently belong to (null = details not established). */
    var detailsForNdc by remember { mutableStateOf<String?>(null) }
    var lookedUp by remember { mutableStateOf<NdcLookupResult?>(null) }
    var isLookingUp by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf("") }
    var form by remember { mutableStateOf("") }
    var isException by remember { mutableStateOf(false) }

    var dosageInstructions by remember { mutableStateOf("") }
    var startText by remember { mutableStateOf(LocalDate.now().toString()) }
    var endText by remember { mutableStateOf("") }
    var expirationText by remember { mutableStateOf("") }
    var pillCount by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var patientName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // ---- schedule: named administration times (shared list) + custom times ----
    val administrationTimes by repository.observeAdministrationTimes().collectAsState(initial = emptyList())
    var selectedNamedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var customTimes by remember { mutableStateOf<List<LocalTime>>(emptyList()) }
    var customTimeText by remember { mutableStateOf("") }
    var customTimeError by remember { mutableStateOf<String?>(null) }

    // ---- per-medication settings ----
    val sessions by repository.observeSessions().collectAsState(initial = emptyList())
    val campSettings by repository.observeSettings().collectAsState(initial = com.campmeds.app.data.entity.CampSettings())
    var isPrn by remember { mutableStateOf<Boolean?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDiscontinue by remember { mutableStateOf(false) }
    var discontinueDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var requireQrScan by remember { mutableStateOf(true) }
    var isGrouped by remember { mutableStateOf(false) }
    var prepackagedGroupId by remember { mutableStateOf<String?>(null) }
    // Group name text box: prefilled from the administration time(s) chosen above until the user edits it.
    var groupName by remember { mutableStateOf("") }
    var groupNameEdited by remember { mutableStateOf(false) }
    // OCR (offline): photo of the label -> NDC candidates; photo of the instruction sheet -> dosage text.
    var ocrNdcOpen by remember { mutableStateOf(false) }
    var ocrNdcResult by remember { mutableStateOf<com.campmeds.app.ocr.OcrOutcome.Recognized?>(null) }
    var ocrInstructionsOpen by remember { mutableStateOf(false) }
    var ocrInstructionsNote by remember { mutableStateOf<String?>(null) }
    var groupMenuOpen by remember { mutableStateOf(false) }
    var patientMedications by remember { mutableStateOf<List<Medication>>(emptyList()) }

    var qrCode by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var existing by remember { mutableStateOf<Medication?>(null) }

    // Validation messages. The user's typed text is never modified when an error is shown.
    var ndcError by remember { mutableStateOf<String?>(null) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var dosageError by remember { mutableStateOf<String?>(null) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var groupError by remember { mutableStateOf<String?>(null) }
    var startError by remember { mutableStateOf<String?>(null) }
    var endError by remember { mutableStateOf<String?>(null) }
    var expirationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(patientId) {
        patientName = repository.getPatient(patientId)?.name.orEmpty()
        patientMedications = repository.getCurrentMedications().filter { it.patientId == patientId }
    }

    LaunchedEffect(medicationId) {
        if (medicationId != null) {
            // One-shot read of the shared list: the record's references can be matched immediately, without
            // racing the reactive list that is only used to draw the chips below.
            val definitionsById = repository.getAdministrationTimes().associateBy { it.id }
            repository.getMedication(medicationId)?.let { med ->
                existing = med
                isPrn = med.isPrn
                ndc = med.ndc
                detailsForNdc = med.ndc
                name = med.name
                strength = med.strength
                form = med.form
                isException = med.isException
                dosageInstructions = med.dosageInstructions
                // A scheduled time keeps its name while the entry still exists; a reference to a removed entry
                // (or a hand-typed time) becomes a custom time with the same clock time — never a lost dose.
                val named = mutableListOf<String>()
                val customs = mutableListOf<LocalTime>()
                med.scheduleTimes.forEachIndexed { index, time ->
                    val id = AdministrationTimeRules.definitionIdAt(med, index)
                    if (id.isNotEmpty() && definitionsById.containsKey(id)) named += id else customs += time
                }
                selectedNamedIds = named.distinct()
                customTimes = customs
                startText = med.startDate.toString()
                endText = med.endDate?.toString() ?: ""
                expirationText = med.bottleExpiration?.toString() ?: ""
                pillCount = med.pillCountEntered?.toString() ?: ""
                purpose = med.purpose.orEmpty()
                qrCode = med.qrCode
                requireQrScan = med.requireQrScan
                isGrouped = med.isGroupAdministered
                prepackagedGroupId = med.prepackagedGroupId
                groupName = med.prepackagedGroupName.orEmpty()
                groupNameEdited = med.prepackagedGroupName != null
                lookedUp = if (med.isException) null
                else NdcLookupResult.Found(
                    ndc = med.ndc,
                    matchedNdc = med.ndc,
                    name = med.name,
                    strength = med.strength,
                    form = med.form,
                    isPackage = med.ndc.count { it == '-' } == 2
                )
            }
        }
    }

    fun onNdcChanged(newValue: String) {
        ndc = newValue
        ndcError = null
        if (newValue.trim() != detailsForNdc) {
            // Manual details typed while the NDC was empty (or that are being cleared back to empty) were never
            // tied to a specific NDC, so typing/removing an NDC must not wipe what staff just entered.
            val keepManual = isException && (detailsForNdc.isNullOrEmpty() || newValue.isBlank())
            if (keepManual) {
                detailsForNdc = newValue.trim()
            } else {
                // BUG FIX (V1 bug 8): details retrieved/typed for the old NDC must not stay attached to a
                // different NDC. Drop them and force a fresh lookup (or an explicit manual exception).
                name = ""; strength = ""; form = ""
                lookedUp = null
                isException = false
                detailsForNdc = null
                manualError = null
            }
        }
    }

    val namedSelection = administrationTimes.filter { it.id in selectedNamedIds }
    val selectedGroup = PrepackagedGroups.groupsOfPatient(patientMedications, patientId)
        .firstOrNull { it.id == prepackagedGroupId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Medication" else "Add Medication") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text("Medication identification", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { ocrNdcOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Read NDC from label photo (on device)")
            }
            ocrNdcResult?.let { result ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val candidates = result.ndcCandidates
                        if (candidates.isEmpty()) {
                            Text("No NDC-shaped number was found in the photo. Type the NDC or read it again.",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Possible NDCs read from the photo — pick one, then run the lookup. " +
                                "Nothing is saved from the photo itself.", style = MaterialTheme.typography.bodySmall)
                            candidates.forEach { c ->
                                val note = buildList {
                                    add(c.format.label)
                                    if (c.fromUnseparatedDigits) add("no dashes: check the layout on the label")
                                    if (c.occurrences > 1) add("appears ${c.occurrences} times")
                                }.joinToString(" · ")
                                OutlinedButton(onClick = { onNdcChanged(c.hyphenated); ocrNdcResult = null },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text("${c.hyphenated}  ($note)")
                                }
                            }
                        }
                        TextButton(onClick = { ocrNdcResult = null }) { Text("Dismiss") }
                    }
                }
            }
            if (ocrNdcOpen) {
                com.campmeds.app.ui.ocr.OcrCaptureSheet(
                    scanType = com.campmeds.app.ocr.OcrScanType.NDC,
                    title = "Photograph the NDC on the label",
                    onDismiss = { ocrNdcOpen = false },
                    onRecognized = { outcome -> ocrNdcResult = outcome; ocrNdcOpen = false }
                )
            }

            OutlinedTextField(
                value = ndc,
                onValueChange = ::onNdcChanged,
                label = { Text("NDC number (optional for manual entry)") },
                singleLine = true,
                isError = ndcError != null,
                supportingText = { Text(ndcError ?: NdcNormalizer.HELP) },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = ndc.isNotBlank() && !isLookingUp,
                onClick = {
                    val key = ndc.trim()
                    isLookingUp = true
                    scope.launch {
                        val result = repository.lookupNdc(key)
                        isLookingUp = false
                        if (ndc.trim() != key) return@launch // NDC edited while the lookup was running
                        lookedUp = result
                        ndcError = null
                        if (result is NdcLookupResult.Found) {
                            name = result.name
                            strength = result.strength
                            form = result.form
                            isException = false
                            detailsForNdc = key
                        }
                    }
                }
            ) {
                Text(if (isLookingUp) "Looking up..." else "Look up NDC")
            }

            when (val result = lookedUp) {
                is NdcLookupResult.Found -> Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Match found — confirm against the physical bottle:", style = MaterialTheme.typography.labelLarge)
                        Text("Name: ${result.name}")
                        Text("Strength: ${result.strength}")
                        Text("Form: ${result.form}")
                        Text("Directory NDC: ${result.matchedNdc}")
                        NdcNormalizer.toBillingForm(result.matchedNdc)?.let {
                            Text("11-digit billing format: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        if (result.ndc.trim() != result.matchedNdc) {
                            Text("You entered: ${result.ndc}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (result.ambiguousInput) {
                            Text(
                                "This number can be read more than one way. The directory matched " +
                                    "${result.matchedNdc}; the other reading(s) — " +
                                    result.alternatives.joinToString(", ") +
                                    " — have no record. Check the dashes on the label before saving.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (result.uncheckedReadings.isNotEmpty()) {
                            Text(
                                "⚠️ openFDA did not answer for another reading of this number (" +
                                    result.uncheckedReadings.joinToString(", ") +
                                    "), so it could not be checked. Type the NDC with its dashes exactly as " +
                                    "printed on the label and look it up again before saving.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                is NdcLookupResult.NotFound -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "No record in the FDA NDC Directory for this number. Check the digits, or use Manual " +
                            "entry below.",
                        color = MaterialTheme.colorScheme.error
                    )
                    // What was searched and what could not be: the answer to "why did this one fail?".
                    result.notes.forEach { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is NdcLookupResult.Ambiguous -> Text(
                    "This number matches more than one product (${result.products.joinToString(", ")}), so it was not " +
                        "guessed. Enter the NDC with its dashes exactly as printed on the label.",
                    color = MaterialTheme.colorScheme.error
                )
                is NdcLookupResult.InvalidInput -> Text(result.message, color = MaterialTheme.colorScheme.error)
                is NdcLookupResult.NetworkError -> Text(
                    "Could not reach openFDA (${result.message}). Check the connection and try again, or use Manual entry below.",
                    color = MaterialTheme.colorScheme.error
                )
                is NdcLookupResult.ApiError -> Text(
                    "openFDA lookup failed (${result.message}). Try again later, or use Manual entry below.",
                    color = MaterialTheme.colorScheme.error
                )
                null -> {}
            }

            if (!isException && lookedUp !is NdcLookupResult.Found) {
                // Always available, including with the NDC field completely empty: no NDC is required for
                // manual entry, and a failed lookup never blocks it.
                OutlinedButton(
                    onClick = {
                        // Explicit, documented manual-exception workflow (spec: isException).
                        isException = true
                        detailsForNdc = ndc.trim()
                        ndcError = null
                    }
                ) {
                    Text("Manual entry — no NDC or can't look it up (flagged as exception)")
                }
            }

            if (isException) {
                Text(
                    "Manual exception: these details were typed by staff, not retrieved from openFDA.",
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedTextField(value = name, onValueChange = { name = it; manualError = null }, label = { Text("Name (manual)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = strength, onValueChange = { strength = it; manualError = null }, label = { Text("Strength (manual)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = form, onValueChange = { form = it; manualError = null }, label = { Text("Form (manual)") },
                    isError = manualError != null, supportingText = { manualError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()
            Text("Dosage & schedule", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = dosageInstructions,
                onValueChange = { dosageInstructions = it; dosageError = null },
                label = { Text("Dosage instructions (e.g. \"1 tablet, twice daily\")") },
                isError = dosageError != null,
                supportingText = { dosageError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { ocrInstructionsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Read instructions from a photo (on device)")
            }
            ocrInstructionsNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (ocrInstructionsOpen) {
                com.campmeds.app.ui.ocr.OcrCaptureSheet(
                    scanType = com.campmeds.app.ocr.OcrScanType.INSTRUCTION_DOCUMENT,
                    title = "Photograph the instructions",
                    onDismiss = { ocrInstructionsOpen = false },
                    onRecognized = { outcome ->
                        // The text is placed as read (line breaks kept); review it before saving.
                        dosageInstructions = outcome.text
                        dosageError = null
                        ocrInstructionsNote = "Text read from the photo is in the box above — check it before saving."
                        ocrInstructionsOpen = false
                    }
                )
            }
            OutlinedTextField(
                value = purpose,
                onValueChange = { purpose = it },
                label = { Text("What is this medication for?") },
                supportingText = { Text("Shown only on the patient's details screen, never while dosing.") },
                modifier = Modifier.fillMaxWidth()
            )

            // ---- administration times (named list + custom times) ----
            Text("Administration times", style = MaterialTheme.typography.titleSmall)
            if (administrationTimes.isEmpty()) {
                Text(
                    "No named administration times are set up yet. An administrator can add them under " +
                        "Settings → Administration times. Until then, add the time(s) below.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                FlowRowOfChips(
                    entries = administrationTimes,
                    selectedIds = selectedNamedIds,
                    onToggle = { id ->
                        scheduleError = null
                        selectedNamedIds = if (id in selectedNamedIds) selectedNamedIds - id else selectedNamedIds + id
                    }
                )
                Text(
                    "Pick every administration time this medication is given at — multiple choices are supported.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (namedSelection.isNotEmpty() || customTimes.isNotEmpty()) {
                val (previewTimes, _) = AdministrationTimeRules.buildSchedule(namedSelection, customTimes)
                Text(
                    "Selected: " + previewTimes.joinToString(" · ") { time ->
                        val entry = namedSelection.firstOrNull { it.time == time }
                        if (entry != null) "${entry.name} ${time.format(AdministrationTimeRules.HH_MM)}"
                        else "${time.format(AdministrationTimeRules.HH_MM)} (custom)"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customTimeText,
                    onValueChange = { customTimeText = it; customTimeError = null },
                    label = { Text("Custom time (24h HH:MM)") },
                    singleLine = true,
                    isError = customTimeError != null,
                    supportingText = { Text(customTimeError ?: "Only needed if it is not one of the named times.") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    when (val parsed = InputValidation.parseScheduleTimes(customTimeText)) {
                        is Validated.Ok -> {
                            customTimes = (customTimes + parsed.value).distinct().sorted()
                            customTimeText = ""
                            scheduleError = null
                        }
                        is Validated.Invalid -> customTimeError = parsed.message
                    }
                }) { Text("Add") }
            }
            if (customTimes.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Custom: " + customTimes.joinToString(", ") { it.format(AdministrationTimeRules.HH_MM) },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        customTimes = emptyList()
                        scheduleError = null
                    }) { Text("Clear custom") }
                }
            }
            scheduleError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("PRN / as-needed medication (required)", style = MaterialTheme.typography.titleSmall)
            if (isPrn == true) Text("Administration times are optional for PRN. Initiate each as-needed dose from the patient's screen; PRN is never automatically due or bulk-held.")
            Row {
                FilterChip(selected = isPrn == true, onClick = { isPrn = true }, label = { Text("Yes — PRN") })
                Spacer(Modifier.width(12.dp))
                FilterChip(selected = isPrn == false, onClick = { isPrn = false }, label = { Text("No — scheduled") })
            }
            com.campmeds.app.ui.common.SessionPresetField(sessions) { startText = it.startDate.toString(); endText = it.endDate.toString() }
            com.campmeds.app.ui.common.DatePresetField("Start date (YYYY-MM-DD)", startText, campSettings.startDates) { startText = it; startError = null }
            startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            com.campmeds.app.ui.common.DatePresetField("End date (optional, YYYY-MM-DD)", endText, campSettings.endDates) { endText = it; endError = null }
            endError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            existing?.let { Text("Created by: ${it.createdByName ?: "Unknown (legacy record)"}") }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = expirationText,
                onValueChange = { expirationText = it; expirationError = null },
                label = { Text("Bottle expiration (optional, YYYY-MM-DD)") },
                isError = expirationError != null,
                supportingText = { expirationError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pillCount,
                onValueChange = { pillCount = it.filter { c -> c.isDigit() } },
                label = { Text("Pill count entered (optional, manual)") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()
            Text("Scanning and packaging", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = requireQrScan, onCheckedChange = { requireQrScan = it })
                Spacer(Modifier.width(12.dp))
                Text(
                    "Require QR scan when selecting this medication.",
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "On: the medication's QR code must be scanned before a dose can be recorded, as today. " +
                    "Off: tapping the medication goes straight to verification — use only for medications with " +
                    "no scannable label. This is saved per medication.",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = isGrouped, onCheckedChange = { checked ->
                    isGrouped = checked
                    groupError = null
                    if (!checked) prepackagedGroupId = null
                })
                Spacer(Modifier.width(12.dp))
                Text(
                    "Administer together with other selected medications.",
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "Use this when the medications are prepackaged together and are meant to be administered together. " +
                    "Only medications of this patient can share a group, and nothing is grouped automatically.",
                style = MaterialTheme.typography.bodySmall
            )
            if (isGrouped) {
                // Prefill from the chosen administration time name(s), e.g. "Breakfast" or "Breakfast + Dinner".
                LaunchedEffect(isGrouped, selectedNamedIds, administrationTimes) {
                    if (!groupNameEdited) groupName = suggestedGroupName(administrationTimes, selectedNamedIds)
                }
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it; groupNameEdited = true; groupError = null },
                    label = { Text("Group name") },
                    supportingText = {
                        Text("Prefilled from the administration time you chose. Change it if needed.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val pickerEntries = PrepackagedGroups.pickerEntries(
                    PrepackagedGroups.groupsOfPatient(patientMedications, patientId),
                    prepackagedGroupId
                )
                OutlinedButton(onClick = { groupMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        selectedGroup?.let { "Group: " + it.detailLabel }
                            ?: (if (prepackagedGroupId != null) "New prepackaged group" else "Choose a group")
                    )
                }
                Text(
                    "Pick the existing group this medication is packaged with, or start a new one.",
                    style = MaterialTheme.typography.bodySmall
                )
                DropdownMenu(expanded = groupMenuOpen, onDismissRequest = { groupMenuOpen = false }) {
                    pickerEntries.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        entry.text + if (entry.isCurrent) "  ✓ current" else "",
                                        fontWeight = if (entry.isCurrent) FontWeight.SemiBold else null
                                    )
                                    Text(
                                        "${entry.group.members.size} medication" +
                                            (if (entry.group.members.size == 1) "" else "s") + " in this group",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            },
                            onClick = {
                                prepackagedGroupId = entry.group.id
                                // Joining a named group adopts its name; an unnamed group keeps the typed text.
                                entry.group.name?.let { groupName = it; groupNameEdited = true }
                                groupMenuOpen = false
                                groupError = null
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("New group (this medication starts a new one)") },
                        onClick = {
                            prepackagedGroupId = PrepackagedGroups.newGroupId()
                            groupNameEdited = false
                            groupMenuOpen = false
                            groupError = null
                        }
                    )
                }
                selectedGroup?.let { group ->
                    Text(
                        "In this group: " + group.members.joinToString(", ") { "${it.name} ${it.strength}" } +
                            (if (group.members.none { it.id == existing?.id }) " — plus this medication" else ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                groupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            HorizontalDivider()
            Text("QR code for bag/bottle label", style = MaterialTheme.typography.titleMedium)
            val currentExisting = existing
            val identityChanging = currentExisting != null && MedicationIdentity.changed(
                currentExisting,
                currentExisting.copy(ndc = ndc.trim(), name = name, strength = strength, form = form)
            )
            when {
                currentExisting == null -> Text(
                    "The QR code is created when you save. Then print, save or share it from here or from the patient's details."
                )
                identityChanging -> Text(
                    "Saving keeps the same medication sheet and QR code. A dated history entry preserves the old and new values.",
                    color = MaterialTheme.colorScheme.error
                )
                else -> {
                    QrLabelPanel(
                        qrContent = qrCode,
                        lines = listOf(patientName, name),
                        fileBaseName = "medication_${patientName}_$name",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Print or attach this code to the bag/bottle. It's what staff scan at dosing time.")
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    // ---- validate everything; nothing is saved unless every field passes ----
                    val ndcKey = ndc.trim()
                    var ok = true

                    if (ndcKey.isEmpty() && !isException) {
                        // An empty NDC is fine, but only through manual entry. No fake NDC is ever required.
                        ndcError = "Enter an NDC and look it up, or use Manual entry (no NDC needed)."; ok = false
                    } else if (detailsForNdc != ndcKey ||
                        (!isException && lookedUp !is NdcLookupResult.Found)
                    ) {
                        ndcError = "Look up this NDC before saving (or use Manual entry)."; ok = false
                    }
                    if (isException && (name.isBlank() || strength.isBlank() || form.isBlank())) {
                        manualError = "Name, strength and form are all required for a manual entry."; ok = false
                    }
                    if (dosageInstructions.isBlank()) {
                        dosageError = "Dosage instructions are required."; ok = false
                    }

                    val (times, administrationTimeIds) = AdministrationTimeRules.buildSchedule(namedSelection, customTimes)
                    if (times.isEmpty() && isPrn != true) {
                        scheduleError = "Choose at least one administration time, or add a custom time."; ok = false
                    }
                    // An entry can disappear (removed in Settings) while this form is open. Refuse to save
                    // rather than silently dropping the dose that was scheduled from it.
                    if (isPrn != true && selectedNamedIds.any { id -> administrationTimes.none { it.id == id } }) {
                        scheduleError = "One of the selected administration times was removed. Choose the times again."; ok = false
                    }

                    val start = when (val r = InputValidation.parseDate(startText, "start date", required = true)) {
                        is Validated.Ok -> r.value
                        is Validated.Invalid -> { startError = r.message; ok = false; null }
                    }
                    val end = when (val r = InputValidation.parseDate(endText, "end date", required = false)) {
                        is Validated.Ok -> r.value
                        is Validated.Invalid -> { endError = r.message; ok = false; null }
                    }
                    val expiration = when (val r = InputValidation.parseDate(expirationText, "bottle expiration", required = false)) {
                        is Validated.Ok -> r.value
                        is Validated.Invalid -> { expirationError = r.message; ok = false; null }
                    }
                    if (start != null) {
                        InputValidation.validateDateRange(start, end)?.let { endError = it; ok = false }
                    }

                    if (isPrn == null) { saveError = "Choose Yes or No for PRN."; ok = false }
                    if (saving || !ok || start == null) return@Button

                    val medication = (existing ?: Medication(
                        patientId = patientId,
                        ndc = ndcKey,
                        name = name,
                        strength = strength,
                        form = form,
                        dosageInstructions = dosageInstructions,
                        scheduleTimes = times,
                        startDate = start,
                        qrCode = qrCode,
                        purpose = purpose.trim().ifBlank { null }
                    )).copy(
                        ndc = ndcKey,
                        name = name,
                        strength = strength,
                        form = form,
                        dosageInstructions = dosageInstructions,
                        scheduleTimes = times,
                        administrationTimeIds = administrationTimeIds,
                        requireQrScan = requireQrScan,
                        isPrn = isPrn == true,
                        isGroupAdministered = isGrouped,
                        prepackagedGroupId = if (isGrouped) prepackagedGroupId else null,
                        prepackagedGroupName = if (isGrouped) groupName.trim().ifBlank { null } else null,
                        startDate = start,
                        endDate = end,
                        bottleExpiration = expiration,
                        pillCountEntered = pillCount.toIntOrNull(),
                        isException = isException,
                        purpose = purpose.trim().ifBlank { null }
                    )

                    // The repository repeats this check; doing it here means the message lands next to the toggle.
                    if (isGrouped && groupName.isBlank()) {
                        groupError = "Enter a name for the group."
                        return@Button
                    }
                    PrepackagedGroups.validate(medication, patientMedications)?.let {
                        groupError = it
                        return@Button
                    }

                    saving = true
                    scope.launch {
                        try { repository.saveMedicationEdit(existing, medication); onDone() }
                        catch (e: IllegalArgumentException) { saveError = e.message }
                        finally { saving = false }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save medication")
            }

            if (existing != null) {
                if (existing?.discontinuedOn == null) OutlinedButton(onClick = { showDiscontinue = true }) {
                    Text("End medication early")
                } else Text("Discontinued effective ${existing?.discontinuedOn}")
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Delete medication") }
            }
        }
    }

    if (showDiscontinue) {
        AlertDialog(onDismissRequest = { showDiscontinue = false }, title = { Text("Discontinue medication?") },
            text = { Column {
                Text("Warning: no doses will be offered on or after the effective date. The original end date and all administration records will be retained.")
                com.campmeds.app.ui.common.DatePresetField("Effective date (YYYY-MM-DD)", discontinueDate, campSettings.endDates) { discontinueDate = it }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !saving, onClick = {
                val date = runCatching { LocalDate.parse(discontinueDate) }.getOrNull()
                if (date == null) saveError = "Enter a valid date." else {
                    saving = true
                    scope.launch {
                        try { repository.discontinueMedication(existing!!.id, date); showDiscontinue = false; onDone() }
                        catch (e: IllegalArgumentException) { saveError = "Choose today or later, within the medication's date range." }
                        finally { saving = false }
                    }
                }
            }) { Text("Confirm discontinuation") } },
            dismissButton = { TextButton(onClick = { showDiscontinue = false }) { Text("Cancel") } })
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this medication?") },
            text = {
                Text(
                    "It will no longer appear on the dashboard or in the patient's medication list. " +
                        "Every past administration record is kept and still appears in exports."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val id = existing?.id
                    if (id != null) scope.launch { repository.archiveMedication(id); onDone() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

/** The named administration times as selectable chips; several can be selected (multiple daily doses). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowOfChips(
    entries: List<AdministrationTime>,
    selectedIds: List<String>,
    onToggle: (String) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { entry ->
            FilterChip(
                selected = entry.id in selectedIds,
                onClick = { onToggle(entry.id) },
                label = { Text("${entry.name} ${entry.time.format(AdministrationTimeRules.HH_MM)}") }
            )
        }
    }
}

/**
 * The group name suggested for the chosen administration times: their names in the order they are listed,
 * e.g. "Breakfast" or "Breakfast + Dinner". Empty when only custom times are chosen.
 */
internal fun suggestedGroupName(entries: List<AdministrationTime>, selectedIds: List<String>): String =
    entries.filter { it.id in selectedIds }.map { it.name.trim() }.filter { it.isNotEmpty() }
        .distinct().joinToString(" + ")
