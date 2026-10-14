package com.campmeds.app.domain

import com.campmeds.app.data.entity.Patient
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CamperDatesTest {
    private val start = LocalDate.of(2026, 7, 1)
    private val end = start.plusDays(7)
    @Test fun inclusiveBoundariesAndRollover() {
        val p = Patient(name = "Camper", startDate = start, endDate = end)
        assertFalse(p.isActiveOn(start.minusDays(1)))
        assertTrue(p.isActiveOn(start))
        assertTrue(p.isActiveOn(end))
        assertFalse(p.isActiveOn(end.plusDays(1)))
        assertTrue(p.copy(endDate = end.plusDays(5)).isActiveOn(end.plusDays(1)))
        assertFalse(p.copy(startDate = end).isActiveOn(start))
        assertFalse(p.copy(isArchived = true).isActiveOn(start))
        assertTrue(Patient(name = "Legacy").isActiveOn(end))
    }
}
