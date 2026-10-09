package com.campmeds.app.export

/**
 * Worksheet naming for the medication export. Pure (no POI/Android) so it is unit-tested.
 *
 * Sheet name = "FirstName LastName MedicationName". Excel allows at most 31 characters, so an over-long
 * name is simply cut off at the end until it fits. Characters Excel forbids (\ / ? * [ ] :) are replaced
 * with a space, since a workbook containing them cannot be opened.
 */
object SheetNames {
    const val MAX_LENGTH = 31

    fun baseName(patientName: String, medicationName: String): String =
        sanitize(listOf(patientName, medicationName).filter { it.isNotBlank() }.joinToString(" "))

    fun sanitize(raw: String): String {
        val cleaned = raw
            .replace(Regex("[\\\\/?*\\[\\]:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('\'')          // Excel rejects names that start or end with an apostrophe
            .trim()
        return truncate(cleaned, MAX_LENGTH).ifBlank { "Medication" }
    }

    /** Cuts the end off; never leaves half of a surrogate pair or a trailing space behind. */
    fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        var cut = text.substring(0, max)
        if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) cut = cut.dropLast(1)
        return cut.trimEnd()
    }

    /**
     * Excel sheet names must be unique, case-insensitively. Two medications can legitimately truncate to the same
     * text (or share patient + medication name, e.g. a retired and a current record). The first keeps the plain
     * name; later ones get " (2)", " (3)", ... with the base shortened so the whole name still fits in 31.
     */
    fun unique(base: String, used: MutableSet<String>): String {
        var candidate = base
        var n = 2
        while (candidate.lowercase() in used) {
            val suffix = " ($n)"
            candidate = truncate(base, MAX_LENGTH - suffix.length) + suffix
            n++
        }
        used += candidate.lowercase()
        return candidate
    }
}
