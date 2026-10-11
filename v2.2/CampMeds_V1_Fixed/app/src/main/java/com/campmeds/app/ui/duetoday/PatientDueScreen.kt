package com.campmeds.app.ui.duetoday

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.campmeds.app.auth.Session
import com.campmeds.app.data.entity.AdministrationTime
import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.Role
import com.campmeds.app.domain.AdministrationTimeRules
import com.campmeds.app.domain.MedicationSafeguards
import com.campmeds.app.domain.PlannedDose
import com.campmeds.app.domain.PrepackagedGroups
import com.campmeds.app.repository.CampMedsRepository
import com.campmeds.app.ui.theme.CampAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The due list of ONE patient: what the dashboard opens when a patient's name is tapped (spec section 1 —
 * the dashboard shows patients, not medications).
 *
 * Rows are the patient's currently due medications and, separately, their prepackaged groups, which can be
 * recorded with a single "Given" action ([GroupAdministrationSheet]) while still creating one administration
 * record per medication. Everything here is derived from the clock, the schedule and the DoseLogs, so a dose
 * disappears from this screen the moment it is recorded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDueScreen(
    repository: CampMedsRepository,
    patientId: String,
    onBack: () -> Unit,
    onOpenPatientDetails: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val today = now.toLocalDate()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) now = LocalDateTime.now()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { now = LocalDateTime.now() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val current = LocalDateTime.now()
            now = current
            val nextMinute = current.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            delay(Duration.between(current, nextMinute).toMillis() + 200)
        }
    }

    val patient by repository.observePatient(patientId).collectAsState(initial = null)
    val todaysLogs by remember(today) { repository.observeDoseLogsForDay(today) }
        .collectAsState(initial = emptyList())
    val activeMeds by remember(today) { repository.observeActiveMedications(today) }
        .collectAsState(initial = emptyList())
    val administrationTimes by repository.observeAdministrationTimes().collectAsState(initial = emptyList())
    val definitionsById = remember(administrationTimes) { administrationTimes.associateBy { it.id } }
    val currentUser by Session.currentUser.collectAsState()

    val entries = remember(now, activeMeds, todaysLogs, patient) {
        val currentPatient = patient ?: return@remember emptyList()
        dueEntriesForPatient(
            buildCurrentDueList(
                now = now,
                activeMedications = activeMeds.filter { it.patientId == patientId },
                patientsById = mapOf(currentPatient.id to currentPatient),
                todaysLogs = todaysLogs
            )
        )
    }

    var selectedItem by remember { mutableStateOf<DueDoseItem?>(null) }
    var selectedGroup by remember { mutableStateOf<PatientDueEntry.Prepackaged?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${patient?.name ?: "Patient"} — due now") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onOpenPatientDetails) {
                        Icon(Icons.Default.Person, contentDescription = "Patient details")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                now.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No medications due right now for ${patient?.name ?: "this patient"}.\n" +
                            "Doses appear here at their scheduled time."
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(entries, key = { entry -> entryKey(entry) }) { entry ->
                        when (entry) {
                            is PatientDueEntry.Single -> SingleDoseRow(
                                item = entry.item,
                                definitions = definitionsById,
                                onOpen = { selectedItem = entry.item }
                            )
                            is PatientDueEntry.Prepackaged -> PrepackagedGroupCard(
                                entry = entry,
                                onOpen = { selectedGroup = entry }
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val currentPatient = patient
    selectedItem?.let { item ->
        DoseActionSheet(
            item = item,
            repository = repository,
            definitions = definitionsById,
            onDismiss = { selectedItem = null },
            onRecord = { status, wasOverride ->
                val user = currentUser ?: return@DoseActionSheet
                scope.launch {
                    repository.recordDose(
                        medicationId = item.medication.id,
                        scheduledFor = item.scheduledFor,
                        status = status,
                        loggedByUserId = user.id,
                        wasOverride = wasOverride
                    )
                    selectedItem = null
                }
            }
        )
    }

    if (selectedGroup != null && currentPatient != null) {
        val group = selectedGroup!!
        GroupAdministrationSheet(
            group = group,
            patient = currentPatient,
            now = now,
            activeMedications = activeMeds.filter { it.patientId == patientId },
            todaysLogs = todaysLogs,
            repository = repository,
            definitions = definitionsById,
            onDismiss = { selectedGroup = null },
            onConfirm = { doses ->
                val user = currentUser ?: return@GroupAdministrationSheet
                scope.launch {
                    // One action, one administration record per medication dose (see the repository).
                    repository.recordGroupAdministration(doses, user.id)
                    selectedGroup = null
                }
            }
        )
    }
}

private fun entryKey(entry: PatientDueEntry): String = when (entry) {
    is PatientDueEntry.Single -> "dose-${entry.item.medication.id}-${entry.item.scheduledFor}"
    is PatientDueEntry.Prepackaged -> "group-${
        entry.items.joinToString("|") { it.medication.id }
    }"
}

/** One medication dose, with the administration times it was scheduled from (names, not just clock times). */
@Composable
private fun SingleDoseRow(
    item: DueDoseItem,
    definitions: Map<String, AdministrationTime>,
    onOpen: () -> Unit
) {
    val med = item.medication
    val index = med.scheduleTimes.indexOf(item.scheduledFor.toLocalTime())
    val label = if (index >= 0) {
        AdministrationTimeRules.labelFor(
            item.scheduledFor.toLocalTime(),
            definitions[AdministrationTimeRules.definitionIdAt(med, index)]
        )
    } else {
        AdministrationTimeRules.HH_MM.format(item.scheduledFor.toLocalTime())
    }

    ListItem(
        headlineContent = { Text(med.name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$label · ${item.scheduledFor.toLocalTime()} · ${med.dosageInstructions}")
                if (MedicationSafeguards.isExpired(med)) {
                    Text("⚠️ Expired bottle", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = {
            Text(
                if (med.requireQrScan) "SCAN + DUE" else "DUE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error
            )
        },
        modifier = Modifier.clickable { onOpen() }
    )
}

/** One prepackaged group: which medications it contains, and the single action that records them all. */
@Composable
private fun PrepackagedGroupCard(entry: PatientDueEntry.Prepackaged, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Prepackaged group — ${entry.items.map { it.medication.id }.distinct().size} medications together",
                style = MaterialTheme.typography.titleSmall
            )
            entry.items.forEach { item ->
                Text(
                    "• ${item.medication.name} — ${item.medication.strength} (${AdministrationTimeRules.HH_MM.format(item.scheduledFor.toLocalTime())})",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                "Due  ${entry.items.minOf { it.scheduledFor }.toLocalTime()} · give them together with one action.",
                style = MaterialTheme.typography.labelMedium
            )
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Give group (one action)")
            }
        }
    }
}

/**
 * The "Can't scan" control, deliberately the first thing on the scanner: full width, amber so it stands out
 * against the dark camera preview and the sheet, above the preview instead of below it, and small enough for
 * a small phone screen. It keeps its original meaning — skip the scan and verify the medication by eye.
 */
@Composable
private fun CanNotScanButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = CampAmber, contentColor = Color(0xFF1B1B1B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Warning, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Can't scan — verify manually", fontWeight = FontWeight.SemiBold)
    }
}

/** Camera permission + live preview, shared by the dosing sheet and the group sheet. */
@Composable
private fun QrScanStep(onCode: (String) -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    if (!hasCameraPermission) {
        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
            Text("Grant camera permission to scan")
        }
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            ScannerView(onScanned = onCode)
        }
    }
}

