package com.campmeds.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campmeds.app.data.entity.CampSettings
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun CampSettingsForm(repository: CampMedsRepository) {
    val settings by repository.observeSettings().collectAsState(initial = null)
    val value = settings ?: return
    var starts by remember(value) { mutableStateOf(value.startDates.joinToString("\n")) }
    var ends by remember(value) { mutableStateOf(value.endDates.joinToString("\n")) }
    var bunk by remember(value) { mutableStateOf(value.bunkLabel) }
    var counselor by remember(value) { mutableStateOf(value.counselorLabel) }
    var division by remember(value) { mutableStateOf(value.divisionLabel) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reusable dates", style = MaterialTheme.typography.titleMedium)
        Text("One YYYY-MM-DD date per line. Presets are available for campers and medications; manual entry stays available. Editing presets never changes existing records.")
        OutlinedTextField(value = starts, onValueChange = { starts = it }, label = { Text("Start-date options") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = ends, onValueChange = { ends = it }, label = { Text("End-date options") }, modifier = Modifier.fillMaxWidth())
        Text("Patient field names", style = MaterialTheme.typography.titleMedium)
        Text("These are three independent fields. Renaming a label preserves every camper's value.")
        OutlinedTextField(value = bunk, onValueChange = { bunk = it }, label = { Text("Bunk field name") })
        OutlinedTextField(value = counselor, onValueChange = { counselor = it }, label = { Text("Counselor field name") })
        OutlinedTextField(value = division, onValueChange = { division = it }, label = { Text("Camper division field name (separate)") })
        Button(onClick = {
            try {
                fun dates(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { LocalDate.parse(it).toString() }.distinct()
                val updated = CampSettings(startDates = dates(starts), endDates = dates(ends), bunkLabel = bunk.trim(), counselorLabel = counselor.trim(), divisionLabel = division.trim())
                require(listOf(bunk, counselor, division).all { it.isNotBlank() })
                scope.launch { repository.saveSettings(updated); message = "Settings saved" }
            } catch (e: Exception) { message = "Provide nonblank field names and valid YYYY-MM-DD dates." }
        }) { Text("Save dates and field names") }
        message?.let { Text(it) }
        HorizontalDivider()
    }
}
