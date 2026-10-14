package com.campmeds.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.campmeds.app.data.entity.*
import com.campmeds.app.domain.validDivisionColor

@Composable
fun DivisionBadge(name: String?, divisions: List<Division>) {
    val hex = divisions.firstOrNull { it.name == name }?.colorHex
    val color = if (hex != null && validDivisionColor(hex)) Color(0xFF000000 or hex.drop(1).toLong(16)) else Color.Gray
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp).background(color, CircleShape))
        Text(name ?: "No division") // Color is never the only identifier.
    }
}

@Composable
fun DivisionFilter(selected: String?, options: List<String>, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Division: ${selected ?: "All divisions"} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All divisions") }, onClick = { onSelect(null); open = false })
            options.distinct().sorted().forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(name); open = false })
            }
        }
    }
}

@Composable
fun SessionPresetField(sessions: List<CampSession>, onSelect: (CampSession) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (sessions.isNotEmpty()) Box {
        OutlinedButton(onClick = { open = true }) { Text("Choose named camp session ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sessions.forEach { s -> DropdownMenuItem(text = { Text("${s.name}: ${s.startDate} — ${s.endDate}") },
                onClick = { onSelect(s); open = false }) }
        }
    }
}

/** Explicit add/remove controls; commas in names stay part of a name, not separators. */
@Composable
fun MultiNameField(label: String, values: List<String>, options: List<String>, enabled: Boolean = true,
    onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        values.forEach { value -> Row {
            Text(value, modifier = Modifier.weight(1f))
            if (enabled) TextButton(onClick = { onChange(values - value) }) { Text("Remove $value") }
        } }
        if (enabled) {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Add $label") }, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    onChange((values + text.trim()).distinct()); text = ""
                }) { Text("Add value") }
                Box {
                    TextButton(enabled = options.isNotEmpty(), onClick = { open = true }) { Text("Choose saved ▾") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        options.distinct().filter { it !in values }.forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = { onChange(values + value); open = false })
                        }
                    }
                }
            }
        }
    }
}
