package com.campmeds.app.ui.duetoday

import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import java.time.LocalDateTime

/** One medication dose on the dashboard. */
data class DueDoseItem(
    val medication: Medication,
    val patient: Patient,
    val scheduledFor: LocalDateTime,
    val status: DoseStatus? // always null in the due list: anything already logged is not "due"
)

/** One patient and the medications currently due for them. */
data class PatientDueGroup(
    val patient: Patient,
    val items: List<DueDoseItem>
)

/**
 * The doses that are due RIGHT NOW: scheduled for today, scheduled time already reached
 * (scheduledFor <= [now]), and with no outcome logged yet.
 *
 * Both the date and the time of day must have been reached. A dose scheduled for 20:00 does not exist on
 * the dashboard at 09:00 even though its date is today. Once any outcome (given/held/refused) is recorded
 * for that medication and scheduled time, the dose leaves the list; the DoseLog itself is untouched.
 *
 * Pure function of (clock, schedule, logs): nothing about "due" is stored, so it is identical after an app
 * restart and rolls over correctly at midnight (yesterday's unlogged doses are not carried into today).
 */
fun buildCurrentDueList(
    now: LocalDateTime,
    activeMedications: List<Medication>,
    patientsById: Map<String, Patient>,
    todaysLogs: List<DoseLog>
): List<DueDoseItem> {
    val day = now.toLocalDate()
    val logged = todaysLogs.mapTo(HashSet()) { it.medicationId to it.scheduledFor }

    return activeMedications.flatMap { med ->
        if (med.isArchived) return@flatMap emptyList<DueDoseItem>()
        val patient = patientsById[med.patientId]?.takeUnless { it.isArchived }
            ?: return@flatMap emptyList<DueDoseItem>()
        med.scheduleTimes.mapNotNull { time ->
            val scheduledFor = day.atTime(time)
            when {
                scheduledFor.isAfter(now) -> null                 // not time yet
                (med.id to scheduledFor) in logged -> null        // already administered/recorded
                else -> DueDoseItem(med, patient, scheduledFor, status = null)
            }
        }
    }.sortedWith(compareBy({ it.scheduledFor }, { it.medication.name.lowercase() }))
}

/** Groups due doses by patient: patients alphabetically, each patient's medications by due time. */
fun groupByPatient(items: List<DueDoseItem>): List<PatientDueGroup> =
    items.groupBy { it.patient.id }
        .values
        .map { PatientDueGroup(it.first().patient, it) }
        .sortedWith(compareBy({ it.patient.name.lowercase() }, { it.patient.id }))
