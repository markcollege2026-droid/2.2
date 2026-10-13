package com.campmeds.app.repository

import androidx.room.withTransaction
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.DatabaseBackupManager
import com.campmeds.app.data.entity.ChangeHistoryEntry
import com.campmeds.app.data.entity.ImportHistoryEntry
import com.campmeds.app.data.entity.Role
import com.campmeds.app.data.entity.User
import com.campmeds.app.spreadsheet.Field
import com.campmeds.app.spreadsheet.FieldChange
import com.campmeds.app.spreadsheet.ImportDecisions
import com.campmeds.app.spreadsheet.ImportMode
import com.campmeds.app.spreadsheet.ImportPlan
import com.campmeds.app.spreadsheet.ImportSnapshot
import com.campmeds.app.spreadsheet.RawSheet
import com.campmeds.app.spreadsheet.SpreadsheetPlanner
import com.campmeds.app.spreadsheet.blockingReason
import java.time.LocalDateTime
import java.util.UUID

/**
 * Applies a reviewed spreadsheet import to the local database. The only write path for spreadsheet data.
 *
 * Guarantees:
 *  - Nothing is written until the administrator has confirmed. Cancelling never calls [apply].
 *  - Admin role is required here too, not only in the UI.
 *  - The plan is recomputed inside the transaction and must have exactly the signature the administrator
 *    reviewed. If anything changed in between, nothing is applied.
 *  - All writes happen in one Room transaction. Any exception rolls the whole import back.
 *  - A verified recovery copy is taken before the transaction starts.
 *  - Every attempt leaves an [ImportHistoryEntry] (COMPLETED, FAILED or REJECTED) without patient values.
 *  - Administration history (DoseLog) is never read for writing, changed, or deleted.
 *  - Archiving is never used for an import that is missing rows: absence means "not mentioned", nothing more.
 */
