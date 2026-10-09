package com.campmeds.app.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity(
    tableName = "medications",
    foreignKeys = [
        ForeignKey(
            entity = Patient::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            // History must never be destroyed by a delete. Patients are archived, not deleted, and a
            // hard delete of a patient that still has medications is refused by the database.
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("patientId"), Index("qrCode", unique = true)]
)
data class Medication(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val patientId: String,

    // NDC lookup fields (section 3)
    val ndc: String,                 // NDC as entered; "" = none (manual entry without an NDC)
    val name: String,                // from openFDA lookup (or manual, if isException)
    val strength: String,            // from openFDA lookup
    val form: String,                // tablet/liquid/etc., from openFDA lookup

    val dosageInstructions: String,  // free text, e.g. "1 tablet, twice daily"
    val scheduleTimes: List<LocalTime>, // e.g. [08:00, 20:00] — stored via Converters
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val bottleExpiration: LocalDate? = null,
    val pillCountEntered: Int? = null,  // manual, not auto-tracked

    val isException: Boolean = false,   // true if manual override used instead of a clean NDC match
    val qrCode: String = UUID.randomUUID().toString(), // printed/attached to the bag or bottle

    /** "What is this medication for?" Shown only on the patient's details screen, never when dosing. */
    val purpose: String? = null,
    /** "Deleted" or replaced (renamed) medications are archived, never hard-deleted, so history stays. */
    val isArchived: Boolean = false
)
