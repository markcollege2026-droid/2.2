package com.campmeds.app.domain

import com.campmeds.app.data.converter.NameListConverters
import com.campmeds.app.data.entity.*
import com.campmeds.app.ui.duetoday.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class CampOptionsTest {
    @Test fun multipleBunksAutofillUnionWithoutDuplicateCounselors() {
        val options = listOf(BunkOption("East", listOf("Jane, Smith", "Jo")), BunkOption("West", listOf("Jo", "Alex")))
        assertEquals(listOf("Jane, Smith", "Jo", "Alex"), counselorsForBunks(listOf("East","West"), options))
        assertEquals(listOf("Jo","Alex"), counselorsForBunks(listOf("West"), options))
        assertTrue(counselorsForBunks(listOf("Unknown"), options).isEmpty())
    }
    @Test fun jsonListsPreserveCommaQuotesUnicodeAndEmptyValues() {
        val codec = NameListConverters()
        val names = listOf("Smith, Jane", "Cabin \"A\"", "בקתה")
        assertEquals(names, codec.decode(codec.encode(names)))
        assertEquals(emptyList<String>(), codec.decode(codec.encode(emptyList())))
    }
    @Test fun divisionFilterSupportsAllAndExactDivisionAndColorValidation() {
        val p = Patient(name = "Camper", division = "Junior")
        assertTrue(p.matchesDivision(null)); assertTrue(p.matchesDivision("Junior")); assertFalse(p.matchesDivision("Senior"))
        assertTrue(validDivisionColor("#00aaFF")); assertFalse(validDivisionColor("blue")); assertFalse(validDivisionColor("#00000000"))
    }
    @Test fun prnNeverBecomesAutomaticallyDueOrJoinsAutomaticGroupEvenWithLegacyTimes() {
        val day = LocalDate.of(2026,7,15)
        val p = Patient(name = "Camper")
        val med = Medication(patientId = p.id, ndc = "", name = "PRN", strength = "1", form = "tablet", dosageInstructions = "as needed",
            scheduleTimes = listOf(LocalTime.of(8,0)), startDate = day, isPrn = true)
        assertTrue(buildCurrentDueList(day.atTime(10,0), listOf(med), mapOf(p.id to p), emptyList()).isEmpty())
        assertTrue(planGroupMembers(day.atTime(10,0), listOf(med), p, emptyList()).isEmpty())
        assertTrue(buildCurrentDueList(day.atTime(10,0), listOf(med.copy(scheduleTimes = emptyList())), mapOf(p.id to p), emptyList()).isEmpty())
    }
}
