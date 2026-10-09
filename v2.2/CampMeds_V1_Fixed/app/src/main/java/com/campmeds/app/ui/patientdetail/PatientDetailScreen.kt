package com.campmeds.app.ui.patientdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campmeds.app.auth.Session
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Role
import com.campmeds.app.repository.CampMedsRepository
import com.campmeds.app.scanner.PatientQr
import com.campmeds.app.ui.common.PatientFormDialog
import com.campmeds.app.ui.common.QrLabelPanel
import kotlinx.coroutines.launch

/**
 * Screen 3 (spec section 4): everything about one patient, reached from the Patients list.
 * This is the ONLY place that shows patient notes/conditions and each medication's purpose
 * ("What is this medication for?"); the dashboard and dosing screens never do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDetailScreen(
    repository: CampMedsRepository,
    patientId: String,
    onAddMedication: () -> Unit,
    onEditMedication: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val patient by repository.observePatient(patientId).collectAsState(initial = null)
    val medications by repository.observeMedicationsForPatient(patientId).collectAsState(initial = emptyList())
    val retired by repository.observeArchivedMedicationsForPatient(patientId).collectAsState(initial = emptyList())

    val currentUser by Session.currentUser.collectAsState()
    val isAdmin = Session.hasAtLeast(currentUser, Role.ADMIN) // observed, so it can't go stale
    val today = java.time.LocalDate.now()

    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showPatientQr by remember { mutableStateOf(false) }
    var qrMedication by remember { mutableStateOf<Medication?>(null) }

    // A deleted (archived) patient is no longer reachable from here.
    LaunchedEffect(patient?.isArchived) { if (patient?.isArchived == true) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(patient?.name ?: "Patient") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (patient != null) {
                        IconButton(onClick = { showPatientQr = true }) {
                            Icon(Icons.Default.QrCode, contentDescription = "Patient QR code")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (isAdmin) {
                FloatingActionButton(
                    onClick = onAddMedication,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add medication")
                }
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                patient?.let { p ->
                    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Name", style = MaterialTheme.typography.labelLarge)
                            Text(p.name)
                            Text("Notes", style = MaterialTheme.typography.labelLarge)
                            Text(p.notes ?: "—")
                            Text("Conditions", style = MaterialTheme.typography.labelLarge)
                            Text(p.conditions ?: "—")
                            if (isAdmin) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { showEdit = true }) { Text("Edit patient") }
                                    OutlinedButton(
                                        onClick = { showDelete = true },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) { Text("Delete patient") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Medications", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenHistory) { Text("View dose history →") }
                }
            }

            if (medications.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No medications on file.")
                    }
                }
            } else {
                items(medications, key = { it.id }) { med ->
                    val isActive = med.startDate <= today && (med.endDate == null || med.endDate >= today)
                    ListItem(
                        headlineContent = { Text("${med.name} — ${med.strength}") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    buildString {
                                        append(med.form).append(" · ").append(med.dosageInstructions)
                                        if (!isActive) append(" · INACTIVE")
                                        if (med.isException) append(" · MANUAL ENTRY")
                                    }
                                )
                                Text("Schedule: ${med.scheduleTimes.joinToString(", ")}")
                                Text("Starts ${med.startDate}" + (med.endDate?.let { " · ends $it" } ?: ""))
                                med.bottleExpiration?.let { Text("Bottle expires $it") }
                                Text(if (med.ndc.isBlank()) "NDC: none" else "NDC: ${med.ndc}")
                                med.purpose?.takeIf { it.isNotBlank() }?.let { Text("For: $it") }
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { qrMedication = med }) {
                                Icon(Icons.Default.QrCode, contentDescription = "Medication QR code")
                            }
                        },
                        modifier = Modifier.clickable(enabled = isAdmin) { onEditMedication(med.id) }
                    )
                    HorizontalDivider()
                }
            }

            if (retired.isNotEmpty()) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Retired medications (history kept)", style = MaterialTheme.typography.titleSmall)
                        retired.forEach { Text("• ${it.name} — ${it.strength}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    if (showEdit) {
        patient?.let { p ->
            PatientFormDialog(
                title = "Edit patient",
                initial = p,
                onDismiss = { showEdit = false },
                onSave = { first, last, notes, conditions ->
                    showEdit = false
                    scope.launch {
                        repository.savePatient(
                            p.copy(firstName = first, lastName = last, notes = notes, conditions = conditions)
                        )
                    }
                }
            )
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete ${patient?.name ?: "patient"}?") },
            text = {
                Text(
                    "The patient and their medications will be removed from the patient list and the dashboard. " +
                        "All past administration records are kept and still appear in exports."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch { repository.archivePatient(patientId) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
        )
    }

    if (showPatientQr) {
        patient?.let { p ->
            AlertDialog(
                onDismissRequest = { showPatientQr = false },
                title = { Text("Patient QR code") },
                text = {
                    QrLabelPanel(
                        qrContent = PatientQr.encode(p.id),
                        lines = listOf(p.name),
                        fileBaseName = "patient_${p.name}"
                    )
                },
                confirmButton = { TextButton(onClick = { showPatientQr = false }) { Text("Close") } }
            )
        }
    }

    qrMedication?.let { med ->
        AlertDialog(
            onDismissRequest = { qrMedication = null },
            title = { Text("Medication QR code") },
            text = {
                QrLabelPanel(
                    qrContent = med.qrCode,
                    lines = listOf(patient?.name.orEmpty(), med.name),
                    fileBaseName = "medication_${patient?.name.orEmpty()}_${med.name}"
                )
            },
            confirmButton = { TextButton(onClick = { qrMedication = null }) { Text("Close") } }
        )
    }
}
