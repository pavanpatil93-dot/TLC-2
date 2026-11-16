package com.example.ui.screens

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ai.ExtractedLeadDraft
import com.example.ai.GeminiRealEstateService
import com.example.crm.*
import com.example.data.PropertiesLocalDatabase
import com.example.data.SheetsRepository
import com.example.data.UserSettingsManager
import com.example.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

private val crmPipelineStages = CrmPipelineStage.entries.map { it.label }

internal enum class FollowUpUrgency(val label: String, val color: Color) {
    OVERDUE("Overdue", Color(0xFFC62828)),
    DUE_TODAY("Due Today", Color(0xFFB26A00)),
    UPCOMING("Upcoming", Color(0xFF1565C0)),
    COMPLETED("Completed", Color(0xFF1E7E45)),
    NONE("No Follow-up Scheduled", Color(0xFF566168))
}

internal fun computeFollowUpUrgency(lead: CrmLead, today: String): FollowUpUrgency {
    if (lead.followUpCompleted) return FollowUpUrgency.COMPLETED
    if (lead.nextFollowUp.isBlank()) return FollowUpUrgency.NONE
    return when {
        lead.nextFollowUp < today -> FollowUpUrgency.OVERDUE
        lead.nextFollowUp == today -> FollowUpUrgency.DUE_TODAY
        else -> FollowUpUrgency.UPCOMING
    }
}

private val CRM_ID_NAME_REGEX = Regex("^CRM[-_]?\\d+$", RegexOption.IGNORE_CASE)

data class CrmLead(
    val id: String,
    val name: String,
    val phone: String,
    val segment: String,
    val requirement: String,
    val location: String,
    val budget: String,
    val status: String,
    val nextFollowUp: String,
    val notes: String,
    val assignedTo: String = "",
    val followUpTime: String = "",
    val updatedBy: String = "",
    val updatedAt: String = "",
    val carpetAreaRange: String = "",
    val nextFollowUpTime: String = "",
    val nextFollowUpAction: String = "Call Client",
    val followUpCompleted: Boolean = false,
    val areaSqFt: String = "",
    val possessionTimeline: String = "",
    val companyName: String = "",
    // 37-Column Master Sheet CRM metadata fields
    val createdDate: String = "",
    val alternatePhone: String = "",
    val clientType: String = "",
    val requirementType: String = "",
    val propertyType: String = "",
    val minCarpetSqFt: String = "",
    val maxCarpetSqFt: String = "",
    val minBudgetInr: String = "",
    val maxBudgetInr: String = "",
    val minRentInr: String = "",
    val maxRentInr: String = "",
    val furnishing: String = "",
    val leadSource: String = "",
    val priority: String = "",
    val lastContactDate: String = "",
    val lastContactMode: String = "",
    val lastContactOutcome: String = "",
    val matchedPropertyCodes: String = "",
    val siteVisitDate: String = "",
    val siteVisitStatus: String = "",
    val clientNotes: String = "",
    val consentStatus: String = "",
    val createdBy: String = "",
    val legacyLeadId: String = "",
    val verificationStatus: String = "",
    val duplicatePhoneCheck: String = ""
)

internal fun CrmLead.displayClientTitle(): String {
    val cleaned = name.trim()
    val isSystemIdName = (id.startsWith("CRM", ignoreCase = true) || id.startsWith("lead_", ignoreCase = true)) &&
        cleaned.equals(id, ignoreCase = true)
    val isInvalidName = cleaned.isBlank() ||
        isSystemIdName ||
        cleaned.equals("Unnamed Client", ignoreCase = true) ||
        CRM_ID_NAME_REGEX.matches(cleaned)
    return if (isInvalidName) {
        "Client • ${phone.ifBlank { id }}"
    } else {
        cleaned
    }
}

internal fun CrmLead.isBrokerLead(): Boolean {
    if (isOwnerLead()) return false
    val ct = clientType.trim()
    val ls = leadSource.trim()
    return ct.contains("Broker", ignoreCase = true) ||
        ct.contains("Channel Partner", ignoreCase = true) ||
        ct.equals("CP", ignoreCase = true) ||
        ct.contains("/ CP", ignoreCase = true) ||
        ls.contains("Channel Partner", ignoreCase = true) ||
        ls.contains("Broker", ignoreCase = true)
}

internal fun CrmLead.isOwnerLead(): Boolean {
    val ct = clientType.trim()
    return ct.equals("Owner", ignoreCase = true) ||
        ct.equals("Property Owner", ignoreCase = true) ||
        ct.equals("Landlord", ignoreCase = true)
}

internal fun cleanActionNotesForDisplay(rawNotes: String): String {
    if (rawNotes.isBlank()) return ""
    // Strip legacy metadata suffixes such as "| Lead source: ... | Priority: ... | Verification: ..."
    val segments = rawNotes.split("|").map { it.trim() }
    val filtered = segments.filterNot { seg ->
        seg.startsWith("Lead source:", ignoreCase = true) ||
            seg.startsWith("Priority:", ignoreCase = true) ||
            seg.startsWith("Verification:", ignoreCase = true)
    }
    return filtered.joinToString(" | ").trim()
}

fun CrmLead.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("clientName", name)
    put("phone", phone)
    put("segment", segment)
    put("requirement", requirement)
    put("location", location)
    put("budget", budget)
    put("status", status)
    put("nextFollowUp", nextFollowUp)
    put("notes", notes)
    put("assignedTo", assignedTo)
    put("followUpTime", followUpTime)
    put("updatedBy", updatedBy)
    put("updatedAt", updatedAt)
    put("carpetAreaRange", carpetAreaRange)
    put("nextFollowUpTime", nextFollowUpTime)
    put("nextFollowUpAction", nextFollowUpAction)
    put("followUpCompleted", followUpCompleted)
    put("areaSqFt", areaSqFt)
    put("possessionTimeline", possessionTimeline)
    put("companyName", companyName)
    put("createdDate", createdDate)
    put("alternatePhone", alternatePhone)
    put("clientType", clientType)
    put("requirementType", requirementType)
    put("propertyType", propertyType)
    put("minCarpetSqFt", minCarpetSqFt)
    put("maxCarpetSqFt", maxCarpetSqFt)
    put("minBudgetInr", minBudgetInr)
    put("maxBudgetInr", maxBudgetInr)
    put("minRentInr", minRentInr)
    put("maxRentInr", maxRentInr)
    put("furnishing", furnishing)
    put("leadSource", leadSource)
    put("priority", priority)
    put("lastContactDate", lastContactDate)
    put("lastContactMode", lastContactMode)
    put("lastContactOutcome", lastContactOutcome)
    put("matchedPropertyCodes", matchedPropertyCodes)
    put("siteVisitDate", siteVisitDate)
    put("siteVisitStatus", siteVisitStatus)
    put("clientNotes", clientNotes)
    put("consentStatus", consentStatus)
    put("createdBy", createdBy)
    put("legacyLeadId", legacyLeadId)
    put("verificationStatus", verificationStatus)
    put("duplicatePhoneCheck", duplicatePhoneCheck)
}

fun readCrmLeads(context: Context): List<CrmLead> {
    val prefs = com.example.data.auditedCrmPreferences(context, "crm_development_v1", Context.MODE_PRIVATE)
    val raw = prefs.getString("leads", "[]") ?: "[]"
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val leadId = item.optString("id", item.optString("Lead ID", ""))
            // Always map client name strictly from Client Name, never Lead ID (CRM-xxxxxx)
            val rawName = item.optString("Client Name")
                .ifBlank { item.optString("clientName") }
                .ifBlank { item.optString("customerName") }
                .ifBlank { item.optString("name") }
                .trim()
            val isSystemIdName = (leadId.startsWith("CRM", ignoreCase = true) || leadId.startsWith("lead_", ignoreCase = true)) &&
                rawName.equals(leadId, ignoreCase = true)
            val resolvedName = if (
                rawName.isBlank() ||
                isSystemIdName ||
                rawName.equals("Unnamed Client", ignoreCase = true) ||
                CRM_ID_NAME_REGEX.matches(rawName)
            ) "" else rawName
            val rawPhone = item.optString("phone")
                .ifBlank { item.optString("Mobile") }
                .ifBlank { item.optString("primaryMobile", item.optString("contactNumber", "")) }
            val rawReq = item.optString("requirement").ifBlank { item.optString("notes", "") }
            val rawLoc = item.optString("location", item.optString("Preferred Locations", "Pune"))
            val rawBudget = item.optString("budget").ifBlank {
                val minR = item.optString("minRentInr", item.optString("Min Rent INR/Month", ""))
                val maxR = item.optString("maxRentInr", item.optString("Max Rent INR/Month", ""))
                val minB = item.optString("minBudgetInr", item.optString("Min Budget INR", ""))
                val maxB = item.optString("maxBudgetInr", item.optString("Max Budget INR", ""))
                maxR.ifBlank { minR }.ifBlank { maxB }.ifBlank { minB }
            }
            CrmLead(
                id = leadId.ifBlank { "lead_${java.util.UUID.randomUUID()}" },
                name = resolvedName,
                phone = rawPhone,
                segment = item.optString("segment", item.optString("Property Category", "Commercial")).ifBlank { "Commercial" },
                requirement = rawReq.ifBlank { "General Enquiry" },
                location = rawLoc.ifBlank { "Pune" },
                budget = rawBudget,
                status = item.optString("status", item.optString("Lead Stage", item.optString("stage", "New"))).ifBlank { "New" },
                nextFollowUp = item.optString("nextFollowUp", item.optString("Next Follow-up Date", "")),
                notes = item.optString("notes", rawReq),
                assignedTo = item.optString("assignedTo", item.optString("Assigned To", "")),
                followUpTime = item.optString("followUpTime"),
                updatedBy = item.optString("updatedBy"),
                updatedAt = item.optString("updatedAt"),
                carpetAreaRange = item.optString("carpetAreaRange"),
                nextFollowUpTime = item.optString("nextFollowUpTime", item.optString("followUpTime")),
                nextFollowUpAction = item.optString("nextFollowUpAction", "Call Client"),
                followUpCompleted = item.optBoolean("followUpCompleted", false),
                areaSqFt = item.optString("areaSqFt", item.optString("carpetAreaRange")),
                possessionTimeline = item.optString("possessionTimeline", item.optString("Possession Deadline", "")),
                companyName = item.optString("companyName"),
                createdDate = item.optString("createdDate", item.optString("Created Date", "")),
                alternatePhone = item.optString("alternatePhone", item.optString("Alternate Mobile", "")),
                clientType = item.optString("clientType", item.optString("Client Type", "")),
                requirementType = item.optString("requirementType", item.optString("Requirement Type", "")),
                propertyType = item.optString("propertyType", item.optString("Property Type", "")),
                minCarpetSqFt = item.optString("minCarpetSqFt", item.optString("Min Carpet SqFt", "")),
                maxCarpetSqFt = item.optString("maxCarpetSqFt", item.optString("Max Carpet SqFt", "")),
                minBudgetInr = item.optString("minBudgetInr", item.optString("Min Budget INR", "")),
                maxBudgetInr = item.optString("maxBudgetInr", item.optString("Max Budget INR", "")),
                minRentInr = item.optString("minRentInr", item.optString("Min Rent INR/Month", "")),
                maxRentInr = item.optString("maxRentInr", item.optString("Max Rent INR/Month", "")),
                furnishing = item.optString("furnishing", item.optString("Furnishing", "")),
                leadSource = item.optString("leadSource", item.optString("Lead Source", "")),
                priority = item.optString("priority", item.optString("Priority", "")),
                lastContactDate = item.optString("lastContactDate", item.optString("Last Contact Date", "")),
                lastContactMode = item.optString("lastContactMode", item.optString("Last Contact Mode", "")),
                lastContactOutcome = item.optString("lastContactOutcome", item.optString("Last Contact Outcome", "")),
                matchedPropertyCodes = item.optString("matchedPropertyCodes", item.optString("Matched Property Codes", "")),
                siteVisitDate = item.optString("siteVisitDate", item.optString("Site Visit Date", "")),
                siteVisitStatus = item.optString("siteVisitStatus", item.optString("Site Visit Status", "")),
                clientNotes = item.optString("clientNotes", item.optString("Client Notes", "")),
                consentStatus = item.optString("consentStatus", item.optString("Consent Status", "")),
                createdBy = item.optString("createdBy", item.optString("Created By", "")),
                legacyLeadId = item.optString("legacyLeadId", item.optString("Legacy Lead ID", "")),
                verificationStatus = item.optString("verificationStatus", item.optString("Verification Status", "")),
                duplicatePhoneCheck = item.optString("duplicatePhoneCheck", item.optString("Duplicate Phone Check", ""))
            )
        }
    }.getOrDefault(emptyList())
}

