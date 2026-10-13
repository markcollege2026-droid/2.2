package com.campmeds.app.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OcrPipelineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var image: File

    @Before
    fun createTempImage() {
        image = File(folder.root, "ocr-test.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    /** Every test that runs the pipeline must leave the temporary image deleted (the pipeline owns it). */
    private fun assertDeleted() = assertFalse("temporary image was not deleted", image.exists())

    private class FakeRecognizer(private val block: suspend (File) -> List<OcrLine>) : TextRecognizerPort {
        override suspend fun recognize(imageFile: File): List<OcrLine> = block(imageFile)
    }

    private fun recogniser(lines: List<OcrLine>) = FakeRecognizer { lines }
    private fun line(text: String, box: BoundingBox? = null) = OcrLine(text, confidence = null, boundingBox = box)

    @Test
    fun ndcScanReturnsLinesAndCandidatesAndDeletesTheImage() = runBlocking {
        val pipeline = OcrPipeline(recogniser(listOf(line("NDC 0069-2587-68", BoundingBox(1, 2, 3, 4)), line("Zyrtec 10 mg"))))
        val outcome = pipeline.run(image, "ndc") as OcrOutcome.Recognized
        assertEquals(OcrScanType.NDC, outcome.scanType)
        assertEquals(2, outcome.lines.size)
        assertEquals("0069-2587-68", outcome.ndcCandidates.single().hyphenated)
        assertEquals(BoundingBox(1, 2, 3, 4), outcome.ndcCandidates.single().boundingBox)
        assertDeleted()
    }

    @Test
    fun instructionScanKeepsEveryLineInOrderWithLineBreaksAndNoCandidates() = runBlocking {
        val lines = listOf(line("Take 1 tablet"), line("0069-2587-68 is the NDC"), line("  "), line("Twice daily"))
        val outcome = OcrPipeline(recogniser(lines)).run(image, "instruction_document") as OcrOutcome.Recognized
        assertTrue(outcome.ndcCandidates.isEmpty())
        // Blank lines are dropped; nothing else is filtered or rewritten.
        assertEquals("Take 1 tablet\n0069-2587-68 is the NDC\nTwice daily", outcome.text)
        assertDeleted()
    }

    @Test
    fun outcomeKeepsConfidenceWhenTheEngineReportsIt() {
        val outcome = OcrPipeline.outcomeFor(
            OcrScanType.INSTRUCTION_DOCUMENT,
            listOf(OcrLine("Dose", confidence = 0.75f, boundingBox = null))
        ) as OcrOutcome.Recognized
        assertEquals(0.75f, outcome.lines.single().confidence!!, 0.0001f)
    }

    @Test
    fun emptyImageGivesEmptyOutcome() = runBlocking {
        val outcome = OcrPipeline(recogniser(listOf(line("   ")))).run(image, "ndc")
        assertEquals(OcrOutcome.Empty(OcrScanType.NDC), outcome)
        assertDeleted()
    }

    @Test
    fun unreadableImageGivesUnreadableOutcome() = runBlocking {
        val failing = FakeRecognizer { throw UnreadableImageException() }
        assertEquals(OcrOutcome.Unreadable, OcrPipeline(failing).run(image, "ndc"))
        assertDeleted()
    }

    @Test
    fun unsupportedScanTypeIsRefusedAndTheImageIsStillDeleted() = runBlocking {
        val outcome = OcrPipeline(recogniser(listOf(line("x")))).run(image, "prescription_pad")
        assertTrue(outcome is OcrOutcome.Failed)
        assertDeleted()
    }

    @Test
    fun engineErrorIsReportedWithoutItsDetails() = runBlocking {
        val failing = FakeRecognizer { throw IllegalStateException("secret text: 0069-2587-68") }
        val outcome = OcrPipeline(failing).run(image, "ndc") as OcrOutcome.Failed
        assertFalse(outcome.message.contains("secret"))
        assertDeleted()
    }

    @Test
    fun cancellationIsRethrownAndTheImageIsDeleted() {
        val cancelled = FakeRecognizer { throw CancellationException("cancelled") }
        try {
            runBlocking { OcrPipeline(cancelled).run(image, "ndc") }
            fail("cancellation should propagate")
        } catch (e: CancellationException) {
            assertDeleted()
        }
    }

    @Test
    fun noNdcShapedNumberGivesNoCandidatesAndNoError() {
        val outcome = OcrPipeline.outcomeFor(OcrScanType.NDC, listOf(line("Two tablets")))
        assertNull((outcome as OcrOutcome.Recognized).ndcCandidates.firstOrNull())
    }

    @Test
    fun scanTypeWireNamesRoundTrip() {
        assertEquals(OcrScanType.NDC, OcrScanType.fromWireName("ndc"))
        assertEquals(OcrScanType.INSTRUCTION_DOCUMENT, OcrScanType.fromWireName("instruction_document"))
        assertNull(OcrScanType.fromWireName("NDC"))
    }
}
