package com.campmeds.app.domain

/**
 * Turns whatever a person types or reads off a bottle into the NDC forms the openFDA NDC Directory
 * actually stores — and says plainly when the text cannot be an NDC at all.
 *
 * The formats the FDA recognises (and the only ones handled here):
 *
 *  | 10-digit NDC (directory format) | segment lengths | 11-digit billing format |
 *  |---------------------------------|-----------------|-------------------------|
 *  | product                         | 4-4             | 5-4 (zero added to labeler)   |
 *  | product                         | 5-3             | 5-4 (zero added to product)   |
 *  | package                         | 4-4-2           | 5-4-2 (zero added to labeler) |
 *  | package                         | 5-3-2           | 5-4-2 (zero added to product) |
 *  | package                         | 5-4-1           | 5-4-2 (zero added to package) |
 *
 * Rules that matter for safety:
 *  - A zero is only ever *added to* or *removed from* the one segment the billing format pads. Removing a
 *    zero never changes which drug or package the code identifies, because the padded zero is not part of
 *    the assigned labeler/product/package code.
 *  - The 10-digit directory form and the 11-digit billing form are NOT interchangeable strings: the
 *    11-digit form is converted by removing one padding zero from the correct segment, never by trimming
 *    the front of the whole number.
 *  - An unhyphenated digit string usually has more than one legal reading (e.g. 10 digits can be split
 *    4-4-2, 5-3-2 or 5-4-1). Those readings are all reported ([Parsed.ambiguous]); the caller checks each
 *    one against the directory and asks the user to confirm rather than silently picking one.
 *  - Anything that is not one of the recognised arrangements is [Parsed.Invalid] with a specific reason
 *    (wrong length, wrong characters, wrong arrangement) — never silently "fixed" into something else.
 *  - The digits as typed are always preserved in [Parsed.entered].
 *  - A value pasted from a word processor or a spreadsheet is read as it is: typographic dashes and
 *    non-breaking spaces separate segments, zero-width characters are dropped, and digits of another script
 *    are refused (an NDC is printed in plain ASCII digits).
 */
object NdcNormalizer {

    /** Recognised 10-digit product arrangements (the directory's `product_ndc` field). */
    private val PRODUCT_SHAPES = listOf(listOf(4, 4), listOf(5, 3))

    /** Recognised 10-digit package arrangements (the directory's `packaging.package_ndc` field). */
    private val PACKAGE_SHAPES = listOf(listOf(4, 4, 2), listOf(5, 3, 2), listOf(5, 4, 1))

    /** The single standardised 11-digit billing arrangement. */
    private val BILLING_SHAPE = listOf(5, 4, 2)

    /** Product portion of the billing format (2 segments, 9 digits): 4-4 or 5-3 after the padding zero. */
    private val BILLING_PRODUCT_SHAPE = listOf(5, 4)

    /** One searchable NDC form, always the 10-digit (or product) form the directory stores, dashed. */
    data class Candidate(
        /** e.g. "0069-2587-68" */
        val value: String,
        val isPackage: Boolean,
        val shape: List<Int>,
        /** True when this form was reached by removing one padding zero from a billing code. */
        val fromBilling: Boolean = false
    )

    sealed class Parsed {
        data class Ok(
            /** The text exactly as typed (trimmed) — kept for display and troubleshooting. */
            val entered: String,
            /** Every dash/space-free digit of the input. */
            val digits: String,
            val candidates: List<Candidate>,
            /** True when the typed digits have more than one legal reading. */
            val ambiguous: Boolean
        ) : Parsed()

        /** Not an NDC at all; [message] explains why in words the form can show the user. */
        data class Invalid(val message: String) : Parsed()
    }

    const val HELP = "Examples: 0069-2587 (product), 0069-2587-68 (package), 00069-2587-68 or 00069258768 (11-digit billing)."

    /**
     * Characters that carry no information but do survive a copy/paste: zero-width spaces and the byte-order
     * mark. They are dropped before anything else — they are never considered digits.
     */
    private const val INVISIBLE = "\u200B\u200C\u200D\uFEFF"

    /**
     * Typographic dashes (what a word processor turns a typed "-" into) plus the non-breaking space. All of
     * them separate segments exactly like "-" does; accepting them cannot change which digits a code has.
     */
    private const val SEPARATORS = "\u2010\u2011\u2012\u2013\u2014\u2015\u2212\u00A0"

