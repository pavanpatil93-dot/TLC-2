package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SheetsRepository
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ImportAndSyncScreen(
    repository: SheetsRepository,
    onBackToInventory: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val properties by repository.properties.collectAsState()
    val sheetsScriptUrl by repository.sheetsScriptUrl.collectAsState()
    val isLiveConnected by repository.isLiveConnected.collectAsState()
    val connectionStatus by repository.connectionStatus.collectAsState()
    val lastSyncTimestamp by repository.lastSyncTimestamp.collectAsState()
    val pendingSyncCount by repository.pendingSyncCount.collectAsState()
    val pendingSyncStatus by repository.pendingSyncStatus.collectAsState()
    val lastDiagnostic by repository.lastDiagnostic.collectAsState()
    val syncLogs by repository.syncLogs.collectAsState()

    var urlInput by remember(sheetsScriptUrl) { mutableStateOf(sheetsScriptUrl) }
    var isSyncing by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var syncFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var showAppsScriptCodeDialog by remember { mutableStateOf(false) }
    var safeImportRows by remember { mutableStateOf<List<com.example.model.ImportPreviewRow>>(emptyList()) }
    var safeImportFile by remember { mutableStateOf("") }
    var safeImportError by remember { mutableStateOf<String?>(null) }
    var confirmSafeImport by remember { mutableStateOf(false) }
    fun loadBundledStarterInventory() {
        try {
            val text = context.assets.open("starter_inventory_120.csv").bufferedReader().use { it.readText() }
            safeImportRows = repository.parseCsvOrTextData(text, "Bundled historical inventory")
            safeImportFile = "Built-in starter inventory (120 historical properties)"
            safeImportError = if (safeImportRows.size != 120) {
                "Expected 120 rows; parsed ${safeImportRows.size}. Review before importing."
            } else null
        } catch (e: Exception) {
            safeImportRows = emptyList()
            safeImportError = e.message ?: "Unable to load bundled inventory"
        }
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val fileText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                safeImportRows = repository.parseCsvOrTextData(fileText, "Tablet CSV Import")
                safeImportFile = uri.lastPathSegment ?: "Selected CSV"
                safeImportError = if (safeImportRows.isEmpty()) "No valid rows parsed. Check CSV headers and format." else null
            } catch (e: Exception) {
                safeImportRows = emptyList()
                safeImportError = e.message ?: "Unable to read CSV"
            }
        }
    }
    if (confirmSafeImport) {
        AlertDialog(
            onDismissRequest = { confirmSafeImport = false },
            title = { Text("Confirm safe import") },
            text = { Text("Add new property codes only. Existing records will not be overwritten. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    val stats = repository.importSafelySkippingExisting(safeImportRows)
                    syncFeedbackMessage = "Added ${stats.addedCount}, skipped ${stats.skippedCount}, failed ${stats.failedCount}"
                    safeImportRows = emptyList()
                    confirmSafeImport = false
                }) { Text("Import new only") }
            },
            dismissButton = { TextButton(onClick = { confirmSafeImport = false }) { Text("Cancel") } }
        )
    }

    val activePropertiesCount = remember(properties) {
        properties.count { it.archiveStatus != ArchiveStatus.ARCHIVED }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .padding(16.dp)
            .testTag("import_and_sync_screen")
            .verticalScroll(rememberScrollState())
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackToInventory,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BrandCharcoalSurface)
                    .border(0.8.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                    .size(38.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = BrandGold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "AUTHORITATIVE DATABASE",
                    color = BrandGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Google Sheet Synchronization Portal",
                    color = TextPrimaryDark,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SAFE CSV PROPERTY IMPORT", color = BrandGold, fontWeight = FontWeight.Bold)
                Text("Select a CSV file from your tablet. Preview duplicates before adding new property codes. Existing records stay unchanged.", color = TextPrimaryDark, fontSize = 12.sp)
                OutlinedButton(onClick = { loadBundledStarterInventory() }) {
                    Text("Preview 120 built-in properties")
                }
                Text("Historical records only. All availability is unconfirmed; confirm before sharing.", color = TextPrimaryDark, fontSize = 11.sp)
                OutlinedButton(onClick = { csvPicker.launch(arrayOf("text/*", "text/csv", "application/csv")) }) {
                    Text("Choose CSV file")
                }
                if (safeImportFile.isNotBlank()) Text(safeImportFile, color = TextPrimaryDark)
                safeImportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (safeImportRows.isNotEmpty()) {
                    val preview = repository.previewSafeImport(safeImportRows)
                    Text("Total: ${preview.total} | New: ${preview.readyToAdd} | Duplicates: ${preview.duplicates} | Invalid: ${preview.invalid}", color = TextPrimaryDark)
                    Button(
                        onClick = { confirmSafeImport = true },
                        enabled = preview.readyToAdd > 0 && safeImportError == null
                    ) { Text("Review and import new records") }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // GOOGLE APPS SCRIPT / SHEET URL CONFIGURATION
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "MASTER GOOGLE SHEET URL",
                    color = BrandGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("Paste Google Apps Script Web App URL or Google Sheet URL...", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            repository.setGoogleSheetUrl(urlInput)
                            Toast.makeText(context, "Google Sheet URL updated & saved!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandCharcoalSurface, contentColor = BrandGold),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save URL", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isTestingConnection = true
                                val diag = repository.testGoogleSheetConnection()
                                isTestingConnection = false
                                syncFeedbackMessage = diag.message
                                Toast.makeText(context, diag.message, Toast.LENGTH_LONG).show()
                            }
                        },
                        enabled = !isTestingConnection,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandCharcoalSurface, contentColor = TextPrimaryDark),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test Connection", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showAppsScriptCodeDialog = true },
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(38.dp)
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = BrandGold, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Google Apps Script Deployment Code", color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // STATUS BANNER CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isLiveConnected) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFEF4444).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isLiveConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (isLiveConnected) Color(0xFF10B981) else Color(0xFFEF4444),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isLiveConnected) "SHEET ENDPOINT CONFIGURED" else "LIVE ACCESS NOT VERIFIED",
                                color = if (isLiveConnected) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = connectionStatus,
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(BrandCharcoalSurface)
                            .border(0.8.dp, BrandGold.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$activePropertiesCount Active Items",
                            color = BrandGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BrandCharcoalSurface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DetailRow(label = "Master Spreadsheet", value = "MASTER_REAL_ESTATE_INVENTORY")
                    DetailRow(label = "Last Sync Timestamp", value = lastSyncTimestamp)
                    DetailRow(label = "Sync Polling Engine", value = "Checks while open and in background")
                    DetailRow(label = "Pending Changes", value = pendingSyncCount.toString())
                    Text(pendingSyncStatus, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isSyncing = true
                                try { repository.retryPendingWrites() }
                                finally { isSyncing = false }
                            }
                        },
                        enabled = pendingSyncCount > 0 && !isSyncing
                    ) { Text("Retry pending changes") }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // UNIFIED "SYNC NOW" ACTION BUTTON
        Button(
            onClick = {
                coroutineScope.launch {
                    isSyncing = true
                    syncFeedbackMessage = "Checking configured sheet and pending changes..."
                    val result = repository.syncWithGoogleSheetNow()
                    repository.retryPendingWrites()
                    syncFeedbackMessage = result + "\n\n" + repository.pendingSyncStatus.value
                    isSyncing = false
                    Toast.makeText(context, if (result.startsWith("Google Sheet Import Complete!")) "Sheet import completed; see pending write status" else "Sync did not complete; see details", Toast.LENGTH_LONG).show()
                }
            },
            enabled = !isSyncing,
            colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("unified_sync_now_btn")
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = BrandCharcoalDark,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Synchronizing Google Sheet...", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            } else {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("SYNC NOW", fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 0.5.sp)
            }
        }

        AnimatedVisibility(visible = syncFeedbackMessage != null) {
            Column {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BrandCharcoalSurface)
                        .border(1.dp, BrandGold.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = syncFeedbackMessage ?: "",
                        color = TextPrimaryDark,
                        fontSize = 11.5.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))



        Spacer(modifier = Modifier.height(16.dp))

        // RECENT SYNC ACTIVITY LOG
        Text(
            text = "RECENT SYNCHRONIZATION LOGS",
            color = BrandGold,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (syncLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(BrandCharcoalCard)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No sync events recorded yet. Click 'SYNC NOW' to perform your first synchronization.",
                    color = TextSecondaryDark,
                    fontSize = 11.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(syncLogs) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, BrandCharcoalBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isSuccess = entry.status == "SUCCESS"
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSuccess) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFEF4444).copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = entry.direction,
                                            color = if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Text(
                                        text = entry.timestamp,
                                        color = TextSecondaryDark,
                                        fontSize = 10.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = entry.details,
                                    color = TextPrimaryDark,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Google Apps Script Deployment Code Dialog
    if (showAppsScriptCodeDialog) {
        val code = repository.getGoogleAppsScriptTemplate()
        AlertDialog(
            onDismissRequest = { showAppsScriptCodeDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = BrandGold, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Google Apps Script Deployment Code", color = BrandGold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Follow these 3 quick steps to enable automatic 2-way write sync:",
                        color = TextPrimaryDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "1. Open Google Sheet > Extensions > Apps Script.\n2. Paste this code and click Save.\n3. Deploy > New Deployment > Web app > Restrict access to authorized users. Private inventory requires authenticated integration; do not deploy an anonymous endpoint.",
                        color = TextSecondaryDark,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Apps Script Code", code)
                        clipboard.setPrimaryClip(clip)
                        showAppsScriptCodeDialog = false
                        Toast.makeText(context, "Apps Script code copied to clipboard!", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy Code to Clipboard", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppsScriptCodeDialog = false }) {
                    Text("Close", color = TextSecondaryDark)
                }
            },
            containerColor = BrandCharcoalCard,
            shape = RoundedCornerShape(12.dp)
        )
    }
}


