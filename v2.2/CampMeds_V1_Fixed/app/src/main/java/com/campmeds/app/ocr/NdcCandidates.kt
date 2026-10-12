package com.campmeds.app.ocr

/**
 * Finds NDC-shaped numbers in recognised text. Pure Kotlin, no Android dependencies.
 *
 * What it does:
 *  - finds hyphen- or space-separated numbers whose group lengths match 4-4-2, 5-3-2, 5-4-1 or the 11-digit
 *    billing layout 5-4-2;
 *  - finds unseparated runs of 10 digits (each of the three 10-digit readings is returned, because the digits
 *    alone cannot say which one is meant) and of 11 digits (the billing layout);
 *  - collapses repeats of the same reading and counts them.
 *
 * What it does NOT do: it never pads, trims or "corrects" digits, and it never decides a number is a real NDC.
 * Matching a number against the FDA directory is the existing lookup's job.
 */
object NdcCandidateExtractor {

    private val separated = Regex("""(?<![0-9])([0-9]{4,5})([- ])([0-9]{3,4})\2([0-9]{1,2})(?![0-9])""")
    private val unseparated = Regex("""(?<![0-9])[0-9]{10,11}(?![0-9])""")

    fun extract(lines: List<OcrLine>): List<NdcCandidate> {
        val found = LinkedHashMap<String, NdcCandidate>()
        lines.forEachIndexed { index, line ->
            for (candidate in candidatesIn(line.text, index, line)) {
                val key = candidate.format.name + ":" + candidate.digits
                val existing = found[key]
                found[key] = if (existing == null) candidate
                else existing.copy(occurrences = existing.occurrences + 1)
            }
        }
        return found.values.toList()
    }

    private fun candidatesIn(text: String, lineIndex: Int, line: OcrLine): List<NdcCandidate> {
        val out = mutableListOf<NdcCandidate>()

        separated.findAll(text).forEach { m ->
            val first = m.groupValues[1]
            val second = m.groupValues[3]
            val third = m.groupValues[4]
            val format = formatFor(first.length, second.length, third.length) ?: return@forEach
            out += NdcCandidate(
                text = m.value,
                hyphenated = listOf(first, second, third).joinToString("-"),
                digits = first + second + third,
                format = format,
                fromUnseparatedDigits = false,
                occurrences = 1,
                lineIndex = lineIndex,
                confidence = line.confidence,
                boundingBox = line.boundingBox
            )
        }

        unseparated.findAll(text).forEach { m ->
            val digits = m.value
            val readings: List<Triple<NdcFormat, Int, Int>> = when (digits.length) {
                11 -> listOf(Triple(NdcFormat.BILLING_5_4_2, 5, 4))
                10 -> listOf(
                    Triple(NdcFormat.FORMAT_4_4_2, 4, 4),
                    Triple(NdcFormat.FORMAT_5_3_2, 5, 3),
                    Triple(NdcFormat.FORMAT_5_4_1, 5, 4)
                )
                else -> emptyList()
            }
            for ((format, firstLen, secondLen) in readings) {
                val first = digits.substring(0, firstLen)
                val second = digits.substring(firstLen, firstLen + secondLen)
                val third = digits.substring(firstLen + secondLen)
                out += NdcCandidate(
                    text = digits,
                    hyphenated = "$first-$second-$third",
                    digits = digits,
                    format = format,
                    fromUnseparatedDigits = true,
                    occurrences = 1,
                    lineIndex = lineIndex,
                    confidence = line.confidence,
                    boundingBox = line.boundingBox
                )
            }
        }
        return out
    }

    /** Only the group lengths that exist in the NDC layouts. Anything else (e.g. 9 digits, dates) is not a candidate. */
    private fun formatFor(a: Int, b: Int, c: Int): NdcFormat? = when {
        a == 4 && b == 4 && c == 2 -> NdcFormat.FORMAT_4_4_2
        a == 5 && b == 3 && c == 2 -> NdcFormat.FORMAT_5_3_2
        a == 5 && b == 4 && c == 1 -> NdcFormat.FORMAT_5_4_1
        a == 5 && b == 4 && c == 2 -> NdcFormat.BILLING_5_4_2
        else -> null
    }
}
