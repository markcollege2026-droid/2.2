package com.campmeds.app.domain

import com.campmeds.app.data.entity.AdministrationTime
import com.campmeds.app.data.entity.Medication
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * All the rules for named administration times ("Breakfast 08:00"), kept in one place so the database,
 * the medication form, the settings screen and the due list cannot drift apart.
 *
 * The scheduled time is always the medication's own [Medication.scheduleTimes] entry — the name is only a
 * label. That is what makes the promises below possible:
 *  - a name change relabels medications without touching any schedule;
 *  - a time change that an administrator confirms is applied to the schedules that reference the entry
 *    ([retime]) and to nothing else, so past DoseLogs (which store the time a dose was actually scheduled
 *    for) are never rewritten;
 *  - removing an entry keeps the medication's time and turns it into a custom time ([detach]).
 */
object AdministrationTimeRules {

    val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** The entry (if any) that the schedule position [index] came from; "" = a custom time. */
    fun definitionIdAt(medication: Medication, index: Int): String =
        medication.administrationTimeIds.getOrNull(index)?.takeIf { it.isNotBlank() }.orEmpty()

    /** What to show for one scheduled time: the entry's name, or the raw time when it has no name. */
    fun labelFor(time: LocalTime, definition: AdministrationTime?): String =
        definition?.name?.takeIf { it.isNotBlank() } ?: time.format(HH_MM)

    /**
     * A label for every scheduled time of a medication, in schedule order. A reference to an entry that no
     * longer exists degrades to the plain time instead of breaking the screen.
     */
    fun labelsFor(medication: Medication, definitions: Map<String, AdministrationTime>): List<String> =
        medication.scheduleTimes.mapIndexed { index, time ->
            labelFor(time, definitions[definitionIdAt(medication, index)])
        }

    /** "Breakfast 08:00 · Bedtime 21:00" for lists and cards. */
    fun scheduleSummary(medication: Medication, definitions: Map<String, AdministrationTime>): String =
        medication.scheduleTimes.mapIndexed { index, time ->
            val label = labelFor(time, definitions[definitionIdAt(medication, index)])
            "$label ${time.format(HH_MM)}"
        }.joinToString(" · ")

    /**
     * Builds the schedule a medication is saved with from the form's selection: the chosen named entries
     * (each with its configured time) plus any custom times. Times are sorted and de-duplicated by time —
     * two identical times would otherwise produce two rows for the same dose — and a named entry wins over a
     * custom time with the same time, so the name is not lost.
     */
    fun buildSchedule(
        namedTimes: List<AdministrationTime>,
        customTimes: List<LocalTime>
    ): Pair<List<LocalTime>, List<String>> {
        val chosen = LinkedHashMap<LocalTime, String>()
        namedTimes.forEach { entry -> chosen[entry.time] = entry.id }
        customTimes.forEach { time -> chosen.putIfAbsent(time, "") }
        val ordered = chosen.entries.sortedBy { it.key }
        return ordered.map { it.key } to ordered.map { it.value }
    }

    /**
     * The same medication with the entry's new time applied to every position that references it. Other
     * times stay where they are; two positions landing on the same time are merged (one dose, not two).
     * Nothing here touches DoseLogs — a dose already recorded keeps the time it was scheduled for.
     */
    fun retime(medication: Medication, definitionId: String, newTime: LocalTime): Medication {
        if (definitionId.isBlank()) return medication
        val updated = medication.scheduleTimes.mapIndexed { index, time ->
            if (definitionIdAt(medication, index) == definitionId) newTime to definitionId else time to definitionIdAt(medication, index)
        }
        val merged = LinkedHashMap<LocalTime, String>()
        updated.forEach { (time, id) -> merged[time] = id } // first writer wins for equal times
        val times = merged.keys.sorted()
        return medication.copy(
            scheduleTimes = times,
            administrationTimeIds = times.map { merged.getValue(it) }
        )
    }

    /** Forgets the entry but keeps every time: the schedule becomes custom times, nothing is lost. */
    fun detach(medication: Medication, definitionId: String): Medication {
        if (definitionId.isBlank()) return medication
        return medication.copy(
            administrationTimeIds = medication.scheduleTimes.indices.map { index ->
                if (definitionIdAt(medication, index) == definitionId) "" else definitionIdAt(medication, index)
            }
        )
    }

    /** The (current) medications whose schedule references [definitionId]. */
    fun using(medications: List<Medication>, definitionId: String): List<Medication> =
        medications.filter { medication ->
            medication.scheduleTimes.indices.any { definitionIdAt(medication, it) == definitionId }
        }

    /** How many scheduled doses reference [definitionId]. */
    fun usageCount(medications: List<Medication>, definitionId: String): Int =
        medications.sumOf { medication ->
            medication.scheduleTimes.indices.count { definitionIdAt(medication, it) == definitionId }
        }

    /** Returns an error message for a name that cannot be saved, or null when it is fine. */
    fun validateName(name: String, others: List<AdministrationTime>): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Enter a name, such as Breakfast."
        if (trimmed.length > 40) return "Keep the name under 40 characters."
        if (others.any { it.name.equals(trimmed, ignoreCase = true) }) {
            return "\"$trimmed\" is already used by another administration time."
        }
        return null
    }
}
