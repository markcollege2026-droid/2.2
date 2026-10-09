package com.campmeds.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdcNormalizerTest {
    private fun values(raw: String) = NdcNormalizer.candidates(raw).map { it.value }

    @Test fun productNdcWithDashes() {
        val c = NdcNormalizer.candidates("0069-2587")
        assertEquals(listOf("0069-2587"), c.map { it.value })
        assertTrue(c.none { it.isPackage })
    }

    @Test fun packageNdcWithDashesIsSearchedAsPackage() {
        val c = NdcNormalizer.candidates(" 0069-2587-68 ")
        assertEquals(listOf("0069-2587-68"), c.map { it.value })
        assertTrue(c.all { it.isPackage })
    }

    @Test fun elevenDigitWithDashesDropsThePaddingZero() {
        assertEquals(listOf("00069-2587-68", "0069-2587-68"), values("00069-2587-68"))
    }

    @Test fun elevenDigitWithoutDashes() {
        assertEquals(listOf("00069-2587-68", "0069-2587-68"), values("00069258768"))
    }

    @Test fun undashedTenDigitsReturnEveryLegalSplit() {
        assertEquals(listOf("0069-2587-68", "00692-587-68", "00692-5876-8"), values("0069258768"))
    }

    @Test fun eightDigitProductWithoutDashes() {
        assertEquals(listOf("0069-2587", "00692-587"), values("00692587"))
    }

    @Test fun rejectsLettersBlankAndWrongLengths() {
        assertTrue(NdcNormalizer.candidates("").isEmpty())
        assertTrue(NdcNormalizer.candidates("ABC-1234").isEmpty())
        assertTrue(NdcNormalizer.candidates("12345").isEmpty())
        assertTrue(NdcNormalizer.candidates("1-2-3-4").isEmpty())
    }
}
