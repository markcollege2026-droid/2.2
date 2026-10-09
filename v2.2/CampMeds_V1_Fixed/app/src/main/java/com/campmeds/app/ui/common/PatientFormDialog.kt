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
    onSave: (firstName: String, lastName: String, notes: String?, conditions: String?) -> Unit
) {
    var first by remember { mutableStateOf(initial?.firstName.orEmpty()) }
    var last by remember { mutableStateOf(initial?.lastName.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var conditions by remember { mutableStateOf(initial?.conditions.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                onClick = { onSave(first.trim(), last.trim(), notes.ifBlank { null }, conditions.ifBlank { null }) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
