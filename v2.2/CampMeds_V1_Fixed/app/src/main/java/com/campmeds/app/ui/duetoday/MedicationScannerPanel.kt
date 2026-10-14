package com.campmeds.app.ui.duetoday

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.campmeds.app.ui.theme.CampAmber
import androidx.compose.ui.graphics.Color

/** Bounded preview in normal layout flow, never an overlay. The manual action is outside the scroll area.
 * Camera content is injected so all permission/error/live layouts can be tested without camera hardware.
 */
@Composable
internal fun MedicationScannerPanel(scanTarget: String, permissionGranted: Boolean, permanentlyDenied: Boolean,
    cameraProblem: String?, onManual: () -> Unit, onGrant: () -> Unit, onSettings: () -> Unit,
    onRetry: () -> Unit, showManual: Boolean = true, preview: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("scanner-panel")) {
        if (showManual) Button(onClick = onManual,
            colors = ButtonDefaults.buttonColors(containerColor = CampAmber, contentColor = Color(0xFF1B1B1B)),
            modifier = Modifier.fillMaxWidth().testTag("verify-manually")) { Text("Verify Manually") }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("scanner-scroll"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scan the QR code on $scanTarget. Manual verification still requires confirming the medication details before recording.",
                modifier = Modifier.testTag("scanner-instructions"))
            when {
                !permissionGranted -> {
                    Text("Camera permission is not granted. Verify Manually remains available.")
                    Button(onClick = onGrant) { Text("Grant camera permission") }
                    if (permanentlyDenied) TextButton(onClick = onSettings) { Text("Open app settings") }
                }
                cameraProblem != null -> {
                    Text(cameraProblem, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Try camera again") }
                }
                else -> {
                    Box(Modifier.fillMaxWidth().height(160.dp).clipToBounds()
                        .background(MaterialTheme.colorScheme.surfaceVariant).testTag("camera-preview")) { preview() }
                }
            }
        }
    }
}

/** Sheet-level manual control stays reachable even with very long prescription instructions above the preview. */
@Composable
internal fun MedicationSheetBody(onManual: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val height = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.85f
    Column(Modifier.fillMaxWidth().heightIn(max = height)) {
        if (onManual != null) Button(onClick = onManual,
            colors = ButtonDefaults.buttonColors(containerColor = CampAmber, contentColor = Color(0xFF1B1B1B)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("sticky-verify-manually")) { Text("Verify Manually") }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp).testTag("dose-sheet-scroll"),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
