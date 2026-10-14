package com.campmeds.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.launch

@Composable
fun CamperSettings(repository: CampMedsRepository) {
    val settings by repository.observeSettings().collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Camper fields and date presets", style = MaterialTheme.typography.titleMedium)
        Text("Labels are independent; renaming never moves or erases camper values. Date presets copy into forms and can be overridden manually.")
        listOf("bunkLabel" to "Bunk", "counselorLabel" to "Counselor", "divisionLabel" to "Camper division",
            "startPresets" to "Start-date presets", "endPresets" to "End-date presets").forEach { (key, title) ->
            var text by remember(settings[key]) { mutableStateOf(settings[key] ?: if (key.endsWith("Label")) title else "") }
            OutlinedTextField(text, { text = it }, label = { Text(title) }, modifier = Modifier.fillMaxWidth(),
                supportingText = { if (key.endsWith("Presets")) Text("One YYYY-MM-DD date per line; blank removes presets.") })
            TextButton(onClick = {
                scope.launch {
                    message = try { repository.saveSetting(key, text.trim()); "Saved $title" }
                    catch (_: Exception) { "Not saved. Check dates, label and admin access." }
                }
            }) { Text("Save $title") }
        }
        message?.let { Text(it) }
    }
}
