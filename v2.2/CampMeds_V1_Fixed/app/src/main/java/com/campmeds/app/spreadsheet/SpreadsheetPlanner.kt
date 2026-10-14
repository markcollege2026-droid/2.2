package com.campmeds.app.spreadsheet

import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.domain.AdministrationTimeRules
import com.campmeds.app.domain.InputValidation
import com.campmeds.app.domain.MedicationIdentity
import com.campmeds.app.domain.Validated
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * What one cell says about a field:
 *  - [Keep]  blank cell: no change (the default for every field),
 *  - [Clear] the explicit [CLEAR_TOKEN] on an optional field of an UPDATE import,
 *  - [Value] a validated new value.
 */
internal sealed class Input<out T> {
    object Keep : Input<Nothing>()
    object Clear : Input<Nothing>()
    data class Value<out T>(val value: T) : Input<T>()
}

internal fun <T> Input<T>.valueOrNull(): T? = if (this is Input.Value<T>) this.value else null

/**
 * Turns a reviewed spreadsheet into an [ImportPlan]. Pure: it reads the [ImportSnapshot] it is given and
 * writes nothing, so a cancelled import provably changes nothing.
 *
 * Rules, in short:
 *  - Records are matched only by permanent ID (UPDATE) or by an explicit administrator decision (NEW).
 *    Row position, row order, and names alone never identify a record.
 *  - Rows for the same patient are one patient; their patient details must agree.
 *  - A blank cell never changes or clears anything. [CLEAR_TOKEN] clears only the optional fields.
 *  - Nothing is deleted or archived because a row is missing from the sheet.
 *  - Medications are never given a dose record: an import creates or edits definitions only.
 */
object SpreadsheetPlanner {

    fun plan(
        sheet: RawSheet,
        columns: Map<Field, Int>,
        mode: ImportMode,
        snapshot: ImportSnapshot,
        decisions: ImportDecisions = ImportDecisions()
    ): ImportPlan = Run(sheet, columns, mode, snapshot, decisions).execute()

    private val ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    internal fun isWellFormedId(text: String): Boolean = ID_PATTERN.matches(text)

    /** Lower-case, single-spaced name used to group rows and to find same-named patients. */
    internal fun nameKey(first: String, last: String): String =
        "$first $last".trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Name key of an existing patient. Legacy records that only have a full name are keyed by that name. */
    internal fun patientNameKey(p: Patient): String =
        if (p.firstName.isBlank() && p.lastName.isBlank()) nameKey(p.name, "") else nameKey(p.firstName, p.lastName)

    /** Same name, strength and form: the identity used to recognise a medication that is already there. */
    internal fun sameIdentity(a: Medication, b: Medication): Boolean =
        a.name.trim().equals(b.name.trim(), ignoreCase = true) &&
            a.strength.trim().equals(b.strength.trim(), ignoreCase = true) &&
            a.form.trim().equals(b.form.trim(), ignoreCase = true)

    private class RowIssues(val rowNumber: Int) {
        val problems = mutableListOf<Issue>()
        fun fail(field: String, message: String) {
            problems += Issue(IssueKind.INVALID_ROW, rowNumber, field, message)
        }
    }

    private data class PatientInput(
        val first: Input<String>,
        val last: Input<String>,
        val dob: Input<LocalDate>,
        val notes: Input<String>,
        val conditions: Input<String>
    )

    private data class MedicationInput(
        val name: Input<String>,
        val ndc: Input<String>,
        val strength: Input<String>,
        val form: Input<String>,
        val dosage: Input<String>,
        val schedule: Input<List<LocalTime>>,
        val start: Input<LocalDate>,
        val end: Input<LocalDate>,
        val expiration: Input<LocalDate>,
        val purpose: Input<String>,
        val prn: Input<Boolean>
    )

    private data class ParsedRow(
        val rowNumber: Int,
        val patientId: String,
        val medicationId: String,
        val patient: PatientInput,
        val medication: MedicationInput?
    )

    private sealed class Target {
        data class New(val key: String) : Target()
        data class Existing(val id: String) : Target()
    }

