package com.campmeds.app.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campmeds.app.auth.Session
import com.campmeds.app.data.BackupFile
import com.campmeds.app.data.BackupValidation
import com.campmeds.app.data.DatabaseBackupManager
import com.campmeds.app.data.DatabaseRestoreManager
import com.campmeds.app.data.SCHEMA_VERSION
import com.campmeds.app.data.entity.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Administrator backup and restore.
 *
 * Backups are copies of the SQLCipher-encrypted database, so they contain every table (patients,
 * medications, schedules, dose history, groups, camp sessions, divisions and bunks, import and change
 * history) and stay encrypted with the device's Keystore-backed key. That also means a backup can only be
 * restored on this install: the key is never exported.
 *
 * Restoring is deliberately explicit: the administrator sees exactly what will be replaced and what the
 * backup contains (validated first), the app takes a safety copy, and the current data is only replaced after
 * that. If anything fails, the original database is put back and the screen says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    backups: DatabaseBackupManager,
    restoreManager: DatabaseRestoreManager,
    onBack: () -> Unit,
    onRestored: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentUser by Session.currentUser.collectAsState()
    val isAdmin = Session.hasAtLeast(currentUser, Role.ADMIN)

    var entries by remember { mutableStateOf<List<BackupFile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<BackupFile?>(null) }
    var restoreCheck by remember { mutableStateOf<BackupValidation?>(null) }
    var checking by remember { mutableStateOf(false) }
    var pendingCreate by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf<DatabaseRestoreManager.RestoreResult.Success?>(null) }

    fun refresh() {
        scope.launch { entries = withContext(Dispatchers.IO) { backups.listBackups() } }
    }

    LaunchedEffect(Unit) { refresh() }

    // Validate the chosen file before the confirmation dialog is shown, so the administrator sees whether the
    // backup is usable (and what it contains) instead of finding out after confirming.
    LaunchedEffect(pendingRestore) {
        val entry = pendingRestore ?: return@LaunchedEffect
        checking = true
        restoreCheck = withContext(Dispatchers.IO) { backups.validate(entry.file) }
        checking = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backups") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Text(
                    "A backup is a complete copy of the encrypted database: patients, medications, schedules, " +
                        "administration history, groups, camp sessions, divisions, bunks, import history and " +
                        "change history. It stays encrypted with this device's key, so it can only be restored " +
                        "on this device. Current database schema: v$SCHEMA_VERSION.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
            if (!isAdmin) {
                item {
                    Text(
                        "Only an administrator can create or restore backups.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                item {
                    Button(
                        onClick = { pendingCreate = true },
                        enabled = !busy,
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    ) { Text("Create a backup now") }
                }
            }

            message?.let { text ->
                item {
                    Text(
                        text,
                        color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            if (busy) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Working. Do not close the app.")
                    }
                }
            }

            item {
                HorizontalDivider()
                Text(
                    if (entries.isEmpty()) "No backups on this device yet." else "Backups on this device",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp)
                )
            }

            items(entries, key = { it.file.absolutePath }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.reason.label) },
                    supportingContent = {
                        Text(
                            entry.createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) +
                                " · " + sizeText(entry.sizeBytes) +
                                (entry.schemaVersion?.let { " · schema v$it" } ?: " · older backup format") +
                                if (entry.legacy) " · no manifest (validated before use)" else ""
                        )
                    },
                    trailingContent = {
                        if (isAdmin) {
                            OutlinedButton(onClick = { message = null; restoreCheck = null; pendingRestore = entry }, enabled = !busy) {
                                Text("Restore")
                            }
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }

    if (pendingCreate) {
        AlertDialog(
            onDismissRequest = { pendingCreate = false },
            title = { Text("Create a backup now?") },
            text = {
                Text(
                    "The app will copy the encrypted database and verify the copy before it is listed here. " +
                        "The database is briefly checkpointed, so keep the app open until it finishes."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCreate = false
                    busy = true
                    message = null
                    scope.launch {
                        val result = runCatching { restoreManager.createManualBackup() }
                        busy = false
                        result.onSuccess {
                            messageIsError = false
                            message = "Backup created and verified: ${it.name}"
                        }.onFailure {
                            messageIsError = true
                            message = "The backup could not be created: ${it.message ?: "unknown error"}"
                        }
                        refresh()
                    }
                }) { Text("Create backup") }
            },
            dismissButton = { TextButton(onClick = { pendingCreate = false }) { Text("Cancel") } }
        )
    }

    pendingRestore?.let { entry ->
        val check = restoreCheck
        val blocked = checking || check !is BackupValidation.Valid
        AlertDialog(
            onDismissRequest = { pendingRestore = null; restoreCheck = null },
            title = { Text("Restore this backup?") },
            text = {
                Column {
                    Text(entry.reason.label + " · " + entry.createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                    Spacer(Modifier.height(8.dp))
                    when {
                        checking -> Text("Checking this backup…")
                        check is BackupValidation.Invalid -> Text(
                            check.message,
                            color = MaterialTheme.colorScheme.error
                        )
                        check is BackupValidation.Valid -> {
                            Text(
                                "Verified: schema v${check.schemaVersion}, " +
                                    "${check.tableCounts["patients"] ?: 0} patients, " +
                                    "${check.tableCounts["medications"] ?: 0} medications, " +
                                    "${check.tableCounts["dose_logs"] ?: 0} administration records."
                            )
                            if (check.needsMigration) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "This backup was made with an older database schema (v${check.schemaVersion}). " +
                                        "It is upgraded to v$SCHEMA_VERSION by the app's normal data-preserving " +
                                        "migrations when it is opened; no records are discarded."
                                )
                            }
                            if (check.legacy) {
                                Spacer(Modifier.height(8.dp))
                                Text("This is an older backup without a manifest, so it was inspected in full.")
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Every patient, medication and administration record on this device will be " +
                                    "replaced by the contents of this backup. A safety copy of the current data " +
                                    "is taken first. If the restore fails, the current data stays as it is."
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("You will be signed out and must sign in again.")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !blocked,
                    onClick = {
                        val file = entry.file
                        pendingRestore = null
                        busy = true
                        message = null
                        scope.launch {
                            val result = restoreManager.restore(file, Session.currentUser.value)
                            busy = false
                            when (result) {
                                is DatabaseRestoreManager.RestoreResult.Success -> restored = result
                                is DatabaseRestoreManager.RestoreResult.Rejected -> {
                                    messageIsError = true
                                    message = result.message
                                }
                                is DatabaseRestoreManager.RestoreResult.Failed -> {
                                    messageIsError = true
                                    message = result.message
                                }
                            }
                            restoreCheck = null
                            refresh()
                        }
                    }
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null; restoreCheck = null }) { Text("Cancel") }
            }
        )
    }

    restored?.let { result ->
        AlertDialog(
            onDismissRequest = { /* the app must be re-entered through the sign-in screen */ },
            title = { Text("Backup restored") },
            text = {
                Column {
                    Text("Restored ${result.restoredFrom} and verified it.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Patients: ${result.tableCounts["patients"] ?: 0}, medications: " +
                            "${result.tableCounts["medications"] ?: 0}, administration records: " +
                            "${result.tableCounts["dose_logs"] ?: 0}."
                    )
                    result.migratedFrom?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The backup was from schema v$it and has been upgraded to v$SCHEMA_VERSION by the " +
                                "app's normal migrations."
                        )
                    }
                    result.safetyBackupName?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("The previous data was kept as a safety copy: $it")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    restored = null
                    onRestored()
                }) { Text("Sign in again") }
            }
        )
    }
}

private fun sizeText(bytes: Long): String =
    if (bytes < 1024 * 1024) String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
