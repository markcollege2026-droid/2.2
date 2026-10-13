package com.campmeds.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdcCandidatesTest {

    private fun lines(vararg text: String, boxed: Boolean = false) = text.mapIndexed { i, t ->
        OcrLine(t, confidence = null, boundingBox = if (boxed) BoundingBox(i, i, i + 10, i + 5) else null)
    }

    private fun extract(vararg text: String) = NdcCandidateExtractor.extract(lines(*text))

    @Test
    fun hyphenated10DigitLayoutsAreRecognisedAsWritten() {
        assertEquals(
            listOf(NdcFormat.FORMAT_4_4_2),
            extract("NDC 0069-2587-68").map { it.format }
        )
        assertEquals(listOf(NdcFormat.FORMAT_5_3_2), extract("68180-613-07").map { it.format })
        assertEquals(listOf(NdcFormat.FORMAT_5_4_1), extract("00093-7384-1").map { it.format })
    }

    @Test
    fun billingLayoutIsRecognisedAndKeptAsWritten() {
        val c = extract("00069-2587-68").single()
        assertEquals(NdcFormat.BILLING_5_4_2, c.format)
        assertEquals("00069-2587-68", c.hyphenated)   // never padded or trimmed
        assertEquals("00069258768", c.digits)
    }

    @Test
    fun spaceSeparatedNumbersAreRecognised() {
        val c = extract("NDC 0069 2587 68").single()
        assertEquals("0069-2587-68", c.hyphenated)
        assertEquals(NdcFormat.FORMAT_4_4_2, c.format)
    }

    @Test
    fun unseparatedTenDigitsReturnEveryReadingAndFlagThem() {
        val found = extract("0069258768")
        assertEquals(
            listOf(NdcFormat.FORMAT_4_4_2, NdcFormat.FORMAT_5_3_2, NdcFormat.FORMAT_5_4_1),
            found.map { it.format }
        )
        assertTrue(found.all { it.fromUnseparatedDigits })
        assertEquals(listOf("0069-2587-68", "00692-587-68", "00692-5876-8"), found.map { it.hyphenated })
    }

    @Test
    fun unseparatedElevenDigitsAreTheBillingLayout() {
        val found = extract("00069258768")
        assertEquals(1, found.size)
        assertEquals(NdcFormat.BILLING_5_4_2, found.single().format)
        assertEquals("00069-2587-68", found.single().hyphenated)
    }

    @Test
    fun otherDigitGroupingsAndDatesAreNotCandidates() {
        assertTrue(extract("0093-025-01").isEmpty())   // 9 digits: not an NDC length
        assertTrue(extract("Take 2 tablets at 08:00").isEmpty())
        assertTrue(extract("Printed 2026-10-12").isEmpty())
        assertTrue(extract("Lot 123456789").isEmpty())
    }

    @Test
    fun duplicatesCollapseAndAreCounted() {
        val found = extract("NDC 0069-2587-68", "Again 0069-2587-68", "0069-2587-68")
        assertEquals(1, found.size)
        assertEquals(3, found.single().occurrences)
        assertEquals(0, found.single().lineIndex)   // first place it was seen
    }

    @Test
    fun locationAndConfidenceComeFromTheLine() {
        val input = listOf(OcrLine("NDC 0069-2587-68", confidence = 0.9f, boundingBox = BoundingBox(1, 2, 3, 4)))
        val c = NdcCandidateExtractor.extract(input).single()
        assertEquals(0.9f, c.confidence!!, 0.0001f)
        assertEquals(BoundingBox(1, 2, 3, 4), c.boundingBox)
        assertEquals(0, c.lineIndex)
    }

    @Test
    fun emptyInputGivesNoCandidates() {
        assertTrue(NdcCandidateExtractor.extract(emptyList()).isEmpty())
    }
}
