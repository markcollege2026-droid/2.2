package com.campmeds.app.ocr

/** The kinds of document the OCR subsystem can read. The wire name is the value passed around as `scanType`. */
enum class OcrScanType(val wireName: String) {
    NDC("ndc"),
    INSTRUCTION_DOCUMENT("instruction_document");

    companion object {
        /** Null for anything unsupported, so callers must handle it instead of guessing a type. */
        fun fromWireName(value: String): OcrScanType? = entries.firstOrNull { it.wireName == value }
    }
}

/** A rectangle in image pixels, as reported by the recogniser. */
data class BoundingBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** One recognised line, in the order the recogniser returned it (its reading order). */
data class OcrLine(
    val text: String,
    /** Null when the engine does not report a confidence for the line. Never invented. */
    val confidence: Float?,
    val boundingBox: BoundingBox?
)

/** The NDC layouts the extractor recognises. [label] is how the layout is written on labels. */
enum class NdcFormat(val label: String) {
    FORMAT_4_4_2("4-4-2"),
    FORMAT_5_3_2("5-3-2"),
    FORMAT_5_4_1("5-4-1"),
    /** The 11-digit billing format (5-4-2). Labels usually show this form. */
    BILLING_5_4_2("5-4-2 (11-digit billing)");
}

/**
 * A number on the image that *looks like* an NDC. It is a candidate only: nothing here proves the number is a
 * listed product. Validation stays with the existing NDC lookup in the app.
 */
data class NdcCandidate(
    /** Exactly as recognised on the image. */
    val text: String,
    /** Hyphenated in [format]'s layout, e.g. "0069-2587-68". Built from the digits, never padded or corrected. */
    val hyphenated: String,
    /** All ten or eleven digits, no separators. */
    val digits: String,
    val format: NdcFormat,
    /** True when the digits had no separators, so this is one of several possible readings. */
    val fromUnseparatedDigits: Boolean,
    /** How many times the same reading appears on the image. */
    val occurrences: Int,
    /** Index of the first line it was found on. */
    val lineIndex: Int,
    val confidence: Float?,
    val boundingBox: BoundingBox?
)

/** The outcome of one OCR run. */
sealed interface OcrOutcome {
    /** Text was recognised. [lines] are in reading order; for NDC scans [ndcCandidates] is filled too. */
    data class Recognized(
        val scanType: OcrScanType,
        val lines: List<OcrLine>,
        val ndcCandidates: List<NdcCandidate>
    ) : OcrOutcome {
        /** Every recognised line joined with line breaks. Nothing is filtered or summarised. */
        val text: String get() = lines.joinToString("\n") { it.text }
    }

    /** The image was read but contained no text. */
    data class Empty(val scanType: OcrScanType) : OcrOutcome

    /** The file could not be decoded as an image. */
    data object Unreadable : OcrOutcome

    /** Something else failed (unsupported scan type, engine failure). The message is safe to show. */
    data class Failed(val message: String) : OcrOutcome
}
