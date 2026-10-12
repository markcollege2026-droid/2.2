package com.campmeds.app.scanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * CameraX ImageAnalysis.Analyzer that feeds frames to ML Kit's on-device barcode scanner and
 * reports the first decoded QR value. Runs fully offline (spec section 7 — scanning must not
 * require network access).
 *
 * onDetected is invoked at most once per analyzer instance; the screen that owns this analyzer
 * is responsible for tearing the camera down once a code is found (see duetoday ScannerScreen).
 *
 * [onError] is invoked at most once when the barcode reader cannot be created or cannot process a frame.
 * Before, such a failure was indistinguishable from "no code in front of the camera": the scanner simply
 * never reported anything. The caller uses this to say that scanning is not working, while keeping its own
 * manual-verification path available.
 */
class BarcodeScannerAnalyzer(
    private val onDetected: (String) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) : androidx.camera.core.ImageAnalysis.Analyzer {

    /** Either the reader, or the reason it could not be created — never null. */
    private val scannerOrFailure: Result<com.google.mlkit.vision.barcode.BarcodeScanner> =
        runCatching { BarcodeScanning.getClient() }

    @Volatile private var hasFired = false
    @Volatile private var hasErrored = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || hasFired) {
            imageProxy.close()
            return
        }

        val scanner = scannerOrFailure.getOrNull()
        if (scanner == null) {
            imageProxy.close()
            reportError(scannerOrFailure.exceptionOrNull() ?: IllegalStateException("Barcode reader unavailable"))
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes: List<Barcode> ->
                val value = barcodes.firstOrNull { it.rawValue != null }?.rawValue
                if (value != null && !hasFired) {
                    hasFired = true
                    onDetected(value)
                }
            }
            .addOnFailureListener { error -> reportError(error) }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun reportError(error: Throwable) {
        if (hasErrored) return
        hasErrored = true
        onError(error)
    }
}
