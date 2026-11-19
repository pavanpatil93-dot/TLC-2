package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.crm.*
import com.example.data.PropertiesLocalDatabase
import com.example.data.SheetsRepository
import com.example.data.UserSettingsManager
import com.example.loi.LoiDraftFactory
import com.example.model.AvailabilityStatus
import com.example.model.CalculationUtils
import com.example.model.PropertyItem
import com.example.model.TlcStandardOptions
import com.example.model.VerificationStatus
import com.example.model.isCommercial
import com.example.ui.components.*
import com.example.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class CrmRequirement(
    val id: String,
    val segment: String,
    val purpose: String,
    val description: String,
    val location: String,
    val budget: String,
    val areaRange: String = "",
    val areaBasis: String = "CARPET",
    val possessionDeadline: String = "",
    val stage: String = "New",
    val originalRequirementText: String = "",
    val recordVersion: Int = 1
)

internal data class CrmActivity(
    val id: String,
    val type: String,
    val timestampEpochMs: Long,
    val date: String,
    val notes: String,
    val nextFollowUp: String,
    val performedBy: String
)

private fun profileArray(context: Context, leadId: String, key: String): JSONArray =
    runCatching {
        JSONArray(
            com.example.data.auditedCrmPreferences(context, "crm_profile_v1", Context.MODE_PRIVATE)
                .getString("${leadId}_${key}", "[]") ?: "[]"
        )
    }.getOrDefault(JSONArray())

private fun persistProfileArray(context: Context, leadId: String, key: String, array: JSONArray) {
    com.example.data.auditedCrmPreferences(context, "crm_profile_v1", Context.MODE_PRIVATE).edit()
        .putString("${leadId}_${key}", array.toString()).apply()
}

internal fun loadActivities(context: Context, id: String): List<CrmActivity> =
    runCatching {
        val array = profileArray(context, id, "activities")
        val parsed = (0 until array.length()).map { i ->
            val j = array.getJSONObject(i)
            CrmActivity(
                id = j.optString("id"),
                type = j.optString("type"),
                timestampEpochMs = j.optLong("timestampEpochMs", System.currentTimeMillis()),
                date = j.optString("date"),
                notes = j.optString("notes"),
                nextFollowUp = j.optString("nextFollowUp"),
                performedBy = j.optString("performedBy", "Authorized Employee")
            )
        }
        parsed.sortedByDescending { it.timestampEpochMs }
    }.getOrDefault(emptyList())

internal fun persistActivities(context: Context, id: String, values: List<CrmActivity>) {
    val array = JSONArray()
    values.forEach {
        array.put(
            JSONObject()
                .put("id", it.id)
                .put("type", it.type)
                .put("timestampEpochMs", it.timestampEpochMs)
                .put("date", it.date)
                .put("notes", it.notes)
                .put("nextFollowUp", it.nextFollowUp)
                .put("performedBy", it.performedBy)
        )
    }
    persistProfileArray(context, id, "activities", array)
}

internal fun appendCrmActivity(
    context: Context,
    leadId: String,
    type: String,
    notes: String,
    nextFollowUp: String,
    performedBy: String
) {
    val existing = loadActivities(context, leadId)
    val nowMs = System.currentTimeMillis()
    val sdf = SimpleDateFormat("dd MMM yyyy '·' h:mm a", Locale.US)
    val dateStr = sdf.format(Date(nowMs))
    val newActivity = CrmActivity(
        id = UUID.randomUUID().toString(),
        type = type,
        timestampEpochMs = nowMs,
        date = dateStr,
        notes = notes,
        nextFollowUp = nextFollowUp,
        performedBy = performedBy.ifBlank { "Authorized Employee" }
    )
    val updated = (listOf(newActivity) + existing)
        .distinctBy { "${it.type}_${it.notes}_${it.date}" }
        .sortedByDescending { it.timestampEpochMs }
    persistActivities(context, leadId, updated)
}