class SpreadsheetImportRepository(
    private val db: AppDatabase,
    private val backups: DatabaseBackupManager?,
    /** Test hook: runs inside the transaction just before commit, so a throw proves the rollback. */
    private val beforeCommit: suspend () -> Unit = {}
) {

    sealed class Outcome {
        data class Completed(val summary: Summary) : Outcome()
        data class Rejected(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    data class Summary(
        val importId: String,
        val newPatients: Int,
        val newMedications: Int,
        val patientsUpdated: Int,
        val medicationsUpdated: Int,
        val fieldsChanged: Int,
        val rowsSkipped: Int,
        val backupFileName: String?
    )

    private class PlanChangedException : Exception("The database changed after the preview.")

    suspend fun loadSnapshot(): ImportSnapshot = ImportSnapshot(
        patients = db.patientDao().getAllIncludingArchived(),
        medications = db.medicationDao().getAllIncludingArchived(),
        administrationTimes = db.administrationTimeDao().getAll(),
        medicationIdsWithHistory = db.doseLogDao().medicationIdsWithHistory().toSet()
    )

    /** Read-only. Used for the preview; never writes. */
    suspend fun preview(
        sheet: RawSheet,
        columns: Map<Field, Int>,
        mode: ImportMode,
        decisions: ImportDecisions
    ): ImportPlan = SpreadsheetPlanner.plan(sheet, columns, mode, loadSnapshot(), decisions)

    suspend fun apply(
        actor: User?,
        sheet: RawSheet,
        columns: Map<Field, Int>,
        mode: ImportMode,
        decisions: ImportDecisions,
        reviewedSignature: String
    ): Outcome {
        if (actor == null || actor.role != Role.ADMIN) {
            return Outcome.Rejected("Only an Administrator can import spreadsheets.")
        }
        val importId = UUID.randomUUID().toString()
        var backupName: String? = null
        return try {
            val preview = SpreadsheetPlanner.plan(sheet, columns, mode, loadSnapshot(), decisions)
            preview.blockingReason(decisions)?.let {
                return reject(importId, sheet, mode, null, it)
            }
            if (preview.signature() != reviewedSignature) {
                return reject(importId, sheet, mode, null, "The database changed after the preview. Run the preview again.")
            }
            if (!preview.hasChanges) {
                return Outcome.Completed(
                    Summary(importId, 0, 0, 0, 0, 0, preview.skippedRowCount(), null)
                )
            }

            backupName = backups?.createVerifiedBackup(db)?.name

            var summary: Summary? = null
            db.withTransaction {
                val plan = SpreadsheetPlanner.plan(sheet, columns, mode, loadSnapshot(), decisions)
                if (plan.signature() != reviewedSignature) throw PlanChangedException()
                summary = writePlan(plan, importId, actor, sheet.fileName, mode, backupName)
                beforeCommit()
            }
            Outcome.Completed(checkNotNull(summary))
        } catch (e: PlanChangedException) {
            reject(importId, sheet, mode, backupName, "The database changed after the preview. Nothing was imported; run the preview again.")
        } catch (e: Exception) {
            recordOutcome(importId, sheet.fileName, mode, "FAILED", backupName, "Rolled back: ${e.javaClass.simpleName}")
            Outcome.Failed("The import failed and every change was rolled back. Nothing was imported. Your data is unchanged.")
        }
    }

    private suspend fun reject(
        importId: String,
        sheet: RawSheet,
        mode: ImportMode,
        backupName: String?,
        message: String
    ): Outcome {
        recordOutcome(importId, sheet.fileName, mode, "REJECTED", backupName, message)
        return Outcome.Rejected(message)
    }

    /** Writes the plan. Runs inside the caller's transaction. */
    private suspend fun writePlan(
        plan: ImportPlan,
        importId: String,
        actor: User,
        fileName: String,
        mode: ImportMode,
        backupName: String?
    ): Summary {
        val now = LocalDateTime.now()
        val changes = mutableListOf<ChangeHistoryEntry>()
        val patientIdByKey = mutableMapOf<String, String>()

        for (create in plan.patientCreates) {
            val id = UUID.randomUUID().toString()
            patientIdByKey[create.key] = id
            db.patientDao().upsert(normalizePatient(create.draft.copy(id = id)))
        }

        for (update in plan.patientUpdates) {
            db.patientDao().upsert(normalizePatient(update.target))
            update.changes.forEach { changes += historyEntry(importId, now, actor, "PATIENT", update.patientId, it) }
        }

        for (create in plan.medicationCreates) {
            val patientId = create.newPatientKey?.let { patientIdByKey[it] } ?: create.draft.patientId
            check(patientId.isNotEmpty()) { "A medication row has no patient." }
            db.medicationDao().upsert(
                create.draft.copy(
                    id = UUID.randomUUID().toString(),
                    patientId = patientId,
                    qrCode = UUID.randomUUID().toString(),
                    isException = true,
                    isArchived = false
                )
            )
        }

        for (update in plan.medicationUpdates) {
            if (update.identityChanged) {
                // Same rule as the app's medication form: the old record is archived (history and export sheet
                // kept) and the changed medication becomes a new record with its own id and QR code.
                val replacement = update.target.copy(
                    id = UUID.randomUUID().toString(),
                    qrCode = UUID.randomUUID().toString(),
                    isArchived = false
                )
                db.medicationDao().archive(update.medicationId)
                db.medicationDao().upsert(replacement)
                changes += ChangeHistoryEntry(
                    importId = importId,
                    changedAt = now,
                    actorUserId = actor.id,
                    recordType = "MEDICATION",
                    recordId = update.medicationId,
                    field = "Replaced by new record",
                    oldValue = null,
                    newValue = replacement.id,
                    significant = true
                )
            } else {
                db.medicationDao().upsert(update.target)
            }
            update.changes.forEach { changes += historyEntry(importId, now, actor, "MEDICATION", update.medicationId, it) }
        }

        if (changes.isNotEmpty()) db.importHistoryDao().insertChanges(changes)

        val skipped = plan.skippedRowCount()
        val summary = Summary(
            importId = importId,
            newPatients = plan.patientCreates.size,
            newMedications = plan.medicationCreates.size,
            patientsUpdated = plan.patientUpdates.size,
            medicationsUpdated = plan.medicationUpdates.size,
            fieldsChanged = changes.size,
            rowsSkipped = skipped,
            backupFileName = backupName
        )
        db.importHistoryDao().insertImport(
            ImportHistoryEntry(
                id = importId,
                createdAt = now,
                sourceFileName = fileName,
                mode = mode.name,
                outcome = "COMPLETED",
                backupFileName = backupName,
                newPatients = summary.newPatients,
                newMedications = summary.newMedications,
                patientsUpdated = summary.patientsUpdated,
                medicationsUpdated = summary.medicationsUpdated,
                fieldsChanged = summary.fieldsChanged,
                rowsSkipped = summary.rowsSkipped,
                message = null
            )
        )
        return summary
    }

    private fun historyEntry(
        importId: String,
        now: LocalDateTime,
        actor: User,
        recordType: String,
        recordId: String,
        change: FieldChange
    ): ChangeHistoryEntry {
        // Patient details are recorded by field name only; medication values are kept for safety review.
        val redact = recordType == "PATIENT"
        return ChangeHistoryEntry(
            importId = importId,
            changedAt = now,
            actorUserId = actor.id,
            recordType = recordType,
            recordId = recordId,
            field = change.field,
            oldValue = if (redact) null else change.oldValue.ifBlank { null },
            newValue = if (redact) null else change.newValue.ifBlank { null },
            significant = change.significant
        )
    }

    private suspend fun recordOutcome(
        importId: String,
        fileName: String,
        mode: ImportMode,
        outcome: String,
        backupName: String?,
        message: String
    ) {
        // Best effort: a failure to write the audit line must not hide the real result from the administrator.
        runCatching {
            db.importHistoryDao().insertImport(
                ImportHistoryEntry(
                    id = importId,
                    createdAt = LocalDateTime.now(),
                    sourceFileName = fileName,
                    mode = mode.name,
                    outcome = outcome,
                    backupFileName = backupName,
                    newPatients = 0,
                    newMedications = 0,
                    patientsUpdated = 0,
                    medicationsUpdated = 0,
                    fieldsChanged = 0,
                    rowsSkipped = 0,
                    message = message
                )
            )
        }
    }

    private fun ImportPlan.skippedRowCount(): Int =
        (skippedRows + issues.mapNotNull { it.rowNumber }).distinct().size
}
