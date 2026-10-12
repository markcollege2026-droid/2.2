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
    fun aNineDigitThreeSegmentCodeSaysHowManyDigitsItHas() {
        // Reported code 0093-025-01: 4-3-2 is 9 digits, so it is one digit short of any NDC (10, or 11 in the
        // billing format). Naming the arrangement without the digit count left the user guessing which part
        // was wrong; the count is what says "a digit is missing".
        val bad = invalid("0093-025-01")

        assertTrue(bad.message.contains("4-3-2"))
        assertTrue(bad.message.contains("9 digits"))
        assertTrue(bad.message.contains("missing or extra digit"))
        // And it is never searched in a "repaired" form: nothing is guessed on the user's behalf.
        assertTrue(NdcNormalizer.candidates("0093-025-01").isEmpty())
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

    // ---------------------------------------------------------------------------------------------
    // 5-3-2 (the arrangement whose unhyphenated form is hardest to read) and pasted values.
    // The real codes below are from the openFDA NDC Directory.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun fiveThreeTwoWithDashesHasExactlyOneReading() {
        // 68180-613-07 (Lupin quetiapine): labeler 68180, product 613, package 07.
        val parsed = ok("68180-613-07")

        assertEquals(listOf("68180-613-07"), parsed.candidates.map { it.value })
        assertEquals(listOf(5, 3, 2), parsed.candidates.single().shape)
        assertTrue(parsed.candidates.single().isPackage)
        assertFalse(parsed.ambiguous)
    }

    @Test
    fun fiveThreeTwoWithoutDashesOffersAllThreeReadingsInShapeOrder() {
        val parsed = ok("6818061307")

        assertEquals(
            listOf("6818-0613-07", "68180-613-07", "68180-6130-7"),
            parsed.candidates.map { it.value }
        )
        assertEquals(
            listOf(listOf(4, 4, 2), listOf(5, 3, 2), listOf(5, 4, 1)),
            parsed.candidates.map { it.shape }
        )
        assertTrue(parsed.ambiguous)
    }

    @Test
    fun fiveThreeTwoAndFiveFourOneBothGetTheZeroAddedToTheShortSegmentOnly() {
        // 5-3-2: the product segment is padded (5-4-2). 5-4-1: the package segment is padded.
        assertEquals("68180-0613-07", NdcNormalizer.toBillingForm("68180-613-07"))
        assertEquals("68180-6130-07", NdcNormalizer.toBillingForm("68180-6130-7"))
        // A 4-4-2 pads the labeler; the segments themselves are never touched.
        assertEquals("00069-2587-68", NdcNormalizer.toBillingForm("0069-2587-68"))
    }

    @Test
    fun fiveThreeTwoBillingFormIsReadBackToFiveThreeTwoNotToFourFourTwo() {
        // 68180-0613-07 is the billing form of 68180-613-07: the zero can only be removed from the segment
        // that carries it, so the 5-3-2 reading comes first (the 5-4-1 reading is the other legal split).
        val values = values("68180-0613-07")
        assertEquals("68180-613-07", values.first())
        assertEquals(listOf("68180-613-07", "68180-0613-7"), values)
    }

    @Test
    fun aTypographicDashOrNonBreakingSpaceSeparatesJustLikeAHyphen() {
        // What a word processor or a spreadsheet does to a pasted NDC: en dash, non-breaking space,
        // zero-width space and byte-order mark.
        assertEquals(listOf("68180-613-07"), values("68180\u2013613\u201307"))
        assertEquals(listOf("68180-613-07"), values("68180\u00A0613\u00A007"))
        assertEquals(listOf("68180-613-07"), values("\uFEFF68180-613-07\u200B"))
        // A leading apostrophe or trailing percent comes from a copied spreadsheet cell.
        assertEquals(listOf("68180-613-07"), values("'68180-613-07"))
        assertEquals(listOf("68180-613-07"), values("68180-613-07%"))
    }

    @Test
    fun digitsOfAnotherScriptAreRejectedWithTheCharacterNamed() {
        // Arabic-Indic digits are digits, but an NDC is printed in ASCII digits: this is refused, not searched.
        val bad = invalid("\u0661\u0662\u0663\u0664\u0665-\u0661\u0662\u0663")
        assertTrue(bad.message.contains("not one of them"))
        assertTrue(NdcNormalizer.candidates("\u0661\u0662\u0663\u0664\u0665-\u0661\u0662\u0663").isEmpty())
    }

    @Test
    fun anInvisibleCharacterIsDroppedInsteadOfBecomingADigitOrASeparator() {
        // A zero-width space carries no information, so it is removed and the value is read as the digit
        // string it is — every legal reading is offered, never one that was silently re-cut.
        val parsed = ok("68180\u200B613\u200B07")
        assertEquals("6818061307", parsed.digits)
        assertEquals(listOf("6818-0613-07", "68180-613-07", "68180-6130-7"), parsed.candidates.map { it.value })

        // A zero-width space inside a segment (what wrapping a value across lines leaves behind) is dropped
        // too, so the code still reads exactly as it is printed.
        assertEquals(listOf("68180-613-07"), values("68180-61\u200B3-07"))
    }
}
