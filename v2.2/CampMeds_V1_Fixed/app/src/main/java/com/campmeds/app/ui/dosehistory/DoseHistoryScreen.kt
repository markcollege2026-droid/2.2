package com.campmeds.app.ui.dosehistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.repository.CampMedsRepository
import java.time.format.DateTimeFormatter

/**
 * Screen 6 (spec section 4/8): per patient, chronological log of DoseLog entries.
 * Read-only in this version — no correction/edit trail yet, per spec section 8.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoseHistoryScreen(
    repository: CampMedsRepository,
    patientId: String,
    onBack: () -> Unit
) {
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d, h:mm a") }
    var patientName by remember { mutableStateOf("Dose History") }
    var medsById by remember { mutableStateOf<Map<String, Medication>>(emptyMap()) }

    LaunchedEffect(patientId) {
        repository.getPatient(patientId)?.let { patientName = it.name }
    }

    val logs by repository.observeHistoryForPatient(patientId).collectAsState(initial = emptyList())

    LaunchedEffect(logs) {
        val ids = logs.map { it.medicationId }.toSet()
        medsById = ids.mapNotNull { repository.getMedication(it) }.associateBy { it.id }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$patientName — History") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No dose history yet.")
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(logs, key = { it.id }) { log: DoseLog ->
                    val med = medsById[log.medicationId]
                    ListItem(
                        headlineContent = { Text(med?.name ?: "Unknown medication") },
                        supportingContent = {
                            Column {
                                Text("Scheduled: ${log.scheduledFor.format(fmt)}")
                                Text("Logged: ${log.loggedAt.format(fmt)}")
                                if (log.wasOverride) Text("⚠ Recorded via override")
                            }
                        },
                        trailingContent = { Text(log.status.name) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
