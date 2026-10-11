package com.campmeds.app.domain

import com.campmeds.app.data.entity.Medication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Prepackaged groups: only medications that were deliberately marked as packaged together are grouped, and a
 * group never spans patients. Nothing here ever groups a medication automatically.
 */
class PrepackagedGroupsTest {

    private fun medication(
        id: String,
        patientId: String,
        name: String,
        groupId: String? = null,
        grouped: Boolean = groupId != null,
        archived: Boolean = false
    ) = Medication(
        id = id,
        patientId = patientId,
        ndc = "",
        name = name,
        strength = "5 mg",
        form = "TABLET",
        dosageInstructions = "1",
        scheduleTimes = listOf(LocalTime.of(8, 0)),
        startDate = LocalDate.of(2026, 7, 1),
        isException = true,
        isGroupAdministered = grouped,
        prepackagedGroupId = groupId,
        isArchived = archived
    )

    private val alphaP1 = medication("m1", "p1", "Alpha", groupId = "g1")
    private val betaP1 = medication("m2", "p1", "Beta", groupId = "g1")
    private val lonerP1 = medication("m3", "p1", "Loner")
    private val gammaP2 = medication("m4", "p2", "Gamma", groupId = "g2")
    private val retiredP1 = medication("m5", "p1", "Retired", groupId = "g1", archived = true)

    private val all = listOf(alphaP1, betaP1, lonerP1, gammaP2, retiredP1)

    @Test
    fun membersOfAGroupAreOnlyThatPatientsCurrentMedications() {
        assertEquals(listOf(alphaP1, betaP1), PrepackagedGroups.membersOf(all, "g1", "p1"))
        assertEquals(listOf(gammaP2), PrepackagedGroups.membersOf(all, "g2", "p2"))
        // The same group id belonging to another patient never includes that patient's medication.
        assertEquals(emptyList<Medication>(), PrepackagedGroups.membersOf(listOf(gammaP2), "g1", "p1").filter { it.patientId != "p1" })
    }

    @Test
    fun groupsOfPatientListsOnlyThatPatientsGroupsWithAHumanLabel() {
        val groups = PrepackagedGroups.groupsOfPatient(listOf(alphaP1, betaP1, lonerP1, gammaP2), "p1")
        assertEquals(1, groups.size)
        assertEquals("g1", groups.single().id)
        assertEquals(listOf(alphaP1, betaP1), groups.single().members)
        assertEquals("Alpha + Beta", groups.single().label)
        assertTrue(groups.none { it.members.any { m -> m.name == "Loner" } })
    }

    @Test
    fun scheduledAtTheSameTimeIsNotAGroup() {
        val groups = PrepackagedGroups.groupsOfPatient(listOf(betaP1, lonerP1), "p1")
        assertEquals(listOf("Alpha + Beta"), groups.map { it.label })
    }

    @Test
    fun validationRequiresAGroupForAGroupedMedication() {
        val groupedWithoutId = lonerP1.copy(isGroupAdministered = true, prepackagedGroupId = null)
        assertNotNull(PrepackagedGroups.validate(groupedWithoutId, all))

        val ungroupedWithId = lonerP1.copy(isGroupAdministered = false, prepackagedGroupId = "g1")
        assertNotNull(PrepackagedGroups.validate(ungroupedWithId, all))

        assertNull(PrepackagedGroups.validate(lonerP1, all))
        assertNull(PrepackagedGroups.validate(alphaP1, all))
    }

    @Test
    fun validationRefusesAGroupThatBelongsToAnotherPatient() {
        // Joining a group that this patient's medications already use is fine.
        assertNull(PrepackagedGroups.validate(medication("m9", "p1", "New", groupId = "g1"), all))

        // A medication of p2 that carries an id p1's medications already use is refused.
        assertNotNull(PrepackagedGroups.validate(medication("m9", "p2", "New", groupId = "g1"), all))
    }

    @Test
    fun everyNewGroupGetsItsOwnIdentifier() {
        val first = PrepackagedGroups.newGroupId()
        val second = PrepackagedGroups.newGroupId()
        assertTrue(first.isNotBlank())
        assertTrue(first != second)
    }
}
