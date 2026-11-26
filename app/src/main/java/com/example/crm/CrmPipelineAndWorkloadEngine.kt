package com.example.crm

import android.content.Context
import com.example.access.*
import com.example.data.UserSettingsManager
import com.example.data.auditedCrmPreferences
import com.example.model.*
import com.example.ui.screens.CrmLead
import com.example.ui.screens.CrmVisit
import com.example.ui.screens.readCrmLeads
import com.example.ui.screens.readVisits
import com.example.ui.screens.saveCrmLeads
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

/**
 * Business division enum shared across CRM requirements, pipeline, assignments, and reporting.
 */
enum class CrmBusinessDivision(val label: String) {
    COMMERCIAL("Commercial"),
    RESIDENTIAL("Residential"),
    UNCLASSIFIED("Unclassified");

    companion object {
        fun fromString(raw: String?): CrmBusinessDivision {
            val clean = raw?.trim().orEmpty()
            return when {
                clean.equals("COMMERCIAL", ignoreCase = true) ||
                    clean.equals("Ascend Commercial", ignoreCase = true) -> COMMERCIAL
                clean.equals("RESIDENTIAL", ignoreCase = true) ||
                    clean.equals("Aurea Living", ignoreCase = true) ||
                    clean.equals("AUREA_RESIDENTIAL", ignoreCase = true) -> RESIDENTIAL
                else -> UNCLASSIFIED
            }
        }
    }
}

/**
 * Evidence-based sales stage belonging to each requirement/lead.
 */
enum class CrmPipelineStage(
    val label: String,
    val ordinalOrder: Int,
    val isClosed: Boolean = false
) {
    NEW("New", 0),
    EXPLORING_MARKET("Exploring Market", 1),
    DETAILS_SHARED("Details Shared", 2),
    FOLLOW_UP("Follow-up", 3),
    SITE_VISIT_PENDING("Site Visit Pending", 4),
    SITE_VISIT_DONE("Site Visit Done", 5),
    NEGOTIATION("Negotiation", 6),
    ON_HOLD("On Hold", 7),
    CLOSED_WON("Closed Won", 8, isClosed = true),
    CLOSED_LOST("Closed Lost", 9, isClosed = true);

    val isOpenActive: Boolean get() = !isClosed && this != ON_HOLD

    companion object {
        fun fromAnyString(raw: String?): CrmPipelineStage {
            val clean = raw?.trim().orEmpty()
            if (clean.isBlank()) return NEW
            entries.firstOrNull {
                it.name.equals(clean, ignoreCase = true) ||
                    it.label.equals(clean, ignoreCase = true)
            }?.let { return it }

            return when {
                clean.contains("won", ignoreCase = true) ||
                    clean.contains("booked", ignoreCase = true) ||
                    clean.contains("converted", ignoreCase = true) -> CLOSED_WON
                clean.contains("lost", ignoreCase = true) ||
                    clean.contains("drop", ignoreCase = true) ||
                    clean.contains("junk", ignoreCase = true) ||
                    clean.contains("dead", ignoreCase = true) -> CLOSED_LOST
                clean.contains("hold", ignoreCase = true) ||
                    clean.contains("postpone", ignoreCase = true) -> ON_HOLD
                clean.contains("negotiat", ignoreCase = true) ||
                    clean.contains("hot", ignoreCase = true) ||
                    clean.contains("closing", ignoreCase = true) -> NEGOTIATION
                clean.contains("visit done", ignoreCase = true) ||
                    clean.contains("visited", ignoreCase = true) -> SITE_VISIT_DONE
                clean.contains("visit", ignoreCase = true) -> SITE_VISIT_PENDING
                clean.contains("follow", ignoreCase = true) ||
                    clean.contains("warm", ignoreCase = true) ||
                    clean.contains("contacted", ignoreCase = true) -> FOLLOW_UP
                clean.contains("detail", ignoreCase = true) ||
                    clean.contains("shared", ignoreCase = true) ||
                    clean.contains("shortlist", ignoreCase = true) -> DETAILS_SHARED
                clean.contains("explor", ignoreCase = true) -> EXPLORING_MARKET
                else -> NEW
            }
        }
    }
}

/**
 * Operational workState independent of sales stage.
 * Historical imported records default to AWAITING_REVIEW until explicitly activated.
 */
enum class CrmWorkState(val label: String) {
    ACTIVE("Active"),
    AWAITING_REVIEW("Awaiting Review"),
    HISTORICAL_INACTIVE("Historical Inactive"),
    ARCHIVED("Archived");

    companion object {
        fun fromString(raw: String?): CrmWorkState =
            entries.firstOrNull {
                it.name.equals(raw?.trim(), ignoreCase = true) ||
                    it.label.equals(raw?.trim(), ignoreCase = true)
            } ?: ACTIVE
    }
}

enum class LeadratReviewDecision(val label: String) {
    UNREVIEWED("Unreviewed"),
    ACTIVATE("Activate"),
    KEEP_HISTORICAL("Keep Historical"),
    NEEDS_INFO("Needs Information"),
    DUPLICATE_REVIEW("Duplicate Review");

    companion object {
        fun fromString(raw: String?): LeadratReviewDecision =
            entries.firstOrNull {
                it.name.equals(raw?.trim(), ignoreCase = true) ||
                    it.label.equals(raw?.trim(), ignoreCase = true)
            } ?: UNREVIEWED
    }
}

enum class TaskDispositionChoice(val label: String) {
    CANCEL_SELECTED("Cancel Selected Tasks"),
    KEEP_NECESSARY("Keep Necessary Tasks")
}

/**
 * Requirement Stage Projection (Prompt 10 & Prompt 06).
 */
data class RequirementStageProjection(
    val leadId: String,
    val clientId: String,
    val businessDivision: CrmBusinessDivision = CrmBusinessDivision.COMMERCIAL,
    val assignedEmployeeId: String = "",
    val stage: CrmPipelineStage = CrmPipelineStage.NEW,
    val legacyStageText: String = "",
    val workState: CrmWorkState = CrmWorkState.ACTIVE,
    val stageChangedAt: Long = System.currentTimeMillis(),
    val stageChangedBy: String = "",
    val stageChangeReason: String = "",
    val nextActionNote: String = "",
    val nextFollowUpId: String = "",
    val linkedVisitId: String = "",
    val linkedDealId: String = "",
    val closureReason: String = "",
    val closureSummary: String = "",
    val closedAt: Long? = null,
    val closedBy: String = "",
    val holdReason: String = "",
    val holdReviewDate: String = "",
    val sourceSystem: String = "MANUAL",
    val sourceEmployeeText: String = "",
    val reviewDecision: LeadratReviewDecision = LeadratReviewDecision.UNREVIEWED,
    val reviewNote: String = "",
    val reviewedBy: String = "",
    val reviewedAt: Long? = null,
    val pendingClosureApproval: Boolean = false,
    val recordVersion: Int = 1,
    val operationId: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val updatedBy: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("leadId", leadId)
        put("clientId", clientId)
        put("businessDivision", businessDivision.name)
        put("assignedEmployeeId", assignedEmployeeId)
        put("stage", stage.name)
        put("legacyStageText", legacyStageText)
        put("workState", workState.name)
        put("stageChangedAt", stageChangedAt)
        put("stageChangedBy", stageChangedBy)
        put("stageChangeReason", stageChangeReason)
        put("nextActionNote", nextActionNote)
        put("nextFollowUpId", nextFollowUpId)
        put("linkedVisitId", linkedVisitId)
        put("linkedDealId", linkedDealId)
        put("closureReason", closureReason)
        put("closureSummary", closureSummary)
        if (closedAt != null) put("closedAt", closedAt)
        put("closedBy", closedBy)
        put("holdReason", holdReason)
        put("holdReviewDate", holdReviewDate)
        put("sourceSystem", sourceSystem)
        put("sourceEmployeeText", sourceEmployeeText)
        put("reviewDecision", reviewDecision.name)
        put("reviewNote", reviewNote)
        put("reviewedBy", reviewedBy)
        if (reviewedAt != null) put("reviewedAt", reviewedAt)
        put("pendingClosureApproval", pendingClosureApproval)
        put("recordVersion", recordVersion)
        put("operationId", operationId)
        put("updatedAt", updatedAt)
        put("updatedBy", updatedBy)
    }

    companion object {
        fun fromJson(json: JSONObject): RequirementStageProjection = RequirementStageProjection(
            leadId = json.optString("leadId"),
            clientId = json.optString("clientId", json.optString("leadId")),
            businessDivision = CrmBusinessDivision.fromString(json.optString("businessDivision", "COMMERCIAL")),
            assignedEmployeeId = json.optString("assignedEmployeeId"),
            stage = CrmPipelineStage.fromAnyString(json.optString("stage", "NEW")),
            legacyStageText = json.optString("legacyStageText"),
            workState = CrmWorkState.fromString(json.optString("workState", "ACTIVE")),
            stageChangedAt = json.optLong("stageChangedAt", System.currentTimeMillis()),
            stageChangedBy = json.optString("stageChangedBy"),
            stageChangeReason = json.optString("stageChangeReason"),
            nextActionNote = json.optString("nextActionNote"),
            nextFollowUpId = json.optString("nextFollowUpId"),
            linkedVisitId = json.optString("linkedVisitId"),
            linkedDealId = json.optString("linkedDealId"),
            closureReason = json.optString("closureReason"),
            closureSummary = json.optString("closureSummary"),
            closedAt = if (json.has("closedAt") && !json.isNull("closedAt")) json.optLong("closedAt") else null,
            closedBy = json.optString("closedBy"),
            holdReason = json.optString("holdReason"),
            holdReviewDate = json.optString("holdReviewDate"),
            sourceSystem = json.optString("sourceSystem", "MANUAL"),
            sourceEmployeeText = json.optString("sourceEmployeeText"),
            reviewDecision = LeadratReviewDecision.fromString(json.optString("reviewDecision", "UNREVIEWED")),
            reviewNote = json.optString("reviewNote"),
            reviewedBy = json.optString("reviewedBy"),
            reviewedAt = if (json.has("reviewedAt") && !json.isNull("reviewedAt")) json.optLong("reviewedAt") else null,
            pendingClosureApproval = json.optBoolean("pendingClosureApproval", false),
            recordVersion = json.optInt("recordVersion", 1).coerceAtLeast(1),
            operationId = json.optString("operationId"),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            updatedBy = json.optString("updatedBy")
        )
    }
}

