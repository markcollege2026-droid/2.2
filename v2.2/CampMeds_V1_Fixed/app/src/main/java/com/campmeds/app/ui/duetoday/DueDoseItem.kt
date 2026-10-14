package com.campmeds.app.ui.duetoday

import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import java.time.LocalDateTime
import com.campmeds.app.domain.isActiveOn

/** One medication dose on a due list. */
data class DueDoseItem(
    val medication: Medication,
    val patient: Patient,
    val scheduledFor: LocalDateTime,
    val status: DoseStatus?,
    val isAsNeeded: Boolean = false // always null in the due list: anything already logged is not "due"
) {
    /** True when this medication is prepackaged with others (and therefore administered as a group). */
    val isPrepackagedMember: Boolean
        get() = medication.isGroupAdministered && !medication.prepackagedGroupId.isNullOrBlank()
}

/** One patient and the medications currently due for them. */
data class PatientDueGroup(
    val patient: Patient,
    val items: List<DueDoseItem>
)

/**
 * One row on a patient's due screen: either a single medication dose or a prepackaged group.
 * The home screen itself shows patients only (spec section 1); this is what a patient expands into.
 */
sealed interface PatientDueEntry {
    val items: List<DueDoseItem>
    val firstDueAt: LocalDateTime
    val sortLabel: String

    data class Single(val item: DueDoseItem) : PatientDueEntry {
        override val items: List<DueDoseItem> get() = listOf(item)
        override val firstDueAt: LocalDateTime get() = item.scheduledFor
        override val sortLabel: String get() = item.medication.name.lowercase()
    }

    data class Prepackaged(
        val groupId: String,
        override val items: List<DueDoseItem>
    ) : PatientDueEntry {
        override val firstDueAt: LocalDateTime get() = items.minOf { it.scheduledFor }
        override val sortLabel: String get() = items.first().medication.name.lowercase()
    }
}

/**
 * Splits one patient's due doses into the rows the patient screen shows: prepackaged group cards and single
 * medications, in due-time order.
 *
 * Only medications explicitly marked as prepackaged are grouped, and only with medications of the same
 * patient that carry the same group identifier. Two medications that merely happen to be due at the same
 * time stay separate.
 */
fun dueEntriesForPatient(items: List<DueDoseItem>): List<PatientDueEntry> {
    val (grouped, singles) = items.partition { it.isPrepackagedMember }

    val groupEntries = grouped
        .groupBy { it.patient.id to it.medication.prepackagedGroupId.orEmpty() }
        .map { (key, groupItems) ->
            PatientDueEntry.Prepackaged(key.second, groupItems.sortedBy { it.scheduledFor })
        }

    return (groupEntries + singles.map { PatientDueEntry.Single(it) })
        .sortedWith(compareBy({ it.firstDueAt }, { it.sortLabel }))
}

/**
 * The state of one member of a prepackaged group today, for the group's confirmation sheet:
 * what is due right now, what is still to come, and what was already recorded.
 */
data class GroupMemberPlan(
    val medication: Medication,
    /** Doses due right now (scheduled time reached, no outcome recorded) — these are what "Given" records. */
    val dueItems: List<DueDoseItem>,
    /** The next dose later today, which a group action must never mark as given. */
    val nextDoseAt: LocalDateTime?,
    /** Outcome already recorded for today's last dose, if any (shown, never overwritten). */
    val alreadyRecorded: DoseStatus?
) {
    val dueNow: Boolean get() = dueItems.isNotEmpty()
}

/**
 * Builds the per-medication picture used by the group sheet. Pure: it reads the clock, the medications of the
 * group and today's DoseLogs, and nothing else.
 */
fun planGroupMembers(
    now: LocalDateTime,
    members: List<Medication>,
    patient: Patient,
    todaysLogs: List<DoseLog>
): List<GroupMemberPlan> {
    val day = now.toLocalDate()
    val logsByMedication = todaysLogs.groupBy { it.medicationId }

    return members.filter { !it.isPrn && it.isActiveOn(day) }.map { medication ->
        val loggedByTime = logsByMedication[medication.id].orEmpty()
            .filter { it.scheduledFor.toLocalDate() == day }
            .associateBy { it.scheduledFor }
        val times = medication.scheduleTimes.map { day.atTime(it) }.sorted()

        GroupMemberPlan(
            medication = medication,
            dueItems = times
                .filter { !it.isAfter(now) && it !in loggedByTime }
                .map { DueDoseItem(medication, patient, it, status = null) },
            nextDoseAt = times.firstOrNull { it.isAfter(now) },
            alreadyRecorded = loggedByTime.values.maxByOrNull { it.scheduledFor }?.status
        )
    }
}

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
        if (med.isPrn || !med.isActiveOn(day)) return@flatMap emptyList<DueDoseItem>()
        val patient = patientsById[med.patientId]?.takeIf { it.isActiveOn(day) }
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
