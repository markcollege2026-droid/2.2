package com.campmeds.app.domain

import com.campmeds.app.data.entity.*
import com.campmeds.app.ui.duetoday.buildCurrentDueList
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CamperDatesTest {
    private val day = LocalDate.of(2026, 7, 15)
    private val camper = Patient(name = "Camper", startDate = day, endDate = day.plusDays(2))
    private val med = Medication(patientId = camper.id, name = "Example", ndc = "", strength = "1", form = "tablet",
        dosageInstructions = "1", scheduleTimes = listOf(LocalTime.of(8, 0)), startDate = day.minusDays(1))

    @Test fun inclusiveStayAndUnrestrictedLegacyDates() {
        assertFalse(camper.isActiveOn(day.minusDays(1)))
        assertTrue(camper.isActiveOn(day))
        assertTrue(camper.isActiveOn(day.plusDays(2)))
        assertFalse(camper.isActiveOn(day.plusDays(3)))
        assertTrue(Patient(name = "Legacy").isActiveOn(day))
        assertFalse(camper.copy(isArchived = true).isActiveOn(day))
    }
    @Test fun dateChangesAndRestartHaveNoStickyArchiveFlag() {
        assertFalse(camper.isActiveOn(day.plusDays(3)))
        val extended = camper.copy(endDate = day.plusDays(7))
        assertTrue(extended.isActiveOn(day.plusDays(3)))
        assertTrue(extended.copy().isActiveOn(day.plusDays(3)))
        assertFalse(extended.copy(startDate = day.plusDays(5)).isActiveOn(day.plusDays(3)))
    }
    @Test fun dueListExcludesFuturePastAndDiscontinuedButIncludesEndDay() {
        fun due(p: Patient, m: Medication = med, date: LocalDate = day) =
            buildCurrentDueList(date.atTime(9, 0), listOf(m), mapOf(p.id to p), emptyList())
        assertTrue(due(camper.copy(startDate = day.plusDays(1))).isEmpty())
        assertTrue(due(camper.copy(endDate = day.minusDays(1))).isEmpty())
        assertEquals(1, due(camper.copy(endDate = day)).size)
        assertTrue(due(camper, med.copy(discontinuedOn = day)).isEmpty())
        assertEquals(1, due(camper, med.copy(discontinuedOn = day.plusDays(1))).size)
    }
    @Test fun oldAndNewMedicationValuesRemainInAudit() {
        val new = med.copy(name = "Changed", isPrn = true, discontinuedOn = day)
        val changes = MedicationChanges.between(med, new, "admin", day.atTime(12, 0))
        assertEquals(setOf("Name", "PRN", "Discontinued effective"), changes.map { it.field }.toSet())
        assertEquals("Example", changes.single { it.field == "Name" }.oldValue)
        assertEquals("Changed", changes.single { it.field == "Name" }.newValue)
        assertTrue(changes.all { it.recordId == med.id && it.actorUserId == "admin" && it.changedAt == day.atTime(12, 0) })
        assertTrue(MedicationChanges.between(med, med, null).isEmpty())
    }
}
