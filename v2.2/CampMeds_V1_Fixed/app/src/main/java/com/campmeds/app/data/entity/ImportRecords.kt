package com.campmeds.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime
import java.util.UUID

/**
 * One row per spreadsheet import attempt that reached the database (completed, failed, or rejected). It holds
 * the source file name and counts only, never patient or medication values, so it is safe to show and keep.
 */
@Entity(tableName = "import_history")
data class ImportHistoryEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val createdAt: LocalDateTime,
    val sourceFileName: String,
    val mode: String,          // NEW_RECORDS or UPDATE_EXISTING
    val outcome: String,       // COMPLETED, FAILED (rolled back) or REJECTED (nothing written)
    val backupFileName: String?,
    val newPatients: Int,
    val newMedications: Int,
    val patientsUpdated: Int,
    val medicationsUpdated: Int,
    val fieldsChanged: Int,
    val rowsSkipped: Int,
    val message: String?
)

/**
 * Field-level audit trail for significant changes made by an import. Values of patient details are NOT stored
 * (only the field name), so the history does not duplicate sensitive patient information. Medication values are
 * stored because they are needed to review dose and schedule changes.
 * No foreign keys: history must survive whatever happens to the records it describes.
 */
@Entity(
    tableName = "change_history",
    indices = [Index("recordId"), Index("importId")]
)
data class ChangeHistoryEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val importId: String,
    val changedAt: LocalDateTime,
    val actorUserId: String?,
    val recordType: String,    // PATIENT or MEDICATION
    val recordId: String,
    val field: String,
    val oldValue: String?,
    val newValue: String?,
    val significant: Boolean
)
