package com.campmeds.app.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.campmeds.app.auth.SecurityQuestions
import com.campmeds.app.data.entity.User
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.launch

/** Randomized choices are remembered for the form lifetime, never reshuffled while typing. */
@Composable
fun SecurityQuestionSetup(onChange: (Map<String, String>) -> Unit) {
    val choices = remember { SecurityQuestions.available.keys.shuffled(java.security.SecureRandom()) }
    var selected by remember { mutableStateOf(List(3) { "" }) }
    var answers by remember { mutableStateOf(List(3) { "" }) }
    Text("Select three different recovery questions. Use answers only you know, preferably stored in a password manager. Answers are not retrievable.")
    repeat(3) { index ->
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(SecurityQuestions.available[selected[index]] ?: "Choose question ${index + 1} ▾") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                choices.filter { it !in selected || it == selected[index] }.forEach { id ->
                    DropdownMenuItem(text = { Text(SecurityQuestions.available.getValue(id)) }, onClick = {
                        selected = selected.toMutableList().also { it[index] = id }
                        answers = answers.toMutableList().also { it[index] = "" }
                        onChange(selected.zip(answers).filter { it.first.isNotBlank() }.toMap())
                        expanded = false
                    })
                }
            }
        }
        OutlinedTextField(answers[index], { answer ->
            answers = answers.toMutableList().also { it[index] = answer }
            onChange(selected.zip(answers).filter { it.first.isNotBlank() }.toMap())
        }, label = { Text("Answer ${index + 1} (at least 3 characters)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
    }
}

@Composable
fun RecoveryDialog(repository: CampMedsRepository, onDismiss: () -> Unit, enroll: Boolean = false) {
    val users by repository.observeUsers().collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<User?>(null) }
    var answers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (enroll) "Set up account recovery" else "Recover local account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (enroll) SecurityQuestionSetup { answers = it }
                else {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) { Text(selected?.name ?: "Select staff account ▾") }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            users.forEach { user -> DropdownMenuItem(text = { Text(user.name) }, onClick = {
                                selected = user; answers = emptyMap(); message = null; expanded = false
                            }) }
                        }
                    }
                    val questions = selected?.recoveryQuestions?.split("|").orEmpty()
                    if (selected != null && questions.isEmpty()) Text("Recovery was not configured for this account. No reset can be performed here.")
                    questions.forEach { q ->
                        OutlinedTextField(answers[q].orEmpty(), { answers = answers + (q to it) },
                            label = { Text(SecurityQuestions.available[q].orEmpty()) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation())
                    }
                    Text("Every failed attempt has a cooldown; five failures lock recovery for an hour. All three answers are required.")
                }
                OutlinedTextField(pin, { pin = it }, label = { Text(if (enroll) "Current PIN" else "New PIN (4–8 digits)") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (!enroll) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Confirm new PIN") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                message?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && answers.size == 3 && answers.values.all { SecurityQuestions.normalize(it).length >= 3 } &&
            SecurityQuestions.validPin(pin) && (enroll || pin == confirmation), onClick = {
            busy = true
            scope.launch {
                val success = if (enroll) repository.enrollRecovery(pin, answers)
                    else selected?.let { repository.recoverAccount(it.id, answers, pin) } == true
                busy = false
                pin = ""; confirmation = ""; answers = emptyMap()
                message = if (success) { if (enroll) "Recovery saved." else "PIN reset. Close this dialog and log in with your new PIN." }
                    else "Unable to verify or recovery is temporarily locked. Wait before trying again."
            }
        }) { Text(if (busy) "Checking…" else if (enroll) "Save recovery" else "Reset PIN") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } })
}
