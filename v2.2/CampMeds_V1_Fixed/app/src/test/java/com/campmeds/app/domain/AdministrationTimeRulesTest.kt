package com.campmeds.app.domain

import com.campmeds.app.data.entity.AdministrationTime
import com.campmeds.app.data.entity.Medication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Named administration times: how a medication's schedule is built from them, what happens to schedules when
 * an administrator renames, re-times or removes an entry, and what the screens display.
 *
 * The invariant behind every test: a scheduled time is only ever moved because an administrator confirmed it,
 * and a removal never loses a dose.
 */
class AdministrationTimeRulesTest {

    private val breakfast = AdministrationTime(id = "t-breakfast", name = "Breakfast", time = LocalTime.of(8, 0))
    private val dinner = AdministrationTime(id = "t-dinner", name = "Dinner", time = LocalTime.of(18, 0))
    private val bedtime = AdministrationTime(id = "t-bedtime", name = "Bedtime", time = LocalTime.of(21, 0))
    private val definitions = listOf(breakfast, dinner, bedtime)

    private fun medication(
        times: List<LocalTime>,
        ids: List<String>,
        name: String = "Alpha"
    ) = Medication(
        id = "m-$name",
        patientId = "p1",
        ndc = "",
        name = name,
        strength = "5 mg",
        form = "TABLET",
        dosageInstructions = "1",
        scheduleTimes = times,
        administrationTimeIds = ids,
        startDate = LocalDate.of(2026, 7, 1),
        isException = true
    )

    // ---------------------------------------------------------------------------------------------
    // Building a schedule from the form's selection
    // ---------------------------------------------------------------------------------------------

