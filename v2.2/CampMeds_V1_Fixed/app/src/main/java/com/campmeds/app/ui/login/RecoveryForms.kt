package com.campmeds.app.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.campmeds.app.auth.RecoverySecurity
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.launch
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

@Composable
fun RecoveryQuestions(onChange: (Map<String, String>) -> Unit) {
    val choices = remember { RecoverySecurity.questions.keys.shuffled(SecureRandom().asKotlinRandom()) }
    val selected = remember { mutableStateListOf("", "", "") }
    val answers = remember { mutableStateListOf("", "", "") }
    var menu by remember { mutableStateOf(-1) }
    fun changed() { onChange(selected.indices.filter { selected[it].isNotEmpty() }.associate { selected[it] to answers[it] }) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose three security questions. Use private, unpredictable answers (4–200 characters). All three are required to reset your PIN.")
        repeat(3) { i ->
            Box {
                OutlinedButton(onClick = { menu = i }) { Text(RecoverySecurity.questions[selected[i]] ?: "Choose question ${i + 1} ▾") }
                DropdownMenu(expanded = menu == i, onDismissRequest = { menu = -1 }) {
                    choices.filter { it !in selected || it == selected[i] }.forEach { id ->
                        DropdownMenuItem(text = { Text(RecoverySecurity.questions.getValue(id)) }, onClick = {
                            selected[i] = id; answers[i] = ""; menu = -1; changed()
                        })
                    }
                }
            }
            OutlinedTextField(value = answers[i], onValueChange = { if (it.length <= 200) { answers[i] = it; changed() } },
                label = { Text("Answer ${i + 1}") }, visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun RecoveryDialog(repository: CampMedsRepository, onDismiss: () -> Unit) {
    val users by repository.observeUsers().collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val user = users.firstOrNull { it.id == selectedId }
    val answers = remember(selectedId) { mutableStateMapOf<String, String>() }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Recover account") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Answer all three questions to reset this account's PIN. Five incorrect attempts lock recovery for 30 minutes; further incorrect attempts renew the lock. Existing accounts must first enroll while signed in.")
            Box {
                OutlinedButton(enabled = !busy && !success, onClick = { menu = true }) { Text(user?.name ?: "Select account ▾") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    users.forEach { u -> DropdownMenuItem(text = { Text(u.name) }, onClick = { selectedId = u.id; menu = false; message = null }) }
                }
            }
            user?.recoveryQuestionIds?.forEach { q ->
                OutlinedTextField(value = answers[q].orEmpty(), enabled = !busy && !success,
                    onValueChange = { if (it.length <= 200) answers[q] = it },
                    label = { Text(RecoverySecurity.questions[q] ?: "Security question") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            if (user != null && user.recoveryQuestionIds.isEmpty()) Text("Recovery has not been enrolled for this account. No reset is available without prior enrollment.")
            OutlinedTextField(value = pin, onValueChange = { if (it.length <= 8) pin = it }, label = { Text("New PIN (4–8 digits)") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(value = confirm, onValueChange = { confirm = it }, label = { Text("Confirm new PIN") }, visualTransformation = PasswordVisualTransformation())
            message?.let { Text(it) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && !success && user?.recoveryQuestionIds?.size == 3, onClick = {
            if (pin != confirm || !pin.matches(Regex("[0-9]{4,8}"))) message = "Enter matching PINs of 4–8 digits."
            else {
                busy = true
                scope.launch {
                    try {
                        success = repository.recoverAccount(user!!.id, answers.toMap(), pin)
                        message = if (success) "PIN reset. Close this dialog and log in with your new PIN."
                            else "Recovery was not accepted. Check the answers and PIN, or wait 30 minutes if attempts are locked."
                        answers.clear(); pin = ""; confirm = ""
                    } finally { busy = false }
                }
            }
        }) { Text(if (busy) "Checking…" else "Reset PIN") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } })
}

@Composable
fun EnrollRecoveryDialog(repository: CampMedsRepository, onDismiss: () -> Unit) {
    var answers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Set up account recovery") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("Your current PIN") }, visualTransformation = PasswordVisualTransformation())
            RecoveryQuestions { answers = it }
            error?.let { Text(it) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val invalid = runCatching { RecoverySecurity.validate(answers) }.exceptionOrNull()
        if (invalid != null) error = invalid.message else {
            busy = true
            scope.launch {
                try {
                    if (repository.enrollRecovery(pin, answers)) onDismiss() else error = "Current PIN was not accepted, or recovery is locked. After five failures, wait 30 minutes."
                } finally { busy = false }
            }
        }
    }) { Text(if (busy) "Saving…" else "Save questions") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}
