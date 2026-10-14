package com.campmeds.app.ui.duetoday

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.campmeds.app.scanner.BarcodeScannerAnalyzer

/**
 * Live camera preview that reports the first decoded QR value via [onScanned].
 *
 * This view never decides what happens when scanning cannot work — that is the caller's job, because the
 * caller is the one holding the "verify manually" control and the identification/confirmation rules. What
 * this view does guarantee is that a failure is *reported* ([onError]) instead of leaving an empty grey box:
 * a device with no back camera, a camera that refuses to bind (in use by another app, or missing), and a
 * barcode reader that cannot start are all surfaced as text.
 */
@Composable
fun ScannerView(
    modifier: Modifier = Modifier,
    onScanned: (String) -> Unit,
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasCameraPermission) {
        Box(modifier.fillMaxSize()) {
            Text("Camera permission is required to scan.")
        }
        return
    }

    val previewView = remember { PreviewView(context).apply {
        // TextureView stays within Compose clipping; SurfaceView may draw above sibling controls.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
        clipChildren = true
        clipToPadding = true
    } }

    DisposableEffect(lifecycleOwner) {
        var disposed = false
        // Built inside the effect so a teardown that happens while the provider is still starting up cannot
        // touch a camera provider that no longer exists.
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            if (disposed) return@Runnable
            // get() throws if the camera provider failed to start (no camera hardware, camera service down):
            // that used to escape as an uncaught exception on the main thread.
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                onError("The camera could not be started on this device.")
                return@Runnable
            }

            if (!runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)) {
                onError("This device has no back camera to scan with.")
                return@Runnable
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        ContextCompat.getMainExecutor(context),
                        BarcodeScannerAnalyzer(
                            onDetected = onScanned,
                            onError = { onError("The barcode reader stopped working on this device.") }
                        )
                    )
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                // The camera is usually already open elsewhere, or the lifecycle is being torn down.
                onError("The camera is not available right now (${e.javaClass.simpleName}).")
            }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            if (cameraProviderFuture.isDone) runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize().clipToBounds())
}
