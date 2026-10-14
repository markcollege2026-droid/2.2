package com.campmeds.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campmeds.app.data.entity.*
import com.campmeds.app.repository.CampMedsRepository
import com.campmeds.app.ui.common.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun CampDirectorySettings(repository: CampMedsRepository) {
    val sessions by repository.observeSessions().collectAsState(initial = emptyList())
    val divisions by repository.observeDivisions().collectAsState(initial = emptyList())
    val bunks by repository.observeBunks().collectAsState(initial = emptyList())
    val settings by repository.observeSettings().collectAsState(initial = CampSettings())
    var filter by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<String?>(null) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#3F51B5") }
    var selectedDivision by remember { mutableStateOf<String?>(null) }
    var counselors by remember { mutableStateOf<List<String>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun begin(type: String) { kind = type; editing = false; name = ""; start = ""; end = ""; counselors = emptyList(); sessionId = null; error = null; selectedDivision = filter }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Named camp sessions", style = MaterialTheme.typography.titleMedium)
        Text("Selecting a session copies its dates. Later edits/removal do not change any saved camper or medication dates.")
        OutlinedButton(onClick = { begin("session") }) { Text("Create camp session") }
        sessions.forEach { s ->
            Text("${s.name}: ${s.startDate} — ${s.endDate}")
            Row {
                TextButton(onClick = { begin("session"); editing = true; sessionId = s.id; name = s.name; start = s.startDate.toString(); end = s.endDate.toString() }) { Text("Edit ${s.name}") }
                TextButton(onClick = { scope.launch { repository.removeSession(s.id) } }) { Text("Remove preset") }
            }
        }
        HorizontalDivider()
        Text("Divisions and colors", style = MaterialTheme.typography.titleMedium)
        DivisionFilter(filter, (divisions.map { it.name } + bunks.mapNotNull { it.division }).distinct()) { filter = it }
        OutlinedButton(onClick = { begin("division") }) { Text("Configure division") }
        divisions.filter { filter == null || it.name == filter }.forEach { d ->
            DivisionBadge(d.name, divisions)
            Row {
                TextButton(onClick = { begin("division"); editing = true; name = d.name; color = d.colorHex }) { Text("Edit color") }
                TextButton(onClick = { scope.launch { repository.removeDivision(d.name) } }) { Text("Remove color option") }
            }
        }
        Text("${settings.bunkLabel} → ${settings.counselorLabel}", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { begin("bunk") }) { Text("Configure bunk association") }
        bunks.filter { filter == null || it.division == filter }.forEach { b ->
            Text("${b.name} (${b.division ?: "All divisions"}): ${b.counselors.joinToString()}")
            Row {
                TextButton(onClick = { begin("bunk"); editing = true; name = b.name; counselors = b.counselors; selectedDivision = b.division }) { Text("Edit association") }
                TextButton(onClick = { scope.launch { repository.removeBunk(b.name) } }) { Text("Remove option") }
            }
        }
        Text("Removing options never deletes camper values or administration history. Division/bunk names are stable keys; create a new option instead of renaming one.")
    }
    if (kind != null) AlertDialog(onDismissRequest = { kind = null }, title = { Text("Configure $kind") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, enabled = !editing || kind == "session", onValueChange = { name = it }, label = { Text("Name") })
            when (kind) {
                "session" -> {
                    DatePresetField("Start date (YYYY-MM-DD)", start, settings.startDates) { start = it }
                    DatePresetField("End date (YYYY-MM-DD)", end, settings.endDates) { end = it }
                }
                "division" -> {
                    OutlinedTextField(value = color, onValueChange = { color = it }, label = { Text("Color (#RRGGBB)") })
                    Text("Examples: #3F51B5 blue, #008577 teal, #C62828 red, #8E24AA purple.")
                }
                "bunk" -> {
                    DivisionFilter(selectedDivision, divisions.map { it.name }) { selectedDivision = it }
                    MultiNameField(settings.counselorLabel, counselors, settings.counselorOptions) { counselors = it }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        scope.launch {
            try {
                when (kind) {
                    "session" -> repository.saveSession(CampSession(id = sessionId ?: java.util.UUID.randomUUID().toString(), name = name,
                        startDate = LocalDate.parse(start), endDate = LocalDate.parse(end)))
                    "division" -> repository.saveDivision(Division(name.trim(), color.trim()))
                    "bunk" -> repository.saveBunk(BunkOption(name.trim(), counselors, selectedDivision))
                }
                kind = null
            } catch (e: Exception) { error = "Check name, dates (end on/after start), and color. ${e.message.orEmpty()}" }
        }
    }) { Text("Save option") } }, dismissButton = { TextButton(onClick = { kind = null }) { Text("Cancel") } })
}
