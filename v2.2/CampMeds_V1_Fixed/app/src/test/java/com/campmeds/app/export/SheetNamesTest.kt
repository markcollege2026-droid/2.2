package com.campmeds.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetNamesTest {
    @Test fun nameIsFirstLastMedication() {
        assertEquals("Ann Lee Albuterol", SheetNames.baseName("Ann Lee", "Albuterol"))
    }

    @Test fun overLongNamesAreCutAtTheEndTo31() {
        val n = SheetNames.baseName("Alexandria Montgomery", "Hydrochlorothiazide")
        assertEquals(31, n.length)
        assertTrue("Alexandria Montgomery Hydrochlorothiazide".startsWith(n))
    }

    @Test fun exactly31IsKeptAndTrailingSpaceAfterCutIsTrimmed() {
        val exact = "a".repeat(31)
        assertEquals(exact, SheetNames.sanitize(exact))
        // 30 chars + space + more: the cut lands right after the space, which must not be left dangling
        val s = SheetNames.sanitize("a".repeat(30) + " bbbb")
        assertEquals("a".repeat(30), s)
    }

    @Test fun forbiddenCharactersAreReplacedAndApostrophesTrimmed() {
        assertEquals("Ann Lee Vit B 12", SheetNames.sanitize("Ann Lee Vit/B:12"))
        assertEquals("Ann", SheetNames.sanitize("'Ann'"))
        assertEquals("Medication", SheetNames.sanitize("///"))
    }

    @Test fun duplicatesGetNumberedSuffixesThatStillFit() {
        val used = HashSet<String>()
        val base = "a".repeat(31)
        val first = SheetNames.unique(base, used)
        val second = SheetNames.unique(base, used)
        val third = SheetNames.unique(base.uppercase(), used) // Excel treats names case-insensitively
        assertEquals(base, first)
        assertEquals("a".repeat(27) + " (2)", second)
        assertEquals("A".repeat(27) + " (3)", third)
        assertTrue(listOf(first, second, third).all { it.length <= 31 })
    }
}
