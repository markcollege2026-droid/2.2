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
    var bunk by remember { mutableStateOf(initial?.bunk.orEmpty()) }
    var counselor by remember { mutableStateOf(initial?.counselor.orEmpty()) }
    var division by remember { mutableStateOf(initial?.division.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DatePresetField("Camper start date (optional, YYYY-MM-DD)", start, settings.startDates) { start = it }
                DatePresetField("Camper end date (inclusive, YYYY-MM-DD)", end, settings.endDates) { end = it }
                Text("Before the start and after the end date this camper is in Archive / future campers. Blank dates are unrestricted.")
                OutlinedTextField(value = bunk, onValueChange = { bunk = it }, label = { Text(settings.bunkLabel) })
                OutlinedTextField(value = counselor, onValueChange = { counselor = it }, label = { Text(settings.counselorLabel) })
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
                            startDate = from, endDate = until, bunk = bunk.trim().ifBlank { null },
                            counselor = counselor.trim().ifBlank { null }, division = division.trim().ifBlank { null }))
                    } catch (e: IllegalArgumentException) { error = "Use YYYY-MM-DD dates; end must not precede start." }
                      catch (e: java.time.format.DateTimeParseException) { error = "Use valid YYYY-MM-DD dates." }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
