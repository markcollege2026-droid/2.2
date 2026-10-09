package com.campmeds.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class InputValidationTest {

    @Test fun validTimesParseSortedAndDeduped() {
        val r = InputValidation.parseScheduleTimes("20:00, 8:00, 08:00")
        assertEquals(Validated.Ok(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))), r)
    }

    @Test fun oneBadTimeRejectsWholeFieldInsteadOfBeingDropped() {
        val r = InputValidation.parseScheduleTimes("08:00, 20:00, 25:00")
        assertTrue(r is Validated.Invalid)
        assertTrue((r as Validated.Invalid).message.contains("\"25:00\""))
    }

    @Test fun ampmIsRejectedNotTurnedIntoEmptySchedule() {
        val r = InputValidation.parseScheduleTimes("8 AM")
        assertEquals(
            Validated.Invalid("Invalid time: \"8 AM\". Use HH:MM format, such as 08:00."), r
        )
    }

    @Test fun blankAndEmptyEntriesRejected() {
        assertTrue(InputValidation.parseScheduleTimes("   ") is Validated.Invalid)
        assertTrue(InputValidation.parseScheduleTimes("08:00,,20:00") is Validated.Invalid)
        assertTrue(InputValidation.parseScheduleTimes("08:00,") is Validated.Invalid)
        assertTrue(InputValidation.parseScheduleTimes("24:00") is Validated.Invalid)
        assertTrue(InputValidation.parseScheduleTimes("8:5") is Validated.Invalid)
    }

    @Test fun impossibleDateRejected() {
        assertTrue(InputValidation.parseDate("2026-09-31", "start date", true) is Validated.Invalid)
        assertTrue(InputValidation.parseDate("07/15/2026", "start date", true) is Validated.Invalid)
    }

    @Test fun optionalBlankDateIsNullButRequiredBlankIsError() {
        assertEquals(Validated.Ok(null), InputValidation.parseDate("", "end date", false))
        assertTrue(InputValidation.parseDate("", "start date", true) is Validated.Invalid)
    }

    @Test fun endBeforeStartRejected_sameDayAllowed() {
        val start = LocalDate.of(2026, 10, 10)
        assertTrue(InputValidation.validateDateRange(start, LocalDate.of(2026, 10, 1)) != null)
        assertNull(InputValidation.validateDateRange(start, start))
        assertNull(InputValidation.validateDateRange(start, null))
    }
}
