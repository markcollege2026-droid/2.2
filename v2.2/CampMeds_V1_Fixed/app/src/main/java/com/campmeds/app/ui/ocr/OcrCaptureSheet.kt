package com.campmeds.app.ui.ocr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.campmeds.app.ocr.MlKitTextRecognizer
import com.campmeds.app.ocr.OcrOutcome
import com.campmeds.app.ocr.OcrPipeline
import com.campmeds.app.ocr.OcrScanType
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Temporary photos live only in the app's private cache directory. */
object OcrTempFiles {
    private const val DIR = "ocr"
    private const val STALE_MILLIS = 60L * 60L * 1000L

    fun directory(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    fun newFile(context: Context): File = File(directory(context), "ocr-${UUID.randomUUID()}.jpg")

    /** Removes leftovers from an interrupted run (process killed mid-capture). Files are never kept long. */
    fun sweepStale(context: Context) {
        val now = System.currentTimeMillis()
        directory(context).listFiles()?.forEach { file ->
            if (now - file.lastModified() > STALE_MILLIS) file.delete()
        }
    }
}

private sealed interface CaptureState {
    data object Ready : CaptureState
    data object Processing : CaptureState
    data class Problem(val message: String) : CaptureState
}

/**
 * Camera capture + on-device OCR as a bottom sheet. The caller receives only a [OcrOutcome.Recognized] result;
 * every other outcome is shown here with a way to try again. The temporary photo is always deleted by the
 * pipeline, and leaving the sheet cancels a recognition that is still running.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OcrCaptureSheet(
    scanType: OcrScanType,
    title: String,
    onDismiss: () -> Unit,
    onRecognized: (OcrOutcome.Recognized) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pipeline = remember { OcrPipeline(MlKitTextRecognizer.shared(context)) }
    var state by remember { mutableStateOf<CaptureState>(CaptureState.Ready) }
    var job by remember { mutableStateOf<Job?>(null) }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { OcrTempFiles.sweepStale(context) }

    ModalBottomSheet(
        onDismissRequest = {
            job?.cancel()
            onDismiss()
        }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)

            when {
                !hasPermission -> {
                    Text(
                        "Camera permission is needed to photograph the label. You can also type the details in by hand.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant camera permission")
                    }
                }

                state is CaptureState.Processing -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Reading the photo on this device…")
                    }
                    OutlinedButton(onClick = {
                        job?.cancel()
                        job = null
                        state = CaptureState.Ready
                    }) { Text("Cancel") }
                }

                state is CaptureState.Problem -> {
                    Text(
                        (state as CaptureState.Problem).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { state = CaptureState.Ready; attempt++ }) { Text("Try again") }
                        TextButton(onClick = onDismiss) { Text("Enter manually") }
                    }
                }

                else -> {
                    cameraError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } ?: Text(
                        "Hold the label flat and steady, in good light, then take the photo. The photo is read on this " +
                            "device and deleted afterwards.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    key(attempt) {
                        CapturePreview(
                            imageCapture = imageCapture,
                            onCameraError = { cameraError = it }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = cameraError == null,
                            onClick = {
                                val file = OcrTempFiles.newFile(context)
                                val executor = ContextCompat.getMainExecutor(context)
                                imageCapture.takePicture(
                                    ImageCapture.OutputFileOptions.Builder(file).build(),
                                    executor,
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                            state = CaptureState.Processing
                                            job = scope.launch {
                                                val outcome = pipeline.run(file, scanType.wireName)
                                                job = null
                                                when (outcome) {
                                                    is OcrOutcome.Recognized -> onRecognized(outcome)
                                                    is OcrOutcome.Empty -> state = CaptureState.Problem(
                                                        "No text was found in the photo. Try again with better light and focus."
                                                    )
                                                    is OcrOutcome.Unreadable -> state = CaptureState.Problem(
                                                        "The photo could not be read. Try again."
                                                    )
                                                    is OcrOutcome.Failed -> state = CaptureState.Problem(outcome.message)
                                                }
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            // Nothing was saved in a useful way; remove whatever the camera left behind.
                                            file.delete()
                                            state = CaptureState.Problem("The photo could not be taken. Try again.")
                                        }
                                    }
                                )
                            }
                        ) { Text("Take photo") }
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/** Live preview bound to the back camera together with the still-capture use case. */
@Composable
private fun CapturePreview(imageCapture: ImageCapture, onCameraError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = try {
                future.get()
            } catch (e: Exception) {
                onCameraError("The camera could not be started on this device.")
                return@Runnable
            }
            if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                onCameraError("This device has no back camera.")
                return@Runnable
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                onCameraError("The camera is not available right now.")
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxWidth().height(260.dp)
    )
}
