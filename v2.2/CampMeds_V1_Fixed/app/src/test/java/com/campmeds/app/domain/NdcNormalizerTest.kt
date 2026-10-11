package com.campmeds.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Format coverage for NDC handling. The expected values are the FDA's recognised arrangements
 * (10-digit product 4-4 / 5-3; 10-digit package 4-4-2 / 5-3-2 / 5-4-1; 11-digit billing 5-4-2 with the
 * padding zero in exactly one segment) and the conversions between them — they are verified by shape and by
 * round trip, not by assuming that an arbitrary number is a real drug.
 */
class NdcNormalizerTest {

    private fun ok(raw: String): NdcNormalizer.Parsed.Ok =
        NdcNormalizer.parse(raw) as? NdcNormalizer.Parsed.Ok
            ?: throw AssertionError("expected $raw to parse, got ${NdcNormalizer.parse(raw)}")

    private fun invalid(raw: String): NdcNormalizer.Parsed.Invalid =
        NdcNormalizer.parse(raw) as? NdcNormalizer.Parsed.Invalid
            ?: throw AssertionError("expected $raw to be rejected, got ${NdcNormalizer.parse(raw)}")

    private fun values(raw: String) = NdcNormalizer.candidates(raw).map { it.value }

    // ---------------------------------------------------------------------------------------------
    // The recognised 10-digit arrangements, hyphenated
    // ---------------------------------------------------------------------------------------------

    @Test
    fun productNdcWithDashes() {
        val parsed = ok("0069-2587")
        assertEquals(listOf("0069-2587"), parsed.candidates.map { it.value })
        assertFalse(parsed.candidates.single().isPackage)
        assertFalse(parsed.ambiguous)
        assertEquals("0069-2587", parsed.entered)
    }

    @Test
    fun fiveThreeProductWithDashes() {
        val parsed = ok("12345-678")
        assertEquals(listOf("12345-678"), parsed.candidates.map { it.value })
        assertFalse(parsed.candidates.single().isPackage)
        assertEquals(listOf(5, 3), parsed.candidates.single().shape)
    }

    @Test
    fun everyTenDigitPackageArrangementIsAccepted() {
        // 4-4-2
        assertEquals(listOf("0069-2587-68"), values("0069-2587-68"))
        // 5-3-2
        assertEquals(listOf("12345-678-90"), values("12345-678-90"))
        // 5-4-1
        assertEquals(listOf("12345-6789-0"), values("12345-6789-0"))
        assertTrue(NdcNormalizer.candidates("0069-2587-68").all { it.isPackage })
        assertTrue(NdcNormalizer.candidates("12345-678-90").all { it.isPackage })
        assertTrue(NdcNormalizer.candidates("12345-6789-0").all { it.isPackage })
    }

    @Test
    fun spacesInsteadOfDashesAreAccepted() {
        assertEquals(listOf("0069-2587-68"), values("0069 2587 68"))
        assertEquals(listOf("0069-2587"), values("  0069   2587 "))
    }

    @Test
    fun leadingZerosInTheAssignedCodeAreNeverStripped() {
        // "0069" is the labeler code; removing its leading zeros would identify a different product.
        assertEquals(listOf("0069-2587"), values("0069-2587"))
        assertEquals(listOf("0020-2587-68"), values("0020-2587-68"))
    }

    // ---------------------------------------------------------------------------------------------
    // Unhyphenated digits: every legal reading, reported as ambiguous instead of guessed
    // ---------------------------------------------------------------------------------------------

    @Test
    fun undashedTenDigitsReturnEveryLegalPackageSplitAndAreFlaggedAmbiguous() {
        val parsed = ok("0069258768")
        assertEquals(listOf("0069-2587-68", "00692-587-68", "00692-5876-8"), parsed.candidates.map { it.value })
        assertTrue(parsed.ambiguous)
        assertEquals("0069258768", parsed.digits)
    }

    @Test
    fun undashedEightDigitsReturnBothProductSplits() {
        val parsed = ok("00692587")
        assertEquals(listOf("0069-2587", "00692-587"), parsed.candidates.map { it.value })
        assertTrue(parsed.ambiguous)
        assertTrue(parsed.candidates.none { it.isPackage })
    }

    // ---------------------------------------------------------------------------------------------
    // 11-digit billing codes: the padding zero is removed from the segment that carries it
    // ---------------------------------------------------------------------------------------------

    @Test
    fun elevenDigitBillingCodeWithDashesDropsThePaddingZeroAndNeverSearchesThePaddedString() {
        val parsed = ok("00069-2587-68")
        assertEquals(listOf("0069-2587-68"), parsed.candidates.map { it.value })
        assertTrue(parsed.candidates.single().fromBilling)
        assertFalse(parsed.ambiguous)
        // The 11-digit form is a billing format only; the directory stores the 10-digit form.
        assertFalse(parsed.candidates.any { it.value == "00069-2587-68" })
    }

    @Test
    fun elevenDigitBillingCodeWithoutDashes() {
        val parsed = ok("00069258768")
        assertEquals(listOf("0069-2587-68"), parsed.candidates.map { it.value })
        assertTrue(parsed.candidates.single().fromBilling)
    }

    @Test
    fun elevenDigitBillingCodeWithTheZeroInTheProductSegment() {
        assertEquals(listOf("12345-678-90"), values("12345-0678-90"))
        assertEquals(listOf("12345-678-90"), values("12345067890"))
    }

