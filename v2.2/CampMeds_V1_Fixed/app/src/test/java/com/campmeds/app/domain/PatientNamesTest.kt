package com.campmeds.app.domain

import com.campmeds.app.data.entity.Medication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PatientNamesTest {
    @Test fun splitsLastWordAsLastName() {
        assertEquals("Mary Ann" to "Smith", splitFullName("  Mary   Ann Smith "))
        assertEquals("John" to "Doe", splitFullName("John Doe"))
    }
    @Test fun singleWordAndEmpty() {
        assertEquals("Cher" to "", splitFullName("Cher"))
        assertEquals("" to "", splitFullName("   "))
    }

    private val med = Medication(
        patientId = "p", ndc = "0069-2587", name = "Alpha", strength = "5 mg", form = "TABLET",
        dosageInstructions = "1", scheduleTimes = listOf(LocalTime.of(8, 0)), startDate = LocalDate.of(2026, 7, 1)
    )

    @Test fun identityChangesOnNameNdcStrengthOrForm() {
        assertTrue(MedicationIdentity.changed(med, med.copy(name = "Beta")))
        assertTrue(MedicationIdentity.changed(med, med.copy(ndc = "")))
        assertTrue(MedicationIdentity.changed(med, med.copy(strength = "10 mg")))
        assertTrue(MedicationIdentity.changed(med, med.copy(form = "LIQUID")))
    }

    @Test fun scheduleInstructionsPurposeAndDatesAreInPlaceEdits() {
        assertFalse(MedicationIdentity.changed(med, med.copy(
            scheduleTimes = listOf(LocalTime.of(9, 0)), dosageInstructions = "2", purpose = "Asthma",
            endDate = LocalDate.of(2026, 8, 1), pillCountEntered = 4
        )))
        assertFalse(MedicationIdentity.changed(med, med.copy(name = " Alpha ")))
    }
}
