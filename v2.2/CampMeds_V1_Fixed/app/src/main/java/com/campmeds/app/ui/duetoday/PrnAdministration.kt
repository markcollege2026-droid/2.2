package com.campmeds.app.ui.duetoday

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.campmeds.app.auth.Session
import com.campmeds.app.data.entity.*
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** One explicit request per dialog, not a scheduled due dose. Its timestamp stays stable through recomposition. */
@Composable
fun PrnAdministration(repository: CampMedsRepository, patient: Patient, med: Medication, onDismiss: () -> Unit) {
    val requestedAt = rememberSaveable(med.id) { LocalDateTime.now().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val definitions by repository.observeAdministrationTimes().collectAsState(initial = emptyList())
    DoseActionSheet(item = DueDoseItem(med, patient, LocalDateTime.parse(requestedAt), null, isAsNeeded = true),
        repository = repository, definitions = definitions.associateBy { it.id }, recording = busy,
        onDismiss = { if (!busy) onDismiss() }, onRecord = { status, override, reason ->
            val user = Session.currentUser.value
            if (!busy && user != null) {
                busy = true
                scope.launch {
                    try {
                        if (repository.recordDose(med.id, LocalDateTime.parse(requestedAt), status, user.id,
                                override, reason, expectedMedication = med, isPrn = true)) onDismiss()
                        else error = "Request already recorded, or medication/camper changed or is inactive. History was not modified. Close and review before requesting another dose."
                    } finally { busy = false }
                }
            }
        })
    error?.let { message -> AlertDialog(onDismissRequest = { error = null; onDismiss() },
        title = { Text("PRN dose not recorded") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null; onDismiss() }) { Text("Close") } }) }
}
