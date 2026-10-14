package com.campmeds.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Presets copy a value into the editable field; deleting a preset never changes existing dates. */
@Composable
fun DatePresetField(label: String, value: String, presets: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (presets.isNotEmpty()) Box {
            TextButton(onClick = { expanded = true }) { Text("Choose saved date ▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                presets.forEach { date -> DropdownMenuItem(text = { Text(date) }, onClick = { onChange(date); expanded = false }) }
            }
        }
    }
}