    private val SEPARATOR_REGEX = Regex("[\\s" + SEPARATORS + "-]+")

    /** Shown when a character that can never be part of an NDC turns up in the field. */
    private fun notAnNdcChar(char: Char) =
        "An NDC contains digits and dashes only — \"$char\" is not one of them. $HELP"

    /** True for a plain ASCII digit and for nothing else: digits of another script are refused, not guessed. */
    private fun isAsciiDigit(char: Char) = char in '0'..'9'

    private fun isSeparator(char: Char) = char == '-' || char.isWhitespace() || char in SEPARATORS

    private fun segmentsOf(text: String): List<String> =
        text.split(SEPARATOR_REGEX).filter { it.isNotEmpty() }

    /** Invisibles removed, and the punctuation a spreadsheet wraps around a copied value dropped. */
    private fun cleaned(raw: String): String =
        raw.filterNot { it in INVISIBLE }
            .trim { it.isWhitespace() || it in SEPARATORS || it == '\'' || it == '%' }

    /** Convenience for callers that only need the searchable forms; an invalid input yields an empty list. */
    fun candidates(raw: String): List<Candidate> =
        when (val parsed = parse(raw)) {
            is Parsed.Ok -> parsed.candidates
            is Parsed.Invalid -> emptyList()
        }

    fun parse(raw: String): Parsed {
        val text = cleaned(raw)
        if (text.isEmpty()) {
            return Parsed.Invalid("Enter an NDC number, or use Manual entry if there is no NDC.")
        }
        // Nothing is removed from the value except characters that carry no digits at all, and anything
        // unexpected stops the lookup here instead of being ignored or "corrected".
        text.firstOrNull { !isAsciiDigit(it) && !isSeparator(it) }?.let { return Parsed.Invalid(notAnNdcChar(it)) }

        val digits = text.filter { isAsciiDigit(it) }
        if (digits.isEmpty()) return Parsed.Invalid("An NDC contains digits and dashes only. $HELP")

        val separated = text.any { isSeparator(it) }
        return if (separated) parseSeparated(text, digits) else parseDigitsOnly(text, digits)
    }

    // ---------------------------------------------------------------------------------------------
    // Unhyphenated input
    // ---------------------------------------------------------------------------------------------

