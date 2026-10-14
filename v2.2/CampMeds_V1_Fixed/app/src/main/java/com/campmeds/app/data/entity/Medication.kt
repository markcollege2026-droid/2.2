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
    indices = [Index("patientId"), Index("qrCode", unique = true), Index("prepackagedGroupId")]
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

    /**
     * Named administration-time entries this schedule was built from, position by position with
     * [scheduleTimes]. "" at a position means a custom time that is not from the shared list (all
     * pre-existing schedules, and any time typed by hand). Always read through
     * `ScheduleSlots`/`AdministrationTimeRules`, which tolerate a shorter or missing list.
     */
    val administrationTimeIds: List<String> = emptyList(),

    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val bottleExpiration: LocalDate? = null,
    val pillCountEntered: Int? = null,  // manual, not auto-tracked

    val isException: Boolean = false,   // true if manual override used instead of a clean NDC match
    val qrCode: String = UUID.randomUUID().toString(), // printed/attached to the bag or bottle

    /**
     * "Require QR scan when selecting this medication."
     * true (the default, and what every pre-existing record gets) keeps the existing workflow: the
     * medication has to be scanned before an outcome can be recorded. false is the explicit, per-medication
     * opt-out for medications with no scannable label; the physical-verification check and the expired-bottle
     * block still apply.
     */
    val requireQrScan: Boolean = true,

    /**
     * "Administer together with other selected medications." — this medication is prepackaged with others
     * and is part of the group identified by [prepackagedGroupId]. Independent of who is scheduled when:
     * grouping never happens by itself, and only medications of the same patient can share a group.
     */
    val isGroupAdministered: Boolean = false,

    /** Identifier shared by the medications of one prepackaged group (see [isGroupAdministered]). */
    val prepackagedGroupId: String? = null,
    /** Name typed for the group on the form (prefilled from the administration time). Null when unnamed. */
    val prepackagedGroupName: String? = null,

    /** "What is this medication for?" Shown only on the patient's details screen, never when dosing. */
    val purpose: String? = null,
    /** "Deleted" or replaced (renamed) medications are archived, never hard-deleted, so history stays. */
    val isArchived: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val isPrn: Boolean = false,
    val createdByUserId: String? = null,
    val createdByName: String? = null,
    val createdAt: java.time.LocalDateTime? = null,
    /** Stable export name allocated at creation/migration, never derived again after an edit. */
    val sheetName: String? = null,
    /** Effective discontinuation day: no further doses on or after this day. Original endDate stays intact. */
    val discontinuedOn: LocalDate? = null
)
