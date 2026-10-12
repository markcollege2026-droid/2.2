package com.campmeds.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Runs the real on-device recogniser (ML Kit bundled model) on a photo-like image generated at test time.
 * This is the check that the OCR path executes on the emulator, not only that it compiles.
 */
@RunWith(AndroidJUnit4::class)
class OcrOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun writeLabelPhoto(lines: List<String>): File {
        val bitmap = Bitmap.createBitmap(1200, 200 + lines.size * 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK; textSize = 84f; isAntiAlias = true }
        lines.forEachIndexed { i, text -> canvas.drawText(text, 60f, 150f + i * 120f, paint) }
        val file = File(OcrTempFiles.newFile(context).parentFile, "test-label-${System.nanoTime()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return file
    }

    @Test
    fun ndcPhotoIsReadAndTheCandidateIsFoundAndTheFileDeleted() = runBlocking {
        val photo = writeLabelPhoto(listOf("NDC 0069-2587-68", "Zyrtec 10 mg"))
        val outcome = OcrPipeline(MlKitTextRecognizer.shared(context)).run(photo, "ndc")
        assertFalse("temporary photo must be deleted", photo.exists())
        assertTrue("expected recognised text, got $outcome", outcome is OcrOutcome.Recognized)
        val recognised = outcome as OcrOutcome.Recognized
        assertTrue(recognised.ndcCandidates.any { it.hyphenated == "0069-2587-68" })
    }

    @Test
    fun instructionPhotoKeepsLinesInOrder() = runBlocking {
        val photo = writeLabelPhoto(listOf("Take one tablet", "Twice daily"))
        val outcome = OcrPipeline(MlKitTextRecognizer.shared(context)).run(photo, "instruction_document")
        assertFalse(photo.exists())
        val recognised = outcome as OcrOutcome.Recognized
        assertEquals(listOf("Take one tablet", "Twice daily"), recognised.lines.map { it.text })
    }

    @Test
    fun blankPhotoGivesEmptyOutcome() = runBlocking {
        val photo = writeLabelPhoto(emptyList())
        val outcome = OcrPipeline(MlKitTextRecognizer.shared(context)).run(photo, "ndc")
        assertFalse(photo.exists())
        assertTrue("expected Empty, got $outcome", outcome is OcrOutcome.Empty)
    }
}
