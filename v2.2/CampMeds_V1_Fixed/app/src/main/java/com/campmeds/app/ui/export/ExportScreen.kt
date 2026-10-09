package com.campmeds.app.ui.export

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.campmeds.app.auth.Session
import com.campmeds.app.data.entity.Role
import com.campmeds.app.export.XlsxExporter
import com.campmeds.app.repository.CampMedsRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Screen 7 (spec section 4): generates one XLSX workbook, one worksheet per medication. Admin only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    repository: CampMedsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUser by Session.currentUser.collectAsState()
    val isAdmin = Session.hasAtLeast(currentUser, Role.ADMIN)

    var status by remember { mutableStateOf<String?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val fileName = remember {
        "campmeds_export_${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}.xlsx"
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri: Uri? ->
        if (uri == null) {
            status = "Export cancelled."
            return@rememberLauncherForActivityResult
        }
        status = "Exporting..."
        scope.launch {
            val data = repository.loadExportData()

            runCatching {
                XlsxExporter().export(context, uri, data)
            }.onSuccess {
                status = "Export complete: saved to the selected location."
            }.onFailure {
                status = "Export failed: ${it.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!isAdmin) {
                Text("Only Admin users can export data.")
            } else {
                Text(
                    "Exports every dose log to one XLSX workbook — one worksheet per medication, named " +
                        "\"FirstName LastName MedicationName\". History of deleted patients and medications is included."
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { createDocLauncher.launch(fileName) }) {
                    Text("Choose location & export")
                }
                status?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it)
                }
            }
        }
    }
}
