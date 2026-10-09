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
 */
class BarcodeScannerAnalyzer(
    private val onDetected: (String) -> Unit
) : androidx.camera.core.ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()
    @Volatile private var hasFired = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || hasFired) {
            imageProxy.close()
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
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