    private class Run(
        val sheet: RawSheet,
        val columns: Map<Field, Int>,
        val mode: ImportMode,
        val snapshot: ImportSnapshot,
        val decisions: ImportDecisions
    ) {
        val issues = mutableListOf<Issue>()
        val patientsById = snapshot.patients.associateBy { it.id }
        val medicationsById = snapshot.medications.associateBy { it.id }

        fun execute(): ImportPlan {
            val rows = sheet.rows.filter { row -> row.cells.any { it.isNotBlank() } }
            val parsed = rows.mapNotNull { parseRow(it) }
            return if (mode == ImportMode.NEW_RECORDS) planNew(parsed, rows.size) else planUpdate(parsed, rows.size)
        }

        // ------------------------------------------------------------------ cell parsing

        fun <T> clearOrFail(field: Field, rc: RowIssues): Input<T> {
            return if (mode == ImportMode.UPDATE_EXISTING && field in Field.CLEARABLE) {
                Input.Clear
            } else {
                rc.fail(
                    field.header,
                    "${field.header} cannot be emptied with $CLEAR_TOKEN here. Leave the cell blank to keep the current value."
                )
                Input.Keep
            }
        }

        fun text(raw: RawRow, field: Field, rc: RowIssues): Input<String> {
            val value = raw.value(columns[field])
            return when {
                value.isEmpty() -> Input.Keep
                value.equals(CLEAR_TOKEN, ignoreCase = true) -> clearOrFail(field, rc)
                else -> Input.Value(value)
            }
        }

        fun date(raw: RawRow, field: Field, rc: RowIssues): Input<LocalDate> {
            val value = raw.value(columns[field])
            return when {
                value.isEmpty() -> Input.Keep
                value.equals(CLEAR_TOKEN, ignoreCase = true) -> clearOrFail(field, rc)
                else -> when (val parsed = InputValidation.parseDate(value, field.header, required = true)) {
                    is Validated.Ok -> parsed.value?.let { Input.Value(it) } ?: Input.Keep
                    is Validated.Invalid -> {
                        rc.fail(field.header, parsed.message)
                        Input.Keep
                    }
                }
            }
        }

        fun schedule(raw: RawRow, rc: RowIssues): Input<List<LocalTime>> {
            val value = raw.value(columns[Field.SCHEDULE_TIMES])
            return when {
                value.isEmpty() -> Input.Keep
                value.equals(CLEAR_TOKEN, ignoreCase = true) -> clearOrFail(Field.SCHEDULE_TIMES, rc)
                else -> when (val parsed = InputValidation.parseScheduleTimes(value)) {
                    is Validated.Ok -> Input.Value(parsed.value)
                    is Validated.Invalid -> {
                        rc.fail(Field.SCHEDULE_TIMES.header, parsed.message)
                        Input.Keep
                    }
                }
            }
        }

        fun prn(raw: RawRow, rc: RowIssues): Input<Boolean> = when (raw.value(columns[Field.PRN]).lowercase(Locale.ROOT)) {
            "" -> Input.Keep
            "yes", "true", "1" -> Input.Value(true)
            "no", "false", "0" -> Input.Value(false)
            else -> { rc.fail("PRN", "PRN must be Yes or No."); Input.Keep }
        }

        fun medicationInput(raw: RawRow, rc: RowIssues): MedicationInput? {
            val medicationFields = listOf(
                Field.MEDICATION, Field.NDC, Field.STRENGTH, Field.FORM, Field.DOSAGE_INSTRUCTIONS,
                Field.SCHEDULE_TIMES, Field.START_DATE, Field.END_DATE, Field.BOTTLE_EXPIRATION, Field.PURPOSE, Field.PRN
            )
            if (medicationFields.all { raw.value(columns[it]).isEmpty() }) return null
            return MedicationInput(
                name = text(raw, Field.MEDICATION, rc),
                ndc = text(raw, Field.NDC, rc),
                strength = text(raw, Field.STRENGTH, rc),
                form = text(raw, Field.FORM, rc),
                dosage = text(raw, Field.DOSAGE_INSTRUCTIONS, rc),
                schedule = schedule(raw, rc),
                start = date(raw, Field.START_DATE, rc),
                end = date(raw, Field.END_DATE, rc),
                expiration = date(raw, Field.BOTTLE_EXPIRATION, rc),
                purpose = text(raw, Field.PURPOSE, rc),
                prn = prn(raw, rc)
            )
        }

        // ------------------------------------------------------------------ row validation

        fun parseRow(raw: RawRow): ParsedRow? {
            val rc = RowIssues(raw.rowNumber)
            val patientIdText = raw.value(columns[Field.PATIENT_ID])
            val medicationIdText = raw.value(columns[Field.MEDICATION_ID])
            val patient = PatientInput(
                first = text(raw, Field.FIRST_NAME, rc),
                last = text(raw, Field.LAST_NAME, rc),
                dob = date(raw, Field.DATE_OF_BIRTH, rc),
                notes = text(raw, Field.NOTES, rc),
                conditions = text(raw, Field.CONDITIONS, rc)
            )
            val medication = medicationInput(raw, rc)

            if (mode == ImportMode.NEW_RECORDS) {
                if (patientIdText.isNotEmpty()) rc.fail("Patient ID", newRecordIdProblem(patientIdText))
                if (medicationIdText.isNotEmpty()) rc.fail("Medication ID", newRecordIdProblem(medicationIdText))
                if (patient.first !is Input.Value<*>) rc.fail("First Name", "First Name is required for a new patient.")
                if (medication != null) requireNewMedicationFields(medication, rc)
            } else {
                validateForUpdate(patientIdText, medicationIdText, medication, rc)
            }

            if (rc.problems.isNotEmpty()) {
                issues += rc.problems
                return null
            }
            return ParsedRow(
                rowNumber = raw.rowNumber,
                patientId = patientIdText.lowercase(Locale.ROOT),
                medicationId = medicationIdText.lowercase(Locale.ROOT),
                patient = patient,
                medication = medication
            )
        }

        fun newRecordIdProblem(id: String): String {
            val key = id.lowercase(Locale.ROOT)
            return when {
                !isWellFormedId(id) ->
                    "\"$id\" is not a valid CampMeds ID. Clear this cell to import the row as a new record."
                patientsById.containsKey(key) || medicationsById.containsKey(key) ->
                    "This record already exists in CampMeds. Use Update existing records to change it, or clear this cell to add a new record."
                else ->
                    "This ID is not in CampMeds. IDs cannot be made up; clear this cell to import the row as a new record."
            }
        }

        fun requireNewMedicationFields(m: MedicationInput, rc: RowIssues) {
            if (m.prn !is Input.Value<*>) rc.fail("PRN", "PRN (Yes or No) is required for each new medication.")
            if (m.name !is Input.Value<*>) rc.fail("Medication", "Medication name is required when other medication columns are filled in.")
            if (m.dosage !is Input.Value<*>) rc.fail("Dosage Instructions", "Dosage instructions are required for a new medication.")
            if (m.schedule !is Input.Value<*>) rc.fail("Schedule Times", "At least one schedule time is required, for example 08:00 or 08:00, 20:00.")
            if (m.start !is Input.Value<*>) rc.fail("Start Date", "Start date is required for a new medication, for example 2026-07-01.")
            val start = m.start.valueOrNull()
            val end = m.end.valueOrNull()
            if (start != null && end != null) {
                InputValidation.validateDateRange(start, end)?.let { rc.fail("End Date", it) }
            }
        }

        fun validateForUpdate(patientIdText: String, medicationIdText: String, medication: MedicationInput?, rc: RowIssues) {
            if (patientIdText.isEmpty()) {
                rc.fail(
                    "Patient ID",
                    "Patient ID is missing, so this row cannot be matched to an existing patient. Rows without IDs are new records: use Import new records."
                )
                return
            }
            if (!isWellFormedId(patientIdText)) {
                rc.fail("Patient ID", "\"$patientIdText\" is not a valid CampMeds patient ID. Do not retype IDs; re-export the template.")
                return
            }
            val patientKey = patientIdText.lowercase(Locale.ROOT)
            val patient = patientsById[patientKey]
            if (patient == null) {
                val message = if (medicationsById.containsKey(patientKey)) {
                    "This is a medication record ID, not a patient ID. Patient IDs belong in the Patient ID column."
                } else {
                    "No patient with this ID exists in CampMeds. IDs cannot be made up or reused."
                }
                rc.fail("Patient ID", message)
                return
            }
            if (patient.isArchived) {
                rc.fail("Patient ID", "This patient is archived and cannot be updated by import.")
                return
            }
            if (medicationIdText.isEmpty()) {
                if (medication != null) {
                    rc.fail(
                        "Medication ID",
                        "Medication ID is missing, so the medication columns on this row cannot be matched. A medication without an ID is new: use Import new records."
                    )
                }
                return
            }
            if (!isWellFormedId(medicationIdText)) {
                rc.fail("Medication ID", "\"$medicationIdText\" is not a valid CampMeds medication ID. Do not retype IDs; re-export the template.")
                return
            }
            val medicationKey = medicationIdText.lowercase(Locale.ROOT)
            val med = medicationsById[medicationKey]
            if (med == null) {
                val message = if (patientsById.containsKey(medicationKey)) {
                    "This is a patient record ID, not a medication ID. Medication IDs belong in the Medication ID column."
                } else {
                    "No medication with this ID exists in CampMeds. IDs cannot be made up or reused."
                }
                rc.fail("Medication ID", message)
                return
            }
            if (med.isArchived) {
                rc.fail("Medication ID", "This medication record is archived and cannot be updated by import. Change it in the app.")
                return
            }
            if (med.patientId != patientKey && med.id in snapshot.medicationIdsWithHistory) {
                rc.fail(
                    "Patient ID",
                    "This medication has administration history, so it cannot be moved to another patient by import. Make this change in the app."
                )
            }
        }

        // ------------------------------------------------------------------ merging rows

        fun <T> List<ParsedRow>.merged(select: (ParsedRow) -> Input<T>): Input<T> =
            map(select).firstOrNull { it !is Input.Keep } ?: Input.Keep

        fun List<ParsedRow>.mergedPatient(): PatientInput = PatientInput(
            first = merged { it.patient.first },
            last = merged { it.patient.last },
            dob = merged { it.patient.dob },
            notes = merged { it.patient.notes },
            conditions = merged { it.patient.conditions }
        )

        fun List<ParsedRow>.mergedMedication(): MedicationInput = MedicationInput(
            name = merged { it.medication?.name ?: Input.Keep },
            ndc = merged { it.medication?.ndc ?: Input.Keep },
            strength = merged { it.medication?.strength ?: Input.Keep },
            form = merged { it.medication?.form ?: Input.Keep },
            dosage = merged { it.medication?.dosage ?: Input.Keep },
            schedule = merged { it.medication?.schedule ?: Input.Keep },
            start = merged { it.medication?.start ?: Input.Keep },
            end = merged { it.medication?.end ?: Input.Keep },
            expiration = merged { it.medication?.expiration ?: Input.Keep },
            purpose = merged { it.medication?.purpose ?: Input.Keep },
            prn = merged { it.medication?.prn ?: Input.Keep }
        )

        /** True (and an issue is recorded) when rows for the same record give different non-blank values. */
        fun conflicts(rows: List<ParsedRow>, label: String, select: (ParsedRow) -> Input<*>): Boolean {
            val distinct = rows.map(select).filter { it !is Input.Keep }.distinct()
            if (distinct.size <= 1) return false
            val rowList = rows.joinToString(", ") { it.rowNumber.toString() }
            issues += Issue(
                IssueKind.CONFLICT,
                rows.minOf { it.rowNumber },
                label,
                "Rows $rowList give different values for $label on the same record. Make them the same, or leave the cell blank on all but one row."
            )
            return true
        }

        /**
         * Compares one field with the stored value. Returns the value to store (old value when unchanged or
         * blank), and records a [FieldChange] when something would really change.
         */
        fun <T> diffField(
            label: String,
            old: T?,
            input: Input<T>,
            render: (T?) -> String,
            significant: Boolean,
            out: MutableList<FieldChange>
        ): T? = when (input) {
            is Input.Keep -> old
            is Input.Clear -> {
                if (old != null && render(old).isNotBlank()) {
                    out += FieldChange(label, render(old), "", significant = true, clears = true)
                }
                null
            }
            is Input.Value<T> -> {
                if (input.value == old) {
                    old
                } else {
                    out += FieldChange(label, render(old), render(input.value), significant)
                    input.value
                }
            }
        }

        fun scheduleFor(times: List<LocalTime>): Pair<List<LocalTime>, List<String>> {
            val named = snapshot.administrationTimes.filter { it.time in times }
            val customs = times.filter { t -> named.none { it.time == t } }
            return AdministrationTimeRules.buildSchedule(named, customs)
        }

        fun medicationDraft(m: MedicationInput, patientId: String): Medication {
            val (times, timeIds) = scheduleFor(m.schedule.valueOrNull().orEmpty())
            return Medication(
                id = "",
                patientId = patientId,
                ndc = m.ndc.valueOrNull().orEmpty(),
                name = m.name.valueOrNull().orEmpty(),
                strength = m.strength.valueOrNull().orEmpty(),
                form = m.form.valueOrNull().orEmpty(),
                dosageInstructions = m.dosage.valueOrNull().orEmpty(),
                scheduleTimes = times,
                administrationTimeIds = timeIds,
                startDate = m.start.valueOrNull() ?: LocalDate.MIN,
                endDate = m.end.valueOrNull(),
                bottleExpiration = m.expiration.valueOrNull(),
                pillCountEntered = null,
                // Typed in by hand, not checked against the NDC directory. Never used as an NDC cache entry.
                isException = true,
                qrCode = "",
                requireQrScan = true,
                isGroupAdministered = false,
                prepackagedGroupId = null,
                purpose = m.purpose.valueOrNull(),
                isPrn = m.prn.valueOrNull() ?: false,
                isArchived = false
            )
        }

        // ------------------------------------------------------------------ NEW RECORDS

        fun planNew(parsed: List<ParsedRow>, rowsRead: Int): ImportPlan {
            val groups = parsed.groupBy { row ->
                nameKey(row.patient.first.valueOrNull().orEmpty(), row.patient.last.valueOrNull().orEmpty())
            }
            val patientCandidates = mutableListOf<PatientCandidate>()
            val patientCreates = mutableListOf<PatientCreate>()
            val patientLinks = mutableListOf<PatientLink>()
            val medicationCreates = mutableListOf<MedicationCreate>()
            val medicationCandidates = mutableListOf<MedicationCandidate>()
            val skipped = mutableListOf<Int>()
            val unresolved = mutableListOf<String>()

            for (groupKey in groups.keys.sorted()) {
                val rows = groups.getValue(groupKey).sortedBy { it.rowNumber }
                val rowNumbers = rows.map { it.rowNumber }
                val patientKey = "name:$groupKey"

                val conflicted = listOf(
                    conflicts(rows, "Date of Birth") { it.patient.dob },
                    conflicts(rows, "Notes") { it.patient.notes },
                    conflicts(rows, "Conditions") { it.patient.conditions }
                ).any { it }
                if (conflicted) {
                    skipped += rowNumbers
                    continue
                }

                val merged = rows.mergedPatient()
                val first = merged.first.valueOrNull().orEmpty()
                val last = merged.last.valueOrNull().orEmpty()
                val displayName = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
                val sheetDob = merged.dob.valueOrNull()
                val newPatient = Patient(
                    id = "",
                    name = displayName,
                    dob = sheetDob,
                    notes = merged.notes.valueOrNull(),
                    firstName = first,
                    lastName = last,
                    conditions = merged.conditions.valueOrNull()
                )

                val existing = snapshot.patients
                    .filter { !it.isArchived && patientNameKey(it) == groupKey }
                    .sortedBy { it.id }

                var target: Target? = null
                if (existing.isEmpty()) {
                    patientCreates += PatientCreate(patientKey, rowNumbers, newPatient)
                    target = Target.New(patientKey)
                } else {
                    patientCandidates += PatientCandidate(patientKey, displayName, rowNumbers, existing)
                    when (val choice = decisions.patientChoices[patientKey]) {
                        is PatientChoice.CreateNew -> {
                            patientCreates += PatientCreate(patientKey, rowNumbers, newPatient)
                            target = Target.New(patientKey)
                        }
                        is PatientChoice.UseExisting -> {
                            val match = existing.firstOrNull { it.id == choice.patientId }
                            if (match == null) {
                                unresolved += patientKey
                            } else if (sheetDob != null && match.dob != null && match.dob != sheetDob) {
                                issues += Issue(
                                    IssueKind.CONFLICT,
                                    rowNumbers.first(),
                                    "Date of Birth",
                                    "The spreadsheet's Date of Birth for ${displayName} differs from the existing record. Resolve it in the app, or use Update existing records after correcting the sheet."
                                )
                                skipped += rowNumbers
                            } else {
                                if (sheetDob != null && match.dob == null) {
                                    issues += Issue(
                                        IssueKind.NOTICE,
                                        rowNumbers.first(),
                                        "Date of Birth",
                                        "The existing record for $displayName has no Date of Birth. The spreadsheet value was not applied; use Update existing records to change patient details."
                                    )
                                }
                                patientLinks += PatientLink(patientKey, match.id, displayName, rowNumbers)
                                target = Target.Existing(match.id)
                            }
                        }
                        null -> unresolved += patientKey
                    }
                }

                val resolved = target ?: continue
                val existingMedications = if (resolved is Target.Existing) {
                    snapshot.medications.filter { it.patientId == resolved.id && !it.isArchived }
                } else {
                    emptyList()
                }
                val plannedInGroup = mutableListOf<Medication>()

                for (row in rows) {
                    val m = row.medication ?: continue
                    val draft = medicationDraft(
                        m,
                        patientId = (resolved as? Target.Existing)?.id.orEmpty()
                    )
                    val duplicate = existingMedications.firstOrNull { sameIdentity(it, draft) }
                    val withinSheet = plannedInGroup.any { sameIdentity(it, draft) }
                    plannedInGroup += draft
                    val reason = when {
                        duplicate != null -> "This patient already has a medication with the same name, strength and form."
                        withinSheet -> "Another row in this spreadsheet has the same medication, strength and form."
                        else -> null
                    }
                    val newPatientKey = (resolved as? Target.New)?.key
                    if (reason == null) {
                        medicationCreates += MedicationCreate(row.rowNumber, newPatientKey, draft, displayName)
                    } else {
                        val key = "row:${row.rowNumber}"
                        medicationCandidates += MedicationCandidate(key, row.rowNumber, draft.name, reason)
                        when (decisions.medicationChoices[key]) {
                            MedicationChoice.ADD -> medicationCreates += MedicationCreate(row.rowNumber, newPatientKey, draft, displayName)
                            MedicationChoice.SKIP -> skipped += row.rowNumber
                            null -> unresolved += key
                        }
                    }
                }
            }

            val linkedIds = patientLinks.map { it.patientId }.toSet()
            val absentPatients = snapshot.patients
                .filter { !it.isArchived && it.id !in linkedIds && patientNameKey(it) !in groups.keys }
                .map { AbsentRecord("PATIENT", it.id, it.name) }

            return ImportPlan(
                mode = ImportMode.NEW_RECORDS,
                rowsRead = rowsRead,
                patientCandidates = patientCandidates,
                medicationCandidates = medicationCandidates,
                patientCreates = patientCreates,
                patientLinks = patientLinks,
                patientUpdates = emptyList(),
                medicationCreates = medicationCreates,
                medicationUpdates = emptyList(),
                skippedRows = skipped.distinct().sorted(),
                issues = issues.sortedBy { it.rowNumber ?: 0 },
                absentRecords = absentPatients,
                unresolvedDecisions = unresolved.distinct()
            )
        }

        // ------------------------------------------------------------------ UPDATE EXISTING

        fun planUpdate(parsed: List<ParsedRow>, rowsRead: Int): ImportPlan {
            val patientUpdates = mutableListOf<PatientUpdate>()
            val medicationUpdates = mutableListOf<MedicationUpdate>()
            val skipped = mutableListOf<Int>()
            val referencedPatients = mutableSetOf<String>()
            val referencedMedications = mutableSetOf<String>()

            val byPatient = parsed.groupBy { it.patientId }
            for (patientId in byPatient.keys.sorted()) {
                val rows = byPatient.getValue(patientId).sortedBy { it.rowNumber }
                referencedPatients += patientId
                val patient = patientsById[patientId] ?: continue

                val conflicted = listOf(
                    conflicts(rows, "First Name") { it.patient.first },
                    conflicts(rows, "Last Name") { it.patient.last },
                    conflicts(rows, "Date of Birth") { it.patient.dob },
                    conflicts(rows, "Notes") { it.patient.notes },
                    conflicts(rows, "Conditions") { it.patient.conditions }
                ).any { it }
                if (conflicted) {
                    skipped += rows.map { it.rowNumber }
                    continue
                }

                val merged = rows.mergedPatient()
                val changes = mutableListOf<FieldChange>()
                val first = diffField("First Name", patient.firstName, merged.first, { it ?: "" }, true, changes)
                    ?: patient.firstName
                val last = diffField("Last Name", patient.lastName, merged.last, { it ?: "" }, true, changes) ?: ""
                val dob = diffField("Date of Birth", patient.dob, merged.dob, { dateText(it) }, true, changes)
                val notes = diffField("Notes", patient.notes, merged.notes, { it ?: "" }, true, changes)
                val conditions = diffField("Conditions", patient.conditions, merged.conditions, { it ?: "" }, true, changes)
                if (changes.isNotEmpty()) {
                    val target = patient.copy(
                        firstName = first,
                        lastName = last,
                        dob = dob,
                        notes = notes?.trim()?.ifBlank { null },
                        conditions = conditions?.trim()?.ifBlank { null }
                    )
                    patientUpdates += PatientUpdate(
                        patientId = patientId,
                        displayName = patient.name,
                        rows = rows.map { it.rowNumber },
                        target = target,
                        changes = changes
                    )
                }
            }

            val byMedication = parsed.filter { it.medicationId.isNotEmpty() }.groupBy { it.medicationId }
            for (medicationId in byMedication.keys.sorted()) {
                val rows = byMedication.getValue(medicationId).sortedBy { it.rowNumber }
                val med = medicationsById[medicationId] ?: continue
                referencedMedications += medicationId
                referencedPatients += rows.map { it.patientId }

                val conflicted = listOf(
                    conflicts(rows, "Patient ID") { Input.Value(it.patientId) },
                    conflicts(rows, "Medication") { it.medication?.name ?: Input.Keep },
                    conflicts(rows, "NDC") { it.medication?.ndc ?: Input.Keep },
                    conflicts(rows, "Strength") { it.medication?.strength ?: Input.Keep },
                    conflicts(rows, "Form") { it.medication?.form ?: Input.Keep },
                    conflicts(rows, "Dosage Instructions") { it.medication?.dosage ?: Input.Keep },
                    conflicts(rows, "Schedule Times") { it.medication?.schedule ?: Input.Keep },
                    conflicts(rows, "Start Date") { it.medication?.start ?: Input.Keep },
                    conflicts(rows, "End Date") { it.medication?.end ?: Input.Keep },
                    conflicts(rows, "Bottle Expiration") { it.medication?.expiration ?: Input.Keep },
                    conflicts(rows, "Purpose") { it.medication?.purpose ?: Input.Keep },
                    conflicts(rows, "PRN") { it.medication?.prn ?: Input.Keep }
                ).any { it }
                if (conflicted) {
                    skipped += rows.map { it.rowNumber }
                    continue
                }

                val m = rows.mergedMedication()
                val patientId = rows.first().patientId
                val changes = mutableListOf<FieldChange>()
                val name = diffField("Medication", med.name, m.name, { it ?: "" }, true, changes) ?: med.name
                val ndc = diffField("NDC", med.ndc, m.ndc, { it ?: "" }, true, changes) ?: med.ndc
                val strength = diffField("Strength", med.strength, m.strength, { it ?: "" }, true, changes) ?: med.strength
                val form = diffField("Form", med.form, m.form, { it ?: "" }, true, changes) ?: med.form
                val dosage = diffField("Dosage Instructions", med.dosageInstructions, m.dosage, { it ?: "" }, true, changes)
                    ?: med.dosageInstructions
                val oldTimes = med.scheduleTimes.sorted()
                val newTimes = diffField("Schedule Times", oldTimes, m.schedule, { timesText(it) }, true, changes)
                    ?: oldTimes
                val start = diffField("Start Date", med.startDate, m.start, { dateText(it) }, true, changes) ?: med.startDate
                val end = diffField("End Date", med.endDate, m.end, { dateText(it) }, true, changes)
                val expiration = diffField("Bottle Expiration", med.bottleExpiration, m.expiration, { dateText(it) }, false, changes)
                val prn = diffField("PRN", med.isPrn, m.prn, { it?.toString().orEmpty() }, true, changes) ?: med.isPrn
                val purpose = diffField("Purpose", med.purpose, m.purpose, { it ?: "" }, false, changes)?.ifBlank { null }

                if (patientId != med.patientId) {
                    changes += FieldChange(
                        "Patient",
                        patientsById[med.patientId]?.name.orEmpty(),
                        patientsById[patientId]?.name.orEmpty(),
                        significant = true
                    )
                }

                val datesChanged = start != med.startDate || end != med.endDate
                val rangeError = if (datesChanged && end != null) InputValidation.validateDateRange(start, end) else null
                if (rangeError != null) {
                    rows.forEach { issues += Issue(IssueKind.INVALID_ROW, it.rowNumber, "End Date", rangeError) }
                    skipped += rows.map { it.rowNumber }
                    continue
                }

                if (changes.isEmpty()) continue

                val (times, timeIds) = if (newTimes != oldTimes) scheduleFor(newTimes) else Pair(med.scheduleTimes, med.administrationTimeIds)
                val target = med.copy(
                    patientId = patientId,
                    name = name,
                    ndc = ndc,
                    strength = strength,
                    form = form,
                    dosageInstructions = dosage,
                    scheduleTimes = times,
                    administrationTimeIds = timeIds,
                    startDate = start,
                    endDate = end,
                    bottleExpiration = expiration,
                    purpose = purpose,
                    isPrn = prn
                )
                medicationUpdates += MedicationUpdate(
                    medicationId = medicationId,
                    patientName = patientsById[patientId]?.name.orEmpty(),
                    rows = rows.map { it.rowNumber },
                    target = target,
                    changes = changes,
                    identityChanged = MedicationIdentity.changed(med, target)
                )
            }

            val absentPatients = snapshot.patients
                .filter { !it.isArchived && it.id !in referencedPatients }
                .map { AbsentRecord("PATIENT", it.id, it.name) }
            val absentMedications = snapshot.medications
                .filter { !it.isArchived && it.id !in referencedMedications }
                .map { AbsentRecord("MEDICATION", it.id, it.name) }

            return ImportPlan(
                mode = ImportMode.UPDATE_EXISTING,
                rowsRead = rowsRead,
                patientCandidates = emptyList(),
                medicationCandidates = emptyList(),
                patientCreates = emptyList(),
                patientLinks = emptyList(),
                patientUpdates = patientUpdates,
                medicationCreates = emptyList(),
                medicationUpdates = medicationUpdates,
                skippedRows = skipped.distinct().sorted(),
                issues = issues.sortedBy { it.rowNumber ?: 0 },
                absentRecords = absentPatients + absentMedications,
                unresolvedDecisions = emptyList()
            )
        }
    }
}
