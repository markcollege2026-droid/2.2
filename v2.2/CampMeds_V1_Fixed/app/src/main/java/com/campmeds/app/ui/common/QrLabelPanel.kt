package com.campmeds.app.ui.common

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.print.PrintHelper
import com.campmeds.app.scanner.QrLabel
import java.io.File

/**
 * Preview of a QR label (QR code + name text underneath) with Print / Save / Share.
 * Used for both medication labels (lines = patient name, medication name) and patient labels (lines = patient name).
 * Print, save and share all use the very same bitmap that is shown here.
 */
@Composable
fun QrLabelPanel(
    qrContent: String,
    lines: List<String>,
    fileBaseName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = remember(qrContent, lines) { QrLabel.render(qrContent, lines) }
    var status by remember { mutableStateOf<String?>(null) }
    val safeName = remember(fileBaseName) { fileBaseName.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "qr" } }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
        if (uri == null) {
            status = "Save cancelled."
            return@rememberLauncherForActivityResult
        }
        status = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                ?: error("could not open the destination")
            "Saved."
        }.getOrElse { "Save failed: ${it.message}" }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code: ${lines.joinToString(", ")}",
            modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                status = runCatching {
                    PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
                        .printBitmap("CampMeds QR - $safeName", bitmap)
                    null
                }.getOrElse { "Print failed: ${it.message}" }
            }) { Text("Print") }
            OutlinedButton(onClick = { saveLauncher.launch("$safeName.png") }) { Text("Save") }
            OutlinedButton(onClick = {
                status = runCatching {
                    val dir = File(context.cacheDir, "qr").apply { mkdirs() }
                    val file = File(dir, "$safeName.png")
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share QR code"))
                    null
                }.getOrElse { "Share failed: ${it.message}" }
            }) { Text("Share") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
