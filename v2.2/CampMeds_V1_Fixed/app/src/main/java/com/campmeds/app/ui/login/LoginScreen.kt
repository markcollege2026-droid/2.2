package com.campmeds.app.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.campmeds.app.auth.RecoverySecurity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.campmeds.app.auth.Session
import com.campmeds.app.data.entity.Role
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Screen 1 (spec section 4): PIN entry, resolves to a User + role. */
@Composable
fun LoginScreen(
    repository: CampMedsRepository,
    onLoggedIn: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showRecovery by remember { mutableStateOf(false) }
    if (showRecovery) RecoveryDialog(repository) { showRecovery = false }
    var isChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // First-run bootstrap: with no Users in the DB yet, nobody could ever log in — so if the
    // user table is empty, walk staff through creating the first Admin account instead.
    var checkedForUsers by remember { mutableStateOf(false) }
    var needsBootstrap by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val users = repository.observeUsers().first()
        needsBootstrap = users.isEmpty()
        checkedForUsers = true
    }

    if (!checkedForUsers) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (needsBootstrap) {
        FirstRunSetupForm(
            onCreated = { user ->
                Session.login(user)
                onLoggedIn()
            },
            repository = repository
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("CampMeds", style = MaterialTheme.typography.headlineMedium)
        Text("Medication Administration Record", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 8) { pin = it; error = null } },
            label = { Text("Enter PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(0.7f)
        )

        Spacer(Modifier.height(16.dp))

        Button(
            enabled = pin.isNotBlank() && !isChecking,
            onClick = {
                isChecking = true
                scope.launch {
                    val user = repository.login(pin)
                    isChecking = false
                    if (user != null) {
                        Session.login(user)
                        onLoggedIn()
                    } else {
                        error = "PIN not recognized"
                        pin = ""
                    }
                }
            }
        ) {
            Text(if (isChecking) "Checking..." else "Log in")
        }
        TextButton(onClick = { showRecovery = true }) { Text("Forgot PIN? Recover account") }
    }
}

/** Shown only when no User records exist yet — creates the first Admin account. */
@Composable
private fun FirstRunSetupForm(
    repository: CampMedsRepository,
    onCreated: (com.campmeds.app.data.entity.User) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var recovery by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Welcome to CampMeds", style = MaterialTheme.typography.headlineSmall)
        Text("No staff accounts exist yet — set up the first Admin account.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Admin name") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.7f))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pin, onValueChange = { if (it.length <= 8) pin = it }, label = { Text("Choose a PIN") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(0.7f)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = confirmPin, onValueChange = { if (it.length <= 8) confirmPin = it }, label = { Text("Confirm PIN") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(0.7f)
        )
        Spacer(Modifier.height(16.dp))

        RecoveryQuestions { recovery = it }
        Button(
            enabled = name.isNotBlank() && pin.isNotBlank() && !isSaving,
            onClick = {
                if (pin != confirmPin) {
                    error = "PINs do not match"
                    return@Button
                }
                if (!pin.matches(Regex("[0-9]{4,8}"))) { error = "Choose a PIN of 4–8 digits"; return@Button }
                val invalid = runCatching { RecoverySecurity.validate(recovery) }.exceptionOrNull()
                if (invalid != null) { error = invalid.message; return@Button }
                isSaving = true
                scope.launch {
                    try { onCreated(repository.createUser(name.trim(), Role.ADMIN, pin, recovery)) }
                    catch (e: IllegalArgumentException) { error = e.message }
                    finally { isSaving = false }
                }
            }
        ) {
            Text(if (isSaving) "Creating..." else "Create Admin account")
        }
    }
}