/**
 * Stage History Entry (Prompt 10).
 */
data class CrmStageHistoryEntry(
    val eventId: String = UUID.randomUUID().toString(),
    val leadId: String,
    val fromStage: CrmPipelineStage?,
    val toStage: CrmPipelineStage,
    val reason: String,
    val actorEmployeeId: String,
    val occurredAt: Long = System.currentTimeMillis(),
    val operationId: String = UUID.randomUUID().toString(),
    val linkedEvidenceIds: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("eventId", eventId)
        put("leadId", leadId)
        if (fromStage != null) put("fromStage", fromStage.name)
        put("toStage", toStage.name)
        put("reason", reason)
        put("actorEmployeeId", actorEmployeeId)
        put("occurredAt", occurredAt)
        put("operationId", operationId)
        put("linkedEvidenceIds", JSONArray(linkedEvidenceIds))
    }

    companion object {
        fun fromJson(json: JSONObject): CrmStageHistoryEntry {
            val evidenceArr = json.optJSONArray("linkedEvidenceIds") ?: JSONArray()
            val evidence = (0 until evidenceArr.length()).mapNotNull { evidenceArr.optString(it).takeIf { s -> s.isNotBlank() } }
            return CrmStageHistoryEntry(
                eventId = json.optString("eventId", UUID.randomUUID().toString()),
                leadId = json.optString("leadId"),
                fromStage = json.optString("fromStage").takeIf { it.isNotBlank() }?.let { CrmPipelineStage.fromAnyString(it) },
                toStage = CrmPipelineStage.fromAnyString(json.optString("toStage", "NEW")),
                reason = json.optString("reason"),
                actorEmployeeId = json.optString("actorEmployeeId"),
                occurredAt = json.optLong("occurredAt", System.currentTimeMillis()),
                operationId = json.optString("operationId"),
                linkedEvidenceIds = evidence
            )
        }
    }
}

/**
 * Assignment & Reassignment Entity Projection (Prompt 11).
 */
data class CrmAssignmentProjection(
    val leadId: String,
    val clientId: String,
    val businessDivision: CrmBusinessDivision,
    val assignedEmployeeId: String,
    val assignedAt: Long,
    val assignedBy: String,
    val assignmentReason: String,
    val previousEmployeeId: String = "",
    val relatedOpenTaskIds: List<String> = emptyList(),
    val selectedTaskIdsToTransfer: List<String> = emptyList(),
    val relatedVisitIds: List<String> = emptyList(),
    val operationId: String = "",
    val expectedLeadVersion: Int = 1,
    val recordVersion: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val updatedBy: String = ""
)

/**
 * Employee Eligibility (Prompt 11).
 */
data class EmployeeEligibilityItem(
    val employeeId: String,
    val employeeName: String,
    val role: EmployeeAccessRole,
    val isActive: Boolean,
    val businessScopes: List<BusinessDivisionScope>,
    val reportsToEmployeeId: String? = null
) {
    fun canReceiveAssignmentFor(division: CrmBusinessDivision): Boolean {
        if (!isActive || role == EmployeeAccessRole.READ_ONLY || role == EmployeeAccessRole.INVENTORY_MANAGER) {
            return false
        }
        return when (division) {
            CrmBusinessDivision.COMMERCIAL ->
                role == EmployeeAccessRole.ADMIN ||
                    role == EmployeeAccessRole.COMMERCIAL_MANAGER ||
                    BusinessDivisionScope.ASCEND_COMMERCIAL in businessScopes
            CrmBusinessDivision.RESIDENTIAL ->
                role == EmployeeAccessRole.ADMIN ||
                    role == EmployeeAccessRole.RESIDENTIAL_MANAGER ||
                    BusinessDivisionScope.AUREA_RESIDENTIAL in businessScopes
            CrmBusinessDivision.UNCLASSIFIED ->
                role == EmployeeAccessRole.ADMIN || businessScopes.isNotEmpty()
        }
    }
}

/**
 * Assignment History Event (Prompt 11).
 */
data class CrmAssignmentHistoryEvent(
    val assignmentEventId: String = UUID.randomUUID().toString(),
    val leadId: String,
    val fromEmployeeId: String,
    val toEmployeeId: String,
    val reason: String,
    val actorEmployeeId: String,
    val occurredAt: Long = System.currentTimeMillis(),
    val operationId: String = UUID.randomUUID().toString(),
    val transferredTaskIds: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("assignmentEventId", assignmentEventId)
        put("leadId", leadId)
        put("fromEmployeeId", fromEmployeeId)
        put("toEmployeeId", toEmployeeId)
        put("reason", reason)
        put("actorEmployeeId", actorEmployeeId)
        put("occurredAt", occurredAt)
        put("operationId", operationId)
        put("transferredTaskIds", JSONArray(transferredTaskIds))
    }

    companion object {
        fun fromJson(json: JSONObject): CrmAssignmentHistoryEvent {
            val taskArr = json.optJSONArray("transferredTaskIds") ?: JSONArray()
            val taskIds = (0 until taskArr.length()).mapNotNull { taskArr.optString(it).takeIf { s -> s.isNotBlank() } }
            return CrmAssignmentHistoryEvent(
                assignmentEventId = json.optString("assignmentEventId", UUID.randomUUID().toString()),
                leadId = json.optString("leadId"),
                fromEmployeeId = json.optString("fromEmployeeId"),
                toEmployeeId = json.optString("toEmployeeId"),
                reason = json.optString("reason"),
                actorEmployeeId = json.optString("actorEmployeeId"),
                occurredAt = json.optLong("occurredAt", System.currentTimeMillis()),
                operationId = json.optString("operationId"),
                transferredTaskIds = taskIds
            )
        }
    }
}

/**
 * Workload Projection State (Prompt 11).
 */
data class EmployeeWorkloadRow(
    val employeeId: String,
    val employeeName: String,
    val role: EmployeeAccessRole,
    val isActive: Boolean,
    val businessScopes: List<BusinessDivisionScope>,
    val activeRequirementCount: Int,
    val dueTodayTaskCount: Int,
    val overdueTaskCount: Int,
    val upcomingVisitCount: Int
)

