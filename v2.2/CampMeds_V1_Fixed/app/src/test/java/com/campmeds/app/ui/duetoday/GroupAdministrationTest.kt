package com.campmeds.app.ui.duetoday

import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.DoseStatus
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.domain.PlannedDose
import com.campmeds.app.domain.distinctDoses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the patient's due screen shows, and exactly which doses a single "Given" group action covers.
 *
 * The rules under test (spec section 4.1): only medications deliberately marked as prepackaged are grouped,
 * a group never spans patients, a dose that is due is one record per medication, and a dose scheduled for
 * later today is never marked as given because another member of the group was administered.
 */
class GroupAdministrationTest {

    private val day = LocalDate.of(2026, 7, 15)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m)

    private val ann = Patient(id = "p1", name = "Ann Lee", firstName = "Ann", lastName = "Lee")
    private val bob = Patient(id = "p2", name = "Bob Ray", firstName = "Bob", lastName = "Ray")
    private val patients = listOf(ann, bob).associateBy { it.id }

    private fun med(
        id: String,
        patient: Patient,
        name: String,
        times: List<LocalTime>,
        groupId: String? = null
    ) = Medication(
        id = id,
        patientId = patient.id,
        ndc = "",
        name = name,
        strength = "5 mg",
        form = "TABLET",
        dosageInstructions = "1",
        scheduleTimes = times,
        startDate = day.minusDays(1),
        isException = true,
        isGroupAdministered = groupId != null,
        prepackagedGroupId = groupId
    )

    private fun log(medicationId: String, scheduled: LocalDateTime, status: DoseStatus = DoseStatus.GIVEN) = DoseLog(
        medicationId = medicationId,
        scheduledFor = scheduled,
        status = status,
        loggedAt = scheduled,
        loggedByUserId = null
    )

    // ---------------------------------------------------------------------------------------------
    // The patient's due screen rows
    // ---------------------------------------------------------------------------------------------

    @Test
    fun onlyMedicationsMarkedAsPrepackagedAreShownAsAGroup() {
        val alpha = med("m1", ann, "Alpha", listOf(LocalTime.of(8, 0)), groupId = "g1")
        val beta = med("m2", ann, "Beta", listOf(LocalTime.of(9, 0)), groupId = "g1")
        val single = med("m3", ann, "Loner", listOf(LocalTime.of(8, 30)))

        val entries = dueEntriesForPatient(
            listOf(
                DueDoseItem(alpha, ann, at(8), null),
                DueDoseItem(single, ann, at(8, 30), null),
                DueDoseItem(beta, ann, at(9), null)
            )
        )

        assertEquals(2, entries.size)
        val group = entries[0] as PatientDueEntry.Prepackaged
        assertEquals("g1", group.groupId)
        assertEquals(listOf("m1", "m2"), group.items.map { it.medication.id })
        assertEquals(at(8), group.firstDueAt)
        assertEquals(listOf("m3"), entries[1].items.map { it.medication.id })
    }

    @Test
    fun aGroupNeverSpansPatientsEvenWithTheSameIdentifier() {
        val annsMed = med("m1", ann, "Alpha", listOf(LocalTime.of(8, 0)), groupId = "g1")
        val bobsMed = med("m2", bob, "Zeta", listOf(LocalTime.of(8, 0)), groupId = "g1")

        val entries = dueEntriesForPatient(
            listOf(DueDoseItem(annsMed, ann, at(8), null), DueDoseItem(bobsMed, bob, at(8), null))
        )

        assertEquals(2, entries.size)
        assertTrue(entries.all { it is PatientDueEntry.Prepackaged && it.items.size == 1 })
        assertEquals(listOf("p1", "p2"), entries.map { it.items.single().patient.id })
    }

    @Test
    fun aRecordedDoseLeavesThePatientScreenAndThePatientDisappearsWhenNothingIsDue() {
        val alpha = med("m1", ann, "Alpha", listOf(LocalTime.of(8, 0)))
        val logs = listOf(log("m1", at(8)))
        val due = buildCurrentDueList(at(9), listOf(alpha), patients, logs)
        assertTrue(dueEntriesForPatient(due).isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // What one "Given" action covers
    // ---------------------------------------------------------------------------------------------

    @Test
    fun planShowsDueNextAndAlreadyRecordedForEveryMember() {
        val alpha = med("m1", ann, "Alpha", listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), groupId = "g1")
        val beta = med("m2", ann, "Beta", listOf(LocalTime.of(8, 0)), groupId = "g1")
        val evening = med("m3", ann, "Evening", listOf(LocalTime.of(20, 0)), groupId = "g1")
        val twice = med("m4", ann, "Twice", listOf(LocalTime.of(6, 0), LocalTime.of(8, 0)), groupId = "g1")

        val plans = planGroupMembers(
            now = at(12),
            members = listOf(alpha, beta, evening, twice),
            patient = ann,
            todaysLogs = listOf(log("m2", at(8)), log("m4", at(6), DoseStatus.HELD))
        )

        val byId = plans.associateBy { it.medication.id }
        assertEquals(listOf(at(8)), byId.getValue("m1").dueItems.map { it.scheduledFor })
        assertEquals(at(20), byId.getValue("m1").nextDoseAt)
        assertNull(byId.getValue("m1").alreadyRecorded)

        assertTrue(byId.getValue("m2").dueItems.isEmpty())                  // already given today
        assertEquals(DoseStatus.GIVEN, byId.getValue("m2").alreadyRecorded)

        assertTrue(byId.getValue("m3").dueItems.isEmpty())                  // not due until 20:00
        assertEquals(at(20), byId.getValue("m3").nextDoseAt)

        assertEquals(listOf(at(8)), byId.getValue("m4").dueItems.map { it.scheduledFor })
        assertEquals(DoseStatus.HELD, byId.getValue("m4").alreadyRecorded)  // history kept, not overwritten
    }

    @Test
    fun aSingleGroupActionCoversOneRecordPerDueDoseAndNeverALaterDose() {
        val alpha = med("m1", ann, "Alpha", listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), groupId = "g1")
        val beta = med("m2", ann, "Beta", listOf(LocalTime.of(8, 0)), groupId = "g1")

        val plans = planGroupMembers(at(12), listOf(alpha, beta), ann, emptyList())
        val doses = plans.filter { it.dueNow }.flatMap { plan ->
            plan.dueItems.map { PlannedDose(it.medication.id, it.scheduledFor) }
        }

        assertEquals(listOf("m1" to at(8), "m2" to at(8)), doses.map { it.medicationId to it.scheduledFor })
        assertTrue(doses.none { it.scheduledFor.toLocalTime() == LocalTime.of(20, 0) })
    }

    @Test
    fun heldOrRefusedMembersCanStillBeLeftOutOfTheGroupAction() {
        val alpha = med("m1", ann, "Alpha", listOf(LocalTime.of(8, 0)), groupId = "g1")
        val beta = med("m2", ann, "Beta", listOf(LocalTime.of(8, 0)), groupId = "g1")
        val excluded = setOf("m2") // the user unticked it to refuse or hold it on its own

        val doses = planGroupMembers(at(12), listOf(alpha, beta), ann, emptyList())
            .filter { it.dueNow && it.medication.id !in excluded }
            .flatMap { plan -> plan.dueItems.map { PlannedDose(it.medication.id, it.scheduledFor) } }

        assertEquals(listOf("m1"), doses.map { it.medicationId })
    }

    @Test
    fun doubleTapsCannotProduceDuplicateDoses() {
        val planned = listOf(
            PlannedDose("m1", at(8)),
            PlannedDose("m1", at(8)),          // second tap
            PlannedDose("m2", at(8)),
            PlannedDose("m1", at(20))          // genuinely different dose
        )
        assertEquals(
            listOf("m1" to at(8), "m2" to at(8), "m1" to at(20)),
            planned.distinctDoses().map { it.medicationId to it.scheduledFor }
        )
    }
}