/**
 * Bottom-sheet dosing flow: scan (or verify manually) → explicitly verify the physical medication → mark
 * outcome.
 *
 * The scan step is only shown when the medication asks for it (the per-medication "Require QR scan when
 * selecting this medication" setting); everything else — the explicit physical verification, the expired
 * bottle block and the override rules — is unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoseActionSheet(
    item: DueDoseItem,
    repository: CampMedsRepository,
    definitions: Map<String, AdministrationTime>,
    onDismiss: () -> Unit,
    onRecord: (DoseStatus, wasOverride: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val med = item.medication
    val requiresScan = med.requireQrScan

    var mode by remember { mutableStateOf(if (requiresScan) DoseFlowMode.CONFIRM_SCAN else DoseFlowMode.MARK_OUTCOME) }
    var scanned by remember { mutableStateOf<Medication?>(null) }
    var scannedUnknown by remember { mutableStateOf(false) }
    var verifiedPhysically by remember { mutableStateOf(false) }

    val canOverride = Session.hasAtLeast(Role.LEVEL1_OVERRIDE)
    val mismatch = scannedUnknown || (scanned != null && scanned?.id != med.id)
    val expired = MedicationSafeguards.isExpired(med)
    val needsOverride = mismatch || expired
    val allowed = !needsOverride || canOverride

    val index = med.scheduleTimes.indexOf(item.scheduledFor.toLocalTime())
    val label = if (index >= 0) {
        AdministrationTimeRules.labelFor(
            item.scheduledFor.toLocalTime(),
            definitions[AdministrationTimeRules.definitionIdAt(med, index)]
        )
    } else {
        AdministrationTimeRules.HH_MM.format(item.scheduledFor.toLocalTime())
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("${item.patient.name} — ${med.name}", style = MaterialTheme.typography.titleMedium)
            Text("$label · due ${item.scheduledFor.toLocalTime()} · ${med.dosageInstructions}")

            if (expired) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "⚠️ EXPIRED: this bottle expired on ${med.bottleExpiration}. Do not use unless cleared by an authorized override.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            when (mode) {
                DoseFlowMode.CONFIRM_SCAN -> {
                    // First control on the screen, with a contrasting background (spec section 5). It keeps
                    // doing exactly what it always did: skip the scan, verify the medication by eye.
                    CanNotScanButton(onClick = {
                        scanned = null
                        scannedUnknown = false
                        mode = DoseFlowMode.MARK_OUTCOME
                    })
                    Text(
                        "Scan the QR code on ${med.name}'s label, or use the button above.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    QrScanStep(onCode = { code ->
                        scope.launch {
                            val found = repository.getMedicationByQr(code)
                            scanned = found
                            scannedUnknown = found == null
                            mode = DoseFlowMode.MARK_OUTCOME
                        }
                    })
                }

                DoseFlowMode.MARK_OUTCOME -> {
                    when {
                        scannedUnknown -> Text(
                            "⚠️ Scanned code is not recognized. It does not match this dose.",
                            color = MaterialTheme.colorScheme.error
                        )
                        mismatch -> Text(
                            "⚠️ Scanned item is a different medication (${scanned?.name} — ${scanned?.strength}) than the one due for this dose.",
                            color = MaterialTheme.colorScheme.error
                        )
                        scanned != null -> Text("✓ Scan matches this dose.", color = MaterialTheme.colorScheme.primary)
                        requiresScan -> Text("Scan skipped — manual verification required.")
                        else -> Text(
                            "This medication is set to not require a QR scan — verify the details below.",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (needsOverride && !canOverride) {
                        Text(
                            "Only a Level 1 override or Admin user can proceed past this warning.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (requiresScan) {
                        // Still one tap away from scanning if the label turns up.
                        TextButton(onClick = {
                            scanned = null; scannedUnknown = false; mode = DoseFlowMode.CONFIRM_SCAN
                        }) { Text("Scan instead") }
                    }

                    // Details the provider must compare with the physical medication in hand.
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("Compare with the physical medication:", style = MaterialTheme.typography.labelLarge)
                            Text("Patient: ${item.patient.name}")
                            Text("Medication: ${med.name}")
                            Text("Strength: ${med.strength}")
                            Text("Form: ${med.form}")
                            Text("Dosage: ${med.dosageInstructions}")
                            med.bottleExpiration?.let { Text("Bottle expiration: $it") }
                        }
                    }

                    // BUG FIX (V1 bug 11): explicit confirmation before any outcome can be recorded.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = verifiedPhysically, onCheckedChange = { verifiedPhysically = it })
                        Text(
                            "I verified the medication name, strength, and form against the physical medication.",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val enabled = verifiedPhysically && allowed
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = enabled, onClick = { onRecord(DoseStatus.GIVEN, needsOverride) }) { Text("Given") }
                        OutlinedButton(enabled = enabled, onClick = { onRecord(DoseStatus.HELD, needsOverride) }) { Text("Held") }
                        OutlinedButton(enabled = enabled, onClick = { onRecord(DoseStatus.REFUSED, needsOverride) }) { Text("Refused") }
                    }
                }
            }

            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

/**
 * One-action administration for a prepackaged group (spec section 4.1).
 *
 * The sheet shows every medication of the group before anything is recorded, with what will happen to it:
 *  - doses that are due right now are ticked and recorded as Given, one DoseLog each;
 *  - a dose scheduled for later today is listed but never recorded;
 *  - a medication that was already recorded today is listed as it stands and is not changed;
 *  - unticking a medication leaves it on the patient's due list so it can be handled on its own
 *    (refused, held, unavailable), and it is never marked as Given by the group action.
 *
 * If any medication due in the group is set to require a QR scan, the group must be scanned first (one scan
 * covers the package), so the existing identification safeguard is not lost by using the group action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupAdministrationSheet(
    group: PatientDueEntry.Prepackaged,
    patient: Patient,
    now: LocalDateTime,
    activeMedications: List<Medication>,
    todaysLogs: List<DoseLog>,
    repository: CampMedsRepository,
    definitions: Map<String, AdministrationTime>,
    onDismiss: () -> Unit,
    onConfirm: (List<PlannedDose>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val members = remember(group.groupId, activeMedications) {
        PrepackagedGroups.membersOf(activeMedications, group.groupId, patient.id)
    }
    val plans = remember(now, members, todaysLogs) { planGroupMembers(now, members, patient, todaysLogs) }

    val canOverride = Session.hasAtLeast(Role.LEVEL1_OVERRIDE)
    val duePlans = plans.filter { it.dueNow }
    val scanningRequired = duePlans.any { it.medication.requireQrScan }

    var mode by remember { mutableStateOf(if (scanningRequired) DoseFlowMode.CONFIRM_SCAN else DoseFlowMode.MARK_OUTCOME) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var scanSkipped by remember { mutableStateOf(false) }
    var scanKey by remember { mutableStateOf(0) }
    var excluded by remember { mutableStateOf(emptySet<String>()) }
    var verifiedPhysically by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    val selected = plans.filter { it.dueNow && it.medication.id !in excluded }
    val blocked = selected.filter { MedicationSafeguards.isExpired(it.medication, now.toLocalDate()) }
    val allowed = blocked.isEmpty() || canOverride

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("${patient.name} — prepackaged group", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every selected medication gets its own administration record. Doses that are not due yet are never recorded.",
                style = MaterialTheme.typography.bodySmall
            )

            when (mode) {
                DoseFlowMode.CONFIRM_SCAN -> {
                    CanNotScanButton(onClick = {
                        scanMessage = null
                        scanSkipped = true
                        mode = DoseFlowMode.MARK_OUTCOME
                    })
                    scanMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    // A medication in this group is set to require a scan, so one scan of the package is
                    // required before the group can be recorded. key(scanKey) restarts the scanner so a
                    // wrong label can be scanned again.
                    key(scanKey) {
                        QrScanStep(onCode = { code ->
                            scope.launch {
                                val found = repository.getMedicationByQr(code)
                                when {
                                    found == null -> scanMessage = "This code is not a medication label."
                                    found.id !in members.map { it.id } ->
                                        scanMessage = "That label is ${found.name}, which is not in this group."
                                    else -> {
                                        scanMessage = null
                                        mode = DoseFlowMode.MARK_OUTCOME
                                    }
                                }
                                scanKey++
                            }
                        })
                    }
                }

                DoseFlowMode.MARK_OUTCOME -> {
                    if (scanMessage != null) Text(scanMessage!!, color = MaterialTheme.colorScheme.error)
                    if (scanSkipped) {
                        Text(
                            "Scan skipped — verify each medication by eye before recording.",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Medications in this group:", style = MaterialTheme.typography.labelLarge)
                            plans.forEach { plan ->
                                GroupMemberRow(
                                    plan = plan,
                                    definitions = definitions,
                                    canOverride = canOverride,
                                    checked = plan.medication.id !in excluded,
                                    onCheckedChange = { checked ->
                                        excluded = if (checked) excluded - plan.medication.id
                                        else excluded + plan.medication.id
                                    }
                                )
                            }
                        }
                    }

                    if (plans.any { it.nextDoseAt != null }) {
                        Text(
                            "Later doses in this group are not due yet and are not recorded by this action.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (blocked.isNotEmpty() && !canOverride) {
                        Text(
                            "An expired medication is selected. Untick it, or ask a Level 1 override / Admin user.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = verifiedPhysically, onCheckedChange = { verifiedPhysically = it })
                        Text(
                            "I verified every medication above against the physical prepackaged medications.",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        enabled = selected.isNotEmpty() && verifiedPhysically && allowed && !submitting,
                        onClick = {
                            // Guard against a double tap: the button locks and the repository refuses to
                            // record a dose that already has an outcome.
                            submitting = true
                            val doses = selected.flatMap { plan ->
                                plan.dueItems.map { dose ->
                                    PlannedDose(
                                        medicationId = dose.medication.id,
                                        scheduledFor = dose.scheduledFor,
                                        wasOverride = MedicationSafeguards.isExpired(dose.medication, now.toLocalDate())
                                    )
                                }
                            }
                            onConfirm(doses)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (selected.isEmpty()) "Nothing due to record"
                            else "Given — record ${selected.sumOf { it.dueItems.size }} dose(s) for ${selected.size} medication(s)"
                        )
                    }
                }
            }

            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

/** One line of the group sheet: what the medication is, and exactly what the group action will do to it. */
@Composable
private fun GroupMemberRow(
    plan: GroupMemberPlan,
    definitions: Map<String, AdministrationTime>,
    canOverride: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val med = plan.medication
    val expired = MedicationSafeguards.isExpired(med)
    val timeIndex = med.scheduleTimes.indexOf(plan.dueItems.firstOrNull()?.scheduledFor?.toLocalTime())
    val dueLabel = plan.dueItems.firstOrNull()?.let { due ->
        if (timeIndex >= 0) {
            AdministrationTimeRules.labelFor(
                due.scheduledFor.toLocalTime(),
                definitions[AdministrationTimeRules.definitionIdAt(med, timeIndex)]
            )
        } else {
            AdministrationTimeRules.HH_MM.format(due.scheduledFor.toLocalTime())
        }
    }

    val status = when {
        plan.dueNow -> "Due $dueLabel — recorded as Given"
        plan.nextDoseAt != null -> "Not due until ${AdministrationTimeRules.HH_MM.format(plan.nextDoseAt.toLocalTime())} — not recorded"
        plan.alreadyRecorded != null -> "Already recorded: ${plan.alreadyRecorded} — not changed"
        else -> "No dose scheduled for today — not recorded"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked && plan.dueNow,
            enabled = plan.dueNow && (!expired || canOverride),
            onCheckedChange = onCheckedChange
        )
        Column(Modifier.weight(1f)) {
            Text("${med.name} — ${med.strength}", style = MaterialTheme.typography.bodyLarge)
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (expired) {
                Text(
                    if (canOverride) "⚠️ Expired bottle — recorded as an override" else "⚠️ Expired bottle — needs an override to include",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (med.requireQrScan && plan.dueNow) Text("Requires a QR scan", style = MaterialTheme.typography.bodySmall)
        }
    }
}

enum class DoseFlowMode { CONFIRM_SCAN, MARK_OUTCOME }
