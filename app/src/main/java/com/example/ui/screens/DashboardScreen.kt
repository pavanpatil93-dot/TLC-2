package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access.ActiveBusinessView
import com.example.access.BusinessDivisionScope
import com.example.access.EmployeeAccessRole
import com.example.access.EmployeeProfile
import com.example.ai.DailyAiBriefingResult
import com.example.ai.DailyBriefingAction
import com.example.ai.DailyBriefingSnapshot
import com.example.ai.GeminiProductivityEngine
import com.example.crm.CrmCallManager
import com.example.crm.CrmCallRecord
import com.example.crm.CrmPipelineAndWorkloadEngine
import com.example.crm.DashboardDataState
import com.example.crm.DashboardDrillDownSpec
import com.example.crm.DashboardMetricProjectionState
import com.example.crm.DashboardReportPeriod
import com.example.crm.EmployeeEligibilityItem
import com.example.crm.PendingLoopsQueueCard
import com.example.crm.WhatsAppFollowUpStore
import com.example.data.ApprovalAndAuditManager
import com.example.data.OfflineDiskCache
import com.example.data.SheetsRepository
import com.example.data.UserSettingsManager
import com.example.data.master.ApprovalRequestEntity
import com.example.data.projects.ProjectDirectoryStore
import com.example.inventory.PropertyPrecomputedCache
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.*

