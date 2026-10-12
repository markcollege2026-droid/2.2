package com.campmeds.app.domain

import com.campmeds.app.data.entity.Medication
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A dose that is about to be written to the administration record: which medication, which scheduled dose,
 * and whether recording it bypasses a safeguard (expired bottle / scan mismatch) so it must be flagged as
 * an override on the DoseLog.
 *
 * One [PlannedDose] = one DoseLog. A prepackaged group recorded with a single "Given" action produces one
 * planned dose per medication — never one generic group entry.
 */
data class PlannedDose(
    val medicationId: String,
    val scheduledFor: LocalDateTime,
    val wasOverride: Boolean = false
)

/**
 * (medication, scheduled time) identifies a dose: the same dose can never be recorded twice, whatever the
 * caller passes in. Used by the group action so a double tap cannot produce duplicate administration records.
 */
fun List<PlannedDose>.distinctDoses(): List<PlannedDose> = distinctBy { it.medicationId to it.scheduledFor }

/** Medication checks that gate administration (spec: expired bottles are never treated as normal). */
object MedicationSafeguards {
    fun isExpired(medication: Medication, on: LocalDate = LocalDate.now()): Boolean =
        medication.bottleExpiration?.isBefore(on) == true
}
