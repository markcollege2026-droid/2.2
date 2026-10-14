package com.campmeds.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.domain.counselorsForBunks

/** Create (initial = null) or edit a patient: first name, last name, notes, conditions. */
@Composable
fun PatientFormDialog(
    title: String,
    initial: Patient?,
    onDismiss: () -> Unit,
    repository: com.campmeds.app.repository.CampMedsRepository,
    onSave: (Patient) -> Unit
) {
    var first by remember { mutableStateOf(initial?.firstName.orEmpty()) }
    var last by remember { mutableStateOf(initial?.lastName.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var conditions by remember { mutableStateOf(initial?.conditions.orEmpty()) }

    val settings by repository.observeSettings().collectAsState(initial = com.campmeds.app.data.entity.CampSettings())
    var start by remember { mutableStateOf(initial?.startDate?.toString().orEmpty()) }
    var end by remember { mutableStateOf(initial?.endDate?.toString().orEmpty()) }
    val bunkOptions by repository.observeBunks().collectAsState(initial = emptyList())
    val divisions by repository.observeDivisions().collectAsState(initial = emptyList())
    val sessions by repository.observeSessions().collectAsState(initial = emptyList())
    var bunks by remember { mutableStateOf(initial?.bunks.orEmpty()) }
    var counselors by remember { mutableStateOf(initial?.counselors.orEmpty()) }
    var division by remember { mutableStateOf(initial?.division.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SessionPresetField(sessions) { start = it.startDate.toString(); end = it.endDate.toString() }
                DatePresetField("Camper start date (optional, YYYY-MM-DD)", start, settings.startDates) { start = it }
                DatePresetField("Camper end date (inclusive, YYYY-MM-DD)", end, settings.endDates) { end = it }
                Text("Before the start and after the end date this camper is in Archive / future campers. Blank dates are unrestricted.")
                if (settings.bunkEnabled) MultiNameField(settings.bunkLabel, bunks,
                    bunkOptions.filter { it.division == null || it.division == division }.map { it.name }) { selected ->
                    val previousAuto = counselorsForBunks(bunks, bunkOptions)
                    val manual = if (settings.allowCounselorOverride) counselors - previousAuto.toSet() else emptyList()
                    bunks = selected
                    counselors = (counselorsForBunks(selected, bunkOptions) + manual).distinct()
                }
                if (settings.counselorEnabled) MultiNameField(settings.counselorLabel, counselors,
                    settings.counselorOptions, enabled = settings.allowCounselorOverride) { counselors = it }
                if (!settings.allowCounselorOverride) Text("Counselors follow the selected bunks. Manual changes are disabled in Settings.")
                DivisionFilter(division.ifBlank { null }, divisions.map { it.name }) { division = it.orEmpty() }
                OutlinedTextField(value = division, onValueChange = { division = it }, label = { Text(settings.divisionLabel) })
                error?.let { Text(it) }
                OutlinedTextField(
                    value = first, onValueChange = { first = it }, label = { Text("First name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = last, onValueChange = { last = it }, label = { Text("Last name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes / allergies (optional)") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = conditions, onValueChange = { conditions = it },
                    label = { Text("Conditions (optional)") }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = first.isNotBlank(),
                onClick = {
                    try {
                        val from = start.trim().takeIf { it.isNotEmpty() }?.let { java.time.LocalDate.parse(it) }
                        val until = end.trim().takeIf { it.isNotEmpty() }?.let { java.time.LocalDate.parse(it) }
                        require(from == null || until == null || from <= until)
                        onSave((initial ?: Patient(name = "")).copy(firstName = first.trim(), lastName = last.trim(),
                            notes = notes.ifBlank { null }, conditions = conditions.ifBlank { null },
                            startDate = from, endDate = until, bunks = bunks, counselors = counselors,
                            bunk = bunks.firstOrNull(), counselor = counselors.firstOrNull(), division = division.trim().ifBlank { null }))
                    } catch (e: IllegalArgumentException) { error = "Use YYYY-MM-DD dates; end must not precede start." }
                      catch (e: java.time.format.DateTimeParseException) { error = "Use valid YYYY-MM-DD dates." }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