private fun isSpecBlank(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.isEmpty() ||
        trimmed.equals("Not Mentioned", ignoreCase = true) ||
        trimmed.equals("General Enquiry", ignoreCase = true) ||
        trimmed.equals("TBD", ignoreCase = true) ||
        trimmed.equals("N/A", ignoreCase = true) ||
        trimmed.equals("NA", ignoreCase = true) ||
        trimmed.equals("None", ignoreCase = true) ||
        trimmed == "-"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrmClientProfile(
    leadId: String,
    clientName: String,
    inventory: List<PropertyItem> = emptyList(),
    onLeadUpdated: ((CrmLead) -> Unit)? = null,
    onDeleteLeadPermanently: ((CrmLead) -> Unit)? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { UserSettingsManager(context) }
    val userProfile by settings.userProfile.collectAsState()
    val loggedInEmployee = userProfile.username.ifBlank { "TLC-1 Advisory Desk" }
    val canPermanentDelete = userProfile.isAdminOrSuperAdmin

    var leadState by remember(leadId) { mutableStateOf(readCrmLeads(context).firstOrNull { it.id == leadId }) }
    val lead = leadState
    var projection by remember(lead) {
        mutableStateOf(lead?.let { CrmPipelineAndWorkloadEngine.getProjection(context, it) })
    }
    var stageHistory by remember(leadId, projection) {
        mutableStateOf(CrmPipelineAndWorkloadEngine.getStageHistory(context, leadId))
    }
    var assignmentHistory by remember(leadId, projection) {
        mutableStateOf(CrmPipelineAndWorkloadEngine.getAssignmentHistory(context, leadId))
    }
    var requirements by remember(leadId) {
        mutableStateOf(loadRequirements(context, leadId))
    }
    var activities by remember(leadId) {
        mutableStateOf(loadActivities(context, leadId))
    }
    var callLogRefreshTick by remember { mutableIntStateOf(0) }
    val matchedCalls = remember(lead, callLogRefreshTick) {
        CrmCallManager.findMatchingCallsForLead(
            context = context,
            phones = listOfNotNull(lead?.phone, lead?.alternatePhone),
            leadId = leadId
        )
    }

    var addingRequirement by remember { mutableStateOf(false) }
    var editingRequirement by remember { mutableStateOf<CrmRequirement?>(null) }
    var matchingRequirementLead by remember { mutableStateOf<CrmLead?>(null) }
    var addingActivity by remember { mutableStateOf(false) }
    var showAllActivities by remember { mutableStateOf(false) }
    var showMissingSpecs by remember { mutableStateOf(false) }
    var showEditLeadScreen by remember { mutableStateOf(false) }
    var showReopenDialog by remember { mutableStateOf(false) }
    var showCallHistorySheet by remember { mutableStateOf(false) }
    var showMessageTemplateSheet by remember { mutableStateOf(false) }
    var showChangeStageSheet by remember { mutableStateOf(false) }
    var showAssignmentSheet by remember { mutableStateOf(false) }
    var showStageMenu by remember { mutableStateOf(false) }
    var showAssigneeMenu by remember { mutableStateOf(false) }
    var ownerRefreshTick by remember { mutableIntStateOf(0) }
    var viewingOwnerProperty by remember { mutableStateOf<PropertyItem?>(null) }
    var showMatchingClientsSheet by remember { mutableStateOf(false) }
    var showLoiBuilderSheet by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var showPermanentDeleteDialog by remember { mutableStateOf(false) }

    fun executePermanentLeadDelete(targetLead: CrmLead) {
        if (onDeleteLeadPermanently != null) {
            onDeleteLeadPermanently(targetLead)
        } else {
            val repo = SheetsRepository(context.applicationContext, startForegroundSync = false)
            repo.permanentlyDeleteCrmLead(targetLead.id)
            Toast.makeText(context, "Deleted ${targetLead.displayClientTitle()} permanently", Toast.LENGTH_SHORT).show()
            onClose()
        }
    }

    val isOwner = lead?.isOwnerLead() == true
    val allLiveInventory = remember(context, inventory, ownerRefreshTick) {
        val local = PropertiesLocalDatabase.loadProperties(context)
        (local + inventory).distinctBy { it.id.ifBlank { it.propertyCode } }
    }
    val ownerLinkedProperties = remember(isOwner, lead, allLiveInventory) {
        if (!isOwner || lead == null) emptyList()
        else {
            val codeTokens = lead.matchedPropertyCodes
                .split(",", "|", ";", "\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val phoneLast10 = lead.phone.filter { it.isDigit() }.takeLast(10)
            allLiveInventory.filter { prop ->
                val matchesCode = codeTokens.any { tok ->
                    prop.propertyCode.equals(tok, ignoreCase = true) ||
                        prop.id.equals(tok, ignoreCase = true)
                }
                val propDigits = prop.ownerContact.orEmpty().filter { it.isDigit() }.takeLast(10)
                val matchesPhone = phoneLast10.length >= 10 && propDigits == phoneLast10
                matchesCode || matchesPhone
            }
        }
    }
    val syncedEmployees = remember(context) {
        SheetsRepository.getSyncedActiveEmployeeNames(context) + listOf("Unassigned")
    }

    val number = lead?.phone.orEmpty().filter { it.isDigit() }
    val dialable = number.length in 7..15 && !leadId.startsWith("demo-")
    var feedback by remember { mutableStateOf("") }

    val fmtTime = remember { SimpleDateFormat("dd MMM yyyy · hh:mm a", Locale.ENGLISH) }
    val resolvedTitle = lead?.displayClientTitle() ?: clientName

    BackHandler { onClose() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = BackgroundSoft) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // Header: Client Name (20.sp bold), small CRM-XXXXXX chip, Priority & Work State badges, Edit
                Surface(
                    color = SurfaceWhite,
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = resolvedTitle,
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                val badgeId = lead?.id ?: leadId
                                if (badgeId.startsWith("CRM-", ignoreCase = true)) {
                                    Surface(
                                        color = SurfaceMuted,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = badgeId,
                                            color = SecondaryText,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                val priorityLabel = lead?.priority.orEmpty().trim()
                                if (priorityLabel.isNotBlank()) {
                                    Surface(
                                        color = WarningAmberSubtle,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = priorityLabel,
                                            color = StatusWarningAmber,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                val workState = projection?.workState ?: CrmWorkState.ACTIVE
                                val isLiveState = workState == CrmWorkState.ACTIVE
                                Surface(
                                    color = if (isLiveState) Color(0xFFE7F7ED) else SurfaceMuted,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = if (isLiveState) "Live" else workState.label,
                                        color = if (isLiveState) StatusSuccessGreen else SecondaryText,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                                if (lead?.isOwnerLead() == true) {
                                    Surface(
                                        color = SelectedSurfaceContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "🏠 Property Owner",
                                            color = PrimaryActionOrange,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                } else if (lead?.isBrokerLead() == true) {
                                    Surface(
                                        color = SelectedSurfaceContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "🤝 Broker / CP",
                                            color = PrimaryActionOrange,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (lead != null) {
                            IconButton(
                                onClick = { showEditLeadScreen = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("profile_edit_lead_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = if (isOwner) "Edit Owner" else "Edit Client",
                                    tint = PrimaryActionOrange
                                )
                            }
                            Box {
                                IconButton(
                                    onClick = { profileMenuExpanded = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("crm_profile_more_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More Options",
                                        tint = TextPrimary
                                    )
                                }
                                DropdownMenu(
                                    expanded = profileMenuExpanded,
                                    onDismissRequest = { profileMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(if (isOwner) "Edit Owner" else "Edit Lead") },
                                        onClick = {
                                            profileMenuExpanded = false
                                            showEditLeadScreen = true
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = null,
                                                tint = PrimaryActionOrange
                                            )
                                        }
                                    )
                                    if (canPermanentDelete) {
                                        HorizontalDivider(color = BorderSubtle)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "Delete Permanently",
                                                    color = StatusDangerRed,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            },
                                            onClick = {
                                                profileMenuExpanded = false
                                                showPermanentDeleteDialog = true
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.DeleteForever,
                                                    contentDescription = null,
                                                    tint = StatusDangerRed
                                                )
                                            },
                                            modifier = Modifier.testTag("crm_profile_delete_permanently_menu_item")
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("profile_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = SecondaryText
                            )
                        }
                    }
                }

                // 1-Tap Stage & Assigned To Dropdown Pills directly below Header
                if (lead != null) {
                    val currentStage = projection?.stage ?: CrmPipelineStage.fromAnyString(lead.status)
                    val currentStageDisplay = if (isOwner) {
                        lead.status.ifBlank { TlcStandardOptions.ownerStages.first() }
                    } else {
                        currentStage.label
                    }
                    val todayIso = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
                    Surface(
                        color = SurfaceWhite,
                        border = BorderStroke(0.8.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Stage Pill
                            Box(modifier = Modifier.weight(1f)) {
                                Surface(
                                    color = SelectedSurfaceContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showStageMenu = true }
                                        .testTag("profile_stage_pill")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isOwner) "Owner Stage" else "Stage",
                                                color = SecondaryText,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = currentStageDisplay,
                                                color = PrimaryActionOrange,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Change Stage",
                                            tint = PrimaryActionOrange,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showStageMenu,
                                    onDismissRequest = { showStageMenu = false },
                                    containerColor = SurfaceWhite
                                ) {
                                    if (isOwner) {
                                        TlcStandardOptions.ownerStages.forEach { targetOwnerStage ->
                                            val selected = targetOwnerStage.equals(currentStageDisplay, ignoreCase = true)
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = targetOwnerStage,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (selected) PrimaryActionOrange else TextPrimary
                                                    )
                                                },
                                                onClick = {
                                                    showStageMenu = false
                                                    if (!selected) {
                                                        val updatedLead = lead.copy(status = targetOwnerStage)
                                                        val allLeads = readCrmLeads(context)
                                                        val nextLeads = if (allLeads.any { it.id == lead.id }) {
                                                            allLeads.map { if (it.id == lead.id) updatedLead else it }
                                                        } else {
                                                            listOf(updatedLead) + allLeads
                                                        }
                                                        saveCrmLeads(context, nextLeads)
                                                        leadState = readCrmLeads(context).firstOrNull { it.id == lead.id } ?: updatedLead
                                                        appendCrmActivity(
                                                            context = context,
                                                            leadId = leadId,
                                                            type = "Owner Stage → $targetOwnerStage",
                                                            notes = "Updated owner stage to $targetOwnerStage",
                                                            nextFollowUp = updatedLead.nextFollowUp,
                                                            performedBy = loggedInEmployee
                                                        )
                                                        activities = loadActivities(context, leadId)
                                                        onLeadUpdated?.invoke(leadState!!)
                                                        Toast.makeText(context, "Stage: $targetOwnerStage", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        CrmPipelineStage.entries.forEach { target ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = target.label,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (target == currentStage) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (target == currentStage) PrimaryActionOrange else TextPrimary
                                                    )
                                                },
                                                onClick = {
                                                    showStageMenu = false
                                                    if (target != currentStage) {
                                                        CrmPipelineAndWorkloadEngine.applyStageTransition(
                                                            context = context,
                                                            lead = lead,
                                                            targetStage = target,
                                                            reason = "Quick stage update to ${target.label}",
                                                            nextActionNote = projection?.nextActionNote?.takeIf { it.isNotBlank() }
                                                                ?: "Follow up on ${target.label}",
                                                            scheduleFollowUpDate = if (target == CrmPipelineStage.FOLLOW_UP) {
                                                                lead.nextFollowUp.ifBlank { todayIso }
                                                            } else {
                                                                lead.nextFollowUp
                                                            },
                                                            scheduleFollowUpTime = lead.nextFollowUpTime.ifBlank { lead.followUpTime },
                                                            detailsSharedConfirmed = (target == CrmPipelineStage.DETAILS_SHARED),
                                                            expectedVersion = projection?.recordVersion
                                                        ).onSuccess { (uLead, uProj) ->
                                                            leadState = uLead
                                                            projection = uProj
                                                            stageHistory = CrmPipelineAndWorkloadEngine.getStageHistory(context, leadId)
                                                            appendCrmActivity(
                                                                context = context,
                                                                leadId = leadId,
                                                                type = "Stage → ${target.label}",
                                                                notes = "Updated stage to ${target.label}",
                                                                nextFollowUp = uLead.nextFollowUp,
                                                                performedBy = loggedInEmployee
                                                            )
                                                            activities = loadActivities(context, leadId)
                                                            onLeadUpdated?.invoke(uLead)
                                                            Toast.makeText(context, "Stage: ${target.label}", Toast.LENGTH_SHORT).show()
                                                        }.onFailure { err ->
                                                            showChangeStageSheet = true
                                                            Toast.makeText(
                                                                context,
                                                                err.message ?: "Complete stage details",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        HorizontalDivider(color = BorderGrey)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "Detailed Stage Update...",
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = PrimaryActionOrange
                                                )
                                            },
                                            onClick = {
                                                showStageMenu = false
                                                showChangeStageSheet = true
                                            }
                                        )
                                    }
                                }
                            }

                            // Assigned To Pill
                            Box(modifier = Modifier.weight(1f)) {
                                Surface(
                                    color = BackgroundSoft,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, BorderGrey),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showAssigneeMenu = true }
                                        .testTag("profile_assignee_pill")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Assigned To",
                                                color = SecondaryText,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = lead.assignedTo.ifBlank { "Unassigned" },
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Change Assignee",
                                            tint = SecondaryText,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showAssigneeMenu,
                                    onDismissRequest = { showAssigneeMenu = false },
                                    containerColor = SurfaceWhite
                                ) {
                                    syncedEmployees.forEach { emp ->
                                        val isSelected = if (emp.equals("Unassigned", ignoreCase = true)) {
                                            lead.assignedTo.isBlank()
                                        } else {
                                            emp.equals(lead.assignedTo, ignoreCase = true)
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = emp,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) PrimaryActionOrange else TextPrimary
                                                )
                                            },
                                            onClick = {
                                                showAssigneeMenu = false
                                                if (!isSelected) {
                                                    val targetAssignee = if (emp.equals("Unassigned", ignoreCase = true)) "" else emp
                                                    val updatedLead = lead.copy(assignedTo = targetAssignee)
                                                    val allLeads = readCrmLeads(context)
                                                    val nextLeads = if (allLeads.any { it.id == lead.id }) {
                                                        allLeads.map { if (it.id == lead.id) updatedLead else it }
                                                    } else {
                                                        listOf(updatedLead) + allLeads
                                                    }
                                                    saveCrmLeads(context, nextLeads)
                                                    leadState = readCrmLeads(context).firstOrNull { it.id == lead.id } ?: updatedLead
                                                    appendCrmActivity(
                                                        context = context,
                                                        leadId = leadId,
                                                        type = "Assigned → ${targetAssignee.ifBlank { "Unassigned" }}",
                                                        notes = "Assigned ${if (isOwner) "owner" else "requirement"} to ${targetAssignee.ifBlank { "Unassigned" }}",
                                                        nextFollowUp = updatedLead.nextFollowUp,
                                                        performedBy = loggedInEmployee
                                                    )
                                                    activities = loadActivities(context, leadId)
                                                    onLeadUpdated?.invoke(leadState!!)
                                                    Toast.makeText(
                                                        context,
                                                        "Assigned to ${targetAssignee.ifBlank { "Unassigned" }}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        )
                                    }
                                    HorizontalDivider(color = BorderGrey)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Assignment & Handover...",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = PrimaryActionOrange
                                            )
                                        },
                                        onClick = {
                                            showAssigneeMenu = false
                                            showAssignmentSheet = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Batch 20: Team "Last Called By" History Banner
                    val latestClientCall = matchedCalls.firstOrNull()
                    if (latestClientCall != null) {
                        item(key = "client_last_called_by_item") {
                            LastCalledByBanner(
                                lastCall = latestClientCall,
                                testTag = "client_last_called_by_banner"
                            )
                        }
                    }

                    // 2-Column Spec Grid Card (Hides "Not Mentioned" fields by default)
                    item {
                        val formattedBudget = remember(lead) {
                            if (lead == null) "" else CalculationUtils.formatCrmBudgetDisplay(
                                rawBudget = lead.budget,
                                transactionOrRequirementHint = "${lead.requirementType} ${lead.requirement}",
                                minInr = lead.minBudgetInr.ifBlank { lead.minRentInr },
                                maxInr = lead.maxBudgetInr.ifBlank { lead.maxRentInr }
                            )
                        }
                        val divisionAndType = remember(lead) {
                            listOfNotNull(
                                lead?.segment?.takeIf { !isSpecBlank(it) },
                                lead?.propertyType?.takeIf { !isSpecBlank(it) }
                            ).joinToString(" • ")
                        }
                        val followUpDisplay = remember(lead) {
                            listOfNotNull(
                                lead?.nextFollowUp?.takeIf { it.isNotBlank() },
                                lead?.nextFollowUpTime?.takeIf { it.isNotBlank() } ?: lead?.followUpTime?.takeIf { it.isNotBlank() }
                            ).joinToString(" · ")
                        }
                        val carpetDisplay = remember(lead) {
                            val rawArea = lead?.carpetAreaRange.orEmpty().ifBlank { lead?.areaSqFt.orEmpty() }
                            if (isSpecBlank(rawArea)) "" else if (rawArea.contains("sq", ignoreCase = true)) rawArea else "$rawArea sq.ft"
                        }

                        val cleanedNotes = remember(lead?.notes) {
                            cleanActionNotesForDisplay(lead?.notes.orEmpty())
                        }
                        val allSpecs = remember(isOwner, lead, formattedBudget, divisionAndType, followUpDisplay, carpetDisplay, cleanedNotes) {
                            if (isOwner) {
                                listOf(
                                    "Owner Stage" to lead?.status.orEmpty(),
                                    "Expected Price / Rent" to formattedBudget,
                                    "Carpet Area" to carpetDisplay,
                                    "Follow-up" to followUpDisplay,
                                    "Mobile" to lead?.phone.orEmpty(),
                                    "Linked Code" to lead?.matchedPropertyCodes.orEmpty(),
                                    "Notes" to cleanedNotes
                                )
                            } else {
                                listOf(
                                    "Division & Type" to divisionAndType,
                                    "Requirement" to lead?.requirement.orEmpty(),
                                    "Preferred Locations" to lead?.location.orEmpty(),
                                    "Budget / Rent" to formattedBudget,
                                    "Carpet Area" to carpetDisplay,
                                    "Furnishing" to lead?.furnishing.orEmpty(),
                                    "Possession" to lead?.possessionTimeline.orEmpty(),
                                    "Next Follow-up" to followUpDisplay,
                                    "Mobile" to lead?.phone.orEmpty(),
                                    "Client Type" to (lead?.clientType.orEmpty().ifBlank { lead?.companyName.orEmpty() }),
                                    "Notes" to cleanedNotes
                                )
                            }
                        }
                        val populatedSpecs = remember(allSpecs) { allSpecs.filter { !isSpecBlank(it.second) } }
                        val missingSpecs = remember(allSpecs) { allSpecs.filter { isSpecBlank(it.second) } }
                        val displayedSpecs = if (showMissingSpecs) allSpecs else populatedSpecs

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                            border = BorderStroke(1.dp, BorderGrey),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                displayedSpecs.chunked(2).forEach { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        pair.forEach { (specLabel, specVal) ->
                                            val missing = isSpecBlank(specVal)
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (missing) SurfaceMuted.copy(alpha = 0.6f) else BackgroundSoft)
                                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                            ) {
                                                Text(
                                                    text = specLabel,
                                                    color = SecondaryText,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = if (missing) "Not Mentioned" else specVal,
                                                    color = if (missing) SecondaryText else TextPrimary,
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = if (specLabel == "Notes") 3 else 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        if (pair.size == 1) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }

                                if (projection?.holdReason?.isNotBlank() == true) {
                                    ProfileField("Hold Reason", "${projection?.holdReason} (Review: ${projection?.holdReviewDate.orEmpty()})")
                                }
                                if (projection?.closureSummary?.isNotBlank() == true) {
                                    ProfileField("Closed Won Summary", projection?.closureSummary.orEmpty())
                                }
                                if (projection?.closureReason?.isNotBlank() == true) {
                                    ProfileField("Closed Lost Reason", projection?.closureReason.orEmpty())
                                }

                                if (missingSpecs.isNotEmpty() && lead != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = { showMissingSpecs = !showMissingSpecs },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                            modifier = Modifier.testTag("toggle_missing_specs_btn")
                                        ) {
                                            Text(
                                                text = if (showMissingSpecs) "Hide Missing Fields" else "+ Edit / Fill Missing Details (${missingSpecs.size})",
                                                color = PrimaryActionOrange,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (showMissingSpecs) {
                                            TextButton(
                                                onClick = { showEditLeadScreen = true },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "Fill Now ›",
                                                    color = PrimaryActionOrange,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Owner Linked Property Cards (Owner Mode only)
                    if (isOwner && lead != null) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Linked Properties (${ownerLinkedProperties.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                if (ownerLinkedProperties.isEmpty()) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                        border = BorderStroke(1.dp, BorderGrey),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "No property linked yet — tap Edit to link or add",
                                                color = SecondaryText,
                                                fontSize = 12.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = { showEditLeadScreen = true }) {
                                                Text(
                                                    text = "Link Now ›",
                                                    color = PrimaryActionOrange,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        items(ownerLinkedProperties, key = { "owner_prop_${it.id}_${it.propertyCode}" }) { prop ->
                            val propPriceLabel = remember(prop) {
                                val amt = CalculationUtils.primaryAmountForProperty(prop)
                                val isRent = prop.tab.isRental ||
                                    prop.transactionType.contains("Lease", ignoreCase = true) ||
                                    prop.transactionType.contains("Rent", ignoreCase = true)
                                if (amt > 0.0) CalculationUtils.formatIndianCurrency(amt, isMonthly = isRent) else ""
                            }
                            val statusColor = when (prop.status) {
                                AvailabilityStatus.AVAILABLE -> StatusSuccessGreen
                                AvailabilityStatus.NOT_AVAILABLE, AvailabilityStatus.SOLD, AvailabilityStatus.LEASED, AvailabilityStatus.WITHDRAWN -> StatusDangerRed
                                else -> StatusWarningAmber
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("owner_linked_property_card_${prop.id}"),
                                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                border = BorderStroke(1.dp, BorderGrey),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (prop.propertyCode.isNotBlank()) {
                                                Surface(color = SelectedSurfaceContainer, shape = RoundedCornerShape(4.dp)) {
                                                    Text(
                                                        text = prop.propertyCode,
                                                        color = PrimaryActionOrange,
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Surface(color = statusColor.copy(alpha = 0.14f), shape = RoundedCornerShape(4.dp)) {
                                                Text(
                                                    text = prop.status.label,
                                                    color = statusColor,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (prop.verificationStatus == VerificationStatus.VERIFIED) {
                                            Text(
                                                text = "✓ Verified",
                                                color = StatusSuccessGreen,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Text(
                                        text = listOfNotNull(
                                            prop.projectName.takeIf { it.isNotBlank() },
                                            prop.unitNumber.takeIf { it.isNotBlank() }?.let { "Unit $it" }
                                        ).joinToString(" • ").ifBlank { prop.propertyCode.ifBlank { "Property" } },
                                        color = TextPrimary,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        text = listOfNotNull(
                                            prop.location.takeIf { it.isNotBlank() },
                                            propPriceLabel.takeIf { it.isNotBlank() },
                                            prop.carpetAreaSqFt.takeIf { it > 0.0 }?.let { "${CalculationUtils.formatNumber(it)} sq.ft" }
                                        ).joinToString(" • "),
                                        color = SecondaryText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewingOwnerProperty = prop },
                                            border = BorderStroke(1.dp, PrimaryActionOrange),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryActionOrange),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("owner_view_property_btn_${prop.id}")
                                        ) {
                                            Text(
                                                text = "View Property",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                                val todayReadable = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())
                                                val stored = PropertiesLocalDatabase.loadProperties(context).toMutableList()
                                                val updatedProp = prop.copy(
                                                    status = AvailabilityStatus.AVAILABLE,
                                                    verificationStatus = VerificationStatus.VERIFIED,
                                                    lastVerifiedDateClean = todayIso,
                                                    lastVerificationDate = todayReadable,
                                                    verifiedBy = loggedInEmployee,
                                                    ownerName = lead.name.ifBlank { prop.ownerName },
                                                    ownerContact = lead.phone.ifBlank { prop.ownerContact },
                                                    isSyncedToSheet = false
                                                )
                                                val idx = stored.indexOfFirst { it.id == prop.id || (prop.propertyCode.isNotBlank() && it.propertyCode.equals(prop.propertyCode, true)) }
                                                if (idx >= 0) stored[idx] = updatedProp else stored.add(0, updatedProp)
                                                PropertiesLocalDatabase.saveProperties(context, stored)
                                                appendCrmActivity(
                                                    context = context,
                                                    leadId = leadId,
                                                    type = "Availability Confirmed",
                                                    notes = "Confirmed ${prop.propertyCode.ifBlank { prop.projectName }} is Available",
                                                    nextFollowUp = lead.nextFollowUp,
                                                    performedBy = loggedInEmployee
                                                )
                                                activities = loadActivities(context, leadId)
                                                ownerRefreshTick++
                                                Toast.makeText(context, "Marked Available", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = StatusSuccessGreen,
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .weight(1.2f)
                                                .heightIn(min = 48.dp)
                                                .testTag("owner_confirm_available_btn_${prop.id}")
                                        ) {
                                            Text(
                                                text = "✅ Confirm Available",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Compact Action Strip: Find Properties / Find Matching Clients | + Requirement | + Note / Activity | Reopen (if closed)
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isOwner && lead != null) {
                                    OutlinedButton(
                                        onClick = { showMatchingClientsSheet = true },
                                        border = BorderStroke(1.dp, PrimaryActionOrange),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryActionOrange),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .weight(1.2f)
                                            .heightIn(min = 48.dp)
                                            .testTag("owner_find_matching_clients_btn")
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "Find Matching Clients",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    if (lead != null && inventory.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = { matchingRequirementLead = lead },
                                            border = BorderStroke(1.dp, PrimaryActionOrange),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryActionOrange),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Find Properties", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { editingRequirement = null; addingRequirement = true },
                                        border = BorderStroke(1.dp, PrimaryActionOrange),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryActionOrange),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Text("+ Requirement", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { addingActivity = true },
                                    border = BorderStroke(1.dp, BorderGrey),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text("+ Note / Activity", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                }
                            }
                            if (!isOwner && projection?.stage?.isClosed == true) {
                                Button(
                                    onClick = { showReopenDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = OnPrimaryActionColor
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("reopen_requirement_btn")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Reopen Requirement", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            val canGenerateLoi = remember(lead, projection) {
                                if (lead == null) false
                                else {
                                    val stage = projection?.stage
                                    val st = lead.status
                                    stage == CrmPipelineStage.NEGOTIATION ||
                                        stage == CrmPipelineStage.CLOSED_WON ||
                                        st.contains("Negotiation", ignoreCase = true) ||
                                        st.contains("LOI", ignoreCase = true) ||
                                        st.contains("Term Sheet", ignoreCase = true) ||
                                        st.contains("Token", ignoreCase = true)
                                }
                            }
                            if (canGenerateLoi) {
                                Button(
                                    onClick = { showLoiBuilderSheet = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = OnPrimaryActionColor
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("profile_generate_loi_pdf_btn")
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Generate LOI PDF", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Additional Client Requirements
                    if (requirements.isNotEmpty()) {
                        item {
                            Text(
                                text = "Additional Client Requirements (${requirements.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        items(requirements, key = { "req_${it.id}" }) { req ->
                            val reqBudget = remember(req.budget, req.purpose) {
                                CalculationUtils.formatCrmBudgetDisplay(req.budget, req.purpose)
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                border = BorderStroke(1.dp, BorderGrey)
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${req.segment} · ${req.purpose}",
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            fontSize = 14.sp
                                        )
                                        Surface(
                                            color = SelectedSurfaceContainer,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = req.stage.ifBlank { "New" },
                                                color = PrimaryActionOrange,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    ProfileField("Requirement", req.description)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(Modifier.weight(1f)) { ProfileField("Location", req.location) }
                                        Box(Modifier.weight(1f)) { ProfileField("Budget", reqBudget.ifBlank { req.budget }) }
                                    }
                                    if (req.areaRange.isNotBlank()) {
                                        ProfileField("Area (${req.areaBasis})", req.areaRange)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                editingRequirement = req
                                                addingRequirement = true
                                            },
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                        ) {
                                            Text("Edit Requirement", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                        if (lead != null) {
                                            Button(
                                                onClick = {
                                                    matchingRequirementLead = lead.copy(
                                                        segment = req.segment,
                                                        requirement = "${req.purpose} ${req.description}",
                                                        location = req.location,
                                                        budget = req.budget,
                                                        carpetAreaRange = req.areaRange,
                                                        possessionTimeline = req.possessionDeadline
                                                    )
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = PrimaryActionOrange,
                                                    contentColor = OnPrimaryActionColor
                                                ),
                                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                            ) {
                                                Text("Find Properties", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Pipeline Stage History Timeline
                    if (stageHistory.isNotEmpty()) {
                        item {
                            Text(
                                text = "Pipeline Stage Progression (${stageHistory.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        items(stageHistory, key = { "st_hist_${it.eventId}" }) { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                border = BorderStroke(1.dp, BorderGrey)
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${entry.fromStage?.label ?: "Initial"} → ${entry.toStage.label}",
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryActionOrange,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = fmtTime.format(Date(entry.occurredAt)),
                                            fontSize = 11.sp,
                                            color = SecondaryText
                                        )
                                    }
                                    Text(
                                        text = "Reason: ${entry.reason.ifBlank { "Stage updated" }}",
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "By: ${entry.actorEmployeeId.ifBlank { "Authorized Employee" }}",
                                        fontSize = 11.sp,
                                        color = SecondaryText
                                    )
                                }
                            }
                        }
                    }

                    // Assignment & Reassignment History Timeline
                    if (assignmentHistory.isNotEmpty()) {
                        item {
                            Text(
                                text = "Assignment History (${assignmentHistory.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        items(assignmentHistory, key = { "as_hist_${it.assignmentEventId}" }) { ev ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                border = BorderStroke(1.dp, BorderGrey)
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${ev.fromEmployeeId.ifBlank { "Unassigned" }} → ${ev.toEmployeeId}",
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = fmtTime.format(Date(ev.occurredAt)),
                                            fontSize = 11.sp,
                                            color = SecondaryText
                                        )
                                    }
                                    Text(
                                        text = "Reason: ${ev.reason.ifBlank { "Assigned" }}",
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "By: ${ev.actorEmployeeId} • Transferred Tasks: ${ev.transferredTaskIds.size}",
                                        fontSize = 11.sp,
                                        color = SecondaryText
                                    )
                                }
                            }
                        }
                    }

                    // Chronological Client Activity History
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Client Activity History (${activities.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            if (activities.size > 5) {
                                TextButton(onClick = { showAllActivities = !showAllActivities }) {
                                    Text(
                                        text = if (showAllActivities) "Show Less" else "View Earlier (${activities.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryActionOrange
                                    )
                                }
                            }
                        }
                    }

                    val displayedActivities = if (showAllActivities) activities else activities.take(5)
                    if (displayedActivities.isEmpty()) {
                        item {
                            Text("No activities recorded yet.", color = SecondaryText, fontSize = 12.sp)
                        }
                    }
                    items(displayedActivities, key = { "act_${it.id}" }) { activity ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                            border = BorderStroke(1.dp, BorderGrey)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(activity.type, fontWeight = FontWeight.Bold, color = PrimaryActionOrange, fontSize = 13.sp)
                                    Text(activity.date, fontSize = 11.sp, color = SecondaryText)
                                }
                                Text(activity.notes, fontSize = 12.5.sp, color = TextPrimary)
                                if (activity.nextFollowUp.isNotBlank()) {
                                    Text("Next Follow-up: ${activity.nextFollowUp}", fontSize = 11.sp, color = StatusCheckAvailability, fontWeight = FontWeight.Bold)
                                }
                                Text("By: ${activity.performedBy}", fontSize = 10.sp, color = SecondaryText)
                            }
                        }
                    }
                }

                // Single Pinned Last Call Log Box (Pinned Right Above Bottom Bar)
                val latestCall = matchedCalls.firstOrNull()
                val sheetLastContactSummary = remember(lead) {
                    if (lead == null) "" else listOfNotNull(
                        lead.lastContactDate.takeIf { it.isNotBlank() },
                        lead.lastContactMode.takeIf { it.isNotBlank() },
                        lead.lastContactOutcome.takeIf { it.isNotBlank() }
                    ).joinToString(" • ")
                }
                val lastCallLine = when {
                    latestCall != null -> {
                        val typeLabel = when (latestCall.callType) {
                            CallType.INCOMING -> "Incoming"
                            CallType.OUTGOING -> "Outgoing"
                            CallType.MISSED -> "Missed"
                        }
                        val dur = CrmCallManager.formatDuration(latestCall.durationSeconds)
                        "Last Call: ${CrmCallManager.formatTimestamp(latestCall.timestamp)} • $typeLabel ($dur)"
                    }
                    sheetLastContactSummary.isNotBlank() -> "Last Contact: $sheetLastContactSummary"
                    else -> "Last Call: No calls logged yet"
                }

                Surface(
                    color = SurfaceWhite,
                    border = BorderStroke(1.dp, BorderGrey),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { showCallHistorySheet = true }
                        .testTag("pinned_last_call_box")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Last Call",
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = lastCallLine,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "All Calls (${matchedCalls.size}) ›",
                            color = PrimaryActionOrange,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (feedback.isNotBlank()) {
                    Text(
                        text = feedback,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        color = StatusNotAvailable,
                        fontSize = 11.5.sp
                    )
                }

                // Pinned Icon Bottom Bar: [ 📞 ] [ 💬 ] [ ✉️ Message Templates ]
                Surface(
                    color = SurfaceWhite,
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Call Icon Button
                        IconButton(
                            enabled = dialable,
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
                                }.onFailure { feedback = "No calling app available" }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (dialable) StatusSuccessGreen.copy(alpha = 0.14f) else SurfaceMuted)
                                .testTag("profile_call_icon_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Call Client",
                                tint = if (dialable) StatusSuccessGreen else SecondaryText,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 2. WhatsApp Icon Button
                        IconButton(
                            enabled = dialable,
                            onClick = {
                                val waPhone = if (number.length == 10) "91$number" else number
                                runCatching {
                                    CrmCallManager.openWhatsAppChat(context, waPhone, "")
                                }.onFailure { feedback = "No WhatsApp handler available" }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (dialable) Color(0xFFE7F9EE) else SurfaceMuted)
                                .testTag("profile_whatsapp_icon_btn")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                                contentDescription = "WhatsApp Client",
                                tint = if (dialable) Color.Unspecified else SecondaryText,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // 3. Message Template Button (Non-wrapping)
                        Button(
                            onClick = { showMessageTemplateSheet = true },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryActionOrange,
                                contentColor = OnPrimaryActionColor
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("profile_message_templates_btn")
                        ) {
                            Icon(Icons.Default.MailOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Message Template",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditLeadScreen && lead != null) {
        AddEditLeadScreen(
            initial = lead,
            onDismiss = { showEditLeadScreen = false },
            onDeletePermanently = if (canPermanentDelete) { targetLead ->
                showEditLeadScreen = false
                executePermanentLeadDelete(targetLead)
            } else null,
            onSave = { editedLead, verifyLive ->
                val allLeads = readCrmLeads(context)
                val updatedList = if (allLeads.any { it.id == editedLead.id }) {
                    allLeads.map { if (it.id == editedLead.id) editedLead else it }
                } else {
                    listOf(editedLead) + allLeads
                }
                saveCrmLeads(context, updatedList)
                val persisted = readCrmLeads(context).firstOrNull { it.id == editedLead.id } ?: editedLead
                if (verifyLive) {
                    if (persisted.isOwnerLead()) {
                        val verifiedOwner = persisted.copy(verificationStatus = "Verified Live")
                        saveCrmLeads(context, readCrmLeads(context).map { if (it.id == verifiedOwner.id) verifiedOwner else it })
                        leadState = verifiedOwner
                        onLeadUpdated?.invoke(verifiedOwner)
                    } else {
                        CrmPipelineAndWorkloadEngine.verifyAndActivateLead(context, persisted)
                            .onSuccess {
                                val activated = readCrmLeads(context).firstOrNull { it.id == persisted.id } ?: persisted
                                leadState = activated.copy(verificationStatus = "Verified Live")
                                saveCrmLeads(context, readCrmLeads(context).map { if (it.id == activated.id) leadState!! else it })
                                onLeadUpdated?.invoke(leadState!!)
                            }
                            .onFailure {
                                leadState = persisted
                                onLeadUpdated?.invoke(persisted)
                            }
                    }
                } else {
                    leadState = persisted
                    onLeadUpdated?.invoke(persisted)
                }
                appendCrmActivity(
                    context = context,
                    leadId = leadId,
                    type = if (persisted.isOwnerLead()) "Owner Updated" else "Requirement Updated",
                    notes = "Details updated for ${persisted.displayClientTitle()}",
                    nextFollowUp = persisted.nextFollowUp,
                    performedBy = loggedInEmployee
                )
                activities = loadActivities(context, leadId)
                ownerRefreshTick++
                showEditLeadScreen = false
                Toast.makeText(context, if (persisted.isOwnerLead()) "Owner updated" else "Client updated", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (addingRequirement) {
        RequirementEditor(
            initial = editingRequirement,
            onDismiss = {
                addingRequirement = false
                editingRequirement = null
            }
        ) { req ->
            requirements = if (editingRequirement == null) {
                listOf(req) + requirements
            } else {
                requirements.map { if (it.id == req.id) req else it }
            }
            persistRequirements(context, leadId, requirements)
            appendCrmActivity(
                context = context,
                leadId = leadId,
                type = if (editingRequirement == null) "Requirement Added" else "Requirement Updated",
                notes = "${req.description} (${req.segment} · ${req.purpose})",
                nextFollowUp = "",
                performedBy = lead?.assignedTo.orEmpty()
            )
            activities = loadActivities(context, leadId)
            addingRequirement = false
            editingRequirement = null
        }
    }

    if (addingActivity) {
        ActivityEditor(onDismiss = { addingActivity = false }) { activity ->
            activities = listOf(activity) + activities
            persistActivities(context, leadId, activities)
            appendCrmActivity(context, leadId, activity.type, activity.notes, activity.nextFollowUp, lead?.assignedTo.orEmpty())
            activities = loadActivities(context, leadId)
            addingActivity = false
        }
    }

    if (showReopenDialog && lead != null) {
        ReopenRequirementDialog(
            lead = lead,
            onDismiss = { showReopenDialog = false },
            onReopened = { updatedLead, updatedProj ->
                leadState = updatedLead
                projection = updatedProj
                stageHistory = CrmPipelineAndWorkloadEngine.getStageHistory(context, leadId)
                appendCrmActivity(
                    context = context,
                    leadId = leadId,
                    type = "Requirement Reopened",
                    notes = "Reopened to ${updatedProj.stage.label}: ${updatedProj.stageChangeReason}",
                    nextFollowUp = updatedLead.nextFollowUp,
                    performedBy = updatedProj.updatedBy
                )
                activities = loadActivities(context, leadId)
                onLeadUpdated?.invoke(updatedLead)
                showReopenDialog = false
                Toast.makeText(context, "Requirement reopened to ${updatedProj.stage.label}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    matchingRequirementLead?.let { targetLead ->
        CrmInventoryMatchesDialog(
            lead = targetLead,
            inventory = inventory,
            onClose = { matchingRequirementLead = null }
        )
    }

    if (showCallHistorySheet && lead != null) {
        LeadCallHistoryModalSheet(
            lead = lead,
            calls = matchedCalls,
            performer = loggedInEmployee,
            onDismiss = { showCallHistorySheet = false },
            onOutcomeLogged = { outcome, note ->
                CrmCallManager.recordLeadCallOutcome(
                    context = context,
                    leadId = lead.id,
                    leadName = lead.displayClientTitle(),
                    phone = lead.phone,
                    outcome = outcome,
                    note = note,
                    performer = loggedInEmployee
                )
                val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val updatedLead = lead.copy(
                    lastContactDate = todayIso,
                    lastContactMode = "Phone Call",
                    lastContactOutcome = outcome
                )
                val allLeads = readCrmLeads(context)
                saveCrmLeads(context, allLeads.map { if (it.id == updatedLead.id) updatedLead else it })
                leadState = readCrmLeads(context).firstOrNull { it.id == updatedLead.id } ?: updatedLead
                appendCrmActivity(
                    context = context,
                    leadId = lead.id,
                    type = "Call: $outcome",
                    notes = note.ifBlank { outcome },
                    nextFollowUp = updatedLead.nextFollowUp,
                    performedBy = loggedInEmployee
                )
                activities = loadActivities(context, leadId)
                callLogRefreshTick++
                onLeadUpdated?.invoke(leadState!!)
                Toast.makeText(context, "Call outcome logged", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showMessageTemplateSheet && lead != null) {
        LeadMessageTemplateSheet(
            lead = lead,
            employeePoc = loggedInEmployee,
            onDismiss = { showMessageTemplateSheet = false },
            onSent = { channel, preview ->
                appendCrmActivity(
                    context = context,
                    leadId = lead.id,
                    type = "$channel Template Sent",
                    notes = preview.take(160),
                    nextFollowUp = lead.nextFollowUp,
                    performedBy = loggedInEmployee
                )
                activities = loadActivities(context, leadId)
                showMessageTemplateSheet = false
            }
        )
    }

    if (showChangeStageSheet && lead != null) {
        CrmChangeStageBottomSheet(
            lead = lead,
            onDismiss = { showChangeStageSheet = false },
            onSaved = { updatedLead ->
                leadState = updatedLead
                projection = CrmPipelineAndWorkloadEngine.getProjection(context, updatedLead)
                stageHistory = CrmPipelineAndWorkloadEngine.getStageHistory(context, leadId)
                activities = loadActivities(context, leadId)
                onLeadUpdated?.invoke(updatedLead)
                showChangeStageSheet = false
            }
        )
    }

    if (showAssignmentSheet && lead != null) {
        CrmAssignmentBottomSheet(
            lead = lead,
            onDismiss = { showAssignmentSheet = false },
            onSaved = { updatedLead ->
                leadState = updatedLead
                projection = CrmPipelineAndWorkloadEngine.getProjection(context, updatedLead)
                assignmentHistory = CrmPipelineAndWorkloadEngine.getAssignmentHistory(context, leadId)
                activities = loadActivities(context, leadId)
                onLeadUpdated?.invoke(updatedLead)
                showAssignmentSheet = false
            }
        )
    }

    viewingOwnerProperty?.let { targetProp ->
        Dialog(
            onDismissRequest = { viewingOwnerProperty = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            PropertyDetailScreen(
                property = targetProp,
                allProperties = allLiveInventory,
                isInternalMode = true,
                isFavorite = false,
                isInComparison = false,
                onBack = { viewingOwnerProperty = null },
                onToggleFavorite = {},
                onToggleCompare = {},
                onShareWhatsApp = {
                    val amt = CalculationUtils.primaryAmountForProperty(targetProp)
                    val isRent = targetProp.tab.isRental ||
                        targetProp.transactionType.contains("Lease", ignoreCase = true) ||
                        targetProp.transactionType.contains("Rent", ignoreCase = true)
                    val priceStr = if (amt > 0.0) CalculationUtils.formatIndianCurrency(amt, isMonthly = isRent) else "Price Pending"
                    val shareText = "${targetProp.projectName} (${targetProp.propertyCode}) • ${targetProp.location} • $priceStr"
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    runCatching { context.startActivity(Intent.createChooser(sendIntent, "Share Property")) }
                },
                onVerifyProperty = {
                    val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val todayReadable = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())
                    val nextProp = targetProp.copy(
                        verificationStatus = VerificationStatus.VERIFIED,
                        lastVerifiedDateClean = todayIso,
                        lastVerificationDate = todayReadable,
                        verifiedBy = loggedInEmployee,
                        isSyncedToSheet = false
                    )
                    val list = PropertiesLocalDatabase.loadProperties(context).toMutableList()
                    val idx = list.indexOfFirst { it.id == targetProp.id }
                    if (idx >= 0) list[idx] = nextProp else list.add(0, nextProp)
                    PropertiesLocalDatabase.saveProperties(context, list)
                    viewingOwnerProperty = nextProp
                    ownerRefreshTick++
                    Toast.makeText(context, "Property verified", Toast.LENGTH_SHORT).show()
                },
                onStatusChange = { newStatus ->
                    val nextProp = targetProp.copy(status = newStatus, isSyncedToSheet = false)
                    val list = PropertiesLocalDatabase.loadProperties(context).toMutableList()
                    val idx = list.indexOfFirst { it.id == targetProp.id }
                    if (idx >= 0) list[idx] = nextProp else list.add(0, nextProp)
                    PropertiesLocalDatabase.saveProperties(context, list)
                    viewingOwnerProperty = nextProp
                    ownerRefreshTick++
                },
                onPropertySelect = { viewingOwnerProperty = it },
                onDeleteProperty = if (canPermanentDelete) {
                    {
                        val repo = SheetsRepository(context.applicationContext, startForegroundSync = false)
                        val deleted = repo.permanentlyDeleteProperty(targetProp)
                        if (deleted) {
                            viewingOwnerProperty = null
                            ownerRefreshTick++
                            Toast.makeText(
                                context,
                                "Deleted ${targetProp.propertyCode.ifBlank { targetProp.projectName }} permanently",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(context, "Delete blocked or failed.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else null
            )
        }
    }

    if (showMatchingClientsSheet && lead != null) {
        OwnerMatchingClientsSheet(
            ownerLead = lead,
            linkedProperties = ownerLinkedProperties,
            onDismiss = { showMatchingClientsSheet = false }
        )
    }

    if (showLoiBuilderSheet && lead != null) {
        val matchedProperty = remember(lead, allLiveInventory, ownerLinkedProperties) {
            ownerLinkedProperties.firstOrNull() ?: run {
                val codes = lead.matchedPropertyCodes
                    .split(",", "|", ";", "\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                allLiveInventory.firstOrNull { prop ->
                    codes.any { c ->
                        prop.propertyCode.equals(c, ignoreCase = true) || prop.id.equals(c, ignoreCase = true)
                    }
                }
            }
        }
        val crmLeadsList = remember(context) {
            runCatching { readCrmLeads(context) }.getOrDefault(emptyList())
        }
        LoiBuilderBottomSheet(
            initialDraft = LoiDraftFactory.fromLead(lead, matchedProperty),
            availableLeads = crmLeadsList,
            availableProperties = allLiveInventory,
            onDismiss = { showLoiBuilderSheet = false },
            onPdfShared = { savedDraft ->
                appendCrmActivity(
                    context = context,
                    leadId = leadId,
                    type = "LOI PDF Generated",
                    notes = "Generated LOI (${savedDraft.loiRefCode}) for ${savedDraft.propertyName.ifBlank { "Premises" }}",
                    nextFollowUp = lead.nextFollowUp,
                    performedBy = loggedInEmployee
                )
                activities = loadActivities(context, leadId)
            }
        )
    }

    if (showPermanentDeleteDialog && lead != null) {
        val leadLabel = listOf(lead.displayClientTitle(), lead.id)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" / ")
        AlertDialog(
            onDismissRequest = { showPermanentDeleteDialog = false },
            containerColor = SurfaceWhite,
            title = {
                Text(
                    "Delete Permanently",
                    color = StatusDangerRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Permanently delete $leadLabel from this device and the Master Google Sheet? This cannot be undone.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermanentDeleteDialog = false
                        executePermanentLeadDelete(lead)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusDangerRed,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("crm_confirm_permanent_delete_btn")
                ) {
                    Text("Delete Permanently", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPermanentDeleteDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = TextPrimary)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnerMatchingClientsSheet(
    ownerLead: CrmLead,
    linkedProperties: List<PropertyItem>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val refProp = linkedProperties.firstOrNull()
    val refDivision = remember(refProp, ownerLead) {
        refProp?.let { if (it.isCommercial()) "Commercial" else "Residential" } ?: ownerLead.segment
    }
    val refLocation = remember(refProp, ownerLead) {
        refProp?.location?.takeIf { it.isNotBlank() } ?: ownerLead.location
    }
    val refAmount = remember(refProp, ownerLead) {
        val fromProp = refProp?.let { CalculationUtils.primaryAmountForProperty(it) } ?: 0.0
        if (fromProp > 0.0) fromProp else (CalculationUtils.parseCurrencyInput(ownerLead.budget) ?: 0.0)
    }
    val refCarpet = remember(refProp, ownerLead) {
        val fromProp = refProp?.carpetAreaSqFt ?: 0.0
        if (fromProp > 0.0) fromProp else (ownerLead.carpetAreaRange.replace(",", "").trim().toDoubleOrNull() ?: 0.0)
    }

    val matchedClients = remember(ownerLead, linkedProperties) {
        readCrmLeads(context)
            .filter { !it.isOwnerLead() && it.id != ownerLead.id }
            .mapNotNull { client ->
                var score = 0
                val reasons = mutableListOf<String>()
                if (refDivision.isNotBlank() && client.segment.equals(refDivision, ignoreCase = true)) {
                    score += 2
                    reasons.add(client.segment)
                }
                if (refLocation.isNotBlank() && client.location.contains(refLocation, ignoreCase = true)) {
                    score += 3
                    reasons.add("Location: $refLocation")
                }
                val clientBudget = CalculationUtils.parseCurrencyInput(client.maxBudgetInr.ifBlank { client.maxRentInr.ifBlank { client.budget } }) ?: 0.0
                if (refAmount > 0.0 && clientBudget > 0.0 && kotlin.math.abs(clientBudget - refAmount) / refAmount <= 0.35) {
                    score += 3
                    reasons.add("Budget Match")
                }
                val clientMinArea = client.minCarpetSqFt.toDoubleOrNull() ?: 0.0
                val clientMaxArea = client.maxCarpetSqFt.toDoubleOrNull() ?: clientMinArea
                if (refCarpet > 0.0 && clientMaxArea > 0.0 && refCarpet in (clientMinArea * 0.7)..(clientMaxArea * 1.35)) {
                    score += 2
                    reasons.add("Area Match")
                }
                if (score > 0) Triple(client, score, reasons.joinToString(" • ")) else null
            }
            .sortedByDescending { it.second }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft,
        dragHandle = { BottomSheetDefaults.DragHandle(color = SecondaryText) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("owner_matching_clients_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Matching Clients (${matchedClients.size})", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(
                        text = listOfNotNull(
                            refProp?.projectName?.takeIf { it.isNotBlank() },
                            refLocation.takeIf { it.isNotBlank() },
                            refAmount.takeIf { it > 0.0 }?.let { CalculationUtils.formatIndianCurrency(it) }
                        ).joinToString(" • ").ifBlank { ownerLead.displayClientTitle() },
                        fontSize = 11.5.sp,
                        color = SecondaryText
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                }
            }

            if (matchedClients.isEmpty()) {
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No matching buyer/tenant leads found yet for this property's segment, location, or price.",
                        color = SecondaryText,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(matchedClients, key = { it.first.id }) { (client, _, reasonLine) ->
                        val budgetDisplay = CalculationUtils.formatCrmBudgetDisplay(
                            rawBudget = client.budget,
                            transactionOrRequirementHint = "${client.requirementType} ${client.requirement}",
                            minInr = client.minBudgetInr.ifBlank { client.minRentInr },
                            maxInr = client.maxBudgetInr.ifBlank { client.maxRentInr }
                        )
                        val digits = client.phone.filter { it.isDigit() }
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = client.displayClientTitle(),
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = reasonLine,
                                        color = PrimaryActionOrange,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            client.requirement.takeIf { it.isNotBlank() },
                                            budgetDisplay.takeIf { it.isNotBlank() }
                                        ).joinToString(" • "),
                                        color = SecondaryText,
                                        fontSize = 11.5.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (digits.length >= 7) {
                                    IconButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", digits, null)))
                                            }
                                        },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(StatusSuccessGreen.copy(alpha = 0.14f))
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = "Call Client", tint = StatusSuccessGreen, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            val waPhone = if (digits.length == 10) "91$digits" else digits
                                            CrmCallManager.openWhatsAppChat(context, waPhone, "")
                                        },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFFE7F9EE))
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                                            contentDescription = "WhatsApp Client",
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeadCallHistoryModalSheet(
    lead: CrmLead,
    calls: List<CrmCallRecord>,
    performer: String,
    onDismiss: () -> Unit,
    onOutcomeLogged: (String, String) -> Unit
) {
    var selectedOutcome by remember { mutableStateOf(TlcStandardOptions.callOutcomes.first()) }
    var outcomeNote by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft,
        dragHandle = { BottomSheetDefaults.DragHandle(color = SecondaryText) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .testTag("lead_call_history_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Call History • ${lead.displayClientTitle()}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Performed by: $performer • ${lead.phone.ifBlank { lead.id }}", fontSize = 11.5.sp, color = SecondaryText)
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                }
            }

            // Quick Log Call Outcome section
            Surface(
                color = SurfaceWhite,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, BorderGrey),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactDropdownField(
                        label = "Call Outcome",
                        selectedValue = selectedOutcome,
                        options = TlcStandardOptions.callOutcomes,
                        onValueSelected = { selectedOutcome = it },
                        allowCustom = true
                    )
                    OutlinedTextField(
                        value = outcomeNote,
                        onValueChange = { outcomeNote = it },
                        label = { Text("Quick Call Note (Optional)", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = BackgroundSoft,
                            unfocusedContainerColor = BackgroundSoft,
                            focusedBorderColor = PrimaryActionOrange,
                            unfocusedBorderColor = BorderGrey
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onOutcomeLogged(selectedOutcome, outcomeNote)
                            outcomeNote = ""
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("log_call_outcome_btn")
                    ) {
                        Text("Log Call Outcome", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text(
                text = "All Matching Calls (${calls.size})",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (calls.isEmpty()) {
                    item {
                        Text(
                            text = "No device or CRM call logs matched ${lead.phone.ifBlank { lead.id }} yet.",
                            color = SecondaryText,
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
                items(calls, key = { "${it.id}_${it.timestamp}" }) { call ->
                    val typeColor = when (call.callType) {
                        CallType.INCOMING -> StatusSuccessGreen
                        CallType.OUTGOING -> PrimaryActionOrange
                        CallType.MISSED -> StatusDangerRed
                    }
                    val typeLabel = when (call.callType) {
                        CallType.INCOMING -> "Incoming"
                        CallType.OUTGOING -> "Outgoing"
                        CallType.MISSED -> "Missed"
                    }
                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = typeColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "$typeLabel • ${CrmCallManager.formatDuration(call.durationSeconds)}",
                                        color = typeColor,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = CrmCallManager.formatTimestamp(call.timestamp),
                                    color = SecondaryText,
                                    fontSize = 11.sp
                                )
                            }
                            if (call.notes.isNotBlank()) {
                                Text(call.notes, color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeadMessageTemplateSheet(
    lead: CrmLead,
    employeePoc: String,
    onDismiss: () -> Unit,
    onSent: (String, String) -> Unit
) {
    val context = LocalContext.current
    val isBroker = remember(lead) { lead.isBrokerLead() }
    val templates = remember(isBroker) {
        buildList {
            if (isBroker) add("Broker / Co-Broking Outreach")
            add("First Intro / Requirement Follow-up")
            add("Missed Call Callback")
            add("Shortlisted Options Follow-up")
            add("Site Visit Confirmation")
            add("Post-Visit Feedback")
            add("Custom")
        }
    }
    var selectedTemplate by remember(isBroker) { mutableStateOf(templates.first()) }
    val clientDisplayName = remember(lead) { lead.displayClientTitle() }
    val formattedBudget = remember(lead) {
        CalculationUtils.formatCrmBudgetDisplay(
            rawBudget = lead.budget,
            transactionOrRequirementHint = "${lead.requirementType} ${lead.requirement}",
            minInr = lead.minBudgetInr.ifBlank { lead.minRentInr },
            maxInr = lead.maxBudgetInr.ifBlank { lead.maxRentInr }
        )
    }
    val reqDesc = remember(lead) {
        lead.requirement.takeIf { !isSpecBlank(it) } ?: "${lead.segment} property"
    }
    val locDesc = remember(lead) {
        lead.location.takeIf { !isSpecBlank(it) } ?: "Pune"
    }

    fun buildTemplateBody(templateName: String): String {
        val budgetSnippet = if (formattedBudget.isNotBlank()) " around $formattedBudget" else ""
        return when (templateName) {
            "Broker / Co-Broking Outreach" ->
                "Hi $clientDisplayName, I am $employeePoc from The Leasing Company. Reaching out on your co-broking requirement for $reqDesc in $locDesc$budgetSnippet. We have verified direct options available — let's connect and align!"
            "First Intro / Requirement Follow-up" ->
                "Hello $clientDisplayName, I am $employeePoc from The Leasing Company. Reaching out regarding your requirement for $reqDesc in $locDesc$budgetSnippet. Please let me know a good time for a quick call."
            "Missed Call Callback" ->
                "Hello $clientDisplayName, tried calling you regarding your property requirement in $locDesc. Kindly share a convenient time to reconnect. — $employeePoc"
            "Shortlisted Options Follow-up" ->
                "Hello $clientDisplayName, we have curated verified $reqDesc options in $locDesc$budgetSnippet matching your criteria. Shall I share the details and schedule a viewing? — $employeePoc"
            "Site Visit Confirmation" ->
                "Hello $clientDisplayName, confirming your upcoming site visit for shortlisted options in $locDesc. Please let us know if you need location pins or any changes. — $employeePoc"
            "Post-Visit Feedback" ->
                "Hello $clientDisplayName, thank you for visiting the properties in $locDesc today. Kindly share your feedback on the options so we can take the next steps. — $employeePoc"
            else ->
                "Hello $clientDisplayName, "
        }
    }

    var messageText by remember { mutableStateOf(buildTemplateBody(selectedTemplate)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft,
        dragHandle = { BottomSheetDefaults.DragHandle(color = SecondaryText) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("lead_message_template_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Message Templates", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Client: $clientDisplayName • POC: $employeePoc", fontSize = 11.5.sp, color = SecondaryText)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = SecondaryText, fontWeight = FontWeight.Bold)
                }
            }

            CompactDropdownField(
                label = "Template",
                selectedValue = selectedTemplate,
                options = templates,
                onValueSelected = { chosen ->
                    selectedTemplate = chosen
                    messageText = buildTemplateBody(chosen)
                },
                allowCustom = false
            )

            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                label = { Text("Editable Message", fontSize = 12.sp) },
                minLines = 4,
                maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceWhite,
                    unfocusedContainerColor = SurfaceWhite,
                    focusedBorderColor = PrimaryActionOrange,
                    unfocusedBorderColor = BorderGrey
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val digits = lead.phone.filter { it.isDigit() }
                        val smsUri = Uri.parse("smsto:$digits")
                        val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                            putExtra("sms_body", messageText)
                        }
                        runCatching {
                            context.startActivity(intent)
                            onSent("SMS", messageText)
                        }.onFailure {
                            Toast.makeText(context, "Could not open SMS app", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("template_send_sms_btn")
                ) {
                    Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send via SMS", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }

                Button(
                    onClick = {
                        val digits = lead.phone.filter { it.isDigit() }
                        val international = if (digits.length == 10) "91$digits" else digits
                        if (international.length < 7) {
                            Toast.makeText(context, "Valid client mobile number required", Toast.LENGTH_SHORT).show()
                        } else {
                            CrmCallManager.openWhatsAppChat(context, international, messageText)
                            onSent("WhatsApp", messageText)
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF25D366),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1.2f)
                        .heightIn(min = 48.dp)
                        .testTag("template_send_whatsapp_btn")
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Send via WhatsApp", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun ReopenRequirementDialog(
    lead: CrmLead,
    onDismiss: () -> Unit,
    onReopened: (CrmLead, RequirementStageProjection) -> Unit
) {
    val context = LocalContext.current
    val openStages = remember {
        listOf(
            CrmPipelineStage.EXPLORING_MARKET,
            CrmPipelineStage.DETAILS_SHARED,
            CrmPipelineStage.FOLLOW_UP,
            CrmPipelineStage.SITE_VISIT_PENDING,
            CrmPipelineStage.NEGOTIATION
        )
    }
    var selectedStage by remember { mutableStateOf(CrmPipelineStage.FOLLOW_UP) }
    var reason by remember { mutableStateOf("") }
    var nextAction by remember { mutableStateOf("") }
    var followUpDate by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
    }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        title = {
            Text("Reopen Closed Requirement", fontWeight = FontWeight.Bold, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select open stage for ${lead.name}:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                openStages.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { st ->
                            FilterChip(
                                selected = selectedStage == st,
                                onClick = { selectedStage = st },
                                label = { Text(st.label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reopening Reason (Mandatory)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nextAction,
                    onValueChange = { nextAction = it },
                    label = { Text("Next Action Note (Mandatory)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = followUpDate,
                    onValueChange = { followUpDate = it },
                    label = { Text("Next Follow-up Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMsg?.let {
                    Text(it, color = StatusNotAvailable, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    CrmPipelineAndWorkloadEngine.reopenRequirement(
                        context = context,
                        lead = lead,
                        targetOpenStage = selectedStage,
                        reason = reason,
                        nextActionNote = nextAction,
                        followUpDate = followUpDate
                    ).onSuccess { (uLead, uProj) ->
                        onReopened(uLead, uProj)
                    }.onFailure { err ->
                        errorMsg = err.message ?: "Unable to reopen requirement."
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor
                )
            ) {
                Text("Confirm Reopen", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SecondaryText)
            }
        }
    )
}

@Composable
private fun ProfileField(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SecondaryText, fontWeight = FontWeight.Bold)
        Text(value.ifBlank { "Not Mentioned" }, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

private fun loadRequirements(context: Context, id: String): List<CrmRequirement> =
    runCatching {
        val array = profileArray(context, id, "requirements")
        (0 until array.length()).map { i ->
            val j = array.getJSONObject(i)
            CrmRequirement(
                id = j.optString("id"),
                segment = j.optString("segment"),
                purpose = j.optString("purpose"),
                description = j.optString("description"),
                location = j.optString("location"),
                budget = j.optString("budget"),
                areaRange = j.optString("areaRange"),
                areaBasis = j.optString("areaBasis", "CARPET"),
                possessionDeadline = j.optString("possessionDeadline"),
                stage = j.optString("stage", "New"),
                originalRequirementText = j.optString("originalRequirementText"),
                recordVersion = j.optInt("recordVersion", 1)
            )
        }
    }.getOrDefault(emptyList())

private fun persistRequirements(context: Context, id: String, values: List<CrmRequirement>) {
    val array = JSONArray()
    values.forEach {
        array.put(
            JSONObject()
                .put("id", it.id)
                .put("segment", it.segment)
                .put("purpose", it.purpose)
                .put("description", it.description)
                .put("location", it.location)
                .put("budget", it.budget)
                .put("areaRange", it.areaRange)
                .put("areaBasis", it.areaBasis)
                .put("possessionDeadline", it.possessionDeadline)
                .put("stage", it.stage)
                .put("originalRequirementText", it.originalRequirementText)
                .put("recordVersion", it.recordVersion)
        )
    }
    persistProfileArray(context, id, "requirements", array)
}

@Composable
private fun RequirementEditor(
    initial: CrmRequirement? = null,
    onDismiss: () -> Unit,
    onSave: (CrmRequirement) -> Unit
) {
    var segment by remember(initial) { mutableStateOf(initial?.segment ?: "Commercial") }
    var purpose by remember(initial) { mutableStateOf(initial?.purpose ?: "Lease") }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var location by remember(initial) { mutableStateOf(initial?.location.orEmpty()) }
    var budget by remember(initial) { mutableStateOf(initial?.budget.orEmpty()) }
    var areaRange by remember(initial) { mutableStateOf(initial?.areaRange.orEmpty()) }
    var areaBasis by remember(initial) { mutableStateOf(initial?.areaBasis ?: "CARPET") }
    var stage by remember(initial) { mutableStateOf(initial?.stage ?: "New") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BackgroundSoft
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Top Bar
                Surface(
                    color = SurfaceWhite,
                    shadowElevation = 2.dp,
                    border = BorderStroke(0.5.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (initial == null) "New Client Requirement" else "Edit Client Requirement",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Scrollable 3 Section Cards
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Card 1: Division & Transaction
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = BorderStroke(1.dp, BorderGrey),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Division & Transaction", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)

                            Text("Division", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Commercial", "Residential").forEach { seg ->
                                    val selected = segment == seg
                                    Surface(
                                        selected = selected,
                                        onClick = { segment = seg },
                                        color = if (selected) PrimaryActionOrange else BackgroundSoft,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, if (selected) PrimaryActionOrange else BorderGrey),
                                        modifier = Modifier.weight(1f).height(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                            Text(seg, color = if (selected) OnPrimaryActionColor else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            Text("Transaction Type", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Lease", "Purchase", "Pre-Leased").forEach { pur ->
                                    val selected = purpose == pur
                                    Surface(
                                        selected = selected,
                                        onClick = { purpose = pur },
                                        color = if (selected) PrimaryActionOrange else BackgroundSoft,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, if (selected) PrimaryActionOrange else BorderGrey),
                                        modifier = Modifier.weight(1f).height(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                            Text(pur, color = if (selected) OnPrimaryActionColor else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Card 2: Requirement Specifications & Budget
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = BorderStroke(1.dp, BorderGrey),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Requirement Specifications", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)

                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("Property / Size / Configuration *") },
                                placeholder = { Text("e.g. 2500 sq.ft IT Office or 3 BHK") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                            )

                            OutlinedTextField(
                                value = location,
                                onValueChange = { location = it },
                                label = { Text("Preferred Micro-Markets") },
                                placeholder = { Text("e.g. Kharadi, Viman Nagar, Baner") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = budget,
                                    onValueChange = { budget = it },
                                    label = { Text("Budget / Rent") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                                )
                                OutlinedTextField(
                                    value = areaRange,
                                    onValueChange = { areaRange = it },
                                    label = { Text("Area Range (sq.ft)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                                )
                            }
                        }
                    }

                    // Card 3: Pipeline Stage
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = BorderStroke(1.dp, BorderGrey),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Pipeline Stage", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                            OutlinedTextField(
                                value = stage,
                                onValueChange = { stage = it },
                                label = { Text("Stage") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                            )
                        }
                    }
                }

                // Sticky Bottom Bar with navigationBarsPadding and imePadding
                Surface(
                    color = SurfaceWhite,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text("Cancel", fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            enabled = description.isNotBlank(),
                            onClick = {
                                onSave(
                                    CrmRequirement(
                                        id = initial?.id ?: UUID.randomUUID().toString(),
                                        segment = segment,
                                        purpose = purpose,
                                        description = description.trim(),
                                        location = location.trim(),
                                        budget = budget.trim(),
                                        areaRange = areaRange.trim(),
                                        areaBasis = areaBasis,
                                        stage = stage,
                                        recordVersion = (initial?.recordVersion ?: 0) + 1
                                    )
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                            modifier = Modifier.weight(1.8f).height(48.dp)
                        ) {
                            Text("Save Requirement", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityEditor(onDismiss: () -> Unit, onSave: (CrmActivity) -> Unit) {
    var type by remember { mutableStateOf("Call") }
    var notes by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        title = { Text("Log Activity or Note", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Call", "Visit", "Note", "WhatsApp").forEach {
                        FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it) })
                    }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Outcome / notes") })
                OutlinedTextField(next, { next = it }, label = { Text("Next follow-up YYYY-MM-DD (Optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                enabled = notes.isNotBlank(),
                onClick = {
                    val nowMs = System.currentTimeMillis()
                    val sdf = SimpleDateFormat("dd MMM yyyy '·' h:mm a", Locale.US)
                    val dateStr = sdf.format(Date(nowMs))
                    onSave(CrmActivity(UUID.randomUUID().toString(), type, nowMs, dateStr, notes.trim(), next.trim(), ""))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor
                )
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SecondaryText)
            }
        }
    )
}