    @Test
    fun elevenDigitBillingCodeWithTheZeroInThePackageSegment() {
        // A 5-4-1 code such as 12345-6789-0 becomes 12345-6789-00 in the billing format.
        assertEquals(listOf("12345-6789-1"), values("12345-6789-01"))
        assertEquals(listOf("12345-6789-0"), values("12345-6789-00"))
    }

    @Test
    fun undashedElevenDigitsOfferEverySegmentThatCouldCarryThePaddingZero() {
        // "00093-0701-01" can be 4-4-2, 5-3-2 or 5-4-1 once one zero is removed.
        val parsed = ok("00093070101")
        assertEquals(listOf("0093-0701-01", "00093-701-01", "00093-0701-1"), parsed.candidates.map { it.value })
        assertTrue(parsed.ambiguous)
    }

    // ---------------------------------------------------------------------------------------------
    // Product billing portion (9 digits) and conversions
    // ---------------------------------------------------------------------------------------------

    @Test
    fun nineDigitProductBillingCodeIsAcceptedHyphenatedAndPlain() {
        assertEquals(listOf("0069-2587"), values("00069-2587"))
        assertEquals(listOf("0069-2587"), values("000692587"))
        assertEquals(listOf("12345-678"), values("12345-0678"))
    }

    @Test
    fun tenDigitFormsConvertToTheElevenDigitBillingFormatByPaddingOnlyTheShortSegment() {
        assertEquals("00069-2587", NdcNormalizer.toBillingForm("0069-2587"))
        assertEquals("12345-0678", NdcNormalizer.toBillingForm("12345-678"))
        assertEquals("00069-2587-68", NdcNormalizer.toBillingForm("0069-2587-68"))
        assertEquals("12345-0678-90", NdcNormalizer.toBillingForm("12345-678-90"))
        assertEquals("12345-6789-00", NdcNormalizer.toBillingForm("12345-6789-0"))
    }

    @Test
    fun billingConversionRoundTripsThroughTheNormalizer() {
        listOf("0069-2587", "12345-678", "0069-2587-68", "12345-678-90", "12345-6789-0").forEach { directoryNdc ->
            val billing = NdcNormalizer.toBillingForm(directoryNdc)
            assertNotNull("no billing form for $directoryNdc", billing)
            assertTrue(
                "billing form $billing of $directoryNdc did not normalise back",
                values(billing!!).contains(directoryNdc)
            )
        }
    }

    @Test
    fun billingConversionRejectsWhatIsNotARecognisedNdc() {
        assertNull(NdcNormalizer.toBillingForm(""))
        assertNull(NdcNormalizer.toBillingForm("00692587"))
        assertNull(NdcNormalizer.toBillingForm("not-an-ndc"))
        assertNull(NdcNormalizer.toBillingForm("12345-67890"))
    }

    // ---------------------------------------------------------------------------------------------
    // Invalid input: rejected with a specific reason, never "fixed"
    // ---------------------------------------------------------------------------------------------

    @Test
    fun emptyInputIsRejectedWithAnInstruction() {
        assertTrue(invalid("").message.contains("Enter an NDC"))
        assertTrue(invalid("   ").message.contains("Enter an NDC"))
        assertTrue(invalid("-").message.contains("digits and dashes"))
    }

    @Test
    fun lettersAndSymbolsAreRejected() {
        assertTrue(invalid("ABC-1234").message.contains("digits and dashes"))
        assertTrue(invalid("0069/2587").message.contains("digits and dashes"))
    }

    @Test
    fun wrongDigitCountsAreRejectedAndSayWhatWasWrong() {
        assertTrue(invalid("1234567").message.contains("has 7"))
        assertTrue(invalid("123456789012").message.contains("has 12"))
        assertTrue(invalid("1234567890123").message.contains("has 13"))
    }

    @Test
    fun wrongSegmentArrangementsAreRejectedAndNameTheArrangement() {
        assertTrue(invalid("1-2-3-4").message.contains("one or two dashes"))
        assertTrue(invalid("123456-78").message.contains("between 1 and 5 digits"))
        // A labeler segment shorter than 4 digits (3-4-2 here) is not a recognised arrangement either; the
        // message names the arrangement as typed so the label can be checked digit by digit.
        assertTrue(invalid("069-2587-68").message.contains("3-4-2"))
        // 5-5 is not a recognised two-segment arrangement.
        assertTrue(invalid("12345-67890").message.contains("5-5"))
    }

    @Test
    fun nineDigitNumberThatIsNotABillingProductCodeIsRejected() {
        // "123456789" cannot be read as 5-4 with a padding zero (no segment starts with 0).
        assertTrue(invalid("123456789").message.contains("billing product code"))
    }

    @Test
    fun nothingIsReturnedAsSearchableForInvalidInput() {
        assertTrue(NdcNormalizer.candidates("").isEmpty())
        assertTrue(NdcNormalizer.candidates("ABC-1234").isEmpty())
        assertTrue(NdcNormalizer.candidates("12345").isEmpty())
        assertTrue(NdcNormalizer.candidates("1-2-3-4").isEmpty())
    }

    @Test
    fun helpTextIsAvailableForTheForm() {
        assertTrue(NdcNormalizer.HELP.contains("0069-2587"))
        assertTrue(NdcNormalizer.HELP.contains("11-digit"))
    }
}