    private fun parseDigitsOnly(text: String, digits: String): Parsed = when (digits.length) {
        // 8 digits: a product NDC, either 4-4 or 5-3.
        8 -> ok(text, digits, PRODUCT_SHAPES.map { candidate(split(digits, it), isPackage = false) })

        // 9 digits: the product portion of the billing format (4-4 or 5-3 with the padding zero added).
        9 -> billingProductReadings(split(digits, BILLING_PRODUCT_SHAPE), text, digits)

        // 10 digits: a package NDC, split 4-4-2, 5-3-2 or 5-4-1.
        10 -> ok(text, digits, PACKAGE_SHAPES.map { candidate(split(digits, it), isPackage = true) })

        // 11 digits: the billing format. Exactly one segment carries the padding zero, and it can only be
        // removed from a segment that actually starts with a zero.
        11 -> ok(text, digits, billingReadings(split(digits, BILLING_SHAPE)))

        else -> Parsed.Invalid(
            "An NDC has 10 or 11 digits (8 digits for a product code) — this has ${digits.length}. " +
                "Check for a missing or extra digit, or enter the code with its dashes as printed on the label."
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Hyphenated (or space separated) input: the boundaries are given, so only the padding zero of a
    // billing code has to be resolved.
    // ---------------------------------------------------------------------------------------------

    private fun parseSeparated(text: String, digits: String): Parsed {
        val segments = segmentsOf(text)
        if (segments.size !in 2..3) {
            return Parsed.Invalid("Use one or two dashes to separate the NDC segments. $HELP")
        }
        if (segments.any { it.length > 5 }) {
            return Parsed.Invalid("An NDC segment is between 1 and 5 digits long. $HELP")
        }
        val shape = segments.map { it.length }

        // The arrangement as typed, when the FDA recognises it.
        if (shape in PRODUCT_SHAPES) return ok(text, digits, listOf(candidate(segments, isPackage = false)))
        if (shape in PACKAGE_SHAPES) return ok(text, digits, listOf(candidate(segments, isPackage = true)))

        // 11-digit billing codes (5-4-2): try removing the padding zero from each segment it could be in.
        if (shape == BILLING_SHAPE) {
            val readings = billingReadings(segments)
            if (readings.isNotEmpty()) return ok(text, digits, readings)
        }

        // 9-digit billing product portion (5-4): same idea, two readings at most.
        if (shape == BILLING_PRODUCT_SHAPE) {
            val readings = productReadings(segments)
            if (readings.isNotEmpty()) return ok(text, digits, readings)
        }

        return Parsed.Invalid(
            "That segment arrangement (${shape.joinToString("-")}) is not a recognised NDC format. " +
                "A 10-digit NDC is 4-4-2, 5-3-2 or 5-4-1; an 11-digit billing code is 5-4-2. $HELP"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun ok(text: String, digits: String, candidates: List<Candidate>): Parsed {
        val distinct = candidates.distinct()
        return Parsed.Ok(
            entered = text,
            digits = digits,
            candidates = distinct,
            // More than one reading of the same digits: the caller must not guess (see class docs).
            ambiguous = distinct.size > 1
        )
    }

    /** Every legal product reading of a 9-digit billing product portion, after removing the padding zero. */
    private fun billingProductReadings(segments: List<String>, text: String, digits: String): Parsed {
        val readings = productReadings(segments)
        return if (readings.isEmpty()) {
            Parsed.Invalid(
                "A 9-digit number is a billing product code (4-4 or 5-3 with one zero added) — $digits " +
                    "does not match that pattern. $HELP"
            )
        } else {
            ok(text, digits, readings)
        }
    }

    private fun productReadings(segments: List<String>): List<Candidate> =
        stripPadding(segments)
            .map { candidate(it, isPackage = false, fromBilling = true) }
            .filter { it.shape in PRODUCT_SHAPES }

    /** Every legal product/package reading of an 11-digit billing code, after removing the padding zero. */
    private fun billingReadings(billingSegments: List<String>): List<Candidate> =
        stripPadding(billingSegments)
            .map { candidate(it, isPackage = it.size == 3, fromBilling = true) }
            .filter { it.shape in PACKAGE_SHAPES }

    /**
     * For each segment that starts with the padding zero, the same code with exactly that one zero
     * removed. Removing a padding zero is the only conversion the billing format requires; the result is
     * filtered by the caller against the recognised arrangements.
     */
    private fun stripPadding(segments: List<String>): List<List<String>> =
        segments.indices.mapNotNull { i ->
            val segment = segments[i]
            if (!segment.startsWith("0")) return@mapNotNull null
            segments.toMutableList().also { it[i] = segment.substring(1) }
        }

    private fun split(digits: String, shape: List<Int>): List<String> {
        val parts = ArrayList<String>(shape.size)
        var i = 0
        for (len in shape) {
            parts += digits.substring(i, i + len)
            i += len
        }
        return parts
    }

    private fun candidate(segments: List<String>, isPackage: Boolean, fromBilling: Boolean = false) = Candidate(
        value = segments.joinToString("-"),
        isPackage = isPackage,
        shape = segments.map { it.length },
        fromBilling = fromBilling
    )

    /**
     * The standardised 11-digit billing form (5-4-2) of a recognised directory NDC, or null when the input
     * is not a recognised form. The zero is added to the segment that is short — the labeler for 4-4(-2),
     * the product for 5-3(-2), the package for 5-4-1 — never to the front of the whole number.
     */
    fun toBillingForm(directoryNdc: String): String? {
        val text = cleaned(directoryNdc)
        if (text.isEmpty()) return null
        val segments = segmentsOf(text)
        val shape = segments.map { it.length }
        val billing = when (shape) {
            listOf(4, 4) -> listOf(pad(segments[0]), segments[1])
            listOf(5, 3) -> listOf(segments[0], pad(segments[1]))
            listOf(4, 4, 2) -> listOf(pad(segments[0]), segments[1], segments[2])
            listOf(5, 3, 2) -> listOf(segments[0], pad(segments[1]), segments[2])
            listOf(5, 4, 1) -> listOf(segments[0], segments[1], pad(segments[2]))
            else -> return null
        }
        return billing.joinToString("-")
    }

    private fun pad(segment: String) = "0$segment"
}
