package com.campmeds.app.spreadsheet

import com.campmeds.app.data.entity.AdministrationTime
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure data model of a spreadsheet import (no Android, no Room). The planner produces an [ImportPlan]
 * from the spreadsheet and a snapshot of the database; nothing in this file writes anything.
 */

enum class ImportMode { NEW_RECORDS, UPDATE_EXISTING }

/** INVALID_ROW: the row is skipped and nothing from it is applied. CONFLICT: the affected record is skipped. NOTICE: information only. */
enum class IssueKind { INVALID_ROW, CONFLICT, NOTICE }

data class Issue(val kind: IssueKind, val rowNumber: Int?, val field: String?, val message: String)

/**
 * One field that would change. [significant] changes (identity, dose, schedule, dates, patient, patient
 * details) need explicit administrator confirmation. [clears] is true when an explicit [CLEAR_TOKEN] would
 * empty an existing value.
 */
data class FieldChange(
    val field: String,
    val oldValue: String,
    val newValue: String,
    val significant: Boolean,
    val clears: Boolean = false
)

/** A spreadsheet patient whose name matches existing patient record(s). The administrator must decide. */
data class PatientCandidate(
    val key: String,
    val displayName: String,
    val rows: List<Int>,
    val matches: List<Patient>
)

/** A medication row that looks like one already in CampMeds, or like another row in the same sheet. */
data class MedicationCandidate(
    val key: String,
    val rowNumber: Int,
    val displayName: String,
    val reason: String
)

/** [draft] has an empty id; the id is generated when the import is applied. */
data class PatientCreate(val key: String, val rows: List<Int>, val draft: Patient)

/** Medication rows that will be attached to an existing patient (confirmed by the administrator). */
data class PatientLink(val key: String, val patientId: String, val displayName: String, val rows: List<Int>)

data class PatientUpdate(val patientId: String, val displayName: String, val rows: List<Int>, val target: Patient, val changes: List<FieldChange>)

/**
 * [draft] has empty id and qrCode (generated on apply). [newPatientKey] is set when the medication belongs to
 * a patient that is itself being created by this import; otherwise draft.patientId is the existing patient.
 */
data class MedicationCreate(val rowNumber: Int, val newPatientKey: String?, val draft: Medication, val patientLabel: String)

/**
 * [identityChanged] (name, NDC, strength or form) follows the app's existing rule: the old record is archived
 * with its history and a new record with a new id and QR code replaces it. The change is listed and confirmed.
 */
data class MedicationUpdate(
    val medicationId: String,
    val patientName: String,
    val rows: List<Int>,
    val target: Medication,
    val changes: List<FieldChange>,
    val identityChanged: Boolean
)

/** Informational only. Absence from a spreadsheet never causes anything to be deleted or archived. */
data class AbsentRecord(val type: String, val id: String, val label: String)

data class ImportPlan(
    val mode: ImportMode,
    val rowsRead: Int,
    val patientCandidates: List<PatientCandidate>,
    val medicationCandidates: List<MedicationCandidate>,
    val patientCreates: List<PatientCreate>,
    val patientLinks: List<PatientLink>,
    val patientUpdates: List<PatientUpdate>,
    val medicationCreates: List<MedicationCreate>,
    val medicationUpdates: List<MedicationUpdate>,
    val skippedRows: List<Int>,
    val issues: List<Issue>,
    val absentRecords: List<AbsentRecord>,
    /** Keys of candidates that still need an administrator decision. */
    val unresolvedDecisions: List<String>
) {
    val hasChanges: Boolean
        get() = patientCreates.isNotEmpty() || medicationCreates.isNotEmpty() ||
            patientUpdates.isNotEmpty() || medicationUpdates.isNotEmpty()

    /** True when at least one significant change or explicit clear needs an explicit confirmation tick. */
    val requiresConfirmation: Boolean
        get() = patientUpdates.any { u -> u.changes.any { it.significant } } ||
            medicationUpdates.any { u -> u.changes.any { it.significant } }

    val invalidRowCount: Int get() = issues.filter { it.kind == IssueKind.INVALID_ROW }.mapNotNull { it.rowNumber }.distinct().size

    /**
     * Everything that would be written, in a stable order. The import is applied only if a fresh plan made
     * inside the database transaction has exactly the same signature as the plan the administrator reviewed.
     */
    fun signature(): String = buildList {
        add("mode=$mode")
        patientCreates.forEach { add("create-patient|${it.key}|${it.rows}|${it.draft}") }
        patientLinks.forEach { add("link-patient|${it.key}|${it.patientId}|${it.rows}") }
        patientUpdates.forEach { add("update-patient|${it.patientId}|${it.rows}|${it.target}|${it.changes}") }
        medicationCreates.forEach { add("create-medication|${it.rowNumber}|${it.newPatientKey}|${it.draft}") }
        medicationUpdates.forEach {
            add("update-medication|${it.medicationId}|${it.rows}|${it.target}|${it.changes}|${it.identityChanged}")
        }
        skippedRows.forEach { add("skipped|$it") }
        issues.forEach { add("issue|${it.kind}|${it.rowNumber}|${it.field}|${it.message}") }
        patientCandidates.forEach { add("patient-candidate|${it.key}|${it.rows}|${it.matches.map { m -> m.id }}") }
        medicationCandidates.forEach { add("medication-candidate|${it.key}|${it.rowNumber}") }
        unresolvedDecisions.forEach { add("unresolved|$it") }
    }.joinToString("\n")
}

/** The administrator's answers. Keys come from the plan's candidates; unknown keys are ignored. */
data class ImportDecisions(
    val patientChoices: Map<String, PatientChoice> = emptyMap(),
    val medicationChoices: Map<String, MedicationChoice> = emptyMap(),
    val confirmSignificantChanges: Boolean = false
)

sealed class PatientChoice {
    /** The spreadsheet patient is a different person: create a new patient record. */
    object CreateNew : PatientChoice()

    /** The spreadsheet patient is an existing record: attach the medication rows to it. */
    data class UseExisting(val patientId: String) : PatientChoice()
}

enum class MedicationChoice { ADD, SKIP }

/** Everything the planner reads from the database. Loaded inside the import transaction. */
data class ImportSnapshot(
    val patients: List<Patient>,
    val medications: List<Medication>,
    val administrationTimes: List<AdministrationTime>,
    val medicationIdsWithHistory: Set<String>
)

/** Why a plan may not be applied yet, or null when it may. */
fun ImportPlan.blockingReason(decisions: ImportDecisions): String? = when {
    unresolvedDecisions.isNotEmpty() ->
        "Resolve every possible duplicate before importing (${unresolvedDecisions.size} still open)."
    requiresConfirmation && !decisions.confirmSignificantChanges ->
        "Review and confirm the significant changes before importing."
    else -> null
}

/** Shared by the planner and the UI: the planned dates of an existing patient-detail value, for messages. */
internal fun timesText(times: List<LocalTime>?): String =
    times?.joinToString(", ") { it.format(com.campmeds.app.domain.AdministrationTimeRules.HH_MM) }.orEmpty()

internal fun dateText(date: LocalDate?): String = date?.toString().orEmpty()
