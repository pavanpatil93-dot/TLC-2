package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SheetsRepository
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectVerificationSheet(
    project: UnderConstProject,
    activeUser: UserProfile,
    repository: SheetsRepository,
    onDismiss: () -> Unit,
    onProjectUpdated: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activeView by remember { mutableStateOf("Overview") } // "Overview", "Evidence", "Comparison", "Audit"
    var isFetching by remember { mutableStateOf(false) }
    var fetchStatusMessage by remember { mutableStateOf("Fetch completed") }
    var candidates by remember { mutableStateOf(repository.getStoredCandidates(project.id)) }
    var auditLogs by remember { mutableStateOf(repository.getAuditLogs(project.id)) }

    // Selection state for View C: map fieldKey -> Boolean (accepted)
    var acceptedFieldsMap = remember(candidates) {
        mutableStateOf(
            candidates.associate { c ->
                c.fieldKey to (c.category == FieldDiffCategory.NEW_INFO || c.category == FieldDiffCategory.NO_CHANGE)
            }
        )
    }

    var showSourceSettings by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    val newCount = candidates.count { it.category == FieldDiffCategory.NEW_INFO }
    val changedCount = candidates.count { it.category == FieldDiffCategory.CHANGED_INFO }
    val conflictCount = candidates.count { it.category == FieldDiffCategory.CONFLICT }

    val reraState = if (project.mahaReraNumber.isNotBlank() && !project.mahaReraNumber.contains("not verified", true)) {
        ReraVerificationState.OFFICIALLY_VERIFIED
    } else {
        ReraVerificationState.VERIFICATION_PENDING
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = PrimaryActionOrange)
                    Text(
                        text = "Project Verification Center",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                }
            }

            // Navigation Tabs for Views A, B, C, D
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = activeView == "Overview",
                    onClick = { activeView = "Overview" },
                    label = { Text("1. Overview") }
                )
                FilterChip(
                    selected = activeView == "Evidence",
                    onClick = { activeView = "Evidence" },
                    label = { Text("2. Source Evidence") }
                )
                FilterChip(
                    selected = activeView == "Comparison",
                    onClick = { activeView = "Comparison" },
                    label = { Text("3. Selective Review (${candidates.size})") }
                )
                FilterChip(
                    selected = activeView == "Audit",
                    onClick = { activeView = "Audit" },
                    label = { Text("4. Audit History (${auditLogs.size})") }
                )
            }

            Divider()

            // View Content
            Box(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)) {
                when (activeView) {
                    "Overview" -> {
                        VerificationOverviewView(
                            project = project,
                            reraState = reraState,
                            isFetching = isFetching,
                            fetchStatusMessage = fetchStatusMessage,
                            newCount = newCount,
                            changedCount = changedCount,
                            conflictCount = conflictCount,
                            showSourceSettings = showSourceSettings,
                            onToggleSourceSettings = { showSourceSettings = !showSourceSettings },
                            onFetchLatest = {
                                coroutineScope.launch {
                                    isFetching = true
                                    fetchStatusMessage = "Fetch in progress..."
                                    val res = repository.fetchProjectVerificationCandidates(project)
                                    isFetching = false
                                    if (res.isSuccess) {
                                        candidates = res.getOrDefault(emptyList())
                                        acceptedFieldsMap.value = candidates.associate { c ->
                                            c.fieldKey to (c.category == FieldDiffCategory.NEW_INFO || c.category == FieldDiffCategory.NO_CHANGE)
                                        }
                                        fetchStatusMessage = "Fetch completed"
                                    } else {
                                        fetchStatusMessage = "Network failure / Source unavailable"
                                    }
                                }
                            },
                            onOpenSource = { url ->
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                }
                            },
                            onGoToComparison = { activeView = "Comparison" }
                        )
                    }
                    "Evidence" -> {
                        SourceEvidenceInspectorView(candidates = candidates, onOpenSource = { url ->
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            }
                        })
                    }
                    "Comparison" -> {
                        FieldComparisonView(
                            candidates = candidates,
                            acceptedMap = acceptedFieldsMap.value,
                            onToggleField = { key, accepted ->
                                acceptedFieldsMap.value = acceptedFieldsMap.value.toMutableMap().apply { put(key, accepted) }
                            },
                            onReviewAndApply = { showConfirmDialog = true }
                        )
                    }
                    "Audit" -> {
                        AuditHistoryView(auditLogs = auditLogs)
                    }
                }
            }

            // Bottom actions
            if (activeView == "Comparison" && candidates.isNotEmpty()) {
                Button(
                    onClick = { showConfirmDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Review & Apply Selected Changes", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showConfirmDialog) {
        val selectedCount = acceptedFieldsMap.value.count { it.value }
        val rejectedCount = candidates.size - selectedCount
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            containerColor = SurfaceWhite,
            title = { Text("Confirm Approved Changes", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected Changes to Apply: $selectedCount", color = TextPrimary)
                    Text("Conflicts / Ignored: $rejectedCount", color = TextPrimary)
                    Text("Approved By: ${activeUser.username} (${activeUser.role.label})", color = TextPrimary)
                    Text("This action is logged atomically in the audit ledger.", fontSize = 12.sp, color = SecondaryText)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        val approved = candidates.filter { acceptedFieldsMap.value[it.fieldKey] == true }
                        val rejected = candidates.filter { acceptedFieldsMap.value[it.fieldKey] != true }
                        val success = repository.applyApprovedProjectChanges(project.id, approved, rejected, activeUser)
                        if (success) {
                            auditLogs = repository.getAuditLogs(project.id)
                            onProjectUpdated()
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Confirm & Apply", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun VerificationOverviewView(
    project: UnderConstProject,
    reraState: ReraVerificationState,
    isFetching: Boolean,
    fetchStatusMessage: String,
    newCount: Int,
    changedCount: Int,
    conflictCount: Int,
    showSourceSettings: Boolean,
    onToggleSourceSettings: () -> Unit,
    onFetchLatest: () -> Unit,
    onOpenSource: (String) -> Unit,
    onGoToComparison: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.projectName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                    Text("Location: ${project.location}", fontSize = 13.sp, color = SecondaryText)
                    Text("Builder: ${project.builderName}", fontSize = 13.sp, color = SecondaryText)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = BorderGrey)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Internal Status", fontSize = 11.sp, color = SecondaryText)
                            Text("Verified", fontWeight = FontWeight.Bold, color = StatusSuccessGreen)
                        }
                        Column {
                            Text("RERA Status", fontSize = 11.sp, color = SecondaryText)
                            Text(reraState.label, fontWeight = FontWeight.Bold, color = FocusOutlineColor)
                        }
                        Column {
                            Text("Last Checked", fontSize = 11.sp, color = SecondaryText)
                            Text(project.lastVerificationDate.ifBlank { "Never" }, fontSize = 12.sp, color = TextPrimary)
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onFetchLatest,
                    enabled = !isFetching,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = OnPrimaryActionColor, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fetching from Official Sources...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fetch Latest Information", fontWeight = FontWeight.Bold)
                    }
                }
                Text("Status: $fetchStatusMessage", fontSize = 12.sp, color = SecondaryText)
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleSourceSettings() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Source Settings & References", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Icon(if (showSourceSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                    }

                    if (showSourceSettings) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Configured Sources:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        OutlinedButton(
                            onClick = { onOpenSource("https://maharerait.maharashtra.gov.in/") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("MahaRERA Portal Search Reference", fontSize = 12.sp)
                        }
                        if (!project.brochureUrl.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = { onOpenSource(project.brochureUrl) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Official Project Brochure", fontSize = 12.sp)
                            }
                        } else {
                            Text("Source not available for automated fetch — manual verification required.", fontSize = 11.sp, color = Color.Red)
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Proposed Changes Summary", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                    Text("$newCount New · $changedCount Changed · $conflictCount Conflicts", fontSize = 13.sp, color = FocusOutlineColor)
                    Button(
                        onClick = onGoToComparison,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        )
                    ) {
                        Text("Review Changes", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SourceEvidenceInspectorView(candidates: List<FetchedFieldCandidate>, onOpenSource: (String) -> Unit) {
    if (candidates.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No fetched candidates available. Click 'Fetch Latest Information' in Overview.", color = SecondaryText)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(candidates) { candidate ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(candidate.fieldLabel, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                            Surface(color = SelectedSurfaceContainer, shape = RoundedCornerShape(4.dp)) {
                                Text(candidate.category.label, fontSize = 10.sp, color = FocusOutlineColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text("Fetched Value: ${candidate.fetchedValue.ifBlank { "Not Recorded" }}", fontSize = 13.sp, color = TextPrimary)
                        Text("Source: ${candidate.sourceName}", fontSize = 11.sp, color = SecondaryText)
                        Text("Fetched At: ${candidate.fetchedAt}", fontSize = 11.sp, color = SecondaryText)
                        if (candidate.sourceUrl.isNotBlank()) {
                            TextButton(onClick = { onOpenSource(candidate.sourceUrl) }, contentPadding = PaddingValues(0.dp)) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open Source Evidence", fontSize = 12.sp)
                            }
                        } else {
                            Text("Source not available — manual verification required.", fontSize = 11.sp, color = StatusDangerRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FieldComparisonView(
    candidates: List<FetchedFieldCandidate>,
    acceptedMap: Map<String, Boolean>,
    onToggleField: (String, Boolean) -> Unit,
    onReviewAndApply: () -> Unit
) {
    if (candidates.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No fields to compare. Run fetch in Overview first.", color = SecondaryText)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(candidates) { candidate ->
                val isAccepted = acceptedMap[candidate.fieldKey] == true
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isAccepted) PrimaryActionOrange else BorderGrey)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(candidate.fieldLabel, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isAccepted) "Accept" else "Keep Existing", fontSize = 12.sp, color = if (isAccepted) FocusOutlineColor else SecondaryText)
                                Checkbox(
                                    checked = isAccepted,
                                    onCheckedChange = { checked -> onToggleField(candidate.fieldKey, checked) }
                                )
                            }
                        }
                        HorizontalDivider(color = BorderGrey)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Saved Value", fontSize = 11.sp, color = SecondaryText)
                                Text(candidate.savedValue.ifBlank { "Not Recorded" }, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Fetched Value", fontSize = 11.sp, color = SecondaryText)
                                Text(candidate.fetchedValue.ifBlank { "Not Recorded" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FocusOutlineColor)
                            }
                        }
                        Surface(color = SurfaceMuted, shape = RoundedCornerShape(4.dp)) {
                            Text("Status: ${candidate.category.label}", fontSize = 11.sp, color = TextPrimary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AuditHistoryView(auditLogs: List<ProjectVerificationAuditEntry>) {
    if (auditLogs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No audit history entries found for this project.", color = SecondaryText)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(auditLogs) { entry ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(entry.fieldChanged, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                            Surface(
                                color = if (entry.decision == "Approved") SuccessGreenSubtle else ErrorRedSubtle,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(entry.decision, fontSize = 10.sp, color = if (entry.decision == "Approved") StatusSuccessGreen else StatusDangerRed, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text("Previous: ${entry.previousValue.ifBlank { "Blank" }} → New: ${entry.newValue.ifBlank { "Blank" }}", fontSize = 12.sp, color = TextPrimary)
                        Text("By: ${entry.employeeIdentity} at ${entry.timestamp}", fontSize = 11.sp, color = SecondaryText)
                        Text("Method: ${entry.verificationMethod} (${entry.sourceReference})", fontSize = 11.sp, color = SecondaryText)
                    }
                }
            }
        }
    }
}
