package com.campmeds.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Presets copy a date into the form; later configuration changes never alter saved records. */
@Composable
fun DatePresetField(label: String, value: String, onValueChange: (String) -> Unit, presets: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(value, onValueChange, label = { Text("$label (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
        if (presets.isNotBlank()) Box {
            TextButton(onClick = { expanded = true }) { Text("Choose $label preset ▾") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                presets.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { date ->
                    DropdownMenuItem(text = { Text(date) }, onClick = { onValueChange(date); expanded = false })
                }
            }
        }
    }
}
