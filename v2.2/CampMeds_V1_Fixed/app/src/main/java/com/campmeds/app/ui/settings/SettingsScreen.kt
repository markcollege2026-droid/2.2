package com.campmeds.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campmeds.app.auth.Session
import com.campmeds.app.data.entity.AdministrationTime
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.Role
import com.campmeds.app.domain.AdministrationTimeRules
import com.campmeds.app.repository.AdministrationTimeUsage
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Settings: the shared list of named administration times (spec section 3).
 *
 * Only administrators may change the list; everyone else sees it read-only, and the medication form uses it
 * as the dropdown of administration times. Every entry has a name and a real time — the time is what
 * medication schedules are calculated from.
 *
 * Data safety when an administrator edits or removes an entry that is already in use:
 *  - renaming an entry only relabels the medications that use it; no schedule changes;
 *  - changing its time asks for confirmation first, then moves exactly those scheduled times, and never
 *    rewrites a DoseLog (past records keep the time their dose was scheduled for);
 *  - removing an entry asks for confirmation, keeps every medication's times and turns them into custom
 *    times, so no dose can be lost or silently moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: CampMedsRepository,
    onBack: () -> Unit,
    onOpenImport: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val currentUser by Session.currentUser.collectAsState()
    val isAdmin = Session.hasAtLeast(currentUser, Role.ADMIN)

    val entries by repository.observeAdministrationTimes().collectAsState(initial = emptyList())
    val patients by repository.observePatients().collectAsState(initial = emptyList())
    val patientsById = remember(patients) { patients.associateBy { it.id } }

    var editorTarget by remember { mutableStateOf<AdministrationTime?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingRetime by remember { mutableStateOf<PendingRetime?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Administration times") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        floatingActionButton = {
            if (isAdmin) {
                ExtendedFloatingActionButton(
                    onClick = { editorTarget = null; showEditor = true; error = null },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add time") }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Text(
                    "Named administration times are chosen when a medication is registered or edited, so staff " +
                        "never have to type a time by hand. The time below is what the due list uses.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
            if (isAdmin) {
                item {
                    OutlinedButton(
                        onClick = onOpenImport,
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    ) { Text("Import spreadsheet (.xlsx)") }
                    Text(
                        "Import patients and medications from Excel, or update records using a CampMeds template. " +
                            "Every import is previewed and needs your confirmation.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()
                }
            }
            if (!isAdmin) {
                item {
                    Text(
                        "Only an administrator can add, change or remove these times.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            if (entries.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (isAdmin) "No administration times yet. Add the first one (for example Breakfast at 08:00)."
                            else "No administration times have been set up yet."
                        )
                    }
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = { Text(entry.time.format(AdministrationTimeRules.HH_MM)) },
                        trailingContent = {
                            if (isAdmin) {
                                Row {
                                    IconButton(onClick = { editorTarget = entry; showEditor = true; error = null }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit ${entry.name}")
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            pendingDelete = PendingDelete(entry, repository.administrationTimeUsage(entry.id))
                                        }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove ${entry.name}")
                                    }
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showEditor) {
        AdministrationTimeDialog(
            existing = editorTarget,
            others = entries.filter { it.id != editorTarget?.id },
            error = error,
            onDismiss = { showEditor = false },
            onSave = { name, time ->
                val validation = AdministrationTimeRules.validateName(name, entries.filter { it.id != editorTarget?.id })
                if (validation != null) {
                    error = validation
                } else {
                    val target = editorTarget
                    error = null
                    if (target != null && target.time != time) {
                        scope.launch {
                            val usage = repository.administrationTimeUsage(target.id)
                            if (usage.doses > 0) {
                                showEditor = false
                                pendingRetime = PendingRetime(target, name.trim(), time, usage)
                            } else {
                                showEditor = false
                                repository.saveAdministrationTime(target, name, time)
                            }
                        }
                    } else {
                        showEditor = false
                        scope.launch { repository.saveAdministrationTime(target, name, time) }
                    }
                }
            }
        )
    }

    pendingRetime?.let { change ->
        AlertDialog(
            onDismissRequest = { pendingRetime = null },
            title = { Text("Change \"${change.entry.name}\" to ${change.time.format(AdministrationTimeRules.HH_MM)}?") },
            text = {
                Column {
                    Text(
                        "${change.usage.doses} scheduled dose(s) on ${change.usage.medications.size} current " +
                            "medication(s) use this time: " +
                            patientNames(change.usage.medications, patientsById)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Those schedules will move to the new time. Administration records that already exist " +
                            "are not changed — history keeps the time each dose was scheduled for."
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = change.entry
                    pendingRetime = null
                    scope.launch { repository.saveAdministrationTime(target, change.newName, change.time) }
                }) { Text("Change time") }
            },
            dismissButton = { TextButton(onClick = { pendingRetime = null }) { Text("Cancel") } }
        )
    }

    pendingDelete?.let { removal ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove \"${removal.entry.name}\"?") },
            text = {
                Column {
                    if (removal.usage.doses == 0) {
                        Text("No medication schedule uses this time.")
                    } else {
                        Text(
                            "${removal.usage.doses} scheduled dose(s) on ${removal.usage.medications.size} current " +
                                "medication(s) use this time: " +
                                patientNames(removal.usage.medications, patientsById)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Those medications keep their current times — they simply stop being labelled with a " +
                                "name. No dose is removed and no time is moved."
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val entry = removal.entry
                    pendingDelete = null
                    scope.launch { repository.deleteAdministrationTime(entry.id) }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

private data class PendingRetime(
    val entry: AdministrationTime,
    val newName: String,
    val time: LocalTime,
    val usage: AdministrationTimeUsage
)

private data class PendingDelete(val entry: AdministrationTime, val usage: AdministrationTimeUsage)

private fun patientNames(medications: List<Medication>, patientsById: Map<String, Patient>): String =
    medications.mapNotNull { patientsById[it.patientId]?.name }.distinct().sorted().joinToString(", ")

/** Name + real time of one entry. The time is picked with the Material 3 time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdministrationTimeDialog(
    existing: AdministrationTime?,
    others: List<AdministrationTime>,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, time: LocalTime) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    val pickerState = rememberTimePickerState(
        initialHour = existing?.time?.hour ?: 8,
        initialMinute = existing?.time?.minute ?: 0,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add administration time" else "Edit administration time") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. Breakfast)") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Time", style = MaterialTheme.typography.labelLarge)
                TimePicker(state = pickerState, layoutType = TimePickerLayoutType.Vertical)
                Text(
                    "Times are 24-hour, like the rest of the app (08:00, 12:00, 18:00, 21:00).",
                    style = MaterialTheme.typography.bodySmall
                )
                if (others.isNotEmpty()) {
                    Text(
                        "Already configured: " + others.joinToString(", ") {
                            "${it.name} ${it.time.format(AdministrationTimeRules.HH_MM)}"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, LocalTime.of(pickerState.hour, pickerState.minute)) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
