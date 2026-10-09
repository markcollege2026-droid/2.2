package com.campmeds.app.ui.duetoday

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.campmeds.app.auth.Session
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.Role
import com.campmeds.app.scanner.PatientQr
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Screen 5 (spec section 4/5): the main dashboard. Doses that are due right now, grouped by patient.
 * Tap a medication → scan QR (or verify manually) → confirm → mark given/held/refused.
 * Tap a patient (or scan their patient QR) to show only that patient's currently due medications.
 *
 * Nothing on this screen shows patient notes/conditions or a medication's purpose; those live only on
 * the patient's details screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodaysDueScreen(
    repository: CampMedsRepository,
    onOpenPatients: () -> Unit,
    onOpenExport: () -> Unit,
    onAddMedication: (patientId: String) -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // "now" (not just the date) is state. Which doses are due depends on the time of day, so it is refreshed
    //  (1) every minute while the screen is shown, (2) on resume / app returning to foreground, and
    //  (3) when the system date/time/timezone changes. This also covers V1 bug 7 (rolling over midnight).
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

    // Reactive: the Add Medication button and Export follow the live session, not a one-time read.
    val currentUser by Session.currentUser.collectAsState()
    val canAddMedication = Session.hasAtLeast(currentUser, Role.ADMIN)
    val isAdmin = canAddMedication

    var selectedItem by remember { mutableStateOf<DueDoseItem?>(null) }
    var selectedPatientId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPatientScan by remember { mutableStateOf(false) }
    var showPatientPicker by remember { mutableStateOf(false) }

    val todaysLogs by remember(today) { repository.observeDoseLogsForDay(today) }
        .collectAsState(initial = emptyList())
    val activeMeds by remember(today) { repository.observeActiveMedications(today) }
        .collectAsState(initial = emptyList())
    val patients by repository.observePatients().collectAsState(initial = emptyList())

    // Derived synchronously from the latest data, so a recorded dose disappears the moment its log is saved.
    val groups = remember(now, activeMeds, patients, todaysLogs) {
        groupByPatient(buildCurrentDueList(now, activeMeds, patients.associateBy { it.id }, todaysLogs))
    }
    val selectedPatient = patients.firstOrNull { it.id == selectedPatientId }
    val visibleGroups = if (selectedPatient != null) groups.filter { it.patient.id == selectedPatient.id } else groups

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Due Now — ${now.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))}") },
                actions = {
                    IconButton(onClick = { showPatientScan = true }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan patient QR")
                    }
                    IconButton(onClick = onOpenPatients) { Icon(Icons.Default.List, contentDescription = "Patients") }
                    if (isAdmin) {
                        IconButton(onClick = onOpenExport) { Icon(Icons.Default.Upload, contentDescription = "Export") }
                    }
                    IconButton(onClick = { Session.logout(); onLogout() }) {
                        Icon(Icons.Default.Logout, contentDescription = "Log out")
                    }
                }
            )
        },
        floatingActionButton = {
            if (canAddMedication) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val target = selectedPatient
                        if (target != null) onAddMedication(target.id) else showPatientPicker = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add Medication") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            currentUser?.let {
                Text(
                    "Logged in as ${it.name} (${it.role})",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (selectedPatient != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Showing ${selectedPatient.name}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { selectedPatientId = null }) { Text("Show all patients") }
                }
            }

            if (visibleGroups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (selectedPatient != null) "No medications due right now for ${selectedPatient.name}."
                        else "No medications due right now."
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    visibleGroups.forEach { group ->
                        item(key = "patient-${group.patient.id}") {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth().clickable { selectedPatientId = group.patient.id }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        group.patient.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("${group.items.size} due", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        items(group.items, key = { "${it.medication.id}-${it.scheduledFor}" }) { item ->
                            ListItem(
                                headlineContent = { Text(item.medication.name) },
                                supportingContent = {
                                    Text("${item.scheduledFor.toLocalTime()} · ${item.medication.dosageInstructions}")
                                },
                                trailingContent = {
                                    Text("DUE", color = MaterialTheme.colorScheme.error)
                                },
                                modifier = Modifier.clickable { selectedItem = item }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showPatientPicker) {
        AlertDialog(
            onDismissRequest = { showPatientPicker = false },
            title = { Text("Add medication for which patient?") },
            text = {
                if (patients.isEmpty()) {
                    Text("There are no patients yet. Add a patient from the Patients screen first.")
                } else {
                    LazyColumn {
                        items(patients, key = { it.id }) { patient ->
                            ListItem(
                                headlineContent = { Text(patient.name) },
                                modifier = Modifier.clickable {
                                    showPatientPicker = false
                                    onAddMedication(patient.id)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPatientPicker = false }) { Text("Cancel") } }
        )
    }

    if (showPatientScan) {
        PatientScanSheet(
            repository = repository,
            onDismiss = { showPatientScan = false },
            onPatientFound = { patient ->
                selectedPatientId = patient.id // same as tapping that patient on the dashboard
                showPatientScan = false
            }
        )
    }

    selectedItem?.let { item ->
        DoseActionSheet(
            item = item,
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
            },
            repository = repository
        )
    }
}

/** Scans a patient QR code and reports that patient; shows a clear message for anything else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatientScanSheet(
    repository: CampMedsRepository,
    onDismiss: () -> Unit,
    onPatientFound: (Patient) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    var message by remember { mutableStateOf<String?>(null) }
    // The scanner reports one code per instance; bumping the key starts a fresh scanner after a bad scan.
    var scanKey by remember { mutableStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scan a patient QR code", style = MaterialTheme.typography.titleMedium)
            if (!hasCameraPermission) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera permission to scan")
                }
            } else {
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    key(scanKey) {
                        ScannerView(onScanned = { code ->
                            scope.launch {
                                val patientId = PatientQr.parse(code)
                                if (patientId == null) {
                                    message = "That is not a patient QR code (it may be a medication label)."
                                    return@launch
                                }
                                val patient = repository.getPatient(patientId)
                                if (patient == null || patient.isArchived) {
                                    message = "This patient was not found. They may have been deleted."
                                } else {
                                    onPatientFound(patient)
                                }
                            }
                        })
                    }
                }
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { message = null; scanKey++ }) { Text("Scan again") }
            }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

/**
 * Bottom-sheet dosing flow: scan (or verify manually) → explicitly verify the physical medication →
 * mark outcome.
 *
 * Override rules (spec section 6): a scan that doesn't match the due medication, or an expired bottle,
 * blocks the outcome buttons unless the user is LEVEL1_OVERRIDE or ADMIN. Proceeding that way is
 * recorded on the DoseLog as wasOverride = true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoseActionSheet(
    item: DueDoseItem,
    repository: CampMedsRepository,
    onDismiss: () -> Unit,
    onRecord: (DoseStatus, wasOverride: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(DoseFlowMode.CONFIRM_SCAN) }
    var scanned by remember { mutableStateOf<Medication?>(null) }
    var scannedUnknown by remember { mutableStateOf(false) }
    var verifiedPhysically by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    val med = item.medication
    val canOverride = Session.hasAtLeast(Role.LEVEL1_OVERRIDE)
    val mismatch = scannedUnknown || (scanned != null && scanned!!.id != med.id)
    // BUG FIX (V1 bug 12): an expired bottle is never treated as normal.
    val expired = med.bottleExpiration?.isBefore(LocalDate.now()) == true
    val needsOverride = mismatch || expired
    val allowed = !needsOverride || canOverride

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${item.patient.name} — ${med.name}", style = MaterialTheme.typography.titleMedium)
            Text("Due ${item.scheduledFor.toLocalTime()} · ${med.dosageInstructions}")

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
                    if (!hasCameraPermission) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant camera permission to scan")
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(280.dp)) {
                            ScannerView(onScanned = { code ->
                                scope.launch {
                                    val found = repository.getMedicationByQr(code)
                                    scanned = found
                                    scannedUnknown = found == null
                                    mode = DoseFlowMode.MARK_OUTCOME
                                }
                            })
                        }
                    }
                    // BUG FIX (V1 bug 10): this used to say "select manually" but selected nothing. It now says
                    // what it really does: skip the scan and verify the due medication by eye.
                    TextButton(onClick = {
                        scanned = null; scannedUnknown = false
                        mode = DoseFlowMode.MARK_OUTCOME
                    }) { Text("Can't scan — verify manually") }
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
                        else -> Text("Scan skipped — manual verification required.")
                    }
                    if (needsOverride && !canOverride) {
                        Text(
                            "Only a Level 1 override or Admin user can proceed past this warning.",
                            color = MaterialTheme.colorScheme.error
                        )
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

private enum class DoseFlowMode { CONFIRM_SCAN, MARK_OUTCOME }
