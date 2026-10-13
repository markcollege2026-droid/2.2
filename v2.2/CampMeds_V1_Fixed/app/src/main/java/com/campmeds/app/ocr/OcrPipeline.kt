package com.campmeds.app.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Thrown by a recogniser when the file is not a readable image. */
class UnreadableImageException(cause: Throwable? = null) : Exception("The image could not be read.", cause)

/**
 * The only thing the pipeline needs from an engine: text lines (in reading order) for an image file.
 * The production implementation is [MlKitTextRecognizer]; tests use a fake.
 */
interface TextRecognizerPort {
    /** @throws UnreadableImageException when the file is not a readable image. */
    suspend fun recognize(imageFile: File): List<OcrLine>
}

/**
 * Runs OCR on one temporary image and turns the lines into an [OcrOutcome].
 *
 * Ownership: the pipeline takes the file over and deletes it in a `finally`, on success, on failure, on an
 * unsupported scan type, and on cancellation. Nothing here logs recognised text or file contents.
 */
class OcrPipeline(private val recognizer: TextRecognizerPort) {

    /** Suspends until done. Rethrows [CancellationException] after the temporary file has been deleted. */
    suspend fun run(imageFile: File, scanTypeWireName: String): OcrOutcome {
        try {
            val scanType = OcrScanType.fromWireName(scanTypeWireName)
                ?: return OcrOutcome.Failed("This kind of scan is not supported.")
            val lines = withContext(Dispatchers.Default) { recognizer.recognize(imageFile) }
            return outcomeFor(scanType, lines)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnreadableImageException) {
            return OcrOutcome.Unreadable
        } catch (e: Exception) {
            // Deliberately generic: the exception text could contain file or image details.
            return OcrOutcome.Failed("Text recognition failed on this device. Try again or enter the details manually.")
        } finally {
            if (imageFile.exists() && !imageFile.delete()) {
                imageFile.deleteOnExit()
            }
        }
    }

    companion object {
        /** Builds the outcome from recognised lines. Blank lines are dropped; everything else is kept as-is. */
        fun outcomeFor(scanType: OcrScanType, rawLines: List<OcrLine>): OcrOutcome {
            val lines = rawLines.filter { it.text.isNotBlank() }
            if (lines.isEmpty()) return OcrOutcome.Empty(scanType)
            val candidates = if (scanType == OcrScanType.NDC) NdcCandidateExtractor.extract(lines) else emptyList()
            return OcrOutcome.Recognized(scanType, lines, candidates)
        }
    }
}
