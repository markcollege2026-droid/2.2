package com.campmeds.app.ui.addmedication

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.domain.InputValidation
import com.campmeds.app.domain.MedicationIdentity
import com.campmeds.app.domain.Validated
import com.campmeds.app.network.NdcLookupResult
import com.campmeds.app.repository.CampMedsRepository
import com.campmeds.app.ui.common.QrLabelPanel
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Screen 4 (spec section 4): NDC entry field → lookup button → shows matched name/strength/form
 * for staff to confirm against the physical bottle → dosage, schedule, dates → generates QR code.
 *
 * V1 Fixed changes vs. the original screen (all in this file):
 *  - schedule times / dates are validated strictly and NEVER silently dropped (bugs 3, 4, 5)
 *  - end date must not precede start date (bug 6)
 *  - changing the NDC discards the old NDC's lookup/manual details; a new lookup (or an explicit
 *    manual-exception entry) is required before saving (bug 8)
 *  - manual entry is an explicit action, and only exception-flagged data is ever manual (bug 9 partner)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditMedicationScreen(
    repository: CampMedsRepository,
    patientId: String,
    medicationId: String?,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isEditing = medicationId != null

    var ndc by remember { mutableStateOf("") }
    /** The exact NDC that name/strength/form currently belong to (null = details not established). */
    var detailsForNdc by remember { mutableStateOf<String?>(null) }
    var lookedUp by remember { mutableStateOf<NdcLookupResult?>(null) }
    var isLookingUp by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf("") }
    var form by remember { mutableStateOf("") }
    var isException by remember { mutableStateOf(false) }

    var dosageInstructions by remember { mutableStateOf("") }
    var scheduleText by remember { mutableStateOf("08:00, 20:00") }
    var startText by remember { mutableStateOf(LocalDate.now().toString()) }
    var endText by remember { mutableStateOf("") }
    var expirationText by remember { mutableStateOf("") }
    var pillCount by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var patientName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var qrCode by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var existing by remember { mutableStateOf<Medication?>(null) }

    // Validation messages. The user's typed text is never modified when an error is shown.
    var ndcError by remember { mutableStateOf<String?>(null) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var dosageError by remember { mutableStateOf<String?>(null) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var startError by remember { mutableStateOf<String?>(null) }
    var endError by remember { mutableStateOf<String?>(null) }
    var expirationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(patientId) {
        patientName = repository.getPatient(patientId)?.name.orEmpty()
    }

    LaunchedEffect(medicationId) {
        if (medicationId != null) {
            repository.getMedication(medicationId)?.let { med ->
                existing = med
                ndc = med.ndc
                detailsForNdc = med.ndc
                name = med.name
                strength = med.strength
                form = med.form
                isException = med.isException
                dosageInstructions = med.dosageInstructions
                scheduleText = med.scheduleTimes.joinToString(", ") { it.toString() }
                startText = med.startDate.toString()
                endText = med.endDate?.toString() ?: ""
                expirationText = med.bottleExpiration?.toString() ?: ""
                pillCount = med.pillCountEntered?.toString() ?: ""
                purpose = med.purpose.orEmpty()
                qrCode = med.qrCode
                lookedUp = if (med.isException) null
                else NdcLookupResult.Found(med.ndc, med.name, med.strength, med.form)
            }
        }
    }

    fun onNdcChanged(newValue: String) {
        ndc = newValue
        ndcError = null
        if (newValue.trim() != detailsForNdc) {
            // Manual details typed while the NDC was empty (or that are being cleared back to empty) were never
            // tied to a specific NDC, so typing/removing an NDC must not wipe what staff just entered.
            val keepManual = isException && (detailsForNdc.isNullOrEmpty() || newValue.isBlank())
            if (keepManual) {
                detailsForNdc = newValue.trim()
            } else {
                // BUG FIX (V1 bug 8): details retrieved/typed for the old NDC must not stay attached to a
                // different NDC. Drop them and force a fresh lookup (or an explicit manual exception).
                name = ""; strength = ""; form = ""
                lookedUp = null
                isException = false
                detailsForNdc = null
                manualError = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Medication" else "Add Medication") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text("Medication identification", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = ndc,
                onValueChange = ::onNdcChanged,
                label = { Text("NDC number (optional for manual entry)") },
                singleLine = true,
                isError = ndcError != null,
                supportingText = { Text(ndcError ?: "Digits and dashes, e.g. 0069-2587 or 0069-2587-68") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = ndc.isNotBlank() && !isLookingUp,
                onClick = {
                    val key = ndc.trim()
                    isLookingUp = true
                    scope.launch {
                        val result = repository.lookupNdc(key)
                        isLookingUp = false
                        if (ndc.trim() != key) return@launch // NDC edited while the lookup was running
                        lookedUp = result
                        ndcError = null
                        if (result is NdcLookupResult.Found) {
                            name = result.name
                            strength = result.strength
                            form = result.form
                            isException = false
                            detailsForNdc = key
                        }
                    }
                }
            ) {
                Text(if (isLookingUp) "Looking up..." else "Look up NDC")
            }

            when (val result = lookedUp) {
                is NdcLookupResult.Found -> Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("Match found — confirm against the physical bottle:", style = MaterialTheme.typography.labelLarge)
                        Text("Name: ${result.name}")
                        Text("Strength: ${result.strength}")
                        Text("Form: ${result.form}")
                    }
                }
                is NdcLookupResult.NotFound -> Text(
                    "No match found for this NDC.",
                    color = MaterialTheme.colorScheme.error
                )
                is NdcLookupResult.Error -> Text(
                    "Lookup failed (${result.message}).",
                    color = MaterialTheme.colorScheme.error
                )
                null -> {}
            }

            if (!isException && lookedUp !is NdcLookupResult.Found) {
                // Always available, including with the NDC field completely empty: no NDC is required for manual entry.
                OutlinedButton(
                    onClick = {
                        // Explicit, documented manual-exception workflow (spec: isException).
                        isException = true
                        detailsForNdc = ndc.trim()
                        ndcError = null
                    }
                ) {
                    Text("Manual entry — no NDC or can't look it up (flagged as exception)")
                }
            }

            if (isException) {
                Text(
                    "Manual exception: these details were typed by staff, not retrieved from openFDA.",
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedTextField(value = name, onValueChange = { name = it; manualError = null }, label = { Text("Name (manual)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = strength, onValueChange = { strength = it; manualError = null }, label = { Text("Strength (manual)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = form, onValueChange = { form = it; manualError = null }, label = { Text("Form (manual)") },
                    isError = manualError != null, supportingText = { manualError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()
            Text("Dosage & schedule", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = dosageInstructions,
                onValueChange = { dosageInstructions = it; dosageError = null },
                label = { Text("Dosage instructions (e.g. \"1 tablet, twice daily\")") },
                isError = dosageError != null,
                supportingText = { dosageError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = purpose,
                onValueChange = { purpose = it },
                label = { Text("What is this medication for?") },
                supportingText = { Text("Shown only on the patient's details screen, never while dosing.") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = scheduleText,
                onValueChange = { scheduleText = it; scheduleError = null },
                label = { Text("Schedule times (24h HH:MM, comma-separated)") },
                isError = scheduleError != null,
                supportingText = { Text(scheduleError ?: "Example: 08:00, 20:00") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = startText,
                onValueChange = { startText = it; startError = null; endError = null },
                label = { Text("Start date (YYYY-MM-DD)") },
                isError = startError != null,
                supportingText = { startError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { endText = it; endError = null },
                label = { Text("End date (optional, YYYY-MM-DD)") },
                isError = endError != null,
                supportingText = { endError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = expirationText,
                onValueChange = { expirationText = it; expirationError = null },
                label = { Text("Bottle expiration (optional, YYYY-MM-DD)") },
                isError = expirationError != null,
                supportingText = { expirationError?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pillCount,
                onValueChange = { pillCount = it.filter { c -> c.isDigit() } },
                label = { Text("Pill count entered (optional, manual)") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()
            Text("QR code for bag/bottle label", style = MaterialTheme.typography.titleMedium)
            val currentExisting = existing
            val identityChanging = currentExisting != null && MedicationIdentity.changed(
                currentExisting,
                currentExisting.copy(ndc = ndc.trim(), name = name, strength = strength, form = form)
            )
            when {
                currentExisting == null -> Text(
                    "The QR code is created when you save. Then print, save or share it from here or from the patient's details."
                )
                identityChanging -> Text(
                    "You changed the medication's name, NDC, strength or form. Saving creates a NEW medication record with a " +
                        "new QR code; the old record, its history and its QR code are kept (the old one is retired).",
                    color = MaterialTheme.colorScheme.error
                )
                else -> {
                    QrLabelPanel(
                        qrContent = qrCode,
                        lines = listOf(patientName, name),
                        fileBaseName = "medication_${patientName}_$name",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Print or attach this code to the bag/bottle. It's what staff scan at dosing time.")
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    // ---- validate everything; nothing is saved unless every field passes ----
                    val ndcKey = ndc.trim()
                    var ok = true

                    if (ndcKey.isEmpty() && !isException) {
                        // An empty NDC is fine, but only through manual entry. No fake NDC is ever required.
                        ndcError = "Enter an NDC and look it up, or use Manual entry (no NDC needed)."; ok = false
                    } else if (detailsForNdc != ndcKey ||
                        (!isException && lookedUp !is NdcLookupResult.Found)
                    ) {
                        ndcError = "Look up this NDC before saving (or use Manual entry)."; ok = false
                    }
                    if (isException && (name.isBlank() || strength.isBlank() || form.isBlank())) {
                        manualError = "Name, strength and form are all required for a manual entry."; ok = false
                    }
                    if (dosageInstructions.isBlank()) {
                        dosageError = "Dosage instructions are required."; ok = false
                    }

                    val times = when (val r = InputValidation.parseScheduleTimes(scheduleText)) {
                        is Validated.Ok -> r.value
                        is Validated.Invalid -> { scheduleError = r.message; ok = false; emptyList() }
                    }
                    val start = when (val r = InputValidation.parseDate(startText, "start date", required = true)) {
                        is Validated.Ok -> r.value
                        is Validated.Invalid -> { startError = r.message; ok = false; null }
                    }
                    val end = when (val r = InputValidation.parseDate(endText, "end date", required = false)) {
                        is Validated.Ok -> r.value
                        is Validated.Invalid -> { endError = r.message; ok = false; null }
                    }
                    val expiration = when (val r = InputValidation.parseDate(expirationText, "bottle expiration", required = false)) {
                        is Validated.Ok -> r.value
                        is Validated.Invalid -> { expirationError = r.message; ok = false; null }
                    }
                    if (start != null) {
                        InputValidation.validateDateRange(start, end)?.let { endError = it; ok = false }
                    }

                    if (!ok || start == null) return@Button

                    val medication = (existing ?: Medication(
                        patientId = patientId,
                        ndc = ndcKey,
                        name = name,
                        strength = strength,
                        form = form,
                        dosageInstructions = dosageInstructions,
                        scheduleTimes = times,
                        startDate = start,
                        qrCode = qrCode,
                        purpose = purpose.trim().ifBlank { null }
                    )).copy(
                        ndc = ndcKey,
                        name = name,
                        strength = strength,
                        form = form,
                        dosageInstructions = dosageInstructions,
                        scheduleTimes = times,
                        startDate = start,
                        endDate = end,
                        bottleExpiration = expiration,
                        pillCountEntered = pillCount.toIntOrNull(),
                        isException = isException,
                        purpose = purpose.trim().ifBlank { null }
                    )
                    scope.launch {
                        // Edits that change name/NDC/strength/form archive the old record and create a new one;
                        // any other edit updates in place. Either way no history is lost.
                        repository.saveMedicationEdit(existing, medication)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save medication")
            }

            if (existing != null) {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Delete medication") }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this medication?") },
            text = {
                Text(
                    "It will no longer appear on the dashboard or in the patient's medication list. " +
                        "Every past administration record is kept and still appears in exports."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val id = existing?.id
                    if (id != null) scope.launch { repository.archiveMedication(id); onDone() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}
