package com.campmeds.app.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device text recognition with the ML Kit bundled Latin model. The model is packaged inside the APK, so it
 * works without network access and nothing is downloaded at runtime.
 *
 * ML Kit's text API does not report a per-line confidence, so [OcrLine.confidence] is always null here.
 */
class MlKitTextRecognizer private constructor(private val appContext: Context) : TextRecognizerPort {

    private val client: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(imageFile: File): List<OcrLine> {
        val input = try {
            // fromFilePath applies the image's EXIF rotation, so photos taken in portrait read correctly.
            InputImage.fromFilePath(appContext, Uri.fromFile(imageFile))
        } catch (e: IOException) {
            throw UnreadableImageException(e)
        } catch (e: IllegalArgumentException) {
            throw UnreadableImageException(e)
        }

        val text = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
            client.process(input)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }

        // ML Kit returns blocks in reading order; lines inside each block are kept in that order too.
        return text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val box = line.boundingBox
                OcrLine(
                    text = line.text,
                    confidence = null,
                    boundingBox = box?.let { BoundingBox(it.left, it.top, it.right, it.bottom) }
                )
            }
        }
    }

    companion object {
        @Volatile
        private var instance: MlKitTextRecognizer? = null

        /** One recogniser for the whole app, created on first use and reused afterwards. */
        fun shared(context: Context): MlKitTextRecognizer =
            instance ?: synchronized(this) {
                instance ?: MlKitTextRecognizer(context.applicationContext).also { instance = it }
            }
    }
}