private data class DashboardComputedState(
    val activeProperties: List<PropertyItem> = emptyList(),
    val liveCrmLeads: List<CrmLead> = emptyList(),
    val commercialCount: Int = 0,
    val residentialCount: Int = 0,
    val commercialAvailableCount: Int = 0,
    val residentialAvailableCount: Int = 0,
    val renewalRadar90dCount: Int = 0,
    val availableCount: Int = 0,
    val leaseCount: Int = 0,
    val saleCount: Int = 0,
    val recentlyAddedInventory: List<PropertyItem> = emptyList(),
    val eligibleEmployees: List<EmployeeEligibilityItem> = emptyList(),
    val projection: DashboardMetricProjectionState = DashboardMetricProjectionState(dataState = DashboardDataState.READY),
    val liveClientAndOwnerFollowUpsDue: Int = 0,
    val unloggedQueueCalls: List<CrmCallRecord> = emptyList(),
    val liveUnloggedCallsCount: Int = 0,
    val live60DayRecheckDueCount: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    properties: List<PropertyItem>,
    underConstProjects: List<UnderConstProject>,
    initialBrand: Brand? = null,
    userProfile: UserProfile = UserProfile(),
    onPropertyClick: (PropertyItem) -> Unit,
    onEditProperty: (PropertyItem) -> Unit,
    onVerifyProperty: (String) -> Unit,
    onOpenMasterSync: () -> Unit,
    onShareWhatsApp: ((PropertyItem) -> Unit)? = null,
    onNavigateToAddProperty: () -> Unit = {},
    onNavigateToCrm: () -> Unit = {},
    onNavigateToVisits: () -> Unit = {},
    onNavigateToInventory: () -> Unit = {},
    onNavigateToCommercialInventory: () -> Unit = onNavigateToInventory,
    onNavigateToResidentialInventory: () -> Unit = onNavigateToInventory,
    onNavigateToProjects: () -> Unit = onNavigateToInventory,
    onSearchInventory: (String) -> Unit = { onNavigateToInventory() },
    onNavigateToCalls: () -> Unit = {},
    onNavigateToWhatsAppInbox: () -> Unit = {},
    onNavigateToDeals: () -> Unit = {},
    dailyReports: List<DailyReportRecord> = emptyList(),
    onNavigateToDailyOps: () -> Unit = {},
    lastSyncAtMillis: Long = 0L,
    onRefreshSync: (() -> Unit)? = null,
    pendingSyncCount: Int = 0,
    isSyncingPending: Boolean = false,
    onSyncPendingClick: (() -> Unit)? = null,
    onOpenMoreDetailsForRole: ((
        role: com.example.crm.PostCallRole,
        call: CrmCallRecord,
        prefilledName: String,
        prefilledNotes: String,
        linkedEntityId: String?,
        subRole: String
    ) -> Unit)? = null,
    onOpenCaptureBrokerUnit: ((brokerName: String, brokerPhone: String, callNote: String) -> Unit)? = null,
    onOpenCallActionQueue: ((calls: List<CrmCallRecord>, startIndex: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val approvalManager = remember { ApprovalAndAuditManager(context) }
    val pendingApprovals by approvalManager.pendingApprovalRequests.collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var selectedPeriod by remember { mutableStateOf(DashboardReportPeriod.TODAY) }
    var customStartDate by remember {
        mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(7).toString())
    }
    var customEndDate by remember {
        mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString())
    }
    var showCustomDateDialog by remember { mutableStateOf(false) }
    var selectedEmployeeFilter by remember { mutableStateOf<String?>(null) }
    var activeDrillDownSpec by remember { mutableStateOf<DashboardDrillDownSpec?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val businessView = remember(initialBrand) {
        when (initialBrand) {
            Brand.COMMERCIAL -> ActiveBusinessView.ASCEND_COMMERCIAL
            Brand.RESIDENTIAL -> ActiveBusinessView.AUREA_RESIDENTIAL
            null -> ActiveBusinessView.ALL_BUSINESSES
        }
    }

    // Asia/Kolkata Timestamps
    val kolkataTz = remember { TimeZone.getTimeZone("Asia/Kolkata") }
    val currentDateDisplay = remember(refreshTrigger) {
        SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).apply { timeZone = kolkataTz }.format(Date())
    }
    val todayIso = remember(refreshTrigger) {
        LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
    }

    val isAdminOrManager = userProfile.role == UserRole.ADMIN ||
        userProfile.role == UserRole.COMMERCIAL_MANAGER ||
        userProfile.role == UserRole.RESIDENTIAL_MANAGER

    var computedState by remember { mutableStateOf(DashboardComputedState()) }

    LaunchedEffect(
        context,
        properties,
        userProfile,
        businessView,
        selectedPeriod,
        customStartDate,
        customEndDate,
        selectedEmployeeFilter,
        pendingApprovals.size,
        todayIso,
        refreshTrigger
    ) {
        computedState = withContext(Dispatchers.IO) {
            val activeProperties = properties.filter { it.archiveStatus != ArchiveStatus.ARCHIVED }
            val commercialProps = activeProperties.filter { it.propertyCategory == PropertyCategory.COMMERCIAL }
            val residentialProps = activeProperties.filter { it.propertyCategory == PropertyCategory.RESIDENTIAL }
            val commAvail = commercialProps.count { it.status == AvailabilityStatus.AVAILABLE }
            val resAvail = residentialProps.count { it.status == AvailabilityStatus.AVAILABLE }
            val radarCount = activeProperties.count { RenewalRadar90dHelper.matchesRenewalRadar90d(it) }
            val availCount = activeProperties.count { it.status == AvailabilityStatus.AVAILABLE }
            val lCount = activeProperties.count {
                PropertyPrecomputedCache.get(it).stdTx == StandardTransactionType.LEASE
            }
            val sCount = activeProperties.count {
                val tx = PropertyPrecomputedCache.get(it).stdTx
                tx == StandardTransactionType.SALE || tx == StandardTransactionType.PRE_LEASED_SALE
            }
            val recent = activeProperties.sortedByDescending { it.rowNumber }.take(5)
            val eligible = CrmPipelineAndWorkloadEngine.loadEligibleEmployees(context)
            val proj = CrmPipelineAndWorkloadEngine.computeDashboardProjection(
                context = context,
                scopedProperties = activeProperties,
                userProfile = userProfile,
                businessView = businessView,
                period = selectedPeriod,
                customStartDate = customStartDate,
                customEndDate = customEndDate,
                selectedEmployeeScopeIds = selectedEmployeeFilter?.let { listOf(it) } ?: emptyList(),
                pendingApprovalsCount = pendingApprovals.size
            )
            val crmLeads = runCatching { readCrmLeads(context) }.getOrDefault(emptyList())
            val leadFollowUpsDue = crmLeads.count { lead ->
                !lead.followUpCompleted &&
                    !lead.status.equals("Closed", ignoreCase = true) &&
                    !lead.status.equals("Archived", ignoreCase = true) &&
                    (lead.nextFollowUp.contains("Today", ignoreCase = true) ||
                        (lead.nextFollowUp.length >= 10 && lead.nextFollowUp.take(10) <= todayIso))
            }
            val followUpsDue = maxOf(leadFollowUpsDue, proj.todayOpenTasks + proj.overdueOpenTasks)
            val unloggedQueue = CrmCallManager.getUnloggedCallQueue(context)
            val unlinkedSavedCalls = CrmCallManager.readCalls(context).filter {
                !it.isLinked &&
                    !CrmCallManager.isCallSkipped(context, it) &&
                    !CrmCallManager.isInternalTeamCall(context, it)
            }
            val mergedUnlinkedQueue = (unloggedQueue + unlinkedSavedCalls)
                .distinctBy { it.id }
                .filter {
                    !it.isLinked &&
                        !CrmCallManager.isCallSkipped(context, it) &&
                        !CrmCallManager.isInternalTeamCall(context, it)
                }
            val unloggedCount = mergedUnlinkedQueue.size
            val recheckFromActive = activeProperties.count { prop ->
                prop.status == AvailabilityStatus.AVAILABLE &&
                    PropertyPrecomputedCache.get(prop).verificationAgeState == VerificationAgeState.DUE
            }
            val recheckDue = maxOf(recheckFromActive, proj.reverificationDue)

            DashboardComputedState(
                activeProperties = activeProperties,
                liveCrmLeads = crmLeads,
                commercialCount = commercialProps.size,
                residentialCount = residentialProps.size,
                commercialAvailableCount = commAvail,
                residentialAvailableCount = resAvail,
                renewalRadar90dCount = radarCount,
                availableCount = availCount,
                leaseCount = lCount,
                saleCount = sCount,
                recentlyAddedInventory = recent,
                eligibleEmployees = eligible,
                projection = proj,
                liveClientAndOwnerFollowUpsDue = followUpsDue,
                unloggedQueueCalls = mergedUnlinkedQueue,
                liveUnloggedCallsCount = unloggedCount,
                live60DayRecheckDueCount = recheckDue
            )
        }
    }

    val activeProperties = computedState.activeProperties
    val liveCrmLeads = computedState.liveCrmLeads
    val commercialCount = computedState.commercialCount
    val residentialCount = computedState.residentialCount
    val commercialAvailableCount = computedState.commercialAvailableCount
    val residentialAvailableCount = computedState.residentialAvailableCount
    val renewalRadar90dCount = computedState.renewalRadar90dCount
    val availableCount = computedState.availableCount
    val leaseCount = computedState.leaseCount
    val saleCount = computedState.saleCount
    val recentlyAddedInventory = computedState.recentlyAddedInventory
    val eligibleEmployees = computedState.eligibleEmployees
    val projection = computedState.projection
    val liveClientAndOwnerFollowUpsDue = computedState.liveClientAndOwnerFollowUpsDue
    val unloggedQueueCalls = computedState.unloggedQueueCalls
    val liveUnloggedCallsCount = computedState.liveUnloggedCallsCount
    val live60DayRecheckDueCount = computedState.live60DayRecheckDueCount

    // Daily AI Priority Briefing (cached per IST day, refreshes on demand)
    var dailyBriefing by remember { mutableStateOf<DailyAiBriefingResult?>(null) }
    var isLoadingBriefing by remember { mutableStateOf(false) }
    var isBriefingExpanded by remember { mutableStateOf(true) }
    var briefingRefreshNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(
        todayIso,
        liveCrmLeads.size,
        liveUnloggedCallsCount,
        commercialAvailableCount,
        residentialAvailableCount,
        briefingRefreshNonce
    ) {
        val forceRefresh = briefingRefreshNonce > 0
        val cached = withContext(Dispatchers.IO) { GeminiProductivityEngine.loadCachedDailyBriefing(context) }
        if (cached != null && dailyBriefing == null) {
            dailyBriefing = cached
        }
        val snapshot = withContext(Dispatchers.IO) {
            val activeLeads = liveCrmLeads.filter { lead ->
                !lead.status.contains("Closed", ignoreCase = true) &&
                    !lead.status.contains("Archived", ignoreCase = true)
            }
            val overdueLeads = activeLeads.filter { lead ->
                !lead.followUpCompleted && lead.nextFollowUp.length >= 10 && lead.nextFollowUp.take(10) < todayIso
            }
            val dueTodayLeads = activeLeads.filter { lead ->
                !lead.followUpCompleted && (lead.nextFollowUp.contains("Today", ignoreCase = true) ||
                    (lead.nextFollowUp.length >= 10 && lead.nextFollowUp.take(10) == todayIso))
            }
            val hotLeads = activeLeads.filter { lead ->
                lead.priority.equals("Hot", ignoreCase = true) ||
                    lead.status.contains("Negotiation", ignoreCase = true) ||
                    lead.status.contains("Site Visit", ignoreCase = true)
            }
            val todayVisits = activeLeads.count { lead ->
                lead.siteVisitDate.length >= 10 && lead.siteVisitDate.take(10) == todayIso
            }
            val waInbox = WhatsAppFollowUpStore.getMessages(context).filter { it.isAiInboxEligible() }
            val waPropCount = waInbox.count { it.aiIntent == "PROPERTY_LISTING" || it.aiIntent == "EXISTING_PROPERTY_UPDATE" }
            val waReqCount = waInbox.count { it.aiIntent == "CLIENT_REQUIREMENT" }
            val availLocations = activeProperties
                .filter { it.status == AvailabilityStatus.AVAILABLE }
                .map { it.location.lowercase(Locale.ENGLISH) }
                .filter { it.isNotBlank() }
                .toSet()
            val hotWithMatches = hotLeads.count { lead ->
                val loc = lead.location.lowercase(Locale.ENGLISH)
                loc.isNotBlank() && availLocations.any { it.contains(loc) || loc.contains(it) }
            }
            DailyBriefingSnapshot(
                employeeName = userProfile.username.ifBlank { "TLC Team" },
                overdueFollowUps = overdueLeads.size,
                dueTodayFollowUps = dueTodayLeads.size,
                hotLeadsCount = hotLeads.size,
                hotLeadsWithLiveMatches = hotWithMatches,
                todaySiteVisits = todayVisits,
                unloggedCallsCount = liveUnloggedCallsCount,
                pendingAiInboxCount = waInbox.size,
                pendingAiPropertyCount = waPropCount,
                pendingAiRequirementCount = waReqCount,
                availableCommercialCount = commercialAvailableCount,
                availableResidentialCount = residentialAvailableCount,
                topOverdueLeadNames = overdueLeads.take(3).map { it.name.ifBlank { it.id } },
                topHotLeadSummaries = hotLeads.take(3).map { "${it.name} (${it.location.ifBlank { "Pune" }})" }
            )
        }
        isLoadingBriefing = true
        try {
            dailyBriefing = GeminiProductivityEngine.generateDailyPriorityBriefing(
                context = context,
                snapshot = snapshot,
                forceRefresh = forceRefresh
            )
        } finally {
            isLoadingBriefing = false
        }
    }

    var tickerNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastSyncAtMillis) {
        tickerNowMillis = System.currentTimeMillis()
        while (true) {
            delay(30_000L)
            tickerNowMillis = System.currentTimeMillis()
        }
    }
    val syncedAgoLabel = remember(lastSyncAtMillis, tickerNowMillis) {
        if (lastSyncAtMillis > 0L) {
            SheetsRepository.formatSyncedAgoLabel(lastSyncAtMillis, tickerNowMillis)
        } else {
            "Updated: ${projection.lastSuccessfulRefreshAt.ifBlank { "Just now" }}"
        }
    }

    val approverEmployee = remember(userProfile) {
        EmployeeProfile(
            employeeId = userProfile.username.lowercase().replace(" ", "_").ifBlank { "exec_01" },
            employeeName = userProfile.username.ifBlank { "Operational Executive" },
            loginMobile = "9822011223",
            loginEmail = "team@theleasingcompany.in",
            role = if (userProfile.role == UserRole.ADMIN) EmployeeAccessRole.ADMIN else EmployeeAccessRole.COMMERCIAL_MANAGER,
            businessScopes = listOf(BusinessDivisionScope.ASCEND_COMMERCIAL, BusinessDivisionScope.AUREA_RESIDENTIAL),
            activeBusinessView = ActiveBusinessView.ALL_BUSINESSES,
            isActive = true
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundSoft)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 88.dp)
            .testTag("dashboard_screen")
    ) {
        // 1. Compact Header: Greeting, Asia/Kolkata Date, Sync Timestamp & Role Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hello, ${userProfile.username.ifBlank { "Executive" }}",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${currentDateDisplay.uppercase(Locale.ENGLISH)} • ${businessView.label.uppercase(Locale.ENGLISH)}",
                    color = PrimaryActionOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "$syncedAgoLabel • ${projection.dataState.label}",
                    color = SecondaryText,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("dashboard_synced_ago_label")
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = {
                        refreshTrigger++
                        onRefreshSync?.invoke()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dashboard_refresh_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Dashboard Metrics",
                        tint = PrimaryActionOrange
                    )
                }

                // Compact clickable Pending Sync pill next to role badge
                val hasPending = pendingSyncCount > 0
                val syncBg = if (hasPending) Color(0xFFFFF4E6) else SuccessGreenSubtle
                val syncBorder = if (hasPending) PrimaryActionOrange else StatusSuccessGreen
                val syncContentColor = if (hasPending) Color(0xFFC2410C) else StatusSuccessGreen
                Surface(
                    onClick = {
                        refreshTrigger++
                        if (onSyncPendingClick != null) {
                            onSyncPendingClick()
                        } else {
                            onRefreshSync?.invoke()
                        }
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = syncBg,
                    border = BorderStroke(1.dp, syncBorder),
                    modifier = Modifier
                        .heightIn(min = 34.dp)
                        .testTag("dashboard_pending_sync_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isSyncingPending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = syncContentColor
                            )
                        } else {
                            Icon(
                                imageVector = if (hasPending) Icons.Default.Sync else Icons.Default.CheckCircle,
                                contentDescription = if (hasPending) "Retry Pending Syncs" else "All Synced",
                                tint = syncContentColor,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Text(
                            text = pendingSyncCount.toString(),
                            color = syncContentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SelectedContainerFill,
                    border = BorderStroke(1.dp, PrimaryActionOrange)
                ) {
                    Text(
                        text = userProfile.role.label.uppercase(Locale.ENGLISH),
                        color = FocusOutlineColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // Manager/Admin Employee Scope Filter Bar
        if (isAdminOrManager && eligibleEmployees.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedEmployeeFilter == null,
                    onClick = { selectedEmployeeFilter = null },
                    label = { Text("All Team", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SelectedContainerFill,
                        selectedLabelColor = TextPrimary
                    )
                )
                eligibleEmployees.forEach { emp ->
                    FilterChip(
                        selected = selectedEmployeeFilter.equals(emp.employeeName, ignoreCase = true),
                        onClick = {
                            selectedEmployeeFilter = if (selectedEmployeeFilter.equals(emp.employeeName, ignoreCase = true)) {
                                null
                            } else {
                                emp.employeeName
                            }
                        },
                        label = { Text(emp.employeeName, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SelectedContainerFill,
                            selectedLabelColor = TextPrimary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Unified Search Bar (Property Code, Project, Location, Unit)
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderGrey),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Inventory",
                    tint = PrimaryActionOrange,
                    modifier = Modifier.padding(start = 6.dp).size(20.dp)
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            "Search code, project, micro-market, or unit...",
                            fontSize = 12.sp,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSearchInventory(searchQuery.trim()) }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_search_input")
                )
                Button(
                    onClick = { onSearchInventory(searchQuery.trim()) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("dashboard_search_btn")
                ) {
                    Text("Search", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2A. Daily AI Priority Briefing Card (Gemini Executive Chief of Staff)
        DailyAiPriorityBriefingCard(
            briefing = dailyBriefing,
            isLoading = isLoadingBriefing,
            expanded = isBriefingExpanded,
            onToggleExpand = { isBriefingExpanded = !isBriefingExpanded },
            onRefresh = { briefingRefreshNonce++ },
            onActionClick = { action ->
                when (action.targetKey) {
                    "CRM" -> onNavigateToCrm()
                    "AI_INBOX" -> onNavigateToWhatsAppInbox()
                    "VISITS" -> onNavigateToVisits()
                    "CALLS" -> onNavigateToCalls()
                    "INVENTORY" -> onNavigateToInventory()
                    else -> onNavigateToCrm()
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2B. Prominent Daily POA / EOD Entry Card (Accessible to ALL roles including Admin)
        val myDailyName = userProfile.username.ifBlank { "Executive" }
        val myDailyEmpId = userProfile.employeeId.ifBlank {
            "EMP-${myDailyName.uppercase(Locale.ENGLISH).replace(Regex("[^A-Z0-9]"), "").take(8)}"
        }
        val todayUserReport = remember(dailyReports, todayIso, myDailyName, myDailyEmpId) {
            dailyReports.firstOrNull { rep ->
                rep.date == todayIso && (
                    rep.employeeId.equals(myDailyEmpId, ignoreCase = true) ||
                        rep.employeeName.equals(myDailyName, ignoreCase = true)
                    )
            }
        }
        val poaStatus = evaluateCutoff(todayUserReport?.poaSubmittedAt.orEmpty(), LocalTime.of(10, 30))
        val eodStatus = evaluateCutoff(todayUserReport?.eodSubmittedAt.orEmpty(), LocalTime.of(19, 30))

        Surface(
            onClick = onNavigateToDailyOps,
            color = SurfaceWhite,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dashboard_daily_ops_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(SelectedContainerFill),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.EventNote,
                            contentDescription = "Daily POA and EOD",
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Daily POA / EOD",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            val poaBg = when (poaStatus) {
                                CutoffStatus.ON_TIME -> SuccessGreenSubtle
                                CutoffStatus.LATE -> WarningAmberSubtle
                                CutoffStatus.PENDING -> SelectedContainerFill
                            }
                            val poaFg = when (poaStatus) {
                                CutoffStatus.ON_TIME -> StatusSuccessGreen
                                CutoffStatus.LATE -> StatusWarningAmber
                                CutoffStatus.PENDING -> PrimaryActionOrange
                            }
                            Surface(color = poaBg, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    text = if (todayUserReport?.poaSubmittedAt?.isNotBlank() == true) {
                                        "POA · ${todayUserReport.poaSubmittedAt}"
                                    } else {
                                        "POA Due 10:30 AM IST"
                                    },
                                    color = poaFg,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            val eodBg = when (eodStatus) {
                                CutoffStatus.ON_TIME -> SuccessGreenSubtle
                                CutoffStatus.LATE -> WarningAmberSubtle
                                CutoffStatus.PENDING -> SurfaceTint
                            }
                            val eodFg = when (eodStatus) {
                                CutoffStatus.ON_TIME -> StatusSuccessGreen
                                CutoffStatus.LATE -> StatusWarningAmber
                                CutoffStatus.PENDING -> SecondaryText
                            }
                            Surface(color = eodBg, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    text = if (todayUserReport?.eodSubmittedAt?.isNotBlank() == true) {
                                        "EOD · ${todayUserReport.eodSubmittedAt}"
                                    } else {
                                        "EOD Due 7:30 PM IST"
                                    },
                                    color = eodFg,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onNavigateToDailyOps,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("dashboard_daily_ops_btn")
                ) {
                    Text(
                        text = if (todayUserReport == null) "Submit" else "Update",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        PendingLoopsQueueCard(
            testTagPrefix = "dashboard_pending_loops"
        )

        if (projection.dataState == DashboardDataState.ERROR && projection.errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = StatusDangerRed.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StatusDangerRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = projection.errorMessage,
                        color = StatusDangerRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { refreshTrigger++ }) {
                        Text("Retry", color = StatusDangerRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. Real-Time Executive Counters (Batch 9: Today's Follow-ups, Unlogged Calls, 60-Day Recheck Due, Active Live Inventory)
        Text(
            text = "REAL-TIME EXECUTIVE COUNTERS",
            color = PrimaryActionOrange,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OperationalKpiCard(
                title = "Today's Follow-ups",
                value = "$liveClientAndOwnerFollowUpsDue",
                icon = Icons.Default.EventAvailable,
                badgeColor = StatusWarningAmber,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_todays_followups_live"),
                onClick = onNavigateToCrm
            )
            OperationalKpiCard(
                title = "Unlogged Calls",
                value = "$liveUnloggedCallsCount",
                icon = Icons.Default.PhoneCallback,
                badgeColor = StatusDangerRed,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_unlogged_calls_live"),
                onClick = {
                    if (unloggedQueueCalls.isNotEmpty()) {
                        if (onOpenCallActionQueue != null) {
                            onOpenCallActionQueue(unloggedQueueCalls, 0)
                        } else {
                            onNavigateToCalls()
                        }
                    } else {
                        coroutineScope.launch {
                            val nextUnlinkedList = withContext(Dispatchers.IO) {
                                CrmCallManager.readCalls(context).filter {
                                    !it.isLinked &&
                                        !CrmCallManager.isCallSkipped(context, it) &&
                                        !CrmCallManager.isInternalTeamCall(context, it)
                                }
                            }
                            if (nextUnlinkedList.isNotEmpty() && onOpenCallActionQueue != null) {
                                onOpenCallActionQueue(nextUnlinkedList, 0)
                            } else {
                                onNavigateToCalls()
                            }
                        }
                    }
                }
            )
            OperationalKpiCard(
                title = "60-Day Recheck Due",
                value = "$live60DayRecheckDueCount",
                icon = Icons.Default.History,
                badgeColor = PrimaryActionOrange,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_60d_recheck_live"),
                onClick = onNavigateToInventory
            )
            OperationalKpiCard(
                title = "Active Live Inventory",
                value = "${commercialCount}C / ${residentialCount}R",
                icon = Icons.Default.Apartment,
                badgeColor = StatusSuccessGreen,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_active_live_inventory_split"),
                onClick = onNavigateToInventory
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3B. Today's Work & CRM Attention (Open Operational State — Never Filtered by Report Date Range)
        Text(
            text = "TODAY'S WORK & CRM ATTENTION",
            color = PrimaryActionOrange,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OperationalKpiCard(
                title = "Due Today",
                value = "${projection.todayOpenTasks}",
                icon = Icons.Default.EventAvailable,
                badgeColor = StatusWarningAmber,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_today_open_tasks"),
                onClick = {
                    projection.drillDowns["TODAY_OPEN_TASKS"]?.let { activeDrillDownSpec = it }
                        ?: onNavigateToCrm()
                }
            )
            OperationalKpiCard(
                title = "Overdue",
                value = "${projection.overdueOpenTasks}",
                icon = Icons.Default.Warning,
                badgeColor = StatusDangerRed,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_overdue_open_tasks"),
                onClick = {
                    projection.drillDowns["OVERDUE_OPEN_TASKS"]?.let { activeDrillDownSpec = it }
                        ?: onNavigateToCrm()
                }
            )
            OperationalKpiCard(
                title = "Visits Today",
                value = "${projection.todayScheduledVisits}",
                icon = Icons.Default.DirectionsWalk,
                badgeColor = StatusInfoBlue,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_today_visits"),
                onClick = {
                    projection.drillDowns["TODAY_SCHEDULED_VISITS"]?.let { activeDrillDownSpec = it }
                        ?: onNavigateToVisits()
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OperationalKpiCard(
                title = "Active Requirements",
                value = "${projection.activeRequirements}",
                icon = Icons.Default.Groups,
                badgeColor = PrimaryActionOrange,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_active_requirements"),
                onClick = {
                    projection.drillDowns["ACTIVE_REQUIREMENTS"]?.let { activeDrillDownSpec = it }
                        ?: onNavigateToCrm()
                }
            )
            OperationalKpiCard(
                title = "Unassigned",
                value = "${projection.unassignedRequirements}",
                icon = Icons.Default.PersonAddAlt1,
                badgeColor = StatusWarningAmber,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_unassigned_requirements"),
                onClick = {
                    projection.drillDowns["UNASSIGNED_REQUIREMENTS"]?.let { activeDrillDownSpec = it }
                        ?: onNavigateToCrm()
                }
            )
            OperationalKpiCard(
                title = "Imported Review",
                value = "${projection.importedLeadsAwaitingReview}",
                icon = Icons.Default.RateReview,
                badgeColor = StatusInfoBlue,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_imported_leads_review"),
                onClick = {
                    projection.drillDowns["IMPORTED_LEADS_REVIEW"]?.let { activeDrillDownSpec = it }
                        ?: onNavigateToCrm()
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 4. Unified Inventory & Attention Metrics
        Text(
            text = "UNIFIED INVENTORY & ATTENTION METRICS",
            color = PrimaryActionOrange,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OperationalKpiCard(
                title = "Commercial Properties",
                value = "$commercialCount",
                icon = Icons.Default.Business,
                badgeColor = PrimaryActionOrange,
                modifier = Modifier
                    .weight(1f)
                    .testTag("kpi_commercial_count"),
                onClick = onNavigateToCommercialInventory
            )
            OperationalKpiCard(
                title = "Residential Properties",
                value = "$residentialCount",
                icon = Icons.Default.HomeWork,
                badgeColor = StatusInfoBlue,
                modifier = Modifier
                    .weight(1f)
                    .testTag("kpi_residential_count"),
                onClick = onNavigateToResidentialInventory
            )
            OperationalKpiCard(
                title = "Available Inventory",
                value = "$availableCount",
                icon = Icons.Default.CheckCircle,
                badgeColor = StatusSuccessGreen,
                modifier = Modifier
                    .weight(1f)
                    .testTag("kpi_available_count"),
                onClick = {
                    projection.drillDowns["AVAILABLE_INVENTORY"]?.let { activeDrillDownSpec = it }
                        ?: onNavigateToInventory()
                }
            )
            OperationalKpiCard(
                title = "Lease / Sale",
                value = "$leaseCount / $saleCount",
                icon = Icons.Default.SwapHoriz,
                badgeColor = StatusInfoBlue,
                modifier = Modifier
                    .weight(1f)
                    .testTag("kpi_lease_sale_count"),
                onClick = onNavigateToInventory
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OperationalKpiCard(
                title = "Pending Verify",
                value = "${projection.pendingVerification}",
                icon = Icons.Default.VerifiedUser,
                badgeColor = StatusWarningAmber,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_pending_verification"),
                onClick = {
                    projection.drillDowns["PENDING_VERIFICATION"]?.let { activeDrillDownSpec = it }
                        ?: onOpenMasterSync()
                }
            )
            OperationalKpiCard(
                title = "60d+ Recheck",
                value = "$live60DayRecheckDueCount",
                icon = Icons.Default.History,
                badgeColor = StatusDangerRed,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_reverification_due"),
                onClick = {
                    projection.drillDowns["REVERIFICATION_DUE"]?.let { activeDrillDownSpec = it }
                        ?: onNavigateToInventory()
                }
            )
            OperationalKpiCard(
                title = "Vacancies",
                value = "${projection.upcomingVacancies}",
                icon = Icons.Default.EventRepeat,
                badgeColor = StatusInfoBlue,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_upcoming_vacancies"),
                onClick = {
                    projection.drillDowns["UPCOMING_VACANCIES"]?.let { activeDrillDownSpec = it }
                        ?: onNavigateToInventory()
                }
            )
            OperationalKpiCard(
                title = "Missing Info",
                value = "${projection.missingCriticalInfo}",
                icon = Icons.Default.Rule,
                badgeColor = StatusWarningAmber,
                modifier = Modifier
                    .weight(1f)
                    .testTag("metric_missing_info"),
                onClick = {
                    projection.drillDowns["MISSING_CRITICAL_INFO"]?.let { activeDrillDownSpec = it }
                        ?: onNavigateToInventory()
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. Recorded Team Results (Period-Bounded in Asia/Kolkata)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dashboard_recorded_results_card"),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = BorderStroke(1.dp, BorderGrey),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RECORDED TEAM RESULTS",
                            color = PrimaryActionOrange,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Period: ${projection.periodStartDate} to ${projection.periodEndDate} (${projection.zoneId})",
                            color = SecondaryText,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Period Selector Chips: Today | This Week | This Month | Custom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DashboardReportPeriod.entries.forEach { periodOption ->
                        val isSelected = selectedPeriod == periodOption
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedPeriod = periodOption
                                if (periodOption == DashboardReportPeriod.CUSTOM) {
                                    showCustomDateDialog = true
                                }
                            },
                            label = {
                                Text(
                                    text = periodOption.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SelectedContainerFill,
                                selectedLabelColor = TextPrimary
                            ),
                            modifier = Modifier.testTag("period_chip_${periodOption.name}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OperationalKpiCard(
                        title = "New Requirements",
                        value = "${projection.newRequirementsCount}",
                        icon = Icons.Default.AddChart,
                        badgeColor = PrimaryActionOrange,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("result_new_requirements"),
                        onClick = {
                            projection.drillDowns["RESULT_NEW_REQUIREMENTS"]?.let { activeDrillDownSpec = it }
                        }
                    )
                    OperationalKpiCard(
                        title = "Completed Follow-ups",
                        value = "${projection.completedFollowUpsCount}",
                        icon = Icons.Default.TaskAlt,
                        badgeColor = StatusSuccessGreen,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("result_completed_followups"),
                        onClick = {
                            projection.drillDowns["RESULT_COMPLETED_FOLLOWUPS"]?.let { activeDrillDownSpec = it }
                        }
                    )
                    OperationalKpiCard(
                        title = "Completed Visits",
                        value = "${projection.completedVisitsCount}",
                        icon = Icons.Default.WhereToVote,
                        badgeColor = StatusInfoBlue,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("result_completed_visits"),
                        onClick = {
                            projection.drillDowns["RESULT_COMPLETED_VISITS"]?.let { activeDrillDownSpec = it }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OperationalKpiCard(
                        title = "Closed Won",
                        value = "${projection.closedWonCount}",
                        icon = Icons.Default.EmojiEvents,
                        badgeColor = StatusSuccessGreen,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("result_closed_won"),
                        onClick = {
                            projection.drillDowns["RESULT_CLOSED_WON"]?.let { activeDrillDownSpec = it }
                        }
                    )
                    OperationalKpiCard(
                        title = "Closed Lost",
                        value = "${projection.closedLostCount}",
                        icon = Icons.Default.Cancel,
                        badgeColor = StatusDangerRed,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("result_closed_lost"),
                        onClick = {
                            projection.drillDowns["RESULT_CLOSED_LOST"]?.let { activeDrillDownSpec = it }
                        }
                    )
                    OperationalKpiCard(
                        title = "Duplicate Reviews",
                        value = "${projection.duplicateReviews}",
                        icon = Icons.Default.FileCopy,
                        badgeColor = StatusWarningAmber,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("metric_duplicate_reviews"),
                        onClick = {
                            projection.drillDowns["DUPLICATE_REVIEWS"]?.let { activeDrillDownSpec = it }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 6. Primary Navigation Shortcuts (Commercial Inventory, Residential Inventory, Project Directory, CRM Leads)
        Text(
            text = "PRIMARY WORKSPACES",
            color = PrimaryActionOrange,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        PrimaryWorkspaceShortcutCard(
            title = "Commercial Inventory",
            subtitle = "$commercialCount saved units • $commercialAvailableCount available for lease/sale",
            badgeText = "COMMERCIAL",
            icon = Icons.Default.Business,
            accentColor = PrimaryActionOrange,
            testTag = "shortcut_commercial_inventory",
            onClick = onNavigateToCommercialInventory
        )

        Spacer(modifier = Modifier.height(8.dp))

        PrimaryWorkspaceShortcutCard(
            title = "Residential Inventory",
            subtitle = "$residentialCount saved units • $residentialAvailableCount available for rent/resale",
            badgeText = "RESIDENTIAL",
            icon = Icons.Default.HomeWork,
            accentColor = StatusInfoBlue,
            testTag = "shortcut_residential_inventory",
            onClick = onNavigateToResidentialInventory
        )

        Spacer(modifier = Modifier.height(8.dp))

        PrimaryWorkspaceShortcutCard(
            title = "Project Directory",
            subtitle = "Pune master projects, developers, RERA & ${underConstProjects.size} under-construction",
            badgeText = "PROJECTS",
            icon = Icons.Default.AccountTree,
            accentColor = PrimaryActionOrange,
            testTag = "shortcut_project_directory",
            onClick = onNavigateToProjects
        )

        Spacer(modifier = Modifier.height(8.dp))

        PrimaryWorkspaceShortcutCard(
            title = "CRM Leads & Follow-ups",
            subtitle = "${projection.activeRequirements} active requirements • ${projection.todayOpenTasks} due today • ${projection.overdueOpenTasks} overdue",
            badgeText = "CRM",
            icon = Icons.Default.Groups,
            accentColor = StatusInfoBlue,
            testTag = "shortcut_crm_leads",
            onClick = onNavigateToCrm
        )

        Spacer(modifier = Modifier.height(8.dp))

        PrimaryWorkspaceShortcutCard(
            title = "🔔 Renewal Radar (90d)",
            subtitle = "$renewalRadar90dCount leased / pre-leased units with lease or lock-in expiring within 90 days",
            badgeText = "90D RADAR",
            icon = Icons.Default.NotificationsActive,
            accentColor = StatusWarningAmber,
            testTag = "shortcut_renewal_radar_90d",
            onClick = { onSearchInventory("RENEWAL_RADAR_90D") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        PrimaryWorkspaceShortcutCard(
            title = "Deal Pipeline & LOI / Brokerage",
            subtitle = "LOI / Token → Agreement → Registration → Billed → Commission + 1-Tap LOI PDF",
            badgeText = "DEALS & LOI",
            icon = Icons.Default.Handshake,
            accentColor = StatusSuccessGreen,
            testTag = "shortcut_deal_pipeline",
            onClick = onNavigateToDeals
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 7. Quick Operational Actions Row
        Text(
            text = "QUICK ACTIONS",
            color = PrimaryActionOrange,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DashboardActionTile(
                label = "Add Property",
                icon = Icons.Default.AddBusiness,
                modifier = Modifier.weight(1f).testTag("dashboard_add_property_btn")
            ) { onNavigateToAddProperty() }
            DashboardActionTile(
                label = "Search All",
                icon = Icons.Default.ManageSearch,
                modifier = Modifier.weight(1f).testTag("dashboard_search_all_btn")
            ) { onNavigateToInventory() }
            DashboardActionTile(
                label = "Call Notes",
                icon = Icons.Default.Call,
                modifier = Modifier.weight(1f).testTag("dashboard_call_notes_btn")
            ) { onNavigateToCalls() }
            DashboardActionTile(
                label = "Master Sync",
                icon = Icons.Default.CloudSync,
                modifier = Modifier.weight(1f).testTag("dashboard_master_sync_btn")
            ) { onOpenMasterSync() }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 8. Recently Added Inventory
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RECENTLY ADDED INVENTORY",
                color = PrimaryActionOrange,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            TextButton(
                onClick = onNavigateToInventory,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("View All (${activeProperties.size})", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (recentlyAddedInventory.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                border = BorderStroke(1.dp, BorderGrey),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No accessible inventory records found",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Add a new property or run Master Sync to load inventory.",
                        color = SecondaryText,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onNavigateToAddProperty,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Property", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recentlyAddedInventory.forEach { prop ->
                    RecentInventoryCompactRow(
                        property = prop,
                        onClick = { onPropertyClick(prop) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 9. Itemized Work Queues: Today's Work & Inventory Attention
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = BorderStroke(1.dp, BorderGrey),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FormatListBulleted, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Today's Operational Work Queue",
                        color = TextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(color = BorderGrey, thickness = 0.8.dp)

                OperationalQueueItem(
                    label = "Overdue Follow-ups",
                    detail = "${projection.overdueOpenTasks} client follow-ups past scheduled target date",
                    dotColor = StatusDangerRed,
                    onClick = {
                        projection.drillDowns["OVERDUE_OPEN_TASKS"]?.let { activeDrillDownSpec = it }
                            ?: onNavigateToCrm()
                    }
                )
                OperationalQueueItem(
                    label = "Follow-ups Due Today",
                    detail = "${projection.todayOpenTasks} tasks scheduled for today in Asia/Kolkata",
                    dotColor = StatusWarningAmber,
                    onClick = {
                        projection.drillDowns["TODAY_OPEN_TASKS"]?.let { activeDrillDownSpec = it }
                            ?: onNavigateToCrm()
                    }
                )
                OperationalQueueItem(
                    label = "Properties Awaiting Verification",
                    detail = "${projection.pendingVerification} listings require price / availability audit",
                    dotColor = StatusInfoBlue,
                    onClick = {
                        projection.drillDowns["PENDING_VERIFICATION"]?.let { activeDrillDownSpec = it }
                            ?: onOpenMasterSync()
                    }
                )
                OperationalQueueItem(
                    label = "60-Day Verification Recheck Due",
                    detail = "${projection.reverificationDue} verified listings older than 60 days",
                    dotColor = PrimaryActionOrange,
                    onClick = {
                        projection.drillDowns["REVERIFICATION_DUE"]?.let { activeDrillDownSpec = it }
                            ?: onNavigateToInventory()
                    }
                )
                if (projection.upcomingVacancies > 0) {
                    OperationalQueueItem(
                        label = "Upcoming Leased Vacancies",
                        detail = "${projection.upcomingVacancies} commercial pre-leased renewals / upcoming expiries",
                        dotColor = StatusSuccessGreen,
                        onClick = {
                            projection.drillDowns["UPCOMING_VACANCIES"]?.let { activeDrillDownSpec = it }
                                ?: onNavigateToInventory()
                        }
                    )
                }
            }
        }

        // 10. Admin Pending Approvals (Visible to Admin & Managers)
        if (isAdminOrManager && pendingApprovals.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceMuted),
                border = BorderStroke(1.dp, StatusWarningAmber),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = StatusWarningAmber, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Pending Approvals (${pendingApprovals.size})",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            color = StatusWarningAmber,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "ACTION REQUIRED",
                                color = OnPrimaryActionColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    pendingApprovals.take(3).forEach { req ->
                        PendingApprovalRow(
                            request = req,
                            onApprove = {
                                coroutineScope.launch {
                                    approvalManager.decideApprovalRequest(req.requestId, approverEmployee, approved = true)
                                }
                            },
                            onReject = {
                                coroutineScope.launch {
                                    approvalManager.decideApprovalRequest(req.requestId, approverEmployee, approved = false, rejectionReason = "Declined from Home Dashboard")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Custom Date Range Dialog
    if (showCustomDateDialog) {
        var draftStart by remember(customStartDate) { mutableStateOf(customStartDate) }
        var draftEnd by remember(customEndDate) { mutableStateOf(customEndDate) }
        AlertDialog(
            onDismissRequest = { showCustomDateDialog = false },
            containerColor = SurfaceWhite,
            title = {
                Text("Custom Report Date Range (Asia/Kolkata)", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draftStart,
                        onValueChange = { draftStart = it.trim() },
                        label = { Text("Start Date (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draftEnd,
                        onValueChange = { draftEnd = it.trim() },
                        label = { Text("End Date (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        customStartDate = draftStart
                        customEndDate = draftEnd
                        selectedPeriod = DashboardReportPeriod.CUSTOM
                        showCustomDateDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Apply Range", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDateDialog = false }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }

    // Metric Drill-Down Modal Sheet (Every Count Opens Its Underlying Records)
    activeDrillDownSpec?.let { spec ->
        DashboardDrillDownBottomSheet(
            spec = spec,
            onDismiss = { activeDrillDownSpec = null },
            onOpenWorkspace = {
                activeDrillDownSpec = null
                when {
                    spec.metricKey.contains("INVENTORY") ||
                        spec.metricKey.contains("VERIFICATION") ||
                        spec.metricKey.contains("VACANC") ||
                        spec.metricKey.contains("MISSING") ||
                        spec.metricKey.contains("DUPLICATE") -> onNavigateToInventory()
                    spec.metricKey.contains("VISIT") -> onNavigateToVisits()
                    else -> onNavigateToCrm()
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardDrillDownBottomSheet(
    spec: DashboardDrillDownSpec,
    onDismiss: () -> Unit,
    onOpenWorkspace: () -> Unit
) {
    var visibleLimit by remember(spec.metricKey) { mutableIntStateOf(25) }
    val visibleItems = remember(spec.summaryItems, visibleLimit) {
        spec.summaryItems.take(visibleLimit)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
                .testTag("dashboard_drilldown_sheet")
        ) {
            Text(
                text = spec.title,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Counting Unit: ${spec.countingUnit}",
                color = PrimaryActionOrange,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = spec.filterSnapshot,
                color = SecondaryText,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = BorderGrey)
            Spacer(modifier = Modifier.height(10.dp))

            if (visibleItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No matching records in this scope.",
                        color = SecondaryText,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = visibleItems,
                        key = { idx, item -> "${idx}_${item.first}_${item.second.hashCode()}" }
                    ) { _, (primaryTitle, secondarySubtitle) ->
                        Surface(
                            onClick = onOpenWorkspace,
                            shape = RoundedCornerShape(10.dp),
                            color = SurfaceMuted,
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = primaryTitle,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = secondarySubtitle,
                                        color = SecondaryText,
                                        fontSize = 11.sp
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Open Record",
                                    tint = PrimaryActionOrange
                                )
                            }
                        }
                    }
                }

                if (spec.summaryItems.size > visibleLimit) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { visibleLimit += 25 },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = "Show Next 25 (${spec.summaryItems.size - visibleLimit} remaining)",
                            color = PrimaryActionOrange,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Close", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onOpenWorkspace,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("drilldown_open_workspace_btn")
                ) {
                    Text("Open Filtered List", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PrimaryWorkspaceShortcutCard(
    title: String,
    subtitle: String,
    badgeText: String,
    icon: ImageVector,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = SurfaceWhite,
        border = BorderStroke(1.dp, BorderGrey),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = title,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            color = accentColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = badgeText,
                                color = accentColor,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open $title",
                tint = PrimaryActionOrange,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun RecentInventoryCompactRow(
    property: PropertyItem,
    onClick: () -> Unit
) {
    val isCommercial = property.propertyCategory == PropertyCategory.COMMERCIAL
    val categoryColor = if (isCommercial) PrimaryActionOrange else StatusInfoBlue
    val effectivePrice = when {
        property.tab.isRental && (property.rent ?: 0.0) > 0.0 -> property.rent ?: 0.0
        !property.tab.isRental && (property.priceSale ?: 0.0) > 0.0 -> property.priceSale ?: 0.0
        else -> property.priceOrRent
    }
    val priceDisplay = if (effectivePrice > 0.0) {
        if (property.tab.isRental) "${CalculationUtils.formatIndianCurrency(effectivePrice)}/mo"
        else CalculationUtils.formatIndianCurrency(effectivePrice)
    } else {
        "Price on Request"
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = SurfaceWhite,
        border = BorderStroke(0.8.dp, BorderGrey),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("recent_property_${property.propertyCode}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = categoryColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = property.propertyCode.ifBlank { property.id },
                            color = categoryColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = property.projectName.ifBlank { "Unnamed Project" },
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${property.propertyCategory.label} • ${property.location.ifBlank { "Location N/A" }} • ${CalculationUtils.formatNumber(property.carpetAreaSqFt)} sq.ft",
                    color = SecondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = priceDisplay,
                    color = PrimaryActionOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = property.status.label,
                    color = if (property.status == AvailabilityStatus.AVAILABLE) StatusSuccessGreen else SecondaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun OperationalKpiCard(
    title: String,
    value: String,
    icon: ImageVector,
    badgeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 78.dp),
        color = SurfaceWhite,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.8.dp, BorderGrey)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                color = SecondaryText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 12.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun DashboardActionTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        color = SurfaceWhite,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.8.dp, BorderGrey)
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = label, tint = PrimaryActionOrange, modifier = Modifier.size(19.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OperationalQueueItem(
    label: String,
    detail: String,
    dotColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(label, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Text(detail, color = SecondaryText, fontSize = 10.sp)
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PendingApprovalRow(
    request: ApprovalRequestEntity,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.8.dp, BorderGrey),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${request.actionType} • ${request.targetId}",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "By: ${request.requestedByName}",
                    color = SecondaryText,
                    fontSize = 10.sp
                )
            }
            if (request.reason.isNotBlank()) {
                Text(
                    text = "Reason: ${request.reason}",
                    color = SecondaryText,
                    fontSize = 11.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = StatusSuccessGreen, contentColor = Color.White),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Approve", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onReject,
                    border = BorderStroke(1.dp, StatusDangerRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusDangerRed),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Reject", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DailyAiPriorityBriefingCard(
    briefing: DailyAiBriefingResult?,
    isLoading: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRefresh: () -> Unit,
    onActionClick: (DailyBriefingAction) -> Unit
) {
    Surface(
        color = PrimaryNavy,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BrandGold.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_daily_ai_briefing_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(BrandGold.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Daily AI Priority Briefing",
                            tint = BrandGold,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Daily AI Priority Briefing",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                        Text(
                            text = briefing?.headline ?: "Analyzing overdue follow-ups, hot deals & AI Inbox...",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = BrandGold,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(16.dp)
                        )
                    } else {
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("dashboard_ai_briefing_refresh_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Daily AI Briefing",
                                tint = BrandGold,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("dashboard_ai_briefing_toggle_btn")
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse Briefing" else "Expand Briefing",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (expanded && briefing != null && briefing.priorityBullets.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    briefing.priorityBullets.forEachIndexed { idx, action ->
                        val ctaLabel = when (action.targetKey) {
                            "AI_INBOX" -> "Open Inbox"
                            "VISITS" -> "Visits"
                            "CALLS" -> "Calls"
                            "INVENTORY" -> "Inventory"
                            else -> "Open CRM"
                        }
                        Surface(
                            onClick = { onActionClick(action) },
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.14f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dashboard_ai_briefing_action_$idx")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = action.title,
                                        color = BrandGold,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = action.detail,
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = PrimaryActionOrange,
                                    shape = RoundedCornerShape(7.dp)
                                ) {
                                    Text(
                                        text = ctaLabel,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                if (!briefing.statusNote.isNullOrBlank()) {
                    Text(
                        text = briefing.statusNote,
                        color = Color(0xFF94A3B8),
                        fontSize = 9.5.sp
                    )
                }
            }
        }
    }
}

