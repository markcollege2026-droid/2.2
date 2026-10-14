package com.campmeds.app.ui.patientlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campmeds.app.auth.Session
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.Role
import com.campmeds.app.repository.CampMedsRepository
import com.campmeds.app.ui.common.PatientFormDialog
import kotlinx.coroutines.launch

/** Screen 2 (spec section 4): search/filter, tap to open a patient. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientListScreen(
    repository: CampMedsRepository,
    onOpenPatient: (String) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var archive by remember { mutableStateOf(false) }
    val rows by remember(archive) {
        if (archive) repository.observeArchive() else repository.observePatients()
    }.collectAsState(initial = emptyList())
    val patients = rows.filter { it.name.contains(query, ignoreCase = true) }
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    val currentUser by Session.currentUser.collectAsState()
    val isAdmin = Session.hasAtLeast(currentUser, Role.ADMIN) // observed, so it can't go stale

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Patients") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        floatingActionButton = {
            if (isAdmin) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add patient")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row {
                FilterChip(selected = !archive, onClick = { archive = false }, label = { Text("Active campers") })
                FilterChip(selected = archive, onClick = { archive = true }, label = { Text("Complete archive / future") })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search patients") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            if (patients.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No patients found.")
                }
            } else {
                LazyColumn {
                    items(patients, key = { it.id }) { patient ->
                        ListItem(
                            headlineContent = { Text(patient.name) },
                            supportingContent = { Text("${patient.startDate ?: "Open start"} — ${patient.endDate ?: "Open end"}" + if (patient.isArchived) " · Manually archived" else "") },
                            modifier = Modifier.clickable { onOpenPatient(patient.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        PatientFormDialog(
            title = "Add patient",
            initial = null,
            onDismiss = { showAddDialog = false },
            repository = repository,
            onSave = { patient ->
                showAddDialog = false
                scope.launch { repository.savePatient(patient) }
            }
        )
    }
}
