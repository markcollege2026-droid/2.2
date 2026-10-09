package com.campmeds.app.ui.duetoday

import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DueListTest {
    private val day = LocalDate.of(2026, 7, 15)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m)

    private val ann = Patient(id = "p-ann", name = "Ann Lee", firstName = "Ann", lastName = "Lee")
    private val bob = Patient(id = "p-bob", name = "Bob Ray", firstName = "Bob", lastName = "Ray")
    private val patients = listOf(ann, bob).associateBy { it.id }

    private fun med(id: String, patient: Patient, name: String, vararg times: LocalTime, archived: Boolean = false) = Medication(
        id = id, patientId = patient.id, ndc = "", name = name, strength = "5 mg", form = "TABLET",
        dosageInstructions = "1", scheduleTimes = times.toList(), startDate = day.minusDays(1),
        isException = true, qrCode = "qr-$id", isArchived = archived
    )

    private fun log(medId: String, scheduled: LocalDateTime, status: DoseStatus = DoseStatus.GIVEN) = DoseLog(
        medicationId = medId, scheduledFor = scheduled, status = status, loggedAt = scheduled, loggedByUserId = null
    )

    private val m1 = med("m1", ann, "Alpha", LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(20, 0))

    @Test fun nothingIsDueBeforeTheFirstScheduledTime() {
        assertTrue(buildCurrentDueList(at(7, 59), listOf(m1), patients, emptyList()).isEmpty())
        // Start of the day: the date has arrived but no time has.
        assertTrue(buildCurrentDueList(day.atStartOfDay(), listOf(m1), patients, emptyList()).isEmpty())
    }

    @Test fun becomesDueExactlyAtItsScheduledTimeAndNotBefore() {
        assertEquals(listOf(at(8)), buildCurrentDueList(at(8, 0), listOf(m1), patients, emptyList()).map { it.scheduledFor })
        assertEquals(listOf(at(8)), buildCurrentDueList(at(11, 59), listOf(m1), patients, emptyList()).map { it.scheduledFor })
        assertEquals(listOf(at(8), at(12)), buildCurrentDueList(at(12, 0), listOf(m1), patients, emptyList()).map { it.scheduledFor })
        assertEquals(listOf(at(8), at(12), at(20)), buildCurrentDueList(at(20, 0), listOf(m1), patients, emptyList()).map { it.scheduledFor })
    }

    @Test fun administeredDoseLeavesTheListButOtherTimesStayIndependent() {
        val logs = listOf(log("m1", at(8)))
        val due = buildCurrentDueList(at(12, 30), listOf(m1), patients, logs)
        assertEquals(listOf(at(12)), due.map { it.scheduledFor })
    }

    @Test fun heldAndRefusedAlsoResolveTheDose() {
        val logs = listOf(log("m1", at(8), DoseStatus.HELD), log("m1", at(12), DoseStatus.REFUSED))
        assertTrue(buildCurrentDueList(at(12, 30), listOf(m1), patients, logs).isEmpty())
    }

    @Test fun differentPatientsAndSchedulesAreGroupedByPatient() {
        val m2 = med("m2", ann, "Beta", LocalTime.of(9, 0))
        val m3 = med("m3", bob, "Gamma", LocalTime.of(8, 30), LocalTime.of(21, 0))
        val groups = groupByPatient(buildCurrentDueList(at(10, 0), listOf(m1, m2, m3), patients, emptyList()))
        assertEquals(listOf("Ann Lee", "Bob Ray"), groups.map { it.patient.name })
        assertEquals(listOf("Alpha", "Beta"), groups[0].items.map { it.medication.name })   // 08:00, 09:00
        assertEquals(listOf("Gamma"), groups[1].items.map { it.medication.name })           // only 08:30; 21:00 not yet
    }

    @Test fun nextDayStartsEmptyAndYesterdaysLogsDoNotHideTodaysDoses() {
        val yesterdayLog = log("m1", day.minusDays(1).atTime(8, 0))
        val nextMorning = day.plusDays(1).atTime(0, 5)
        assertTrue(buildCurrentDueList(nextMorning, listOf(m1), patients, listOf(yesterdayLog)).isEmpty())
        val due = buildCurrentDueList(day.plusDays(1).atTime(8, 1), listOf(m1), patients, listOf(yesterdayLog))
        assertEquals(listOf(day.plusDays(1).atTime(8, 0)), due.map { it.scheduledFor })
    }

    @Test fun resultDependsOnlyOnClockAndLogsSoRestartsAreIdentical() {
        val first = buildCurrentDueList(at(13, 0), listOf(m1), patients, emptyList())
        val afterRestart = buildCurrentDueList(at(13, 0), listOf(m1), patients, emptyList())
        assertEquals(first, afterRestart)
    }

    @Test fun archivedMedicationsAndArchivedPatientsNeverAppear() {
        val archivedMed = med("m9", ann, "Old", LocalTime.of(8, 0), archived = true)
        val archivedPatient = bob.copy(isArchived = true)
        val m3 = med("m3", bob, "Gamma", LocalTime.of(8, 0))
        val due = buildCurrentDueList(at(9, 0), listOf(archivedMed, m3), mapOf(ann.id to ann, bob.id to archivedPatient), emptyList())
        assertTrue(due.isEmpty())
    }
}
