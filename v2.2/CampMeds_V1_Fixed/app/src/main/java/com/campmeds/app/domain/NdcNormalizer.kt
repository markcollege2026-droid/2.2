package com.campmeds.app.domain

/**
 * Turns whatever a person types or reads off a bottle into the NDC forms openFDA actually stores.
 *
 * Why this exists: the original lookup sent the raw text to openFDA's `product_ndc` field, which only
 * contains 2-segment *product* codes with dashes (e.g. "0069-2587"). Bottle labels and pharmacy
 * paperwork carry 3-segment *package* codes ("0069-2587-68"), or 11-digit HIPAA codes with a padded zero
 * ("00069-2587-68" / "00069258768"), so almost every realistic entry came back "not found".
 *
 * openFDA stores 10-digit forms: product 4-4 or 5-3, package 4-4-2, 5-3-2 or 5-4-1.
 * 11-digit (5-4-2) input is converted by removing the one padding zero; an undashed 10-digit number is
 * ambiguous, so every legal split is returned and the caller must reject a result that matches more than
 * one product.
 */
object NdcNormalizer {

    data class Candidate(val value: String, val isPackage: Boolean)

    private val PRODUCT_SHAPES = listOf(listOf(4, 4), listOf(5, 3))
    private val PACKAGE_SHAPES = listOf(listOf(4, 4, 2), listOf(5, 3, 2), listOf(5, 4, 1))

    /** Empty list = the text cannot be an NDC (letters, wrong digit count, wrong segment count). */
    fun candidates(raw: String): List<Candidate> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (text.any { it !in '0'..'9' && it != '-' && !it.isWhitespace() }) return emptyList()

        val separated = text.any { it == '-' || it.isWhitespace() }
        val bases: List<List<String>> = if (separated) {
            val segments = text.split(Regex("[-\\s]+")).filter { it.isNotEmpty() }
            if (segments.size in 2..3) listOf(segments) else return emptyList()
        } else {
            when (text.length) {
                8 -> PRODUCT_SHAPES.map { split(text, it) }
                9 -> listOf(split(text, listOf(5, 4)))
                10 -> PACKAGE_SHAPES.map { split(text, it) }
                11 -> listOf(split(text, listOf(5, 4, 2)))
                else -> return emptyList()
            }
        }

        val out = LinkedHashSet<List<String>>()
        for (base in bases) {
            out += base
            out += unpadded(base)
        }
        return out.map { Candidate(it.joinToString("-"), isPackage = it.size == 3) }
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

    /** For each segment that starts with a padding zero, the form with that one zero removed, if it is a legal shape. */
    private fun unpadded(segments: List<String>): List<List<String>> {
        val shapes = if (segments.size == 3) PACKAGE_SHAPES else PRODUCT_SHAPES
        val result = ArrayList<List<String>>()
        for (i in segments.indices) {
            if (!segments[i].startsWith("0")) continue
            val stripped = segments.toMutableList().also { it[i] = it[i].substring(1) }
            if (shapes.any { shape -> shape == stripped.map { it.length } }) result += stripped
        }
        return result
    }
}