internal fun saveCrmLeads(context: Context, leads: List<CrmLead>) {
    val existing = readCrmLeads(context).associateBy { it.id }
    val settings = UserSettingsManager(context)
    val identity = settings.auditIdentity()
    val now = java.time.Instant.now().toString()
    val audited = leads.map { lead ->
        val previous = existing[lead.id]
        if (previous == lead && lead.updatedBy.isNotBlank() && lead.updatedAt.isNotBlank()) {
            lead
        } else {
            lead.copy(updatedBy = identity, updatedAt = now)
        }
    }
    val array = JSONArray()
    val stored = JSONArray(context.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE).getString("leads", "[]"))
    audited.forEach { lead ->
        val old = (0 until stored.length()).map { stored.getJSONObject(it) }.firstOrNull { it.optString("id") == lead.id }
        val merged = old?.let { JSONObject(it.toString()) } ?: JSONObject()
        val previous = existing[lead.id]
        val unchanged = previous != null &&
            previous.copy(updatedBy = lead.updatedBy, updatedAt = lead.updatedAt) == lead
        if (!unchanged) {
            val fields = lead.toJson()
            fields.keys().forEach { key -> merged.put(key, fields.get(key)) }
        }
        array.put(merged)
    }
    com.example.data.auditedCrmPreferences(context, "crm_development_v1", Context.MODE_PRIVATE)
        .edit().putString("leads", array.toString()).apply()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CrmFoundationScreen(
    inventory: List<PropertyItem> = emptyList(),
    onNavigateToCalls: (() -> Unit)? = null,
    onPushCrmLeadToSheet: (suspend (CrmLead) -> Result<Unit>)? = null,
    externalRefreshTick: Int = 0
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val currentProfile = remember { UserSettingsManager(context).userProfile.value }
    var leads by remember { mutableStateOf(readCrmLeads(context)) }
    var pipelineRefreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(externalRefreshTick) {
        if (externalRefreshTick > 0) {
            leads = readCrmLeads(context)
            pipelineRefreshTick++
        }
    }

    var search by rememberSaveable { mutableStateOf("") }
    var activeLeadTab by rememberSaveable { mutableStateOf("All Leads") } // "All Leads", "Today's Leads", "Overdue", "Upcoming"
    var divisionFilter by rememberSaveable { mutableStateOf("All") } // "All", "Commercial", "Residential"
    var assignedScopeMode by rememberSaveable { mutableStateOf("Team Leads") } // "My Leads", "Team Leads", "Unassigned"
    var assignedEmployeeFilter by rememberSaveable { mutableStateOf("") }
    var selectedStages by remember { mutableStateOf<Set<CrmPipelineStage>>(emptySet()) }
    var includeHistoricalRecords by rememberSaveable { mutableStateOf(false) }
    var sortBy by rememberSaveable { mutableStateOf("Follow-up Urgency") }

    var showFilterSheet by remember { mutableStateOf(false) }
    var showTaskCentreModal by remember { mutableStateOf(false) }
    var showLeadratReviewQueue by remember { mutableStateOf(false) }
    var showTeamWorkloadSheet by remember { mutableStateOf(false) }

    var editing by remember { mutableStateOf<CrmLead?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var profileLead by remember { mutableStateOf<CrmLead?>(null) }
    var visitLead by remember { mutableStateOf<CrmLead?>(null) }
    var matchLead by remember { mutableStateOf<CrmLead?>(null) }
    var quickFollowUpLead by remember { mutableStateOf<CrmLead?>(null) }
    var stageChangeLead by remember { mutableStateOf<CrmLead?>(null) }
    var assignLead by remember { mutableStateOf<CrmLead?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    val projectionsById = remember(leads, pipelineRefreshTick) {
        leads.associate { it.id to CrmPipelineAndWorkloadEngine.getProjection(context, it) }
    }

    val archivedWorkLeads = remember(leads, projectionsById) {
        leads.filter { lead ->
            val state = projectionsById[lead.id]?.workState
            state == CrmWorkState.ARCHIVED || state == CrmWorkState.AWAITING_REVIEW || state == CrmWorkState.HISTORICAL_INACTIVE
        }
    }

    val awaitingReviewLeads = remember(leads, projectionsById) {
        leads.filter { projectionsById[it.id]?.workState == CrmWorkState.AWAITING_REVIEW }
    }

    val activeWorkLeads = remember(leads, projectionsById, includeHistoricalRecords) {
        leads.filter { lead ->
            val proj = projectionsById[lead.id]
            if (includeHistoricalRecords) {
                proj?.workState != CrmWorkState.ARCHIVED
            } else {
                proj?.workState == CrmWorkState.ACTIVE
            }
        }
    }

    val stageCounts = remember(leads, pipelineRefreshTick, includeHistoricalRecords) {
        CrmPipelineAndWorkloadEngine.computeStageCounts(context, leads, includeHistorical = includeHistoricalRecords)
    }

    val activeClientLeads = remember(activeWorkLeads) {
        activeWorkLeads.filterNot { it.isOwnerLead() }
    }
    val ownerWorkLeads = remember(activeWorkLeads) {
        activeWorkLeads.filter { it.isOwnerLead() }
    }

    val overdueCount = remember(activeClientLeads, today) {
        activeClientLeads.count { it.nextFollowUp.isNotBlank() && !it.followUpCompleted && it.nextFollowUp < today }
    }
    val dueTodayCount = remember(activeClientLeads, today) {
        activeClientLeads.count { it.nextFollowUp == today && !it.followUpCompleted }
    }
    val upcomingCount = remember(activeClientLeads, today) {
        activeClientLeads.count { it.nextFollowUp > today && !it.followUpCompleted }
    }
    val brokerCount = remember(activeClientLeads) {
        activeClientLeads.count { it.isBrokerLead() }
    }
    val ownerCount = ownerWorkLeads.size

    val targetLeadPool = if (activeLeadTab == "Archive") archivedWorkLeads else activeWorkLeads

    val filtered = remember(
        targetLeadPool,
        projectionsById,
        activeLeadTab,
        divisionFilter,
        assignedScopeMode,
        assignedEmployeeFilter,
        selectedStages,
        search,
        today
    ) {
        targetLeadPool.filter { lead ->
            val proj = projectionsById[lead.id]
            val stage = proj?.stage ?: CrmPipelineStage.fromAnyString(lead.status)

            val matchesTab = when (activeLeadTab) {
                "Archive" -> true
                "Owners" -> lead.isOwnerLead()
                "Broker Reqs" -> !lead.isOwnerLead() && lead.isBrokerLead()
                "Today's Leads" -> !lead.isOwnerLead() && lead.nextFollowUp == today && !lead.followUpCompleted
                "Overdue" -> !lead.isOwnerLead() && lead.nextFollowUp.isNotBlank() && !lead.followUpCompleted && lead.nextFollowUp < today
                "Upcoming" -> !lead.isOwnerLead() && lead.nextFollowUp > today && !lead.followUpCompleted
                else -> !lead.isOwnerLead()
            }
            val matchesDivision = when (divisionFilter) {
                "Commercial" -> lead.segment.equals("Commercial", true)
                "Residential" -> lead.segment.equals("Residential", true)
                else -> true
            }
            val matchesStage = selectedStages.isEmpty() || stage in selectedStages
            val matchesScopeMode = when (assignedScopeMode) {
                "My Leads" -> lead.assignedTo.equals(currentProfile.username, ignoreCase = true)
                "Unassigned" -> lead.assignedTo.isBlank()
                else -> true
            }
            val matchesSpecificAssignee = assignedEmployeeFilter.isBlank() ||
                lead.assignedTo.equals(assignedEmployeeFilter, ignoreCase = true)
            val matchesSearch = search.isBlank() || listOf(
                lead.name,
                lead.phone,
                lead.companyName,
                lead.location,
                lead.requirement,
                lead.assignedTo,
                stage.label
            ).any { value -> value.contains(search, ignoreCase = true) }

            matchesTab && matchesDivision && matchesStage && matchesScopeMode && matchesSpecificAssignee && matchesSearch
        }
    }

    val sortedLeads = remember(filtered, sortBy, today) {
        when (sortBy) {
            "Recent" -> filtered.sortedByDescending { it.updatedAt.ifBlank { it.id } }
            "Name" -> filtered.sortedBy { it.name }
            else -> filtered.sortedWith(
                compareBy<CrmLead> {
                    val urgency = computeFollowUpUrgency(it, today)
                    when (urgency) {
                        FollowUpUrgency.OVERDUE -> 0
                        FollowUpUrgency.DUE_TODAY -> 1
                        FollowUpUrgency.UPCOMING -> 2
                        FollowUpUrgency.COMPLETED -> 3
                        FollowUpUrgency.NONE -> 4
                    }
                }.thenBy { it.nextFollowUp.ifBlank { "9999-12-31" } }
            )
        }
    }

    var crmSubTab by rememberSaveable { mutableIntStateOf(0) } // 0: Clients & Leads, 1: Deals & Calculator
    var calculatorTargetProperty by remember { mutableStateOf<PropertyItem?>(null) }

    if (showTaskCentreModal) {
        CrmTasksScreen(onBack = {
            showTaskCentreModal = false
            leads = readCrmLeads(context)
            pipelineRefreshTick++
        })
        return
    }

    if (crmSubTab == 1) {
        Column(Modifier.fillMaxSize().background(BackgroundSoft)) {
            CrmSubTabSwitcher(
                crmSubTab = crmSubTab,
                onSelectTab = { crmSubTab = it }
            )
            BrokerageDealCalculator(
                inventory = inventory,
                initialProperty = calculatorTargetProperty,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        val isSearchPinned by remember {
            derivedStateOf {
                val stickyInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "crm_search_and_filter" }
                stickyInfo != null && stickyInfo.offset <= 0
            }
        }
        val showFloatingAddClient by remember {
            derivedStateOf {
                val visible = listState.layoutInfo.visibleItemsInfo
                visible.isNotEmpty() && visible.none { it.key == "crm_header_actions" }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundSoft)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("crm_leads_lazy_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 92.dp)
            ) {
                // 0. Ultra-Compact CRM Top Control Bar (Sub-Tabs + Tasks + Workload + Add)
                item(key = "crm_sub_tabs") {
                    CrmSubTabSwitcher(
                        crmSubTab = crmSubTab,
                        onSelectTab = { crmSubTab = it },
                        dueTodayCount = dueTodayCount + overdueCount,
                        onOpenTasks = { showTaskCentreModal = true },
                        onOpenWorkload = { showTeamWorkloadSheet = true },
                        onAddClient = { editing = null; showEditor = true }
                    )
                }

                // 1. Optional awaiting-review import notice (kept ultra-slim)
                if (awaitingReviewLeads.isNotEmpty()) {
                    item(key = "crm_review_banner") {
                        Surface(
                            onClick = { showLeadratReviewQueue = true },
                            color = SelectedSurfaceContainer,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, PrimaryActionOrange),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .testTag("crm_review_imported_leads_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.FactCheck, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = "${awaitingReviewLeads.size} imported leads need review",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                Text("Review ›", fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = PrimaryActionOrange)
                            }
                        }
                    }
                }

                // 2. Pinned Sticky Header: Search + Filter Button + Live/Today/Overdue/Archive Chips
                stickyHeader(key = "crm_search_and_filter") {
                    Surface(
                        color = BackgroundSoft,
                        tonalElevation = if (isSearchPinned) 2.dp else 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("crm_sticky_search_filter_header")
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(top = 4.dp, bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Search + Filter Button Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = search,
                                        onValueChange = { search = it },
                                        placeholder = { Text("Search client, phone, location...", fontSize = 12.sp, color = SecondaryText) },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(18.dp)) },
                                        trailingIcon = {
                                            if (search.isNotEmpty()) {
                                                IconButton(onClick = { search = "" }) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = SurfaceWhite,
                                            unfocusedContainerColor = SurfaceWhite,
                                            focusedBorderColor = PrimaryActionOrange,
                                            unfocusedBorderColor = BorderGrey
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("crm_search_input")
                                    )

                                    IconButton(
                                        onClick = { showFilterSheet = true },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(SurfaceWhite)
                                            .testTag("crm_open_filter_sheet_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = "Filters",
                                            tint = if (selectedStages.isNotEmpty() || divisionFilter != "All" || assignedScopeMode != "Team Leads" || assignedEmployeeFilter.isNotBlank()) {
                                                PrimaryActionOrange
                                            } else {
                                                TextPrimary
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // Lead Work Tabs (Live, Owners, Broker Reqs, Today, Overdue, Archive)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(
                                        "All Leads" to "Live (${activeClientLeads.size})",
                                        "Owners" to "🏠 Owners ($ownerCount)",
                                        "Broker Reqs" to "🤝 Broker Reqs ($brokerCount)",
                                        "Today's Leads" to "Today ($dueTodayCount)",
                                        "Overdue" to "Overdue ($overdueCount)",
                                        "Archive" to "Archive (${archivedWorkLeads.size})"
                                    ).forEach { (tabKey, label) ->
                                        FilterChipItem(
                                            label = label,
                                            isSelected = activeLeadTab == tabKey,
                                            onClick = { activeLeadTab = tabKey }
                                        )
                                    }
                                }

                                // Active Stage & Assignment Removable Filter Chips
                                if (divisionFilter != "All" || selectedStages.isNotEmpty() || assignedScopeMode != "Team Leads" || assignedEmployeeFilter.isNotBlank() || includeHistoricalRecords) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (divisionFilter != "All") {
                                            InputChip(
                                                selected = true,
                                                onClick = { divisionFilter = "All" },
                                                label = { Text("$divisionFilter ✕", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                            )
                                        }
                                        selectedStages.forEach { st ->
                                            InputChip(
                                                selected = true,
                                                onClick = { selectedStages = selectedStages - st },
                                                label = { Text("Stage: ${st.label} ✕", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                            )
                                        }
                                        if (assignedScopeMode != "Team Leads") {
                                            InputChip(
                                                selected = true,
                                                onClick = { assignedScopeMode = "Team Leads" },
                                                label = { Text("$assignedScopeMode ✕", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                            )
                                        }
                                        if (assignedEmployeeFilter.isNotBlank()) {
                                            InputChip(
                                                selected = true,
                                                onClick = { assignedEmployeeFilter = "" },
                                                label = { Text("Assigned: $assignedEmployeeFilter ✕", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                            )
                                        }
                                        if (includeHistoricalRecords) {
                                            InputChip(
                                                selected = true,
                                                onClick = { includeHistoricalRecords = false },
                                                label = { Text("Including Historical ✕", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                            )
                                        }
                                    }
                                }
                            }

                            if (isSearchPinned) {
                                HorizontalDivider(thickness = 1.dp, color = BorderGrey)
                            }
                        }
                    }
                }

                // 5. CRM Leads List
                items(sortedLeads, key = { it.id }) { lead ->
                    val proj = projectionsById[lead.id]
                    val isArchived = proj?.workState != CrmWorkState.ACTIVE
                    val missingReq = remember(lead) {
                        CrmPipelineAndWorkloadEngine.minimumRequiredMissingFields(lead)
                    }
                    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                        CrmClientCard(
                            lead = lead,
                            projection = proj,
                            today = today,
                            isArchived = isArchived,
                            missingRequiredFields = missingReq,
                            onCardClick = { profileLead = lead },
                            onCall = {
                                val clean = ContactUtils.normalizePhoneNumber(lead.phone)
                                if (clean.length < 7) {
                                    snackbarMessage = "Valid client phone number not available"
                                } else {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")))
                                    }.onFailure { snackbarMessage = "No calling app available" }
                                }
                            },
                            onWhatsApp = {
                                val clean = ContactUtils.normalizePhoneNumber(lead.phone)
                                if (clean.length < 7) {
                                    snackbarMessage = "Valid client phone number not available"
                                } else {
                                    val phone = if (clean.length == 10) "91$clean" else clean
                                    val rawMsg = if (lead.isBrokerLead()) {
                                        "Hello ${lead.displayClientTitle()}, regarding your client requirement for ${lead.requirement.ifBlank { "properties" }} in ${lead.location.ifBlank { "Pune" }}, sharing options for co-broking from The Leasing Company."
                                    } else {
                                        "Hello ${lead.displayClientTitle()}, regarding your requirement for ${lead.requirement.ifBlank { "properties" }} in ${lead.location.ifBlank { "Pune" }}, sharing verified options from The Leasing Company."
                                    }
                                    val msg = Uri.encode(rawMsg)
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=$msg")))
                                    }.onFailure { snackbarMessage = "No WhatsApp handler available" }
                                }
                            },
                            onMatch = { matchLead = lead },
                            onScheduleFollowUp = { quickFollowUpLead = lead },
                            onOpenChangeStage = { stageChangeLead = lead },
                            onOpenAssign = { assignLead = lead },
                            onSiteVisits = { visitLead = lead },
                            onHistory = { profileLead = lead },
                            onVerifyToggle = { goLive ->
                                if (goLive) {
                                    CrmPipelineAndWorkloadEngine.verifyAndActivateLead(context, lead)
                                        .onSuccess {
                                            leads = readCrmLeads(context)
                                            val liveLead = leads.firstOrNull { it.id == lead.id }?.copy(verificationStatus = "Verified Live")
                                                ?: lead.copy(verificationStatus = "Verified Live")
                                            leads = leads.map { if (it.id == liveLead.id) liveLead else it }
                                            saveCrmLeads(context, leads)
                                            coroutineScope.launch { onPushCrmLeadToSheet?.invoke(liveLead) }
                                            pipelineRefreshTick++
                                            snackbarMessage = "${liveLead.displayClientTitle()} verified and activated Live"
                                        }
                                        .onFailure { err ->
                                            snackbarMessage = err.message ?: "Complete 100% required details first"
                                            editing = lead
                                            showEditor = true
                                        }
                                } else {
                                    CrmPipelineAndWorkloadEngine.archiveLead(context, lead)
                                    leads = readCrmLeads(context)
                                    val archivedLead = leads.firstOrNull { it.id == lead.id }?.copy(verificationStatus = "Archived")
                                        ?: lead.copy(verificationStatus = "Archived")
                                    leads = leads.map { if (it.id == archivedLead.id) archivedLead else it }
                                    saveCrmLeads(context, leads)
                                    coroutineScope.launch { onPushCrmLeadToSheet?.invoke(archivedLead) }
                                    pipelineRefreshTick++
                                    snackbarMessage = "${archivedLead.displayClientTitle()} moved to Archive"
                                }
                            }
                        )
                    }
                }

                if (sortedLeads.isEmpty()) {
                    item(key = "crm_empty_state") {
                        val isArchivePool = activeLeadTab != "Archive" && activeWorkLeads.isEmpty() && archivedWorkLeads.isNotEmpty()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (isArchivePool) "All Leads Archived (${archivedWorkLeads.size})" else "No client requirements match these filters.",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isArchivePool) {
                                Text(
                                    text = "Verify leads from Archive with 100% required details to make them Live.",
                                    color = SecondaryText,
                                    fontSize = 12.sp
                                )
                                Button(
                                    onClick = { activeLeadTab = "Archive" },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = OnPrimaryActionColor
                                    ),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("crm_open_archive_btn")
                                ) {
                                    Text("Review Archive (${archivedWorkLeads.size})", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                TextButton(onClick = {
                                    search = ""
                                    activeLeadTab = "All Leads"
                                    divisionFilter = "All"
                                    selectedStages = emptySet()
                                    assignedScopeMode = "Team Leads"
                                    assignedEmployeeFilter = ""
                                }) {
                                    Text("Reset all filters", color = PrimaryActionOrange, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Compact Floating + Add Client button when scrolled past top header
            AnimatedVisibility(
                visible = showFloatingAddClient,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.9f, animationSpec = tween(180)),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f, animationSpec = tween(150)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            ) {
                ExtendedFloatingActionButton(
                    onClick = { editing = null; showEditor = true },
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor,
                    shape = RoundedCornerShape(14.dp),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("crm_floating_add_client_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Client",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Add Client",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Filter Sheet with Stage Counts & Employee Scope
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            containerColor = BackgroundSoft
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Filter CRM Pipeline & Workload", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

                Text("Division", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Commercial", "Residential").forEach { divOpt ->
                        FilterChipItem(
                            label = divOpt,
                            isSelected = divisionFilter == divOpt,
                            onClick = { divisionFilter = divOpt }
                        )
                    }
                }

                Text("Ownership Scope", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("My Leads", "Team Leads", "Unassigned").forEach { scopeOpt ->
                        FilterChipItem(
                            label = scopeOpt,
                            isSelected = assignedScopeMode == scopeOpt,
                            onClick = { assignedScopeMode = scopeOpt }
                        )
                    }
                }

                Text("Pipeline Stage (Distinct Active Requirements)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                CrmPipelineStage.entries.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { st ->
                            val count = stageCounts[st] ?: 0
                            val isSelected = st in selectedStages
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedStages = if (isSelected) selectedStages - st else selectedStages + st
                                },
                                label = { Text("${st.label} ($count)", fontSize = 11.5.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Text("Sort Order", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Follow-up Urgency", "Recent", "Name").forEach { sOpt ->
                        FilterChipItem(
                            label = sOpt,
                            isSelected = sortBy == sOpt,
                            onClick = { sortBy = sOpt }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeHistoricalRecords,
                        onCheckedChange = { includeHistoricalRecords = it },
                        colors = CheckboxDefaults.colors(checkedColor = PrimaryActionOrange, checkmarkColor = OnPrimaryActionColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Include historical/closed records in list", fontSize = 12.5.sp, color = TextPrimary)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            selectedStages = emptySet()
                            assignedScopeMode = "Team Leads"
                            assignedEmployeeFilter = ""
                            includeHistoricalRecords = false
                            showFilterSheet = false
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text("Reset", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Button(
                        onClick = { showFilterSheet = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)
                    ) {
                        Text("Apply Filters", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Change Stage Bottom Sheet (Prompt 10)
    stageChangeLead?.let { targetLead ->
        CrmChangeStageBottomSheet(
            lead = targetLead,
            onDismiss = { stageChangeLead = null },
            onSaved = { updatedLead ->
                leads = readCrmLeads(context)
                val synced = leads.firstOrNull { it.id == updatedLead.id } ?: updatedLead
                coroutineScope.launch { onPushCrmLeadToSheet?.invoke(synced) }
                pipelineRefreshTick++
                stageChangeLead = null
                Toast.makeText(context, "Stage updated to ${updatedLead.status}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Assignment & Reassignment Bottom Sheet (Prompt 11)
    assignLead?.let { targetLead ->
        CrmAssignmentBottomSheet(
            lead = targetLead,
            onDismiss = { assignLead = null },
            onSaved = { updatedLead ->
                leads = readCrmLeads(context)
                val synced = leads.firstOrNull { it.id == updatedLead.id } ?: updatedLead
                coroutineScope.launch { onPushCrmLeadToSheet?.invoke(synced) }
                pipelineRefreshTick++
                assignLead = null
                Toast.makeText(context, "Assigned to ${updatedLead.assignedTo}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Team Workload Bottom Sheet (Prompt 11)
    if (showTeamWorkloadSheet) {
        TeamWorkloadBottomSheet(
            leads = leads,
            todayIso = today,
            onSelectEmployeeScope = { empName ->
                if (empName == "UNASSIGNED") {
                    assignedScopeMode = "Unassigned"
                    assignedEmployeeFilter = ""
                } else {
                    assignedScopeMode = "Team Leads"
                    assignedEmployeeFilter = empName
                }
                showTeamWorkloadSheet = false
            },
            onDismiss = { showTeamWorkloadSheet = false }
        )
    }

    // Leadrat Imported Leads Review Queue Sheet (Prompt 06)
    if (showLeadratReviewQueue) {
        LeadratReviewQueueSheet(
            awaitingLeads = awaitingReviewLeads,
            onLeadReviewed = {
                leads = readCrmLeads(context)
                pipelineRefreshTick++
            },
            onDismiss = { showLeadratReviewQueue = false }
        )
    }

    // Matcher, Site Visits, Profile, Follow-up, Lead Editor
    matchLead?.let { selected ->
        CrmInventoryMatchesDialog(lead = selected, inventory = inventory) { matchLead = null }
    }
    visitLead?.let { selected ->
        CrmSiteVisitsDialog(
            leadId = selected.id,
            clientName = selected.name,
            inventory = inventory,
            lead = selected,
            onOpenCalculatorForProperty = { prop ->
                calculatorTargetProperty = prop
                crmSubTab = 1
                visitLead = null
            },
            onClose = {
                visitLead = null
                pipelineRefreshTick++
            }
        )
    }
    profileLead?.let { selected ->
        CrmClientProfile(
            leadId = selected.id,
            clientName = selected.displayClientTitle(),
            inventory = inventory,
            onLeadUpdated = { updated ->
                leads = readCrmLeads(context)
                pipelineRefreshTick++
                coroutineScope.launch {
                    onPushCrmLeadToSheet?.invoke(updated)
                }
            },
            onClose = {
                profileLead = null
                leads = readCrmLeads(context)
                pipelineRefreshTick++
            }
        )
    }
    quickFollowUpLead?.let { selected ->
        QuickFollowUpBottomSheet(
            lead = selected,
            onDismiss = { quickFollowUpLead = null },
            onSave = { date, time, action, newAssignee, notesVal, completed ->
                val updated = selected.copy(
                    nextFollowUp = date,
                    nextFollowUpTime = time,
                    followUpTime = time,
                    nextFollowUpAction = action,
                    assignedTo = newAssignee.ifBlank { selected.assignedTo },
                    notes = notesVal,
                    followUpCompleted = completed
                )
                leads = leads.map { if (it.id == selected.id) updated else it }
                saveCrmLeads(context, leads)
                val persisted = readCrmLeads(context).firstOrNull { it.id == selected.id } ?: updated
                val actType = if (completed) "Follow-up Completed" else "Follow-up Scheduled"
                appendCrmActivity(context, selected.id, actType, "Action: $action. Notes: $notesVal", date, persisted.assignedTo)
                coroutineScope.launch {
                    onPushCrmLeadToSheet?.invoke(persisted)
                }
                pipelineRefreshTick++
                quickFollowUpLead = null
                Toast.makeText(context, "Follow-up saved successfully", Toast.LENGTH_SHORT).show()
            }
        )
    }
    if (showEditor) {
        AddEditLeadScreen(
            initial = editing,
            onDismiss = { showEditor = false },
            onBatchSaveLeads = { batchLeads ->
                if (batchLeads.isNotEmpty()) {
                    val existingIds = leads.map { it.id }.toSet()
                    val toInsert = batchLeads.filterNot { it.id in existingIds }
                    val updatedList = toInsert + leads.map { old ->
                        batchLeads.firstOrNull { it.id == old.id } ?: old
                    }
                    saveCrmLeads(context, updatedList)
                    leads = readCrmLeads(context)
                    batchLeads.forEach { item ->
                        appendCrmActivity(
                            context = context,
                            leadId = item.id,
                            type = "AI Paste Imported",
                            notes = "Imported via AI Paste: ${item.requirement} (${item.location})",
                            nextFollowUp = item.nextFollowUp,
                            performedBy = item.assignedTo
                        )
                        if (CrmPipelineAndWorkloadEngine.isReadyForLiveVerification(item)) {
                            CrmPipelineAndWorkloadEngine.verifyAndActivateLead(context, item)
                        }
                        coroutineScope.launch {
                            onPushCrmLeadToSheet?.invoke(item)
                        }
                    }
                    leads = readCrmLeads(context)
                    pipelineRefreshTick++
                    showEditor = false
                    Toast.makeText(context, "Saved ${batchLeads.size} lead(s) to CRM", Toast.LENGTH_SHORT).show()
                }
            },
            onSave = { lead, verifyLive ->
                val existsAlready = leads.any { it.id == lead.id }
                leads = if (!existsAlready) listOf(lead) + leads
                else leads.map { if (it.id == lead.id) lead else it }
                saveCrmLeads(context, leads)
                val persistedLead = readCrmLeads(context).firstOrNull { it.id == lead.id } ?: lead
                val actType = if (editing == null) "Lead Created" else "Requirement Updated"
                appendCrmActivity(
                    context = context,
                    leadId = persistedLead.id,
                    type = actType,
                    notes = "Lead updated / created for ${persistedLead.displayClientTitle()} (${persistedLead.segment})",
                    nextFollowUp = persistedLead.nextFollowUp,
                    performedBy = persistedLead.assignedTo
                )
                if (verifyLive) {
                    CrmPipelineAndWorkloadEngine.verifyAndActivateLead(context, persistedLead)
                        .onSuccess {
                            leads = readCrmLeads(context)
                            val liveLead = leads.firstOrNull { it.id == persistedLead.id } ?: persistedLead
                            val syncedLead = liveLead.copy(verificationStatus = "Verified Live")
                            leads = leads.map { if (it.id == syncedLead.id) syncedLead else it }
                            saveCrmLeads(context, leads)
                            coroutineScope.launch {
                                onPushCrmLeadToSheet?.invoke(syncedLead)
                            }
                            pipelineRefreshTick++
                            showEditor = false
                            Toast.makeText(context, "${syncedLead.displayClientTitle()} verified & synced Live", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { err ->
                            coroutineScope.launch {
                                onPushCrmLeadToSheet?.invoke(persistedLead)
                            }
                            pipelineRefreshTick++
                            showEditor = false
                            snackbarMessage = err.message ?: "Saved to Archive — complete 100% required fields to go Live"
                        }
                } else {
                    coroutineScope.launch {
                        onPushCrmLeadToSheet?.invoke(persistedLead)
                    }
                    pipelineRefreshTick++
                    showEditor = false
                    Toast.makeText(context, "Draft saved & synced", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    snackbarMessage?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            snackbarMessage = null
        }
    }
}

@Composable
private fun CrmClientCard(
    lead: CrmLead,
    projection: RequirementStageProjection?,
    today: String,
    isArchived: Boolean = false,
    missingRequiredFields: List<String> = emptyList(),
    onCardClick: () -> Unit,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit,
    onMatch: () -> Unit,
    onScheduleFollowUp: () -> Unit,
    onOpenChangeStage: () -> Unit,
    onOpenAssign: () -> Unit,
    onSiteVisits: () -> Unit,
    onHistory: () -> Unit,
    onVerifyToggle: ((Boolean) -> Unit)? = null
) {
    val urgency = computeFollowUpUrgency(lead, today)
    var showMoreMenu by remember { mutableStateOf(false) }
    val resolvedStage = projection?.stage ?: CrmPipelineStage.fromAnyString(lead.status)
    val isBroker = lead.isBrokerLead()
    val isOwner = lead.isOwnerLead()

    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderGrey),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick)
            .testTag("crm_client_card_${lead.id}")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Header Row: Client Name, Stage Badge, Division Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lead.displayClientTitle(),
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (lead.id.startsWith("CRM-", ignoreCase = true)) {
                            Surface(
                                color = SurfaceMuted,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = lead.id,
                                    color = SecondaryText,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (isOwner) {
                            Surface(
                                color = SelectedSurfaceContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "🏠 Property Owner",
                                    color = PrimaryActionOrange,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        } else if (isBroker) {
                            Surface(
                                color = Color(0xFFE0F2FE),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "🤝 Broker / CP",
                                    color = Color(0xFF0369A1),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (lead.companyName.isNotBlank()) {
                            Text(
                                text = lead.companyName,
                                color = SecondaryText,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isArchived) {
                        Surface(
                            color = if (missingRequiredFields.isEmpty()) WarningAmberSubtle else Color(0xFFFEE2E2),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (missingRequiredFields.isEmpty()) "Ready" else "Need ${missingRequiredFields.size}",
                                color = if (missingRequiredFields.isEmpty()) StatusWarningAmber else StatusDangerRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Surface(
                        color = SelectedSurfaceContainer,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { onOpenChangeStage() }
                    ) {
                        Text(
                            text = resolvedStage.label,
                            color = PrimaryActionOrange,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Surface(
                        color = SurfaceMuted,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = lead.segment.ifBlank { "Commercial" },
                            color = TextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Requirement & Location
            Text(
                text = buildString {
                    append(lead.requirement.ifBlank { "Requirement Not Mentioned" })
                    if (lead.location.isNotBlank()) append(" · ${lead.location}")
                },
                color = TextPrimary,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Budget (Indian Currency ₹ L / ₹ Cr / /mo), Area & Assigned POC compact row
            val formattedBudget = remember(lead.budget, lead.requirementType, lead.requirement, lead.minBudgetInr, lead.maxBudgetInr, lead.minRentInr, lead.maxRentInr) {
                CalculationUtils.formatCrmBudgetDisplay(
                    rawBudget = lead.budget,
                    transactionOrRequirementHint = "${lead.requirementType} ${lead.requirement}",
                    minInr = lead.minBudgetInr.ifBlank { lead.minRentInr },
                    maxInr = lead.maxBudgetInr.ifBlank { lead.maxRentInr }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAssign() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        if (formattedBudget.isNotBlank()) append(formattedBudget)
                        if (formattedBudget.isNotBlank() && lead.carpetAreaRange.isNotBlank()) append(" · ")
                        if (lead.carpetAreaRange.isNotBlank()) append("${lead.carpetAreaRange} sq.ft")
                    }.ifBlank { "Budget/Area Pending" },
                    color = PrimaryActionOrange,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = lead.assignedTo.ifBlank { "Unassigned" },
                    color = if (lead.assignedTo.isBlank()) StatusCheckAvailability else SecondaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            // Tappable Follow-Up Row
            Surface(
                color = urgency.color.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.8.dp, urgency.color.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onScheduleFollowUp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = urgency.color, modifier = Modifier.size(14.dp))
                        Text(
                            text = buildString {
                                append(urgency.label)
                                if (lead.nextFollowUp.isNotBlank()) {
                                    append(" (${lead.nextFollowUp}")
                                    if (lead.nextFollowUpTime.isNotBlank()) append(" ${lead.nextFollowUpTime}")
                                    append(")")
                                }
                            },
                            color = urgency.color,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("›", color = urgency.color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = BorderGrey, thickness = 0.8.dp)

            // Compact Action Row: Call Icon | WhatsApp Logo | Follow-up | Verify Switch | More
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCall,
                    modifier = Modifier
                        .size(48.dp)
                        .background(SurfaceMuted, RoundedCornerShape(8.dp))
                        .testTag("crm_lead_call_${lead.id}")
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Call Client", tint = StatusSuccessGreen, modifier = Modifier.size(20.dp))
                }

                IconButton(
                    onClick = onWhatsApp,
                    modifier = Modifier
                        .size(48.dp)
                        .background(SurfaceMuted, RoundedCornerShape(8.dp))
                        .testTag("crm_lead_whatsapp_${lead.id}")
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.ic_whatsapp_logo),
                        contentDescription = "WhatsApp Client",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Button(
                    onClick = onScheduleFollowUp,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Icon(Icons.Default.Event, contentDescription = "Schedule Follow-up", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Follow-up", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                if (onVerifyToggle != null) {
                    Switch(
                        checked = !isArchived,
                        onCheckedChange = { goLive -> onVerifyToggle(goLive) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPrimaryActionColor,
                            checkedTrackColor = StatusSuccessGreen,
                            uncheckedThumbColor = TextPrimary,
                            uncheckedTrackColor = SurfaceMuted
                        ),
                        modifier = Modifier.testTag("crm_lead_verify_switch_${lead.id}")
                    )
                }

                Box {
                    IconButton(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier
                            .size(48.dp)
                            .background(SurfaceMuted, RoundedCornerShape(8.dp))
                            .testTag("crm_lead_more_${lead.id}")
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More CRM Actions", tint = TextPrimary, modifier = Modifier.size(20.dp))
                    }

                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Change Stage", fontWeight = FontWeight.Bold) },
                            leadingIcon = { Icon(Icons.Default.TrendingUp, contentDescription = null) },
                            onClick = { showMoreMenu = false; onOpenChangeStage() }
                        )
                        DropdownMenuItem(
                            text = { Text("Assign / Reassign Lead", fontWeight = FontWeight.Bold) },
                            leadingIcon = { Icon(Icons.Default.PersonAddAlt, contentDescription = null) },
                            onClick = { showMoreMenu = false; onOpenAssign() }
                        )
                        DropdownMenuItem(
                            text = { Text("Find Matching Properties") },
                            leadingIcon = { Icon(Icons.Default.CompareArrows, contentDescription = null) },
                            onClick = { showMoreMenu = false; onMatch() }
                        )
                        DropdownMenuItem(
                            text = { Text("Site Visits") },
                            leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
                            onClick = { showMoreMenu = false; onSiteVisits() }
                        )
                        DropdownMenuItem(
                            text = { Text("View Client Details & History") },
                            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                            onClick = { showMoreMenu = false; onHistory() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Evidence-Based Change Stage Bottom Sheet (Prompt 10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrmChangeStageBottomSheet(
    lead: CrmLead,
    onDismiss: () -> Unit,
    onSaved: (CrmLead) -> Unit
) {
    val context = LocalContext.current
    val currentProj = remember(lead) { CrmPipelineAndWorkloadEngine.getProjection(context, lead) }
    val visits = remember(lead.id) { readVisits(context, lead.id) }
    val openTasks = remember(lead.id) {
        runCatching { CrmTaskRepository.read(context) }.getOrDefault(emptyList())
            .filter { it.status == CrmTaskStatus.OPEN && it.originId == lead.id }
    }

    var targetStage by remember { mutableStateOf(currentProj.stage) }
    var reason by remember { mutableStateOf("") }
    var nextActionNote by remember { mutableStateOf(currentProj.nextActionNote) }
    var followUpDate by remember { mutableStateOf(lead.nextFollowUp) }
    var followUpTime by remember { mutableStateOf(lead.nextFollowUpTime) }
    var selectedVisitId by remember { mutableStateOf(currentProj.linkedVisitId.ifBlank { visits.firstOrNull()?.id.orEmpty() }) }
    var detailsSharedConfirmed by remember { mutableStateOf(false) }
    var holdReason by remember { mutableStateOf(currentProj.holdReason) }
    var holdReviewDate by remember { mutableStateOf(currentProj.holdReviewDate) }
    var closureSummary by remember { mutableStateOf(currentProj.closureSummary) }
    var closureReason by remember { mutableStateOf(currentProj.closureReason) }
    var closureConfirmed by remember { mutableStateOf(false) }
    var taskDisposition by remember { mutableStateOf(TaskDispositionChoice.CANCEL_SELECTED) }
    var selectedTasksToCancel by remember { mutableStateOf(openTasks.map { it.id }.toSet()) }
    var keepTasksReason by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val operationId = remember { UUID.randomUUID().toString() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Change Pipeline Stage", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Client: ${lead.name} • Current Stage: ${currentProj.stage.label}",
                color = SecondaryText,
                fontSize = 12.sp
            )

            HorizontalDivider(color = BorderGrey)

            Text("Select New Stage:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            CrmPipelineStage.entries.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { st ->
                        FilterChip(
                            selected = targetStage == st,
                            onClick = { targetStage = st; errorMsg = null },
                            label = { Text(st.label, fontSize = 11.5.sp) },
                            modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                        )
                    }
                }
            }

            // Stage-specific Evidence Fields
            if (targetStage == CrmPipelineStage.DETAILS_SHARED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = detailsSharedConfirmed,
                        onCheckedChange = { detailsSharedConfirmed = it },
                        colors = CheckboxDefaults.colors(checkedColor = PrimaryActionOrange, checkmarkColor = OnPrimaryActionColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Confirm property details / shortlist were shared with client", fontSize = 12.5.sp, color = TextPrimary)
                }
            }

            if (targetStage == CrmPipelineStage.FOLLOW_UP) {
                Text("Required Follow-up Schedule:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                OutlinedTextField(
                    value = followUpDate,
                    onValueChange = { followUpDate = it },
                    label = { Text("Follow-up Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (targetStage == CrmPipelineStage.SITE_VISIT_PENDING || targetStage == CrmPipelineStage.SITE_VISIT_DONE) {
                Text("Required Linked Site Visit Evidence:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                if (visits.isEmpty()) {
                    Text(
                        text = "No site visits recorded for this client yet. Schedule a site visit before confirming this stage.",
                        color = StatusNotAvailable,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    visits.forEach { v ->
                        FilterChip(
                            selected = selectedVisitId == v.id,
                            onClick = { selectedVisitId = v.id },
                            label = { Text("${v.property} (${v.date} • ${v.status})", fontSize = 11.sp) }
                        )
                    }
                }
            }

            if (targetStage == CrmPipelineStage.ON_HOLD) {
                OutlinedTextField(
                    value = holdReason,
                    onValueChange = { holdReason = it },
                    label = { Text("Hold Reason (Mandatory)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = holdReviewDate,
                    onValueChange = { holdReviewDate = it },
                    label = { Text("Hold Review Date (YYYY-MM-DD, Mandatory)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (targetStage == CrmPipelineStage.CLOSED_WON) {
                OutlinedTextField(
                    value = closureSummary,
                    onValueChange = { closureSummary = it },
                    label = { Text("Closure Summary / Finalized Unit (Mandatory)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Note: Closing a requirement as Closed Won does not automatically mark inventory Sold/Leased or generate an invoice.",
                    color = SecondaryText,
                    fontSize = 11.sp
                )
            }

            if (targetStage == CrmPipelineStage.CLOSED_LOST) {
                OutlinedTextField(
                    value = closureReason,
                    onValueChange = { closureReason = it },
                    label = { Text("Closure Reason (e.g., budget mismatch, leased elsewhere)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (targetStage.isClosed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = closureConfirmed,
                        onCheckedChange = { closureConfirmed = it },
                        colors = CheckboxDefaults.colors(checkedColor = PrimaryActionOrange, checkmarkColor = OnPrimaryActionColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Confirm closing this requirement as ${targetStage.label}", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }

                if (openTasks.isNotEmpty()) {
                    Text("Open Tasks Disposition (${openTasks.size} linked open tasks):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskDispositionChoice.entries.forEach { choice ->
                            FilterChip(
                                selected = taskDisposition == choice,
                                onClick = { taskDisposition = choice },
                                label = { Text(choice.label, fontSize = 11.sp) }
                            )
                        }
                    }
                    if (taskDisposition == TaskDispositionChoice.KEEP_NECESSARY) {
                        OutlinedTextField(
                            value = keepTasksReason,
                            onValueChange = { keepTasksReason = it },
                            label = { Text("Reason for keeping open tasks") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Stage Progression Note / Reason") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            OutlinedTextField(
                value = nextActionNote,
                onValueChange = { nextActionNote = it },
                label = { Text("Next Action Note") },
                modifier = Modifier.fillMaxWidth()
            )

            errorMsg?.let {
                Text(it, color = StatusNotAvailable, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Button(
                    onClick = {
                        CrmPipelineAndWorkloadEngine.applyStageTransition(
                            context = context,
                            lead = lead,
                            targetStage = targetStage,
                            reason = reason,
                            nextActionNote = nextActionNote,
                            scheduleFollowUpDate = followUpDate,
                            scheduleFollowUpTime = followUpTime,
                            selectedVisitId = selectedVisitId,
                            detailsSharedConfirmed = detailsSharedConfirmed,
                            holdReason = holdReason,
                            holdReviewDate = holdReviewDate,
                            closureReason = closureReason,
                            closureSummary = closureSummary,
                            closureConfirmed = closureConfirmed,
                            taskDispositionChoice = taskDisposition,
                            taskIdsToCancel = selectedTasksToCancel,
                            keepTasksReason = keepTasksReason,
                            expectedVersion = currentProj.recordVersion,
                            operationId = operationId
                        ).onSuccess { (uLead, _) ->
                            appendCrmActivity(
                                context = context,
                                leadId = lead.id,
                                type = "Stage Changed → ${targetStage.label}",
                                notes = reason.ifBlank { "Updated to ${targetStage.label}" },
                                nextFollowUp = uLead.nextFollowUp,
                                performedBy = uLead.assignedTo
                            )
                            onSaved(uLead)
                        }.onFailure { err ->
                            errorMsg = err.message ?: "Unable to save stage change."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    modifier = Modifier.weight(1.5f).heightIn(min = 48.dp)
                ) {
                    Text("Save Stage", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Assignment & Reassignment Bottom Sheet (Prompt 11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrmAssignmentBottomSheet(
    lead: CrmLead,
    onDismiss: () -> Unit,
    onSaved: (CrmLead) -> Unit
) {
    val context = LocalContext.current
    val teamEmployees = remember(context) {
        SheetsRepository.getSyncedActiveEmployeeNames(context) + listOf("Unassigned")
    }
    val relatedOpenTasks = remember(lead.id) {
        runCatching { CrmTaskRepository.read(context) }.getOrDefault(emptyList())
            .filter { it.status == CrmTaskStatus.OPEN && it.originId == lead.id }
    }

    var selectedEmployee by remember { mutableStateOf(lead.assignedTo.ifBlank { teamEmployees.firstOrNull().orEmpty() }) }
    var noteText by remember { mutableStateOf("") }
    var selectedTaskIds by remember { mutableStateOf(relatedOpenTasks.map { it.id }.toSet()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Assign Lead",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${lead.displayClientTitle()} • Current: ${lead.assignedTo.ifBlank { "Unassigned" }}",
                color = SecondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                teamEmployees.forEach { empName ->
                    val isSelected = selectedEmployee.equals(empName, ignoreCase = true)
                    Surface(
                        color = if (isSelected) SelectedSurfaceContainer else SurfaceWhite,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) PrimaryActionOrange else BorderGrey),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectedEmployee = empName
                                errorMsg = null
                            }
                            .testTag("assign_emp_${empName.lowercase().replace(" ", "_")}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = empName,
                                color = if (isSelected) PrimaryActionOrange else TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = PrimaryActionOrange,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Assignment Note (Optional)", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite,
                        focusedBorderColor = PrimaryActionOrange,
                        unfocusedBorderColor = BorderGrey
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (relatedOpenTasks.isNotEmpty()) {
                    Text(
                        text = "Transfer Open Tasks (${relatedOpenTasks.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    relatedOpenTasks.forEach { task ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = task.id in selectedTaskIds,
                                onCheckedChange = { checked ->
                                    selectedTaskIds = if (checked) selectedTaskIds + task.id else selectedTaskIds - task.id
                                },
                                colors = CheckboxDefaults.colors(checkedColor = PrimaryActionOrange, checkmarkColor = OnPrimaryActionColor)
                            )
                            Text(
                                text = "${task.title} (Due: ${task.dueDate.ifBlank { "Open" }})",
                                fontSize = 12.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }

                errorMsg?.let {
                    Text(it, color = StatusNotAvailable, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = {
                        val target = selectedEmployee.trim()
                        if (target.isBlank()) {
                            errorMsg = "Select a team member."
                            return@Button
                        }
                        val resolvedAssignee = if (target.equals("Unassigned", ignoreCase = true)) "" else target
                        val allLeads = readCrmLeads(context)
                        val updatedLead = lead.copy(assignedTo = resolvedAssignee)
                        val nextLeads = if (allLeads.any { it.id == lead.id }) {
                            allLeads.map { if (it.id == lead.id) updatedLead else it }
                        } else {
                            listOf(updatedLead) + allLeads
                        }
                        saveCrmLeads(context, nextLeads)
                        relatedOpenTasks.forEach { task ->
                            if (task.id in selectedTaskIds) {
                                runCatching { CrmTaskRepository.upsert(context, task.copy(assignedTo = resolvedAssignee)) }
                            }
                        }
                        appendCrmActivity(
                            context = context,
                            leadId = lead.id,
                            type = "Lead Assigned",
                            notes = "Assigned to ${resolvedAssignee.ifBlank { "Unassigned" }}${if (noteText.isNotBlank()) ": ${noteText.trim()}" else ""}",
                            nextFollowUp = updatedLead.nextFollowUp,
                            performedBy = resolvedAssignee.ifBlank { UserSettingsManager(context).userProfile.value.username }
                        )
                        onSaved(updatedLead)
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    modifier = Modifier
                        .weight(1.3f)
                        .heightIn(min = 48.dp)
                        .testTag("assign_confirm_btn")
                ) {
                    Text("Assign", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * Team Workload Bottom Sheet (Prompt 11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamWorkloadBottomSheet(
    leads: List<CrmLead>,
    todayIso: String,
    onSelectEmployeeScope: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val workloadState = remember(leads, todayIso) {
        CrmPipelineAndWorkloadEngine.computeTeamWorkloadState(context, leads, todayIso)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Team Workload & Unassigned Queue", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Tap any row to open that employee's active requirements in CRM", fontSize = 11.5.sp, color = SecondaryText)

            // Unassigned Queue Card
            Surface(
                onClick = { onSelectEmployeeScope("UNASSIGNED") },
                color = SelectedSurfaceContainer,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, PrimaryActionOrange),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Unassigned Active Requirements", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.5.sp)
                        Text("Requires assignment to an eligible team member", color = SecondaryText, fontSize = 11.sp)
                    }
                    Text(
                        text = "${workloadState.unassignedRequirementCount} ›",
                        color = PrimaryActionOrange,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(workloadState.employeeRows, key = { it.employeeId }) { row ->
                    Surface(
                        onClick = { onSelectEmployeeScope(row.employeeName) },
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${row.employeeName} (${row.role.label})",
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = row.businessScopes.joinToString { it.label },
                                    color = PrimaryActionOrange,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Active: ${row.activeRequirementCount}", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text("Due Today: ${row.dueTodayTaskCount}", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = StatusCheckAvailability)
                                Text("Overdue: ${row.overdueTaskCount}", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = StatusNotAvailable)
                                Text("Visits: ${row.upcomingVisitCount}", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = StatusAvailable)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Leadrat Lead Review & Activation Sheet (Prompt 06).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeadratReviewQueueSheet(
    awaitingLeads: List<CrmLead>,
    onLeadReviewed: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var reviewingLead by remember { mutableStateOf<CrmLead?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Leadrat Imported Leads Review Queue (${awaitingLeads.size})", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Historical imports stay out of daily active work until explicitly activated with an assignee and follow-up date.", fontSize = 11.5.sp, color = SecondaryText)

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(awaitingLeads, key = { it.id }) { lead ->
                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(lead.name.ifBlank { "Imported Client" }, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text("Phone: ${lead.phone} • Imported Status: ${lead.status.ifBlank { "Unclassified" }}", color = SecondaryText, fontSize = 11.5.sp)
                            Text("Requirement: ${lead.requirement.ifBlank { "Not Mentioned" }}", color = TextPrimary, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { reviewingLead = lead },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = OnPrimaryActionColor
                                    ),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Review & Decide", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    reviewingLead?.let { target ->
        var decision by remember { mutableStateOf(LeadratReviewDecision.ACTIVATE) }
        var division by remember { mutableStateOf(CrmBusinessDivision.fromString(target.segment).let { if (it == CrmBusinessDivision.UNCLASSIFIED) CrmBusinessDivision.COMMERCIAL else it }) }
        var assignee by remember { mutableStateOf(target.assignedTo.ifBlank { "Sagar" }) }
        var followUpDate by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
        var note by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { reviewingLead = null },
            containerColor = SurfaceWhite,
            title = { Text("Review Imported Lead • ${target.name}", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(LeadratReviewDecision.ACTIVATE, LeadratReviewDecision.KEEP_HISTORICAL, LeadratReviewDecision.NEEDS_INFO).forEach { dec ->
                            FilterChip(
                                selected = decision == dec,
                                onClick = { decision = dec },
                                label = { Text(dec.label, fontSize = 10.5.sp) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(CrmBusinessDivision.COMMERCIAL, CrmBusinessDivision.RESIDENTIAL).forEach { div ->
                            FilterChip(
                                selected = division == div,
                                onClick = { division = div },
                                label = { Text(div.label, fontSize = 11.sp) }
                            )
                        }
                    }
                    OutlinedTextField(assignee, { assignee = it }, label = { Text("Assigned Employee") }, singleLine = true)
                    if (decision == LeadratReviewDecision.ACTIVATE) {
                        OutlinedTextField(followUpDate, { followUpDate = it }, label = { Text("Next Follow-up (YYYY-MM-DD)") }, singleLine = true)
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("Review Note") })
                    err?.let { Text(it, color = StatusNotAvailable, fontSize = 11.5.sp, fontWeight = FontWeight.Bold) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        CrmPipelineAndWorkloadEngine.applyLeadratReviewDecision(
                            context = context,
                            lead = target,
                            decision = decision,
                            businessDivision = division,
                            assignedEmployeeName = assignee,
                            nextFollowUpDate = followUpDate,
                            reviewNote = note
                        ).onSuccess {
                            reviewingLead = null
                            onLeadReviewed()
                        }.onFailure { e ->
                            err = e.message ?: "Unable to save review decision"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Save Decision", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { reviewingLead = null }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickFollowUpBottomSheet(
    lead: CrmLead,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val settings = remember { UserSettingsManager(context) }
    val defaultAssignee = settings.userProfile.value.username.ifBlank { "Unassigned" }

    var followUpDate by remember {
        mutableStateOf(lead.nextFollowUp.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) })
    }
    var followUpTime by remember {
        mutableStateOf(lead.nextFollowUpTime.ifBlank { lead.followUpTime.ifBlank { "11:00 AM" } })
    }
    var actionType by remember {
        mutableStateOf(lead.nextFollowUpAction.ifBlank { "Call Client" })
    }
    var assignedTo by remember {
        mutableStateOf(lead.assignedTo.ifBlank { defaultAssignee })
    }
    var notes by remember {
        mutableStateOf(cleanActionNotesForDisplay(lead.notes))
    }
    var completed by remember { mutableStateOf(lead.followUpCompleted) }

    val timeSlots = remember { listOf("11:00 AM", "2:00 PM", "4:30 PM", "6:00 PM", "Custom Clock...") }
    val actionOptions = remember { listOf("Call Client", "WhatsApp Follow-up", "Site Visit", "Send Proposal") }

    val cal = remember { Calendar.getInstance() }
    val isoFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val todayStr = remember { isoFmt.format(cal.time) }
    val tomorrowStr = remember {
        (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.let { isoFmt.format(it.time) }
    }
    val plus3Str = remember {
        (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 3) }.let { isoFmt.format(it.time) }
    }

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
        ) {
            // Compact body fitting comfortably on 1 screen
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Schedule Follow-up",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${lead.displayClientTitle()} • ${lead.phone.ifBlank { lead.id }}",
                            color = SecondaryText,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        color = SurfaceMuted,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = lead.id,
                            color = SecondaryText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                // Row 1: 2-column dropdowns (Action Type | Assigned To)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactDropdownField(
                        label = "Action Type",
                        selectedValue = actionType,
                        options = actionOptions,
                        onValueSelected = { actionType = it },
                        modifier = Modifier.weight(1f),
                        allowCustom = false
                    )
                    CompactDropdownField(
                        label = "Assigned To",
                        selectedValue = assignedTo,
                        options = TlcStandardOptions.teamEmployees,
                        onValueSelected = { assignedTo = it },
                        modifier = Modifier.weight(1f),
                        allowCustom = true
                    )
                }

                // Row 2a: Quick Date Chips [Today] [Tomorrow] [+3 Days]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Today" to todayStr, "Tomorrow" to tomorrowStr, "+3 Days" to plus3Str).forEach { (label, dateVal) ->
                        val selected = followUpDate == dateVal
                        FilterChip(
                            selected = selected,
                            onClick = { followUpDate = dateVal },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SelectedSurfaceContainer,
                                selectedLabelColor = PrimaryActionOrange
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                        )
                    }
                }

                // Row 2b: Side-by-side Date Picker & Time Dropdown / Custom Clock
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Date",
                            color = SecondaryText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                val pickCal = Calendar.getInstance()
                                runCatching { isoFmt.parse(followUpDate) }.getOrNull()?.let { pickCal.time = it }
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d ->
                                        followUpDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                                    },
                                    pickCal.get(Calendar.YEAR),
                                    pickCal.get(Calendar.MONTH),
                                    pickCal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = SurfaceWhite,
                                contentColor = TextPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = "Pick Date", tint = PrimaryActionOrange, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = followUpDate.ifBlank { "Pick Date" },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Time",
                            color = SecondaryText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                val nowCal = Calendar.getInstance()
                                TimePickerDialog(
                                    context,
                                    { _, h, m ->
                                        val tmpCal = Calendar.getInstance().apply {
                                            set(Calendar.HOUR_OF_DAY, h)
                                            set(Calendar.MINUTE, m)
                                        }
                                        followUpTime = SimpleDateFormat("h:mm a", Locale.US).format(tmpCal.time)
                                    },
                                    nowCal.get(Calendar.HOUR_OF_DAY),
                                    nowCal.get(Calendar.MINUTE),
                                    DateFormat.is24HourFormat(context)
                                ).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = SurfaceWhite,
                                contentColor = TextPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("quick_followup_time_btn")
                        ) {
                            Icon(Icons.Default.AccessTime, contentDescription = "Pick Time", tint = PrimaryActionOrange, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = followUpTime.ifBlank { "Pick Time" },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Row 3: Compact Follow-up Notes (max 2 lines) + Mark Completed
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Follow-up Notes", fontSize = 12.sp) },
                    placeholder = { Text("Short next action note...", fontSize = 12.sp) },
                    maxLines = 2,
                    minLines = 2,
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
                        .clickable { completed = !completed },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = completed,
                        onCheckedChange = { completed = it },
                        colors = CheckboxDefaults.colors(checkedColor = PrimaryActionOrange, checkmarkColor = OnPrimaryActionColor)
                    )
                    Text(
                        text = "Mark Follow-up Completed",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
            }

            // Permanently Pinned Bottom Action Row
            Surface(
                color = SurfaceWhite,
                border = BorderStroke(1.dp, BorderGrey),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("quick_followup_cancel_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Button(
                        onClick = {
                            onSave(followUpDate.trim(), followUpTime.trim(), actionType, assignedTo.trim(), notes.trim(), completed)
                        },
                        modifier = Modifier
                            .weight(1.4f)
                            .heightIn(min = 48.dp)
                            .testTag("quick_followup_save_btn"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        )
                    ) {
                        Text("Save Follow-up", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddEditLeadScreen(
    initial: CrmLead?,
    onDismiss: () -> Unit,
    onBatchSaveLeads: ((List<CrmLead>) -> Unit)? = null,
    onSave: (CrmLead, Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings = remember { UserSettingsManager(context) }
    val profile = settings.userProfile.collectAsState().value
    val defaultEmployee = profile.username.ifBlank { "Pawan Patil" }
    val isoFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    val initialClientTypeMode = remember(initial) {
        when {
            initial?.isOwnerLead() == true -> "Owner"
            initial?.isBrokerLead() == true -> "Broker / CP"
            else -> "Direct Client"
        }
    }
    val initialDivision = remember(initial) {
        when {
            initial?.segment?.equals("Residential", ignoreCase = true) == true -> "Residential"
            else -> "Commercial"
        }
    }
    val initialTransaction = remember(initial) {
        val rawTx = initial?.requirementType.orEmpty().ifBlank { initial?.requirement.orEmpty() }
        when {
            rawTx.contains("Pre-Leased", ignoreCase = true) || rawTx.contains("Pre Leased", ignoreCase = true) -> "Pre-Leased"
            rawTx.contains("Under Construction", ignoreCase = true) -> "Under Construction"
            rawTx.contains("Buy", ignoreCase = true) || rawTx.contains("Purchase", ignoreCase = true) || rawTx.contains("Sale", ignoreCase = true) -> "Purchase"
            initialDivision == "Residential" -> "Rent"
            else -> "Lease"
        }
    }
    val initialPropType = remember(initial, initialDivision) {
        val raw = initial?.propertyType?.takeIf { it.isNotBlank() } ?: initial?.requirement.orEmpty()
        when {
            raw.contains("Showroom", true) -> "Showroom"
            raw.contains("Restaurant", true) || raw.contains("F&B", true) -> "Restaurant / F&B"
            raw.contains("Shop", true) -> "Shop"
            raw.contains("Warehouse", true) -> "Warehouse"
            raw.contains("Commercial Land", true) -> "Commercial Land"
            raw.contains("Office", true) -> "Office"
            raw.contains("Villa", true) || raw.contains("Bungalow", true) -> "Villa / Bungalow"
            raw.contains("Penthouse", true) -> "Penthouse"
            raw.contains("Plot", true) || raw.contains("Land", true) -> "Plot / Land"
            raw.contains("Apartment", true) || raw.contains("Flat", true) || raw.contains("BHK", true) -> "Apartment / Flat"
            initialDivision == "Residential" -> "Apartment / Flat"
            else -> "Office"
        }
    }
    val initialSubOption = remember(initial, initialDivision, initialTransaction, initialPropType) {
        val req = "${initial?.furnishing.orEmpty()} ${initial?.requirement.orEmpty()}"
        when {
            initialTransaction == "Pre-Leased" ->
                TlcStandardOptions.crmPreLeasedYieldOptions.firstOrNull { req.contains(it, true) } ?: "7%–8%"
            initialPropType in listOf("Shop", "Showroom", "Restaurant / F&B") ->
                TlcStandardOptions.crmRetailFloorSpecOptions.firstOrNull { req.contains(it.substringBefore("/").trim(), true) } ?: "Ground / High-Street"
            initialDivision == "Residential" && initialPropType in listOf("Apartment / Flat", "Villa / Bungalow", "Penthouse") ->
                Regex("""(\d+(?:\.\d+)?)\s*BHK""", RegexOption.IGNORE_CASE).find(req)?.groupValues?.getOrNull(1)?.let { "$it BHK" }
                    ?: "3 BHK"
            initialPropType == "Office" ->
                TlcStandardOptions.crmOfficeFurnishingSubOptions.firstOrNull { req.contains(it, true) }
                    ?: initial?.furnishing?.takeIf { it.isNotBlank() && it != "Not Mentioned" }
                    ?: "Warm Shell"
            else -> initial?.furnishing?.takeIf { it.isNotBlank() } ?: "Not Mentioned"
        }
    }

    var clientTypeMode by remember(initial) { mutableStateOf(initialClientTypeMode) }
    val isBrokerClient = clientTypeMode == "Broker / CP"
    val isOwnerClient = clientTypeMode == "Owner"

    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var brokerFirmName by remember(initial) { mutableStateOf(initial?.companyName.orEmpty()) }
    var phone by remember(initial) { mutableStateOf(initial?.phone.orEmpty()) }
    var segment by remember(initial) { mutableStateOf(initialDivision) }
    var transactionType by remember(initial) { mutableStateOf(initialTransaction) }
    var propertyType by remember(initial) { mutableStateOf(initialPropType) }
    var subOption by remember(initial) { mutableStateOf(initialSubOption) }
    var location by remember(initial) { mutableStateOf(initial?.location.orEmpty()) }

    val teamEmployeeOptions = remember(context) {
        SheetsRepository.getSyncedActiveEmployeeNames(context) + listOf("Unassigned")
    }

    var localAddedProperties by remember { mutableStateOf<List<PropertyItem>>(emptyList()) }
    val allInventoryProperties = remember(context, localAddedProperties) {
        val loaded = runCatching { PropertiesLocalDatabase.loadProperties(context) }.getOrDefault(emptyList())
        (localAddedProperties + loaded).distinctBy { it.id }
    }
    val propertyPickerOptions = remember(allInventoryProperties) {
        allInventoryProperties.map { p ->
            val code = p.propertyCode.ifBlank { p.id }
            val unitPart = if (p.unitNumber.isNotBlank()) " Unit ${p.unitNumber}" else ""
            val locPart = if (p.location.isNotBlank()) " (${p.location})" else ""
            "$code • ${p.projectName}$unitPart$locPart".trim()
        }
    }
    var linkedPropertyCode by remember(initial) {
        mutableStateOf(
            initial?.matchedPropertyCodes?.split(",", ";")
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
        )
    }
    val linkedPropertyOptionDisplay = remember(linkedPropertyCode, propertyPickerOptions) {
        if (linkedPropertyCode.isBlank()) "" else {
            propertyPickerOptions.firstOrNull {
                it.substringBefore("•").trim().equals(linkedPropertyCode, ignoreCase = true) ||
                    it.contains(linkedPropertyCode, ignoreCase = true)
            } ?: linkedPropertyCode
        }
    }
    var ownerStage by remember(initial) {
        mutableStateOf(
            TlcStandardOptions.ownerStages.firstOrNull { it.equals(initial?.status, ignoreCase = true) }
                ?: TlcStandardOptions.ownerStages.first()
        )
    }
    var showQuickAddPropertyDialog by remember { mutableStateOf(false) }

    val activeSizePresets = remember(propertyType) {
        if (propertyType.contains("Land", ignoreCase = true) || propertyType.contains("Plot", ignoreCase = true)) {
            TlcStandardOptions.crmPlotSizePresets
        } else {
            TlcStandardOptions.crmCarpetSizePresets
        }
    }
    val isRentTx = transactionType.equals("Lease", ignoreCase = true) || transactionType.equals("Rent", ignoreCase = true)
    val activeBudgetPresets = remember(isRentTx) {
        if (isRentTx) TlcStandardOptions.crmLeaseBudgetPresets else TlcStandardOptions.crmSaleBudgetPresets
    }

    var selectedSizePreset by remember(initial) {
        val existingArea = initial?.carpetAreaRange.orEmpty().trim()
        val matched = activeSizePresets.firstOrNull { it.label.equals(existingArea, ignoreCase = true) }
        mutableStateOf(
            when {
                matched != null -> matched.label
                existingArea.isNotBlank() -> "Custom"
                else -> ""
            }
        )
    }
    var carpetAreaRange by remember(initial) { mutableStateOf(initial?.carpetAreaRange.orEmpty()) }
    var customMinSqFt by remember(initial) { mutableStateOf(initial?.minCarpetSqFt.orEmpty()) }
    var customMaxSqFt by remember(initial) { mutableStateOf(initial?.maxCarpetSqFt.orEmpty()) }

    var selectedBudgetPreset by remember(initial) {
        val existingBudget = initial?.budget.orEmpty().trim()
        val matched = activeBudgetPresets.firstOrNull { it.label.equals(existingBudget, ignoreCase = true) }
        mutableStateOf(
            when {
                matched != null -> matched.label
                existingBudget.isNotBlank() -> "Custom"
                else -> ""
            }
        )
    }
    var budget by remember(initial) { mutableStateOf(initial?.budget.orEmpty()) }
    var customMinBudgetInr by remember(initial) {
        mutableStateOf(if (isRentTx) initial?.minRentInr.orEmpty() else initial?.minBudgetInr.orEmpty())
    }
    var customMaxBudgetInr by remember(initial) {
        mutableStateOf(if (isRentTx) initial?.maxRentInr.orEmpty() else initial?.maxBudgetInr.orEmpty())
    }

    var possessionTimeline by remember(initial) { mutableStateOf(initial?.possessionTimeline.orEmpty()) }
    var status by remember(initial) { mutableStateOf(initial?.status?.takeIf { it.isNotBlank() } ?: "New Lead") }
    var assignedTo by remember(initial) {
        val initEmp = initial?.assignedTo.orEmpty().trim()
        mutableStateOf(if (initEmp.isNotBlank()) initEmp else defaultEmployee)
    }
    var followUp by remember(initial) { mutableStateOf(initial?.nextFollowUp?.ifBlank { isoFmt.format(Date()) } ?: isoFmt.format(Date())) }
    var followUpTime by remember(initial) { mutableStateOf(initial?.nextFollowUpTime.orEmpty().ifBlank { initial?.followUpTime.orEmpty().ifBlank { "11:00 AM" } }) }
    var actionNotes by remember(initial) { mutableStateOf(cleanActionNotesForDisplay(initial?.notes.orEmpty().ifBlank { initial?.clientNotes.orEmpty() })) }

    // AI Paste Sheet state
    var showAiPasteSheet by remember { mutableStateOf(false) }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                ContactUtils.extractContactFromUri(context, uri)?.let { (pickedName, pickedPhone) ->
                    if (pickedName.isNotBlank()) name = pickedName
                    if (pickedPhone.isNotBlank()) phone = pickedPhone
                }
            }
        }
    }

    val existingLeads = remember { readCrmLeads(context) }
    val phoneClean = phone.filter { it.isDigit() }
    val duplicateClient = remember(phoneClean, existingLeads) {
        if (phoneClean.length >= 10) {
            existingLeads.find {
                it.phone.filter { ch -> ch.isDigit() }.takeLast(10) == phoneClean.takeLast(10) &&
                    (initial == null || it.id != initial.id)
            }
        } else null
    }

    val propertyTypeOptions = remember(segment) {
        if (segment.equals("Residential", ignoreCase = true)) {
            TlcStandardOptions.crmResidentialPropertyTypes
        } else {
            TlcStandardOptions.crmCommercialPropertyTypes
        }
    }
    val transactionOptions = remember(segment) {
        if (segment.equals("Residential", ignoreCase = true)) {
            TlcStandardOptions.crmResidentialTransactions
        } else {
            TlcStandardOptions.crmCommercialTransactions
        }
    }
    val subOptionConfig = remember(segment, propertyType, transactionType) {
        when {
            transactionType == "Pre-Leased" -> "Target Yield" to TlcStandardOptions.crmPreLeasedYieldOptions
            propertyType in listOf("Shop", "Showroom", "Restaurant / F&B") -> "Floor / Spec" to TlcStandardOptions.crmRetailFloorSpecOptions
            propertyType == "Office" -> "Furnishing" to TlcStandardOptions.crmOfficeFurnishingSubOptions
            segment.equals("Residential", true) && propertyType in listOf("Apartment / Flat", "Villa / Bungalow", "Penthouse") ->
                "Configuration" to TlcStandardOptions.crmResidentialBhkOptions
            else -> "Spec / Furnishing" to TlcStandardOptions.furnishingOptions
        }
    }

    val pipelineStages = remember {
        listOf(
            "New Lead", "Contacted", "Exploring Market", "Details Shared",
            "Site Visit Pending", "Site Visit Done", "Negotiation",
            "Closed Won", "Closed Lost"
        )
    }

    val composedRequirement = remember(transactionType, propertyType, subOption, carpetAreaRange, segment) {
        val parts = mutableListOf<String>()
        parts.add(transactionType)
        if (segment == "Residential" && subOption.contains("BHK", ignoreCase = true)) {
            parts.add(subOption)
            parts.add(propertyType)
        } else {
            parts.add(propertyType)
            if (subOption.isNotBlank() && subOption != "Not Mentioned") {
                parts.add("($subOption)")
            }
        }
        if (carpetAreaRange.isNotBlank()) {
            parts.add("• $carpetAreaRange")
        }
        parts.joinToString(" ")
    }

    val resolvedMinCarpet = customMinSqFt.ifBlank {
        activeSizePresets.firstOrNull { it.label == selectedSizePreset }?.minValue?.toString().orEmpty()
    }
    val resolvedMaxCarpet = customMaxSqFt.ifBlank {
        activeSizePresets.firstOrNull { it.label == selectedSizePreset }?.maxValue?.toString().orEmpty()
    }

    val selectedBudPresetObj = activeBudgetPresets.firstOrNull { it.label == selectedBudgetPreset && it.label != "Custom" }
    val parsedManualBudget = CalculationUtils.parseCurrencyInput(budget)?.toLong()?.toString().orEmpty()
    val resolvedMinBudget = selectedBudPresetObj?.minValue?.toString()
        ?: customMinBudgetInr.ifBlank { parsedManualBudget }
    val resolvedMaxBudget = selectedBudPresetObj?.maxValue?.toString()
        ?: customMaxBudgetInr.ifBlank { parsedManualBudget }
    val resolvedBudgetDisplay = budget.ifBlank {
        when {
            resolvedMaxBudget.isNotBlank() -> resolvedMaxBudget
            resolvedMinBudget.isNotBlank() -> resolvedMinBudget
            else -> ""
        }
    }
    val budgetPreview = remember(resolvedBudgetDisplay, isRentTx, resolvedMinBudget, resolvedMaxBudget) {
        CalculationUtils.formatCrmBudgetDisplay(
            rawBudget = resolvedBudgetDisplay,
            transactionOrRequirementHint = if (isOwnerClient) "" else transactionType,
            minInr = resolvedMinBudget,
            maxInr = resolvedMaxBudget
        )
    }

    val matchedLinkedProperty = remember(linkedPropertyCode, allInventoryProperties) {
        if (linkedPropertyCode.isBlank()) null else allInventoryProperties.firstOrNull {
            it.propertyCode.equals(linkedPropertyCode, ignoreCase = true) ||
                it.id.equals(linkedPropertyCode, ignoreCase = true)
        }
    }

    val draftLead = if (isOwnerClient) {
        val ownerSegment = matchedLinkedProperty?.let { if (it.isCommercial()) "Commercial" else "Residential" } ?: segment
        val ownerLocation = matchedLinkedProperty?.location?.takeIf { it.isNotBlank() } ?: location.ifBlank { "Pune" }
        val ownerPropType = matchedLinkedProperty?.propertyType?.takeIf { it.isNotBlank() } ?: propertyType
        val ownerReqText = buildString {
            append("Owner Listing")
            if (linkedPropertyCode.isNotBlank()) append(" • $linkedPropertyCode")
            if (matchedLinkedProperty != null) append(" (${matchedLinkedProperty.projectName})")
        }
        val cleanOwnerBudget = resolvedBudgetDisplay.trim()
        val parsedOwnerBudget = CalculationUtils.parseCurrencyInput(cleanOwnerBudget)?.toLong()?.toString().orEmpty()
        val ownerCarpet = carpetAreaRange.trim()
            .ifBlank { matchedLinkedProperty?.carpetAreaSqFt?.takeIf { it > 0 }?.let { CalculationUtils.formatNumber(it) }.orEmpty() }
        CrmLead(
            id = initial?.id ?: "CRM-${System.currentTimeMillis().toString().takeLast(6)}",
            name = name.trim(),
            phone = phone.trim(),
            segment = ownerSegment,
            requirement = ownerReqText,
            location = ownerLocation,
            budget = cleanOwnerBudget.ifBlank { "Owner Price Pending" },
            status = ownerStage,
            nextFollowUp = followUp.trim(),
            notes = actionNotes.trim(),
            assignedTo = assignedTo.trim().ifBlank { defaultEmployee },
            followUpTime = followUpTime.trim(),
            carpetAreaRange = ownerCarpet,
            nextFollowUpTime = followUpTime.trim(),
            nextFollowUpAction = initial?.nextFollowUpAction ?: "Call Owner",
            followUpCompleted = initial?.followUpCompleted ?: false,
            areaSqFt = ownerCarpet,
            possessionTimeline = possessionTimeline.trim(),
            companyName = "",
            createdDate = initial?.createdDate?.ifBlank { isoFmt.format(Date()) } ?: isoFmt.format(Date()),
            alternatePhone = initial?.alternatePhone.orEmpty(),
            clientType = "Owner",
            requirementType = matchedLinkedProperty?.transactionType ?: "Listing",
            propertyType = ownerPropType,
            minCarpetSqFt = resolvedMinCarpet,
            maxCarpetSqFt = resolvedMaxCarpet,
            minBudgetInr = parsedOwnerBudget,
            maxBudgetInr = parsedOwnerBudget,
            minRentInr = initial?.minRentInr.orEmpty(),
            maxRentInr = initial?.maxRentInr.orEmpty(),
            furnishing = matchedLinkedProperty?.furnishing?.label ?: "",
            leadSource = "Direct Owner / Landlord",
            priority = initial?.priority.orEmpty(),
            lastContactDate = initial?.lastContactDate.orEmpty(),
            lastContactMode = initial?.lastContactMode.orEmpty(),
            lastContactOutcome = initial?.lastContactOutcome.orEmpty(),
            matchedPropertyCodes = linkedPropertyCode.trim(),
            siteVisitDate = initial?.siteVisitDate.orEmpty(),
            siteVisitStatus = initial?.siteVisitStatus.orEmpty(),
            clientNotes = actionNotes.trim(),
            consentStatus = initial?.consentStatus.orEmpty(),
            createdBy = initial?.createdBy?.ifBlank { defaultEmployee } ?: defaultEmployee,
            legacyLeadId = initial?.legacyLeadId.orEmpty(),
            verificationStatus = initial?.verificationStatus.orEmpty(),
            duplicatePhoneCheck = if (duplicateClient != null) "Duplicate Phone (${duplicateClient.id})" else initial?.duplicatePhoneCheck.orEmpty()
        )
    } else {
        CrmLead(
            id = initial?.id ?: "CRM-${System.currentTimeMillis().toString().takeLast(6)}",
            name = name.trim(),
            phone = phone.trim(),
            segment = segment,
            requirement = composedRequirement,
            location = location.trim(),
            budget = resolvedBudgetDisplay.trim(),
            status = status,
            nextFollowUp = followUp.trim(),
            notes = actionNotes.trim(),
            assignedTo = assignedTo.trim().ifBlank { defaultEmployee },
            followUpTime = followUpTime.trim(),
            carpetAreaRange = carpetAreaRange.trim(),
            nextFollowUpTime = followUpTime.trim(),
            nextFollowUpAction = initial?.nextFollowUpAction ?: "Call Client",
            followUpCompleted = initial?.followUpCompleted ?: false,
            areaSqFt = carpetAreaRange.trim(),
            possessionTimeline = possessionTimeline.trim(),
            companyName = brokerFirmName.trim(),
            createdDate = initial?.createdDate?.ifBlank { isoFmt.format(Date()) } ?: isoFmt.format(Date()),
            alternatePhone = initial?.alternatePhone.orEmpty(),
            clientType = if (isBrokerClient) "Broker / CP" else "Direct Client",
            requirementType = transactionType,
            propertyType = propertyType,
            minCarpetSqFt = resolvedMinCarpet,
            maxCarpetSqFt = resolvedMaxCarpet,
            minBudgetInr = if (!isRentTx) resolvedMinBudget else initial?.minBudgetInr.orEmpty(),
            maxBudgetInr = if (!isRentTx) resolvedMaxBudget else initial?.maxBudgetInr.orEmpty(),
            minRentInr = if (isRentTx) resolvedMinBudget else initial?.minRentInr.orEmpty(),
            maxRentInr = if (isRentTx) resolvedMaxBudget else initial?.maxRentInr.orEmpty(),
            furnishing = subOption,
            leadSource = if (isBrokerClient) "Channel Partner / Broker" else (initial?.leadSource.orEmpty()),
            priority = initial?.priority.orEmpty(),
            lastContactDate = initial?.lastContactDate.orEmpty(),
            lastContactMode = initial?.lastContactMode.orEmpty(),
            lastContactOutcome = initial?.lastContactOutcome.orEmpty(),
            matchedPropertyCodes = initial?.matchedPropertyCodes.orEmpty(),
            siteVisitDate = initial?.siteVisitDate.orEmpty(),
            siteVisitStatus = initial?.siteVisitStatus.orEmpty(),
            clientNotes = actionNotes.trim().ifBlank { initial?.clientNotes.orEmpty() },
            consentStatus = initial?.consentStatus.orEmpty(),
            createdBy = initial?.createdBy?.ifBlank { defaultEmployee } ?: defaultEmployee,
            legacyLeadId = initial?.legacyLeadId.orEmpty(),
            verificationStatus = initial?.verificationStatus.orEmpty(),
            duplicatePhoneCheck = if (duplicateClient != null) "Duplicate Phone (${duplicateClient.id})" else initial?.duplicatePhoneCheck.orEmpty()
        )
    }

    val missingLiveFields = if (isOwnerClient) {
        buildList {
            if (name.isBlank()) add("Owner Name")
            if (!com.example.inventory.InventoryQuality.isMeaningfulPhone(phone)) add("Valid Phone (10+ digits)")
            if (followUp.isBlank()) add("Follow-up Date")
        }
    } else {
        CrmPipelineAndWorkloadEngine.minimumRequiredMissingFields(draftLead)
    }

    fun syncLinkedOwnerToInventory(ownerLead: CrmLead) {
        if (!isOwnerClient || linkedPropertyCode.isBlank()) return
        val existingProps = PropertiesLocalDatabase.loadProperties(context)
        val idx = existingProps.indexOfFirst {
            it.propertyCode.equals(linkedPropertyCode, ignoreCase = true) ||
                it.id.equals(linkedPropertyCode, ignoreCase = true)
        }
        if (idx >= 0) {
            val target = existingProps[idx]
            val updatedProp = target.copy(
                ownerName = ownerLead.name.ifBlank { target.ownerName },
                ownerContact = ownerLead.phone.ifBlank { target.ownerContact },
                isSyncedToSheet = false
            )
            val nextList = existingProps.toMutableList().apply { set(idx, updatedProp) }
            PropertiesLocalDatabase.saveProperties(context, nextList)
        }
    }

    BackHandler { onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("add_edit_lead_screen"),
            color = BackgroundSoft
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // Sticky Top Bar: Back + Title + [✨ AI Paste] + [Contacts]
                Surface(
                    color = SurfaceWhite,
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            val screenTitle = when {
                                isOwnerClient && initial == null -> "Add Owner"
                                isOwnerClient -> "Edit Owner"
                                initial == null -> "Add Client"
                                else -> "Edit Client"
                            }
                            Text(
                                text = screenTitle,
                                color = TextPrimary,
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = if (missingLiveFields.isEmpty()) "Ready for Live"
                                else "Need: ${missingLiveFields.joinToString(", ")}",
                                color = if (missingLiveFields.isEmpty()) StatusSuccessGreen else StatusDangerRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!isOwnerClient) {
                            OutlinedButton(
                                onClick = { showAiPasteSheet = true },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, PrimaryActionOrange),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = SelectedSurfaceContainer,
                                    contentColor = PrimaryActionOrange
                                ),
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 40.dp)
                                    .testTag("lead_ai_paste_btn")
                            ) {
                                Text(
                                    text = "✨ AI Paste",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val pickIntent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                                contactPickerLauncher.launch(pickIntent)
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceMuted)
                                .testTag("pick_from_contacts_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContactPhone,
                                contentDescription = "Pick from Contacts",
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Scrollable 2-Column Compact Form Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 3-Way Client Type Toggle: [ Direct Client ] | [ 🤝 Broker / CP ] | [ 🏠 Owner ]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceMuted)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            Triple("Direct Client", "Direct Client", "lead_type_direct_btn"),
                            Triple("Broker / CP", "🤝 Broker / CP", "lead_type_broker_btn"),
                            Triple("Owner", "🏠 Owner", "lead_type_owner_btn")
                        ).forEach { (modeKey, modeLabel, tag) ->
                            val selected = clientTypeMode == modeKey
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) PrimaryActionOrange else Color.Transparent)
                                    .clickable { clientTypeMode = modeKey }
                                    .testTag(tag),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = modeLabel,
                                    color = if (selected) OnPrimaryActionColor else TextPrimary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (duplicateClient != null) {
                        Surface(
                            color = StatusNotAvailable.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, StatusNotAvailable)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusNotAvailable, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Duplicate phone registered under '${duplicateClient.displayClientTitle()}' (${duplicateClient.status})",
                                        color = TextPrimary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        val merged = draftLead.copy(
                                            id = duplicateClient.id,
                                            createdDate = duplicateClient.createdDate.ifBlank { draftLead.createdDate },
                                            matchedPropertyCodes = draftLead.matchedPropertyCodes.ifBlank { duplicateClient.matchedPropertyCodes },
                                            notes = listOf(draftLead.notes, duplicateClient.notes)
                                                .filter { it.isNotBlank() }
                                                .distinct()
                                                .joinToString(" | "),
                                            clientNotes = listOf(draftLead.clientNotes, duplicateClient.clientNotes)
                                                .filter { it.isNotBlank() }
                                                .distinct()
                                                .joinToString(" | "),
                                            verificationStatus = if (missingLiveFields.isEmpty()) "Verified Live" else "Draft / Archived"
                                        )
                                        syncLinkedOwnerToInventory(merged)
                                        onSave(merged, missingLiveFields.isEmpty())
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, PrimaryActionOrange),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = SurfaceWhite,
                                        contentColor = PrimaryActionOrange
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .heightIn(min = 40.dp)
                                        .testTag("lead_merge_existing_btn")
                                ) {
                                    Text(
                                        text = "Merge & Update Existing",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    // Row 1: Client / Broker / Owner Name * | Mobile * (10-digit)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = {
                                Text(
                                    text = when {
                                        isOwnerClient -> "Owner Name *"
                                        isBrokerClient -> "Broker / Client Name *"
                                        else -> "Client Name *"
                                    },
                                    fontSize = 11.5.sp
                                )
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = SurfaceWhite,
                                unfocusedContainerColor = SurfaceWhite,
                                focusedBorderColor = PrimaryActionOrange,
                                unfocusedBorderColor = BorderGrey
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lead_name_input")
                        )
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Mobile *", fontSize = 11.5.sp) },
                            placeholder = { Text("10-digit", fontSize = 11.5.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = SurfaceWhite,
                                unfocusedContainerColor = SurfaceWhite,
                                focusedBorderColor = PrimaryActionOrange,
                                unfocusedBorderColor = BorderGrey
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lead_mobile_input")
                        )
                    }

                    if (isOwnerClient) {
                        // Owner Mode Row 2: Linked Property (Searchable + Add New) | Owner Stage
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                SearchableDropdownField(
                                    label = "Linked Property",
                                    selectedValue = linkedPropertyOptionDisplay,
                                    options = propertyPickerOptions,
                                    onValueSelected = { chosen ->
                                        val code = chosen.substringBefore("•").trim()
                                        linkedPropertyCode = code
                                        val picked = allInventoryProperties.firstOrNull {
                                            it.propertyCode.equals(code, ignoreCase = true) ||
                                                it.id.equals(code, ignoreCase = true)
                                        }
                                        if (picked != null) {
                                            if (carpetAreaRange.isBlank() && picked.carpetAreaSqFt > 0.0) {
                                                carpetAreaRange = picked.carpetAreaSqFt.toInt().toString()
                                            }
                                            if (budget.isBlank()) {
                                                val amt = CalculationUtils.primaryAmountForProperty(picked)
                                                if (amt > 0.0) budget = amt.toLong().toString()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    allowAddNew = true
                                )
                                TextButton(
                                    onClick = { showQuickAddPropertyDialog = true },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("owner_add_new_property_btn")
                                ) {
                                    Text(
                                        text = "+ Add New Property",
                                        color = PrimaryActionOrange,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            CompactDropdownField(
                                label = "Owner Stage",
                                selectedValue = ownerStage,
                                options = TlcStandardOptions.ownerStages,
                                onValueSelected = { ownerStage = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                        }

                        // Owner Mode Row 3: Expected Rent / Price (₹) | Carpet Area (sq.ft)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = budget,
                                    onValueChange = { budget = it },
                                    label = { Text("Expected Rent / Price (₹)", fontSize = 11.sp) },
                                    placeholder = { Text("e.g. 120000 or 2.5 Cr", fontSize = 11.sp) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = SurfaceWhite,
                                        unfocusedContainerColor = SurfaceWhite,
                                        focusedBorderColor = PrimaryActionOrange,
                                        unfocusedBorderColor = BorderGrey
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("owner_expected_price_input")
                                )
                                if (budgetPreview.isNotBlank()) {
                                    Text(
                                        text = "≈ $budgetPreview",
                                        color = PrimaryActionOrange,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = carpetAreaRange,
                                onValueChange = { carpetAreaRange = it },
                                label = { Text("Carpet Area (sq.ft)", fontSize = 11.sp) },
                                placeholder = { Text("e.g. 1450", fontSize = 11.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = SurfaceWhite,
                                    unfocusedContainerColor = SurfaceWhite,
                                    focusedBorderColor = PrimaryActionOrange,
                                    unfocusedBorderColor = BorderGrey
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("owner_carpet_area_input")
                            )
                        }

                        // Owner Mode Row 4: Assigned To (full width above Date & Time)
                        CompactDropdownField(
                            label = "Assigned To",
                            selectedValue = assignedTo,
                            options = teamEmployeeOptions,
                            onValueSelected = { assignedTo = it },
                            modifier = Modifier.fillMaxWidth(),
                            allowCustom = true
                        )
                    } else {
                        if (isBrokerClient) {
                            OutlinedTextField(
                                value = brokerFirmName,
                                onValueChange = { brokerFirmName = it },
                                label = { Text("Broker Firm / Agency (Optional)", fontSize = 11.5.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = SurfaceWhite,
                                    unfocusedContainerColor = SurfaceWhite,
                                    focusedBorderColor = PrimaryActionOrange,
                                    unfocusedBorderColor = BorderGrey
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("lead_broker_firm_input")
                            )
                        }

                        // Row 2: Division | Property Type (Cascading)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Division",
                                selectedValue = segment,
                                options = TlcStandardOptions.crmDivisions,
                                onValueSelected = { newDiv ->
                                    segment = newDiv
                                    if (newDiv == "Residential") {
                                        if (propertyType !in TlcStandardOptions.crmResidentialPropertyTypes) {
                                            propertyType = "Apartment / Flat"
                                        }
                                        if (transactionType !in TlcStandardOptions.crmResidentialTransactions) {
                                            transactionType = "Rent"
                                        }
                                        subOption = "3 BHK"
                                    } else {
                                        if (propertyType !in TlcStandardOptions.crmCommercialPropertyTypes) {
                                            propertyType = "Office"
                                        }
                                        if (transactionType !in TlcStandardOptions.crmCommercialTransactions) {
                                            transactionType = "Lease"
                                        }
                                        subOption = "Warm Shell"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                            CompactDropdownField(
                                label = "Property Type",
                                selectedValue = propertyType,
                                options = propertyTypeOptions,
                                onValueSelected = { chosenType ->
                                    propertyType = chosenType
                                    subOption = when {
                                        transactionType == "Pre-Leased" -> "7%–8%"
                                        chosenType in listOf("Shop", "Showroom", "Restaurant / F&B") -> "Ground / High-Street"
                                        chosenType == "Office" -> "Warm Shell"
                                        segment == "Residential" && chosenType in listOf("Apartment / Flat", "Villa / Bungalow", "Penthouse") -> "3 BHK"
                                        else -> "Not Mentioned"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )
                        }

                        // Row 3: Transaction | Dynamic Smart Sub-Option
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Transaction",
                                selectedValue = transactionType,
                                options = transactionOptions,
                                onValueSelected = { newTx ->
                                    val prevRent = transactionType.equals("Lease", true) || transactionType.equals("Rent", true)
                                    val nextRent = newTx.equals("Lease", true) || newTx.equals("Rent", true)
                                    transactionType = newTx
                                    if (newTx == "Pre-Leased") {
                                        subOption = "7%–8%"
                                    } else if (subOption in TlcStandardOptions.crmPreLeasedYieldOptions) {
                                        subOption = if (propertyType == "Office") "Warm Shell" else "Not Mentioned"
                                    }
                                    if (prevRent != nextRent && selectedBudgetPreset != "Custom") {
                                        selectedBudgetPreset = ""
                                        budget = ""
                                        customMinBudgetInr = ""
                                        customMaxBudgetInr = ""
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                            CompactDropdownField(
                                label = subOptionConfig.first,
                                selectedValue = subOption,
                                options = subOptionConfig.second,
                                onValueSelected = { subOption = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )
                        }

                        // Row 4: Size Preset | Budget Preset (2-Column Grid)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val sizeLabel = if (propertyType.contains("Land", true) || propertyType.contains("Plot", true)) {
                                "Plot Size"
                            } else {
                                "Carpet Size"
                            }
                            CompactDropdownField(
                                label = sizeLabel,
                                selectedValue = selectedSizePreset.ifBlank { carpetAreaRange },
                                options = activeSizePresets.map { it.label },
                                onValueSelected = { chosen ->
                                    selectedSizePreset = chosen
                                    if (chosen != "Custom") {
                                        val presetObj = activeSizePresets.firstOrNull { it.label == chosen }
                                        carpetAreaRange = chosen
                                        customMinSqFt = presetObj?.minValue?.toString().orEmpty()
                                        customMaxSqFt = presetObj?.maxValue?.toString().orEmpty()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                            CompactDropdownField(
                                label = if (isRentTx) "Monthly Rent *" else "Budget *",
                                selectedValue = selectedBudgetPreset.ifBlank { budget },
                                options = activeBudgetPresets.map { it.label },
                                onValueSelected = { chosen ->
                                    selectedBudgetPreset = chosen
                                    if (chosen != "Custom") {
                                        val presetObj = activeBudgetPresets.firstOrNull { it.label == chosen }
                                        budget = chosen
                                        customMinBudgetInr = presetObj?.minValue?.toString().orEmpty()
                                        customMaxBudgetInr = presetObj?.maxValue?.toString().orEmpty()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                        }

                        if (selectedSizePreset == "Custom" || selectedBudgetPreset == "Custom") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                if (selectedSizePreset == "Custom") {
                                    OutlinedTextField(
                                        value = carpetAreaRange,
                                        onValueChange = {
                                            carpetAreaRange = it
                                            val nums = Regex("""\d+""").findAll(it.replace(",", "")).mapNotNull { m -> m.value.toLongOrNull() }.toList()
                                            customMinSqFt = nums.firstOrNull()?.toString().orEmpty()
                                            customMaxSqFt = (nums.lastOrNull() ?: nums.firstOrNull())?.toString().orEmpty()
                                        },
                                        label = { Text("Custom Area (sq.ft)", fontSize = 11.sp) },
                                        placeholder = { Text("e.g. 1200-1800", fontSize = 11.sp) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = SurfaceWhite,
                                            unfocusedContainerColor = SurfaceWhite,
                                            focusedBorderColor = PrimaryActionOrange,
                                            unfocusedBorderColor = BorderGrey
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }

                                if (selectedBudgetPreset == "Custom") {
                                    Column(modifier = Modifier.weight(1f)) {
                                        OutlinedTextField(
                                            value = budget,
                                            onValueChange = {
                                                budget = it
                                                val parsed = CalculationUtils.parseCurrencyInput(it)?.toLong()?.toString().orEmpty()
                                                customMinBudgetInr = parsed
                                                customMaxBudgetInr = parsed
                                            },
                                            label = { Text(if (isRentTx) "Custom Rent (₹)" else "Custom Budget (₹)", fontSize = 11.sp) },
                                            placeholder = { Text(if (isRentTx) "e.g. 120000" else "e.g. 22500000", fontSize = 11.sp) },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = SurfaceWhite,
                                                unfocusedContainerColor = SurfaceWhite,
                                                focusedBorderColor = PrimaryActionOrange,
                                                unfocusedBorderColor = BorderGrey
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("lead_budget_input")
                                        )
                                        if (budgetPreview.isNotBlank()) {
                                            Text(
                                                text = "≈ $budgetPreview",
                                                color = PrimaryActionOrange,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }

                        // Row 5: Preferred Location | Possession
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SearchableDropdownField(
                                label = "Preferred Location",
                                selectedValue = location,
                                options = TlcStandardOptions.puneLocations,
                                onValueSelected = { location = it },
                                modifier = Modifier.weight(1f),
                                allowAddNew = true
                            )
                            CompactDropdownField(
                                label = "Possession",
                                selectedValue = possessionTimeline,
                                options = TlcStandardOptions.possessionTimelines,
                                onValueSelected = { possessionTimeline = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )
                        }

                        // Row 6: Pipeline Stage | Assigned To
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Pipeline Stage",
                                selectedValue = status,
                                options = pipelineStages,
                                onValueSelected = { status = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                            CompactDropdownField(
                                label = "Assigned To",
                                selectedValue = assignedTo,
                                options = teamEmployeeOptions,
                                onValueSelected = { assignedTo = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )
                        }
                    }

                    // Row 7: Follow-up Date 📅 | Time 🕒 (Both native pickers)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Follow-up Date",
                                color = SecondaryText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                            )
                            OutlinedButton(
                                onClick = {
                                    val pickCal = Calendar.getInstance()
                                    runCatching { isoFmt.parse(followUp) }.getOrNull()?.let { pickCal.time = it }
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            followUp = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                                        },
                                        pickCal.get(Calendar.YEAR),
                                        pickCal.get(Calendar.MONTH),
                                        pickCal.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = SurfaceWhite,
                                    contentColor = TextPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.CalendarToday, contentDescription = "Pick Follow-up Date", tint = PrimaryActionOrange, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = followUp.ifBlank { "Pick Date" },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Follow-up Time",
                                color = SecondaryText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                            )
                            OutlinedButton(
                                onClick = {
                                    val nowCal = Calendar.getInstance()
                                    TimePickerDialog(
                                        context,
                                        { _, h, m ->
                                            val tmpCal = Calendar.getInstance().apply {
                                                set(Calendar.HOUR_OF_DAY, h)
                                                set(Calendar.MINUTE, m)
                                            }
                                            followUpTime = SimpleDateFormat("h:mm a", Locale.US).format(tmpCal.time)
                                        },
                                        nowCal.get(Calendar.HOUR_OF_DAY),
                                        nowCal.get(Calendar.MINUTE),
                                        DateFormat.is24HourFormat(context)
                                    ).show()
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = SurfaceWhite,
                                    contentColor = TextPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.AccessTime, contentDescription = "Pick Follow-up Time", tint = PrimaryActionOrange, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = followUpTime.ifBlank { "Pick Time" },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    if (!isOwnerClient) {
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text("Requirement Summary", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SecondaryText)
                                Text(
                                    text = buildString {
                                        append(composedRequirement)
                                        if (budgetPreview.isNotBlank()) append(" • $budgetPreview")
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PrimaryActionOrange
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = actionNotes,
                        onValueChange = { actionNotes = it },
                        label = { Text(if (isOwnerClient) "Owner Notes" else "Action Notes", fontSize = 12.sp) },
                        placeholder = {
                            Text(
                                text = if (isOwnerClient) "Key availability, lock-in, furnishing, owner preferences..." else "Client brief, seating, floor preference, visit notes...",
                                fontSize = 12.sp
                            )
                        },
                        minLines = 2,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceWhite,
                            unfocusedContainerColor = SurfaceWhite,
                            focusedBorderColor = PrimaryActionOrange,
                            unfocusedBorderColor = BorderGrey
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Pinned Bottom Action Bar (Outer Column already enforces statusBars + navigationBars + imePadding)
                Surface(
                    color = SurfaceWhite,
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            enabled = name.isNotBlank() || phone.filter { it.isDigit() }.length >= 7,
                            onClick = {
                                val finalId = initial?.id ?: draftLead.id
                                val savedLead = draftLead.copy(
                                    id = finalId,
                                    verificationStatus = "Draft / Archived"
                                )
                                syncLinkedOwnerToInventory(savedLead)
                                onSave(savedLead, false)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("lead_save_draft_btn")
                        ) {
                            Text("Save Draft", fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, softWrap = false)
                        }
                        Button(
                            enabled = missingLiveFields.isEmpty(),
                            onClick = {
                                val finalId = initial?.id ?: draftLead.id
                                val savedLead = draftLead.copy(
                                    id = finalId,
                                    verificationStatus = "Verified Live"
                                )
                                syncLinkedOwnerToInventory(savedLead)
                                onSave(savedLead, true)
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryActionOrange,
                                contentColor = OnPrimaryActionColor
                            ),
                            modifier = Modifier
                                .weight(1.3f)
                                .heightIn(min = 48.dp)
                                .testTag("lead_save_live_btn")
                        ) {
                            Text("Save & Go Live", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }

    if (showQuickAddPropertyDialog) {
        QuickNewOwnerPropertyDialog(
            ownerName = name,
            ownerPhone = phone,
            onDismiss = { showQuickAddPropertyDialog = false },
            onCreated = { newProp ->
                val current = PropertiesLocalDatabase.loadProperties(context)
                PropertiesLocalDatabase.saveProperties(context, listOf(newProp) + current)
                localAddedProperties = listOf(newProp) + localAddedProperties
                linkedPropertyCode = newProp.propertyCode.ifBlank { newProp.id }
                if (carpetAreaRange.isBlank() && newProp.carpetAreaSqFt > 0.0) {
                    carpetAreaRange = newProp.carpetAreaSqFt.toInt().toString()
                }
                val newAmt = CalculationUtils.primaryAmountForProperty(newProp)
                if (budget.isBlank() && newAmt > 0.0) {
                    budget = newAmt.toLong().toString()
                }
                showQuickAddPropertyDialog = false
                Toast.makeText(context, "Property ${linkedPropertyCode} created & linked", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showAiPasteSheet) {
        AiPasteLeadsSplitterSheet(
            defaultAssignee = assignedTo.ifBlank { defaultEmployee },
            defaultFollowUpDate = followUp.ifBlank { isoFmt.format(Date()) },
            onDismiss = { showAiPasteSheet = false },
            onFillFormWithSingleDraft = { d ->
                clientTypeMode = if (d.isBrokerLead) "Broker / CP" else "Direct Client"
                if (d.isBrokerLead && d.brokerName.isNotBlank()) {
                    name = if (d.clientName.isNotBlank() && !d.clientName.equals(d.brokerName, true)) {
                        "${d.brokerName} (${d.clientName})"
                    } else d.brokerName
                } else if (d.clientName.isNotBlank()) {
                    name = d.clientName
                }
                if (d.firmName.isNotBlank()) brokerFirmName = d.firmName
                if (d.mobile.isNotBlank()) phone = d.mobile
                segment = d.division
                propertyType = d.propertyType
                transactionType = d.transactionType
                if (d.subOption.isNotBlank()) subOption = d.subOption
                if (d.location.isNotBlank()) location = d.location
                if (d.areaRangeText.isNotBlank()) {
                    selectedSizePreset = "Custom"
                    carpetAreaRange = d.areaRangeText
                    customMinSqFt = d.minCarpetSqFt?.toString().orEmpty()
                    customMaxSqFt = d.maxCarpetSqFt?.toString().orEmpty()
                }
                if (d.budgetText.isNotBlank() || d.maxBudgetInr != null || d.minBudgetInr != null) {
                    selectedBudgetPreset = "Custom"
                    budget = d.budgetText.ifBlank { (d.maxBudgetInr ?: d.minBudgetInr)?.toString().orEmpty() }
                    customMinBudgetInr = d.minBudgetInr?.toString().orEmpty()
                    customMaxBudgetInr = d.maxBudgetInr?.toString().orEmpty()
                }
                if (d.notes.isNotBlank()) actionNotes = d.notes
                showAiPasteSheet = false
            },
            onSaveBatchDrafts = { selectedDrafts ->
                val baseTs = System.currentTimeMillis()
                val newLeads = selectedDrafts.mapIndexed { idx, d ->
                    val isMonthly = d.transactionType.equals("Lease", true) || d.transactionType.equals("Rent", true)
                    val leadId = "CRM-${((baseTs + idx) % 1_000_000L).toString().padStart(6, '0')}"
                    val resolvedName = when {
                        d.isBrokerLead && d.brokerName.isNotBlank() && d.clientName.isNotBlank() && !d.clientName.contains(d.brokerName, true) ->
                            "${d.brokerName} • ${d.clientName}"
                        d.clientName.isNotBlank() -> d.clientName
                        d.brokerName.isNotBlank() -> d.brokerName
                        else -> "AI Lead ${idx + 1}"
                    }
                    val budgetAmtStr = d.budgetText.ifBlank {
                        (d.maxBudgetInr ?: d.minBudgetInr)?.toString().orEmpty()
                    }
                    val reqStr = d.requirementSummary.ifBlank {
                        listOf(d.transactionType, d.propertyType, d.subOption, d.areaRangeText)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                    }
                    val draft = CrmLead(
                        id = leadId,
                        name = resolvedName,
                        phone = d.mobile,
                        segment = d.division,
                        requirement = reqStr,
                        location = d.location,
                        budget = budgetAmtStr,
                        status = "New Lead",
                        nextFollowUp = followUp.ifBlank { isoFmt.format(Date()) },
                        nextFollowUpTime = followUpTime.ifBlank { "11:00 AM" },
                        followUpTime = followUpTime.ifBlank { "11:00 AM" },
                        nextFollowUpAction = "Call Client",
                        notes = d.notes,
                        assignedTo = assignedTo.ifBlank { defaultEmployee },
                        carpetAreaRange = d.areaRangeText,
                        areaSqFt = d.areaRangeText,
                        companyName = d.firmName,
                        createdDate = isoFmt.format(Date()),
                        clientType = if (d.isBrokerLead) "Broker / CP" else "Direct Client",
                        requirementType = d.transactionType,
                        propertyType = d.propertyType,
                        minCarpetSqFt = d.minCarpetSqFt?.toString().orEmpty(),
                        maxCarpetSqFt = d.maxCarpetSqFt?.toString().orEmpty(),
                        minBudgetInr = if (!isMonthly) d.minBudgetInr?.toString().orEmpty() else "",
                        maxBudgetInr = if (!isMonthly) d.maxBudgetInr?.toString().orEmpty() else "",
                        minRentInr = if (isMonthly) d.minBudgetInr?.toString().orEmpty() else "",
                        maxRentInr = if (isMonthly) d.maxBudgetInr?.toString().orEmpty() else "",
                        furnishing = d.subOption,
                        leadSource = if (d.isBrokerLead) "Channel Partner / Broker" else "WhatsApp / AI Paste",
                        createdBy = defaultEmployee
                    )
                    val readyLive = CrmPipelineAndWorkloadEngine.isReadyForLiveVerification(draft)
                    draft.copy(verificationStatus = if (readyLive) "Verified Live" else "Draft / Archived")
                }
                showAiPasteSheet = false
                if (onBatchSaveLeads != null) {
                    onBatchSaveLeads(newLeads)
                } else if (newLeads.size == 1) {
                    val single = newLeads.first()
                    onSave(single, CrmPipelineAndWorkloadEngine.isReadyForLiveVerification(single))
                } else {
                    val existing = readCrmLeads(context)
                    saveCrmLeads(context, newLeads + existing)
                    newLeads.forEach { l ->
                        appendCrmActivity(context, l.id, "AI Paste Imported", l.requirement, l.nextFollowUp, l.assignedTo)
                        if (CrmPipelineAndWorkloadEngine.isReadyForLiveVerification(l)) {
                            CrmPipelineAndWorkloadEngine.verifyAndActivateLead(context, l)
                        }
                    }
                    onDismiss()
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiPasteLeadsSplitterSheet(
    defaultAssignee: String,
    defaultFollowUpDate: String,
    onDismiss: () -> Unit,
    onFillFormWithSingleDraft: (ExtractedLeadDraft) -> Unit,
    onSaveBatchDrafts: (List<ExtractedLeadDraft>) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val geminiService = remember { GeminiRealEstateService() }
    var rawText by remember { mutableStateOf("") }
    var isParsing by remember { mutableStateOf(false) }
    var parsedDrafts by remember { mutableStateOf<List<ExtractedLeadDraft>>(emptyList()) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft,
        dragHandle = { BottomSheetDefaults.DragHandle(color = SecondaryText) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("ai_paste_leads_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("✨ AI Paste Requirement Splitter", fontSize = 16.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Paste a client message or multi-brand broker forward", fontSize = 11.5.sp, color = SecondaryText)
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                }
            }

            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it },
                label = { Text("Paste WhatsApp / Broker Message", fontSize = 12.sp) },
                placeholder = { Text("e.g. Broker Rahul (+91 98xxxxxx10):\n1. Café Brand — 1,200 sq.ft Shop on FC Road, Rent ₹2 Lac\n2. Tech Firm — 2,500 sq.ft Office in Kharadi, Rent ₹1.8 Lac", fontSize = 11.5.sp) },
                minLines = 3,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceWhite,
                    unfocusedContainerColor = SurfaceWhite,
                    focusedBorderColor = PrimaryActionOrange,
                    unfocusedBorderColor = BorderGrey
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_paste_input")
            )

            Button(
                enabled = rawText.isNotBlank() && !isParsing,
                onClick = {
                    isParsing = true
                    errorMsg = null
                    coroutineScope.launch {
                        val result = runCatching { geminiService.parseBrokerOrClientLeadsBatch(rawText) }
                            .getOrElse { geminiService.fallbackParseLeadsBatch(rawText) }
                        isParsing = false
                        if (result.isEmpty()) {
                            errorMsg = "Could not detect requirements in pasted text."
                        } else {
                            parsedDrafts = result
                            selectedIndices = result.indices.toSet()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("ai_paste_parse_btn")
            ) {
                if (isParsing) {
                    CircularProgressIndicator(
                        color = OnPrimaryActionColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Splitting & Extracting...", fontWeight = FontWeight.Bold)
                } else {
                    Text("✨ Extract & Split Requirements", fontWeight = FontWeight.Bold)
                }
            }

            errorMsg?.let {
                Text(it, color = StatusDangerRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            if (parsedDrafts.isNotEmpty()) {
                val firstBroker = parsedDrafts.firstOrNull { it.brokerName.isNotBlank() || it.firmName.isNotBlank() || it.mobile.isNotBlank() }
                if (firstBroker != null && (firstBroker.brokerName.isNotBlank() || firstBroker.firmName.isNotBlank())) {
                    Surface(
                        color = Color(0xFFE0F2FE),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = buildString {
                                append("🤝 Detected Sender: ")
                                append(listOf(firstBroker.brokerName, firstBroker.firmName, firstBroker.mobile).filter { it.isNotBlank() }.joinToString(" • "))
                            },
                            color = Color(0xFF0369A1),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(parsedDrafts.size) { idx ->
                        val item = parsedDrafts[idx]
                        val isChecked = idx in selectedIndices
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isChecked) PrimaryActionOrange else BorderGrey),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIndices = if (isChecked) selectedIndices - idx else selectedIndices + idx
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedIndices = if (checked) selectedIndices + idx else selectedIndices - idx
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = PrimaryActionOrange, checkmarkColor = OnPrimaryActionColor)
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.clientName.ifBlank { "Requirement ${idx + 1}" },
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (item.isBrokerLead) {
                                            Surface(color = Color(0xFFE0F2FE), shape = RoundedCornerShape(4.dp)) {
                                                Text("🤝 Broker", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0369A1), modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${item.division} • ${item.transactionType} ${item.propertyType}" +
                                            (if (item.subOption.isNotBlank()) " (${item.subOption})" else ""),
                                        fontSize = 12.sp,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val budDisplay = CalculationUtils.formatCrmBudgetDisplay(
                                        rawBudget = item.budgetText,
                                        transactionOrRequirementHint = item.transactionType,
                                        minInr = item.minBudgetInr?.toString().orEmpty(),
                                        maxInr = item.maxBudgetInr?.toString().orEmpty()
                                    )
                                    Text(
                                        text = listOf(
                                            item.location.ifBlank { "Location TBD" },
                                            item.areaRangeText,
                                            budDisplay
                                        ).filter { it.isNotBlank() }.joinToString(" • "),
                                        fontSize = 11.5.sp,
                                        color = PrimaryActionOrange,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (parsedDrafts.size == 1) {
                                        TextButton(
                                            onClick = { onFillFormWithSingleDraft(item) },
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Fill Form Fields ›", color = PrimaryActionOrange, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val selectedList = selectedIndices.sorted().mapNotNull { parsedDrafts.getOrNull(it) }
                Button(
                    enabled = selectedList.isNotEmpty(),
                    onClick = { onSaveBatchDrafts(selectedList) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("ai_paste_save_selected_btn")
                ) {
                    Text(
                        text = "Save Selected Leads (${selectedList.size}) • Assignee: $defaultAssignee • $defaultFollowUpDate",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CrmSubTabSwitcher(
    crmSubTab: Int,
    onSelectTab: (Int) -> Unit,
    dueTodayCount: Int = 0,
    onOpenTasks: (() -> Unit)? = null,
    onOpenWorkload: (() -> Unit)? = null,
    onAddClient: (() -> Unit)? = null
) {
    Surface(
        color = SurfaceWhite,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("crm_sub_tab_switcher"),
        border = BorderStroke(0.8.dp, BorderGrey)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceMuted)
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (crmSubTab == 0) PrimaryActionOrange else Color.Transparent)
                        .clickable { onSelectTab(0) }
                        .padding(vertical = 6.dp)
                        .testTag("crm_sub_tab_clients"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Clients",
                        color = if (crmSubTab == 0) OnPrimaryActionColor else SecondaryText,
                        fontWeight = if (crmSubTab == 0) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (crmSubTab == 1) PrimaryActionOrange else Color.Transparent)
                        .clickable { onSelectTab(1) }
                        .padding(vertical = 6.dp)
                        .testTag("crm_sub_tab_deals"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Deals",
                        color = if (crmSubTab == 1) OnPrimaryActionColor else SecondaryText,
                        fontWeight = if (crmSubTab == 1) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }
            }

            if (onOpenTasks != null) {
                IconButton(
                    onClick = onOpenTasks,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceMuted)
                        .testTag("crm_todays_work_strip")
                ) {
                    BadgedBox(
                        badge = {
                            if (dueTodayCount > 0) {
                                Badge(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor) {
                                    Text("$dueTodayCount", fontSize = 9.sp)
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.TaskAlt, contentDescription = "Task Centre", tint = PrimaryActionOrange, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (onOpenWorkload != null) {
                IconButton(
                    onClick = onOpenWorkload,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceMuted)
                        .testTag("crm_team_workload_btn")
                ) {
                    Icon(Icons.Default.Badge, contentDescription = "Team Workload", tint = TextPrimary, modifier = Modifier.size(18.dp))
                }
            }

            if (onAddClient != null) {
                IconButton(
                    onClick = onAddClient,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrimaryActionOrange)
                        .testTag("crm_add_client_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Client", tint = OnPrimaryActionColor, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

internal fun upsertCrmLeadFromPostCall(
    context: Context,
    lead: CrmLead,
    activityType: String,
    activityNotes: String,
    performer: String
): CrmLead {
    val existing = readCrmLeads(context)
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val resolvedId = lead.id.ifBlank {
        "CRM-${(existing.size + 1).toString().padStart(6, '0')}"
    }
    val resolvedLead = lead.copy(
        id = resolvedId,
        createdDate = lead.createdDate.ifBlank { today },
        createdBy = lead.createdBy.ifBlank { performer.ifBlank { "TLC-1 Team" } },
        assignedTo = lead.assignedTo.ifBlank { performer.ifBlank { "TLC-1 Team" } }
    )
    val nextLeads = if (existing.any { it.id == resolvedId }) {
        existing.map { if (it.id == resolvedId) resolvedLead else it }
    } else {
        listOf(resolvedLead) + existing
    }
    saveCrmLeads(context, nextLeads)
    val persisted = readCrmLeads(context).firstOrNull { it.id == resolvedId } ?: resolvedLead
    appendCrmActivity(
        context = context,
        leadId = persisted.id,
        type = activityType.ifBlank { "Call Logged" },
        notes = activityNotes.ifBlank { "Post-call update logged" },
        nextFollowUp = persisted.nextFollowUp,
        performedBy = performer.ifBlank { persisted.assignedTo.ifBlank { "TLC-1 Team" } }
    )
    if (CrmPipelineAndWorkloadEngine.isReadyForLiveVerification(persisted)) {
        CrmPipelineAndWorkloadEngine.verifyAndActivateLead(context, persisted)
            .onSuccess { liveLead ->
                val marked = liveLead.copy(verificationStatus = "Verified Live")
                val refreshed = readCrmLeads(context).map { if (it.id == marked.id) marked else it }
                saveCrmLeads(context, refreshed)
            }
    }
    return readCrmLeads(context).firstOrNull { it.id == resolvedId } ?: persisted
}

@Composable
private fun QuickNewOwnerPropertyDialog(
    ownerName: String,
    ownerPhone: String,
    onDismiss: () -> Unit,
    onCreated: (PropertyItem) -> Unit
) {
    var projectName by remember { mutableStateOf("") }
    var division by remember { mutableStateOf("Commercial") }
    var transaction by remember { mutableStateOf("Lease") }
    var unitNumber by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("Kharadi") }
    var carpetSqFtText by remember { mutableStateOf("") }
    var askingAmountText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val propertyTypeDefault = if (division == "Commercial") "Office" else "Apartment"
    val isLeaseTx = transaction.equals("Lease", ignoreCase = true) || transaction.equals("Rent", ignoreCase = true)
    val askingPreview = remember(askingAmountText, isLeaseTx) {
        CalculationUtils.formatSmartIndianAmount(askingAmountText, isMonthly = isLeaseTx)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Add Owner Property",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 17.sp
                )
                Text(
                    text = "Creates a verified listing pre-linked to ${ownerName.ifBlank { "this owner" }}",
                    fontSize = 11.5.sp,
                    color = SecondaryText
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it; errorMsg = null },
                    label = { Text("Project / Building Name *", fontSize = 11.5.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = BackgroundSoft,
                        unfocusedContainerColor = BackgroundSoft,
                        focusedBorderColor = PrimaryActionOrange,
                        unfocusedBorderColor = BorderGrey
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_owner_prop_project")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactDropdownField(
                        label = "Division",
                        selectedValue = division,
                        options = listOf("Commercial", "Residential"),
                        onValueSelected = { division = it },
                        modifier = Modifier.weight(1f),
                        allowCustom = false
                    )
                    CompactDropdownField(
                        label = "Transaction",
                        selectedValue = transaction,
                        options = listOf("Lease", "Sale", "Pre-Leased"),
                        onValueSelected = { transaction = it },
                        modifier = Modifier.weight(1f),
                        allowCustom = false
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = unitNumber,
                        onValueChange = { unitNumber = it },
                        label = { Text("Unit / Office No", fontSize = 11.5.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = BackgroundSoft,
                            unfocusedContainerColor = BackgroundSoft,
                            focusedBorderColor = PrimaryActionOrange,
                            unfocusedBorderColor = BorderGrey
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_owner_prop_unit")
                    )
                    SearchableDropdownField(
                        label = "Location",
                        selectedValue = location,
                        options = TlcStandardOptions.puneLocations,
                        onValueSelected = { location = it },
                        modifier = Modifier.weight(1f),
                        allowAddNew = true
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    OutlinedTextField(
                        value = carpetSqFtText,
                        onValueChange = { carpetSqFtText = it },
                        label = { Text("Carpet (sq.ft)", fontSize = 11.5.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = BackgroundSoft,
                            unfocusedContainerColor = BackgroundSoft,
                            focusedBorderColor = PrimaryActionOrange,
                            unfocusedBorderColor = BorderGrey
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_owner_prop_carpet")
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = askingAmountText,
                            onValueChange = { askingAmountText = it },
                            label = { Text(if (isLeaseTx) "Monthly Rent (₹)" else "Asking Price (₹)", fontSize = 11.5.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = BackgroundSoft,
                                unfocusedContainerColor = BackgroundSoft,
                                focusedBorderColor = PrimaryActionOrange,
                                unfocusedBorderColor = BorderGrey
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("quick_owner_prop_price")
                        )
                        if (askingPreview.isNotBlank()) {
                            Text(
                                text = "≈ $askingPreview",
                                color = PrimaryActionOrange,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                    }
                }

                errorMsg?.let { msg ->
                    Text(msg, color = StatusDangerRed, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedProject = projectName.trim()
                    if (trimmedProject.isBlank()) {
                        errorMsg = "Enter Project / Building Name."
                        return@Button
                    }
                    val stamp = System.currentTimeMillis()
                    val codePrefix = if (division == "Commercial") "CP-NEW" else "RP-NEW"
                    val generatedCode = "$codePrefix-${stamp.toString().takeLast(4)}"
                    val carpetSqFt = carpetSqFtText.replace(",", "").trim().toDoubleOrNull() ?: 0.0
                    val parsedAmount = CalculationUtils.parseCurrencyInput(askingAmountText) ?: 0.0
                    val resolvedTab = when {
                        division == "Commercial" && transaction == "Pre-Leased" -> InventoryTab.PRE_LEASED
                        division == "Commercial" && isLeaseTx -> InventoryTab.OFF_LEASE
                        division == "Commercial" -> InventoryTab.OFF_OUT
                        isLeaseTx -> InventoryTab.RESI_RENT
                        else -> InventoryTab.RESI_RESALE
                    }
                    val todaySlash = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())
                    val todayReadable = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())
                    val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val newProp = PropertyItem(
                        id = "prop_owner_$stamp",
                        propertyCode = generatedCode,
                        date = todaySlash,
                        location = location.ifBlank { "Kharadi" },
                        projectName = trimmedProject,
                        unitNumber = unitNumber.trim(),
                        propertyType = propertyTypeDefault,
                        tab = resolvedTab,
                        carpetAreaSqFt = carpetSqFt,
                        priceOrRent = parsedAmount,
                        priceSale = if (!isLeaseTx && parsedAmount > 0.0) parsedAmount else null,
                        rent = if (isLeaseTx && parsedAmount > 0.0) parsedAmount else null,
                        status = AvailabilityStatus.AVAILABLE,
                        ownerName = ownerName.trim().ifBlank { null },
                        ownerContact = ownerPhone.trim().ifBlank { null },
                        sourceInfo = "Owner CRM",
                        lastVerificationDate = todayReadable,
                        isSyncedToSheet = false,
                        portfolio = division,
                        transactionType = if (transaction == "Pre-Leased") "Pre-Leased Sale" else transaction,
                        verificationStatus = VerificationStatus.VERIFIED,
                        lastVerifiedDateClean = todayIso,
                        syncUpdatedAt = todayReadable,
                        archiveStatus = ArchiveStatus.ACTIVE
                    )
                    onCreated(newProp)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("quick_owner_prop_save_btn")
            ) {
                Text("Create & Link", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancel", color = SecondaryText, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}


