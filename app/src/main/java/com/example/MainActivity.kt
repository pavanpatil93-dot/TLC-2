package com.example

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.access.*
import com.example.callintelligence.CallReviewDatabase
import com.example.callintelligence.CallReviewEntity
import com.example.callintelligence.CallReviewScreen
import com.example.crm.*
import com.example.data.OfflineDiskCache
import com.example.data.SheetsRepository
import com.example.data.ActualDataBootstrap
import com.example.data.OneTapSyncCoordinator
import com.example.data.UserSettingsManager
import com.example.data.projects.DirectoryProject
import com.example.data.projects.ProjectDirectoryStore
import com.example.model.*
import com.example.notifications.AlertTargetType
import com.example.notifications.TeamAlertStore
import com.example.notifications.TeamNotificationManager
import com.example.ui.components.*
import com.example.ui.screens.*
import com.example.ui.shortlist.ShortlistReviewBottomSheet
import com.example.ui.shortlist.ShortlistViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NotificationAlertDeepLink(
    val alertId: String,
    val targetType: AlertTargetType,
    val targetId: String
)

class MainActivity : ComponentActivity() {
    private var pendingAlertDeepLink by mutableStateOf<NotificationAlertDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TeamNotificationManager.ensureChannelsCreated(this)
        extractAlertDeepLink(intent)?.let { pendingAlertDeepLink = it }
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppPortal(
                    pendingAlertDeepLink = pendingAlertDeepLink,
                    onConsumeAlertDeepLink = { pendingAlertDeepLink = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractAlertDeepLink(intent)?.let { pendingAlertDeepLink = it }
    }

    private fun extractAlertDeepLink(intent: Intent?): NotificationAlertDeepLink? {
        if (intent == null) return null
        val alertId = intent.getStringExtra(TeamNotificationManager.EXTRA_ALERT_ID).orEmpty()
        val typeRaw = intent.getStringExtra(TeamNotificationManager.EXTRA_TARGET_TYPE).orEmpty()
        val targetId = intent.getStringExtra(TeamNotificationManager.EXTRA_TARGET_ID).orEmpty()
        if (alertId.isBlank() && targetId.isBlank()) return null
        return NotificationAlertDeepLink(
            alertId = alertId,
            targetType = AlertTargetType.fromString(typeRaw),
            targetId = targetId
        )
    }
}

@Composable
fun MainAppPortal(
    pendingAlertDeepLink: NotificationAlertDeepLink? = null,
    onConsumeAlertDeepLink: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val repository = remember(context) {
        SheetsRepository(context, deferInitialDiskLoad = true)
    }
    LaunchedEffect(repository) {
        withContext(Dispatchers.IO) {
            TeamNotificationManager.ensureChannelsCreated(context)
            TeamAlertStore.hydrate(context)
            repository.ensureLoadedFromDisk()
            com.example.data.BackgroundSyncScheduler.ensureMasterAutoSyncScheduled(context)
            repository.syncIfStaleInBackground(SheetsRepository.FOREGROUND_RESUME_PULL_INTERVAL_MS)
        }
    }
    val userSettingsManager = remember(context) { UserSettingsManager(context) }

    val allProperties by repository.properties.collectAsState()
    val allUnderConstProjects by repository.underConstProjects.collectAsState()
    val favoriteIds by repository.favoriteIds.collectAsState()
    val comparisonIds by repository.comparisonIds.collectAsState()
    val isLiveConnected by repository.isLiveConnected.collectAsState()
    val lastSyncTimestamp by repository.lastSyncTimestamp.collectAsState()
    val lastSyncAtMillis by repository.lastSyncAtMillis.collectAsState()
    val syncRevision by repository.syncRevision.collectAsState()
    val whatsappUnreadCount by WhatsAppFollowUpStore.unreadCount.collectAsState()
    val dailyReports by repository.dailyReports.collectAsState()
    val tenantsList by repository.tenants.collectAsState()
    val brokersVendorsList by repository.brokersVendors.collectAsState()

    val userProfile by userSettingsManager.userProfile.collectAsState()
    val isLoggedIn by userSettingsManager.isLoggedIn.collectAsState()
    val savedScriptUrl by repository.sheetsScriptUrl.collectAsState()
    val pendingSyncOps by repository.pendingSyncOps.collectAsState()
    val pendingSyncWritesCount by repository.pendingSyncCount.collectAsState()
    val topSyncChipState by repository.topSyncChipState.collectAsState()
    val syncBannerState by repository.syncBannerState.collectAsState()
    val availableAppUpdate by repository.availableAppUpdate.collectAsState()
    var isSyncingPending by remember { mutableStateOf(false) }
    val totalPendingSyncCount = remember(pendingSyncOps, pendingSyncWritesCount) {
        maxOf(pendingSyncOps.size, pendingSyncWritesCount)
    }
    val brandLogos by userSettingsManager.brandLogos.collectAsState()
    val activeBrandScope by userSettingsManager.activeBrand.collectAsState()
    val currentThemeMode by userSettingsManager.themeMode.collectAsState()
    val autoSaveContactsEnabled by userSettingsManager.autoSaveGoogleContacts.collectAsState()
    val revealConfidentialPhonesForExec by userSettingsManager.revealConfidentialPhonesForExec.collectAsState()

    // Navigation and Drawer State
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var currentDestination by remember { mutableStateOf(DrawerDestination.INVENTORY_DASHBOARD) }
    var isProjectDirectoryScrolling by remember { mutableStateOf(false) }
    var previousNonComparisonDestination by remember { mutableStateOf(DrawerDestination.SEARCH_PROPERTIES) }
    var crmWorkspaceSelected by remember { mutableStateOf(false) }
    var selectedBrand by remember { mutableStateOf<Brand?>(null) }
    var showUnclassifiedReview by remember { mutableStateOf(false) }
    var showCallReview by remember { mutableStateOf(false) }
    var showSavedCallReviews by remember { mutableStateOf(false) }
    val callReviewDao = remember(context) { CallReviewDatabase.get(context).callReviewDao() }
    val saveableStateHolder = rememberSaveableStateHolder()

    var directoryProjects by remember { mutableStateOf<List<DirectoryProject>>(emptyList()) }
    var crmExternalRefreshTick by remember { mutableIntStateOf(0) }
    var callsExternalRefreshTick by remember { mutableIntStateOf(0) }
    var activePostCall by remember { mutableStateOf<CrmCallRecord?>(null) }
    var unloggedCallQueue by remember { mutableStateOf<List<CrmCallRecord>>(emptyList()) }
    var postCallMatchTargetLead by remember { mutableStateOf<CrmLead?>(null) }
    var postCallVisitTargetLead by remember { mutableStateOf<CrmLead?>(null) }

    var activeDetailProperty by remember { mutableStateOf<PropertyItem?>(null) }
    var activeDetailProject by remember { mutableStateOf<UnderConstProject?>(null) }
    var editingPropertyTarget by remember { mutableStateOf<PropertyItem?>(null) }
    var showComparisonScreen by remember { mutableStateOf(false) }
    var comparisonNotice by remember { mutableStateOf<String?>(null) }
    var resolvedComparisonProps by remember { mutableStateOf<List<PropertyItem>>(emptyList()) }
    var mapTargetProperty by remember { mutableStateOf<PropertyItem?>(null) }
    var matchingBrokersTargetProperty by remember { mutableStateOf<PropertyItem?>(null) }
    var pendingProjectDirectoryId by remember { mutableStateOf<String?>(null) }
    var clientMatcherInitialRequirement by remember { mutableStateOf<String?>(null) }
    var pendingTenantLinkProperty by remember { mutableStateOf<PropertyItem?>(null) }
    var pendingTenantId by remember { mutableStateOf<String?>(null) }
    var pendingTenantDraft by remember { mutableStateOf<OfflineDiskCache.TenantCacheRecord?>(null) }
    var pendingBrokerVendorDraft by remember { mutableStateOf<BrokerVendorRecord?>(null) }
    var pendingTenantsTabIndex by remember { mutableIntStateOf(0) }
    var pendingCrmPrefillLead by remember { mutableStateOf<CrmLead?>(null) }
    var pendingCrmSelectedLeadId by remember { mutableStateOf<String?>(null) }
    var showTeamAlertsSheet by remember { mutableStateOf(false) }
    val teamAlerts by TeamAlertStore.alerts.collectAsState()
    val unreadAlertCount = remember(teamAlerts) { teamAlerts.count { !it.isRead } }

    val postNotificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Gracefully handled whether granted or denied */ }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = context.getSharedPreferences("ascend_aurea_user_settings", Context.MODE_PRIVATE)
            val alreadyPrompted = prefs.getBoolean("notif_permission_prompted_v1", false)
            if (!alreadyPrompted && !TeamNotificationManager.hasNotificationPermission(context)) {
                prefs.edit().putBoolean("notif_permission_prompted_v1", true).apply()
                runCatching { postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }

    val navigateToAlertTarget: (String, AlertTargetType, String) -> Unit = { alertId, targetType, targetId ->
        if (alertId.isNotBlank()) {
            TeamAlertStore.markRead(context, alertId)
        }
        showTeamAlertsSheet = false
        showComparisonScreen = false
        activeDetailProject = null
        editingPropertyTarget = null
        when (targetType) {
            AlertTargetType.PROPERTY -> {
                val matched = allProperties.firstOrNull {
                    it.id.equals(targetId, ignoreCase = true) ||
                        it.recordId.equals(targetId, ignoreCase = true) ||
                        it.propertyCode.equals(targetId, ignoreCase = true)
                }
                if (matched != null) {
                    activeDetailProperty = matched
                } else {
                    activeDetailProperty = null
                    currentDestination = DrawerDestination.SEARCH_PROPERTIES
                }
            }
            AlertTargetType.LEAD -> {
                activeDetailProperty = null
                pendingCrmSelectedLeadId = targetId.takeIf { it.isNotBlank() }
                currentDestination = DrawerDestination.CRM_WORKSPACE
            }
            AlertTargetType.TENANT -> {
                activeDetailProperty = null
                pendingTenantId = targetId.takeIf { it.isNotBlank() }
                pendingTenantsTabIndex = 0
                currentDestination = DrawerDestination.TENANTS_LEASES
            }
            AlertTargetType.NONE -> {
                activeDetailProperty = null
                currentDestination = DrawerDestination.INVENTORY_DASHBOARD
            }
        }
    }

    LaunchedEffect(pendingAlertDeepLink, isLoggedIn, allProperties.size) {
        val dl = pendingAlertDeepLink ?: return@LaunchedEffect
        if (!isLoggedIn) return@LaunchedEffect
        // Wait for property list if targeting a property and list is still loading
        if (dl.targetType == AlertTargetType.PROPERTY && allProperties.isEmpty()) return@LaunchedEffect
        navigateToAlertTarget(dl.alertId, dl.targetType, dl.targetId)
        onConsumeAlertDeepLink()
    }

    var pendingAddPropertyName by remember { mutableStateOf("") }
    var pendingAddPropertyPhone by remember { mutableStateOf("") }
    var pendingAddPropertyNote by remember { mutableStateOf("") }
    var pendingAddPropertyPocType by remember { mutableStateOf("Property Owner") }
    var pendingAddPropertyTab by remember { mutableStateOf<InventoryTab?>(null) }
    var pendingAddPropertyFromCall by remember { mutableStateOf(false) }
    var postCallQueue by remember { mutableStateOf<List<CrmCallRecord>>(emptyList()) }
    var postCallQueueIndex by remember { mutableIntStateOf(0) }
    var pendingCallNavigation by remember { mutableStateOf<PendingCallNavigation?>(null) }
    val activeCallSession by CallActionSessionManager.activeSession.collectAsState()
    val shortlistViewModel: ShortlistViewModel = viewModel()
    val shortlistState by shortlistViewModel.state.collectAsState()

    val oneTapSyncCoordinator = remember(context, repository) {
        OneTapSyncCoordinator(context, repository)
    }
    val oneTapSyncReport by oneTapSyncCoordinator.syncReport.collectAsState()
    val isSyncResultSheetVisible by oneTapSyncCoordinator.isResultSheetVisible.collectAsState()

    var settingsInitialTab by remember { mutableIntStateOf(0) }
    val sessionManager = remember(context, userSettingsManager, shortlistViewModel) {
        EmployeeSessionManager(
            context = context,
            userSettingsManager = userSettingsManager,
            onClearInMemoryState = {
                activeDetailProperty = null
                activeDetailProject = null
                editingPropertyTarget = null
                showComparisonScreen = false
                resolvedComparisonProps = emptyList()
                currentDestination = DrawerDestination.INVENTORY_DASHBOARD
                shortlistViewModel.clearShortlist()
            }
        )
    }
    val sessionState by sessionManager.sessionState.collectAsState()

    LaunchedEffect(sessionManager) {
        sessionManager.initialize()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    // Passive background call refresh: updates Home Dashboard counter + Calls tab badge without auto-interrupting the user on app launch/resume.
    val refreshAndPromptPostCall: () -> Unit = {
        if (isLoggedIn && !sessionState.isPinLocked) {
            coroutineScope.launch {
                val (newCalls, updatedQueue) = withContext(Dispatchers.IO) {
                    val detected = CrmCallManager.checkForNewUnloggedDeviceCalls(context)
                    val queue = CrmCallManager.getUnloggedCallQueue(context)
                    Pair(detected, queue)
                }
                unloggedCallQueue = updatedQueue
                if (newCalls.isNotEmpty()) {
                    callsExternalRefreshTick++
                }
            }
        }
    }

    var showDealPipelineScreen by remember { mutableStateOf(false) }

    // Batch 20: Top-level LaunchedEffect(pendingCallNavigation) for "Add More Details →"
    LaunchedEffect(pendingCallNavigation) {
        val nav = pendingCallNavigation ?: return@LaunchedEffect
        activePostCall = null
        postCallQueue = emptyList()
        postCallQueueIndex = 0
        activeDetailProperty = null
        activeDetailProject = null
        showComparisonScreen = false
        showDealPipelineScreen = false

        when (nav.role) {
            PostCallRole.CLIENT_LEAD, PostCallRole.FRESH_UNKNOWN -> {
                val existingLeads = withContext(Dispatchers.IO) { readCrmLeads(context) }
                val phone10 = CrmCallManager.last10Digits(nav.prefillPhone)
                val matchedLead = existingLeads.firstOrNull { lead ->
                    (!nav.taggedPropertyId.isNullOrBlank() && lead.id == nav.taggedPropertyId) ||
                        (phone10.length >= 7 && (
                            CrmCallManager.last10Digits(lead.phone) == phone10 ||
                                CrmCallManager.last10Digits(lead.alternatePhone) == phone10
                            ))
                }
                val targetSegment = nav.segmentHint.ifBlank { "Commercial" }
                pendingCrmPrefillLead = matchedLead ?: CrmLead(
                    id = nav.taggedPropertyId?.takeIf { it.startsWith("LD-", true) }.orEmpty(),
                    name = nav.prefillName,
                    phone = nav.prefillPhone,
                    companyName = if (nav.isBrokerClient) nav.prefillName else "",
                    clientType = if (nav.isBrokerClient) "Broker / CP" else "Direct Client",
                    leadSource = if (nav.isBrokerClient) "Channel Partner / Broker" else "Phone Call",
                    segment = targetSegment,
                    requirement = nav.prefillNote.ifBlank { "Inbound Call Inquiry" },
                    location = "",
                    budget = "",
                    status = "New Lead",
                    verificationStatus = "Verified Live",
                    nextFollowUp = "",
                    notes = nav.prefillNote
                )
                crmExternalRefreshTick++
                currentDestination = DrawerDestination.CRM_WORKSPACE
            }
            PostCallRole.PROPERTY_OWNER -> {
                val matchedProp = nav.taggedPropertyId?.let { id ->
                    allProperties.firstOrNull { it.id == id || it.propertyCode.equals(id, true) }
                }
                if (matchedProp != null) {
                    editingPropertyTarget = matchedProp.copy(
                        ownerName = nav.prefillName.ifBlank { matchedProp.ownerName.orEmpty() },
                        ownerContact = nav.prefillPhone.ifBlank { matchedProp.ownerContact.orEmpty() },
                        remarks = if (nav.prefillNote.isNotBlank() && !matchedProp.remarks.orEmpty().contains(nav.prefillNote)) {
                            listOfNotNull(matchedProp.remarks?.takeIf { it.isNotBlank() }, nav.prefillNote).joinToString(" | ")
                        } else {
                            matchedProp.remarks
                        }
                    )
                } else {
                    pendingAddPropertyName = nav.prefillName
                    pendingAddPropertyPhone = nav.prefillPhone
                    pendingAddPropertyNote = nav.prefillNote
                    pendingAddPropertyPocType = "Property Owner"
                    pendingAddPropertyTab = nav.initialPropertyTab
                    pendingAddPropertyFromCall = true
                    currentDestination = DrawerDestination.ADD_PROPERTY
                }
            }
            PostCallRole.DEVELOPER_POC -> {
                pendingProjectDirectoryId = nav.taggedPropertyId
                currentDestination = DrawerDestination.PROJECT_DIRECTORY
            }
            PostCallRole.TENANT -> {
                val existingTenant = nav.taggedPropertyId?.let { id ->
                    tenantsList.firstOrNull { it.id == id || it.propertyCode.equals(id, true) }
                }
                val linkedProp = nav.taggedPropertyId?.let { id ->
                    allProperties.firstOrNull { it.id == id || it.propertyCode.equals(id, true) }
                }
                pendingTenantDraft = existingTenant ?: OfflineDiskCache.TenantCacheRecord(
                    id = nav.taggedPropertyId?.takeIf { it.startsWith("TNT-", true) }
                        ?: "TNT-${java.util.UUID.randomUUID().toString().take(8).uppercase(java.util.Locale.US)}",
                    tenantName = nav.prefillName,
                    contactNumber = nav.prefillPhone,
                    propertyCode = linkedProp?.propertyCode ?: nav.taggedPropertyId.orEmpty(),
                    projectName = linkedProp?.projectName.orEmpty(),
                    unitNumber = linkedProp?.unitNumber.orEmpty(),
                    location = linkedProp?.location.orEmpty(),
                    notes = nav.prefillNote,
                    status = "Active"
                )
                pendingTenantId = existingTenant?.id
                pendingTenantLinkProperty = null
                pendingBrokerVendorDraft = null
                pendingTenantsTabIndex = 0
                currentDestination = DrawerDestination.TENANTS_LEASES
            }
            PostCallRole.BROKER, PostCallRole.OPS_VENDOR -> {
                val isOps = nav.role == PostCallRole.OPS_VENDOR
                val existingBv = nav.taggedPropertyId?.let { id ->
                    brokersVendorsList.firstOrNull { it.id == id }
                }
                pendingBrokerVendorDraft = existingBv ?: BrokerVendorRecord(
                    id = nav.taggedPropertyId?.takeIf { it.startsWith("BV-", true) }
                        ?: "BV-${java.util.UUID.randomUUID().toString().take(8).uppercase(java.util.Locale.US)}",
                    name = nav.prefillName,
                    phone = nav.prefillPhone,
                    roleCategory = if (isOps) "Ops / Vendor" else "Broker / CP",
                    subRole = nav.subRole.ifBlank { if (isOps) BrokerVendorRecord.opsSubRoles.first() else "Channel Partner" },
                    notes = nav.prefillNote
                )
                pendingTenantId = null
                pendingTenantDraft = null
                pendingTenantLinkProperty = null
                pendingTenantsTabIndex = 2
                currentDestination = DrawerDestination.TENANTS_LEASES
            }
        }
        pendingCallNavigation = null
    }
    DisposableEffect(lifecycleOwner, sessionManager, isLoggedIn, sessionState.isPinLocked) {
        val unregisterCallMonitor = CrmCallManager.registerCallStateMonitor(context) {
            refreshAndPromptPostCall()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    coroutineScope.launch {
                        sessionManager.onAppBackgrounded()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    refreshAndPromptPostCall()
                    repository.refreshTopSyncChipState()
                    repository.syncIfStaleInBackground(SheetsRepository.FOREGROUND_RESUME_PULL_INTERVAL_MS)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregisterCallMonitor()
        }
    }

    val currentEmployee = sessionState.currentEmployee
    val effectiveUserProfile = remember(isLoggedIn, userProfile, currentEmployee) {
        if (isLoggedIn) userProfile else (currentEmployee?.toLegacyUserProfile() ?: userProfile)
    }
    val isSuperAdmin = isLoggedIn && (
        effectiveUserProfile.username.equals("Pawan Patil", ignoreCase = true) ||
            effectiveUserProfile.role == UserRole.ADMIN
        )
    // Core team (Super Admin, Admin, Commercial/Residential/Inventory Managers, Sales Executives)
    // always see 100% of Live Commercial & Residential properties with full Owner/Tenant contacts.
    val isCoreTeamMember = isLoggedIn && (
        isSuperAdmin ||
            effectiveUserProfile.role in setOf(
                UserRole.ADMIN,
                UserRole.COMMERCIAL_MANAGER,
                UserRole.RESIDENTIAL_MANAGER,
                UserRole.INVENTORY_MANAGER,
                UserRole.SALES_EXECUTIVE
            )
        )
    val allowCommercial = isLoggedIn && (
        isCoreTeamMember ||
            effectiveUserProfile.division.contains("Commercial", ignoreCase = true) ||
            !effectiveUserProfile.division.contains("Residential", ignoreCase = true)
        )
    val allowResidential = isLoggedIn && (
        isCoreTeamMember ||
            effectiveUserProfile.division.contains("Residential", ignoreCase = true) ||
            !effectiveUserProfile.division.contains("Commercial", ignoreCase = true)
        )
    val isInternalMode = isLoggedIn &&
        effectiveUserProfile.role != UserRole.READ_ONLY &&
        effectiveUserProfile.role != UserRole.BROKER &&
        effectiveUserProfile.role != UserRole.OWNER

    var pendingExploreSearchQuery by remember { mutableStateOf("") }

    var scopedProperties by remember { mutableStateOf<List<PropertyItem>>(emptyList()) }
    var commercialCount by remember { mutableIntStateOf(0) }
    var residentialCount by remember { mutableIntStateOf(0) }
    var unclassifiedCount by remember { mutableIntStateOf(0) }
    val scopedUnderConstProjects = remember(allUnderConstProjects, isLoggedIn, allowResidential) {
        if (!isLoggedIn || !allowResidential) emptyList() else allUnderConstProjects
    }

    LaunchedEffect(allProperties, isLoggedIn, allowCommercial, allowResidential, scopedUnderConstProjects) {
        if (!isLoggedIn) {
            scopedProperties = emptyList()
            commercialCount = 0
            residentialCount = 0
            unclassifiedCount = 0
        } else {
            val result = withContext(Dispatchers.Default) {
                val filtered = allProperties.filter { prop ->
                    val isRes = prop.isResidential()
                    val isComm = prop.isCommercial()
                    when {
                        allowCommercial && allowResidential -> true
                        allowCommercial -> !isRes
                        allowResidential -> !isComm
                        else -> false
                    }
                }
                val comm = filtered.count { it.isCommercial() }
                val res = filtered.count { it.isResidential() } + scopedUnderConstProjects.size
                val uncl = filtered.count { it.isUnclassified() }
                Pair(filtered, Triple(comm, res, uncl))
            }
            scopedProperties = result.first
            commercialCount = result.second.first
            residentialCount = result.second.second
            unclassifiedCount = result.second.third
        }
    }

    LaunchedEffect(scopedProperties) {
        shortlistViewModel.updateInventory(scopedProperties)
    }
    LaunchedEffect(effectiveUserProfile.username) {
        shortlistViewModel.updateEmployeeName(effectiveUserProfile.username)
    }

    LaunchedEffect(effectiveUserProfile) {
        repository.loadComparisonForUser(effectiveUserProfile)
    }

    LaunchedEffect(effectiveUserProfile.employeeId, currentEmployee?.employeeId) {
        userSettingsManager.syncThemeForEmployee(
            effectiveUserProfile.employeeId.ifBlank { currentEmployee?.employeeId }
        )
    }

    LaunchedEffect(isLoggedIn, allowCommercial, allowResidential) {
        if (isLoggedIn) {
            if (allowCommercial && !allowResidential) {
                selectedBrand = Brand.COMMERCIAL
            } else if (allowResidential && !allowCommercial) {
                selectedBrand = Brand.RESIDENTIAL
            }
        }
    }

    LaunchedEffect(isLoggedIn, effectiveUserProfile.mobileNumber, currentEmployee?.employeeId) {
        if (isLoggedIn) {
            val loadedDir = withContext(Dispatchers.IO) {
                val dbPhones = runCatching {
                    EmployeeAccessDatabase.get(context).employeeDao().getAll().map { it.loginMobile }
                }.getOrDefault(emptyList())
                val allEmpPhones = (dbPhones + listOfNotNull(
                    effectiveUserProfile.mobileNumber.takeIf { it.isNotBlank() },
                    currentEmployee?.loginMobile
                )).distinct()
                IgnoredNumbersManager.syncInternalEmployeeNumbers(context, allEmpPhones)
                CrmCallManager.retagInternalTeamCalls(context)
                com.example.crm.TeamHandoverStore.reconcileWithCallLogs(context)
                val dir = runCatching { ProjectDirectoryStore.read(context) }.getOrDefault(emptyList())
                val queue = CrmCallManager.getUnloggedCallQueue(context)
                Pair(dir, queue)
            }
            directoryProjects = loadedDir.first
            unloggedCallQueue = loadedDir.second
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            WhatsAppFollowUpStore.refreshUnreadCount(context)
        }
    }

    LaunchedEffect(syncRevision) {
        if (syncRevision > 0) {
            val reloadedProjects = withContext(Dispatchers.IO) {
                runCatching { ProjectDirectoryStore.read(context) }.getOrDefault(directoryProjects)
            }
            directoryProjects = reloadedProjects
            crmExternalRefreshTick++
            callsExternalRefreshTick++
        }
    }

    val performFullSignOut: () -> Unit = {
        userSettingsManager.logout()
        selectedBrand = null
        currentDestination = DrawerDestination.INVENTORY_DASHBOARD
        activeDetailProperty = null
        activeDetailProject = null
        editingPropertyTarget = null
        showComparisonScreen = false
        coroutineScope.launch {
            drawerState.close()
            sessionManager.signOut()
        }
    }

    val completeAuthenticatedLogin: suspend (UserProfile) -> Unit = { authenticatedProfile ->
        userSettingsManager.loginSuccess(authenticatedProfile)
        val mappedRole = when (authenticatedProfile.role) {
            UserRole.ADMIN -> EmployeeAccessRole.ADMIN
            UserRole.COMMERCIAL_MANAGER -> EmployeeAccessRole.COMMERCIAL_MANAGER
            UserRole.RESIDENTIAL_MANAGER -> EmployeeAccessRole.RESIDENTIAL_MANAGER
            UserRole.INVENTORY_MANAGER -> EmployeeAccessRole.INVENTORY_MANAGER
            UserRole.READ_ONLY -> EmployeeAccessRole.READ_ONLY
            else -> EmployeeAccessRole.SALES_EXECUTIVE
        }
        val isCoreTeamFullAccess = authenticatedProfile.username.equals("Pawan Patil", ignoreCase = true) ||
            mappedRole in setOf(
                EmployeeAccessRole.ADMIN,
                EmployeeAccessRole.COMMERCIAL_MANAGER,
                EmployeeAccessRole.RESIDENTIAL_MANAGER,
                EmployeeAccessRole.INVENTORY_MANAGER,
                EmployeeAccessRole.SALES_EXECUTIVE
            )
        val hasComm = isCoreTeamFullAccess || authenticatedProfile.division.contains("Commercial", ignoreCase = true)
        val hasRes = isCoreTeamFullAccess || authenticatedProfile.division.contains("Residential", ignoreCase = true)
        val mappedScopes = buildList {
            if (hasComm) add(BusinessDivisionScope.ASCEND_COMMERCIAL)
            if (hasRes) add(BusinessDivisionScope.AUREA_RESIDENTIAL)
            if (isEmpty()) {
                add(BusinessDivisionScope.ASCEND_COMMERCIAL)
                add(BusinessDivisionScope.AUREA_RESIDENTIAL)
            }
        }
        if (PhoneNormalizer.normalizeIndianMobile(authenticatedProfile.mobileNumber) != null) {
            sessionManager.provisionTrustedEmployee(
                employeeId = authenticatedProfile.employeeId.ifBlank { null },
                employeeName = authenticatedProfile.username,
                rawMobile = authenticatedProfile.mobileNumber,
                rawEmail = authenticatedProfile.email.ifBlank { null },
                role = mappedRole,
                businessScopes = mappedScopes,
                jobTitle = authenticatedProfile.division,
                accessStatus = EmployeeAccessStatus.ACTIVE,
                identityStatus = EmployeeIdentityStatus.VERIFIED_ACCOUNT,
                authorizationSource = AuthorizationSource.AUTHENTICATED_BACKEND
            )
            sessionManager.loginWithNameAndMobile(
                rawName = authenticatedProfile.username,
                rawMobile = authenticatedProfile.mobileNumber,
                rawEmail = authenticatedProfile.email.ifBlank { null }
            )
        }
        selectedBrand = when {
            isCoreTeamFullAccess || (hasComm && hasRes) -> null
            hasComm -> Brand.COMMERCIAL
            hasRes -> Brand.RESIDENTIAL
            else -> null
        }
    }

    if (!isLoggedIn) {
        EmployeeLoginScreen(
            initialScriptUrl = savedScriptUrl,
            onAuthenticate = { loginId, password, scriptUrl ->
                repository.authenticateEmployee(loginId, password, scriptUrl)
            },
            onUpdatePendingPassword = { empId, newPassword, scriptUrl ->
                repository.updateEmployeePassword(empId, newPassword, scriptUrl)
            },
            onLoginSuccess = { verifiedProfile ->
                coroutineScope.launch {
                    completeAuthenticatedLogin(verifiedProfile)
                }
            }
        )
        return
    }

    if (sessionState.isPinLocked && currentEmployee != null) {
        PinLockScreen(
            employee = currentEmployee,
            errorMessage = sessionState.lastErrorMessage,
            onVerifyPin = { pin ->
                coroutineScope.launch {
                    sessionManager.verifyPin(pin)
                }
            },
            onSwitchProfile = performFullSignOut,
            onSignOut = performFullSignOut
        )
        return
    }

    LaunchedEffect(showComparisonScreen, currentDestination, allProperties, effectiveUserProfile, comparisonIds) {
        if (showComparisonScreen || currentDestination == DrawerDestination.COMPARE_PROPERTIES) {
            val (resolved, notice) = repository.resolveAccessibleComparisonProperties(effectiveUserProfile)
            resolvedComparisonProps = resolved
            if (notice != null) {
                comparisonNotice = notice
            }
        } else {
            comparisonNotice = null
        }
    }

    val handleToggleCompare: (String) -> Unit = { propertyId ->
        val (ok, message) = repository.toggleCompare(propertyId, effectiveUserProfile)
        if (!ok && message.isNotBlank()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val closeComparisonScreen: (Boolean) -> Unit = { navigateToInventory ->
        showComparisonScreen = false
        comparisonNotice = null
        if (navigateToInventory) {
            currentDestination = when (previousNonComparisonDestination) {
                DrawerDestination.SEARCH_PROPERTIES,
                DrawerDestination.COMMERCIAL,
                DrawerDestination.RESIDENTIAL,
                DrawerDestination.SAVED_PROPERTIES -> previousNonComparisonDestination
                else -> DrawerDestination.SEARCH_PROPERTIES
            }
            crmWorkspaceSelected = false
        } else if (currentDestination == DrawerDestination.COMPARE_PROPERTIES) {
            currentDestination = previousNonComparisonDestination
        }
    }

    // Dialogs & WhatsApp Sharing
    var showWhatsAppShareDialog by remember { mutableStateOf(false) }
    var shareTargetPropertyId by remember { mutableStateOf<String?>(null) }
    var shareTargetFallbackProperty by remember { mutableStateOf<PropertyItem?>(null) }
    var shareTargetProject by remember { mutableStateOf<UnderConstProject?>(null) }
    var customShareMessage by remember { mutableStateOf<String?>(null) }

    val resolvedDetailProperty = remember(activeDetailProperty, allProperties) {
        activeDetailProperty?.let { detail ->
            allProperties.firstOrNull { it.id == detail.id } ?: detail
        }
    }

    val resolvedShareProperty = remember(shareTargetPropertyId, allProperties, shareTargetFallbackProperty) {
        shareTargetPropertyId?.let { id ->
            allProperties.firstOrNull { it.id == id }
        } ?: shareTargetFallbackProperty
    }

    val triggerPropertyShare: (PropertyItem) -> Unit = { prop ->
        val latest = allProperties.firstOrNull { it.id == prop.id } ?: prop
        shareTargetPropertyId = latest.id
        shareTargetFallbackProperty = latest
        shareTargetProject = null
        customShareMessage = null
        showWhatsAppShareDialog = true
    }

    val handleVerifyProperty: (PropertyItem, AvailabilityStatus?, String, String) -> Unit = { prop, avail, method, notes ->
        val res = repository.verifyAndPublishPropertyAsync(
            propertyId = prop.id,
            availabilityStatus = avail,
            method = method,
            notes = notes,
            verifiedBy = effectiveUserProfile.username
        )
        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
        if (!res.success && res.missingFields.isNotEmpty()) {
            editingPropertyTarget = prop
        } else if (res.success && activeDetailProperty?.id == prop.id) {
            activeDetailProperty = res.property ?: allProperties.find { it.id == prop.id }
        }
    }

    val handleToggleVerifyLive: (PropertyItem, Boolean) -> Unit = { prop, goLive ->
        if (goLive) {
            handleVerifyProperty(prop, prop.status, "Verification Switch", "")
        } else {
            val ok = repository.movePropertyToPendingAsync(prop.id)
            if (ok) {
                Toast.makeText(context, "Moved to Pending (Off-Market) — Saved & syncing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val handleInlineUpdateProperty: (PropertyItem, Boolean) -> Unit = { updatedProp, promoteToLive ->
        val finalProp = if (promoteToLive) {
            updatedProp.copy(
                verificationStatus = VerificationStatus.VERIFIED,
                archiveStatus = ArchiveStatus.ACTIVE,
                lastVerifiedDateClean = java.time.LocalDate.now().toString(),
                verifiedBy = effectiveUserProfile.username.ifBlank { updatedProp.verifiedBy }
            )
        } else {
            updatedProp
        }
        val summary = if (promoteToLive) "Inline Completeness Completed & Published Live" else "Inline Completeness Update"
        val saved = repository.updatePropertyAsync(finalProp, summary)
        if (saved) {
            if (activeDetailProperty?.id == finalProp.id) {
                activeDetailProperty = finalProp
            }
            Toast.makeText(
                context,
                if (promoteToLive) "Completed & published Live — syncing in background" else "Details saved — syncing in background",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(context, "Saved locally", Toast.LENGTH_SHORT).show()
        }
    }

    // Location & Map Review Sheet State (Prompt 07)
    val activityContext = androidx.compose.ui.platform.LocalContext.current
    var locationReviewPropertyId by remember { mutableStateOf<String?>(null) }
    var locationReviewForDirections by remember { mutableStateOf(false) }
    var locationReviewError by remember { mutableStateOf<String?>(null) }
    var lastMapLaunchTimeMs by remember { mutableLongStateOf(0L) }

    val launchMapUriSafely: (String, (String) -> Unit) -> Unit = { targetUri, onError ->
        val now = System.currentTimeMillis()
        if (now - lastMapLaunchTimeMs >= 1000L) {
            if (!targetUri.startsWith("https://")) {
                onError("Invalid or unsafe map URL.")
            } else {
                lastMapLaunchTimeMs = now
                val parsedUri = Uri.parse(targetUri)
                val mapIntent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                    if (activityContext !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val mapLaunched = runCatching { activityContext.startActivity(mapIntent) }.isSuccess
                if (!mapLaunched) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        if (activityContext !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val browserLaunched = runCatching { activityContext.startActivity(browserIntent) }.isSuccess
                    if (!browserLaunched) {
                        onError("No map or browser app available. Use Copy Location below.")
                    }
                }
            }
        }
    }

    val copyLocationSafely: (String, (String) -> Unit) -> Unit = { copyText, onResult ->
        if (copyText.isBlank()) {
            onResult("No location recorded to copy")
        } else {
            val copied = runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@runCatching false
                clipboard.setPrimaryClip(ClipData.newPlainText("Property Location", copyText))
                true
            }.getOrDefault(false)
            onResult(if (copied) "Location copied" else "Unable to copy location")
        }
    }

    val triggerPropertyMapAction: (PropertyItem, Boolean) -> Unit = { prop, forDirections ->
        val dest = repository.resolvePropertyMapDestination(prop.id, effectiveUserProfile)
        if (dest == null) {
            Toast.makeText(context, "Location unavailable for your current access scope.", Toast.LENGTH_SHORT).show()
        } else {
            val openReview = dest.mode == DestinationMode.UNAVAILABLE ||
                dest.hasConflict ||
                (forDirections && dest.mode == DestinationMode.ADDRESS_SEARCH)
            if (openReview) {
                locationReviewPropertyId = prop.id
                locationReviewForDirections = forDirections
                locationReviewError = null
            } else {
                val targetUri = if (forDirections) dest.directionsUri else dest.primaryUri
                launchMapUriSafely(targetUri) { errMsg ->
                    locationReviewPropertyId = prop.id
                    locationReviewForDirections = forDirections
                    locationReviewError = errMsg
                }
            }
        }
    }

    // Real-time counts within employee scope are computed off Main thread above alongside scopedProperties

    // Handle back button for sub-screens & drawer
    BackHandler(
        enabled = drawerState.isOpen ||
                activeDetailProperty != null ||
                activeDetailProject != null ||
                showComparisonScreen ||
                showUnclassifiedReview ||
                showCallReview ||
                showSavedCallReviews ||
                currentDestination != DrawerDestination.INVENTORY_DASHBOARD
    ) {
        when {
            drawerState.isOpen -> coroutineScope.launch { drawerState.close() }
            activeDetailProperty != null -> activeDetailProperty = null
            activeDetailProject != null -> activeDetailProject = null
            showComparisonScreen -> closeComparisonScreen(false)
            showCallReview -> showCallReview = false
            showSavedCallReviews -> showSavedCallReviews = false
            showUnclassifiedReview -> showUnclassifiedReview = false
            currentDestination != DrawerDestination.SEARCH_PROPERTIES -> currentDestination = DrawerDestination.SEARCH_PROPERTIES
        }
    }

    CompositionLocalProvider(
        LocalPropertyMapHandler provides { prop -> triggerPropertyMapAction(prop, false) },
        LocalMatchingBrokersHandler provides { prop -> matchingBrokersTargetProperty = prop }
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(296.dp)
                    .background(BackgroundSoft)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    AppDrawerContent(
                        currentDestination = currentDestination,
                        selectedBrand = selectedBrand,
                        brandLogos = brandLogos,
                        userProfile = effectiveUserProfile,
                        commercialCount = commercialCount,
                        residentialCount = residentialCount,
                        comparisonCount = comparisonIds.size,
                        savedCount = favoriteIds.size,
                        unclassifiedCount = unclassifiedCount,
                        onNavigate = { destination ->
                            if (destination == DrawerDestination.COMPARE_PROPERTIES) {
                                if (currentDestination != DrawerDestination.COMPARE_PROPERTIES) {
                                    previousNonComparisonDestination = currentDestination
                                }
                                showComparisonScreen = true
                            } else {
                                previousNonComparisonDestination = destination
                                currentDestination = destination
                                showComparisonScreen = false
                            }
                            when (destination) {
                                DrawerDestination.CRM_WORKSPACE -> crmWorkspaceSelected = true
                                DrawerDestination.SEARCH_PROPERTIES, DrawerDestination.INVENTORY_DASHBOARD,
                                DrawerDestination.DAILY_OPS,
                                DrawerDestination.COMMERCIAL, DrawerDestination.RESIDENTIAL,
                                DrawerDestination.ADD_PROPERTY, DrawerDestination.SAVED_PROPERTIES,
                                DrawerDestination.COMPARE_PROPERTIES, DrawerDestination.MASTER_SYNC,
                                DrawerDestination.AI_SEARCH, DrawerDestination.FIND_ON_MAP,
                                DrawerDestination.PROJECT_DIRECTORY -> crmWorkspaceSelected = false
                                else -> Unit
                            }
                            when (destination) {
                                DrawerDestination.COMMERCIAL -> {
                                    selectedBrand = Brand.COMMERCIAL
                                    showUnclassifiedReview = false
                                }
                                DrawerDestination.RESIDENTIAL -> {
                                    selectedBrand = Brand.RESIDENTIAL
                                    showUnclassifiedReview = false
                                }
                                DrawerDestination.INVENTORY_DASHBOARD -> {
                                    selectedBrand = null
                                    showUnclassifiedReview = false
                                }
                                DrawerDestination.COMPARE_PROPERTIES -> {
                                    showComparisonScreen = true
                                }
                                else -> {
                                    showUnclassifiedReview = false
                                }
                            }
                        },
                        onCloseDrawer = {
                            coroutineScope.launch { drawerState.close() }
                        },
                        crmMode = crmWorkspaceSelected,
                        themeMode = currentThemeMode,
                        onThemeModeChange = { newMode ->
                            userSettingsManager.setThemeMode(
                                newMode,
                                effectiveUserProfile.employeeId.ifBlank { currentEmployee?.employeeId }
                            )
                        },
                        whatsappUnreadCount = whatsappUnreadCount
                    )
                }
                HorizontalDivider(color = BorderGrey, thickness = 1.dp)
                Surface(
                    color = SurfaceWhite,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = "${effectiveUserProfile.username.ifBlank { "Employee" }} • ${effectiveUserProfile.division}",
                            color = SecondaryText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        val isAdminUser = effectiveUserProfile.username.equals("Pawan", true) ||
                            effectiveUserProfile.role == UserRole.ADMIN ||
                            (currentEmployee != null && currentEmployee.role == EmployeeAccessRole.ADMIN)
                        if (isAdminUser) {
                            OutlinedButton(
                                onClick = { com.example.ui.screens.shareAppApk(context) },
                                border = BorderStroke(1.dp, PrimaryActionOrange),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryActionOrange),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .testTag("drawer_share_apk_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Android,
                                    contentDescription = "Share APK",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share App APK", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        OutlinedButton(
                            onClick = performFullSignOut,
                            border = BorderStroke(1.dp, ErrorRed),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("drawer_sign_out_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "Sign Out",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign Out", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (activeDetailProperty == null && activeDetailProject == null && !showComparisonScreen) {
                    val activeBrand = when (currentDestination) {
                        DrawerDestination.COMMERCIAL -> Brand.COMMERCIAL
                        DrawerDestination.RESIDENTIAL -> Brand.RESIDENTIAL
                        else -> selectedBrand
                    }

                    val resolvedScreenTitle = when (currentDestination) {
                        DrawerDestination.INVENTORY_DASHBOARD -> "Dashboard"
                        DrawerDestination.DAILY_OPS -> "Daily POA & EOD"
                        DrawerDestination.CRM_WORKSPACE -> "CRM Workspace"
                        DrawerDestination.CRM_CALLS -> "Call Intelligence"
                        DrawerDestination.WHATSAPP_FOLLOWUPS -> "WhatsApp Follow-ups"
                        DrawerDestination.CLIENT_MATCHER -> "Client Matcher"
                        DrawerDestination.PROJECT_DIRECTORY -> "Project Directory"
                        DrawerDestination.SAVED_PROPERTIES -> "Saved Properties"
                        DrawerDestination.COMPARE_PROPERTIES -> "Compare"
                        DrawerDestination.FIND_ON_MAP -> "Property Map"
                        DrawerDestination.MASTER_SYNC -> "Master Sync"
                        DrawerDestination.TEAM_ADMIN -> "Team Admin"
                        DrawerDestination.SETTINGS -> "Settings"
                        else -> "Inventory"
                    }
                    val allowedViews = remember(isSuperAdmin, allowCommercial, allowResidential) {
                        buildList {
                            if (allowCommercial) add(ActiveBusinessView.ASCEND_COMMERCIAL)
                            if (allowResidential) add(ActiveBusinessView.AUREA_RESIDENTIAL)
                            if (isSuperAdmin || (allowCommercial && allowResidential)) {
                                add(ActiveBusinessView.ALL_BUSINESSES)
                            }
                        }
                    }
                    PortalTopBar(
                        selectedBrand = activeBrand,
                        onSelectBrand = { brand ->
                            if (isSuperAdmin || (allowCommercial && allowResidential)) {
                                selectedBrand = brand
                                when (brand) {
                                    Brand.COMMERCIAL -> currentDestination = DrawerDestination.COMMERCIAL
                                    Brand.RESIDENTIAL -> currentDestination = DrawerDestination.RESIDENTIAL
                                    null -> currentDestination = DrawerDestination.SEARCH_PROPERTIES
                                }
                                showUnclassifiedReview = false
                            } else {
                                Toast.makeText(
                                    context,
                                    "Access locked to ${effectiveUserProfile.division} division.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        brandLogos = brandLogos,
                        onOpenDrawer = {
                            coroutineScope.launch { drawerState.open() }
                        },
                        onOpenAddProperty = { currentDestination = DrawerDestination.ADD_PROPERTY },
                        onOpenImport = { currentDestination = DrawerDestination.MASTER_SYNC },
                        comparisonCount = comparisonIds.size,
                        onOpenComparison = {
                            if (currentDestination != DrawerDestination.COMPARE_PROPERTIES) {
                                previousNonComparisonDestination = currentDestination
                            }
                            showComparisonScreen = true
                        },
                        onNavigateHome = {
                            currentDestination = DrawerDestination.INVENTORY_DASHBOARD
                            selectedBrand = when {
                                allowCommercial && !allowResidential -> Brand.COMMERCIAL
                                allowResidential && !allowCommercial -> Brand.RESIDENTIAL
                                else -> sessionState.activeBusinessView.toBrandFilter()
                            }
                            showUnclassifiedReview = false
                        },
                        activeBrandScope = activeBrandScope,
                        canSwitchBrand = isSuperAdmin || (allowCommercial && allowResidential),
                        onSelectActiveBrand = { scope -> userSettingsManager.setActiveBrand(scope) },
                        screenTitle = resolvedScreenTitle,
                        activeBusinessView = when {
                            allowCommercial && !allowResidential -> ActiveBusinessView.ASCEND_COMMERCIAL
                            allowResidential && !allowCommercial -> ActiveBusinessView.AUREA_RESIDENTIAL
                            else -> sessionState.activeBusinessView
                        },
                        availableBusinessViews = allowedViews,
                        onSelectBusinessView = { view ->
                            coroutineScope.launch {
                                if (currentEmployee != null) {
                                    val res = sessionManager.switchBusinessView(view)
                                    if (!res.success && res.message.isNotBlank()) {
                                        Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    userSettingsManager.setActiveBrand(
                                        when (view) {
                                            ActiveBusinessView.ASCEND_COMMERCIAL -> ActiveBrand.ASCEND_COMMERCIAL
                                            ActiveBusinessView.AUREA_RESIDENTIAL -> ActiveBrand.AUREA_LIVING
                                            ActiveBusinessView.ALL_BUSINESSES -> ActiveBrand.ALL_BUSINESSES
                                        }
                                    )
                                }
                            }
                        },
                        employeeDisplayName = effectiveUserProfile.username.ifBlank { currentEmployee?.employeeName ?: "Employee" },
                        onOpenProfile = {
                            settingsInitialTab = 1
                            currentDestination = DrawerDestination.SETTINGS
                        },
                        unreadAlertCount = unreadAlertCount,
                        onOpenAlerts = { showTeamAlertsSheet = true },
                        topSyncChipState = topSyncChipState,
                        onTapSyncChip = {
                            Toast.makeText(context, "Syncing changes with TLC Master sheet...", Toast.LENGTH_SHORT).show()
                            oneTapSyncCoordinator.triggerOneTapSync(
                                employee = sessionState.currentEmployee,
                                showSheetOnFinish = false
                            )
                        }
                    )
                }
            },
            bottomBar = {
                val isDesktop = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
                if (!isDesktop && activeDetailProperty == null && activeDetailProject == null && !showComparisonScreen) {
                    PortalBottomNavBar(
                        selectedTab = when (currentDestination) {
                            DrawerDestination.INVENTORY_DASHBOARD,
                            DrawerDestination.DAILY_OPS -> PortalNavTab.HOME
                            DrawerDestination.PROJECT_DIRECTORY -> PortalNavTab.PROJECTS
                            DrawerDestination.CRM_WORKSPACE -> PortalNavTab.CRM
                            DrawerDestination.CRM_CALLS -> PortalNavTab.CALLS
                            else -> PortalNavTab.INVENTORY
                        },
                        onTabSelected = { tab ->
                            crmWorkspaceSelected = tab == PortalNavTab.CRM || tab == PortalNavTab.CALLS
                            currentDestination = when (tab) {
                                PortalNavTab.HOME -> DrawerDestination.INVENTORY_DASHBOARD
                                PortalNavTab.PROJECTS -> DrawerDestination.PROJECT_DIRECTORY
                                PortalNavTab.INVENTORY -> DrawerDestination.SEARCH_PROPERTIES
                                PortalNavTab.CRM -> DrawerDestination.CRM_WORKSPACE
                                PortalNavTab.CALLS -> DrawerDestination.CRM_CALLS
                            }
                        },
                        unloggedCallsCount = unloggedCallQueue.size
                    )
                }
            },
            containerColor = BrandCharcoalDark,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
                    .background(BrandCharcoalDark)
            ) {
                // Persistent Top Apps Script / Sync Warning Banner (stays visible until sync succeeds)
                AnimatedVisibility(
                    visible = syncBannerState.hasError,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        color = Color(0xFFFFF3E0),
                        border = BorderStroke(1.dp, StatusDangerRed.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("top_sync_warning_banner")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = "Sync warning",
                                    tint = StatusDangerRed,
                                    modifier = Modifier
                                        .padding(top = 1.dp)
                                        .size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = syncBannerState.title.ifBlank { "Apps Script Sync Error" },
                                        color = Color(0xFF8C1D18),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (syncBannerState.hint.isNotBlank()) {
                                        Text(
                                            text = syncBannerState.hint,
                                            color = Color(0xFF4E2A00),
                                            fontSize = 11.sp,
                                            lineHeight = 14.5.sp
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (!syncBannerState.isRetrying) {
                                            repository.setSyncBannerRetrying(true)
                                            coroutineScope.launch {
                                                repository.retryAllPendingSyncsAndPull()
                                                oneTapSyncCoordinator.triggerOneTapSync(
                                                    employee = sessionState.currentEmployee,
                                                    showSheetOnFinish = false
                                                )
                                            }
                                        }
                                    },
                                    enabled = !syncBannerState.isRetrying,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .heightIn(min = 40.dp)
                                        .testTag("top_sync_banner_retry_btn")
                                ) {
                                    if (syncBannerState.isRetrying) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Retrying...", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text("Retry Now", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        activeDetailProperty = null
                                        activeDetailProject = null
                                        showComparisonScreen = false
                                        currentDestination = DrawerDestination.MASTER_SYNC
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFF8C1D18).copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .heightIn(min = 40.dp)
                                        .testTag("top_sync_banner_setup_btn")
                                ) {
                                    Text(
                                        text = "Sync Setup",
                                        color = Color(0xFF8C1D18),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when {
                    // Active Property Detail Screen
                    resolvedDetailProperty != null -> {
                        val currentDetail = resolvedDetailProperty
                        val detailMapDestination = remember(currentDetail, effectiveUserProfile, allProperties) {
                            repository.resolvePropertyMapDestination(currentDetail.id, effectiveUserProfile)
                                ?: ResolvedMapDestination(
                                    propertyId = currentDetail.id,
                                    propertyCode = currentDetail.propertyCode,
                                    projectName = currentDetail.projectName,
                                    mode = DestinationMode.UNAVAILABLE,
                                    precision = currentDetail.locationPrecision,
                                    primaryUri = "",
                                    directionsUri = "",
                                    copyableText = "",
                                    warningLabel = "Location unavailable for current access scope"
                                )
                        }
                        val canEditDetailLocation = remember(currentDetail, effectiveUserProfile, allProperties) {
                            repository.canEditPropertyLocation(currentDetail.id, effectiveUserProfile)
                        }
                        PropertyDetailScreen(
                            onResolveDuplicate = repository::resolveDuplicate,
                            onConfirmPrice = { repository.confirmPrice(currentDetail.id) },
                            property = currentDetail,
                            allProperties = scopedProperties,
                            isInternalMode = isInternalMode,
                            isFavorite = favoriteIds.contains(currentDetail.id),
                            isInComparison = comparisonIds.contains(currentDetail.id),
                            onBack = { activeDetailProperty = null },
                            onToggleFavorite = { repository.toggleFavorite(currentDetail.id) },
                            onToggleCompare = { handleToggleCompare(currentDetail.id) },
                            onShareWhatsApp = {
                                triggerPropertyShare(currentDetail)
                            },
                            onVerifyProperty = {
                                handleVerifyProperty(currentDetail, currentDetail.status, "Detail Screen", "")
                            },
                            onStatusChange = { newStatus ->
                                repository.updateAvailabilityAsync(currentDetail.id, newStatus)
                                activeDetailProperty = currentDetail.copy(status = newStatus)
                            },
                            onPropertySelect = { selected ->
                                repository.markViewed(selected.id)
                                activeDetailProperty = selected
                            },
                            onEditProperty = {
                                editingPropertyTarget = currentDetail
                            },
                            onDeleteProperty = if (isSuperAdmin) {
                                {
                                    val deleted = repository.permanentlyDeleteProperty(currentDetail)
                                    if (deleted) {
                                        activeDetailProperty = null
                                        if (editingPropertyTarget?.id == currentDetail.id) editingPropertyTarget = null
                                    }
                                    Toast.makeText(
                                        context,
                                        if (deleted) "Deleted ${currentDetail.propertyCode.ifBlank { currentDetail.projectName }} permanently" else "Delete blocked or save failed.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else null,
                            onViewOnMap = { prop ->
                                mapTargetProperty = prop
                                activeDetailProperty = null
                                currentDestination = DrawerDestination.FIND_ON_MAP
                            },
                            onSharePropertyWhatsApp = triggerPropertyShare,
                            resolvedMapDestination = detailMapDestination,
                            canEditLocation = canEditDetailLocation,
                            onViewMapExternal = { prop -> triggerPropertyMapAction(prop, false) },
                            onDirectionsExternal = { prop -> triggerPropertyMapAction(prop, true) },
                            onCopyLocation = { prop, onResult ->
                                val d = repository.resolvePropertyMapDestination(prop.id, effectiveUserProfile)
                                copyLocationSafely(d?.copyableText.orEmpty(), onResult)
                            },
                            sheetsRepository = repository,
                            onLinkOrViewTenant = { prop ->
                                pendingTenantLinkProperty = prop
                                pendingTenantId = null
                                pendingBrokerVendorDraft = null
                                pendingTenantsTabIndex = 0
                                activeDetailProperty = null
                                currentDestination = DrawerDestination.TENANTS_LEASES
                            }
                        )
                    }

                    // Active Project Detail Screen (Under Construction)
                    activeDetailProject != null -> {
                        ProjectDetailScreen(
                            project = activeDetailProject!!,
                            allProperties = allProperties,
                            isInternalMode = isInternalMode,
                            repository = repository,
                            onBack = { activeDetailProject = null },
                            onShareWhatsApp = {
                                shareTargetPropertyId = null
                                shareTargetFallbackProperty = null
                                shareTargetProject = activeDetailProject
                                customShareMessage = null
                                showWhatsAppShareDialog = true
                            },
                            onOpenProperty = { prop ->
                                repository.markViewed(prop.id)
                                activeDetailProperty = prop
                            },
                            onAddUnitForProject = { proj ->
                                activeDetailProject = null
                                currentDestination = DrawerDestination.ADD_PROPERTY
                            },
                            onViewOnMap = { proj ->
                                activeDetailProject = null
                                currentDestination = DrawerDestination.FIND_ON_MAP
                            },
                            onPromoteUnit = { newUnit ->
                                val saved = repository.addPropertyAsync(newUnit)
                                if (saved) {
                                    Toast.makeText(
                                        context,
                                        "Unit ${newUnit.unitNumber} promoted to Direct Inventory & syncing",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(context, "Unable to save promoted unit", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDeleteProjectPermanently = if (isSuperAdmin) { proj ->
                                val deleted = repository.permanentlyDeleteUnderConstProject(proj)
                                if (deleted) {
                                    activeDetailProject = null
                                    coroutineScope.launch(Dispatchers.IO) {
                                        directoryProjects = runCatching { ProjectDirectoryStore.read(context) }.getOrDefault(directoryProjects)
                                    }
                                }
                                Toast.makeText(
                                    context,
                                    if (deleted) "Deleted ${proj.projectName} permanently" else "Delete blocked or failed.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else null
                        )
                    }

                    // Comparison Screen Modal
                    showComparisonScreen -> {
                        val orderedFallback = remember(allProperties, comparisonIds) {
                            val byId = allProperties.associateBy { it.id }
                            comparisonIds.mapNotNull { byId[it] }
                        }
                        val compProps = if (resolvedComparisonProps.isNotEmpty() || comparisonNotice != null) {
                            resolvedComparisonProps
                        } else {
                            orderedFallback
                        }
                        ComparisonScreen(
                            comparisonProperties = compProps,
                            onRemoveProperty = { handleToggleCompare(it) },
                            onClearAll = {
                                repository.clearComparison(effectiveUserProfile)
                                resolvedComparisonProps = emptyList()
                            },
                            onBack = { closeComparisonScreen(false) },
                            onBrowseInventory = { closeComparisonScreen(true) },
                            onViewProperty = { prop ->
                                repository.markViewed(prop.id)
                                activeDetailProperty = prop
                            },
                            noticeMessage = comparisonNotice,
                            onShareComparisonWhatsApp = {
                                if (compProps.isNotEmpty()) {
                                    shareTargetPropertyId = null
                                    shareTargetFallbackProperty = null
                                    shareTargetProject = null
                                    customShareMessage = WhatsAppShareTemplates.shortlist(
                                        properties = compProps,
                                        senderName = effectiveUserProfile.username.takeIf { it.isNotBlank() }
                                    )
                                    showWhatsAppShareDialog = true
                                }
                            }
                        )
                    }

                    showDealPipelineScreen -> {
                        CrmDealPipelineScreen(
                            inventory = scopedProperties,
                            onBack = { showDealPipelineScreen = false }
                        )
                    }

                    // Primary Navigation Screens
                    else -> {
                        saveableStateHolder.SaveableStateProvider("main_dest_${currentDestination.name}") {
                        when (currentDestination) {
                            DrawerDestination.PROJECT_DIRECTORY -> {
                                ProjectDirectoryScreen(
                                    inventory = scopedProperties,
                                    onOpenProperty = { activeDetailProperty = it },
                                    onPromoteUnit = { newUnit, proj ->
                                        val saved = repository.addPropertyAsync(newUnit)
                                        if (saved) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                runCatching { ProjectDirectoryStore.linkUnit(context, proj.id, newUnit.id) }
                                            }
                                            Toast.makeText(
                                                context,
                                                "Unit ${newUnit.unitNumber} promoted to Direct Inventory & syncing",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(context, "Unable to save promoted unit", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    generatePropertyCode = { tab -> repository.generateNextCode(tab) },
                                    initialSelectedProjectId = pendingProjectDirectoryId,
                                    onConsumeInitialProjectId = { pendingProjectDirectoryId = null },
                                    onDeleteProjectPermanently = if (isSuperAdmin) { proj ->
                                        val deleted = repository.permanentlyDeleteDirectoryProject(proj)
                                        if (deleted) {
                                            directoryProjects = directoryProjects.filterNot { it.id.equals(proj.id, ignoreCase = true) }
                                        }
                                    } else null
                                )
                            }
                            DrawerDestination.CRM_WORKSPACE -> {
                                CrmFoundationScreen(
                                    inventory = scopedProperties,
                                    onNavigateToCalls = { currentDestination = DrawerDestination.CRM_CALLS },
                                    onPushCrmLeadToSheet = { lead -> repository.pushCrmLeadToMasterSheet(lead) },
                                    onDeleteCrmLeadFromSheet = { leadId ->
                                        val targetLead = readCrmLeads(context).firstOrNull { it.id.equals(leadId, ignoreCase = true) }
                                        val targetTab = if (targetLead?.segment.equals("Residential", ignoreCase = true)) {
                                            "Residential CRM"
                                        } else {
                                            "Commercial CRM"
                                        }
                                        repository.deleteRecordPermanently(
                                            tabName = targetTab,
                                            keyHeader = "Lead ID",
                                            keyValue = leadId
                                        )
                                    },
                                    externalRefreshTick = crmExternalRefreshTick,
                                    initialPrefillLead = pendingCrmPrefillLead,
                                    onConsumeInitialPrefillLead = { pendingCrmPrefillLead = null },
                                    initialSelectedLeadId = pendingCrmSelectedLeadId,
                                    onConsumeInitialSelectedLeadId = { pendingCrmSelectedLeadId = null },
                                    activeCallContext = activeCallSession,
                                    onLeadSaved = { savedLead ->
                                        val linked = CallActionSessionManager.completeAndAutoLink(
                                            context = context,
                                            entityType = if (savedLead.clientType.contains("Broker", true)) "Broker" else "Client",
                                            entityTitle = savedLead.displayClientTitle(),
                                            entityId = savedLead.id,
                                            onCompleted = {
                                                coroutineScope.launch {
                                                    unloggedCallQueue = withContext(Dispatchers.IO) {
                                                        CrmCallManager.getUnloggedCallQueue(context)
                                                    }
                                                    callsExternalRefreshTick++
                                                }
                                            }
                                        )
                                        if (linked) {
                                            Toast.makeText(
                                                context,
                                                "Call auto-linked to ${savedLead.displayClientTitle()} & marked Handled",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                            }

                            DrawerDestination.CRM_CALLS -> {
                                if (showCallReview) {
                                    CallReviewScreen(
                                        onCancel = { showCallReview = false },
                                        onSave = { draft ->
                                            coroutineScope.launch {
                                                try {
                                                    val now = System.currentTimeMillis()
                                                    val followUpAt = when (draft.followUp) {
                                                        "TODAY" -> now
                                                        "TOMORROW" -> now + 86_400_000L
                                                        "THREE_DAYS" -> now + 3 * 86_400_000L
                                                        else -> null
                                                    }
                                                    callReviewDao.upsert(
                                                        CallReviewEntity(
                                                            reviewId = java.util.UUID.randomUUID().toString(),
                                                            createdAtEpochMs = now,
                                                            callEndedAtEpochMs = null,
                                                            callerType = draft.callerType,
                                                            division = draft.division,
                                                            contactReference = draft.contactReference,
                                                            projectId = draft.projectId,
                                                            propertyCode = draft.propertyCode,
                                                            leadReference = draft.leadReference,
                                                            summary = draft.summary,
                                                            actionNotes = draft.actionNotes,
                                                            followUpAtEpochMs = followUpAt,
                                                            whatsappDraft = draft.whatsappDraft,
                                                            reviewStatus = "SAVED",
                                                            updatedBy = userSettingsManager.auditIdentity(),
                                                            updatedAt = java.time.Instant.now().toString()
                                                        )
                                                    )
                                                    showCallReview = false
                                                    Toast.makeText(context, "Call review saved locally", Toast.LENGTH_SHORT).show()
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Unable to save call review", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    )
                                } else if (showSavedCallReviews) {
                                    val savedReviews by callReviewDao.observeReviews().collectAsState(initial = emptyList())
                                    Column(
                                        modifier = Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TextButton(onClick = { showSavedCallReviews = false }) { Text("Back to CRM calls") }
                                        Text("Saved call reviews", style = MaterialTheme.typography.titleLarge)
                                        if (savedReviews.isEmpty()) Text("No saved call reviews yet.")
                                        savedReviews.forEach { review ->
                                            Card(modifier = Modifier.fillMaxWidth()) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text("${review.callerType} · ${review.division}", style = MaterialTheme.typography.titleMedium)
                                                    Text(review.summary.ifBlank { "No summary entered" })
                                                    if (review.actionNotes.isNotBlank()) Text("Next: ${review.actionNotes}")
                                                    if (review.propertyCode != null) Text("Property: ${review.propertyCode}")
                                                    if (review.followUpAtEpochMs != null) {
                                                        Text("Follow-up: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(review.followUpAtEpochMs))}")
                                                    }
                                                    Text("WhatsApp: draft only — not sent", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    CrmCallsScreen(
                                        inventory = scopedProperties,
                                        onAddPropertyToInventory = { newProp ->
                                            repository.addPropertyAsync(newProp)
                                        },
                                        onNavigateToClients = { currentDestination = DrawerDestination.CRM_WORKSPACE },
                                        onNewCallReview = { showCallReview = true },
                                        onOpenSavedReviews = { showSavedCallReviews = true },
                                        onOpenPostCallSheet = { clickedCall ->
                                            postCallQueue = listOf(clickedCall)
                                            postCallQueueIndex = 0
                                            activePostCall = clickedCall
                                        },
                                        onOpenPostCallQueue = { queueList, startIdx ->
                                            val safeIdx = startIdx.coerceIn(0, (queueList.size - 1).coerceAtLeast(0))
                                            postCallQueue = queueList
                                            postCallQueueIndex = safeIdx
                                            activePostCall = queueList.getOrNull(safeIdx)
                                        },
                                        onEditExtractedClient = { draftLead, _ ->
                                            pendingCrmPrefillLead = draftLead
                                            crmExternalRefreshTick++
                                            currentDestination = DrawerDestination.CRM_WORKSPACE
                                        },
                                        onEditExtractedProperty = { draftProp, _ ->
                                            editingPropertyTarget = draftProp
                                        },
                                        onPushCrmLeadToSheet = { lead ->
                                            repository.pushCrmLeadToMasterSheet(lead)
                                        },
                                        externalRefreshTick = callsExternalRefreshTick
                                    )
                                }
                            }

                            DrawerDestination.INVENTORY_DASHBOARD -> {
                                DashboardScreen(
                                    properties = scopedProperties,
                                    underConstProjects = scopedUnderConstProjects,
                                    initialBrand = null,
                                    userProfile = effectiveUserProfile,
                                    onPropertyClick = { prop ->
                                        repository.markViewed(prop.id)
                                        activeDetailProperty = prop
                                    },
                                    onEditProperty = { prop ->
                                        editingPropertyTarget = prop
                                    },
                                    onVerifyProperty = { id ->
                                        allProperties.find { it.id == id }?.let { prop ->
                                            handleVerifyProperty(prop, prop.status, "Dashboard", "")
                                        }
                                    },
                                    onOpenMasterSync = { currentDestination = DrawerDestination.MASTER_SYNC },
                                    onNavigateToAddProperty = { currentDestination = DrawerDestination.ADD_PROPERTY },
                                    onNavigateToCrm = { currentDestination = DrawerDestination.CRM_WORKSPACE },
                                    onNavigateToVisits = { currentDestination = DrawerDestination.CRM_WORKSPACE },
                                    onNavigateToInventory = {
                                        selectedBrand = null
                                        currentDestination = DrawerDestination.SEARCH_PROPERTIES
                                    },
                                    onNavigateToCalls = { currentDestination = DrawerDestination.CRM_CALLS },
                                    onNavigateToWhatsAppInbox = { currentDestination = DrawerDestination.WHATSAPP_FOLLOWUPS },
                                    onNavigateToCommercialInventory = {
                                        selectedBrand = Brand.COMMERCIAL
                                        currentDestination = DrawerDestination.COMMERCIAL
                                    },
                                    onNavigateToResidentialInventory = {
                                        selectedBrand = Brand.RESIDENTIAL
                                        currentDestination = DrawerDestination.RESIDENTIAL
                                    },
                                    onNavigateToProjects = {
                                        currentDestination = DrawerDestination.PROJECT_DIRECTORY
                                    },
                                    onSearchInventory = { query ->
                                        pendingExploreSearchQuery = query
                                        selectedBrand = null
                                        currentDestination = DrawerDestination.SEARCH_PROPERTIES
                                    },
                                    onNavigateToDeals = {
                                        showDealPipelineScreen = true
                                    },
                                    dailyReports = dailyReports,
                                    onNavigateToDailyOps = {
                                        currentDestination = DrawerDestination.DAILY_OPS
                                    },
                                    lastSyncAtMillis = lastSyncAtMillis,
                                    onRefreshSync = {
                                        oneTapSyncCoordinator.triggerOneTapSync(sessionState.currentEmployee)
                                    },
                                    pendingSyncCount = totalPendingSyncCount,
                                    isSyncingPending = isSyncingPending,
                                    onSyncPendingClick = {
                                        if (!isSyncingPending) {
                                            isSyncingPending = true
                                            coroutineScope.launch {
                                                val summary = repository.retryAllPendingSyncsAndPull()
                                                isSyncingPending = false
                                                Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    onOpenMoreDetailsForRole = { role, savedCall, prefilledName, prefilledNotes, linkedEntityId, subRole ->
                                        pendingCallNavigation = PendingCallNavigation.fromPostCallAction(
                                            role = role,
                                            call = savedCall,
                                            prefilledName = prefilledName,
                                            prefilledNotes = prefilledNotes,
                                            linkedEntityId = linkedEntityId,
                                            subRole = subRole
                                        )
                                    },
                                    onOpenCaptureBrokerUnit = { bName, bPhone, cNote ->
                                        pendingAddPropertyName = bName
                                        pendingAddPropertyPhone = bPhone
                                        pendingAddPropertyNote = cNote
                                        pendingAddPropertyPocType = "Broker"
                                        pendingAddPropertyTab = null
                                        pendingAddPropertyFromCall = true
                                        currentDestination = DrawerDestination.ADD_PROPERTY
                                    },
                                    onOpenCallActionQueue = { queueList, startIdx ->
                                        val safeIdx = startIdx.coerceIn(0, (queueList.size - 1).coerceAtLeast(0))
                                        postCallQueue = queueList
                                        postCallQueueIndex = safeIdx
                                        activePostCall = queueList.getOrNull(safeIdx)
                                    }
                                )
                            }

                            DrawerDestination.DAILY_OPS -> {
                                DailyOpsScreen(
                                    userProfile = effectiveUserProfile,
                                    properties = scopedProperties,
                                    dailyReports = dailyReports,
                                    onSaveDailyReport = { report -> repository.pushDailyReport(report) },
                                    onRefreshSync = {
                                        oneTapSyncCoordinator.triggerOneTapSync(sessionState.currentEmployee)
                                    }
                                )
                            }

                            DrawerDestination.TENANTS_LEASES -> {
                                val canViewFullPhone = remember(effectiveUserProfile, revealConfidentialPhonesForExec) {
                                    PhoneMaskingUtils.canViewUnmaskedConfidentialPhone(
                                        userProfile = effectiveUserProfile,
                                        revealToggleOn = revealConfidentialPhonesForExec
                                    )
                                }
                                TenantsManagementScreen(
                                    tenants = tenantsList,
                                    brokersVendors = brokersVendorsList,
                                    inventory = scopedProperties,
                                    userProfile = effectiveUserProfile,
                                    canViewFullConfidentialPhones = canViewFullPhone,
                                    autoSaveGoogleContactsEnabled = autoSaveContactsEnabled,
                                    prefilledTenantProperty = pendingTenantLinkProperty,
                                    prefilledTenantId = pendingTenantId,
                                    prefilledTenantDraft = pendingTenantDraft,
                                    prefilledBrokerVendorDraft = pendingBrokerVendorDraft,
                                    initialTabIndex = pendingTenantsTabIndex,
                                    onConsumePrefills = {
                                        pendingTenantLinkProperty = null
                                        pendingTenantId = null
                                        pendingTenantDraft = null
                                        pendingBrokerVendorDraft = null
                                    },
                                    onSaveTenant = { savedTenant ->
                                        repository.saveTenantLocalAndSync(savedTenant)
                                        val linked = CallActionSessionManager.completeAndAutoLink(
                                            context = context,
                                            entityType = "Tenant",
                                            entityTitle = savedTenant.tenantName.ifBlank { savedTenant.projectName },
                                            entityId = savedTenant.id,
                                            onCompleted = {
                                                coroutineScope.launch {
                                                    unloggedCallQueue = withContext(Dispatchers.IO) {
                                                        CrmCallManager.getUnloggedCallQueue(context)
                                                    }
                                                    callsExternalRefreshTick++
                                                }
                                            }
                                        )
                                        if (linked) {
                                            Toast.makeText(
                                                context,
                                                "Call auto-linked to Tenant ${savedTenant.tenantName} & marked Handled",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    onSaveBrokerVendor = { savedPartner ->
                                        repository.saveBrokerVendorLocalAndSync(savedPartner)
                                        val linked = CallActionSessionManager.completeAndAutoLink(
                                            context = context,
                                            entityType = if (savedPartner.roleCategory.contains("Vendor", true) || savedPartner.roleCategory.contains("Ops", true)) "Vendor" else "Broker",
                                            entityTitle = savedPartner.name,
                                            entityId = savedPartner.id,
                                            onCompleted = {
                                                coroutineScope.launch {
                                                    unloggedCallQueue = withContext(Dispatchers.IO) {
                                                        CrmCallManager.getUnloggedCallQueue(context)
                                                    }
                                                    callsExternalRefreshTick++
                                                }
                                            }
                                        )
                                        if (linked) {
                                            Toast.makeText(
                                                context,
                                                "Call auto-linked to ${savedPartner.name} & marked Handled",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    activeCallContext = activeCallSession,
                                    onBack = { currentDestination = DrawerDestination.INVENTORY_DASHBOARD }
                                )
                            }

                            DrawerDestination.SEARCH_PROPERTIES,
                            DrawerDestination.COMMERCIAL,
                            DrawerDestination.RESIDENTIAL -> {
                                val targetBrand = when (currentDestination) {
                                    DrawerDestination.COMMERCIAL -> Brand.COMMERCIAL
                                    DrawerDestination.RESIDENTIAL -> Brand.RESIDENTIAL
                                    else -> selectedBrand
                                }

                                ExploreScreen(
                                    properties = scopedProperties,
                                    selectedBrand = targetBrand,
                                    onSelectBrand = { brand ->
                                        selectedBrand = brand
                                        when (brand) {
                                            Brand.COMMERCIAL -> currentDestination = DrawerDestination.COMMERCIAL
                                            Brand.RESIDENTIAL -> currentDestination = DrawerDestination.RESIDENTIAL
                                            null -> currentDestination = DrawerDestination.SEARCH_PROPERTIES
                                        }
                                        showUnclassifiedReview = false
                                    },
                                    isInternalMode = isInternalMode,
                                    favoriteIds = favoriteIds,
                                    comparisonIds = comparisonIds,
                                    onPropertyClick = { prop ->
                                        repository.markViewed(prop.id)
                                        activeDetailProperty = prop
                                    },
                                    onEditProperty = { prop ->
                                        editingPropertyTarget = prop
                                    },
                                    onToggleFavorite = { repository.toggleFavorite(it) },
                                    onToggleCompare = { handleToggleCompare(it) },
                                    onShareWhatsApp = triggerPropertyShare,
                                    onStatusChange = { id, st -> repository.updateAvailabilityAsync(id, st) },
                                    onOpenAddProperty = { currentDestination = DrawerDestination.ADD_PROPERTY },
                                    onOpenImport = { currentDestination = DrawerDestination.MASTER_SYNC },
                                    isLiveConnected = isLiveConnected,
                                    lastSyncTimestamp = lastSyncTimestamp,
                                    showUnclassifiedOnly = showUnclassifiedReview,
                                    onClearUnclassifiedOnly = { showUnclassifiedReview = false },
                                    onOpenComparison = {
                                        if (currentDestination != DrawerDestination.COMPARE_PROPERTIES) {
                                            previousNonComparisonDestination = currentDestination
                                        }
                                        showComparisonScreen = true
                                    },
                                    onClearComparison = { repository.clearComparison(effectiveUserProfile) },
                                    shortlistIds = shortlistState.selectedIds,
                                    onToggleShortlist = { shortlistViewModel.toggleSelection(it) },
                                    onReviewShortlist = { shortlistViewModel.openReviewSheet() },
                                    onClearShortlist = { shortlistViewModel.clearShortlist() },
                                    preferredGridView = sessionState.inventoryViewMode == "GRID",
                                    onInventoryViewChange = { isGrid ->
                                        coroutineScope.launch {
                                            sessionManager.setInventoryViewMode(if (isGrid) "GRID" else "LIST")
                                        }
                                    },
                                    initialSearchQuery = pendingExploreSearchQuery,
                                    onConsumeInitialSearchQuery = { pendingExploreSearchQuery = "" },
                                    onVerifyProperty = { propId, status, method, notes ->
                                        allProperties.find { it.id == propId }?.let { prop ->
                                            handleVerifyProperty(prop, status, method, notes)
                                        }
                                    },
                                    onToggleVerifyLive = handleToggleVerifyLive,
                                    onInlineUpdateProperty = handleInlineUpdateProperty,
                                    onUploadPhoto = { propId, fileName, mime, bytes ->
                                        repository.uploadMediaToDrive(propId, fileName, mime, bytes)
                                    }
                                )
                            }

                            DrawerDestination.SAVED_PROPERTIES -> {
                                val savedProperties = remember(scopedProperties, favoriteIds) {
                                    scopedProperties.filter { favoriteIds.contains(it.id) }
                                }
                                ExploreScreen(
                                    properties = savedProperties,
                                    selectedBrand = null,
                                    onSelectBrand = { },
                                    isInternalMode = isInternalMode,
                                    favoriteIds = favoriteIds,
                                    comparisonIds = comparisonIds,
                                    onPropertyClick = { prop ->
                                        repository.markViewed(prop.id)
                                        activeDetailProperty = prop
                                    },
                                    onEditProperty = { prop ->
                                        editingPropertyTarget = prop
                                    },
                                    onToggleFavorite = { repository.toggleFavorite(it) },
                                    onToggleCompare = { handleToggleCompare(it) },
                                    onShareWhatsApp = triggerPropertyShare,
                                    onStatusChange = { id, st -> repository.updateAvailabilityAsync(id, st) },
                                    onOpenAddProperty = { currentDestination = DrawerDestination.ADD_PROPERTY },
                                    onOpenImport = { currentDestination = DrawerDestination.MASTER_SYNC },
                                    isLiveConnected = isLiveConnected,
                                    lastSyncTimestamp = lastSyncTimestamp,
                                    onOpenComparison = {
                                        if (currentDestination != DrawerDestination.COMPARE_PROPERTIES) {
                                            previousNonComparisonDestination = currentDestination
                                        }
                                        showComparisonScreen = true
                                    },
                                    onClearComparison = { repository.clearComparison(effectiveUserProfile) },
                                    shortlistIds = shortlistState.selectedIds,
                                    onToggleShortlist = { shortlistViewModel.toggleSelection(it) },
                                    onReviewShortlist = { shortlistViewModel.openReviewSheet() },
                                    onClearShortlist = { shortlistViewModel.clearShortlist() },
                                    preferredGridView = sessionState.inventoryViewMode == "GRID",
                                    onInventoryViewChange = { isGrid ->
                                        coroutineScope.launch {
                                            sessionManager.setInventoryViewMode(if (isGrid) "GRID" else "LIST")
                                        }
                                    },
                                    onVerifyProperty = { propId, status, method, notes ->
                                        allProperties.find { it.id == propId }?.let { prop ->
                                            handleVerifyProperty(prop, status, method, notes)
                                        }
                                    },
                                    onToggleVerifyLive = handleToggleVerifyLive,
                                    onInlineUpdateProperty = handleInlineUpdateProperty,
                                    onUploadPhoto = { propId, fileName, mime, bytes ->
                                        repository.uploadMediaToDrive(propId, fileName, mime, bytes)
                                    }
                                )
                            }

                            DrawerDestination.ADD_PROPERTY -> {
                                AddPropertyScreen(
                                    onSaveProperty = { newProp ->
                                        val roleLabel = activeCallSession?.targetRoleLabel
                                            ?: if (pendingAddPropertyPocType.equals("Broker", true)) "Broker" else "Owner"
                                        pendingAddPropertyName = ""
                                        pendingAddPropertyPhone = ""
                                        pendingAddPropertyNote = ""
                                        pendingAddPropertyPocType = "Property Owner"
                                        pendingAddPropertyTab = null
                                        pendingAddPropertyFromCall = false
                                        val savedOk = repository.addPropertyAsync(newProp)
                                        if (savedOk) {
                                            val linked = CallActionSessionManager.completeAndAutoLink(
                                                context = context,
                                                entityType = roleLabel,
                                                entityTitle = listOf(newProp.projectName, newProp.unitNumber)
                                                    .filter { it.isNotBlank() }
                                                    .joinToString(" ")
                                                    .ifBlank { newProp.propertyCode },
                                                entityId = newProp.propertyCode.ifBlank { newProp.id },
                                                onCompleted = {
                                                    coroutineScope.launch {
                                                        unloggedCallQueue = withContext(Dispatchers.IO) {
                                                            CrmCallManager.getUnloggedCallQueue(context)
                                                        }
                                                        callsExternalRefreshTick++
                                                    }
                                                }
                                            )
                                            if (linked) {
                                                Toast.makeText(
                                                    context,
                                                    "Call auto-linked to ${newProp.propertyCode.ifBlank { newProp.projectName }} & marked Handled",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        savedOk
                                    },
                                    onCheckDuplicates = { code, proj, unit ->
                                        repository.findPotentialDuplicates(code, proj, unit)
                                    },
                                    onGenerateCode = { tab ->
                                        repository.generateNextCode(tab)
                                    },
                                    onCancel = {
                                        pendingAddPropertyName = ""
                                        pendingAddPropertyPhone = ""
                                        pendingAddPropertyNote = ""
                                        pendingAddPropertyPocType = "Property Owner"
                                        pendingAddPropertyTab = null
                                        pendingAddPropertyFromCall = false
                                        currentDestination = DrawerDestination.INVENTORY_DASHBOARD
                                    },
                                    onUploadMediaToDrive = { propId, fileName, mime, bytes ->
                                        repository.uploadMediaToDrive(propId, fileName, mime, bytes)
                                    },
                                    onPushPropertyToSheet = { prop ->
                                        repository.pushPropertyToMasterSheet(prop)
                                    },
                                    initialOwnerName = pendingAddPropertyName,
                                    initialOwnerPhone = pendingAddPropertyPhone,
                                    initialRemarks = pendingAddPropertyNote,
                                    initialPocType = pendingAddPropertyPocType,
                                    initialTab = pendingAddPropertyTab,
                                    activeCallContext = activeCallSession,
                                    fromCallIntelligence = pendingAddPropertyFromCall || pendingAddPropertyPhone.isNotBlank(),
                                    onRouteClientRequirementToCrm = { rawRequirement ->
                                        val phone = com.example.ai.SafeWhatsAppExtractor.normalizeIndianPhone(
                                            Regex("""(?:\+?91[\-\s]?)?[6-9]\d{9}""").find(rawRequirement)?.value
                                        )
                                        val inferredSegment = if (Regex("""\b(\d\s*bhk|flat|apartment|villa|row\s*house|residential|resi)\b""", RegexOption.IGNORE_CASE).containsMatchIn(rawRequirement)) {
                                            "Residential"
                                        } else {
                                            "Commercial"
                                        }
                                        pendingCrmPrefillLead = CrmLead(
                                            id = "",
                                            name = "WhatsApp Requirement",
                                            phone = phone,
                                            segment = inferredSegment,
                                            requirement = rawRequirement.trim().take(280),
                                            location = "",
                                            budget = "",
                                            status = "New Lead",
                                            verificationStatus = "Pending",
                                            nextFollowUp = "",
                                            notes = rawRequirement.trim()
                                        )
                                        crmExternalRefreshTick++
                                        currentDestination = DrawerDestination.CRM_WORKSPACE
                                    }
                                )
                            }

                            DrawerDestination.CLIENT_MATCHER -> {
                                ClientMatchScreen(
                                    tenantInventory = scopedProperties,
                                    isInternalMode = isInternalMode,
                                    initialRequirement = clientMatcherInitialRequirement,
                                    onPropertyClick = { prop ->
                                        activeDetailProperty = prop
                                    },
                                    onShareWhatsApp = triggerPropertyShare,
                                    onViewMatchesOnMap = { matchedList ->
                                        mapTargetProperty = matchedList.firstOrNull()
                                        currentDestination = DrawerDestination.FIND_ON_MAP
                                    },
                                    directoryProjects = directoryProjects,
                                    onOpenProjectById = { projId ->
                                        pendingProjectDirectoryId = projId
                                        currentDestination = DrawerDestination.PROJECT_DIRECTORY
                                    }
                                )
                            }

                            DrawerDestination.AI_SEARCH -> {
                                AiAssistantScreen(
                                    inventory = scopedProperties,
                                    isInternalMode = isInternalMode,
                                    onSaveNewProperty = { newProp ->
                                        repository.addPropertyAsync(newProp)
                                    },
                                    onPropertyClick = { prop ->
                                        activeDetailProperty = prop
                                    },
                                    onShareWhatsApp = triggerPropertyShare,
                                    onOpenClientMatcher = { reqText ->
                                        clientMatcherInitialRequirement = reqText
                                        currentDestination = DrawerDestination.CLIENT_MATCHER
                                    }
                                )
                            }

                            DrawerDestination.FIND_ON_MAP -> {
                                MapSearchScreen(
                                    allProperties = scopedProperties,
                                    initialTargetProperty = mapTargetProperty,
                                    initialPortfolio = selectedBrand,
                                    onPropertyClick = { prop ->
                                        activeDetailProperty = prop
                                    },
                                    onShareWhatsApp = triggerPropertyShare,
                                    onBack = {
                                        currentDestination = DrawerDestination.INVENTORY_DASHBOARD
                                        mapTargetProperty = null
                                    }
                                )
                            }

                            DrawerDestination.COMPARE_PROPERTIES -> {
                                val orderedFallback = remember(allProperties, comparisonIds) {
                                    val byId = allProperties.associateBy { it.id }
                                    comparisonIds.mapNotNull { byId[it] }
                                }
                                val compProps = if (resolvedComparisonProps.isNotEmpty() || comparisonNotice != null) {
                                    resolvedComparisonProps
                                } else {
                                    orderedFallback
                                }
                                ComparisonScreen(
                                    comparisonProperties = compProps,
                                    onRemoveProperty = { handleToggleCompare(it) },
                                    onClearAll = {
                                        repository.clearComparison(effectiveUserProfile)
                                        resolvedComparisonProps = emptyList()
                                    },
                                    onBack = { closeComparisonScreen(false) },
                                    onBrowseInventory = { closeComparisonScreen(true) },
                                    onViewProperty = { prop ->
                                        repository.markViewed(prop.id)
                                        activeDetailProperty = prop
                                    },
                                    noticeMessage = comparisonNotice,
                                    onShareComparisonWhatsApp = {
                                        if (compProps.isNotEmpty()) {
                                            shareTargetPropertyId = null
                                            shareTargetFallbackProperty = null
                                            shareTargetProject = null
                                            customShareMessage = WhatsAppShareTemplates.shortlist(
                                                properties = compProps,
                                                senderName = effectiveUserProfile.username.takeIf { it.isNotBlank() }
                                            )
                                            showWhatsAppShareDialog = true
                                        }
                                    }
                                )
                            }

                            DrawerDestination.MASTER_SYNC -> {
                                LaunchedEffect(Unit) {
                                    oneTapSyncCoordinator.triggerOneTapSync(sessionState.currentEmployee)
                                    oneTapSyncCoordinator.showResultSheet()
                                    currentDestination = DrawerDestination.INVENTORY_DASHBOARD
                                }
                            }

                            DrawerDestination.WHATSAPP_FOLLOWUPS -> {
                                WhatsAppFollowUpsScreen(
                                    inventory = scopedProperties,
                                    employeeName = effectiveUserProfile.username,
                                    onOpenLeadInCrm = {
                                        crmWorkspaceSelected = true
                                        currentDestination = DrawerDestination.CRM_WORKSPACE
                                    },
                                    onOpenPropertyDetail = { prop ->
                                        repository.markViewed(prop.id)
                                        activeDetailProperty = prop
                                    },
                                    onPublishLiveProperty = { liveProp ->
                                        repository.addPropertyAsync(liveProp)
                                    },
                                    onSaveCrmLead = { lead, actType, actNotes ->
                                        coroutineScope.launch {
                                            val persisted = withContext(Dispatchers.IO) {
                                                upsertCrmLeadFromPostCall(
                                                    context = context,
                                                    lead = lead,
                                                    activityType = actType,
                                                    activityNotes = actNotes,
                                                    performer = effectiveUserProfile.username
                                                )
                                            }
                                            crmExternalRefreshTick++
                                            repository.pushCrmLeadToMasterSheet(persisted)
                                        }
                                    },
                                    onGeneratePropertyCode = { tab ->
                                        repository.generateNextCode(tab)
                                    }
                                )
                            }

                            DrawerDestination.PUNE_INTELLIGENCE -> {
                                PuneIntelligenceScreen(
                                    onNavigateBack = { currentDestination = DrawerDestination.INVENTORY_DASHBOARD }
                                )
                            }

                            DrawerDestination.TEAM_ADMIN -> {
                                AdminTeamScreen(
                                    sessionState = sessionState,
                                    onBack = {
                                        settingsInitialTab = 1
                                        currentDestination = DrawerDestination.SETTINGS
                                    },
                                    onUpdateTeamMember = { targetId, expectedVer, name, email, role, scopes, reportsTo, title, status ->
                                        coroutineScope.launch {
                                            val res = sessionManager.updateTeamMember(
                                                targetEmployeeId = targetId,
                                                expectedVersion = expectedVer,
                                                employeeName = name,
                                                loginEmail = email,
                                                role = role,
                                                businessScopes = scopes,
                                                reportsToEmployeeId = reportsTo,
                                                jobTitle = title,
                                                accessStatus = status
                                            )
                                            if (res.message.isNotBlank()) {
                                                Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }

                            DrawerDestination.SETTINGS -> {
                                SettingsScreen(
                                    userSettingsManager = userSettingsManager,
                                    repository = repository,
                                    properties = scopedProperties,
                                    onBack = {
                                        settingsInitialTab = 0
                                        currentDestination = DrawerDestination.INVENTORY_DASHBOARD
                                    },
                                    onOpenMasterSync = { currentDestination = DrawerDestination.MASTER_SYNC },
                                    onPropertySelect = { prop ->
                                        activeDetailProperty = prop
                                    },
                                    onVerifyProperty = { id ->
                                        allProperties.find { it.id == id }?.let { prop ->
                                            handleVerifyProperty(prop, prop.status, "Settings", "")
                                        }
                                    },
                                    employeeSessionManager = sessionManager,
                                    initialSectionTab = settingsInitialTab,
                                    onSwitchProfile = performFullSignOut,
                                    onLockNow = {
                                        coroutineScope.launch {
                                            val res = sessionManager.lockNow()
                                            if (!res.success && res.message.isNotBlank()) {
                                                Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onSignOut = performFullSignOut,
                                    onOpenTeamAdmin = {
                                        currentDestination = DrawerDestination.TEAM_ADMIN
                                    }
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

    // Full-Screen 2-Column Property Editing Overlay
    if (editingPropertyTarget != null) {
        EditPropertyScreen(
            property = editingPropertyTarget!!,
            onSave = { updated, auditText ->
                val saved = repository.updatePropertyAsync(updated, auditText)
                if (saved) {
                    if (activeDetailProperty?.id == updated.id) activeDetailProperty = updated
                    val linked = CallActionSessionManager.completeAndAutoLink(
                        context = context,
                        entityType = "Owner",
                        entityTitle = listOf(updated.projectName, updated.unitNumber)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .ifBlank { updated.propertyCode },
                        entityId = updated.propertyCode.ifBlank { updated.id },
                        onCompleted = {
                            coroutineScope.launch {
                                unloggedCallQueue = withContext(Dispatchers.IO) {
                                    CrmCallManager.getUnloggedCallQueue(context)
                                }
                                callsExternalRefreshTick++
                            }
                        }
                    )
                    if (linked) {
                        Toast.makeText(
                            context,
                            "Call auto-linked to ${updated.propertyCode.ifBlank { updated.projectName }} & marked Handled",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                saved
            },
            onDismiss = { editingPropertyTarget = null },
            onUploadMediaToDrive = { propId, fileName, mime, bytes ->
                repository.uploadMediaToDrive(propId, fileName, mime, bytes)
            },
            onPushPropertyToSheet = { prop ->
                repository.pushPropertyToMasterSheet(prop)
            },
            onDeletePermanently = if (isSuperAdmin) {
                {
                    val target = editingPropertyTarget
                    if (target != null) {
                        val deleted = repository.permanentlyDeleteProperty(target)
                        if (deleted) {
                            editingPropertyTarget = null
                            if (activeDetailProperty?.id == target.id) activeDetailProperty = null
                        }
                        Toast.makeText(
                            context,
                            if (deleted) "Deleted ${target.propertyCode.ifBlank { target.projectName }} permanently" else "Delete blocked or save failed.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } else null
        )
    }

    // WhatsApp Share Dialog
    if (showWhatsAppShareDialog) {
        WhatsAppShareDialog(
            property = resolvedShareProperty,
            project = shareTargetProject,
            customMessage = customShareMessage,
            senderName = effectiveUserProfile.username.takeIf { it.isNotBlank() },
            userSettingsManager = userSettingsManager,
            onDismiss = {
                showWhatsAppShareDialog = false
                shareTargetPropertyId = null
                shareTargetFallbackProperty = null
                shareTargetProject = null
                customShareMessage = null
            }
        )
    }

    if (shortlistState.isReviewSheetOpen) {
        ShortlistReviewBottomSheet(
            state = shortlistState,
            onRemoveProperty = { shortlistViewModel.removeProperty(it) },
            onCopyText = { shortlistViewModel.copyShortlistToClipboard(activityContext) },
            onShareWhatsApp = { shortlistViewModel.shareToWhatsApp(activityContext) },
            onDismiss = { shortlistViewModel.closeReviewSheet() }
        )
    }

    if (isSyncResultSheetVisible) {
        SyncResultBottomSheet(
            report = oneTapSyncReport,
            onDismiss = { oneTapSyncCoordinator.dismissResultSheet() }
        )
    }

    // Batch 40: 48-Hour Team Alerts Feed Bottom Sheet
    if (showTeamAlertsSheet) {
        TeamAlertsBottomSheet(
            alerts = teamAlerts,
            onDismiss = { showTeamAlertsSheet = false },
            onAlertClick = { alert ->
                navigateToAlertTarget(alert.id, alert.targetType, alert.targetId)
            },
            onMarkAllRead = { TeamAlertStore.markAllRead(context) },
            onOpenAlertSettings = {
                settingsInitialTab = 8
                activeDetailProperty = null
                activeDetailProject = null
                currentDestination = DrawerDestination.SETTINGS
            }
        )
    }

    // Location Review & Conflict Sheet (Prompt 07)
    val reviewPropId = locationReviewPropertyId
    if (reviewPropId != null) {
        val reviewProperty = remember(reviewPropId, allProperties) {
            allProperties.firstOrNull { it.id == reviewPropId }
        }
        val reviewDestination = remember(reviewProperty, effectiveUserProfile, allProperties) {
            reviewProperty?.let { repository.resolvePropertyMapDestination(it.id, effectiveUserProfile) }
        }
        val canEditReviewLocation = remember(reviewProperty, effectiveUserProfile, allProperties) {
            reviewProperty?.let { repository.canEditPropertyLocation(it.id, effectiveUserProfile) } ?: false
        }
        if (reviewProperty != null && reviewDestination != null) {
            LocationReviewModalSheet(
                property = reviewProperty,
                destination = reviewDestination,
                isDirectionsRequest = locationReviewForDirections,
                initialError = locationReviewError,
                canEditLocation = canEditReviewLocation,
                onEditLocation = {
                    locationReviewPropertyId = null
                    locationReviewError = null
                    editingPropertyTarget = reviewProperty
                },
                onOpenUri = { targetUri, onError ->
                    launchMapUriSafely(targetUri, onError)
                },
                onCopyLocation = { textToCopy, onResult ->
                    copyLocationSafely(textToCopy, onResult)
                },
                onDismiss = {
                    locationReviewPropertyId = null
                    locationReviewError = null
                }
            )
        } else {
            LaunchedEffect(reviewPropId) {
                locationReviewPropertyId = null
                locationReviewError = null
            }
        }
    }

    // Global Matching Brokers Multi-Select Batch Share Sheet (triggered from any PropertyCard's Brokers button)
    matchingBrokersTargetProperty?.let { targetProp ->
        var targetPhotoSources by remember(targetProp.id) { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(targetProp.id) {
            targetPhotoSources = WhatsAppShareHelper.collectPropertyPhotoSources(context, listOf(targetProp))
        }
        val unifiedBrokerMessage = remember(
            targetProp.id,
            effectiveUserProfile.username,
            effectiveUserProfile.mobileNumber,
            currentEmployee?.employeeName
        ) {
            WhatsAppShareFormatter.formatProperty(
                property = targetProp,
                employeeName = effectiveUserProfile.username.ifBlank { currentEmployee?.employeeName ?: "TLC Advisory" },
                employeeMobile = effectiveUserProfile.mobileNumber.ifBlank { WhatsAppShareFormatter.FALLBACK_DESK_MOBILE }
            )
        }
        ShareToMatchingBrokersSheet(
            property = targetProp,
            unifiedMessage = unifiedBrokerMessage,
            photoSources = targetPhotoSources,
            onDismiss = { matchingBrokersTargetProperty = null },
            onTriggerSyncBrokers = {
                oneTapSyncCoordinator.triggerOneTapSync(
                    employee = sessionState.currentEmployee,
                    showSheetOnFinish = false
                )
            }
        )
    }

    // Batch 5 + Batch 20: Smart Post-Call Pop-Up Bottom Sheet Overlay with Sequential Clear Queue Mode
    val advanceMainPostCallQueue: () -> Unit = {
        val nextIdx = postCallQueueIndex + 1
        if (nextIdx < postCallQueue.size) {
            postCallQueueIndex = nextIdx
            activePostCall = postCallQueue[nextIdx]
        } else {
            activePostCall = null
            postCallQueue = emptyList()
            postCallQueueIndex = 0
        }
    }

    activePostCall?.let { currentCall ->
        var currentLeads by remember(currentCall.id) { mutableStateOf<List<CrmLead>>(emptyList()) }
        LaunchedEffect(currentCall.id, crmExternalRefreshTick) {
            currentLeads = withContext(Dispatchers.IO) { readCrmLeads(context) }
        }
        CallLogActionScreen(
            call = currentCall,
            leads = currentLeads,
            inventory = scopedProperties,
            directoryProjects = directoryProjects,
            underConstProjects = scopedUnderConstProjects,
            tenants = tenantsList,
            brokersVendors = brokersVendorsList,
            queuePosition = (postCallQueueIndex + 1).coerceAtLeast(1),
            queueTotal = postCallQueue.size.coerceAtLeast(1),
            onBack = {
                activePostCall = null
                postCallQueue = emptyList()
                postCallQueueIndex = 0
                coroutineScope.launch {
                    unloggedCallQueue = withContext(Dispatchers.IO) {
                        CrmCallManager.getUnloggedCallQueue(context)
                    }
                    callsExternalRefreshTick++
                }
            },
            onSkipLater = { c ->
                CrmCallManager.markCallSkipped(context, c)
                advanceMainPostCallQueue()
                coroutineScope.launch {
                    val refreshedQueue = withContext(Dispatchers.IO) {
                        CrmCallManager.removeFromUnloggedQueue(context, c)
                        CrmCallManager.markPostCallPopupShown(context, c)
                        CrmCallManager.getUnloggedCallQueue(context)
                    }
                    unloggedCallQueue = refreshedQueue
                    callsExternalRefreshTick++
                }
            },
            onMarkPersonalIgnore = { c ->
                Toast.makeText(context, "Marked ${c.phoneNumber} as Ignored / Spam", Toast.LENGTH_SHORT).show()
                advanceMainPostCallQueue()
                coroutineScope.launch {
                    val refreshedQueue = withContext(Dispatchers.IO) {
                        IgnoredNumbersManager.markPersonalIgnored(context, c.phoneNumber)
                        CrmCallManager.removeFromUnloggedQueue(context, c)
                        CrmCallManager.markPostCallPopupShown(context, c)
                        CrmCallManager.getUnloggedCallQueue(context)
                    }
                    unloggedCallQueue = refreshedQueue
                    callsExternalRefreshTick++
                }
            },
            onOpenExistingRecord = { match, session ->
                CallActionSessionManager.beginSession(session)
                activePostCall = null
                postCallQueue = emptyList()
                postCallQueueIndex = 0
                when {
                    match.matchedLead != null -> {
                        val lead = match.matchedLead
                        CrmCallManager.markCallHandledAndLinked(
                            context = context,
                            callId = session.callId,
                            fallbackCall = session.callRecord,
                            oppType = if (lead.clientType.contains("Broker", true)) "Broker" else "Client",
                            oppTitle = lead.displayClientTitle(),
                            oppId = lead.id,
                            notesOverride = session.aiSummaryNote
                        )
                        pendingCrmSelectedLeadId = lead.id
                        crmExternalRefreshTick++
                        currentDestination = DrawerDestination.CRM_WORKSPACE
                    }
                    match.matchedProperties.isNotEmpty() -> {
                        val prop = match.matchedProperties.first()
                        CrmCallManager.markCallHandledAndLinked(
                            context = context,
                            callId = session.callId,
                            fallbackCall = session.callRecord,
                            oppType = "Owner",
                            oppTitle = listOf(prop.projectName, prop.unitNumber).filter { it.isNotBlank() }.joinToString(" ").ifBlank { prop.propertyCode },
                            oppId = prop.propertyCode.ifBlank { prop.id },
                            notesOverride = session.aiSummaryNote
                        )
                        activeDetailProperty = prop
                    }
                    match.matchedTenant != null -> {
                        val tenant = match.matchedTenant
                        CrmCallManager.markCallHandledAndLinked(
                            context = context,
                            callId = session.callId,
                            fallbackCall = session.callRecord,
                            oppType = "Tenant",
                            oppTitle = tenant.tenantName,
                            oppId = tenant.id,
                            notesOverride = session.aiSummaryNote
                        )
                        pendingTenantId = tenant.id
                        pendingTenantDraft = tenant
                        pendingBrokerVendorDraft = null
                        pendingTenantsTabIndex = 0
                        currentDestination = DrawerDestination.TENANTS_LEASES
                    }
                    match.matchedBrokerVendor != null -> {
                        val bv = match.matchedBrokerVendor
                        CrmCallManager.markCallHandledAndLinked(
                            context = context,
                            callId = session.callId,
                            fallbackCall = session.callRecord,
                            oppType = if (bv.roleCategory.contains("Vendor", true) || bv.roleCategory.contains("Ops", true)) "Vendor" else "Broker",
                            oppTitle = bv.name,
                            oppId = bv.id,
                            notesOverride = session.aiSummaryNote
                        )
                        pendingBrokerVendorDraft = bv
                        pendingTenantId = null
                        pendingTenantDraft = null
                        pendingTenantsTabIndex = 2
                        currentDestination = DrawerDestination.TENANTS_LEASES
                    }
                    match.matchedDirectoryProject != null -> {
                        val proj = match.matchedDirectoryProject
                        CrmCallManager.markCallHandledAndLinked(
                            context = context,
                            callId = session.callId,
                            fallbackCall = session.callRecord,
                            oppType = "Developer POC",
                            oppTitle = proj.name,
                            oppId = proj.id,
                            notesOverride = session.aiSummaryNote
                        )
                        pendingProjectDirectoryId = proj.id
                        currentDestination = DrawerDestination.PROJECT_DIRECTORY
                    }
                    match.matchedUnderConstProject != null -> {
                        val ucProj = match.matchedUnderConstProject
                        CrmCallManager.markCallHandledAndLinked(
                            context = context,
                            callId = session.callId,
                            fallbackCall = session.callRecord,
                            oppType = "Developer POC",
                            oppTitle = ucProj.projectName,
                            oppId = ucProj.id,
                            notesOverride = session.aiSummaryNote
                        )
                        pendingProjectDirectoryId = ucProj.id
                        currentDestination = DrawerDestination.PROJECT_DIRECTORY
                    }
                    else -> {
                        pendingCrmPrefillLead = CrmLead(
                            id = "",
                            name = session.callerName,
                            phone = session.callerPhone,
                            segment = "Commercial",
                            requirement = session.aiSummaryNote.ifBlank { "Inbound Call Inquiry" },
                            location = "",
                            budget = "",
                            status = "New Lead",
                            verificationStatus = "Verified Live",
                            nextFollowUp = "",
                            notes = session.aiSummaryNote
                        )
                        crmExternalRefreshTick++
                        currentDestination = DrawerDestination.CRM_WORKSPACE
                    }
                }
                coroutineScope.launch {
                    unloggedCallQueue = withContext(Dispatchers.IO) {
                        CrmCallManager.getUnloggedCallQueue(context)
                    }
                    callsExternalRefreshTick++
                }
            },
            onRouteClientLead = { segment, session ->
                CallActionSessionManager.beginSession(session.copy(targetRoleLabel = "$segment Client"))
                activePostCall = null
                postCallQueue = emptyList()
                postCallQueueIndex = 0
                pendingCallNavigation = PendingCallNavigation(
                    targetRoute = "crm_workspace",
                    role = PostCallRole.CLIENT_LEAD,
                    prefillName = session.callerName,
                    prefillPhone = session.callerPhone,
                    prefillNote = session.aiSummaryNote,
                    segmentHint = segment,
                    isBrokerClient = false,
                    callId = session.callId
                )
            },
            onRouteOwnerAddProperty = { initialTab, session ->
                CallActionSessionManager.beginSession(session.copy(targetRoleLabel = "Owner"))
                activePostCall = null
                postCallQueue = emptyList()
                postCallQueueIndex = 0
                pendingAddPropertyName = session.callerName
                pendingAddPropertyPhone = session.callerPhone
                pendingAddPropertyNote = session.aiSummaryNote
                pendingAddPropertyPocType = "Property Owner"
                pendingAddPropertyTab = initialTab
                pendingAddPropertyFromCall = true
                currentDestination = DrawerDestination.ADD_PROPERTY
            },
            onUpdateOwnerUnitStatus = { prop, newStatus, callNote, updatedPrice ->
                val ok = repository.confirmOwnerCallStatus(
                    propertyId = prop.id,
                    newStatus = newStatus,
                    callNote = callNote,
                    confirmedBy = effectiveUserProfile.username,
                    updatedRentOrSale = updatedPrice,
                    pocType = prop.pocType.takeIf { it.isNotBlank() }
                )
                Toast.makeText(
                    context,
                    if (ok) "Unit ${prop.propertyCode.ifBlank { prop.projectName }} updated & call linked" else "Unable to update unit",
                    Toast.LENGTH_SHORT
                ).show()
                ok
            },
            onRouteBrokerHasClient = { session ->
                CallActionSessionManager.beginSession(session.copy(targetRoleLabel = "Broker Client"))
                activePostCall = null
                postCallQueue = emptyList()
                postCallQueueIndex = 0
                pendingCallNavigation = PendingCallNavigation(
                    targetRoute = "crm_workspace",
                    role = PostCallRole.CLIENT_LEAD,
                    prefillName = session.callerName,
                    prefillPhone = session.callerPhone,
                    prefillNote = session.aiSummaryNote,
                    segmentHint = "Commercial",
                    isBrokerClient = true,
                    callId = session.callId
                )
            },
            onRouteBrokerHasProperty = { session ->
                CallActionSessionManager.beginSession(session.copy(targetRoleLabel = "Broker"))
                activePostCall = null
                postCallQueue = emptyList()
                postCallQueueIndex = 0
                pendingAddPropertyName = session.callerName
                pendingAddPropertyPhone = session.callerPhone
                pendingAddPropertyNote = session.aiSummaryNote
                pendingAddPropertyPocType = "Broker"
                pendingAddPropertyTab = null
                pendingAddPropertyFromCall = true
                currentDestination = DrawerDestination.ADD_PROPERTY
            },
            onRouteTenant = { existingTenant, session ->
                CallActionSessionManager.beginSession(session.copy(targetRoleLabel = "Tenant"))
                activePostCall = null
                postCallQueue = emptyList()
                postCallQueueIndex = 0
                pendingTenantDraft = existingTenant ?: OfflineDiskCache.TenantCacheRecord(
                    id = "TNT-${java.util.UUID.randomUUID().toString().take(8).uppercase(java.util.Locale.US)}",
                    tenantName = session.callerName,
                    contactNumber = session.callerPhone,
                    notes = session.aiSummaryNote,
                    status = "Active"
                )
                pendingTenantId = existingTenant?.id
                pendingTenantLinkProperty = null
                pendingBrokerVendorDraft = null
                pendingTenantsTabIndex = 0
                currentDestination = DrawerDestination.TENANTS_LEASES
            },
            onRouteDeveloperPoc = { matchedProject, session ->
                CallActionSessionManager.beginSession(session.copy(targetRoleLabel = "Developer POC"))
                activePostCall = null
                postCallQueue = emptyList()
                postCallQueueIndex = 0
                if (matchedProject != null) {
                    CrmCallManager.markCallHandledAndLinked(
                        context = context,
                        callId = session.callId,
                        fallbackCall = session.callRecord,
                        oppType = "Developer POC",
                        oppTitle = matchedProject.name,
                        oppId = matchedProject.id,
                        notesOverride = session.aiSummaryNote
                    )
                    coroutineScope.launch {
                        unloggedCallQueue = withContext(Dispatchers.IO) {
                            CrmCallManager.getUnloggedCallQueue(context)
                        }
                        callsExternalRefreshTick++
                    }
                }
                pendingProjectDirectoryId = matchedProject?.id
                currentDestination = DrawerDestination.PROJECT_DIRECTORY
            },
            onRouteVendor = { existingVendor, session ->
                CallActionSessionManager.beginSession(session.copy(targetRoleLabel = "Vendor"))
                activePostCall = null
                postCallQueue = emptyList()
                postCallQueueIndex = 0
                pendingBrokerVendorDraft = existingVendor ?: BrokerVendorRecord(
                    id = "BV-${java.util.UUID.randomUUID().toString().take(8).uppercase(java.util.Locale.US)}",
                    name = session.callerName,
                    phone = session.callerPhone,
                    roleCategory = "Ops / Vendor",
                    subRole = BrokerVendorRecord.opsSubRoles.first(),
                    notes = session.aiSummaryNote
                )
                pendingTenantId = null
                pendingTenantDraft = null
                pendingTenantLinkProperty = null
                pendingTenantsTabIndex = 2
                currentDestination = DrawerDestination.TENANTS_LEASES
            },
            onCallHandled = { handledCall ->
                advanceMainPostCallQueue()
                coroutineScope.launch {
                    val refreshedQueue = withContext(Dispatchers.IO) {
                        CrmCallManager.markPostCallPopupShown(context, handledCall)
                        CrmCallManager.removeFromUnloggedQueue(context, handledCall)
                        CrmCallManager.getUnloggedCallQueue(context)
                    }
                    unloggedCallQueue = refreshedQueue
                    callsExternalRefreshTick++
                }
            }
        )
    }

    postCallMatchTargetLead?.let { selectedLead ->
        CrmInventoryMatchesDialog(
            lead = selectedLead,
            inventory = scopedProperties,
            onClose = { postCallMatchTargetLead = null }
        )
    }

    postCallVisitTargetLead?.let { selectedLead ->
        CrmSiteVisitsDialog(
            leadId = selectedLead.id,
            clientName = selectedLead.displayClientTitle(),
            inventory = scopedProperties,
            lead = selectedLead,
            onClose = {
                postCallVisitTargetLead = null
                crmExternalRefreshTick++
            }
        )
    }

    availableAppUpdate?.let { updateInfo ->
        com.example.ui.components.AppUpdateDialog(
            updateInfo = updateInfo,
            onDismiss = { repository.dismissAvailableAppUpdate() }
        )
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationReviewModalSheet(
    property: PropertyItem,
    destination: ResolvedMapDestination,
    isDirectionsRequest: Boolean,
    initialError: String?,
    canEditLocation: Boolean,
    onEditLocation: () -> Unit,
    onOpenUri: (String, (String) -> Unit) -> Unit,
    onCopyLocation: (String, (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val navy = TextPrimary
    val teal = PrimaryActionOrange
    val softWhite = BackgroundSoft
    val borderColor = BorderGrey

    val sources = remember(destination, isDirectionsRequest) {
        buildList {
            destination.alternateCoordinateUri?.let { coordSearchUri ->
                val target = if (isDirectionsRequest) destination.directionsUri else coordSearchUri
                add(
                    Triple(
                        "COORDINATES",
                        "Recorded Coordinates",
                        target to (destination.alternateCoordinateUri ?: destination.primaryUri)
                    )
                )
            }
            destination.alternateMapLinkUri?.let { mapUrl ->
                add(
                    Triple(
                        "MAP_LINK",
                        "Stored Maps Link",
                        mapUrl to mapUrl
                    )
                )
            }
            if (destination.mode == DestinationMode.ADDRESS_SEARCH && destination.primaryUri.isNotBlank()) {
                add(
                    Triple(
                        "ADDRESS_SEARCH",
                        "Recorded Address Search (${destination.copyableText})",
                        destination.primaryUri to destination.copyableText
                    )
                )
            }
        }
    }

    var selectedSourceKey by remember(destination.propertyId, sources) {
        mutableStateOf(sources.firstOrNull()?.first ?: "")
    }
    var feedbackMessage by remember(destination.propertyId, initialError) { mutableStateOf(initialError) }
    var feedbackIsError by remember(destination.propertyId, initialError) { mutableStateOf(initialError != null) }

    val selectedEntry = sources.firstOrNull { it.first == selectedSourceKey } ?: sources.firstOrNull()
    val headerSubtitle = remember(property.propertyCode, property.projectName) {
        listOf(
            property.propertyCode.trim(),
            property.projectName.trim().ifBlank { "Property" }
        ).filter { it.isNotBlank() }.joinToString(" • ")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = softWhite,
        contentColor = navy,
        modifier = Modifier.testTag("location_review_modal_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isDirectionsRequest) "Review Directions Destination" else "Location Review",
                        color = navy,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = headerSubtitle,
                        color = Color(0xFF486581),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = navy)
                }
            }

            HorizontalDivider(color = borderColor)

            // Precision & Warning Banner
            Surface(
                color = if (destination.hasConflict || destination.mode == DestinationMode.ADDRESS_SEARCH) Color(0xFFFEF3C7) else Color(0xFFE6FFFA),
                border = BorderStroke(1.dp, if (destination.hasConflict || destination.mode == DestinationMode.ADDRESS_SEARCH) Color(0xFFF59E0B) else teal),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("location_review_warning_banner")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = destination.precision.label,
                        color = navy,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (destination.warningLabel.isNotBlank()) {
                        Text(
                            text = destination.warningLabel,
                            color = Color(0xFF334E68),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (feedbackMessage != null) {
                Surface(
                    color = if (feedbackIsError) Color(0xFFFEE2E2) else Color(0xFFE6FFFA),
                    border = BorderStroke(1.dp, if (feedbackIsError) Color(0xFFEF4444) else teal),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("location_review_feedback_banner")
                ) {
                    Text(
                        text = feedbackMessage.orEmpty(),
                        color = if (feedbackIsError) Color(0xFF991B1B) else navy,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (destination.mode == DestinationMode.UNAVAILABLE || sources.isEmpty()) {
                Surface(
                    color = Color.White,
                    border = BorderStroke(1.dp, borderColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Location not recorded",
                            color = navy,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("sheet_location_unavailable")
                        )
                        if (canEditLocation) {
                            Button(
                                onClick = onEditLocation,
                                colors = ButtonDefaults.buttonColors(containerColor = teal, contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("sheet_edit_location_btn")
                            ) {
                                Icon(Icons.Default.EditLocationAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Edit Location", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                sources.forEach { (key, title, uris) ->
                    val isSelected = selectedEntry?.first == key
                    Surface(
                        color = Color.White,
                        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) teal else borderColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSourceKey = key }
                            .testTag("location_source_$key")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedSourceKey = key },
                                colors = RadioButtonDefaults.colors(selectedColor = teal, unselectedColor = navy)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = navy,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = uris.first,
                                    color = Color(0xFF486581),
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        val target = selectedEntry?.third?.first.orEmpty()
                        if (target.isNotBlank()) {
                            feedbackMessage = null
                            onOpenUri(target) { err ->
                                feedbackIsError = true
                                feedbackMessage = err
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = teal, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("location_review_open_btn")
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open Selected Location", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                OutlinedButton(
                    onClick = {
                        val copyValue = selectedEntry?.third?.second ?: destination.copyableText
                        onCopyLocation(copyValue) { msg ->
                            feedbackIsError = msg != "Location copied"
                            feedbackMessage = msg
                        }
                    },
                    border = BorderStroke(1.dp, navy.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = navy),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("location_review_copy_btn")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Location", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }

            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(1.dp, borderColor),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = navy),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("location_review_cancel_btn")
            ) {
                Text("Cancel", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun FindPropertiesOnMapComingSoon(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .padding(24.dp)
            .testTag("find_on_map_coming_soon"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(BrandGold.copy(alpha = 0.15f))
                .border(1.5.dp, BrandGold, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Map, contentDescription = null, tint = BrandGold, modifier = Modifier.size(36.dp))
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "FIND PROPERTIES ON MAP",
            color = BrandGold,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Interactive Map Explorer",
            color = TextPrimaryDark,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Map-based geo-cluster discovery for Pune prime commercial & residential corridors (Baner, Kharadi, Hinjewadi, Kalyani Nagar, Viman Nagar) is arriving in the next release.",
            color = TextSecondaryDark,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(40.dp)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Back to Home Dashboard", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
fun EmployeeLoginScreen(
    initialScriptUrl: String,
    onAuthenticate: suspend (loginId: String, password: String, scriptUrl: String?) -> Result<UserProfile>,
    onUpdatePendingPassword: suspend (employeeId: String, newPassword: String, scriptUrl: String?) -> Result<Unit>,
    onLoginSuccess: (UserProfile) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var loginIdInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val initialHasScriptUrl = remember(initialScriptUrl) {
        initialScriptUrl.contains("script.google.com", ignoreCase = true)
    }
    var scriptUrlInput by remember(initialScriptUrl) {
        mutableStateOf(if (initialHasScriptUrl) initialScriptUrl else "")
    }
    var showScriptUrlField by remember(initialHasScriptUrl) {
        mutableStateOf(!initialHasScriptUrl)
    }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var pendingProfile by remember { mutableStateOf<UserProfile?>(null) }
    var newPasswordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var pendingDialogError by remember { mutableStateOf<String?>(null) }
    var isUpdatingPassword by remember { mutableStateOf(false) }

    Surface(
        color = BackgroundSoft,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag("employee_login_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val loginIsDark = TlcThemeController.isDark
            Surface(
                color = if (loginIsDark) Color(0xFF1E2328) else Color(0xFFFFFFFF),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderGrey),
                modifier = Modifier
                    .padding(bottom = 14.dp)
                    .testTag("login_tlc_logo")
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_tlc_logo),
                    contentDescription = "The Leasing Company Logo",
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(if (loginIsDark) Color.White else Color(0xFF111827)),
                    modifier = Modifier
                        .width(140.dp)
                        .height(44.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Text(
                text = "THE LEASING COMPANY",
                color = PrimaryNavy,
                fontFamily = FontFamily.Serif,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            Text(
                text = "TLC-1 Employee Sign In",
                color = SecondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            Surface(
                color = SurfaceWhite,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderGrey),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Sign in with your User ID (EMP-001), Email Address, or Phone Number",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = loginIdInput,
                        onValueChange = {
                            loginIdInput = it
                            errorMessage = null
                        },
                        label = { Text("User ID (EMP-001), Email, or Phone", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Badge,
                                contentDescription = "User ID, Email, or Phone",
                                tint = SecondaryText
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_id_input")
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            errorMessage = null
                        },
                        label = { Text("Password (Optional)", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Password",
                                tint = SecondaryText
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = SecondaryText
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_password_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { showScriptUrlField = !showScriptUrlField },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = PrimaryTeal,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (showScriptUrlField) "Hide Server URL" else "Apps Script Server URL",
                                color = PrimaryTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (showScriptUrlField) {
                        OutlinedTextField(
                            value = scriptUrlInput,
                            onValueChange = {
                                scriptUrlInput = it
                                errorMessage = null
                            },
                            label = { Text("Apps Script Web App URL (/exec)", fontSize = 11.sp) },
                            placeholder = { Text("https://script.google.com/macros/s/.../exec", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_script_url_input")
                        )
                    }

                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            color = ErrorRedSubtle,
                            border = BorderStroke(1.dp, ErrorRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("login_error_banner")
                        ) {
                            Text(
                                text = errorMessage.orEmpty(),
                                color = ErrorRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (isLoading) return@Button
                            isLoading = true
                            errorMessage = null
                            coroutineScope.launch {
                                val overrideUrl = scriptUrlInput.trim().takeIf { it.isNotBlank() }
                                val result = onAuthenticate(loginIdInput, passwordInput, overrideUrl)
                                isLoading = false
                                result.onSuccess { profile ->
                                    if (profile.accountStatus.equals("Pending Setup", ignoreCase = true)) {
                                        newPasswordInput = ""
                                        confirmPasswordInput = ""
                                        pendingDialogError = null
                                        pendingProfile = profile
                                    } else {
                                        onLoginSuccess(profile)
                                    }
                                }.onFailure { err ->
                                    errorMessage = err.message?.takeIf { it.isNotBlank() } ?: err.localizedMessage ?: "Sign in failed. Verify credentials and server URL."
                                }
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryTeal,
                            contentColor = OnPrimaryActionColor
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("employee_login_submit_btn")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = OnPrimaryActionColor,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Signing In...", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        } else {
                            Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    val pendingUser = pendingProfile
    if (pendingUser != null) {
        AlertDialog(
            onDismissRequest = { if (!isUpdatingPassword) pendingProfile = null },
            containerColor = SurfaceWhite,
            modifier = Modifier.testTag("pending_password_setup_dialog"),
            title = {
                Text(
                    text = "Set New Password",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Welcome ${pendingUser.username}. Your account is Pending Setup. Set a personal password to activate access.",
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = newPasswordInput,
                        onValueChange = {
                            newPasswordInput = it
                            pendingDialogError = null
                        },
                        label = { Text("New Password", fontSize = 12.sp) },
                        trailingIcon = {
                            IconButton(
                                onClick = { newPasswordVisible = !newPasswordVisible },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (newPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (newPasswordVisible) "Hide new password" else "Show new password",
                                    tint = SecondaryText
                                )
                            }
                        },
                        visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_password_input")
                    )
                    OutlinedTextField(
                        value = confirmPasswordInput,
                        onValueChange = {
                            confirmPasswordInput = it
                            pendingDialogError = null
                        },
                        label = { Text("Confirm Password", fontSize = 12.sp) },
                        trailingIcon = {
                            IconButton(
                                onClick = { confirmPasswordVisible = !confirmPasswordVisible },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (confirmPasswordVisible) "Hide confirm password" else "Show confirm password",
                                    tint = SecondaryText
                                )
                            }
                        },
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("confirm_password_input")
                    )
                    if (!pendingDialogError.isNullOrBlank()) {
                        Text(
                            text = pendingDialogError.orEmpty(),
                            color = ErrorRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isUpdatingPassword) return@Button
                        val trimmedNew = newPasswordInput.trim()
                        val trimmedConfirm = confirmPasswordInput.trim()
                        when {
                            trimmedNew.length < 4 -> {
                                pendingDialogError = "Password must be at least 4 characters."
                            }
                            trimmedNew != trimmedConfirm -> {
                                pendingDialogError = "Passwords do not match."
                            }
                            else -> {
                                isUpdatingPassword = true
                                pendingDialogError = null
                                coroutineScope.launch {
                                    val overrideUrl = scriptUrlInput.trim().takeIf { it.isNotBlank() }
                                    val updateRes = onUpdatePendingPassword(pendingUser.employeeId, trimmedNew, overrideUrl)
                                    isUpdatingPassword = false
                                    updateRes.onSuccess {
                                        val activeUser = pendingUser.copy(accountStatus = "Active")
                                        pendingProfile = null
                                        onLoginSuccess(activeUser)
                                    }.onFailure { err ->
                                        pendingDialogError = err.localizedMessage ?: "Unable to update password."
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isUpdatingPassword,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryTeal,
                        contentColor = OnPrimaryActionColor
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("save_new_password_btn")
                ) {
                    Text(
                        text = if (isUpdatingPassword) "Saving..." else "Save & Sign In",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!isUpdatingPassword) pendingProfile = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }
}