    @Test
    fun namedEntriesAndCustomTimesAreCombinedInTimeOrderWithParallelIds() {
        val (times, ids) = AdministrationTimeRules.buildSchedule(
            namedTimes = listOf(breakfast, bedtime),
            customTimes = listOf(LocalTime.of(14, 30))
        )
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(14, 30), LocalTime.of(21, 0)), times)
        assertEquals(listOf("t-breakfast", "", "t-bedtime"), ids)
    }

    @Test
    fun aCustomTimeEqualToANamedTimeIsMergedAndTheNameWins() {
        val (times, ids) = AdministrationTimeRules.buildSchedule(
            namedTimes = listOf(breakfast),
            customTimes = listOf(LocalTime.of(8, 0))
        )
        assertEquals(listOf(LocalTime.of(8, 0)), times)
        assertEquals(listOf("t-breakfast"), ids)
    }

    @Test
    fun severalEntriesAndNoCustomTimesAreSupported() {
        val (times, ids) = AdministrationTimeRules.buildSchedule(definitions, emptyList())
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(21, 0)), times)
        assertEquals(listOf("t-breakfast", "t-dinner", "t-bedtime"), ids)
    }

    // ---------------------------------------------------------------------------------------------
    // A changed entry time: applied to the referencing schedules and nothing else
    // ---------------------------------------------------------------------------------------------

    @Test
    fun retimeMovesOnlyTheReferencingPosition() {
        val med = medication(
            times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
            ids = listOf("t-breakfast", "")
        )
        val updated = AdministrationTimeRules.retime(med, "t-breakfast", LocalTime.of(8, 30))

        assertEquals(listOf(LocalTime.of(8, 30), LocalTime.of(20, 0)), updated.scheduleTimes)
        assertEquals(listOf("t-breakfast", ""), updated.administrationTimeIds)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), med.scheduleTimes) // input untouched
    }

    @Test
    fun retimeThatCollidesWithAnotherTimeLeavesOneDose() {
        val med = medication(
            times = listOf(LocalTime.of(8, 0), LocalTime.of(8, 30)),
            ids = listOf("t-breakfast", "")
        )
        val updated = AdministrationTimeRules.retime(med, "t-breakfast", LocalTime.of(8, 30))

        assertEquals(listOf(LocalTime.of(8, 30)), updated.scheduleTimes)
        assertEquals(listOf("t-breakfast"), updated.administrationTimeIds)
    }

    @Test
    fun retimeIgnoresOtherEntriesAndBlankIds() {
        val med = medication(
            times = listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)),
            ids = listOf("t-breakfast", "t-dinner")
        )
        val updated = AdministrationTimeRules.retime(med, "t-breakfast", LocalTime.of(9, 0))

        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), updated.scheduleTimes)
        assertEquals(listOf("t-breakfast", "t-dinner"), updated.administrationTimeIds)
        assertEquals(med, AdministrationTimeRules.retime(med, "", LocalTime.of(9, 0)))
    }

    // ---------------------------------------------------------------------------------------------
    // A removed entry: the times stay, only the name goes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun detachKeepsEveryTimeAndOnlyForgetsTheReference() {
        val med = medication(
            times = listOf(LocalTime.of(8, 0), LocalTime.of(21, 0)),
            ids = listOf("t-breakfast", "t-bedtime")
        )
        val updated = AdministrationTimeRules.detach(med, "t-breakfast")

        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(21, 0)), updated.scheduleTimes)
        assertEquals(listOf("", "t-bedtime"), updated.administrationTimeIds)
    }

    // ---------------------------------------------------------------------------------------------
    // What is in use, and what the screens show
    // ---------------------------------------------------------------------------------------------

    @Test
    fun usingAndUsageCountReportTheSchedulesThatReferenceAnEntry() {
        val a = medication(listOf(LocalTime.of(8, 0)), listOf("t-breakfast"), name = "A")
        val b = medication(listOf(LocalTime.of(8, 0), LocalTime.of(21, 0)), listOf("t-breakfast", "t-bedtime"), name = "B")
        val c = medication(listOf(LocalTime.of(14, 0)), listOf(""), name = "C")
        val all = listOf(a, b, c)

        assertEquals(listOf(a, b), AdministrationTimeRules.using(all, "t-breakfast"))
        assertEquals(2, AdministrationTimeRules.usageCount(all, "t-breakfast"))
        assertEquals(1, AdministrationTimeRules.usageCount(all, "t-bedtime"))
        assertTrue(AdministrationTimeRules.using(all, "t-dinner").isEmpty())
    }

    @Test
    fun labelsShowTheEntryNameAndFallBackToTheClockForCustomTimes() {
        val med = medication(
            times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0), LocalTime.of(21, 0)),
            ids = listOf("t-breakfast", "", "t-bedtime")
        )
        val byId = definitions.associateBy { it.id }

        assertEquals(listOf("Breakfast", "20:00", "Bedtime"), AdministrationTimeRules.labelsFor(med, byId))
        assertEquals("Breakfast 08:00 · 20:00 · Bedtime 21:00", AdministrationTimeRules.scheduleSummary(med, byId))
    }

    @Test
    fun aReferenceToAnEntryThatNoLongerExistsFallsBackToTheTime() {
        val med = medication(listOf(LocalTime.of(8, 0)), listOf("t-deleted"))
        assertEquals(listOf("08:00"), AdministrationTimeRules.labelsFor(med, definitions.associateBy { it.id }))
        assertEquals("", AdministrationTimeRules.definitionIdAt(med, 5)) // no crash on a missing position
    }

    // ---------------------------------------------------------------------------------------------
    // Names
    // ---------------------------------------------------------------------------------------------

    @Test
    fun nameValidationRejectsBlankDuplicatesAndOverlongNames() {
        assertTrue(AdministrationTimeRules.validateName("", definitions)!!.contains("Enter a name"))
        assertTrue(AdministrationTimeRules.validateName("   ", definitions)!!.contains("Enter a name"))
        assertTrue(AdministrationTimeRules.validateName("breakfast", definitions)!!.contains("already used"))
        assertTrue(AdministrationTimeRules.validateName("BEDTIME", definitions)!!.contains("already used"))
        assertTrue(AdministrationTimeRules.validateName("L".repeat(41), definitions)!!.contains("40 characters"))
        assertNull(AdministrationTimeRules.validateName("Lunch", definitions))
        assertNull(AdministrationTimeRules.validateName("  Lunch  ", definitions))
    }
}