data class TeamWorkloadProjectionState(
    val employeeRows: List<EmployeeWorkloadRow> = emptyList(),
    val unassignedRequirementCount: Int = 0,
    val suspendedAssigneeRequirementIds: List<String> = emptyList(),
    val selectedEmployeeIds: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Home Dashboard & Practical Team Reports State (Prompt 12).
 */
enum class DashboardReportPeriod(val label: String) {
    TODAY("Today"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    CUSTOM("Custom")
}

enum class DashboardDataState(val label: String) {
    LOADING("Loading"),
    READY("Ready"),
    PARTIAL("Partial Data"),
    OFFLINE_CACHE("Saved Local Cache"),
    ERROR("Error")
}

data class DashboardDrillDownSpec(
    val metricKey: String,
    val title: String,
    val countingUnit: String,
    val filterSnapshot: String,
    val recordIds: List<String>,
    val summaryItems: List<Pair<String, String>> = emptyList()
)

data class DashboardMetricProjectionState(
    val activeEmployeeId: String = "",
    val businessView: ActiveBusinessView = ActiveBusinessView.ALL_BUSINESSES,
    val employeeScopeIds: List<String> = emptyList(),
    val period: DashboardReportPeriod = DashboardReportPeriod.TODAY,
    val periodStartDate: String = "",
    val periodEndDate: String = "",
    val zoneId: String = "Asia/Kolkata",
    val todayOpenTasks: Int = 0,
    val overdueOpenTasks: Int = 0,
    val todayScheduledVisits: Int = 0,
    val activeRequirements: Int = 0,
    val unassignedRequirements: Int = 0,
    val importedLeadsAwaitingReview: Int = 0,
    val availableInventory: Int = 0,
    val pendingVerification: Int = 0,
    val reverificationDue: Int = 0,
    val upcomingVacancies: Int = 0,
    val missingCriticalInfo: Int = 0,
    val duplicateReviews: Int = 0,
    val pendingApprovals: Int = 0,
    val newRequirementsCount: Int = 0,
    val completedFollowUpsCount: Int = 0,
    val completedVisitsCount: Int = 0,
    val closedWonCount: Int = 0,
    val closedLostCount: Int = 0,
    val lastSuccessfulRefreshAt: String = "",
    val dataState: DashboardDataState = DashboardDataState.READY,
    val errorMessage: String? = null,
    val drillDowns: Map<String, DashboardDrillDownSpec> = emptyMap()
)

/**
 * Centralized engine for CRM Pipeline, Evidence-Based Stage Progression, Assignment & Reassignment,
 * Team Workload, Leadrat Review Queue, and Practical Team Reports.
 */
object CrmPipelineAndWorkloadEngine {
    private const val PIPELINE_PREFS = "crm_pipeline_projections_v1"
    private val lock = Any()

    fun loadEligibleEmployees(context: Context): List<EmployeeEligibilityItem> =
        getTeamEligibilityDirectory(context).filter { it.isActive }

    fun getTeamEligibilityDirectory(context: Context): List<EmployeeEligibilityItem> {
        val dbEmployees = runCatching {
            kotlinx.coroutines.runBlocking {
                EmployeeAccessDatabase.get(context).employeeDao().getAll().map { it.toDomain() }
            }
        }.getOrDefault(emptyList())

        val mappedFromDb = dbEmployees.map { emp ->
            EmployeeEligibilityItem(
                employeeId = emp.employeeId,
                employeeName = emp.employeeName,
                role = emp.role,
                isActive = emp.isActive && emp.accessStatus == EmployeeAccessStatus.ACTIVE,
                businessScopes = emp.businessScopes,
                reportsToEmployeeId = emp.reportsToEmployeeId
            )
        }
        if (mappedFromDb.isNotEmpty()) {
            return mappedFromDb.sortedWith(compareByDescending<EmployeeEligibilityItem> { it.isActive }.thenBy { it.employeeName })
        }
        // If Room hasn't been hydrated yet, fall back only to synced `Team & Users` disk cache (never hardcoded test accounts)
        val syncedDiskMembers = com.example.data.OfflineDiskCache.loadEmployeeDirectory(context)
            .filter { it.name.isNotBlank() }
            .map { member ->
                val accessRole = when {
                    member.role.contains("super", true) || member.role.contains("admin", true) -> EmployeeAccessRole.ADMIN
                    member.role.contains("commercial", true) -> EmployeeAccessRole.COMMERCIAL_MANAGER
                    member.role.contains("residential", true) -> EmployeeAccessRole.RESIDENTIAL_MANAGER
                    member.role.contains("manager", true) -> EmployeeAccessRole.COMMERCIAL_MANAGER
                    else -> EmployeeAccessRole.SALES_EXECUTIVE
                }
                val scopes = when {
                    member.division.contains("both", true) || member.division.contains("all", true) -> BusinessDivisionScope.entries.toList()
                    member.division.contains("resi", true) -> listOf(BusinessDivisionScope.AUREA_RESIDENTIAL)
                    else -> listOf(BusinessDivisionScope.ASCEND_COMMERCIAL)
                }
                EmployeeEligibilityItem(
                    employeeId = member.id.ifBlank { member.name },
                    employeeName = member.name,
                    role = accessRole,
                    isActive = member.isActiveMember(),
                    businessScopes = scopes,
                    reportsToEmployeeId = member.managerId.ifBlank { null }
                )
            }
        return syncedDiskMembers.sortedWith(compareByDescending<EmployeeEligibilityItem> { it.isActive }.thenBy { it.employeeName })
    }

    fun isHistoricalLeadratImport(lead: CrmLead): Boolean {
        return lead.id.startsWith("leadrat", ignoreCase = true) ||
            lead.id.startsWith("imp-", ignoreCase = true) ||
            lead.notes.contains("Leadrat", ignoreCase = true) ||
            lead.notes.contains("Imported", ignoreCase = true)
    }

    fun minimumRequiredMissingFields(lead: CrmLead): List<String> = buildList {
        val invalid = setOf("", "n/a", "na", "none", "unknown", "-", "tbd", "unnamed client", "general enquiry")
        fun present(s: String?) = s?.trim()?.lowercase()?.let { it.isNotEmpty() && it !in invalid } == true
        if (!present(lead.name)) add("Client Name")
        if (!com.example.inventory.InventoryQuality.isMeaningfulPhone(lead.phone)) add("Valid Phone (10+ digits)")
        if (!lead.segment.equals("Commercial", ignoreCase = true) && !lead.segment.equals("Residential", ignoreCase = true)) {
            add("Division (Commercial / Residential)")
        }
        if (!present(lead.requirement)) add("Requirement")
        if (!present(lead.location)) add("Location")
        if (!present(lead.budget) && !present(lead.carpetAreaRange) && !present(lead.areaSqFt)) {
            add("Budget or Area Range")
        }
        if (!present(lead.assignedTo)) add("Assigned Employee")
        if (!present(lead.nextFollowUp)) add("Next Follow-up Date")
    }

    fun isReadyForLiveVerification(lead: CrmLead): Boolean =
        minimumRequiredMissingFields(lead).isEmpty()

    fun getProjection(context: Context, lead: CrmLead): RequirementStageProjection = synchronized(lock) {
        val prefs = context.getSharedPreferences(PIPELINE_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("proj_${lead.id}", null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val parsed = RequirementStageProjection.fromJson(JSONObject(raw))
                return parsed.copy(
                    assignedEmployeeId = lead.assignedTo.ifBlank { parsed.assignedEmployeeId }
                )
            }
        }
        val isHistorical = isHistoricalLeadratImport(lead)
        val needsVerification = lead.notes.contains("Needs Verification", ignoreCase = true) ||
            minimumRequiredMissingFields(lead).isNotEmpty()
        val division = CrmBusinessDivision.fromString(lead.segment)
        val mappedStage = CrmPipelineStage.fromAnyString(lead.status)
        val defaultWorkState = when {
            isHistorical && lead.nextFollowUp.isBlank() -> CrmWorkState.ARCHIVED
            needsVerification -> CrmWorkState.ARCHIVED
            mappedStage.isClosed -> CrmWorkState.HISTORICAL_INACTIVE
            else -> CrmWorkState.ACTIVE
        }
        return RequirementStageProjection(
            leadId = lead.id,
            clientId = lead.id,
            businessDivision = if (division == CrmBusinessDivision.UNCLASSIFIED) CrmBusinessDivision.COMMERCIAL else division,
            assignedEmployeeId = lead.assignedTo.trim(),
            stage = mappedStage,
            legacyStageText = lead.status.trim(),
            workState = defaultWorkState,
            stageChangedAt = System.currentTimeMillis(),
            stageChangedBy = lead.updatedBy.ifBlank { "System" },
            nextActionNote = lead.nextFollowUpAction.ifBlank { lead.notes.take(120) },
            sourceSystem = if (isHistorical) "LEADRAT" else "MANUAL",
            sourceEmployeeText = lead.assignedTo.trim(),
            reviewDecision = if (defaultWorkState == CrmWorkState.ARCHIVED || defaultWorkState == CrmWorkState.AWAITING_REVIEW) {
                LeadratReviewDecision.UNREVIEWED
            } else {
                LeadratReviewDecision.ACTIVATE
            }
        )
    }

    fun getStageHistory(context: Context, leadId: String): List<CrmStageHistoryEntry> = synchronized(lock) {
        val prefs = context.getSharedPreferences(PIPELINE_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("stage_hist_$leadId", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { CrmStageHistoryEntry.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.occurredAt }
        }.getOrDefault(emptyList())
    }

    fun getAssignmentHistory(context: Context, leadId: String): List<CrmAssignmentHistoryEvent> = synchronized(lock) {
        val prefs = context.getSharedPreferences(PIPELINE_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("assign_hist_$leadId", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { CrmAssignmentHistoryEvent.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.occurredAt }
        }.getOrDefault(emptyList())
    }

    fun computeStageCounts(
        context: Context,
        leads: List<CrmLead>,
        includeHistorical: Boolean = false
    ): Map<CrmPipelineStage, Int> {
        val distinctLeads = leads.distinctBy { it.id }
        val counts = EnumMap<CrmPipelineStage, Int>(CrmPipelineStage::class.java)
        CrmPipelineStage.entries.forEach { counts[it] = 0 }
        for (lead in distinctLeads) {
            val proj = getProjection(context, lead)
            if (!includeHistorical && proj.workState != CrmWorkState.ACTIVE) continue
            counts[proj.stage] = (counts[proj.stage] ?: 0) + 1
        }
        return counts
    }

    fun canActorModifyLead(
        userRole: UserRole,
        actorName: String,
        lead: CrmLead
    ): Result<Unit> {
        if (userRole == UserRole.READ_ONLY || userRole == UserRole.BROKER || userRole == UserRole.OWNER) {
            return Result.failure(IllegalStateException("Read-Only role cannot modify CRM pipeline stages or assignments."))
        }
        if (userRole == UserRole.INVENTORY_MANAGER) {
            return Result.failure(IllegalStateException("Inventory role cannot modify CRM pipeline stages or client assignments."))
        }
        val div = CrmBusinessDivision.fromString(lead.segment)
        if (userRole == UserRole.COMMERCIAL_MANAGER && div == CrmBusinessDivision.RESIDENTIAL) {
            return Result.failure(IllegalStateException("Commercial Manager can only modify Commercial division requirements."))
        }
        if (userRole == UserRole.RESIDENTIAL_MANAGER && div == CrmBusinessDivision.COMMERCIAL) {
            return Result.failure(IllegalStateException("Residential Manager can only modify Residential division requirements."))
        }
        if (userRole == UserRole.SALES_EXECUTIVE) {
            val assigned = lead.assignedTo.trim()
            if (assigned.isNotBlank() &&
                !assigned.equals(actorName.trim(), ignoreCase = true) &&
                !assigned.equals("Sales Executive", ignoreCase = true)
            ) {
                return Result.failure(IllegalStateException("Sales Executive can only update requirements assigned to them ($actorName)."))
            }
        }
        return Result.success(Unit)
    }

    /**
     * Evidence-based stage transition with optimistic concurrency, idempotency, and explicit task disposition.
     */
    fun applyStageTransition(
        context: Context,
        lead: CrmLead,
        targetStage: CrmPipelineStage,
        reason: String,
        nextActionNote: String,
        scheduleFollowUpDate: String = "",
        scheduleFollowUpTime: String = "",
        selectedOpenTaskId: String = "",
        selectedVisitId: String = "",
        detailsSharedConfirmed: Boolean = false,
        holdReason: String = "",
        holdReviewDate: String = "",
        closureReason: String = "",
        closureSummary: String = "",
        closureConfirmed: Boolean = false,
        taskDispositionChoice: TaskDispositionChoice = TaskDispositionChoice.KEEP_NECESSARY,
        taskIdsToCancel: Set<String> = emptySet(),
        keepTasksReason: String = "",
        expectedVersion: Int? = null,
        operationId: String = UUID.randomUUID().toString()
    ): Result<Pair<CrmLead, RequirementStageProjection>> = synchronized(lock) {
        runCatching {
            val settings = UserSettingsManager(context)
            val profile = settings.userProfile.value
            val actorName = profile.username.ifBlank { "Authorized Employee" }
            canActorModifyLead(profile.role, actorName, lead).getOrThrow()

            val currentProj = getProjection(context, lead)
            val existingHistory = getStageHistory(context, lead.id)

            // Idempotency check
            if (operationId.isNotBlank() &&
                (currentProj.operationId == operationId || existingHistory.any { it.operationId == operationId })
            ) {
                return@runCatching lead to currentProj
            }

            if (expectedVersion != null && expectedVersion != currentProj.recordVersion) {
                throw IllegalStateException("Version conflict: requirement was updated elsewhere. Refresh and retry.")
            }

            val cleanReason = reason.trim()
            val cleanNextAction = nextActionNote.trim()
            val fromStage = currentProj.stage

            // Backward progression requires reason
            if (!targetStage.isClosed && targetStage != CrmPipelineStage.ON_HOLD &&
                !fromStage.isClosed && fromStage != CrmPipelineStage.ON_HOLD &&
                targetStage.ordinalOrder < fromStage.ordinalOrder
            ) {
                require(cleanReason.length >= 3) {
                    "Moving backwards from ${fromStage.label} to ${targetStage.label} requires an explanation."
                }
            }

            // Active stages require an assigned employee
            if (targetStage.isOpenActive && lead.assignedTo.isBlank() && currentProj.assignedEmployeeId.isBlank()) {
                throw IllegalArgumentException("Assign a responsible employee before advancing to ${targetStage.label}.")
            }

            val evidenceIds = mutableListOf<String>()
            var resolvedFollowUpId = selectedOpenTaskId.trim().ifBlank { currentProj.nextFollowUpId }
            var resolvedFollowUpDate = scheduleFollowUpDate.trim().ifBlank { lead.nextFollowUp }
            var resolvedFollowUpTime = scheduleFollowUpTime.trim().ifBlank { lead.nextFollowUpTime }
            var resolvedVisitId = selectedVisitId.trim().ifBlank { currentProj.linkedVisitId }

            when (targetStage) {
                CrmPipelineStage.NEW -> Unit
                CrmPipelineStage.EXPLORING_MARKET -> {
                    require(cleanReason.isNotBlank() || cleanNextAction.isNotBlank()) {
                        "Enter a brief requirement review or next action note for Exploring Market."
                    }
                }
                CrmPipelineStage.DETAILS_SHARED -> {
                    require(detailsSharedConfirmed || cleanReason.isNotBlank() || cleanNextAction.isNotBlank()) {
                        "Confirm that property details or a shortlist were shared with the client."
                    }
                    evidenceIds.add("DETAILS_CONFIRMED")
                }
                CrmPipelineStage.FOLLOW_UP -> {
                    val openTasks = runCatching { CrmTaskRepository.read(context) }.getOrDefault(emptyList())
                        .filter { it.status == CrmTaskStatus.OPEN && (it.originId == lead.id || it.id == resolvedFollowUpId) }
                    if (openTasks.isEmpty() && scheduleFollowUpDate.isBlank()) {
                        throw IllegalArgumentException("Follow-up stage requires a scheduled open follow-up task (enter a YYYY-MM-DD follow-up date).")
                    }
                    if (scheduleFollowUpDate.isNotBlank()) {
                        require(runCatching { LocalDate.parse(scheduleFollowUpDate.trim()) }.isSuccess) {
                            "Enter a valid follow-up date (YYYY-MM-DD)."
                        }
                        val createdTask = CrmTask(
                            title = cleanNextAction.ifBlank { "Follow up with ${lead.name}" },
                            origin = CrmTaskOrigin.LEAD,
                            originId = lead.id,
                            assignedTo = lead.assignedTo,
                            dueDate = scheduleFollowUpDate.trim(),
                            dueTime = scheduleFollowUpTime.trim().takeIf { Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(it) }.orEmpty(),
                            notes = cleanReason
                        )
                        CrmTaskRepository.upsert(context, createdTask)
                        resolvedFollowUpId = createdTask.id
                        resolvedFollowUpDate = scheduleFollowUpDate.trim()
                    } else {
                        resolvedFollowUpId = openTasks.first().id
                        if (openTasks.first().dueDate.isNotBlank()) {
                            resolvedFollowUpDate = openTasks.first().dueDate
                        }
                    }
                    evidenceIds.add(resolvedFollowUpId)
                }
                CrmPipelineStage.SITE_VISIT_PENDING -> {
                    val visits = readVisits(context, lead.id)
                    val matchedVisit = visits.firstOrNull { it.id == resolvedVisitId } ?: visits.firstOrNull()
                    require(matchedVisit != null) {
                        "Site Visit Pending requires a scheduled site visit record for this client. Schedule a site visit first."
                    }
                    resolvedVisitId = matchedVisit.id
                    evidenceIds.add(matchedVisit.id)
                }
                CrmPipelineStage.SITE_VISIT_DONE -> {
                    val visits = readVisits(context, lead.id)
                    val matchedVisit = visits.firstOrNull { it.id == resolvedVisitId }
                        ?: visits.firstOrNull { it.status.equals("Completed", ignoreCase = true) || it.outcome.isNotBlank() }
                    require(matchedVisit != null && (matchedVisit.status.equals("Completed", ignoreCase = true) || matchedVisit.outcome.isNotBlank())) {
                        "Site Visit Done requires a completed site visit record with an outcome."
                    }
                    resolvedVisitId = matchedVisit.id
                    evidenceIds.add(matchedVisit.id)
                }
                CrmPipelineStage.NEGOTIATION -> {
                    require(cleanReason.length >= 3 || cleanNextAction.length >= 3) {
                        "Negotiation stage requires a meaningful commercial or progression note."
                    }
                }
                CrmPipelineStage.ON_HOLD -> {
                    require(holdReason.trim().length >= 3) {
                        "On Hold requires an explicit hold reason."
                    }
                    require(holdReviewDate.trim().isNotBlank() && runCatching { LocalDate.parse(holdReviewDate.trim()) }.isSuccess) {
                        "On Hold requires a valid review date (YYYY-MM-DD)."
                    }
                    resolvedFollowUpDate = holdReviewDate.trim()
                }
                CrmPipelineStage.CLOSED_WON -> {
                    require(closureConfirmed) {
                        "Please confirm closing this requirement as Closed Won."
                    }
                    require(closureSummary.trim().length >= 3) {
                        "Closed Won requires a closure summary (e.g., finalized unit or deal reference)."
                    }
                }
                CrmPipelineStage.CLOSED_LOST -> {
                    require(closureConfirmed) {
                        "Please confirm closing this requirement as Closed Lost."
                    }
                    require(closureReason.trim().length >= 3) {
                        "Closed Lost requires an explicit closure reason (e.g., budget mismatch, leased elsewhere, postponed)."
                    }
                }
            }

            // Explicit Task Disposition when closing a requirement
            if (targetStage.isClosed) {
                val openTasks = runCatching { CrmTaskRepository.read(context) }.getOrDefault(emptyList())
                    .filter { it.status == CrmTaskStatus.OPEN && it.originId == lead.id }
                if (openTasks.isNotEmpty()) {
                    when (taskDispositionChoice) {
                        TaskDispositionChoice.CANCEL_SELECTED -> {
                            val toCancel = if (taskIdsToCancel.isEmpty()) openTasks.map { it.id }.toSet() else taskIdsToCancel
                            toCancel.forEach { taskId ->
                                CrmTaskRepository.setStatus(context, taskId, CrmTaskStatus.CANCELLED)
                            }
                        }
                        TaskDispositionChoice.KEEP_NECESSARY -> {
                            require(keepTasksReason.trim().isNotBlank() || cleanReason.isNotBlank()) {
                                "Provide a reason for keeping open tasks on a closed requirement, or choose Cancel Selected Tasks."
                            }
                        }
                    }
                }
            }

            val nowMs = System.currentTimeMillis()
            val updatedProj = currentProj.copy(
                stage = targetStage,
                workState = if (targetStage.isClosed) CrmWorkState.HISTORICAL_INACTIVE else CrmWorkState.ACTIVE,
                stageChangedAt = nowMs,
                stageChangedBy = actorName,
                stageChangeReason = cleanReason.ifBlank {
                    when (targetStage) {
                        CrmPipelineStage.ON_HOLD -> holdReason.trim()
                        CrmPipelineStage.CLOSED_WON -> closureSummary.trim()
                        CrmPipelineStage.CLOSED_LOST -> closureReason.trim()
                        else -> "Updated to ${targetStage.label}"
                    }
                },
                nextActionNote = cleanNextAction.ifBlank { currentProj.nextActionNote },
                nextFollowUpId = resolvedFollowUpId,
                linkedVisitId = resolvedVisitId,
                closureReason = if (targetStage == CrmPipelineStage.CLOSED_LOST) closureReason.trim() else currentProj.closureReason,
                closureSummary = if (targetStage == CrmPipelineStage.CLOSED_WON) closureSummary.trim() else currentProj.closureSummary,
                closedAt = if (targetStage.isClosed) nowMs else currentProj.closedAt,
                closedBy = if (targetStage.isClosed) actorName else currentProj.closedBy,
                holdReason = if (targetStage == CrmPipelineStage.ON_HOLD) holdReason.trim() else currentProj.holdReason,
                holdReviewDate = if (targetStage == CrmPipelineStage.ON_HOLD) holdReviewDate.trim() else currentProj.holdReviewDate,
                recordVersion = currentProj.recordVersion + 1,
                operationId = operationId,
                updatedAt = nowMs,
                updatedBy = actorName
            )

            val historyEntry = CrmStageHistoryEntry(
                leadId = lead.id,
                fromStage = fromStage,
                toStage = targetStage,
                reason = updatedProj.stageChangeReason,
                actorEmployeeId = actorName,
                occurredAt = nowMs,
                operationId = operationId,
                linkedEvidenceIds = evidenceIds
            )

            // Save projection + stage history atomically
            val prefs = context.getSharedPreferences(PIPELINE_PREFS, Context.MODE_PRIVATE)
            val nextHistArr = JSONArray().put(historyEntry.toJson())
            existingHistory.forEach { nextHistArr.put(it.toJson()) }
            check(
                prefs.edit()
                    .putString("proj_${lead.id}", updatedProj.toJson().toString())
                    .putString("stage_hist_${lead.id}", nextHistArr.toString())
                    .commit()
            ) { "Failed to persist pipeline stage transition." }

            // Update CrmLead status & follow-up in crm_development_v1
            val allLeads = readCrmLeads(context)
            val updatedLead = lead.copy(
                status = targetStage.label,
                nextFollowUp = resolvedFollowUpDate,
                nextFollowUpTime = resolvedFollowUpTime,
                nextFollowUpAction = cleanNextAction.ifBlank { lead.nextFollowUpAction }
            )
            if (allLeads.any { it.id == lead.id }) {
                saveCrmLeads(context, allLeads.map { if (it.id == lead.id) updatedLead else it })
            }

            updatedLead to updatedProj
        }
    }

    /**
     * Reopen a closed requirement (`CLOSED_WON` or `CLOSED_LOST`) while preserving closure history.
     */
    fun reopenRequirement(
        context: Context,
        lead: CrmLead,
        targetOpenStage: CrmPipelineStage,
        reason: String,
        nextActionNote: String,
        followUpDate: String,
        operationId: String = UUID.randomUUID().toString()
    ): Result<Pair<CrmLead, RequirementStageProjection>> = synchronized(lock) {
        runCatching {
            val currentProj = getProjection(context, lead)
            require(currentProj.stage.isClosed) {
                "Only closed requirements (Closed Won / Closed Lost) can be reopened."
            }
            require(!targetOpenStage.isClosed) {
                "Select an open pipeline stage to reopen this requirement."
            }
            require(reason.trim().length >= 3) {
                "Enter a clear reason for reopening this requirement."
            }
            require(nextActionNote.trim().length >= 3) {
                "Enter the next action note for the reopened requirement."
            }
            applyStageTransition(
                context = context,
                lead = lead,
                targetStage = targetOpenStage,
                reason = "Reopened: ${reason.trim()}",
                nextActionNote = nextActionNote.trim(),
                scheduleFollowUpDate = followUpDate.trim(),
                expectedVersion = currentProj.recordVersion,
                operationId = operationId
            ).getOrThrow()
        }
    }

    /**
     * Assign or Reassign a requirement with eligibility validation, explicit task transfer selection,
     * and full assignment history preservation (Prompt 11).
     */
    fun applyLeadAssignmentOrReassignment(
        context: Context,
        lead: CrmLead,
        targetEmployeeNameOrId: String,
        reason: String,
        selectedTaskIdsToTransfer: Set<String>,
        expectedVersion: Int? = null,
        operationId: String = UUID.randomUUID().toString()
    ): Result<CrmLead> = synchronized(lock) {
        runCatching {
            val settings = UserSettingsManager(context)
            val profile = settings.userProfile.value
            val actorName = profile.username.ifBlank { "Authorized Employee" }
            canActorModifyLead(profile.role, actorName, lead).getOrThrow()

            val directory = getTeamEligibilityDirectory(context)
            val targetClean = targetEmployeeNameOrId.trim()
            require(targetClean.isNotBlank()) { "Select an eligible active employee." }

            val matchedEmployee = directory.firstOrNull {
                it.employeeId.equals(targetClean, ignoreCase = true) ||
                    it.employeeName.equals(targetClean, ignoreCase = true)
            }
            val division = CrmBusinessDivision.fromString(lead.segment)
            if (matchedEmployee != null) {
                require(matchedEmployee.isActive) {
                    "${matchedEmployee.employeeName} is currently inactive/suspended and cannot receive new assignments."
                }
                require(matchedEmployee.canReceiveAssignmentFor(division)) {
                    "${matchedEmployee.employeeName} (${matchedEmployee.role.label}) is not authorized for ${division.label} requirements."
                }
            }

            val resolvedAssigneeName = matchedEmployee?.employeeName ?: targetClean
            val previousAssignee = lead.assignedTo.trim()
            if (previousAssignee.isNotBlank() && !previousAssignee.equals(resolvedAssigneeName, ignoreCase = true)) {
                require(reason.trim().length >= 3) {
                    "Reassigning from $previousAssignee to $resolvedAssigneeName requires a handover reason."
                }
            }

            val currentProj = getProjection(context, lead)
            val existingAssignHistory = getAssignmentHistory(context, lead.id)
            if (operationId.isNotBlank() && existingAssignHistory.any { it.operationId == operationId }) {
                return@runCatching lead
            }
            if (expectedVersion != null && expectedVersion != currentProj.recordVersion) {
                throw IllegalStateException("Version conflict: requirement was updated concurrently. Refresh and retry.")
            }

            // Transfer only explicitly selected OPEN tasks; completed tasks keep original employee attribution
            val allTasks = runCatching { CrmTaskRepository.read(context) }.getOrDefault(emptyList())
            val transferredIds = mutableListOf<String>()
            allTasks.forEach { task ->
                if (task.originId == lead.id &&
                    task.status == CrmTaskStatus.OPEN &&
                    task.id in selectedTaskIdsToTransfer
                ) {
                    CrmTaskRepository.upsert(context, task.copy(assignedTo = resolvedAssigneeName))
                    transferredIds.add(task.id)
                }
            }

            val nowMs = System.currentTimeMillis()
            val updatedProj = currentProj.copy(
                assignedEmployeeId = resolvedAssigneeName,
                recordVersion = currentProj.recordVersion + 1,
                operationId = operationId,
                updatedAt = nowMs,
                updatedBy = actorName
            )
            val assignEvent = CrmAssignmentHistoryEvent(
                leadId = lead.id,
                fromEmployeeId = previousAssignee.ifBlank { "Unassigned" },
                toEmployeeId = resolvedAssigneeName,
                reason = reason.trim().ifBlank { "Assigned to $resolvedAssigneeName" },
                actorEmployeeId = actorName,
                occurredAt = nowMs,
                operationId = operationId,
                transferredTaskIds = transferredIds
            )

            val prefs = context.getSharedPreferences(PIPELINE_PREFS, Context.MODE_PRIVATE)
            val nextHistArr = JSONArray().put(assignEvent.toJson())
            existingAssignHistory.forEach { nextHistArr.put(it.toJson()) }
            check(
                prefs.edit()
                    .putString("proj_${lead.id}", updatedProj.toJson().toString())
                    .putString("assign_hist_${lead.id}", nextHistArr.toString())
                    .commit()
            ) { "Unable to save assignment history." }

            val allLeads = readCrmLeads(context)
            val updatedLead = lead.copy(assignedTo = resolvedAssigneeName)
            if (allLeads.any { it.id == lead.id }) {
                saveCrmLeads(context, allLeads.map { if (it.id == lead.id) updatedLead else it })
            }
            updatedLead
        }
    }

    /**
     * Apply Leadrat Review Decision (Prompt 06):
     * - ACTIVATE: Requires assignee & follow-up date, sets workState = ACTIVE.
     * - KEEP_HISTORICAL: Sets workState = HISTORICAL_INACTIVE (never enters daily active pipeline).
     * - NEEDS_INFO / DUPLICATE_REVIEW: Keeps workState = AWAITING_REVIEW with review note.
     */
    fun applyLeadratReviewDecision(
        context: Context,
        lead: CrmLead,
        decision: LeadratReviewDecision,
        businessDivision: CrmBusinessDivision,
        assignedEmployeeName: String,
        nextFollowUpDate: String,
        reviewNote: String,
        operationId: String = UUID.randomUUID().toString()
    ): Result<CrmLead> = synchronized(lock) {
        runCatching {
            val actorName = UserSettingsManager(context).userProfile.value.username.ifBlank { "Authorized Reviewer" }
            val currentProj = getProjection(context, lead)

            if (decision == LeadratReviewDecision.ACTIVATE) {
                require(businessDivision != CrmBusinessDivision.UNCLASSIFIED) {
                    "Select Commercial or Residential division before activating an imported lead."
                }
                require(assignedEmployeeName.trim().isNotBlank()) {
                    "Assign an active employee before activating an imported lead."
                }
                require(nextFollowUpDate.trim().isNotBlank() && runCatching { LocalDate.parse(nextFollowUpDate.trim()) }.isSuccess) {
                    "Schedule a valid next follow-up date (YYYY-MM-DD) before activating."
                }
            }

            val nextWorkState = when (decision) {
                LeadratReviewDecision.ACTIVATE -> CrmWorkState.ACTIVE
                LeadratReviewDecision.KEEP_HISTORICAL -> CrmWorkState.HISTORICAL_INACTIVE
                LeadratReviewDecision.NEEDS_INFO,
                LeadratReviewDecision.DUPLICATE_REVIEW,
                LeadratReviewDecision.UNREVIEWED -> CrmWorkState.AWAITING_REVIEW
            }

            val nowMs = System.currentTimeMillis()
            val updatedProj = currentProj.copy(
                businessDivision = businessDivision,
                assignedEmployeeId = assignedEmployeeName.trim().ifBlank { currentProj.assignedEmployeeId },
                workState = nextWorkState,
                reviewDecision = decision,
                reviewNote = reviewNote.trim(),
                reviewedBy = actorName,
                reviewedAt = nowMs,
                recordVersion = currentProj.recordVersion + 1,
                operationId = operationId,
                updatedAt = nowMs,
                updatedBy = actorName
            )

            val prefs = context.getSharedPreferences(PIPELINE_PREFS, Context.MODE_PRIVATE)
            check(
                prefs.edit().putString("proj_${lead.id}", updatedProj.toJson().toString()).commit()
            ) { "Failed to save Leadrat review decision." }

            val updatedLead = lead.copy(
                segment = if (businessDivision == CrmBusinessDivision.RESIDENTIAL) "Residential" else "Commercial",
                assignedTo = assignedEmployeeName.trim().ifBlank { lead.assignedTo },
                nextFollowUp = if (decision == LeadratReviewDecision.ACTIVATE) nextFollowUpDate.trim() else lead.nextFollowUp
            )
            val allLeads = readCrmLeads(context)
            if (allLeads.any { it.id == lead.id }) {
                saveCrmLeads(context, allLeads.map { if (it.id == lead.id) updatedLead else it })
            }
            updatedLead
        }
    }

    /**
     * Enforces 100% minimum required details before promoting an archived lead to Active/Live.
     */
    fun verifyAndActivateLead(
        context: Context,
        lead: CrmLead
    ): Result<CrmLead> = synchronized(lock) {
        runCatching {
            val missing = minimumRequiredMissingFields(lead)
            require(missing.isEmpty()) {
                "Complete 100% required lead details before activating Live: ${missing.joinToString(", ")}"
            }
            val actorName = UserSettingsManager(context).userProfile.value.username.ifBlank { "Authorized Reviewer" }
            val currentProj = getProjection(context, lead)
            val nowMs = System.currentTimeMillis()
            val cleanedNotes = lead.notes
                .replace("Verification: Needs Verification", "Verification: Verified Live", ignoreCase = true)
                .trim()
            val updatedLead = lead.copy(notes = cleanedNotes)
            val division = CrmBusinessDivision.fromString(updatedLead.segment)
            val updatedProj = currentProj.copy(
                businessDivision = if (division == CrmBusinessDivision.UNCLASSIFIED) CrmBusinessDivision.COMMERCIAL else division,
                assignedEmployeeId = updatedLead.assignedTo.trim(),
                workState = CrmWorkState.ACTIVE,
                reviewDecision = LeadratReviewDecision.ACTIVATE,
                reviewedBy = actorName,
                reviewedAt = nowMs,
                recordVersion = currentProj.recordVersion + 1,
                updatedAt = nowMs,
                updatedBy = actorName
            )
            val prefs = context.getSharedPreferences(PIPELINE_PREFS, Context.MODE_PRIVATE)
            check(prefs.edit().putString("proj_${lead.id}", updatedProj.toJson().toString()).commit()) {
                "Unable to persist lead verification state."
            }
            val allLeads = readCrmLeads(context)
            val nextLeads = if (allLeads.any { it.id == lead.id }) {
                allLeads.map { if (it.id == lead.id) updatedLead else it }
            } else {
                listOf(updatedLead) + allLeads
            }
            saveCrmLeads(context, nextLeads)
            updatedLead
        }
    }

    /**
     * Batch 21: Routes leads saved from Call Intelligence straight to Live / Active CRM
     * with Verified Live status, even if secondary optional fields are filled gradually.
     */
    fun activateCallCapturedLead(
        context: Context,
        lead: CrmLead
    ): CrmLead = synchronized(lock) {
        val actorName = UserSettingsManager(context).userProfile.value.username.ifBlank { "TLC-1 Team" }
        val currentProj = getProjection(context, lead)
        val nowMs = System.currentTimeMillis()
        val normalizedStatus = if (lead.status.isBlank() || lead.status.equals("New", ignoreCase = true)) {
            "New Lead"
        } else {
            lead.status
        }
        val cleanedNotes = lead.notes
            .replace("Verification: Needs Verification", "Verification: Verified Live", ignoreCase = true)
            .trim()
        val liveLead = lead.copy(
            status = normalizedStatus,
            verificationStatus = "Verified Live",
            notes = cleanedNotes
        )
        val division = CrmBusinessDivision.fromString(liveLead.segment)
        val updatedProj = currentProj.copy(
            businessDivision = if (division == CrmBusinessDivision.UNCLASSIFIED) CrmBusinessDivision.COMMERCIAL else division,
            assignedEmployeeId = liveLead.assignedTo.trim(),
            stage = CrmPipelineStage.fromAnyString(normalizedStatus),
            legacyStageText = normalizedStatus,
            workState = CrmWorkState.ACTIVE,
            reviewDecision = LeadratReviewDecision.ACTIVATE,
            reviewedBy = actorName,
            reviewedAt = nowMs,
            recordVersion = currentProj.recordVersion + 1,
            updatedAt = nowMs,
            updatedBy = actorName
        )
        val prefs = context.getSharedPreferences(PIPELINE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("proj_${liveLead.id}", updatedProj.toJson().toString()).commit()
        val allLeads = readCrmLeads(context)
        val nextLeads = if (allLeads.any { it.id == liveLead.id }) {
            allLeads.map { if (it.id == liveLead.id) liveLead else it }
        } else {
            listOf(liveLead) + allLeads
        }
        saveCrmLeads(context, nextLeads)
        liveLead
    }

    fun archiveLead(context: Context, lead: CrmLead): Result<CrmLead> = synchronized(lock) {
        runCatching {
            val actorName = UserSettingsManager(context).userProfile.value.username.ifBlank { "Authorized Reviewer" }
            val currentProj = getProjection(context, lead)
            val nowMs = System.currentTimeMillis()
            val updatedProj = currentProj.copy(
                workState = CrmWorkState.ARCHIVED,
                reviewDecision = LeadratReviewDecision.UNREVIEWED,
                reviewedBy = actorName,
                reviewedAt = nowMs,
                recordVersion = currentProj.recordVersion + 1,
                updatedAt = nowMs,
                updatedBy = actorName
            )
            val prefs = context.getSharedPreferences(PIPELINE_PREFS, Context.MODE_PRIVATE)
            check(prefs.edit().putString("proj_${lead.id}", updatedProj.toJson().toString()).commit()) {
                "Unable to archive lead."
            }
            lead
        }
    }

    /**
     * Compute Team Workload Projection State (Prompt 11).
     */
    fun computeTeamWorkloadState(
        context: Context,
        leads: List<CrmLead>,
        todayIso: String = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
    ): TeamWorkloadProjectionState {
        val directory = getTeamEligibilityDirectory(context)
        val allTasks = runCatching { CrmTaskRepository.read(context) }.getOrDefault(emptyList())
        val distinctLeads = leads.distinctBy { it.id }

        val activeRequirements = distinctLeads.map { it to getProjection(context, it) }
            .filter { (_, proj) -> proj.workState == CrmWorkState.ACTIVE && !proj.stage.isClosed }

        val unassignedCount = activeRequirements.count { (lead, proj) ->
            lead.assignedTo.isBlank() && proj.assignedEmployeeId.isBlank()
        }

        val inactiveNames = directory.filter { !it.isActive }.map { it.employeeName.lowercase(Locale.US) }.toSet()
        val suspendedReqIds = activeRequirements
            .filter { (lead, _) -> lead.assignedTo.trim().lowercase(Locale.US) in inactiveNames }
            .map { (lead, _) -> lead.id }

        val rows = directory.map { emp ->
            val empActiveReqs = activeRequirements.filter { (lead, proj) ->
                lead.assignedTo.equals(emp.employeeName, ignoreCase = true) ||
                    proj.assignedEmployeeId.equals(emp.employeeId, ignoreCase = true) ||
                    proj.assignedEmployeeId.equals(emp.employeeName, ignoreCase = true)
            }
            val empReqIds = empActiveReqs.map { it.first.id }.toSet()

            val empOpenTasks = allTasks.filter { task ->
                task.status == CrmTaskStatus.OPEN &&
                    (task.assignedTo.equals(emp.employeeName, ignoreCase = true) || task.originId in empReqIds)
            }
            val dueTodayTasks = empOpenTasks.count { it.dueDate == todayIso } +
                empActiveReqs.count { (lead, _) -> !lead.followUpCompleted && lead.nextFollowUp == todayIso }
            val overdueTasks = empOpenTasks.count { it.dueDate.isNotBlank() && it.dueDate < todayIso } +
                empActiveReqs.count { (lead, _) -> !lead.followUpCompleted && lead.nextFollowUp.isNotBlank() && lead.nextFollowUp < todayIso }

            val upcomingVisits = empActiveReqs.sumOf { (lead, _) ->
                readVisits(context, lead.id).count { v ->
                    v.status.equals("Scheduled", ignoreCase = true) && v.date >= todayIso
                }
            }

            EmployeeWorkloadRow(
                employeeId = emp.employeeId,
                employeeName = emp.employeeName,
                role = emp.role,
                isActive = emp.isActive,
                businessScopes = emp.businessScopes,
                activeRequirementCount = empActiveReqs.size,
                dueTodayTaskCount = dueTodayTasks,
                overdueTaskCount = overdueTasks,
                upcomingVisitCount = upcomingVisits
            )
        }

        return TeamWorkloadProjectionState(
            employeeRows = rows,
            unassignedRequirementCount = unassignedCount,
            suspendedAssigneeRequirementIds = suspendedReqIds
        )
    }

    /**
     * Compute Home Dashboard & Practical Team Reports Projection (Prompt 12).
     * Separates current open workload from period-bounded recorded outcomes in Asia/Kolkata.
     */
    fun computeDashboardProjection(
        context: Context,
        scopedProperties: List<PropertyItem>,
        userProfile: UserProfile,
        businessView: ActiveBusinessView,
        period: DashboardReportPeriod,
        customStartDate: String = "",
        customEndDate: String = "",
        selectedEmployeeScopeIds: List<String> = emptyList(),
        pendingApprovalsCount: Int = 0
    ): DashboardMetricProjectionState {
        return runCatching {
            val kolkataZone = ZoneId.of("Asia/Kolkata")
            val today = LocalDate.now(kolkataZone)
            val todayIso = today.toString()

            val (startDate, endDate) = when (period) {
                DashboardReportPeriod.TODAY -> todayIso to todayIso
                DashboardReportPeriod.THIS_WEEK -> today.minusDays((today.dayOfWeek.value - 1).toLong()).toString() to todayIso
                DashboardReportPeriod.THIS_MONTH -> today.withDayOfMonth(1).toString() to todayIso
                DashboardReportPeriod.CUSTOM -> {
                    val s = customStartDate.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: today.minusDays(7).toString()
                    val e = customEndDate.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: todayIso
                    if (s <= e) s to e else e to s
                }
            }

            val allLeads = readCrmLeads(context)
            val divisionFilter: CrmBusinessDivision? = when (businessView) {
                ActiveBusinessView.ASCEND_COMMERCIAL -> CrmBusinessDivision.COMMERCIAL
                ActiveBusinessView.AUREA_RESIDENTIAL -> CrmBusinessDivision.RESIDENTIAL
                ActiveBusinessView.ALL_BUSINESSES -> null
            }

            // Role-based CRM scoping
            val scopedLeads = allLeads.filter { lead ->
                val proj = getProjection(context, lead)
                val matchesDivision = divisionFilter == null || proj.businessDivision == divisionFilter
                val matchesRole = when (userProfile.role) {
                    UserRole.ADMIN -> true
                    UserRole.COMMERCIAL_MANAGER -> proj.businessDivision == CrmBusinessDivision.COMMERCIAL
                    UserRole.RESIDENTIAL_MANAGER -> proj.businessDivision == CrmBusinessDivision.RESIDENTIAL
                    UserRole.SALES_EXECUTIVE -> lead.assignedTo.isBlank() ||
                        lead.assignedTo.equals(userProfile.username, ignoreCase = true) ||
                        lead.assignedTo.equals("Sales Executive", ignoreCase = true)
                    UserRole.INVENTORY_MANAGER, UserRole.READ_ONLY, UserRole.BROKER, UserRole.OWNER -> true
                }
                val matchesSelectedEmployee = selectedEmployeeScopeIds.isEmpty() ||
                    selectedEmployeeScopeIds.any { sel ->
                        lead.assignedTo.equals(sel, ignoreCase = true) ||
                            proj.assignedEmployeeId.equals(sel, ignoreCase = true)
                    }
                matchesDivision && matchesRole && matchesSelectedEmployee
            }.distinctBy { it.id }

            val projectionsById = scopedLeads.associate { it.id to getProjection(context, it) }

            val activeLeadPairs = scopedLeads.mapNotNull { lead ->
                val proj = projectionsById[lead.id] ?: return@mapNotNull null
                if (proj.workState == CrmWorkState.ACTIVE && !proj.stage.isClosed) lead to proj else null
            }
            val unassignedLeadPairs = activeLeadPairs.filter { (lead, proj) ->
                lead.assignedTo.isBlank() && proj.assignedEmployeeId.isBlank()
            }
            val awaitingReviewLeads = scopedLeads.filter { lead ->
                projectionsById[lead.id]?.workState == CrmWorkState.AWAITING_REVIEW
            }

            // Tasks & Follow-ups
            val allTasks = runCatching { CrmTaskRepository.read(context) }.getOrDefault(emptyList())
            val scopedLeadIds = scopedLeads.map { it.id }.toSet()
            val scopedOpenTasks = allTasks.filter { task ->
                task.status == CrmTaskStatus.OPEN &&
                    (task.originId.isBlank() || task.originId in scopedLeadIds)
            }

            val dueTodayLeadTasks = activeLeadPairs.filter { (lead, _) ->
                !lead.followUpCompleted && lead.nextFollowUp == todayIso
            }
            val overdueLeadTasks = activeLeadPairs.filter { (lead, _) ->
                !lead.followUpCompleted && lead.nextFollowUp.isNotBlank() && lead.nextFollowUp < todayIso
            }

            val todayOpenTaskIds = (
                scopedOpenTasks.filter { it.dueDate == todayIso }.map { "task:${it.id}" } +
                    dueTodayLeadTasks.map { "lead:${it.first.id}" }
                ).distinct()
            val overdueOpenTaskIds = (
                scopedOpenTasks.filter { it.dueDate.isNotBlank() && it.dueDate < todayIso }.map { "task:${it.id}" } +
                    overdueLeadTasks.map { "lead:${it.first.id}" }
                ).distinct()

            // Today's Scheduled Visits
            val todayVisits = scopedLeads.flatMap { lead ->
                readVisits(context, lead.id).filter { v ->
                    v.status.equals("Scheduled", ignoreCase = true) && v.date == todayIso
                }.map { lead to it }
            }

            // Inventory Attention (within scoped active properties)
            val activeProps = scopedProperties.filter { prop ->
                prop.archiveStatus == ArchiveStatus.ACTIVE &&
                    when (businessView) {
                        ActiveBusinessView.ASCEND_COMMERCIAL -> prop.isCommercial()
                        ActiveBusinessView.AUREA_RESIDENTIAL -> prop.isResidential()
                        ActiveBusinessView.ALL_BUSINESSES -> true
                    }
            }
            val availableProps = activeProps.filter { it.status == AvailabilityStatus.AVAILABLE }
            val pendingVerificationProps = activeProps.filter { it.verificationStatus != VerificationStatus.VERIFIED }
            val reverificationDueProps = activeProps.filter { it.needsReverification(60) }
            val upcomingVacancyProps = activeProps.filter {
                it.status == AvailabilityStatus.LEASED && it.upcomingVacancyDate.isNotBlank()
            }
            val missingInfoProps = activeProps.filter { prop ->
                prop.projectName.isBlank() ||
                    prop.location.isBlank() ||
                    (prop.carpetAreaSqFt <= 0.0 && (prop.builtUpAreaSqFt ?: 0.0) <= 0.0) ||
                    CalculationUtils.primaryAmountForProperty(prop) <= 0.0
            }
            val duplicateReviewProps = activeProps.filter { it.duplicateDecisions.isNotEmpty() || it.sameUnitGroupId.isNotBlank() }

            // Recorded Results within [startDate..endDate] in Asia/Kolkata
            fun epochToIsoDate(epochMs: Long): String {
                if (epochMs <= 0L) return ""
                return Instant.ofEpochMilli(epochMs).atZone(kolkataZone).toLocalDate().toString()
            }

            // 1. New Requirements created/activated in period (excluding historical unreviewed imports)
            val newReqsInPeriod = scopedLeads.filter { lead ->
                val proj = projectionsById[lead.id] ?: return@filter false
                if (proj.sourceSystem == "LEADRAT" && proj.workState != CrmWorkState.ACTIVE) return@filter false
                val dateIso = lead.updatedAt.take(10).takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                    ?: epochToIsoDate(proj.stageChangedAt)
                dateIso in startDate..endDate
            }

            // 2. Completed Follow-ups in period
            val completedTasksInPeriod = allTasks.filter { task ->
                task.status == CrmTaskStatus.COMPLETED &&
                    epochToIsoDate(task.updatedAt) in startDate..endDate
            }
            val completedLeadFollowUpsInPeriod = scopedLeads.filter { lead ->
                lead.followUpCompleted && lead.nextFollowUp in startDate..endDate
            }
            val completedFollowUpCount = (completedTasksInPeriod.map { it.id } + completedLeadFollowUpsInPeriod.map { it.id }).distinct().size

            // 3. Completed Visits in period
            val completedVisitsInPeriod = scopedLeads.flatMap { lead ->
                readVisits(context, lead.id).filter { v ->
                    v.status.equals("Completed", ignoreCase = true) &&
                        (v.date in startDate..endDate || epochToIsoDate(v.timestamp) in startDate..endDate)
                }.map { lead to it }
            }

            // 4. Closed Won & Closed Lost in period (from explicit stage transitions / closedAt)
            val closedWonInPeriod = scopedLeads.filter { lead ->
                val proj = projectionsById[lead.id] ?: return@filter false
                val closedIso = proj.closedAt?.let { epochToIsoDate(it) }.orEmpty()
                proj.stage == CrmPipelineStage.CLOSED_WON && (closedIso.isBlank() || closedIso in startDate..endDate)
            }
            val closedLostInPeriod = scopedLeads.filter { lead ->
                val proj = projectionsById[lead.id] ?: return@filter false
                val closedIso = proj.closedAt?.let { epochToIsoDate(it) }.orEmpty()
                proj.stage == CrmPipelineStage.CLOSED_LOST && (closedIso.isBlank() || closedIso in startDate..endDate)
            }

            val refreshTimeLabel = SimpleDateFormat("dd MMM yyyy, hh:mm a 'IST'", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }.format(Date())

            val drillDowns = mapOf(
                "TODAY_OPEN_TASKS" to DashboardDrillDownSpec(
                    metricKey = "TODAY_OPEN_TASKS",
                    title = "Follow-ups & Tasks Due Today",
                    countingUnit = "Distinct Open Tasks (${todayOpenTaskIds.size})",
                    filterSnapshot = "Status = OPEN · Due Date = $todayIso · Scope = ${businessView.label}",
                    recordIds = todayOpenTaskIds,
                    summaryItems = dueTodayLeadTasks.map { (l, _) -> l.name to "${l.requirement.ifBlank { "Requirement" }} • Due Today ${l.nextFollowUpTime}" } +
                        scopedOpenTasks.filter { it.dueDate == todayIso }.map { t -> t.title to "Task • Due ${t.dueDate} ${t.dueTime}" }
                ),
                "OVERDUE_OPEN_TASKS" to DashboardDrillDownSpec(
                    metricKey = "OVERDUE_OPEN_TASKS",
                    title = "Overdue Follow-ups & Tasks",
                    countingUnit = "Distinct Open Tasks (${overdueOpenTaskIds.size})",
                    filterSnapshot = "Status = OPEN · Due Date < $todayIso · Scope = ${businessView.label}",
                    recordIds = overdueOpenTaskIds,
                    summaryItems = overdueLeadTasks.map { (l, _) -> l.name to "Overdue since ${l.nextFollowUp} • Assigned: ${l.assignedTo.ifBlank { "Unassigned" }}" } +
                        scopedOpenTasks.filter { it.dueDate.isNotBlank() && it.dueDate < todayIso }.map { t -> t.title to "Overdue since ${t.dueDate}" }
                ),
                "TODAY_SCHEDULED_VISITS" to DashboardDrillDownSpec(
                    metricKey = "TODAY_SCHEDULED_VISITS",
                    title = "Scheduled Site Visits Today",
                    countingUnit = "Distinct Visits (${todayVisits.size})",
                    filterSnapshot = "Visit Status = Scheduled · Date = $todayIso",
                    recordIds = todayVisits.map { it.second.id },
                    summaryItems = todayVisits.map { (l, v) -> l.name to "${v.property} • ${v.date}" }
                ),
                "ACTIVE_REQUIREMENTS" to DashboardDrillDownSpec(
                    metricKey = "ACTIVE_REQUIREMENTS",
                    title = "Active Client Requirements",
                    countingUnit = "Distinct Active Requirements (${activeLeadPairs.size})",
                    filterSnapshot = "WorkState = ACTIVE · Stage = Open · Scope = ${businessView.label}",
                    recordIds = activeLeadPairs.map { it.first.id },
                    summaryItems = activeLeadPairs.map { (l, p) -> l.name to "${p.stage.label} • ${l.requirement.ifBlank { "Requirement" }} • ${l.assignedTo.ifBlank { "Unassigned" }}" }
                ),
                "UNASSIGNED_REQUIREMENTS" to DashboardDrillDownSpec(
                    metricKey = "UNASSIGNED_REQUIREMENTS",
                    title = "Unassigned Active Requirements",
                    countingUnit = "Distinct Unassigned Requirements (${unassignedLeadPairs.size})",
                    filterSnapshot = "WorkState = ACTIVE · AssignedEmployee = Unassigned",
                    recordIds = unassignedLeadPairs.map { it.first.id },
                    summaryItems = unassignedLeadPairs.map { (l, p) -> l.name to "${p.businessDivision.label} • ${l.requirement.ifBlank { "Needs assignment" }}" }
                ),
                "IMPORTED_LEADS_REVIEW" to DashboardDrillDownSpec(
                    metricKey = "IMPORTED_LEADS_REVIEW",
                    title = "Imported Leads Awaiting Review",
                    countingUnit = "Distinct Imported Records (${awaitingReviewLeads.size})",
                    filterSnapshot = "Source = LEADRAT · WorkState = AWAITING_REVIEW",
                    recordIds = awaitingReviewLeads.map { it.id },
                    summaryItems = awaitingReviewLeads.map { l -> l.name to "${l.segment} • Imported Status: ${l.status.ifBlank { "Unclassified" }}" }
                ),
                "AVAILABLE_INVENTORY" to DashboardDrillDownSpec(
                    metricKey = "AVAILABLE_INVENTORY",
                    title = "Available Inventory",
                    countingUnit = "Distinct Active Listings (${availableProps.size})",
                    filterSnapshot = "Status = AVAILABLE · Scope = ${businessView.label}",
                    recordIds = availableProps.map { it.id },
                    summaryItems = availableProps.take(25).map { p -> "${p.propertyCode} · ${p.projectName}" to "${p.location} • ${p.carpetAreaSqFt.toInt()} sq.ft" }
                ),
                "PENDING_VERIFICATION" to DashboardDrillDownSpec(
                    metricKey = "PENDING_VERIFICATION",
                    title = "Pending Verification",
                    countingUnit = "Distinct Unverified Listings (${pendingVerificationProps.size})",
                    filterSnapshot = "VerificationStatus != VERIFIED · Scope = ${businessView.label}",
                    recordIds = pendingVerificationProps.map { it.id },
                    summaryItems = pendingVerificationProps.take(25).map { p -> "${p.propertyCode} · ${p.projectName}" to "${p.verificationStatus.label} • ${p.location}" }
                ),
                "REVERIFICATION_DUE" to DashboardDrillDownSpec(
                    metricKey = "REVERIFICATION_DUE",
                    title = "60-Day Re-verification Due",
                    countingUnit = "Distinct Listings >= 60 Days (${reverificationDueProps.size})",
                    filterSnapshot = "Verification Age >= 60 days in Asia/Kolkata",
                    recordIds = reverificationDueProps.map { it.id },
                    summaryItems = reverificationDueProps.take(25).map { p ->
                        val age = p.verificationAgeDays()?.let { "$it days ago" } ?: "Never verified"
                        "${p.propertyCode} · ${p.projectName}" to "Last verified: $age • ${p.location}"
                    }
                ),
                "UPCOMING_VACANCIES" to DashboardDrillDownSpec(
                    metricKey = "UPCOMING_VACANCIES",
                    title = "Upcoming Leased Vacancies",
                    countingUnit = "Distinct Leased Listings (${upcomingVacancyProps.size})",
                    filterSnapshot = "Status = LEASED · Upcoming Vacancy Date Present",
                    recordIds = upcomingVacancyProps.map { it.id },
                    summaryItems = upcomingVacancyProps.take(25).map { p -> "${p.propertyCode} · ${p.projectName}" to "Vacancy: ${p.upcomingVacancyDate}" }
                ),
                "MISSING_CRITICAL_INFO" to DashboardDrillDownSpec(
                    metricKey = "MISSING_CRITICAL_INFO",
                    title = "Listings Missing Critical Information",
                    countingUnit = "Distinct Listings (${missingInfoProps.size})",
                    filterSnapshot = "Missing Project, Location, Area, or Price/Rent",
                    recordIds = missingInfoProps.map { it.id },
                    summaryItems = missingInfoProps.take(25).map { p -> "${p.propertyCode} · ${p.projectName.ifBlank { "Project Not Mentioned" }}" to "Location: ${p.location.ifBlank { "Not Mentioned" }}" }
                ),
                "DUPLICATE_REVIEWS" to DashboardDrillDownSpec(
                    metricKey = "DUPLICATE_REVIEWS",
                    title = "Duplicate Listing Reviews",
                    countingUnit = "Distinct Candidate Listings (${duplicateReviewProps.size})",
                    filterSnapshot = "DuplicateStatus = POSSIBLE_DUPLICATE",
                    recordIds = duplicateReviewProps.map { it.id },
                    summaryItems = duplicateReviewProps.take(25).map { p -> "${p.propertyCode} · ${p.projectName}" to "Candidate duplicate in ${p.location}" }
                ),
                "RESULT_NEW_REQUIREMENTS" to DashboardDrillDownSpec(
                    metricKey = "RESULT_NEW_REQUIREMENTS",
                    title = "New Requirements ($startDate to $endDate)",
                    countingUnit = "Distinct Requirements (${newReqsInPeriod.size})",
                    filterSnapshot = "Period = $startDate..$endDate · Excludes Historical Imports",
                    recordIds = newReqsInPeriod.map { it.id },
                    summaryItems = newReqsInPeriod.map { l -> l.name to "${l.segment} • ${l.requirement.ifBlank { "Requirement" }}" }
                ),
                "RESULT_COMPLETED_FOLLOWUPS" to DashboardDrillDownSpec(
                    metricKey = "RESULT_COMPLETED_FOLLOWUPS",
                    title = "Completed Follow-ups ($startDate to $endDate)",
                    countingUnit = "Distinct Completed Tasks ($completedFollowUpCount)",
                    filterSnapshot = "Status = COMPLETED · Period = $startDate..$endDate",
                    recordIds = (completedTasksInPeriod.map { it.id } + completedLeadFollowUpsInPeriod.map { it.id }).distinct(),
                    summaryItems = completedTasksInPeriod.map { t -> t.title to "Completed • ${t.assignedTo.ifBlank { "Team" }}" } +
                        completedLeadFollowUpsInPeriod.map { l -> l.name to "Follow-up completed (${l.nextFollowUp})" }
                ),
                "RESULT_COMPLETED_VISITS" to DashboardDrillDownSpec(
                    metricKey = "RESULT_COMPLETED_VISITS",
                    title = "Completed Site Visits ($startDate to $endDate)",
                    countingUnit = "Distinct Completed Visits (${completedVisitsInPeriod.size})",
                    filterSnapshot = "Visit Status = Completed · Period = $startDate..$endDate",
                    recordIds = completedVisitsInPeriod.map { it.second.id },
                    summaryItems = completedVisitsInPeriod.map { (l, v) -> l.name to "${v.property} • Outcome: ${v.outcome.ifBlank { "Completed" }}" }
                ),
                "RESULT_CLOSED_WON" to DashboardDrillDownSpec(
                    metricKey = "RESULT_CLOSED_WON",
                    title = "Closed Won Requirements ($startDate to $endDate)",
                    countingUnit = "Distinct Requirements (${closedWonInPeriod.size})",
                    filterSnapshot = "Stage = CLOSED_WON · Period = $startDate..$endDate",
                    recordIds = closedWonInPeriod.map { it.id },
                    summaryItems = closedWonInPeriod.map { l ->
                        val p = projectionsById[l.id]
                        l.name to "Summary: ${p?.closureSummary?.ifBlank { "Closed Won" } ?: "Closed Won"} • By ${p?.closedBy?.ifBlank { l.assignedTo } ?: ""}"
                    }
                ),
                "RESULT_CLOSED_LOST" to DashboardDrillDownSpec(
                    metricKey = "RESULT_CLOSED_LOST",
                    title = "Closed Lost Requirements ($startDate to $endDate)",
                    countingUnit = "Distinct Requirements (${closedLostInPeriod.size})",
                    filterSnapshot = "Stage = CLOSED_LOST · Period = $startDate..$endDate",
                    recordIds = closedLostInPeriod.map { it.id },
                    summaryItems = closedLostInPeriod.map { l ->
                        val p = projectionsById[l.id]
                        l.name to "Reason: ${p?.closureReason?.ifBlank { "Closed Lost" } ?: "Closed Lost"}"
                    }
                )
            )

            DashboardMetricProjectionState(
                activeEmployeeId = userProfile.username,
                businessView = businessView,
                employeeScopeIds = selectedEmployeeScopeIds,
                period = period,
                periodStartDate = startDate,
                periodEndDate = endDate,
                zoneId = "Asia/Kolkata",
                todayOpenTasks = todayOpenTaskIds.size,
                overdueOpenTasks = overdueOpenTaskIds.size,
                todayScheduledVisits = todayVisits.size,
                activeRequirements = activeLeadPairs.size,
                unassignedRequirements = unassignedLeadPairs.size,
                importedLeadsAwaitingReview = awaitingReviewLeads.size,
                availableInventory = availableProps.size,
                pendingVerification = pendingVerificationProps.size,
                reverificationDue = reverificationDueProps.size,
                upcomingVacancies = upcomingVacancyProps.size,
                missingCriticalInfo = missingInfoProps.size,
                duplicateReviews = duplicateReviewProps.size,
                pendingApprovals = pendingApprovalsCount,
                newRequirementsCount = newReqsInPeriod.size,
                completedFollowUpsCount = completedFollowUpCount,
                completedVisitsCount = completedVisitsInPeriod.size,
                closedWonCount = closedWonInPeriod.size,
                closedLostCount = closedLostInPeriod.size,
                lastSuccessfulRefreshAt = refreshTimeLabel,
                dataState = DashboardDataState.READY,
                errorMessage = null,
                drillDowns = drillDowns
            )
        }.getOrElse { err ->
            DashboardMetricProjectionState(
                activeEmployeeId = userProfile.username,
                businessView = businessView,
                period = period,
                dataState = DashboardDataState.ERROR,
                errorMessage = err.message ?: "Unable to load dashboard metrics; check local database."
            )
        }
    }
}
