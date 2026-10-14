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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.campmeds.app.auth.Session
import com.campmeds.app.domain.isActiveOn
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.Role
import com.campmeds.app.repository.CampMedsRepository
import com.campmeds.app.scanner.PatientQr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The home screen: the names of the patients who have medications due right now — and nothing about the
 * medications themselves (spec section 1). Tapping a patient opens that patient's currently due medications
 * ([PatientDueScreen]), which is where a dose is selected and administered.
 *
 * Which patients appear is derived, never stored: a patient is listed while at least one of their scheduled
 * doses has had its time reached and has no recorded outcome. A patient with medications scheduled only
 * later today does not appear at all, a patient whose only due dose has just been recorded disappears, and
 * a patient who still has another dose due stays. Because it is computed from the clock plus the DoseLogs,
 * restarts and midnight need no special handling.
 *
 * Patient notes/conditions and a medication's purpose are never shown here (they live on the patient's
 * details screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodaysDueScreen(
    repository: CampMedsRepository,
    onOpenPatients: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenPatientDue: (patientId: String) -> Unit,
    onOpenSettings: () -> Unit,
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

    // Reactive: the permission-gated controls follow the live session, not a one-time read.
    val currentUser by Session.currentUser.collectAsState()
    val canAddMedication = Session.hasAtLeast(currentUser, Role.ADMIN)
    val isAdmin = canAddMedication

    var showPatientScan by remember { mutableStateOf(false) }
    var showPatientPicker by remember { mutableStateOf(false) }

    val todaysLogs by remember(today) { repository.observeDoseLogsForDay(today) }
        .collectAsState(initial = emptyList())
    val activeMeds by remember(today) { repository.observeActiveMedications(today) }
        .collectAsState(initial = emptyList())
    val patients by repository.observePatients().collectAsState(initial = emptyList())

    // Derived synchronously from the latest data, so a recorded dose removes its patient immediately when
    // that was their last due medication, and keeps them listed while anything is still due.
    val dueGroups = remember(now, activeMeds, patients, todaysLogs) {
        groupByPatient(buildCurrentDueList(now, activeMeds, patients.associateBy { it.id }, todaysLogs))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Due Now — ${now.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))}") },
                actions = {
                    IconButton(onClick = { showPatientScan = true }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan patient QR")
                    }
                    IconButton(onClick = onOpenPatients) { Icon(Icons.Default.List, contentDescription = "Patients") }
                    if (currentUser != null) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Administration times")
                        }
                    }
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
                    onClick = { showPatientPicker = true },
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
            Text(
                "Tap a patient to see their due medications.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (dueGroups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No medications due right now.")
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(dueGroups, key = { "patient-${it.patient.id}" }) { group ->
                        PatientDueRow(group = group, onOpen = { onOpenPatientDue(group.patient.id) })
                        HorizontalDivider()
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
                showPatientScan = false
                onOpenPatientDue(patient.id) // same as tapping that patient on the dashboard
            }
        )
    }
}

/**
 * One patient on the home screen: the name, how many medications are due, and nothing else. Individual
 * medication names are deliberately not shown here — they belong to the patient's own due screen.
 */
@Composable
private fun PatientDueRow(group: PatientDueGroup, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                group.patient.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${group.items.size} due",
                style = MaterialTheme.typography.labelLarge
            )
            Text("   ›", style = MaterialTheme.typography.titleMedium)
        }
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
                                if (patient == null || !patient.isActiveOn(LocalDate.now())) {
                                    message = "This camper is not active today. Check Archive / future campers to view their records."
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
