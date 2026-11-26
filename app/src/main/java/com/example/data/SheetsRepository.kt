package com.example.data

import android.content.Context
import com.example.access.ActiveBusinessView
import com.example.access.BusinessDivisionScope
import com.example.access.EmployeeProfile
import com.example.crm.CallableContact
import com.example.crm.CallableContactType
import com.example.crm.ContactUtils
import com.example.model.*
import com.example.data.projects.DirectoryProject
import com.example.data.projects.ProjectDirectoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

data class TabReconciliationOutcome(
    val tabName: String,
    val statusOk: Boolean,
    val sheetRowCount: Int,
    val removedLocalCount: Int,
    val removedRoomCount: Int,
    val preservedPendingCount: Int
)

data class TabMergeOutcome(
    val addedCount: Int = 0,
    val updatedCount: Int = 0,
    val removedCount: Int = 0,
    val preservedPendingCount: Int = 0,
    val totalProcessed: Int = 0
)

enum class TopSyncStatus {
    SYNCED,
    SYNCING,
    OFFLINE_PENDING
}

data class TopSyncChipState(
    val status: TopSyncStatus = TopSyncStatus.SYNCED,
    val pendingCount: Int = 0,
    val lastError: String? = null
)

data class SyncDiagnosticsSnapshot(
    val configuredScriptUrl: String = TlcSchemaV2Api.BASE_WEB_APP_URL,
    val activeSpreadsheetName: String = TlcSchemaV2Api.EXPECTED_SPREADSHEET_NAME,
    val activeSpreadsheetId: String = TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID,
    val expectedSpreadsheetId: String = TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID,
    val isExpectedSpreadsheetConnected: Boolean = true,
    val tabRowCounts: Map<String, Int> = TlcInventoryTabV2.ALL_FIVE_TABS.associateWith { 0 },
    val lastRemovedStaleCount: Int = 0,
    val lastPreservedPendingCount: Int = 0,
    val lastSyncedAt: String = "Never",
    val lastStatusSummary: String = "Ready"
) {
    val totalSyncedSheetRows: Int
        get() = tabRowCounts.values.sum()
}

class SheetsRepository(
    private val context: Context? = null,
    private val startForegroundSync: Boolean = true,
    deferInitialDiskLoad: Boolean = false
) {

    private val _properties = MutableStateFlow<List<PropertyItem>>(
        if (context != null && !deferInitialDiskLoad) PropertiesLocalDatabase.loadProperties(context) else emptyList()
    )
    val properties: StateFlow<List<PropertyItem>> = _properties.asStateFlow()
    private val httpClient = OkHttpClient()
    @Volatile
    private var diskLoaded: Boolean = context == null || !deferInitialDiskLoad
    @Volatile
    private var lastNonInventorySyncedCount: Int = 0
    private val syncMutex = Mutex()
    private val ioWriterMutex = Mutex()
    private val ioWriterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun actor() = UserSettingsManager(context).auditIdentity()
    private fun allowed(action: String) = UserSettingsManager(context).userProfile.value.role.canPerformSensitiveAction(action)
    private fun stamped(p: PropertyItem) = p.copy(updatedBy = actor(), updatedAt = java.time.Instant.now().toString())

    @Synchronized
    private fun persistChanges(): Boolean {
        val currentList = _properties.value
        val updatedList = currentList.map { p ->
            if (!p.isSyncedToSheet) stamped(p) else p
        }
        _properties.value = updatedList
        com.example.inventory.PropertyPrecomputedCache.warmUp(updatedList)
        val saved = if (context != null) {
            PropertiesLocalDatabase.saveProperties(context, updatedList)
        } else true
        if (saved) {
            refreshPendingSyncState(reconcileDisk = false)
        } else if (context != null) {
            _properties.value = PropertiesLocalDatabase.loadProperties(context)
        }
        return saved
    }

    private val _pendingSyncCount = MutableStateFlow(0)
    val pendingSyncCount: StateFlow<Int> = _pendingSyncCount.asStateFlow()
    private val _pendingSyncStatus = MutableStateFlow("No pending sync changes")
    val pendingSyncStatus: StateFlow<String> = _pendingSyncStatus.asStateFlow()
    private val _isSyncingNow = MutableStateFlow(false)
    val isSyncingNow: StateFlow<Boolean> = _isSyncingNow.asStateFlow()
    private val _topSyncChipState = MutableStateFlow(TopSyncChipState())
    val topSyncChipState: StateFlow<TopSyncChipState> = _topSyncChipState.asStateFlow()
    private val queueMutex = sharedQueueMutex

    internal fun close() {
        repoScope.cancel()
        ioWriterScope.cancel()
    }

    private fun countPendingMultiDeviceRecords(appContext: Context): Int {
        val pendingPropQueue = runCatching { PropertiesLocalDatabase.loadPendingWrites(appContext).size }.getOrDefault(0)
        val pendingPropFlags = _properties.value.count { !it.isSyncedToSheet }
        val pendingProps = maxOf(pendingPropQueue, pendingPropFlags)
        val pendingLeads = runCatching {
            val prefs = auditedCrmPreferences(appContext, "crm_development_v1", Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString("leads", "[]") ?: "[]")
            var cnt = 0
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optBoolean("pendingSync", false)) cnt++
            }
            cnt
        }.getOrDefault(0)
        val pendingCalls = runCatching {
            val roomPending = kotlinx.coroutines.runBlocking {
                com.example.data.master.MasterProjectDatabase.getDatabase(appContext)
                    .masterProjectDao()
                    .countPendingCallLogsSync()
            }
            val localPending = com.example.crm.CrmCallManager.readCalls(appContext)
                .count { it.syncStatus.equals("PENDING", ignoreCase = true) }
            maxOf(roomPending, localPending)
        }.getOrDefault(0)
        return pendingProps + pendingLeads + pendingCalls
    }

    private fun isDeviceOnline(appContext: Context): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    internal fun refreshTopSyncChipState(
        syncing: Boolean = _isSyncingNow.value,
        lastError: String? = null
    ) {
        val appContext = context
        val totalPending = if (appContext != null) countPendingMultiDeviceRecords(appContext) else _pendingSyncCount.value
        val online = if (appContext != null) isDeviceOnline(appContext) else true
        val status = when {
            syncing -> TopSyncStatus.SYNCING
            !online || totalPending > 0 -> TopSyncStatus.OFFLINE_PENDING
            else -> TopSyncStatus.SYNCED
        }
        _topSyncChipState.value = TopSyncChipState(
            status = status,
            pendingCount = totalPending,
            lastError = lastError
        )
    }

    private fun refreshPendingSyncState(
        reconcileDisk: Boolean = true,
        preloadedPersisted: Map<String, PropertyItem>? = null
    ) {
        val appContext = context ?: return
        try {
            val queue = PropertiesLocalDatabase.loadPendingWrites(appContext)
            if (reconcileDisk) {
                val persisted = preloadedPersisted ?: PropertiesLocalDatabase.loadProperties(appContext).associateBy { it.id }
                _properties.update { current ->
                    current.map { item ->
                        val saved = persisted[item.id]
                        if (!item.isSyncedToSheet && saved?.isSyncedToSheet == true &&
                            AppsScriptSyncProtocol.sameWriteSnapshot(item, saved)) {
                            item.copy(isSyncedToSheet = true)
                        } else item
                    }
                }
            }
            val totalPending = countPendingMultiDeviceRecords(appContext)
            _pendingSyncCount.value = totalPending
            val review = queue.count { it.state == PendingWriteState.REVIEW }
            _pendingSyncStatus.value = if (totalPending == 0) "No pending sync changes"
                else "$totalPending pending changes; $review require review"
            refreshTopSyncChipState()
        } catch (_: Exception) {
            _pendingSyncStatus.value = "Pending queue unavailable; original data retained"
        }
    }

    private val _underConstProjects = MutableStateFlow<List<UnderConstProject>>(emptyList())
    val underConstProjects: StateFlow<List<UnderConstProject>> = _underConstProjects.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    private val _comparisonIds = MutableStateFlow<List<String>>(emptyList())
    val comparisonIds: StateFlow<List<String>> = _comparisonIds.asStateFlow()

    private var activeComparisonKey: String = "default_staff"
    private val inMemoryComparisonByKey = mutableMapOf<String, List<String>>()

    private val _backups = MutableStateFlow<List<BackupSnapshot>>(emptyList())
    val backups: StateFlow<List<BackupSnapshot>> = _backups.asStateFlow()

    private val _storageHealth = MutableStateFlow(
        if (context != null && !deferInitialDiskLoad) PropertiesLocalDatabase.inspectStorageHealth(context) else StorageHealthReport()
    )
    val storageHealth: StateFlow<StorageHealthReport> = _storageHealth.asStateFlow()

    private val _pendingSyncOps = MutableStateFlow<List<PendingSyncOp>>(
        if (context != null && !deferInitialDiskLoad) PendingSyncOpStore.loadAll(context) else emptyList()
    )
    val pendingSyncOps: StateFlow<List<PendingSyncOp>> = _pendingSyncOps.asStateFlow()

    private val _availableAppUpdate = MutableStateFlow<AppUpdateInfo?>(null)
    val availableAppUpdate: StateFlow<AppUpdateInfo?> = _availableAppUpdate.asStateFlow()

    private val _syncDiagnostics = MutableStateFlow(loadPersistedSyncDiagnostics())
    val syncDiagnostics: StateFlow<SyncDiagnosticsSnapshot> = _syncDiagnostics.asStateFlow()

    private fun loadPersistedSyncDiagnostics(): SyncDiagnosticsSnapshot {
        val appContext = context ?: return SyncDiagnosticsSnapshot()
        val prefs = appContext.getSharedPreferences(SYNC_DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
        val scriptUrl = prefs.getString("configured_script_url", TlcSchemaV2Api.BASE_WEB_APP_URL)
            ?.takeIf { it.isNotBlank() } ?: TlcSchemaV2Api.BASE_WEB_APP_URL
        val sheetName = prefs.getString("active_spreadsheet_name", TlcSchemaV2Api.EXPECTED_SPREADSHEET_NAME)
            ?.takeIf { it.isNotBlank() } ?: TlcSchemaV2Api.EXPECTED_SPREADSHEET_NAME
        val sheetId = prefs.getString("active_spreadsheet_id", TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID)
            ?.takeIf { it.isNotBlank() } ?: TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID
        val countsJson = prefs.getString("tab_row_counts_json", null)
        val parsedCounts = linkedMapOf<String, Int>().apply {
            TlcInventoryTabV2.ALL_FIVE_TABS.forEach { put(it, 0) }
            if (!countsJson.isNullOrBlank()) {
                runCatching {
                    val obj = JSONObject(countsJson)
                    TlcInventoryTabV2.ALL_FIVE_TABS.forEach { tab ->
                        if (obj.has(tab)) put(tab, obj.optInt(tab, 0).coerceAtLeast(0))
                    }
                }
            }
        }
        return SyncDiagnosticsSnapshot(
            configuredScriptUrl = scriptUrl,
            activeSpreadsheetName = sheetName,
            activeSpreadsheetId = sheetId,
            expectedSpreadsheetId = TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID,
            isExpectedSpreadsheetConnected = sheetId.equals(TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID, ignoreCase = true),
            tabRowCounts = parsedCounts,
            lastRemovedStaleCount = prefs.getInt("last_removed_stale_count", 0),
            lastPreservedPendingCount = prefs.getInt("last_preserved_pending_count", 0),
            lastSyncedAt = prefs.getString("last_synced_at", "Never") ?: "Never",
            lastStatusSummary = prefs.getString("last_status_summary", "Ready") ?: "Ready"
        )
    }

    internal fun updateSyncDiagnostics(
        configuredScriptUrl: String = _sheetsScriptUrl.value.ifBlank { TlcSchemaV2Api.BASE_WEB_APP_URL },
        activeSpreadsheetName: String? = null,
        activeSpreadsheetId: String? = null,
        tabRowCounts: Map<String, Int>? = null,
        lastRemovedStaleCount: Int = _syncDiagnostics.value.lastRemovedStaleCount,
        lastPreservedPendingCount: Int = _syncDiagnostics.value.lastPreservedPendingCount,
        lastSyncedAt: String = _syncDiagnostics.value.lastSyncedAt,
        lastStatusSummary: String = _syncDiagnostics.value.lastStatusSummary
    ): SyncDiagnosticsSnapshot {
        val current = _syncDiagnostics.value
        val resolvedName = activeSpreadsheetName?.takeIf { it.isNotBlank() }
            ?: current.activeSpreadsheetName.ifBlank { TlcSchemaV2Api.EXPECTED_SPREADSHEET_NAME }
        val resolvedId = activeSpreadsheetId?.takeIf { it.isNotBlank() }
            ?: current.activeSpreadsheetId.ifBlank { TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID }
        val mergedCounts = linkedMapOf<String, Int>().apply {
            TlcInventoryTabV2.ALL_FIVE_TABS.forEach { tab ->
                put(tab, tabRowCounts?.get(tab) ?: current.tabRowCounts[tab] ?: 0)
            }
        }
        val snapshot = SyncDiagnosticsSnapshot(
            configuredScriptUrl = TlcSchemaV2Api.resolveEndpointUrl(configuredScriptUrl),
            activeSpreadsheetName = resolvedName,
            activeSpreadsheetId = resolvedId,
            expectedSpreadsheetId = TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID,
            isExpectedSpreadsheetConnected = resolvedId.equals(TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID, ignoreCase = true),
            tabRowCounts = mergedCounts,
            lastRemovedStaleCount = lastRemovedStaleCount,
            lastPreservedPendingCount = lastPreservedPendingCount,
            lastSyncedAt = lastSyncedAt,
            lastStatusSummary = lastStatusSummary
        )
        _syncDiagnostics.value = snapshot
        context?.let { appContext ->
            val countsObj = JSONObject().apply {
                mergedCounts.forEach { (tab, count) -> put(tab, count) }
            }
            appContext.getSharedPreferences(SYNC_DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("configured_script_url", snapshot.configuredScriptUrl)
                .putString("active_spreadsheet_name", snapshot.activeSpreadsheetName)
                .putString("active_spreadsheet_id", snapshot.activeSpreadsheetId)
                .putString("tab_row_counts_json", countsObj.toString())
                .putInt("last_removed_stale_count", snapshot.lastRemovedStaleCount)
                .putInt("last_preserved_pending_count", snapshot.lastPreservedPendingCount)
                .putString("last_synced_at", snapshot.lastSyncedAt)
                .putString("last_status_summary", snapshot.lastStatusSummary)
                .apply()
        }
        return snapshot
    }

    fun setAvailableAppUpdate(info: AppUpdateInfo?) {
        _availableAppUpdate.value = info
    }

    fun dismissAvailableAppUpdate() {
        _availableAppUpdate.value = null
    }

    private val processedMutationOutcomes = LinkedHashMap<String, PropertyEditOutcome>()

    fun refreshStorageHealth(): StorageHealthReport {
        val report = context?.let { PropertiesLocalDatabase.inspectStorageHealth(it) } ?: StorageHealthReport()
        _storageHealth.value = report
        return report
    }

    @Synchronized
    fun recoverFromCorruptStorageUsingBackup(): StorageHealthReport {
        val appContext = context ?: return StorageHealthReport()
        val report = PropertiesLocalDatabase.quarantineCorruptPrimaryAndRestoreBackup(appContext)
        _storageHealth.value = report
        if (report.state != StorageHealthState.CORRUPT_REQUIRES_ACTION) {
            _properties.value = PropertiesLocalDatabase.loadProperties(appContext)
            refreshPendingSyncState()
            refreshPendingSyncOps()
        }
        return report
    }

    @Synchronized
    fun resetCorruptStorageAfterQuarantine(): StorageHealthReport {
        val appContext = context ?: return StorageHealthReport()
        val report = PropertiesLocalDatabase.quarantineCorruptPrimaryAndResetFresh(appContext)
        _storageHealth.value = report
        _properties.value = PropertiesLocalDatabase.loadProperties(appContext)
        refreshPendingSyncState()
        refreshPendingSyncOps()
        return report
    }

    fun refreshPendingSyncOps() {
        val appContext = context ?: return
        _pendingSyncOps.value = PendingSyncOpStore.loadAll(appContext)
    }

    fun retryBlockedAndRetryableOps(hasValidAuth: Boolean): Int {
        val appContext = context ?: return 0
        val count = PendingSyncOpStore.retryBlockedAndRetryable(appContext, hasValidAuth)
        refreshPendingSyncOps()
        return count
    }

    fun resolveConflictKeepLocalOp(opId: String, propertyId: String): Boolean {
        val appContext = context ?: return false
        val currentVersion = _properties.value.firstOrNull { it.id == propertyId }?.recordVersion ?: 0
        val ok = PendingSyncOpStore.resolveConflictKeepLocal(appContext, opId, currentVersion)
        refreshPendingSyncOps()
        return ok
    }

    fun discardPendingSyncOp(opId: String): Boolean {
        val appContext = context ?: return false
        val ok = PendingSyncOpStore.discardOp(appContext, opId)
        refreshPendingSyncOps()
        return ok
    }

    companion object {
        const val DEFAULT_MASTER_SHEET_URL = TlcSchemaV2Api.BASE_WEB_APP_URL
        const val AUTO_SYNC_INTERVAL_MS = 15L * 60L * 1000L
        const val FOREGROUND_SYNC_INTERVAL_MS = 2L * 60L * 1000L
        const val FOREGROUND_RESUME_PULL_INTERVAL_MS = 2L * 60L * 1000L
        private const val LAST_SYNC_AT_MILLIS_KEY = "last_successful_sync_millis"
        private const val FILE_V2_BOOTSTRAP_CACHE = "tlc_v2_bootstrap_cache.json"
        val DEFAULT_SYNCED_TABS = TlcSchemaV2Api.CANONICAL_SYNC_TABS + TlcSchemaV2Api.OPERATIONAL_RECONCILE_TABS
        const val MAX_COMPARISON_COUNT = 3
        private const val COMPARISON_PREFS_NAME = "property_comparison_selection_v1"
        private const val MUTATION_DEDUP_PREFS_NAME = "processed_mutation_ids_v1"
        private const val SYNCED_EMPLOYEES_PREFS = "tlc_synced_employees_v1"
        private const val SYNC_DIAGNOSTICS_PREFS = "tlc_sync_diagnostics_v1"
        private val LEGACY_FAKE_EMPLOYEE_NAMES = setOf(
            "amit",
            "amit sharma",
            "neha",
            "neha gupta",
            "rohan",
            "rohan deshmukh",
            "vikram",
            "vikram patel",
            "rahul sharma",
            "satish",
            "satish fuke",
            "himanshu",
            "himanshu m. chordiya",
            "himanshu chordiya",
            "ram",
            "ram kamble",
            "admin",
            "staff",
            "test",
            "test user",
            "demo",
            "demo user",
            "sample employee"
        )
        private val sharedQueueMutex = Mutex()

        fun formatSyncedAgoLabel(lastSyncAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
            if (lastSyncAtMillis <= 0L) return "Not synced yet"
            val diffMs = (nowMillis - lastSyncAtMillis).coerceAtLeast(0L)
            val mins = diffMs / 60_000L
            return when {
                mins < 1L -> "Synced just now"
                mins < 60L -> "Synced ${mins}m ago"
                mins < 24L * 60L -> "Synced ${mins / 60L}h ago"
                else -> "Synced ${mins / (24L * 60L)}d ago"
            }
        }

        fun getSyncedActiveEmployeeNames(context: Context?): List<String> {
            if (context == null) return emptyList()
            val roomNames = runCatching {
                kotlinx.coroutines.runBlocking {
                    com.example.access.EmployeeAccessDatabase.get(context).employeeDao().getAll()
                }.filter {
                    it.isActive && it.accessStatus == com.example.access.EmployeeAccessStatus.ACTIVE
                }.map { it.employeeName.trim() }
            }.getOrDefault(emptyList())

            val diskCached = OfflineDiskCache.loadEmployeeNames(context)
            val prefCached = if (roomNames.isEmpty() && diskCached.isEmpty()) {
                runCatching {
                    val prefs = context.getSharedPreferences(SYNCED_EMPLOYEES_PREFS, Context.MODE_PRIVATE)
                    val raw = prefs.getString("active_names", "[]") ?: "[]"
                    val arr = JSONArray(raw)
                    (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { n -> n.isNotBlank() } }
                }.getOrDefault(emptyList())
            } else emptyList()

            val sourceList = when {
                roomNames.isNotEmpty() -> roomNames
                diskCached.isNotEmpty() -> diskCached
                else -> prefCached
            }

            return sourceList
                .map { it.trim() }
                .filter { name ->
                    name.isNotBlank() &&
                        !name.equals("Unassigned", ignoreCase = true) &&
                        name.lowercase(Locale.ENGLISH) !in LEGACY_FAKE_EMPLOYEE_NAMES
                }
                .distinctBy { it.lowercase(Locale.ENGLISH) }
        }

        fun getSyncedActiveEmployeeDirectory(context: Context?): List<OfflineDiskCache.CachedEmployeeMember> {
            if (context == null) return emptyList()
            val diskMembers = OfflineDiskCache.loadEmployeeDirectory(context)
                .filter { it.isActiveMember() && it.name.lowercase(Locale.ENGLISH) !in LEGACY_FAKE_EMPLOYEE_NAMES }
            if (diskMembers.isNotEmpty()) return diskMembers
            return getSyncedActiveEmployeeNames(context).map { name ->
                OfflineDiskCache.CachedEmployeeMember(name = name)
            }
        }

        suspend fun loadActiveEmployeeDirectoryWithRoom(context: Context): List<OfflineDiskCache.CachedEmployeeMember> =
            withContext(Dispatchers.IO) {
                val diskMembers = OfflineDiskCache.loadEmployeeDirectory(context)
                    .filter { it.isActiveMember() && it.name.lowercase(Locale.ENGLISH) !in LEGACY_FAKE_EMPLOYEE_NAMES }
                val roomMembers = runCatching {
                    com.example.access.EmployeeAccessDatabase.get(context).employeeDao().getAll()
                        .filter {
                            it.isActive &&
                                it.accessStatus == com.example.access.EmployeeAccessStatus.ACTIVE &&
                                it.employeeName.trim().lowercase(Locale.ENGLISH) !in LEGACY_FAKE_EMPLOYEE_NAMES
                        }
                        .map { entity ->
                            val divLabel = entity.businessScopes.joinToString(" & ") { scope ->
                                if (scope == com.example.access.BusinessDivisionScope.ASCEND_COMMERCIAL) "Commercial" else "Residential"
                            }
                            OfflineDiskCache.CachedEmployeeMember(
                                id = entity.employeeId,
                                name = entity.employeeName.trim(),
                                mobile = entity.loginMobile.trim(),
                                role = entity.jobTitle?.takeIf { it.isNotBlank() } ?: entity.role.label,
                                division = divLabel,
                                status = "Active"
                            )
                        }
                }.getOrDefault(emptyList())

                val mergedByName = linkedMapOf<String, OfflineDiskCache.CachedEmployeeMember>()
                (diskMembers + roomMembers).forEach { member ->
                    val key = member.name.trim().lowercase(Locale.ENGLISH)
                    if (key.isBlank() || key == "unassigned") return@forEach
                    val existing = mergedByName[key]
                    if (existing == null) {
                        mergedByName[key] = member
                    } else {
                        mergedByName[key] = existing.copy(
                            id = existing.id.ifBlank { member.id },
                            mobile = existing.mobile.ifBlank { member.mobile },
                            role = existing.role.ifBlank { member.role },
                            division = existing.division.ifBlank { member.division }
                        )
                    }
                }
                if (mergedByName.isNotEmpty()) {
                    mergedByName.values.toList()
                } else {
                    getSyncedActiveEmployeeNames(context).map { OfflineDiskCache.CachedEmployeeMember(name = it) }
                }
            }

        internal fun isValidGoogleMapsHttpsUrl(rawUrl: String): Boolean {
            if (rawUrl.isBlank()) return false
            if (!rawUrl.startsWith("https://")) return false
            if (rawUrl.any { it.isWhitespace() }) return false
            val authorityPart = rawUrl.substringAfter("://")
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore("#")
            if (authorityPart.isBlank() || authorityPart.contains('@')) return false
            val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return false
            if (uri.scheme != "https") return false
            if (!uri.userInfo.isNullOrBlank()) return false
            val host = uri.host?.lowercase(Locale.ENGLISH).orEmpty()
            val path = uri.rawPath.orEmpty().lowercase(Locale.ENGLISH)
            return when (host) {
                "maps.google.com", "maps.app.goo.gl" -> true
                "www.google.com", "google.com" -> path == "/maps" || path.startsWith("/maps/")
                else -> false
            }
        }

        fun resolveMapDestinationForProperty(property: PropertyItem): ResolvedMapDestination {
            val lat = property.latitude ?: property.lat
            val lng = property.longitude ?: property.lng
            val hasValidCoords = lat != null && lng != null &&
                lat.isFinite() && lng.isFinite() &&
                lat in -90.0..90.0 && lng in -180.0..180.0 &&
                !(lat == 0.0 && lng == 0.0)

            val rawUrl = property.googleMapsUrl?.trim().orEmpty()
            val hasValidMapLink = isValidGoogleMapsHttpsUrl(rawUrl)

            val coordSearchUri = if (hasValidCoords) {
                "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
            } else null
            val coordDirectionsUri = if (hasValidCoords) {
                "https://www.google.com/maps/dir/?api=1&destination=$lat,$lng"
            } else null

            val hasConflict = if (hasValidCoords && hasValidMapLink) {
                val exactPair = "$lat,$lng"
                val encodedPair = "$lat%2C$lng"
                !rawUrl.contains(exactPair, ignoreCase = true) &&
                    !rawUrl.contains(encodedPair, ignoreCase = true)
            } else false

            val excludedPlaceholders = setOf("not mentioned", "n/a", "unknown")
            val publicQuery = listOf(
                property.projectName,
                property.fullAddress,
                property.location,
                property.city
            ).map { it.trim() }
                .filter { part ->
                    part.isNotBlank() && part.lowercase(Locale.ENGLISH) !in excludedPlaceholders
                }
                .distinctBy { it.lowercase(Locale.ENGLISH) }
                .joinToString(", ")

            return when {
                hasValidCoords -> ResolvedMapDestination(
                    propertyId = property.id,
                    propertyCode = property.propertyCode,
                    projectName = property.projectName,
                    mode = DestinationMode.COORDINATES,
                    precision = property.locationPrecision,
                    primaryUri = coordSearchUri!!,
                    directionsUri = coordDirectionsUri!!,
                    copyableText = coordSearchUri,
                    hasConflict = hasConflict,
                    alternateCoordinateUri = coordSearchUri,
                    alternateMapLinkUri = if (hasValidMapLink) rawUrl else null,
                    warningLabel = if (hasConflict) {
                        "Location needs review — both coordinates and a map link are recorded"
                    } else ""
                )
                hasValidMapLink -> ResolvedMapDestination(
                    propertyId = property.id,
                    propertyCode = property.propertyCode,
                    projectName = property.projectName,
                    mode = DestinationMode.MAP_LINK,
                    precision = property.locationPrecision,
                    primaryUri = rawUrl,
                    directionsUri = rawUrl,
                    copyableText = rawUrl,
                    hasConflict = false,
                    alternateCoordinateUri = null,
                    alternateMapLinkUri = rawUrl,
                    warningLabel = ""
                )
                publicQuery.isNotBlank() -> {
                    val encoded = java.net.URLEncoder.encode(publicQuery, "UTF-8").replace("+", "%20")
                    val searchUri = "https://www.google.com/maps/search/?api=1&query=$encoded"
                    ResolvedMapDestination(
                        propertyId = property.id,
                        propertyCode = property.propertyCode,
                        projectName = property.projectName,
                        mode = DestinationMode.ADDRESS_SEARCH,
                        precision = property.locationPrecision,
                        primaryUri = searchUri,
                        directionsUri = searchUri,
                        copyableText = publicQuery,
                        hasConflict = false,
                        alternateCoordinateUri = null,
                        alternateMapLinkUri = null,
                        warningLabel = "Search location — exact pin not verified"
                    )
                }
                else -> ResolvedMapDestination(
                    propertyId = property.id,
                    propertyCode = property.propertyCode,
                    projectName = property.projectName,
                    mode = DestinationMode.UNAVAILABLE,
                    precision = property.locationPrecision,
                    primaryUri = "",
                    directionsUri = "",
                    copyableText = "",
                    hasConflict = false,
                    alternateCoordinateUri = null,
                    alternateMapLinkUri = null,
                    warningLabel = "Location not recorded"
                )
            }
        }
    }

    private val _sheetsScriptUrl = MutableStateFlow<String>(DEFAULT_MASTER_SHEET_URL)
    val sheetsScriptUrl: StateFlow<String> = _sheetsScriptUrl.asStateFlow()

    private val _isLiveConnected = MutableStateFlow<Boolean>(false)
    val isLiveConnected: StateFlow<Boolean> = _isLiveConnected.asStateFlow()

    private val _connectionStatus = MutableStateFlow<String>("Not Connected - Ready for Google Sheet Setup")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow<String>("Never")
    val lastSyncTimestamp: StateFlow<String> = _lastSyncTimestamp.asStateFlow()

    data class ConnectionDiagnostic(
        val isSuccess: Boolean,
        val readStatus: String,
        val writeStatus: String,
        val latencyMs: Long,
        val recordsCount: Int,
        val message: String,
        val checkedAt: String
    )

    private val _lastDiagnostic = MutableStateFlow<ConnectionDiagnostic?>(null)
    val lastDiagnostic: StateFlow<ConnectionDiagnostic?> = _lastDiagnostic.asStateFlow()

    private val _syncLogs = MutableStateFlow<List<SyncLogEntry>>(emptyList())
    val syncLogs: StateFlow<List<SyncLogEntry>> = _syncLogs.asStateFlow()

    private val _dataReviewQueue = MutableStateFlow<List<DataReviewRow>>(emptyList())
    val dataReviewQueue: StateFlow<List<DataReviewRow>> = _dataReviewQueue.asStateFlow()

    fun logSyncEvent(direction: String, status: String, recordCount: Int, details: String, triggeredBy: String = "User") {
        val entry = SyncLogEntry(
            timestamp = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.ENGLISH).format(Date()),
            direction = direction,
            status = status,
            recordCount = recordCount,
            details = details,
            triggeredBy = triggeredBy
        )
        _syncLogs.value = listOf(entry) + _syncLogs.value.take(49)
    }

    fun addReviewRow(sourceFile: String, rowNumber: Int, rawData: String, issueReason: String, suggestedCode: String? = null) {
        val row = DataReviewRow(
            sourceFile = sourceFile,
            rowNumber = rowNumber,
            rawData = rawData,
            issueReason = issueReason,
            suggestedCode = suggestedCode,
            createdAt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())
        )
        _dataReviewQueue.value = listOf(row) + _dataReviewQueue.value
    }

    fun dismissReviewRow(reviewId: String) {
        _dataReviewQueue.value = _dataReviewQueue.value.filter { it.reviewId != reviewId }
    }

    fun clearAllReviewRows() {
        _dataReviewQueue.value = emptyList()
    }

    fun clearSyncLogs() {
        _syncLogs.value = emptyList()
    }

    private val _syncRevision = MutableStateFlow(0)
    val syncRevision: StateFlow<Int> = _syncRevision.asStateFlow()

    private val _dailyReports = MutableStateFlow<List<DailyReportRecord>>(
        if (context != null && !deferInitialDiskLoad) OfflineDiskCache.loadDailyReports(context) else emptyList()
    )
    val dailyReports: StateFlow<List<DailyReportRecord>> = _dailyReports.asStateFlow()

    private val _tenants = MutableStateFlow<List<OfflineDiskCache.TenantCacheRecord>>(
        if (context != null && !deferInitialDiskLoad) OfflineDiskCache.loadTenants(context) else emptyList()
    )
    val tenants: StateFlow<List<OfflineDiskCache.TenantCacheRecord>> = _tenants.asStateFlow()

    private val _brokersVendors = MutableStateFlow<List<BrokerVendorRecord>>(
        if (context != null && !deferInitialDiskLoad) OfflineDiskCache.loadBrokersVendors(context) else emptyList()
    )
    val brokersVendors: StateFlow<List<BrokerVendorRecord>> = _brokersVendors.asStateFlow()

    data class SyncBannerState(
        val hasError: Boolean = false,
        val httpStatusCode: Int? = null,
        val title: String = "",
        val message: String = "",
        val hint: String = "",
        val failedAction: String = "",
        val isRetrying: Boolean = false,
        val updatedAtMillis: Long = 0L
    )

    private val _syncBannerState = MutableStateFlow(SyncBannerState())
    val syncBannerState: StateFlow<SyncBannerState> = _syncBannerState.asStateFlow()

    fun reportSyncError(
        title: String,
        rawError: String,
        failedAction: String = "Master Sheet Sync"
    ) {
        val cleanErr = rawError.trim().ifBlank { "Unable to reach TLC Master sheet Apps Script (/exec)." }
        val statusCode = when {
            cleanErr.contains("401") -> 401
            cleanErr.contains("403") -> 403
            cleanErr.contains("404") -> 404
            cleanErr.contains("500") -> 500
            else -> null
        }
        val isAuthIssue = statusCode == 401 || statusCode == 403 ||
            cleanErr.contains("Apps Script deployment", ignoreCase = true) ||
            cleanErr.contains("returned HTML", ignoreCase = true)
        val hintText = if (isAuthIssue) {
            TlcSchemaV2Api.AUTH_401_403_HINT
        } else {
            "Local changes are saved safely. Tap Retry once online or check Sync Setup."
        }
        _syncBannerState.value = SyncBannerState(
            hasError = true,
            httpStatusCode = statusCode,
            title = if (isAuthIssue) "Apps Script Sync Blocked (HTTP ${statusCode ?: 401})" else title,
            message = cleanErr,
            hint = hintText,
            failedAction = failedAction,
            isRetrying = false,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    fun setSyncBannerRetrying(retrying: Boolean) {
        _syncBannerState.update { cur ->
            if (!cur.hasError) cur else cur.copy(isRetrying = retrying)
        }
    }

    fun clearSyncError() {
        if (_syncBannerState.value.hasError) {
            _syncBannerState.value = SyncBannerState()
        }
    }

    // Schema v2 reactive state (bootstrap dropdowns, team directory, visits, dashboard KPIs)
    private val _masterDropdowns = MutableStateFlow(TlcMasterDropdowns())
    val masterDropdowns: StateFlow<TlcMasterDropdowns> = _masterDropdowns.asStateFlow()

    private val _bootstrapTeam = MutableStateFlow<List<TlcTeamMember>>(emptyList())
    val bootstrapTeam: StateFlow<List<TlcTeamMember>> = _bootstrapTeam.asStateFlow()

    private val _siteVisits = MutableStateFlow<List<TlcSiteVisitRecord>>(emptyList())
    val siteVisits: StateFlow<List<TlcSiteVisitRecord>> = _siteVisits.asStateFlow()

    private val _dashboardKpis = MutableStateFlow<TlcDashboardKpis?>(null)
    val dashboardKpis: StateFlow<TlcDashboardKpis?> = _dashboardKpis.asStateFlow()

    private val _lastSyncAtMillis = MutableStateFlow(
        if (context != null && !deferInitialDiskLoad) {
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getLong(LAST_SYNC_AT_MILLIS_KEY, 0L)
        } else 0L
    )
    val lastSyncAtMillis: StateFlow<Long> = _lastSyncAtMillis.asStateFlow()

    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun hydrateLocalStateFromDisk(startupContext: Context, includePropertiesAndHealth: Boolean) {
        if (includePropertiesAndHealth) {
            val loadedProps = PropertiesLocalDatabase.loadProperties(startupContext)
            _properties.value = loadedProps
            _dailyReports.value = OfflineDiskCache.loadDailyReports(startupContext)
            val cachedTenants = OfflineDiskCache.loadTenants(startupContext)
            _tenants.value = cachedTenants.ifEmpty { deriveTenantsFromProperties(loadedProps) }
            _brokersVendors.value = OfflineDiskCache.loadBrokersVendors(startupContext)
            _storageHealth.value = PropertiesLocalDatabase.inspectStorageHealth(startupContext)
            _pendingSyncOps.value = PendingSyncOpStore.loadAll(startupContext)
            runCatching { PropertiesLocalDatabase.recoverPendingWrites(startupContext) }
            refreshPendingSyncState(reconcileDisk = false)
        } else {
            runCatching { PropertiesLocalDatabase.recoverPendingWrites(startupContext) }
            refreshPendingSyncState(reconcileDisk = true)
            if (_tenants.value.isEmpty() && _properties.value.isNotEmpty()) {
                _tenants.value = deriveTenantsFromProperties(_properties.value)
            }
            if (_brokersVendors.value.isEmpty()) {
                _brokersVendors.value = OfflineDiskCache.loadBrokersVendors(startupContext)
            }
        }

        val prefs = startupContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedSyncMs = prefs.getLong(LAST_SYNC_AT_MILLIS_KEY, 0L)
        if (savedSyncMs > 0L) {
            _lastSyncAtMillis.value = savedSyncMs
            _lastSyncTimestamp.value = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(savedSyncMs))
        }
        _favoriteIds.value = prefs.getStringSet("favorite_property_ids", emptySet())?.toSet() ?: emptySet()
        val initialUser = UserSettingsManager(startupContext).userProfile.value
        val initialCompKey = comparisonEmployeeKey(initialUser)
        activeComparisonKey = initialCompKey
        _comparisonIds.value = loadSavedComparisonIds(initialCompKey)
        val rawSavedUrl = prefs.getString("google_sheet_url", "")?.trim().orEmpty()
        val savedUrl = TlcSchemaV2Api.resolveEndpointUrl(rawSavedUrl)
        if (rawSavedUrl.contains(TlcSchemaV2Api.DEPRECATED_V1_SCRIPT_ID, ignoreCase = true) || rawSavedUrl.isBlank()) {
            prefs.edit().putString("google_sheet_url", savedUrl).apply()
        }
        _sheetsScriptUrl.value = savedUrl
        _isLiveConnected.value = false
        _connectionStatus.value = if (savedUrl.isNotBlank()) "TLC Master Sheet (Schema v2) ready" else "Not Connected - Ready for Google Sheet Setup"
        loadCachedBootstrapPayload(startupContext)
        sanitizeShiftedInventoryRecords(startupContext)
        if (_properties.value.isNotEmpty()) {
            runCatching { OfflineDiskCache.saveInventorySplits(startupContext, _properties.value) }
        }
    }

    private fun loadCachedBootstrapPayload(appContext: Context) {
        runCatching {
            val file = java.io.File(appContext.filesDir, FILE_V2_BOOTSTRAP_CACHE)
            if (!file.exists()) return
            val parsed = TlcBootstrapPayload.fromJson(JSONObject(file.readText()))
            _masterDropdowns.value = parsed.dropdowns
            _bootstrapTeam.value = parsed.team
        }
    }

    private fun saveCachedBootstrapPayload(appContext: Context, payload: TlcBootstrapPayload) {
        _masterDropdowns.value = payload.dropdowns
        _bootstrapTeam.value = payload.team
        runCatching {
            val file = java.io.File(appContext.filesDir, FILE_V2_BOOTSTRAP_CACHE)
            val tmp = java.io.File(appContext.filesDir, "$FILE_V2_BOOTSTRAP_CACHE.tmp")
            tmp.writeText(payload.toJson().toString())
            if (!tmp.renameTo(file)) {
                file.writeText(payload.toJson().toString())
                tmp.delete()
            }
        }
        if (payload.team.isNotEmpty()) {
            val filteredTeam = payload.team.filter { member ->
                val lowerName = member.fullName.trim().lowercase(Locale.ENGLISH)
                lowerName.isNotBlank() && lowerName !in LEGACY_FAKE_EMPLOYEE_NAMES
            }
            val cachedEmployees = filteredTeam.map { member ->
                OfflineDiskCache.CachedEmployeeMember(
                    id = member.userId.ifBlank { member.fullName },
                    name = member.fullName.trim(),
                    mobile = member.phoneNumber.trim(),
                    division = member.division.trim(),
                    teamId = "",
                    role = member.appRole.trim(),
                    status = member.activeStatus.trim().ifBlank { "Active" },
                    managerId = "",
                    loginId = member.userId.ifBlank { member.emailAddress.ifBlank { member.phoneNumber } },
                    password = "",
                    updatedAt = java.time.Instant.now().toString()
                )
            }
            OfflineDiskCache.saveEmployeeDirectory(appContext, cachedEmployees)
            com.example.crm.IgnoredNumbersManager.syncInternalEmployeeNumbers(
                appContext,
                cachedEmployees.map { it.mobile }.filter { it.isNotBlank() }
            )
            val namesArr = JSONArray().apply {
                filteredTeam.filter { it.isActive() }.forEach { put(it.fullName.trim()) }
            }
            appContext.getSharedPreferences(SYNCED_EMPLOYEES_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("active_names", namesArr.toString())
                .apply()

            // Reconcile Room EmployeeAccessDatabase with synced Team & Users so stale/test users are removed
            runCatching {
                val empDao = com.example.access.EmployeeAccessDatabase.get(appContext).employeeDao()
                val syncedIds = mutableListOf<String>()
                kotlinx.coroutines.runBlocking {
                    filteredTeam.forEach { member ->
                        val empId = member.userId.trim().ifBlank {
                            "EMP-${Math.abs(member.fullName.trim().hashCode()).toString(36).uppercase().take(6)}"
                        }
                        syncedIds.add(empId)
                        val normalizedMob = com.example.access.PhoneNormalizer.normalizeIndianMobile(member.phoneNumber)
                            ?: member.phoneNumber.trim().takeIf { it.isNotBlank() } ?: "0000000000"
                        val roleRaw = member.appRole
                        val accessRole = when {
                            roleRaw.contains("super", true) || roleRaw.contains("admin", true) -> com.example.access.EmployeeAccessRole.ADMIN
                            roleRaw.contains("commercial", true) -> com.example.access.EmployeeAccessRole.COMMERCIAL_MANAGER
                            roleRaw.contains("residential", true) -> com.example.access.EmployeeAccessRole.RESIDENTIAL_MANAGER
                            roleRaw.contains("manager", true) -> com.example.access.EmployeeAccessRole.COMMERCIAL_MANAGER
                            else -> com.example.access.EmployeeAccessRole.SALES_EXECUTIVE
                        }
                        val statusLower = member.activeStatus.lowercase(Locale.ENGLISH)
                        val accessStatus = when {
                            statusLower.contains("suspend") || statusLower == "false" || statusLower == "disabled" || statusLower == "inactive" ->
                                com.example.access.EmployeeAccessStatus.SUSPENDED
                            statusLower.contains("pending") -> com.example.access.EmployeeAccessStatus.PENDING
                            else -> com.example.access.EmployeeAccessStatus.ACTIVE
                        }
                        val scopes = when {
                            member.division.contains("both", true) || member.division.contains("all", true) ->
                                com.example.access.BusinessDivisionScope.entries.toList()
                            member.division.contains("resi", true) ->
                                listOf(com.example.access.BusinessDivisionScope.AUREA_RESIDENTIAL)
                            else -> listOf(com.example.access.BusinessDivisionScope.ASCEND_COMMERCIAL)
                        }
                        val profile = com.example.access.EmployeeProfile(
                            employeeId = empId,
                            employeeName = member.fullName.trim(),
                            loginMobile = normalizedMob,
                            loginEmail = member.emailAddress.trim().ifBlank { null },
                            role = accessRole,
                            businessScopes = scopes,
                            reportsToEmployeeId = null,
                            jobTitle = roleRaw.ifBlank { "Sales Executive" },
                            isActive = accessStatus == com.example.access.EmployeeAccessStatus.ACTIVE,
                            accessStatus = accessStatus,
                            identityStatus = com.example.access.EmployeeIdentityStatus.VERIFIED_ACCOUNT,
                            authorizationSource = com.example.access.AuthorizationSource.TRUSTED_PROVISIONING,
                            activeBusinessView = com.example.access.ActiveBusinessView.ALL_BUSINESSES,
                            pinEnabled = false,
                            pinCredentialRef = null,
                            pinFailedAttempts = 0,
                            pinLockedUntil = null,
                            approvedDeskContacts = com.example.access.APPROVED_DESK_CONTACTS,
                            recordVersion = 1
                        )
                        empDao.upsert(com.example.access.EmployeeEntity.fromDomain(profile))
                    }
                    if (syncedIds.isNotEmpty()) {
                        empDao.deleteWhereIdNotIn(syncedIds)
                    }
                }
            }
        }
    }

    fun ensureLoadedFromDisk() {
        val startupContext = context ?: return
        if (diskLoaded) return
        synchronized(this) {
            if (diskLoaded) return
            hydrateLocalStateFromDisk(startupContext, includePropertiesAndHealth = true)
            diskLoaded = true
        }
    }

    fun reloadFromDisk() {
        val appContext = context ?: return
        val loaded = PropertiesLocalDatabase.loadProperties(appContext)
        _properties.value = loaded
        _dailyReports.value = OfflineDiskCache.loadDailyReports(appContext)
        val cachedTenants = OfflineDiskCache.loadTenants(appContext)
        _tenants.value = cachedTenants.ifEmpty { deriveTenantsFromProperties(loaded) }
        _brokersVendors.value = OfflineDiskCache.loadBrokersVendors(appContext)
        refreshPendingSyncState(reconcileDisk = false)
    }

    fun isSyncStale(maxAgeMs: Long = AUTO_SYNC_INTERVAL_MS, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val last = _lastSyncAtMillis.value
        if (last <= 0L) return true
        return (nowMillis - last) >= maxAgeMs
    }

    fun markSuccessfulSyncNow(nowMillis: Long = System.currentTimeMillis()) {
        _lastSyncAtMillis.value = nowMillis
        val formatted = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(nowMillis))
        _lastSyncTimestamp.value = formatted
        context?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            ?.edit()
            ?.putLong(LAST_SYNC_AT_MILLIS_KEY, nowMillis)
            ?.apply()
    }

    suspend fun syncIfStale(maxAgeMs: Long = AUTO_SYNC_INTERVAL_MS): String? {
        if (_sheetsScriptUrl.value.trim().isBlank()) return null
        if (!isSyncStale(maxAgeMs)) return null
        return syncMutex.withLock {
            if (!isSyncStale(maxAgeMs)) return@withLock null
            val result = syncWithGoogleSheetNow()
            retryPendingWrites()
            result
        }
    }

    fun syncIfStaleInBackground(maxAgeMs: Long = AUTO_SYNC_INTERVAL_MS) {
        if (!isSyncStale(maxAgeMs) || _sheetsScriptUrl.value.trim().isBlank()) return
        repoScope.launch {
            runCatching { syncIfStale(maxAgeMs) }
        }
    }

    init {
        val startupContext = context
        if (startupContext != null && !deferInitialDiskLoad) {
            hydrateLocalStateFromDisk(startupContext, includePropertiesAndHealth = false)
            diskLoaded = true
            if (startForegroundSync) {
                runCatching {
                    BackgroundSyncScheduler.ensureScheduled(startupContext)
                    BackgroundSyncScheduler.ensureMasterAutoSyncScheduled(startupContext)
                }
            }
        } else if (startupContext != null && startForegroundSync) {
            repoScope.launch {
                ensureLoadedFromDisk()
                runCatching {
                    BackgroundSyncScheduler.ensureScheduled(startupContext)
                    BackgroundSyncScheduler.ensureMasterAutoSyncScheduled(startupContext)
                }
            }
        }

        // Active foreground sync every 3 minutes without blocking UI (WorkManager stays at 15 min in background)
        if (startForegroundSync) repoScope.launch {
            ensureLoadedFromDisk()
            while (isActive) {
                if (_sheetsScriptUrl.value.isNotBlank() && isSyncStale(FOREGROUND_SYNC_INTERVAL_MS)) {
                    runCatching {
                        syncIfStale(FOREGROUND_SYNC_INTERVAL_MS)
                    }
                }
                delay(FOREGROUND_SYNC_INTERVAL_MS)
            }
        }
    }

    fun setGoogleSheetUrl(url: String) {
        val trimmed = TlcSchemaV2Api.resolveEndpointUrl(url)
        _sheetsScriptUrl.value = trimmed
        _isLiveConnected.value = false
        _connectionStatus.value = "TLC Master Sheet (Schema v2) URL saved - verifying connection"
        val settingsContext = context
        if (settingsContext != null) {
            val prefs = settingsContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("google_sheet_url", trimmed).apply()
            runCatching { BackgroundSyncScheduler.ensureScheduled(settingsContext) }
        }
        if (trimmed.isNotBlank()) {
            repoScope.launch {
                syncWithGoogleSheetNow()
                retryPendingWrites()
            }
        }
    }

    suspend fun testGoogleSheetConnection(): ConnectionDiagnostic = withContext(Dispatchers.IO) {
        val rawUrl = _sheetsScriptUrl.value.trim()
        val timestamp = SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.ENGLISH).format(Date())
        if (rawUrl.isBlank()) {
            val result = ConnectionDiagnostic(
                isSuccess = false,
                readStatus = "Failed: No URL set",
                writeStatus = "Not tested",
                latencyMs = 0L,
                recordsCount = 0,
                message = "Please configure a valid Google Sheet or Google Apps Script URL first.",
                checkedAt = timestamp
            )
            _lastDiagnostic.value = result
            return@withContext result
        }

        val startTime = System.currentTimeMillis()
        var readSuccess = false
        var writeSuccess = false
        var recordsParsed = 0
        var diagnosticMessage = ""

        try {
            // Always route connection tests through the configured Apps Script Web App (/exec)
            // instead of direct docs.google.com/spreadsheets URLs (which return HTTP 401 on private sheets).
            val endpoint = TlcSchemaV2Api.resolveEndpointUrl(rawUrl)
            val bootstrapRes = TlcSchemaV2Api.fetchBootstrap(endpoint)
            val currentRole = context?.let { UserSettingsManager.readCurrentApiRole(it) } ?: "RM"
            val invRes = TlcSchemaV2Api.fetchInventoryRaw(endpoint, role = currentRole)
            val latency = System.currentTimeMillis() - startTime

            if (bootstrapRes.isFailure && invRes.isFailure) {
                val err = bootstrapRes.exceptionOrNull()?.message
                    ?: invRes.exceptionOrNull()?.message
                    ?: "Unable to connect to TLC Master Sheet Apps Script (/exec)."
                val result = ConnectionDiagnostic(
                    isSuccess = false,
                    readStatus = "Apps Script /exec Failed",
                    writeStatus = "Not tested",
                    latencyMs = latency,
                    recordsCount = 0,
                    message = err,
                    checkedAt = timestamp
                )
                _lastDiagnostic.value = result
                return@withContext result
            }

            val teamCount = bootstrapRes.getOrNull()?.team?.size ?: 0
            bootstrapRes.getOrNull()?.let { payload ->
                context?.let { saveCachedBootstrapPayload(it, payload) }
            }
            val invJson = invRes.getOrNull()
            val parsedRows = invJson?.let { parseAppsScriptJsonData(it.toString()) }.orEmpty()
            val (metaName, metaId) = invJson?.let { TlcSchemaV2Api.extractSpreadsheetMetadata(it) } ?: (null to null)
            val perTabCounts = linkedMapOf<String, Int>().apply {
                TlcInventoryTabV2.ALL_FIVE_TABS.forEach { put(it, 0) }
                parsedRows.forEach { row ->
                    val canonicalTab = TlcInventoryTabV2.resolveTabName(row.tab)
                    put(canonicalTab, (get(canonicalTab) ?: 0) + 1)
                }
            }
            readSuccess = bootstrapRes.isSuccess || invRes.isSuccess
            writeSuccess = readSuccess
            recordsParsed = parsedRows.size
            val resolvedName = metaName ?: TlcSchemaV2Api.EXPECTED_SPREADSHEET_NAME
            val resolvedId = metaId ?: TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID
            diagnosticMessage = "Connected to $resolvedName (${latency}ms): $teamCount team members, $recordsParsed inventory rows (role=$currentRole)."

            if (readSuccess) {
                updateSyncDiagnostics(
                    configuredScriptUrl = endpoint,
                    activeSpreadsheetName = resolvedName,
                    activeSpreadsheetId = resolvedId,
                    tabRowCounts = perTabCounts,
                    lastSyncedAt = timestamp,
                    lastStatusSummary = "Connection Verified ($recordsParsed rows across 5 tabs)"
                )
            }

            val finalResult = ConnectionDiagnostic(
                isSuccess = readSuccess,
                readStatus = if (readSuccess) "Verified ($resolvedName GET OK)" else "Read Failed",
                writeStatus = if (writeSuccess) "Ready (Apps Script /exec Verified)" else "API Key Required",
                latencyMs = latency,
                recordsCount = recordsParsed,
                message = diagnosticMessage,
                checkedAt = timestamp
            )
            _lastDiagnostic.value = finalResult
            logSyncEvent(
                direction = "VERIFICATION_TEST",
                status = if (finalResult.isSuccess) "SUCCESS" else "FAILED",
                recordCount = finalResult.recordsCount,
                details = finalResult.message,
                triggeredBy = "Diagnostics Test"
            )
            return@withContext finalResult

        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            val result = ConnectionDiagnostic(
                isSuccess = false,
                readStatus = "Network Error: ${e.localizedMessage ?: "Failed"}",
                writeStatus = "Not reachable",
                latencyMs = latency,
                recordsCount = 0,
                message = "Could not reach endpoint: ${e.localizedMessage}. Check internet connection or sheet URL.",
                checkedAt = timestamp
            )
            _lastDiagnostic.value = result
            logSyncEvent(
                direction = "VERIFICATION_TEST",
                status = "FAILED",
                recordCount = 0,
                details = result.message,
                triggeredBy = "Diagnostics Test"
            )
            return@withContext result
        }
    }

    fun triggerSyncNow(): String {
        var resultMsg = "Initiating Google Sheet Auto-Sync..."
        repoScope.launch {
            resultMsg = syncWithGoogleSheetNow()
            retryPendingWrites()
        }
        return resultMsg
    }

    suspend fun deleteRecordPermanently(tabName: String, keyHeader: String, keyValue: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanKey = keyValue.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing key value for permanent delete"))
        }
        runCatching {
            val endpoint = TlcSchemaV2Api.resolveEndpointUrl(_sheetsScriptUrl.value)
            val payload = JSONObject().apply {
                put("action", "DELETE")
                put("tabName", tabName)
                put("keyHeader", keyHeader)
                put("keyValue", cleanKey)
            }
            TlcSchemaV2Api.postAction(endpoint, payload).getOrThrow()
            logSyncEvent(
                direction = "APP_TO_SHEET",
                status = "SUCCESS",
                recordCount = 1,
                details = "Permanently deleted $tabName ($keyHeader=$cleanKey)",
                triggeredBy = actor()
            )
        }.onFailure { err ->
            logSyncEvent(
                direction = "APP_TO_SHEET",
                status = "FAILED",
                recordCount = 0,
                details = "Permanent delete failed for $tabName ($keyHeader=$cleanKey): ${err.message}",
                triggeredBy = actor()
            )
        }
    }

    private fun normalizeSyncIdentifier(raw: String?): String =
        raw?.trim()?.uppercase(Locale.ENGLISH).orEmpty()

    private fun collectPendingDraftIds(appContext: Context?): Set<String> {
        val pendingIds = linkedSetOf<String>()
        _properties.value.forEach { prop ->
            if (!prop.isSyncedToSheet) {
                listOf(prop.id, prop.recordId, prop.propertyCode).forEach { key ->
                    val norm = normalizeSyncIdentifier(key)
                    if (norm.isNotBlank()) pendingIds.add(norm)
                }
            }
        }
        if (appContext != null) {
            runCatching {
                PendingSyncOpStore.loadAll(appContext).forEach { op ->
                    val norm = normalizeSyncIdentifier(op.entityId)
                    if (norm.isNotBlank()) pendingIds.add(norm)
                }
            }
            runCatching {
                PropertiesLocalDatabase.loadPendingWrites(appContext).forEach { pw ->
                    listOf(pw.property.id, pw.property.recordId, pw.property.propertyCode).forEach { key ->
                        val norm = normalizeSyncIdentifier(key)
                        if (norm.isNotBlank()) pendingIds.add(norm)
                    }
                }
            }
        }
        return pendingIds
    }

    /**
     * Reconciles a single canonical inventory tab against a validated Google Sheet response.
     *
     * Rules:
     * - If `sheetStatusOk == false`, NO local or Room records are deleted.
     * - If `sheetStatusOk == true`, any local property or Room `PropertyUnitEntity` belonging to
     *   `tabName` whose `Unit ID` / `Property ID` / `recordId` / `propertyCode` is absent from
     *   `sheetRows` is deleted — EXCEPT unsynced local drafts (`pendingSync == true` / `!isSyncedToSheet`).
     */
    suspend fun reconcileInventoryTabWithSheet(
        tabName: String,
        sheetStatusOk: Boolean,
        sheetRows: List<ImportPreviewRow>
    ): TabReconciliationOutcome = withContext(Dispatchers.IO) {
        val canonicalTab = TlcInventoryTabV2.resolveTabNameFromString(tabName) ?: tabName.trim()
        if (!sheetStatusOk) {
            return@withContext TabReconciliationOutcome(
                tabName = canonicalTab,
                statusOk = false,
                sheetRowCount = sheetRows.size,
                removedLocalCount = 0,
                removedRoomCount = 0,
                preservedPendingCount = 0
            )
        }

        val sheetIds = linkedSetOf<String>()
        sheetRows.forEach { row ->
            listOf(
                row.recordId,
                row.propertyCode,
                row.unmappedColumns["Unit ID"],
                row.unmappedColumns["Property ID"],
                row.unmappedColumns["unit_id"],
                row.unmappedColumns["unitId"]
            ).forEach { candidate ->
                val norm = normalizeSyncIdentifier(candidate)
                if (norm.isNotBlank()) sheetIds.add(norm)
            }
        }

        val appContext = context
        val pendingIds = collectPendingDraftIds(appContext)
        val currentList = _properties.value
        val keptProps = ArrayList<PropertyItem>(currentList.size)
        var removedLocalCount = 0
        var preservedPendingCount = 0

        currentList.forEach { prop ->
            val propCanonicalTab = TlcInventoryTabV2.resolveTabName(prop)
            if (!propCanonicalTab.equals(canonicalTab, ignoreCase = true)) {
                keptProps.add(prop)
                return@forEach
            }
            val propKeys = listOf(prop.id, prop.recordId, prop.propertyCode)
                .map(::normalizeSyncIdentifier)
                .filter { it.isNotBlank() }
            val isPendingDraft = !prop.isSyncedToSheet || propKeys.any { it in pendingIds }
            if (isPendingDraft) {
                keptProps.add(prop)
                preservedPendingCount++
                return@forEach
            }
            val existsInSheet = propKeys.any { it in sheetIds }
            if (existsInSheet) {
                keptProps.add(prop)
            } else {
                removedLocalCount++
            }
        }

        if (removedLocalCount > 0) {
            _properties.value = keptProps
            com.example.inventory.PropertyPrecomputedCache.warmUp(keptProps)
            if (appContext != null) {
                PropertiesLocalDatabase.saveProperties(appContext, keptProps)
                refreshPendingSyncState(reconcileDisk = false)
            }
            _syncRevision.value++
        }

        var removedRoomCount = 0
        if (appContext != null) {
            runCatching {
                val dao = com.example.data.master.MasterProjectDatabase.getDatabase(appContext).masterProjectDao()
                val allUnits = dao.getAllUnitsSync()
                val staleUnitIds = allUnits.filter { unit ->
                    val unitCanonicalTab = TlcInventoryTabV2.resolveTabNameFromString(unit.tab)
                    if (unitCanonicalTab == null || !unitCanonicalTab.equals(canonicalTab, ignoreCase = true)) {
                        return@filter false
                    }
                    val unitKeys = listOf(unit.unitId, unit.propertyCode)
                        .map(::normalizeSyncIdentifier)
                        .filter { it.isNotBlank() }
                    val isPending = unitKeys.any { it in pendingIds }
                    val existsInSheet = unitKeys.any { it in sheetIds }
                    !isPending && !existsInSheet
                }.map { it.unitId }
                if (staleUnitIds.isNotEmpty()) {
                    dao.deleteUnitsByIds(staleUnitIds)
                    removedRoomCount = staleUnitIds.size
                }
            }
        }

        TabReconciliationOutcome(
            tabName = canonicalTab,
            statusOk = true,
            sheetRowCount = sheetRows.size,
            removedLocalCount = removedLocalCount,
            removedRoomCount = removedRoomCount,
            preservedPendingCount = preservedPendingCount
        )
    }

    suspend fun reconcileValidatedTabs(
        validatedTabs: Map<String, List<ImportPreviewRow>>
    ): List<TabReconciliationOutcome> {
        val outcomes = mutableListOf<TabReconciliationOutcome>()
        validatedTabs.forEach { (tabName, rows) ->
            outcomes.add(
                reconcileInventoryTabWithSheet(
                    tabName = tabName,
                    sheetStatusOk = true,
                    sheetRows = rows
                )
            )
        }
        return outcomes
    }

    private fun parseAppsScriptTabJson(root: JSONObject, tabName: String): List<ImportPreviewRow> {
        val arr = TlcSchemaV2Api.extractPrimaryArray(root, tabName, "rows", "records", "inventory", "items", "data")
            ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        val grid = convertJsonArrayToRows(arr)
        if (grid.size <= 1) return emptyList()
        return parseWorksheets(listOf(ParsedWorksheet(sheetName = tabName, rows = grid)))
    }

    /**
     * Authoritative Apps Script tab merger shared by [syncWithGoogleSheetNow], [OneTapSyncCoordinator],
     * and unit tests. Handles inventory tabs, Commercial/Residential CRM, Projects, Brokers, Vendors,
     * Team & Users, Call Logs, Daily Reports, and Approvals & Audit Log with safe deletion reconciliation.
     */
    internal suspend fun mergeTabRowsFromAppsScript(
        tabName: String,
        rows: List<List<String>>,
        sheetStatusOk: Boolean
    ): TabMergeOutcome {
        val canonicalInvTab = TlcInventoryTabV2.resolveTabNameFromString(tabName)
        if (canonicalInvTab != null) {
            val previewRows = if (rows.size > 1) {
                parseWorksheets(listOf(ParsedWorksheet(sheetName = canonicalInvTab, rows = rows)))
            } else emptyList()
            val stats = if (previewRows.isNotEmpty()) {
                importRowsDetailed(previewRows, duplicateHandling = "SYNC_MERGE")
            } else SyncStats(0, 0, 0, 0, 0)
            val recon = reconcileInventoryTabWithSheet(
                tabName = canonicalInvTab,
                sheetStatusOk = sheetStatusOk,
                sheetRows = previewRows
            )
            return TabMergeOutcome(
                addedCount = stats.addedCount,
                updatedCount = stats.updatedCount,
                removedCount = recon.removedLocalCount + recon.removedRoomCount,
                preservedPendingCount = recon.preservedPendingCount,
                totalProcessed = previewRows.size
            )
        }

        return when {
            tabName.equals(TlcSchemaV2Api.TAB_COMMERCIAL_CRM, ignoreCase = true) ||
                tabName.equals(TlcSchemaV2Api.TAB_RESIDENTIAL_CRM, ignoreCase = true) -> {
                val segment = if (tabName.contains("Residential", ignoreCase = true)) "Residential" else "Commercial"
                val merged = mergeCrmSheetRowsIntoPrefs(
                    rows = rows,
                    defaultSegment = segment,
                    reconcileStale = sheetStatusOk
                )
                TabMergeOutcome(addedCount = merged, totalProcessed = (rows.size - 1).coerceAtLeast(0))
            }

            tabName.equals(TlcSchemaV2Api.TAB_COMMERCIAL_PROJECTS, ignoreCase = true) ||
                tabName.equals(TlcSchemaV2Api.TAB_RESIDENTIAL_PROJECTS, ignoreCase = true) -> {
                val appCtx = context
                val merged = if (appCtx != null && (rows.size > 1 || sheetStatusOk)) {
                    ProjectDirectoryStore.mergeSyncedProjectRows(
                        context = appCtx,
                        division = tabName,
                        rows = rows,
                        reconcileStale = sheetStatusOk
                    ).also {
                        _syncRevision.value++
                    }
                } else 0
                TabMergeOutcome(addedCount = merged, totalProcessed = (rows.size - 1).coerceAtLeast(0))
            }

            tabName.equals(TlcSchemaV2Api.TAB_COMMERCIAL_BROKERS, ignoreCase = true) ||
                tabName.equals(TlcSchemaV2Api.TAB_RESIDENTIAL_BROKERS, ignoreCase = true) ||
                tabName.equals(TlcSchemaV2Api.TAB_VENDORS, ignoreCase = true) -> {
                val merged = mergeBrokersVendorsSheetRowsIntoCache(
                    rows = rows,
                    defaultRoleHint = tabName,
                    reconcileStale = sheetStatusOk
                )
                TabMergeOutcome(addedCount = merged, totalProcessed = (rows.size - 1).coerceAtLeast(0))
            }

            tabName.equals(TlcSchemaV2Api.TAB_TEAM_USERS, ignoreCase = true) -> {
                val merged = mergeEmployeesSheetRowsIntoPrefs(rows, reconcileStale = sheetStatusOk)
                TabMergeOutcome(addedCount = merged, totalProcessed = (rows.size - 1).coerceAtLeast(0))
            }

            tabName.equals(TlcSchemaV2Api.TAB_TENANTS, ignoreCase = true) -> {
                val merged = mergeTenantsSheetRowsIntoCache(rows, reconcileStale = sheetStatusOk)
                TabMergeOutcome(addedCount = merged, totalProcessed = (rows.size - 1).coerceAtLeast(0))
            }

            tabName.equals(TlcSchemaV2Api.TAB_CALL_LOGS, ignoreCase = true) -> {
                mergeCallLogsSheetRowsWithOutcome(rows, reconcileStale = sheetStatusOk)
            }

            tabName.equals(TlcSchemaV2Api.TAB_DAILY_REPORTS, ignoreCase = true) -> {
                mergeDailyReportRowsWithOutcome(rows, reconcileStale = sheetStatusOk)
            }

            tabName.equals(TlcSchemaV2Api.TAB_APPROVALS_AUDIT_LOG, ignoreCase = true) -> {
                reconcileAuditAndApprovalsFromSheet(rows, reconcileStale = sheetStatusOk)
            }

            else -> TabMergeOutcome()
        }
    }

    suspend fun syncWithGoogleSheetNow(): String = withContext(Dispatchers.IO) {
        val rawUrl = _sheetsScriptUrl.value.trim()
        _isSyncingNow.value = true
        refreshTopSyncChipState(syncing = true)

        try {
            lastNonInventorySyncedCount = 0
            _connectionStatus.value = "Syncing with TLC Master sheet..."
            val allImportRows = mutableListOf<ImportPreviewRow>()
            val validatedTabRows = linkedMapOf<String, List<ImportPreviewRow>>()
            var discoveredSpreadsheetName: String? = null
            var discoveredSpreadsheetId: String? = null
            val endpoint = TlcSchemaV2Api.resolveEndpointUrl(rawUrl)
            val resolvedEndpointForDiagnostics = endpoint
            var firstCriticalServerError: String? = null

            val appCtx = context
            val currentApiRole = appCtx?.let { UserSettingsManager.readCurrentApiRole(it) } ?: "RM"
            val currentFullName = appCtx?.let { UserSettingsManager.readCurrentFullName(it) }.orEmpty()
            val rmFilter = if (currentApiRole.equals("Admin", ignoreCase = true)) null else currentFullName.takeIf { it.isNotBlank() }

            // 1. Bootstrap (dropdowns + team directory) via Apps Script /exec
            val bootstrapResult = TlcSchemaV2Api.fetchBootstrap(endpoint)
            bootstrapResult.onSuccess { payload ->
                if (appCtx != null) saveCachedBootstrapPayload(appCtx, payload)
                lastNonInventorySyncedCount += payload.team.size
            }.onFailure { err ->
                val msg = err.message.orEmpty()
                if (msg.contains("TLC_API_KEY", ignoreCase = true) ||
                    msg.contains("401", ignoreCase = true) ||
                    msg.contains("403", ignoreCase = true)
                ) {
                    firstCriticalServerError = msg
                }
            }

            // 2a. Query each of the 5 canonical inventory tabs directly via GET ?tabName=<encodedTabName>
            for (tabName in TlcInventoryTabV2.ALL_FIVE_TABS) {
                val tabRes = TlcSchemaV2Api.fetchTabRaw(
                    baseUrl = endpoint,
                    tabName = tabName,
                    role = currentApiRole
                )
                val tabPayload = tabRes.getOrNull()
                if (tabPayload != null && tabPayload.isStatusOk) {
                    if (discoveredSpreadsheetName == null && !tabPayload.spreadsheetName.isNullOrBlank()) {
                        discoveredSpreadsheetName = tabPayload.spreadsheetName
                    }
                    if (discoveredSpreadsheetId == null && !tabPayload.spreadsheetId.isNullOrBlank()) {
                        discoveredSpreadsheetId = tabPayload.spreadsheetId
                    }
                    val parsedTabRows = parseAppsScriptTabJson(tabPayload.rawJson, tabName)
                    validatedTabRows[tabName] = parsedTabRows
                    allImportRows.addAll(parsedTabRows)
                } else {
                    val errMsg = tabRes.exceptionOrNull()?.message.orEmpty()
                    if (errMsg.contains("401", ignoreCase = true) ||
                        errMsg.contains("403", ignoreCase = true) ||
                        errMsg.contains("TLC_API_KEY", ignoreCase = true)
                    ) {
                        firstCriticalServerError = errMsg
                    }
                }
            }

            // 2b. Fallback to unified ?action=inventory if any of the 5 tabs were not validated via ?tabName=...
            if (validatedTabRows.size < TlcInventoryTabV2.ALL_FIVE_TABS.size && firstCriticalServerError == null) {
                val inventoryRes = TlcSchemaV2Api.fetchInventoryRaw(
                    baseUrl = endpoint,
                    role = currentApiRole
                )
                val inventoryJson = inventoryRes.getOrNull()
                if (inventoryJson != null && TlcSchemaV2Api.isValidOkStatus(inventoryJson)) {
                    val (metaName, metaId) = TlcSchemaV2Api.extractSpreadsheetMetadata(inventoryJson)
                    if (discoveredSpreadsheetName == null && !metaName.isNullOrBlank()) {
                        discoveredSpreadsheetName = metaName
                    }
                    if (discoveredSpreadsheetId == null && !metaId.isNullOrBlank()) {
                        discoveredSpreadsheetId = metaId
                    }
                    val parsedAllRows = parseAppsScriptJsonData(inventoryJson.toString())
                    val groupedByTab = parsedAllRows.groupBy { TlcInventoryTabV2.resolveTabName(it.tab) }
                    TlcInventoryTabV2.ALL_FIVE_TABS.forEach { tabName ->
                        if (!validatedTabRows.containsKey(tabName)) {
                            val tabRows = groupedByTab[tabName].orEmpty()
                            validatedTabRows[tabName] = tabRows
                            allImportRows.addAll(tabRows)
                        }
                    }
                } else if (validatedTabRows.isEmpty()) {
                    inventoryRes.exceptionOrNull()?.message?.let { firstCriticalServerError = it }
                }
            }

            if (firstCriticalServerError != null && validatedTabRows.isEmpty()) {
                throw java.io.IOException(firstCriticalServerError)
            }

            // 3. Sync exact non-inventory canonical & operational tabs via Apps Script /exec GET ?tabName=<encodedTabName>
            val nonInventoryTabs = listOf(
                TlcSchemaV2Api.TAB_COMMERCIAL_CRM,
                TlcSchemaV2Api.TAB_RESIDENTIAL_CRM,
                TlcSchemaV2Api.TAB_COMMERCIAL_PROJECTS,
                TlcSchemaV2Api.TAB_RESIDENTIAL_PROJECTS,
                TlcSchemaV2Api.TAB_COMMERCIAL_BROKERS,
                TlcSchemaV2Api.TAB_RESIDENTIAL_BROKERS,
                TlcSchemaV2Api.TAB_VENDORS,
                TlcSchemaV2Api.TAB_TEAM_USERS,
                TlcSchemaV2Api.TAB_TENANTS,
                TlcSchemaV2Api.TAB_CALL_LOGS,
                TlcSchemaV2Api.TAB_DAILY_REPORTS,
                TlcSchemaV2Api.TAB_APPROVALS_AUDIT_LOG
            )
            var crmTabsSynced = false
            for (tabName in nonInventoryTabs) {
                runCatching {
                    val tabRes = TlcSchemaV2Api.fetchTabRaw(
                        baseUrl = endpoint,
                        tabName = tabName,
                        role = currentApiRole
                    )
                    val payload = tabRes.getOrNull()
                    if (payload != null && payload.isStatusOk) {
                        val arr = TlcSchemaV2Api.extractPrimaryArray(
                            payload.rawJson,
                            tabName,
                            "data",
                            "rows",
                            "records",
                            "leads",
                            "team",
                            "calls"
                        )
                        val rows = if (arr != null && arr.length() > 0) convertJsonArrayToRows(arr) else emptyList()
                        val outcome = mergeTabRowsFromAppsScript(
                            tabName = tabName,
                            rows = rows,
                            sheetStatusOk = true
                        )
                        lastNonInventorySyncedCount += outcome.totalProcessed + outcome.addedCount + outcome.updatedCount
                        if (tabName == TlcSchemaV2Api.TAB_COMMERCIAL_CRM || tabName == TlcSchemaV2Api.TAB_RESIDENTIAL_CRM) {
                            crmTabsSynced = true
                        }
                    }
                }
            }

            // 3b. Fallback for CRM leads via ?action=leads if ?tabName=Commercial CRM / Residential CRM wasn't served
            if (!crmTabsSynced) {
                for (vertical in listOf("commercial", "residential")) {
                    runCatching {
                        val leadsJson = TlcSchemaV2Api.fetchLeadsRaw(
                            baseUrl = endpoint,
                            vertical = vertical,
                            assignedRm = rmFilter
                        ).getOrNull()
                        if (leadsJson != null) {
                            val arr = TlcSchemaV2Api.extractPrimaryArray(leadsJson, "leads", "Commercial CRM", "Residential CRM", "data", "records")
                            if (arr != null && arr.length() > 0) {
                                val rows = convertJsonArrayToRows(arr)
                                if (rows.size > 1) {
                                    val defaultSegment = if (vertical == "residential") "Residential" else "Commercial"
                                    lastNonInventorySyncedCount += mergeCrmSheetRowsIntoPrefs(
                                        rows = rows,
                                        defaultSegment = defaultSegment,
                                        reconcileStale = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Site Visits
            runCatching {
                val visits = TlcSchemaV2Api.fetchVisits(
                    baseUrl = endpoint,
                    assignedRm = rmFilter
                ).getOrNull()
                if (visits != null) {
                    _siteVisits.value = visits
                    lastNonInventorySyncedCount += visits.size
                }
            }

            // 5. Live Dashboard KPIs
            runCatching {
                val kpis = TlcSchemaV2Api.fetchDashboard(endpoint).getOrNull()
                if (kpis != null) {
                    _dashboardKpis.value = kpis
                }
            }

            // Reconcile validated inventory tabs ALWAYS — including 0-row cleared tabs — after merging any sheet rows
            val importStats = if (allImportRows.isNotEmpty()) {
                importRowsDetailed(allImportRows, duplicateHandling = "SYNC_MERGE")
            } else {
                SyncStats(0, 0, 0, 0, 0)
            }

            val reconciliationOutcomes = if (validatedTabRows.isNotEmpty()) {
                reconcileValidatedTabs(validatedTabRows)
            } else {
                emptyList()
            }
            val totalRemovedStale = reconciliationOutcomes.sumOf { it.removedLocalCount }
            val totalRemovedRoom = reconciliationOutcomes.sumOf { it.removedRoomCount }
            val totalPreservedPending = reconciliationOutcomes.sumOf { it.preservedPendingCount }

            val nowMs = System.currentTimeMillis()
            val nowStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(nowMs))
            val tabRowCounts = linkedMapOf<String, Int>().apply {
                TlcInventoryTabV2.ALL_FIVE_TABS.forEach { tab ->
                    put(tab, validatedTabRows[tab]?.size ?: 0)
                }
            }
            val resolvedSheetName = discoveredSpreadsheetName ?: TlcSchemaV2Api.EXPECTED_SPREADSHEET_NAME
            val resolvedSheetId = discoveredSpreadsheetId ?: TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID

            if (allImportRows.isNotEmpty() || validatedTabRows.isNotEmpty()) {
                if (importStats.failedCount == 0) {
                    markSuccessfulSyncNow(nowMs)
                }
                context?.let { ctx ->
                    runCatching {
                        com.example.notifications.TeamSyncDiffEngine.processPostSyncDiff(
                            context = ctx,
                            properties = _properties.value
                        )
                    }
                }
                val isCleanSuccess = importStats.failedCount == 0
                _isLiveConnected.value = isCleanSuccess
                _connectionStatus.value = if (isCleanSuccess) {
                    "$resolvedSheetName synced (${_properties.value.size} active, $totalRemovedStale stale removed)"
                } else {
                    "Sheet sync incomplete: ${importStats.failedCount} records failed"
                }
                updateSyncDiagnostics(
                    configuredScriptUrl = resolvedEndpointForDiagnostics,
                    activeSpreadsheetName = resolvedSheetName,
                    activeSpreadsheetId = resolvedSheetId,
                    tabRowCounts = tabRowCounts,
                    lastRemovedStaleCount = totalRemovedStale + totalRemovedRoom,
                    lastPreservedPendingCount = totalPreservedPending,
                    lastSyncedAt = nowStr,
                    lastStatusSummary = if (isCleanSuccess) {
                        "OK (${allImportRows.size} sheet rows, $totalRemovedStale stale removed)"
                    } else {
                        "Partial (${importStats.failedCount} failed)"
                    }
                )
                logSyncEvent(
                    direction = "SHEET_TO_APP",
                    status = if (isCleanSuccess) "SUCCESS" else "WARNING",
                    recordCount = allImportRows.size,
                    details = "$resolvedSheetName sync: ${importStats.addedCount} added, ${importStats.updatedCount} updated, $totalRemovedStale stale removed ($totalRemovedRoom Room), $totalPreservedPending pending preserved",
                    triggeredBy = "Google Sheet Sync"
                )
                return@withContext (if (isCleanSuccess) "$resolvedSheetName Sync Complete!\n\n" else "$resolvedSheetName Sync Incomplete — ${importStats.failedCount} records failed.\n\n") +
                    "• Source Records Found: ${importStats.recordsFound}\n" +
                    "• New Properties Added: ${importStats.addedCount}\n" +
                    "• Existing Properties Updated: ${importStats.updatedCount}\n" +
                    "• Stale / Removed Sheet Rows Cleared: $totalRemovedStale" + (if (totalRemovedRoom > 0) " (+$totalRemovedRoom Room)" else "") + "\n" +
                    "• Pending Unsaved Drafts Preserved: $totalPreservedPending\n" +
                    "• Total Active Inventory: ${_properties.value.size} Properties\n" +
                    "• Last Successful Sync: $nowStr"
            } else {
                if (lastNonInventorySyncedCount > 0) {
                    markSuccessfulSyncNow(nowMs)
                }
                _isLiveConnected.value = lastNonInventorySyncedCount > 0
                _connectionStatus.value = "Sheet sync unverified (no valid inventory tab responses)"
                updateSyncDiagnostics(
                    configuredScriptUrl = resolvedEndpointForDiagnostics,
                    activeSpreadsheetName = resolvedSheetName,
                    activeSpreadsheetId = resolvedSheetId,
                    lastSyncedAt = nowStr,
                    lastStatusSummary = "No validated inventory tab response"
                )
                logSyncEvent(
                    direction = "SHEET_TO_APP",
                    status = "WARNING",
                    recordCount = 0,
                    details = "Connected to sheet but no validated inventory tab responses returned",
                    triggeredBy = "Google Sheet Sync"
                )
                return@withContext "Connected to Google Sheet, but no validated inventory tab responses were returned.\n\n" +
                    "Local records were preserved safely. Check the Apps Script endpoint and tab names."
            }
        } catch (e: Exception) {
            val err = e.localizedMessage ?: "Network Error"
            _isLiveConnected.value = false
            _connectionStatus.value = "Sync Error: $err"
            logSyncEvent(
                direction = "SHEET_TO_APP",
                status = "FAILED",
                recordCount = 0,
                details = "Sync failed: $err",
                triggeredBy = "Google Sheet Sync"
            )
            return@withContext "Failed to sync Google Sheet: $err"
        } finally {
            _isSyncingNow.value = false
            refreshPendingSyncState(reconcileDisk = false)
        }
    }

    private fun extractSpreadsheetId(url: String): String {
        if (url.contains("/d/")) {
            return url.substringAfter("/d/").substringBefore("/")
        }
        if (!url.contains("http") && url.length in 20..60) {
            return url
        }
        return ""
    }

    private fun fetchUrlText(urlString: String): String {
        var currentUrl = java.net.URL(urlString)
        var conn = currentUrl.openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")

        var status = conn.responseCode
        var redirectCount = 0
        while ((status == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                status == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                status == 307 || status == 308) && redirectCount < 5) {
            val newUrl = conn.getHeaderField("Location") ?: break
            currentUrl = java.net.URL(newUrl)
            conn = currentUrl.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            status = conn.responseCode
            redirectCount++
        }

        if (status in 200..299) {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw java.io.IOException("HTTP error code $status")
        }
    }

    private fun parseAppsScriptJsonData(jsonString: String): List<ImportPreviewRow> {
        val trimmed = jsonString.trim()
        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            throw java.io.IOException("Received HTML response instead of JSON. Verify the Apps Script endpoint and authenticated access; do not enable anonymous access for private data.")
        }

        val results = mutableListOf<ImportPreviewRow>()
        try {
            if (trimmed.startsWith("[")) {
                val rootArray = org.json.JSONArray(trimmed)
                val parsedRows = convertJsonArrayToRows(rootArray)
                if (parsedRows.isNotEmpty()) {
                    val worksheet = ParsedWorksheet(sheetName = "Master Inventory", rows = parsedRows)
                    results.addAll(parseWorksheets(listOf(worksheet)))
                }
            } else if (trimmed.startsWith("{")) {
                val root = org.json.JSONObject(trimmed)
                // Parse optional appUpdate metadata from TlcWebApp.gs doGet response
                AppUpdateManager.parseAppUpdateFromJson(trimmed)?.let { updateInfo ->
                    if (AppUpdateManager.isRemoteVersionNewer(updateInfo.version)) {
                        _availableAppUpdate.value = updateInfo
                    }
                }
                val dataObj = root.optJSONObject("data")
                    ?: root.optJSONObject("tabs")
                    ?: root.optJSONObject("properties")
                    ?: root

                // Helper to group flat inventory arrays by their per-item Tab/Sheet column when present
                fun parseInventoryJsonArray(defaultTabName: String, arr: org.json.JSONArray) {
                    val tabKeys = listOf("Tab", "tab", "Source Tab", "Inventory Tab", "Sheet", "sheet", "_tab", "source_tab")
                    val grouped = linkedMapOf<String, MutableList<org.json.JSONObject>>()
                    var hasObjectItems = false
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        hasObjectItems = true
                        val itemTab = tabKeys.firstNotNullOfOrNull { k ->
                            obj.optString(k, "").trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        } ?: defaultTabName
                        grouped.getOrPut(itemTab) { mutableListOf() }.add(obj)
                    }
                    if (hasObjectItems && grouped.isNotEmpty()) {
                        grouped.forEach { (tabName, objs) ->
                            val subArr = org.json.JSONArray()
                            objs.forEach { subArr.put(it) }
                            val rows = convertJsonArrayToRows(subArr)
                            if (rows.isNotEmpty()) {
                                results.addAll(parseWorksheets(listOf(ParsedWorksheet(sheetName = tabName, rows = rows))))
                            }
                        }
                    } else {
                        val parsedRows = convertJsonArrayToRows(arr)
                        if (parsedRows.isNotEmpty()) {
                            results.addAll(parseWorksheets(listOf(ParsedWorksheet(sheetName = defaultTabName, rows = parsedRows))))
                        }
                    }
                }

                val keys = dataObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val sheetArray = dataObj.optJSONArray(key)
                    if (sheetArray != null) {
                        val firstObj = sheetArray.optJSONObject(0)
                        val looksLikeLeadArray = key.equals(TlcSchemaV2Api.TAB_COMMERCIAL_CRM, ignoreCase = true) ||
                            key.equals(TlcSchemaV2Api.TAB_RESIDENTIAL_CRM, ignoreCase = true) ||
                            (key.equals("data", ignoreCase = true) && firstObj != null && (firstObj.has("Lead ID") || firstObj.has("lead_id")))
                        if (looksLikeLeadArray) {
                            val parsedRows = convertJsonArrayToRows(sheetArray)
                            if (parsedRows.isNotEmpty()) {
                                val seg = if (key.contains("Residential", ignoreCase = true)) "Residential" else "Commercial"
                                lastNonInventorySyncedCount += mergeCrmSheetRowsIntoPrefs(parsedRows, defaultSegment = seg, reconcileStale = true)
                            }
                        } else if (key.equals(TlcSchemaV2Api.TAB_TEAM_USERS, ignoreCase = true)) {
                            val parsedRows = convertJsonArrayToRows(sheetArray)
                            lastNonInventorySyncedCount += mergeEmployeesSheetRowsIntoPrefs(parsedRows, reconcileStale = true)
                        } else if (key.equals(TlcSchemaV2Api.TAB_CALL_LOGS, ignoreCase = true)) {
                            val parsedRows = convertJsonArrayToRows(sheetArray)
                            lastNonInventorySyncedCount += mergeCallLogsSheetRowsIntoPrefs(parsedRows, reconcileStale = true)
                        } else if (key.equals(TlcSchemaV2Api.TAB_COMMERCIAL_PROJECTS, ignoreCase = true) ||
                            key.equals(TlcSchemaV2Api.TAB_RESIDENTIAL_PROJECTS, ignoreCase = true)
                        ) {
                            val parsedRows = convertJsonArrayToRows(sheetArray)
                            if (context != null) {
                                lastNonInventorySyncedCount += ProjectDirectoryStore.mergeSyncedProjectRows(context, key, parsedRows, reconcileStale = true)
                                _syncRevision.value++
                            }
                        } else if (key.equals(TlcSchemaV2Api.TAB_DAILY_REPORTS, ignoreCase = true)) {
                            val parsedRows = convertJsonArrayToRows(sheetArray)
                            lastNonInventorySyncedCount += mergeDailyReportRowsIntoCache(parsedRows, reconcileStale = true)
                        } else if (key.equals(TlcSchemaV2Api.TAB_APPROVALS_AUDIT_LOG, ignoreCase = true)) {
                            val parsedRows = convertJsonArrayToRows(sheetArray)
                            lastNonInventorySyncedCount += kotlinx.coroutines.runBlocking {
                                reconcileAuditAndApprovalsFromSheet(parsedRows, reconcileStale = true).totalProcessed
                            }
                        } else if (key.equals(TlcSchemaV2Api.TAB_TENANTS, ignoreCase = true)) {
                            val parsedRows = convertJsonArrayToRows(sheetArray)
                            lastNonInventorySyncedCount += mergeTenantsSheetRowsIntoCache(parsedRows, reconcileStale = true)
                        } else if (key.equals(TlcSchemaV2Api.TAB_COMMERCIAL_BROKERS, ignoreCase = true) ||
                            key.equals(TlcSchemaV2Api.TAB_RESIDENTIAL_BROKERS, ignoreCase = true) ||
                            key.equals(TlcSchemaV2Api.TAB_VENDORS, ignoreCase = true)
                        ) {
                            val parsedRows = convertJsonArrayToRows(sheetArray)
                            lastNonInventorySyncedCount += mergeBrokersVendorsSheetRowsIntoCache(parsedRows, defaultRoleHint = key, reconcileStale = true)
                        } else if (key.contains("Projects", ignoreCase = true) ||
                            key.equals("SETTINGS", ignoreCase = true) ||
                            key.equals("SYNC_LOG", ignoreCase = true) ||
                            key.equals("DATA_REVIEW", ignoreCase = true) ||
                            key.equals("visits", ignoreCase = true) ||
                            key.equals("locations", ignoreCase = true) ||
                            key.equals("puneZones", ignoreCase = true)
                        ) {
                            // Skip non-inventory metadata/project/system tabs when parsing Property Inventory
                        } else {
                            parseInventoryJsonArray(key, sheetArray)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SheetsRepository", "Error parsing Apps Script JSON: ${e.message}", e)
        }
        return results
    }

    private fun convertJsonArrayToRows(jsonArray: org.json.JSONArray): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        if (jsonArray.length() == 0) return rows

        val firstItem = jsonArray.opt(0)
        if (firstItem is org.json.JSONArray) {
            for (i in 0 until jsonArray.length()) {
                val arr = jsonArray.optJSONArray(i) ?: continue
                val rowList = mutableListOf<String>()
                for (j in 0 until arr.length()) {
                    rowList.add(arr.optString(j, ""))
                }
                rows.add(rowList)
            }
        } else if (firstItem is org.json.JSONObject) {
            val headerSet = linkedSetOf<String>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val keys = obj.keys()
                while (keys.hasNext()) {
                    headerSet.add(keys.next())
                }
            }
            val headers = headerSet.toList()
            rows.add(headers) // Header row

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val rowList = mutableListOf<String>()
                headers.forEach { h ->
                    rowList.add(obj.optString(h, ""))
                }
                rows.add(rowList)
            }
        }
        return rows
    }

    // Favorites & Compare
    fun toggleFavorite(propertyId: String) {
        val cur = _favoriteIds.value.toMutableSet()
        if (cur.contains(propertyId)) cur.remove(propertyId) else cur.add(propertyId)
        _favoriteIds.value = cur
        context?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putStringSet("favorite_property_ids", cur.toSet())?.apply()
    }

    private fun comparisonEmployeeKey(activeUser: UserProfile): String =
        activeUser.username.trim().ifBlank { "default_staff" }

    private fun loadSavedComparisonIds(employeeKey: String): List<String> {
        val appContext = context ?: return inMemoryComparisonByKey[employeeKey].orEmpty()
        val raw = appContext.getSharedPreferences(COMPARISON_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(employeeKey, null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "").trim()
                    if (item.isNotBlank() && !contains(item)) add(item)
                }
            }.take(MAX_COMPARISON_COUNT)
        }.getOrDefault(emptyList())
    }

    private fun saveComparisonIds(employeeKey: String, ids: List<String>) {
        val sanitized = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_COMPARISON_COUNT)
        inMemoryComparisonByKey[employeeKey] = sanitized
        context?.getSharedPreferences(COMPARISON_PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(employeeKey, org.json.JSONArray(sanitized).toString())
            ?.apply()
        activeComparisonKey = employeeKey
        _comparisonIds.value = sanitized
    }

    fun loadComparisonForUser(activeUser: UserProfile) {
        val key = comparisonEmployeeKey(activeUser)
        activeComparisonKey = key
        _comparisonIds.value = loadSavedComparisonIds(key)
    }

    fun toggleCompare(propertyId: String, activeUser: UserProfile): Pair<Boolean, String> {
        val cleanId = propertyId.trim()
        if (cleanId.isBlank()) return Pair(false, "Property not found.")
        val key = comparisonEmployeeKey(activeUser)
        val cur = loadSavedComparisonIds(key).toMutableList()

        if (cur.contains(cleanId)) {
            cur.remove(cleanId)
            saveComparisonIds(key, cur)
            return Pair(true, "Removed from comparison.")
        }

        val target = _properties.value.firstOrNull { it.id == cleanId }
        val isTombstoned = target == null ||
            (target.archiveStatus == ArchiveStatus.ARCHIVED && target.status == AvailabilityStatus.WITHDRAWN)
        if (isTombstoned || target == null) {
            return Pair(false, "Property is no longer available.")
        }

        if (!isWithinBusinessDivisionScope(target, activeUser)) {
            return Pair(false, "Access restricted for your current division scope.")
        }

        if (cur.size >= MAX_COMPARISON_COUNT) {
            return Pair(false, "Compare up to 3 properties. Remove one to add another.")
        }

        cur.add(cleanId)
        saveComparisonIds(key, cur)
        return Pair(true, "Added to comparison (${cur.size}/$MAX_COMPARISON_COUNT).")
    }

    fun toggleCompare(propertyId: String): Boolean {
        val activeUser = UserSettingsManager(context).userProfile.value
        return toggleCompare(propertyId, activeUser).first
    }

    fun clearComparison(activeUser: UserProfile? = null) {
        val key = activeUser?.let { comparisonEmployeeKey(it) } ?: activeComparisonKey
        saveComparisonIds(key, emptyList())
    }

    fun resolveAccessibleComparisonProperties(activeUser: UserProfile): Pair<List<PropertyItem>, String?> {
        val key = comparisonEmployeeKey(activeUser)
        val savedIds = loadSavedComparisonIds(key)
        if (savedIds.isEmpty()) {
            activeComparisonKey = key
            if (_comparisonIds.value.isNotEmpty()) {
                _comparisonIds.value = emptyList()
            }
            return Pair(emptyList(), null)
        }

        val byId = _properties.value.associateBy { it.id }
        val resolved = mutableListOf<PropertyItem>()
        val retainedIds = mutableListOf<String>()
        var droppedAny = false

        savedIds.forEach { id ->
            val prop = byId[id]
            val isDeleted = prop == null ||
                (prop.archiveStatus == ArchiveStatus.ARCHIVED && prop.status == AvailabilityStatus.WITHDRAWN)
            if (!isDeleted && prop != null && isWithinBusinessDivisionScope(prop, activeUser)) {
                resolved.add(prop)
                retainedIds.add(id)
            } else {
                droppedAny = true
            }
        }

        if (droppedAny || retainedIds != savedIds) {
            saveComparisonIds(key, retainedIds)
        } else {
            activeComparisonKey = key
            if (_comparisonIds.value != retainedIds) {
                _comparisonIds.value = retainedIds
            }
        }

        val notice = if (droppedAny) {
            "Removed unavailable or restricted property from comparison."
        } else null
        return Pair(resolved, notice)
    }

    fun markViewed(propertyId: String) {
        val now = System.currentTimeMillis()
        _properties.value = _properties.value.map {
            if (it.id == propertyId) it.copy(lastViewedTimestamp = now) else it
        }
    }

    // Delete Single Property
    @Synchronized
    fun deleteProperty(propertyId: String): Boolean {
        if (!allowed("DELETE_RECORD")) return false
        val target = _properties.value.firstOrNull { it.id == propertyId } ?: return false
        // Retain an auditable tombstone instead of silently erasing history.
        return updateProperty(target.copy(status = AvailabilityStatus.WITHDRAWN, archiveStatus = ArchiveStatus.ARCHIVED), "Deleted / archived")
    }

    @Synchronized
    fun deleteAllProperties(): Boolean {
        if (!allowed("DELETE_RECORD") || !allowed("BULK_EDIT")) return false
        _properties.value = _properties.value.map { stamped(it.copy(status = AvailabilityStatus.WITHDRAWN, archiveStatus = ArchiveStatus.ARCHIVED, isSyncedToSheet = false)) }
        return persistChanges()
    }

    /**
     * Batch 30: Permanently removes a Property from in-memory state + context.filesDir cache
     * immediately, and sends a background DELETE doPost request to TlcWebApp.gs.
     */
    @Synchronized
    fun permanentlyDeleteProperty(property: PropertyItem): Boolean {
        val appContext = context
        val isAdmin = appContext?.let { UserSettingsManager(it).userProfile.value.isAdminOrSuperAdmin } ?: allowed("DELETE_RECORD")
        if (!isAdmin) return false

        val targetIds = setOf(property.id, property.recordId, property.propertyCode)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.uppercase(Locale.ENGLISH) }
            .toSet()

        val remaining = _properties.value.filterNot { item ->
            item.id.trim().uppercase(Locale.ENGLISH) in targetIds ||
                (property.id.isNotBlank() && item.id == property.id)
        }
        val saved = if (appContext != null) {
            PropertiesLocalDatabase.saveProperties(appContext, remaining).also { ok ->
                if (ok) {
                    runCatching { OfflineDiskCache.saveInventorySplits(appContext, remaining) }
                    runCatching { PendingSyncOpStore.removeForEntity(appContext, property.id) }
                }
            }
        } else {
            true
        }
        if (!saved) return false

        _properties.value = remaining
        if (_favoriteIds.value.contains(property.id)) {
            val nextFavs = _favoriteIds.value - property.id
            _favoriteIds.value = nextFavs
            appContext?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)?.edit()
                ?.putStringSet("favorite_property_ids", nextFavs)?.apply()
        }
        if (_comparisonIds.value.contains(property.id)) {
            saveComparisonIds(activeComparisonKey, _comparisonIds.value.filterNot { it == property.id })
        }
        refreshPendingSyncState(reconcileDisk = false)
        refreshPendingSyncOps()

        val tabName = if (property.isResidential()) "Residential Inventory" else "Commercial Inventory"
        val keyValue = property.recordId.ifBlank { property.id.ifBlank { property.propertyCode } }
        if (keyValue.isNotBlank()) {
            repoScope.launch(Dispatchers.IO) {
                deleteRecordPermanently(
                    tabName = tabName,
                    keyHeader = "Property ID",
                    keyValue = keyValue
                )
            }
        }
        return true
    }

    /**
     * Batch 30: Permanently removes a Project (UnderConstProject) from in-memory state +
     * context.filesDir cache immediately, and sends a background DELETE doPost request to TlcWebApp.gs.
     */
    @Synchronized
    fun permanentlyDeleteUnderConstProject(project: UnderConstProject): Boolean {
        val appContext = context
        val isAdmin = appContext?.let { UserSettingsManager(it).userProfile.value.isAdminOrSuperAdmin } ?: allowed("DELETE_RECORD")
        if (!isAdmin) return false

        val code = project.projectCode.ifBlank { project.id }.trim()
        val matchedDirDivision = appContext?.let { ctx ->
            runCatching {
                ProjectDirectoryStore.read(ctx).firstOrNull {
                    it.id.equals(code, ignoreCase = true) ||
                        it.id.equals(project.id, ignoreCase = true) ||
                        it.name.equals(project.projectName, ignoreCase = true)
                }?.division
            }.getOrNull()
        }
        val tabName = when {
            matchedDirDivision?.equals("commercial", ignoreCase = true) == true -> "Commercial Projects"
            matchedDirDivision?.equals("residential", ignoreCase = true) == true -> "Residential Projects"
            code.startsWith("CP", ignoreCase = true) -> "Commercial Projects"
            else -> "Residential Projects"
        }

        _underConstProjects.value = _underConstProjects.value.filterNot {
            it.id == project.id || (code.isNotBlank() && it.projectCode.equals(code, ignoreCase = true))
        }
        if (appContext != null && code.isNotBlank()) {
            runCatching { ProjectDirectoryStore.deletePermanently(appContext, code) }
            if (project.id.isNotBlank() && !project.id.equals(code, ignoreCase = true)) {
                runCatching { ProjectDirectoryStore.deletePermanently(appContext, project.id) }
            }
            _syncRevision.value++
        }

        if (code.isNotBlank()) {
            repoScope.launch(Dispatchers.IO) {
                deleteRecordPermanently(
                    tabName = tabName,
                    keyHeader = "Project Code",
                    keyValue = code
                )
            }
        }
        return true
    }

    /**
     * Batch 30: Permanently removes a DirectoryProject (Commercial or Residential) from
     * in-memory state + context.filesDir cache immediately, and sends a background DELETE doPost request.
     */
    @Synchronized
    fun permanentlyDeleteDirectoryProject(project: com.example.data.projects.DirectoryProject): Boolean {
        val appContext = context
        val isAdmin = appContext?.let { UserSettingsManager(it).userProfile.value.isAdminOrSuperAdmin } ?: allowed("DELETE_RECORD")
        if (!isAdmin) return false

        val code = project.id.trim()
        if (appContext != null && code.isNotBlank()) {
            runCatching { ProjectDirectoryStore.deletePermanently(appContext, code) }
        }
        _underConstProjects.value = _underConstProjects.value.filterNot {
            it.id.equals(code, ignoreCase = true) ||
                it.projectCode.equals(code, ignoreCase = true) ||
                it.projectName.equals(project.name, ignoreCase = true)
        }
        _syncRevision.value++

        val tabName = if (project.division.equals("residential", ignoreCase = true)) {
            "Residential Projects"
        } else {
            "Commercial Projects"
        }
        if (code.isNotBlank()) {
            repoScope.launch(Dispatchers.IO) {
                deleteRecordPermanently(
                    tabName = tabName,
                    keyHeader = "Project Code",
                    keyValue = code
                )
            }
        }
        return true
    }

    /**
     * Batch 30: Permanently removes a CRM Lead from local disk cache + prefs immediately,
     * and sends a background DELETE doPost request to TlcWebApp.gs.
     */
    fun permanentlyDeleteCrmLead(leadId: String): Boolean {
        val appContext = context ?: return false
        val isAdmin = UserSettingsManager(appContext).userProfile.value.isAdminOrSuperAdmin
        if (!isAdmin) return false
        val cleanId = leadId.trim()
        if (cleanId.isBlank()) return false

        val allLeads = com.example.ui.screens.readCrmLeads(appContext)
        val targetLead = allLeads.firstOrNull { it.id.equals(cleanId, ignoreCase = true) }
        val targetCrmTab = if (targetLead?.segment?.equals("Residential", ignoreCase = true) == true) {
            "Residential CRM"
        } else {
            "Commercial CRM"
        }
        val remaining = allLeads.filterNot {
            it.id.equals(cleanId, ignoreCase = true)
        }
        com.example.ui.screens.saveCrmLeads(appContext, remaining)
        _syncRevision.value++

        repoScope.launch(Dispatchers.IO) {
            deleteRecordPermanently(
                tabName = targetCrmTab,
                keyHeader = "Lead ID",
                keyValue = cleanId
            )
        }
        return true
    }

    // Add Property
    @Synchronized
    fun addProperty(newItem: PropertyItem): Boolean {
        if (_properties.value.any { it.id == newItem.id }) return false
        if (!com.example.inventory.InventoryQuality.validDates(newItem)) return false
        if (newItem.propertyCode.isNotBlank() && _properties.value.any { it.propertyCode.equals(newItem.propertyCode, ignoreCase = true) && it.id != newItem.id }) {
            return false
        }
        val staffActor = actor()
        val preVerifiedLive = newItem.verificationStatus == VerificationStatus.VERIFIED
        val todayIso = java.time.LocalDate.now().toString()
        val todayReadable = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())
        val prepared = newItem.copy(
            updatedBy = staffActor,
            verifiedBy = if (preVerifiedLive) (newItem.verifiedBy?.takeIf { it.isNotBlank() } ?: staffActor) else newItem.verifiedBy,
            verificationStatus = if (preVerifiedLive) VerificationStatus.VERIFIED else VerificationStatus.PENDING,
            archiveStatus = ArchiveStatus.ACTIVE,
            lastVerifiedDateClean = if (preVerifiedLive) newItem.lastVerifiedDateClean.ifBlank { todayIso } else newItem.lastVerifiedDateClean,
            lastVerificationDate = if (preVerifiedLive) newItem.lastVerificationDate.ifBlank { todayReadable } else newItem.lastVerificationDate,
            isSyncedToSheet = false
        )
        val propertyToSave = stamped(prepared)
        val newList = listOf(propertyToSave) + _properties.value
        val saved = if (context != null) PropertiesLocalDatabase.saveProperties(context, newList) else true
        if (saved) {
            _properties.value = newList
            com.example.inventory.PropertyPrecomputedCache.warmItem(propertyToSave)
            refreshPendingSyncState(reconcileDisk = false)
            val appContext = context
            if (appContext != null) {
                PendingSyncOpStore.enqueueOrCoalesce(
                    appContext,
                    PendingSyncOp(
                        opId = "op_${UUID.randomUUID()}",
                        mutationId = UUID.randomUUID().toString(),
                        entityType = SaveEntityType.PROPERTY,
                        entityId = propertyToSave.id,
                        action = PendingSyncAction.UPSERT,
                        payloadJson = PropertiesLocalDatabase.serializePropertyJson(propertyToSave).toString(),
                        expectedVersion = propertyToSave.recordVersion,
                        createdAt = System.currentTimeMillis()
                    )
                )
                refreshPendingSyncOps()
                runCatching { BackgroundSyncScheduler.enqueueImmediatePush(appContext) }
                if (startForegroundSync) {
                    repoScope.launch {
                        runCatching { pushPropertyToMasterSheet(propertyToSave) }
                        retryPendingWrites()
                    }
                }
            }
        } else if (startForegroundSync) {
            repoScope.launch {
                runCatching { pushPropertyToMasterSheet(propertyToSave) }
            }
        }
        return saved
    }

    /**
     * Non-blocking local-first add: updates in-memory StateFlow immediately (<10ms) and serializes
     * disk save + Master Sheet UPSERT on Dispatchers.IO.
     */
    fun addPropertyAsync(newItem: PropertyItem): Boolean {
        if (_properties.value.any { it.id == newItem.id } ||
            !com.example.inventory.InventoryQuality.validDates(newItem) ||
            (newItem.propertyCode.isNotBlank() && _properties.value.any { it.propertyCode.equals(newItem.propertyCode, ignoreCase = true) && it.id != newItem.id })
        ) {
            return false
        }
        val staffActor = actor()
        val preVerifiedLive = newItem.verificationStatus == VerificationStatus.VERIFIED
        val todayIso = java.time.LocalDate.now().toString()
        val todayReadable = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())
        val propertyToSave = stamped(
            newItem.copy(
                updatedBy = staffActor,
                verifiedBy = if (preVerifiedLive) (newItem.verifiedBy?.takeIf { it.isNotBlank() } ?: staffActor) else newItem.verifiedBy,
                verificationStatus = if (preVerifiedLive) VerificationStatus.VERIFIED else VerificationStatus.PENDING,
                archiveStatus = ArchiveStatus.ACTIVE,
                lastVerifiedDateClean = if (preVerifiedLive) newItem.lastVerifiedDateClean.ifBlank { todayIso } else newItem.lastVerifiedDateClean,
                lastVerificationDate = if (preVerifiedLive) newItem.lastVerificationDate.ifBlank { todayReadable } else newItem.lastVerificationDate,
                isSyncedToSheet = false
            )
        )
        com.example.inventory.PropertyPrecomputedCache.warmItem(propertyToSave)
        _properties.update { current -> listOf(propertyToSave) + current }
        ioWriterScope.launch {
            ioWriterMutex.withLock {
                val appContext = context
                if (appContext != null) {
                    val saved = PropertiesLocalDatabase.saveProperties(appContext, _properties.value)
                    if (saved) {
                        refreshPendingSyncState(reconcileDisk = false)
                        PendingSyncOpStore.enqueueOrCoalesce(
                            appContext,
                            PendingSyncOp(
                                opId = "op_${UUID.randomUUID()}",
                                mutationId = UUID.randomUUID().toString(),
                                entityType = SaveEntityType.PROPERTY,
                                entityId = propertyToSave.id,
                                action = PendingSyncAction.UPSERT,
                                payloadJson = PropertiesLocalDatabase.serializePropertyJson(propertyToSave).toString(),
                                expectedVersion = propertyToSave.recordVersion,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        refreshPendingSyncOps()
                        runCatching { BackgroundSyncScheduler.enqueueImmediatePush(appContext) }
                    }
                }
                if (startForegroundSync) {
                    runCatching { pushPropertyToMasterSheet(propertyToSave) }
                    retryPendingWrites()
                }
            }
        }
        return true
    }

    // Edit Property with Historical Snapshot for Undo / Restore
    @Synchronized
    fun updateProperty(updatedItem: PropertyItem, changeSummary: String): Boolean {
        if (_properties.value.none { it.id == updatedItem.id }) return false
        if (!com.example.inventory.InventoryQuality.validDates(updatedItem)) return false
        if (updatedItem.propertyCode.isNotBlank() && _properties.value.any { it.propertyCode.equals(updatedItem.propertyCode, ignoreCase = true) && it.id != updatedItem.id }) {
            return false
        }
        val today = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
        var updatedTarget: PropertyItem? = null
        val newList = _properties.value.map { existing ->
            if (existing.id == updatedItem.id) {
                // Save previous snapshot in history
                val snapshot = PropertyItemSnapshot(
                    carpetAreaSqFt = existing.carpetAreaSqFt,
                    builtUpAreaSqFt = existing.builtUpAreaSqFt,
                    priceOrRent = existing.priceOrRent,
                    priceSale = existing.priceSale,
                    rent = existing.rent,
                    deposit = existing.deposit,
                    roiPercent = existing.roiPercent,
                    furnishing = existing.furnishing,
                    possession = existing.possession,
                    status = existing.status,
                    ownerName = existing.ownerName,
                    ownerContact = existing.ownerContact,
                    remarks = existing.remarks
                )
                val historyEntry = PropertyEditHistory(
                    historyId = "hist_${System.currentTimeMillis()}",
                    timestamp = today,
                    summary = changeSummary,
                    snapshot = snapshot
                )
                stamped(updatedItem.copy(
                    editHistory = listOf(historyEntry) + existing.editHistory,
                    recordVersion = existing.recordVersion + 1,
                    isSyncedToSheet = false
                )).also { updatedTarget = it }
            } else existing
        }
        val saved = if (context != null) PropertiesLocalDatabase.saveProperties(context, newList) else true
        if (saved) {
            _properties.value = newList
            updatedTarget?.let { com.example.inventory.PropertyPrecomputedCache.warmItem(it) }
            refreshPendingSyncState(reconcileDisk = false)
            context?.let { appContext ->
                runCatching { BackgroundSyncScheduler.enqueueImmediatePush(appContext) }
            }
            if (startForegroundSync) {
                updatedTarget?.let { prop ->
                    repoScope.launch {
                        runCatching { pushPropertyToMasterSheet(prop) }
                        if (_isLiveConnected.value) retryPendingWrites()
                    }
                }
            }
        }
        return saved
    }

    /**
     * Non-blocking local-first update: mutates StateFlow & PrecomputedCache synchronously in <1ms,
     * then persists atomically to filesDir and pushes to Google Sheets on a serialized IO worker.
     */
    fun updatePropertyAsync(updatedItem: PropertyItem, changeSummary: String): Boolean {
        val current = _properties.value
        if (current.none { it.id == updatedItem.id }) return false
        if (!com.example.inventory.InventoryQuality.validDates(updatedItem)) return false
        if (updatedItem.propertyCode.isNotBlank() && current.any { it.propertyCode.equals(updatedItem.propertyCode, ignoreCase = true) && it.id != updatedItem.id }) {
            return false
        }
        val today = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
        var updatedTarget: PropertyItem? = null
        _properties.update { latest ->
            latest.map { existing ->
                if (existing.id == updatedItem.id) {
                    val snapshot = PropertyItemSnapshot(
                        carpetAreaSqFt = existing.carpetAreaSqFt,
                        builtUpAreaSqFt = existing.builtUpAreaSqFt,
                        priceOrRent = existing.priceOrRent,
                        priceSale = existing.priceSale,
                        rent = existing.rent,
                        deposit = existing.deposit,
                        roiPercent = existing.roiPercent,
                        furnishing = existing.furnishing,
                        possession = existing.possession,
                        status = existing.status,
                        ownerName = existing.ownerName,
                        ownerContact = existing.ownerContact,
                        remarks = existing.remarks
                    )
                    val historyEntry = PropertyEditHistory(
                        historyId = "hist_${System.currentTimeMillis()}",
                        timestamp = today,
                        summary = changeSummary,
                        snapshot = snapshot
                    )
                    stamped(updatedItem.copy(
                        editHistory = listOf(historyEntry) + existing.editHistory,
                        recordVersion = existing.recordVersion + 1,
                        isSyncedToSheet = false
                    )).also { updatedTarget = it }
                } else existing
            }
        }
        val prop = updatedTarget ?: return false
        com.example.inventory.PropertyPrecomputedCache.warmItem(prop)
        ioWriterScope.launch {
            ioWriterMutex.withLock {
                val appContext = context
                if (appContext != null) {
                    val saved = PropertiesLocalDatabase.saveProperties(appContext, _properties.value)
                    if (saved) {
                        refreshPendingSyncState(reconcileDisk = false)
                        PendingSyncOpStore.enqueueOrCoalesce(
                            appContext,
                            PendingSyncOp(
                                opId = "op_${UUID.randomUUID()}",
                                mutationId = UUID.randomUUID().toString(),
                                entityType = SaveEntityType.PROPERTY,
                                entityId = prop.id,
                                action = PendingSyncAction.UPSERT,
                                payloadJson = PropertiesLocalDatabase.serializePropertyJson(prop).toString(),
                                expectedVersion = prop.recordVersion,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        refreshPendingSyncOps()
                        runCatching { BackgroundSyncScheduler.enqueueImmediatePush(appContext) }
                    }
                }
                if (startForegroundSync) {
                    runCatching { pushPropertyToMasterSheet(prop) }
                    if (_isLiveConnected.value) retryPendingWrites()
                }
            }
        }
        return true
    }

    @Synchronized
    fun editPropertyReliable(
        propertyId: String,
        expectedVersion: Int,
        changedFields: Set<String>,
        draft: PropertyItem,
        activeUser: UserProfile,
        mutationId: String = ""
    ): PropertyEditOutcome {
        val cleanMutationId = mutationId.trim()
        val dedupKey = if (cleanMutationId.isNotBlank()) "$propertyId|$cleanMutationId" else ""
        if (dedupKey.isNotBlank()) {
            processedMutationOutcomes[dedupKey]?.let { return it }
            val alreadyVersion = context?.getSharedPreferences(MUTATION_DEDUP_PREFS_NAME, Context.MODE_PRIVATE)
                ?.getInt(dedupKey, -1) ?: -1
            if (alreadyVersion >= 0) {
                val latest = _properties.value.firstOrNull { it.id == propertyId }
                return PropertyEditOutcome(
                    status = PropertyEditStatus.SUCCESS,
                    updatedProperty = latest,
                    message = "Already saved on this device"
                )
            }
        }

        val currentList = _properties.value
        val existing = currentList.firstOrNull { it.id == propertyId }

        if (changedFields.isEmpty()) {
            return PropertyEditOutcome(
                status = PropertyEditStatus.NO_CHANGES,
                updatedProperty = existing,
                message = "No changes to save."
            )
        }

        if (existing == null) {
            return PropertyEditOutcome(
                status = PropertyEditStatus.NOT_FOUND,
                updatedProperty = null,
                message = "Property no longer available."
            )
        }

        val portfolio = existing.effectivePortfolio()
        val isAuthorized = when (activeUser.role) {
            UserRole.READ_ONLY, UserRole.BROKER, UserRole.OWNER -> false
            UserRole.ADMIN -> true
            UserRole.COMMERCIAL_MANAGER -> portfolio == StandardPortfolio.COMMERCIAL
            UserRole.RESIDENTIAL_MANAGER -> portfolio == StandardPortfolio.RESIDENTIAL
            UserRole.INVENTORY_MANAGER, UserRole.SALES_EXECUTIVE -> {
                val explicitScope = context?.let { ctx ->
                    val prefs = ctx.getSharedPreferences("real_estate_user_settings", Context.MODE_PRIVATE)
                    prefs.getString("active_brand", null)?.let { ActiveBrand.fromString(it) }
                }
                when (explicitScope) {
                    ActiveBrand.ASCEND_COMMERCIAL -> portfolio == StandardPortfolio.COMMERCIAL
                    ActiveBrand.AUREA_LIVING -> portfolio == StandardPortfolio.RESIDENTIAL
                    ActiveBrand.ALL_BUSINESSES, null -> true
                }
            }
        }
        if (!isAuthorized) {
            return PropertyEditOutcome(
                status = PropertyEditStatus.PERMISSION_DENIED,
                updatedProperty = existing,
                message = "You do not have permission to edit properties in this business division."
            )
        }

        if (existing.recordVersion != expectedVersion) {
            return PropertyEditOutcome(
                status = PropertyEditStatus.CONFLICT,
                updatedProperty = existing,
                message = "Another update exists for this property. Review the latest record before saving."
            )
        }

        val allowedMaintenanceBases = setOf("MONTHLY", "PER_CARPET_SQFT_MONTH", "PER_BUILTUP_SQFT_MONTH", "UNKNOWN")
        fun cleanOptionalText(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }

        var merged: PropertyItem = existing
        for (field in changedFields) {
            merged = when (field) {
                "propertyType" -> merged.copy(propertyType = draft.propertyType.trim())
                "tab" -> merged.copy(tab = draft.tab)
                "transactionType" -> merged.copy(transactionType = draft.transactionType.trim())
                "projectName" -> merged.copy(projectName = draft.projectName.trim())
                "location" -> merged.copy(location = draft.location.trim())
                "unitNumber" -> merged.copy(unitNumber = draft.unitNumber.trim())
                "floor" -> merged.copy(floor = draft.floor.trim())
                "carpetAreaSqFt" -> merged.copy(carpetAreaSqFt = draft.carpetAreaSqFt)
                "builtUpAreaSqFt" -> merged.copy(builtUpAreaSqFt = draft.builtUpAreaSqFt)
                "priceOrRent" -> merged.copy(priceOrRent = draft.priceOrRent)
                "priceSale" -> merged.copy(priceSale = draft.priceSale)
                "rent" -> merged.copy(rent = draft.rent)
                "deposit" -> merged.copy(deposit = draft.deposit)
                "roiPercent" -> merged.copy(roiPercent = draft.roiPercent)
                "monthlyRentalIncome" -> merged.copy(monthlyRentalIncome = draft.monthlyRentalIncome)
                "maintenance" -> merged.copy(maintenance = draft.maintenance)
                "maintenanceBasis" -> {
                    val norm = draft.maintenanceBasis.trim().uppercase(Locale.ENGLISH)
                    merged.copy(maintenanceBasis = if (norm in allowedMaintenanceBases) norm else "UNKNOWN")
                }
                "maintenanceIncluded" -> merged.copy(maintenanceIncluded = draft.maintenanceIncluded)
                "lockInMonths" -> merged.copy(lockInMonths = draft.lockInMonths)
                "escalationPercent" -> merged.copy(escalationPercent = draft.escalationPercent)
                "escalationIntervalMonths" -> merged.copy(escalationIntervalMonths = draft.escalationIntervalMonths)
                "furnishing" -> merged.copy(furnishing = draft.furnishing)
                "bhk" -> merged.copy(bhk = cleanOptionalText(draft.bhk))
                "workstations" -> merged.copy(workstations = draft.workstations)
                "cabins" -> merged.copy(cabins = draft.cabins)
                "conferenceRooms" -> merged.copy(conferenceRooms = draft.conferenceRooms)
                "parkingCount" -> merged.copy(parkingCount = draft.parkingCount)
                "parking" -> merged.copy(parking = draft.parking.trim())
                "possession" -> merged.copy(possession = draft.possession.trim())
                "leaseExpiryDate" -> merged.copy(leaseExpiryDate = draft.leaseExpiryDate.trim())
                "upcomingVacancyDate" -> merged.copy(upcomingVacancyDate = draft.upcomingVacancyDate.trim())
                "status" -> merged.copy(status = draft.status)
                "ownerName" -> merged.copy(ownerName = cleanOptionalText(draft.ownerName))
                "ownerContact" -> merged.copy(ownerContact = cleanOptionalText(draft.ownerContact))
                "brokerName" -> merged.copy(brokerName = cleanOptionalText(draft.brokerName))
                "brokerContact" -> merged.copy(brokerContact = cleanOptionalText(draft.brokerContact))
                "sourceInfo" -> merged.copy(sourceInfo = cleanOptionalText(draft.sourceInfo))
                "pocType" -> merged.copy(pocType = normalizePocType(draft.pocType))
                "remarks" -> merged.copy(remarks = cleanOptionalText(draft.remarks))
                "internalRemarks" -> merged.copy(internalRemarks = cleanOptionalText(draft.internalRemarks))
                "listingLink" -> merged.copy(listingLink = cleanOptionalText(draft.listingLink))
                "images" -> merged.copy(images = draft.images)
                "attachments" -> merged.copy(attachments = draft.attachments)
                "videoUrl" -> merged.copy(videoUrl = cleanOptionalText(draft.videoUrl))
                "floorPlanUrl" -> merged.copy(floorPlanUrl = cleanOptionalText(draft.floorPlanUrl))
                "fullAddress" -> merged.copy(fullAddress = draft.fullAddress.trim())
                "city" -> merged.copy(city = draft.city.trim())
                "locationPrecision" -> merged.copy(locationPrecision = draft.locationPrecision)
                else -> merged
            }
        }

        if ("priceOrRent" !in changedFields &&
            ("priceSale" in changedFields || "rent" in changedFields || "transactionType" in changedFields || "tab" in changedFields)
        ) {
            val txLower = merged.transactionType.lowercase(Locale.ENGLISH)
            val isPreLeased = txLower.contains("pre-lease") || txLower.contains("pre leased") || merged.tab == InventoryTab.PRE_LEASED
            val isLeaseTx = !isPreLeased && (txLower.contains("lease") || txLower.contains("rent") || (txLower.isBlank() && merged.tab.isRental))
            val syncedPrimary = if (isLeaseTx) {
                merged.rent ?: if ("rent" in changedFields) 0.0 else merged.priceOrRent
            } else {
                merged.priceSale ?: if ("priceSale" in changedFields) 0.0 else merged.priceOrRent
            }
            merged = merged.copy(priceOrRent = syncedPrimary)
        }

        val validationError = validateEditedProperty(merged)
        if (validationError != null) {
            return PropertyEditOutcome(
                status = PropertyEditStatus.VALIDATION_ERROR,
                updatedProperty = existing,
                message = validationError
            )
        }

        val nowIso = java.time.Instant.now().toString()
        val displayActor = "${activeUser.username.trim().ifBlank { "Unregistered" }} (${activeUser.role.label})"
        val historyTimestamp = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
        val snapshot = PropertyItemSnapshot(
            carpetAreaSqFt = existing.carpetAreaSqFt,
            builtUpAreaSqFt = existing.builtUpAreaSqFt,
            priceOrRent = existing.priceOrRent,
            priceSale = existing.priceSale,
            rent = existing.rent,
            deposit = existing.deposit,
            roiPercent = existing.roiPercent,
            furnishing = existing.furnishing,
            possession = existing.possession,
            status = existing.status,
            ownerName = existing.ownerName,
            ownerContact = existing.ownerContact,
            remarks = existing.remarks
        )
        val historyEntry = PropertyEditHistory(
            historyId = "hist_${System.currentTimeMillis()}",
            timestamp = historyTimestamp,
            summary = "Edited: ${changedFields.sorted().joinToString(", ")}",
            snapshot = snapshot
        )

        val updatedRecord = merged.copy(
            editHistory = listOf(historyEntry) + existing.editHistory,
            recordVersion = existing.recordVersion + 1,
            updatedAt = nowIso,
            updatedBy = displayActor,
            isSyncedToSheet = false
        )

        val newList = currentList.map { if (it.id == existing.id) updatedRecord else it }

        if (context != null) {
            val saved = PropertiesLocalDatabase.saveProperties(context, newList)
            if (!saved) {
                refreshStorageHealth()
                return PropertyEditOutcome(
                    status = PropertyEditStatus.STORAGE_ERROR,
                    updatedProperty = existing,
                    message = "Failed to save property on device storage. Original record retained."
                )
            }
            val persisted = updatedRecord
            _properties.value = newList
            com.example.inventory.PropertyPrecomputedCache.warmItem(persisted)
            refreshStorageHealth()
            refreshPendingSyncState(reconcileDisk = false)

            val effectiveMutationId = cleanMutationId.ifBlank { java.util.UUID.randomUUID().toString() }
            PendingSyncOpStore.enqueueOrCoalesce(
                context,
                PendingSyncOp(
                    opId = "op_${java.util.UUID.randomUUID()}",
                    mutationId = effectiveMutationId,
                    entityType = SaveEntityType.PROPERTY,
                    entityId = persisted.id,
                    action = PendingSyncAction.UPSERT,
                    payloadJson = PropertiesLocalDatabase.serializePropertyJson(persisted).toString(),
                    expectedVersion = persisted.recordVersion,
                    createdAt = System.currentTimeMillis()
                )
            )
            refreshPendingSyncOps()
            if (startForegroundSync) {
                repoScope.launch {
                    runCatching { pushPropertyToMasterSheet(persisted) }
                }
            }
            EmployeeActivityJournal.record(
                context,
                action = "EDIT_PROPERTY",
                entityType = "PROPERTY",
                entityId = persisted.id,
                details = "v${persisted.recordVersion}: ${changedFields.sorted().joinToString(",")}"
            )
            val successOutcome = PropertyEditOutcome(
                status = PropertyEditStatus.SUCCESS,
                updatedProperty = persisted,
                message = "Saved on this device."
            )
            if (dedupKey.isNotBlank()) {
                processedMutationOutcomes[dedupKey] = successOutcome
                context.getSharedPreferences(MUTATION_DEDUP_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putInt(dedupKey, persisted.recordVersion).apply()
            }
            if (_isLiveConnected.value) {
                repoScope.launch {
                    retryPendingWrites()
                }
            }
            return successOutcome
        } else {
            _properties.value = newList
            val successOutcome = PropertyEditOutcome(
                status = PropertyEditStatus.SUCCESS,
                updatedProperty = updatedRecord,
                message = "Saved on this device."
            )
            if (dedupKey.isNotBlank()) {
                processedMutationOutcomes[dedupKey] = successOutcome
            }
            return successOutcome
        }
    }

    private fun validateEditedProperty(item: PropertyItem): String? {
        if (item.projectName.isBlank()) return "Project / Building name is required."
        if (item.location.isBlank()) return "Location is required."
        if (item.propertyType.isBlank()) return "Property type is required."

        val txLower = item.transactionType.trim().lowercase(Locale.ENGLISH)
        if (txLower !in setOf("lease", "sale", "pre-leased", "pre-leased sale")) {
            return "Transaction type must be Lease, Sale, or Pre-Leased."
        }

        if (!item.carpetAreaSqFt.isFinite() || item.carpetAreaSqFt < 0.0) {
            return "Carpet area must be a non-negative finite number."
        }
        item.builtUpAreaSqFt?.let {
            if (!it.isFinite() || it < 0.0) return "Built-up area must be a non-negative finite number."
        }
        if (!item.priceOrRent.isFinite() || item.priceOrRent < 0.0) {
            return "Price / rent must be a non-negative finite number."
        }
        item.priceSale?.let {
            if (!it.isFinite() || it < 0.0) return "Sale price must be a non-negative finite number."
        }
        item.rent?.let {
            if (!it.isFinite() || it < 0.0) return "Monthly rent must be a non-negative finite number."
        }
        item.deposit?.let {
            if (!it.isFinite() || it < 0.0) return "Security deposit must be a non-negative finite number."
        }
        item.maintenance?.let {
            if (!it.isFinite() || it < 0.0) return "Maintenance must be a non-negative finite number."
        }
        item.roiPercent?.let {
            if (!it.isFinite() || it < 0.0) return "ROI % must be a non-negative finite number."
        }
        item.monthlyRentalIncome?.let {
            if (!it.isFinite() || it < 0.0) return "Monthly rental income must be a non-negative finite number."
        }
        item.parkingCount?.let {
            if (it < 0) return "Parking count cannot be negative."
        }
        item.lockInMonths?.let {
            if (it < 0) return "Lock-in months cannot be negative."
        }
        item.escalationPercent?.let {
            if (!it.isFinite() || it < 0.0) return "Escalation % must be a non-negative finite number."
        }
        item.escalationIntervalMonths?.let {
            if (it < 0) return "Escalation interval months cannot be negative."
        }

        val isoDateRegex = Regex("\\d{4}-\\d{2}-\\d{2}")
        if (item.leaseExpiryDate.isNotBlank() &&
            (!isoDateRegex.matches(item.leaseExpiryDate) || com.example.inventory.InventoryQuality.date(item.leaseExpiryDate) == null)
        ) {
            return "Lease expiry date must be a valid YYYY-MM-DD date."
        }
        if (item.upcomingVacancyDate.isNotBlank() &&
            (!isoDateRegex.matches(item.upcomingVacancyDate) || com.example.inventory.InventoryQuality.date(item.upcomingVacancyDate) == null)
        ) {
            return "Upcoming vacancy date must be a valid YYYY-MM-DD date."
        }

        return null
    }

    // Restore previous version from history
    fun restorePropertyHistory(propertyId: String, historyId: String): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        val v = p.editHistory.find { it.historyId == historyId }?.snapshot ?: return false
        return updateProperty(p.copy(carpetAreaSqFt=v.carpetAreaSqFt, builtUpAreaSqFt=v.builtUpAreaSqFt,
            priceOrRent=v.priceOrRent, priceSale=v.priceSale, rent=v.rent, deposit=v.deposit,
            roiPercent=v.roiPercent, furnishing=v.furnishing, possession=v.possession, status=v.status,
            ownerName=v.ownerName, ownerContact=v.ownerContact, remarks=v.remarks), "Restore history")
    }

    fun updateAvailability(propertyId: String, newStatus: AvailabilityStatus): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        return updateProperty(p.copy(status=newStatus), "Availability: ${newStatus.label}")
    }

    fun updateAvailabilityAsync(propertyId: String, newStatus: AvailabilityStatus): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        return updatePropertyAsync(p.copy(status = newStatus), "Availability: ${newStatus.label}")
    }

    data class VerifyPublishResult(
        val success: Boolean,
        val missingFields: List<String> = emptyList(),
        val message: String,
        val property: PropertyItem? = null
    )

    fun updateVerificationStatus(propertyId: String, status: VerificationStatus, verifiedBy: String? = null): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        val today = java.time.LocalDate.now().toString()
        val nextArchive = if (status == VerificationStatus.VERIFIED) ArchiveStatus.ACTIVE else p.archiveStatus
        return updateProperty(p.copy(
            verificationStatus = status,
            archiveStatus = nextArchive,
            lastVerifiedDateClean = if (status == VerificationStatus.VERIFIED) today else p.lastVerifiedDateClean,
            verifiedBy = if (status == VerificationStatus.VERIFIED) actor() else p.verifiedBy,
            ownerConfirmationStatus = if (status == VerificationStatus.VERIFIED) "Confirmed" else "Pending"
        ), "Verification updated")
    }

    /**
     * Enforces 100% minimum required details before promoting an archived/pending property
     * to Verified + Active (Live) and syncing it to Google Sheets.
     */
    fun verifyAndPublishProperty(
        propertyId: String,
        availabilityStatus: AvailabilityStatus? = null,
        method: String = "Verification Switch",
        notes: String = "",
        verifiedBy: String? = null
    ): VerifyPublishResult {
        val p = _properties.value.find { it.id == propertyId }
            ?: return VerifyPublishResult(false, emptyList(), "Property record not found.")
        val effectiveStatus = availabilityStatus ?: p.status
        val candidate = p.copy(status = effectiveStatus)
        val missing = com.example.inventory.InventoryQuality.minimumRequiredMissingFields(candidate)
        if (missing.isNotEmpty()) {
            return VerifyPublishResult(
                success = false,
                missingFields = missing,
                message = "Complete 100% required details before going Live: ${missing.joinToString(", ")}",
                property = p
            )
        }
        val today = java.time.LocalDate.now().toString()
        val verifier = verifiedBy?.takeIf { it.isNotBlank() } ?: actor()
        val updatedNotes = if (notes.isNotBlank()) {
            listOf(p.internalRemarks.orEmpty(), "[$today · $method] $notes").filter { it.isNotBlank() }.joinToString("\n")
        } else p.internalRemarks
        val nextProp = candidate.copy(
            verificationStatus = VerificationStatus.VERIFIED,
            archiveStatus = ArchiveStatus.ACTIVE,
            lastVerifiedDateClean = today,
            lastVerificationDate = today,
            verifiedBy = verifier,
            ownerConfirmationStatus = "Confirmed",
            internalRemarks = updatedNotes,
            isSyncedToSheet = false
        )
        val saved = updateProperty(nextProp, "Verified & Published Live ($method)")
        if (!saved) {
            return VerifyPublishResult(false, emptyList(), "Unable to save verified property locally.", p)
        }
        val persisted = _properties.value.find { it.id == propertyId } ?: nextProp
        if (context != null) {
            PendingSyncOpStore.enqueueOrCoalesce(
                context,
                PendingSyncOp(
                    opId = "op_${java.util.UUID.randomUUID()}",
                    mutationId = java.util.UUID.randomUUID().toString(),
                    entityType = SaveEntityType.PROPERTY,
                    entityId = persisted.id,
                    action = PendingSyncAction.UPSERT,
                    payloadJson = PropertiesLocalDatabase.serializePropertyJson(persisted).toString(),
                    expectedVersion = persisted.recordVersion,
                    createdAt = System.currentTimeMillis()
                )
            )
            refreshPendingSyncOps()
            if (startForegroundSync) {
                repoScope.launch {
                    runCatching { pushPropertyToMasterSheet(persisted) }
                    if (_sheetsScriptUrl.value.isNotBlank()) {
                        retryPendingWrites()
                    }
                }
            }
        }
        return VerifyPublishResult(
            success = true,
            missingFields = emptyList(),
            message = "Verified & Live — queued for Google Sheet sync",
            property = persisted
        )
    }

    fun unverifyAndArchiveProperty(propertyId: String, reason: String = "Moved to Archive"): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        return updateProperty(
            p.copy(
                verificationStatus = VerificationStatus.PENDING,
                archiveStatus = ArchiveStatus.ARCHIVED,
                ownerConfirmationStatus = "Pending"
            ),
            reason
        )
    }

    /**
     * Batch 13: Toggling a Live card OFF moves it to Pending (Off-Market, ArchiveStatus.ACTIVE)
     * instead of Archived, writes immediately to filesDir, and queues Master Sheet UPSERT.
     */
    fun movePropertyToPending(propertyId: String, reason: String = "Toggled Off Live → Pending"): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        val updated = p.copy(
            verificationStatus = VerificationStatus.PENDING,
            archiveStatus = ArchiveStatus.ACTIVE,
            ownerConfirmationStatus = "Pending",
            isSyncedToSheet = false
        )
        val saved = updateProperty(updated, reason)
        if (saved && context != null) {
            val persisted = _properties.value.find { it.id == propertyId } ?: updated
            PendingSyncOpStore.enqueueOrCoalesce(
                context,
                PendingSyncOp(
                    opId = "op_${java.util.UUID.randomUUID()}",
                    mutationId = java.util.UUID.randomUUID().toString(),
                    entityType = SaveEntityType.PROPERTY,
                    entityId = persisted.id,
                    action = PendingSyncAction.UPSERT,
                    payloadJson = PropertiesLocalDatabase.serializePropertyJson(persisted).toString(),
                    expectedVersion = persisted.recordVersion,
                    createdAt = System.currentTimeMillis()
                )
            )
            refreshPendingSyncOps()
            repoScope.launch {
                pushPropertyToMasterSheet(persisted)
                retryPendingWrites()
            }
        }
        return saved
    }

    /**
     * Non-blocking Live -> Pending toggle for instant <10ms card switch feedback.
     */
    fun movePropertyToPendingAsync(propertyId: String, reason: String = "Toggled Off Live → Pending"): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        val updated = p.copy(
            verificationStatus = VerificationStatus.PENDING,
            archiveStatus = ArchiveStatus.ACTIVE,
            ownerConfirmationStatus = "Pending",
            isSyncedToSheet = false
        )
        return updatePropertyAsync(updated, reason)
    }

    /**
     * Non-blocking Pending -> Live verification & publish for instant <10ms switch feedback.
     */
    fun verifyAndPublishPropertyAsync(
        propertyId: String,
        availabilityStatus: AvailabilityStatus? = null,
        method: String = "Verification Switch",
        notes: String = "",
        verifiedBy: String? = null
    ): VerifyPublishResult {
        val p = _properties.value.find { it.id == propertyId }
            ?: return VerifyPublishResult(false, emptyList(), "Property record not found.")
        val effectiveStatus = availabilityStatus ?: p.status
        val candidate = p.copy(status = effectiveStatus)
        val missing = com.example.inventory.InventoryQuality.minimumRequiredMissingFields(candidate)
        if (missing.isNotEmpty()) {
            return VerifyPublishResult(
                success = false,
                missingFields = missing,
                message = "Complete 100% required details before going Live: ${missing.joinToString(", ")}",
                property = p
            )
        }
        val today = java.time.LocalDate.now().toString()
        val verifier = verifiedBy?.takeIf { it.isNotBlank() } ?: actor()
        val updatedNotes = if (notes.isNotBlank()) {
            listOf(p.internalRemarks.orEmpty(), "[$today · $method] $notes").filter { it.isNotBlank() }.joinToString("\n")
        } else p.internalRemarks
        val nextProp = candidate.copy(
            verificationStatus = VerificationStatus.VERIFIED,
            archiveStatus = ArchiveStatus.ACTIVE,
            lastVerifiedDateClean = today,
            lastVerificationDate = today,
            verifiedBy = verifier,
            ownerConfirmationStatus = "Confirmed",
            internalRemarks = updatedNotes,
            isSyncedToSheet = false
        )
        val saved = updatePropertyAsync(nextProp, "Verified & Published Live ($method)")
        if (!saved) {
            return VerifyPublishResult(false, emptyList(), "Unable to save verified property locally.", p)
        }
        val updated = _properties.value.find { it.id == propertyId } ?: nextProp
        return VerifyPublishResult(
            success = true,
            missingFields = emptyList(),
            message = "Verified & Live — queued for Google Sheet sync",
            property = updated
        )
    }

    /**
     * Batch 13: Applies an approved Project Photo to the current property AND ALL other units
     * belonging to the same projectName (or projectId), saves immediately to filesDir, and syncs to Sheet.
     */
    fun applyProjectPhotoToAllMatchingUnits(
        projectName: String,
        projectId: String,
        photoUrl: String,
        targetPropertyId: String? = null
    ): Int {
        val cleanUrl = photoUrl.trim()
        if (cleanUrl.isBlank()) return 0
        val normName = projectName.trim().lowercase(Locale.ENGLISH)
        val normPid = projectId.trim()
        val matchedIds = mutableListOf<String>()
        val updatedList = _properties.value.map { prop ->
            val sameId = targetPropertyId != null && prop.id == targetPropertyId
            val sameProjectId = normPid.isNotBlank() && prop.projectId.trim().equals(normPid, ignoreCase = true)
            val sameProjectName = normName.isNotBlank() && prop.projectName.trim().lowercase(Locale.ENGLISH) == normName
            if (sameId || sameProjectId || sameProjectName) {
                val mergedImages = (listOf(cleanUrl) + prop.images.filterNot { it.equals(cleanUrl, ignoreCase = true) }).distinct()
                if (mergedImages != prop.images) {
                    matchedIds.add(prop.id)
                    stamped(
                        prop.copy(
                            images = mergedImages,
                            listingLink = prop.listingLink?.takeIf { it.isNotBlank() } ?: cleanUrl,
                            recordVersion = prop.recordVersion + 1,
                            isSyncedToSheet = false
                        )
                    )
                } else prop
            } else prop
        }
        if (matchedIds.isEmpty()) return 0
        _properties.value = updatedList
        val appContext = context
        if (appContext != null) {
            PropertiesLocalDatabase.saveProperties(appContext, updatedList)
            refreshPendingSyncState()
            val refreshedMap = _properties.value.associateBy { it.id }
            matchedIds.forEach { pid ->
                refreshedMap[pid]?.let { persisted ->
                    PendingSyncOpStore.enqueueOrCoalesce(
                        appContext,
                        PendingSyncOp(
                            opId = "op_${java.util.UUID.randomUUID()}",
                            mutationId = java.util.UUID.randomUUID().toString(),
                            entityType = SaveEntityType.PROPERTY,
                            entityId = persisted.id,
                            action = PendingSyncAction.UPSERT,
                            payloadJson = PropertiesLocalDatabase.serializePropertyJson(persisted).toString(),
                            expectedVersion = persisted.recordVersion,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            refreshPendingSyncOps()
            repoScope.launch {
                retryPendingWrites()
            }
        }
        return matchedIds.size
    }

    /**
     * Fetch building photo candidates via free exact-quoted Bing Images search (no API key required).
     */
    suspend fun searchOnlineProjectPhotos(
        projectName: String,
        location: String,
        customQuery: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        googleMapsUrl: String? = null
    ): Result<List<OnlineProjectPhotoCandidate>> = withContext(Dispatchers.IO) {
        val cleanProject = projectName.trim()
        if (cleanProject.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Project / Building name is required to search photos."))
        }
        val (resolvedLat, resolvedLng) = resolveProjectCoordinates(
            projectName = cleanProject,
            latitude = latitude,
            longitude = longitude,
            googleMapsUrl = googleMapsUrl
        )
        val candidates = BingImageSearchEngine.searchProjectPhotos(
            projectName = cleanProject,
            location = location,
            customQuery = customQuery,
            latitude = resolvedLat,
            longitude = resolvedLng
        )
        Result.success(candidates)
    }

    private fun resolveProjectCoordinates(
        projectName: String,
        latitude: Double?,
        longitude: Double?,
        googleMapsUrl: String?
    ): Pair<Double?, Double?> {
        if (latitude != null && longitude != null && (latitude != 0.0 || longitude != 0.0)) {
            return latitude to longitude
        }
        // Try parsing @lat,lng or q=lat,lng from Google Maps URL if available
        val urlText = googleMapsUrl.orEmpty()
        if (urlText.isNotBlank()) {
            val coordRegex = Regex("""(?:@|q=|ll=|center=)(-?\d+\.\d+),\s*(-?\d+\.\d+)""")
            val match = coordRegex.find(urlText)
            if (match != null) {
                val parsedLat = match.groupValues[1].toDoubleOrNull()
                val parsedLng = match.groupValues[2].toDoubleOrNull()
                if (parsedLat != null && parsedLng != null && (parsedLat != 0.0 || parsedLng != 0.0)) {
                    return parsedLat to parsedLng
                }
            }
        }
        // Fall back to any sibling unit in the same project that already has coordinates
        val siblingWithCoords = _properties.value.firstOrNull { item ->
            item.projectName.trim().equals(projectName, ignoreCase = true) &&
                item.latitude != null && item.longitude != null &&
                (item.latitude != 0.0 || item.longitude != 0.0)
        }
        return if (siblingWithCoords != null) {
            siblingWithCoords.latitude to siblingWithCoords.longitude
        } else {
            null to null
        }
    }

    fun confirmPrice(propertyId: String): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        return updateProperty(p.copy(lastPriceConfirmationDate=java.time.LocalDate.now().toString()), "Price explicitly confirmed")
    }

    fun confirmOwnerCallStatus(
        propertyId: String,
        newStatus: AvailabilityStatus,
        callNote: String = "",
        confirmedBy: String? = null,
        updatedRentOrSale: Double? = null,
        pocType: String? = null
    ): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        val today = java.time.LocalDate.now().toString()
        val verifier = confirmedBy?.takeIf { it.isNotBlank() } ?: actor()
        val notePrefix = "[$today · Post-Call Owner Check by $verifier]"
        val mergedRemarks = if (callNote.isNotBlank()) {
            listOf(p.internalRemarks.orEmpty(), "$notePrefix ${callNote.trim()}")
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } else p.internalRemarks

        val validPrice = updatedRentOrSale?.takeIf { it > 0.0 }
        val nextPriceOrRent = validPrice ?: p.priceOrRent
        val nextRent = if (validPrice != null && p.tab.isRental) validPrice else p.rent
        val nextSale = if (validPrice != null && !p.tab.isRental) validPrice else p.priceSale
        val nextPriceConfDate = if (validPrice != null) today else p.lastPriceConfirmationDate

        val nextVerification = if (newStatus == AvailabilityStatus.AVAILABLE) {
            VerificationStatus.VERIFIED
        } else {
            p.verificationStatus
        }
        val nextArchive = when (newStatus) {
            AvailabilityStatus.LEASED, AvailabilityStatus.SOLD -> ArchiveStatus.ARCHIVED
            else -> p.archiveStatus
        }

        val nextPocType = normalizePocType(pocType).ifBlank { p.pocType }
        val summary = buildString {
            append("Post-call Owner update: ${newStatus.label}")
            if (validPrice != null) append(" (Updated Rent/Price: ₹${validPrice.toLong()})")
        }
        return updatePropertyAsync(
            p.copy(
                status = newStatus,
                verificationStatus = nextVerification,
                archiveStatus = nextArchive,
                lastVerifiedDateClean = today,
                lastVerificationDate = today,
                lastPriceConfirmationDate = nextPriceConfDate,
                verifiedBy = verifier,
                updatedBy = verifier,
                updatedAt = today,
                ownerConfirmationStatus = "Confirmed",
                priceOrRent = nextPriceOrRent,
                rent = nextRent,
                priceSale = nextSale,
                internalRemarks = mergedRemarks,
                pocType = nextPocType
            ),
            summary
        )
    }

    fun linkOwnerPhoneToProperty(
        propertyId: String,
        ownerPhone: String,
        ownerName: String? = null,
        callNote: String = "",
        pocType: String? = null
    ): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        val cleanPhone = ownerPhone.filter { it.isDigit() }.takeLast(10).ifBlank { ownerPhone.trim() }
        val mergedContact = listOf(p.ownerContact.orEmpty().trim(), cleanPhone)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" / ")
        val nextName = p.ownerName?.takeIf { it.isNotBlank() } ?: ownerName?.takeIf { it.isNotBlank() }
        val nextPocType = normalizePocType(pocType).ifBlank { p.pocType }
        val today = java.time.LocalDate.now().toString()
        val mergedRemarks = if (callNote.isNotBlank()) {
            listOf(p.internalRemarks.orEmpty(), "[$today · Linked via Post-Call] ${callNote.trim()}")
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } else p.internalRemarks
        return updatePropertyAsync(
            p.copy(
                ownerContact = mergedContact,
                ownerName = nextName,
                internalRemarks = mergedRemarks,
                pocType = nextPocType
            ),
            "Linked owner phone via Post-Call"
        )
    }

    fun updateArchiveStatus(propertyId: String, status: ArchiveStatus): Boolean {
        val p = _properties.value.find { it.id == propertyId } ?: return false
        return updateProperty(p.copy(archiveStatus=status), "Archive status updated")
    }

    fun verifyProperty(propertyId: String): Boolean = verifyAndPublishProperty(propertyId).success

    @Synchronized
    fun resolveDuplicate(firstId: String, secondId: String, decision: String): Boolean {
        if (!allowed("BULK_EDIT")) return false
        if (decision !in setOf("Keep Separate", "Link as Same Unit", "Review Later")) return false
        val a = _properties.value.find { it.id == firstId } ?: return false
        val b = _properties.value.find { it.id == secondId } ?: return false
        if (a.id == b.id) return false
        val group = a.sameUnitGroupId.ifBlank { b.sameUnitGroupId }.ifBlank { java.util.UUID.randomUUID().toString() }
        _properties.value = _properties.value.map {
            if (it.id == a.id || it.id == b.id) stamped(it.copy(
                duplicateDecisions=it.duplicateDecisions + ((if(it.id==a.id)b.id else a.id) to decision),
                sameUnitGroupId=if(decision=="Link as Same Unit") group else it.sameUnitGroupId,
                recordVersion = it.recordVersion + 1,
                isSyncedToSheet=false)) else it
        }
        return persistChanges()
    }

    // Photo Management
    @Synchronized
    fun addPropertyPhoto(propertyId: String, photoUrl: String) {
        _properties.value = _properties.value.map { prop ->
            if (prop.id == propertyId) prop.copy(images = prop.images + photoUrl, recordVersion = prop.recordVersion + 1, isSyncedToSheet = false) else prop
        }
        persistChanges()
    }

    @Synchronized
    fun removePropertyPhoto(propertyId: String, photoIndex: Int) {
        _properties.value = _properties.value.map { prop ->
            if (prop.id == propertyId && photoIndex in prop.images.indices) {
                val list = prop.images.toMutableList()
                list.removeAt(photoIndex)
                prop.copy(images = list, recordVersion = prop.recordVersion + 1, isSyncedToSheet = false)
            } else prop
        }
        persistChanges()
    }

    fun setCoverPhoto(propertyId: String, photoIndex: Int) {
        _properties.value = _properties.value.map { prop ->
            if (prop.id == propertyId && photoIndex in prop.images.indices) {
                val item = prop.images[photoIndex]
                val list = prop.images.toMutableList()
                list.removeAt(photoIndex)
                list.add(0, item)
                prop.copy(images = list, recordVersion = prop.recordVersion + 1, isSyncedToSheet = false)
            } else prop
        }
        persistChanges()
    }

    // Document & Attachment Management
    @Synchronized
    fun addPropertyAttachment(propertyId: String, attachment: PropertyAttachment) {
        _properties.value = _properties.value.map { prop ->
            if (prop.id == propertyId) {
                prop.copy(attachments = prop.attachments + attachment, recordVersion = prop.recordVersion + 1, isSyncedToSheet = false)
            } else prop
        }
        persistChanges()
    }

    @Synchronized
    fun removePropertyAttachment(propertyId: String, attachmentId: String) {
        _properties.value = _properties.value.map { prop ->
            if (prop.id == propertyId) {
                prop.copy(attachments = prop.attachments.filter { it.id != attachmentId }, recordVersion = prop.recordVersion + 1, isSyncedToSheet = false)
            } else prop
        }
        persistChanges()
    }


    // Header Recognition & Synonym Matching Engine (Step 1 Standard)
    fun recognizeHeader(sourceHeader: String): Pair<String, MappingConfidence> {
        val clean = sourceHeader.trim().lowercase()
            .replace("_", " ")
            .replace("-", " ")
            .replace(".", "")
            .replace(":", "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Exact matches against 35 standard master columns
        MASTER_PROPERTY_35_COLUMNS.firstOrNull { it.lowercase() == clean }?.let {
            return Pair(it, MappingConfidence.EXACT)
        }

        // TLC-1 three-tab master sheet uses different header labels from the
        // legacy 35-column import template. Resolve explicit aliases first.
        // Preserve non-mapped columns through the existing unmapped-data path.
        val masterAliases = mapOf(
            "property id" to "Record ID",
            "project id" to "Project ID",
            "micro market" to "Micro-Market",
            "legacy property code" to "Property Code",
            "date added" to "Date",
            "location" to "Location",
            "project / building" to "Project / Building",
            "amenities" to "Amenities",
            "unit no" to "Unit / Shop / Office No",
            "carpet sqft" to "Carpet Area",
            "total carpet area (sq ft)" to "Carpet Area",
            "ground floor carpet (sq ft)" to "Ground Floor Carpet (Sq Ft)",
            "mezzanine carpet (sq ft)" to "Mezzanine Carpet (Sq Ft)",
            "built up sqft" to "Built-up Area",
            "total built up area (sq ft)" to "Built-up Area",
            "sale price inr" to "Price / Sale",
            "sale price (₹)" to "Price / Sale",
            "rent inr/month" to "Rent",
            "monthly rent (₹)" to "Rent",
            "current monthly rent" to "Rent",
            "deposit inr" to "Deposit",
            "total security deposit (₹)" to "Deposit",
            "roi percent" to "ROI %",
            "current roi / yield (%)" to "ROI %",
            "attached pantry" to "Attached Pantry",
            "attached washroom" to "Attached Washroom",
            "frontage (ft)" to "Frontage (Ft)",
            "frontage ft" to "Frontage (Ft)",
            "clear height (ft)" to "Clear Height (Ft)",
            "clear height ft" to "Clear Height (Ft)",
            "truck access" to "Truck Access",
            "power load" to "Power Load",
            "target possession date" to "Target Possession Date",
            "pune zone" to "Pune Zone",
            "jurisdiction" to "Jurisdiction",
            "oc status" to "OC Status",
            "pre leased status" to "Pre-Leased Status",
            "tenant name" to "Tenant Name",
            "tenant / brand" to "Tenant Name",
            "car parking" to "Car Parking",
            "parking" to "Car Parking",
            "type of poc" to "Type of POC",
            "poc type" to "Type of POC",
            "owner / poc" to "Owner / Contact Person",
            "owner contact" to "Contact Number",
            "listing url" to "Listing Link",
            "last verified" to "Last Verified Date",
            "record status" to "Archive Status"
        )
        masterAliases[clean]?.let { return Pair(it, MappingConfidence.HIGH) }

        // High Confidence Synonym Dictionary
        return when {
            clean in listOf("pcode", "code", "property code", "prop code", "listing code", "unit code", "flat code", "listing id", "asset code", "legacy property code") ->
                Pair("Property Code", MappingConfidence.HIGH)
            clean in listOf("date", "entry date", "listed date", "created date", "record date", "listing date", "inventory date") ->
                Pair("Date", MappingConfidence.HIGH)
            clean in listOf("location", "locality", "place", "address", "site address", "property address") ->
                Pair("Location", MappingConfidence.HIGH)
            clean == "amenities" || clean == "amenity" ->
                Pair("Amenities", MappingConfidence.HIGH)
            clean in listOf("office name", "building name", "project name", "project", "building", "society", "tower", "complex", "commercial building", "property name") ->
                Pair("Project / Building", MappingConfidence.HIGH)
            clean in listOf("unit", "shop", "office no", "unit no", "shop no", "office number", "unit number", "flat no", "suite no", "room no", "door no", "shop / office no") ->
                Pair("Unit / Shop / Office No", MappingConfidence.HIGH)
            clean in listOf("floor", "floor no", "level", "storey", "floor number") ->
                Pair("Floor", MappingConfidence.HIGH)
            clean in listOf("property type", "asset type", "prop type", "category", "commercial/residential", "commercial or residential") ->
                Pair("Property Type", MappingConfidence.HIGH)
            clean in listOf("sub type", "subtype", "configuration", "inventory type", "tab", "deal category") ->
                Pair("Sub Type", MappingConfidence.HIGH)
            clean in listOf("carpet sq ft", "carpet area", "carpet", "usable area", "carpet sqft", "rwa", "net area", "carpet (sq ft)") ->
                Pair("Carpet Area", MappingConfidence.HIGH)
            clean in listOf("built-up area", "built up area", "builtup area", "builtup", "bua", "super built-up", "chargeable area", "saleable area", "built-up (sq ft)") ->
                Pair("Built-up Area", MappingConfidence.HIGH)
            clean in listOf("sale amount", "price / sale", "sale price", "cost", "total cost", "asking price", "expected price", "rate", "sale rate", "outright price") ->
                Pair("Price / Sale", MappingConfidence.HIGH)
            clean in listOf("monthly rental", "rent", "rental", "monthly rent", "lease rent", "asking rent", "rate per month", "rent pm") ->
                Pair("Rent", MappingConfidence.HIGH)
            clean in listOf("deposit", "security deposit", "deposit amount", "dep", "advance", "token deposit") ->
                Pair("Deposit", MappingConfidence.HIGH)
            clean in listOf("roi", "roi %", "yield", "gross yield", "rental yield", "return", "annual yield") ->
                Pair("ROI %", MappingConfidence.HIGH)
            clean in listOf("possession", "possession date", "available from", "possession status", "availability date", "handover") ->
                Pair("Possession", MappingConfidence.HIGH)
            clean in listOf("furnishing", "furnishing status", "fitout", "fitouts", "interior", "furnished status") ->
                Pair("Furnishing", MappingConfidence.HIGH)
            clean in listOf("status", "availability status", "avail status", "occupancy", "current status") ->
                Pair("Status", MappingConfidence.HIGH)
            clean in listOf("type of poc", "poc type", "poc category", "contact type", "owner / poc type") ->
                Pair("Type of POC", MappingConfidence.HIGH)
            clean in listOf("owner", "contact person", "owner name", "seller", "landlord", "broker name", "person", "client name", "point of contact") ->
                Pair("Owner / Contact Person", MappingConfidence.HIGH)
            clean in listOf("mobile number", "contact number", "phone", "mobile", "phone number", "cell", "contact no", "telephone", "mobile no") ->
                Pair("Contact Number", MappingConfidence.HIGH)
            clean in listOf("source", "listing source", "lead source", "channel", "broker source", "brokerage") ->
                Pair("Source", MappingConfidence.HIGH)
            clean in listOf("remarks", "description", "details", "comments", "property notes", "notes") ->
                Pair("Remarks", MappingConfidence.HIGH)
            clean in listOf("listing link", "link", "url", "web link", "photos link", "drive link") ->
                Pair("Listing Link", MappingConfidence.HIGH)
            clean in listOf("last updated", "updated at", "update date", "sync date", "audit date") ->
                Pair("Last Updated", MappingConfidence.HIGH)
            clean in listOf("remark", "internal remarks", "agent notes", "private remark", "internal remark", "confidential remark") ->
                Pair("Remark", MappingConfidence.HIGH)
            clean in listOf("portfolio", "brand", "division", "entity", "company") ->
                Pair("Portfolio", MappingConfidence.HIGH)
            clean in listOf("transaction type", "deal type", "trans type", "sale/lease", "transaction") ->
                Pair("Transaction Type", MappingConfidence.HIGH)
            clean in listOf("google maps url", "maps url", "map link", "gmap", "google maps", "location link") ->
                Pair("Google Maps URL", MappingConfidence.HIGH)
            clean in listOf("latitude", "lat") ->
                Pair("Latitude", MappingConfidence.HIGH)
            clean in listOf("longitude", "lng", "long") ->
                Pair("Longitude", MappingConfidence.HIGH)
            clean in listOf("verification status", "verified status", "verification", "audit status") ->
                Pair("Verification Status", MappingConfidence.HIGH)
            clean in listOf("last verified date", "verification date", "verified on", "audit date") ->
                Pair("Last Verified Date", MappingConfidence.HIGH)
            clean in listOf("verified by", "verifier", "audited by", "auditor", "checked by") ->
                Pair("Verified By", MappingConfidence.HIGH)
            clean in listOf("record id", "asset id", "internal id", "system id", "permanent id") ->
                Pair("Record ID", MappingConfidence.HIGH)
            clean in listOf("sync updated at", "synced at", "last sync", "sheet sync time") ->
                Pair("Sync Updated At", MappingConfidence.HIGH)
            clean in listOf("archive status", "archived", "is archived", "active/archived") ->
                Pair("Archive Status", MappingConfidence.HIGH)

            // Medium Confidence Substring heuristics
            clean.contains("carpet") -> Pair("Carpet Area", MappingConfidence.MEDIUM)
            clean.contains("built") -> Pair("Built-up Area", MappingConfidence.MEDIUM)
            clean.contains("sale") || clean.contains("price") -> Pair("Price / Sale", MappingConfidence.MEDIUM)
            clean.contains("rent") -> Pair("Rent", MappingConfidence.MEDIUM)
            clean.contains("phone") || clean.contains("mobile") -> Pair("Contact Number", MappingConfidence.MEDIUM)
            clean.contains("owner") -> Pair("Owner / Contact Person", MappingConfidence.MEDIUM)
            clean.contains("project") || clean.contains("building") -> Pair("Project / Building", MappingConfidence.MEDIUM)
            clean.contains("code") -> Pair("Property Code", MappingConfidence.MEDIUM)
            clean.contains("deposit") -> Pair("Deposit", MappingConfidence.MEDIUM)
            clean.contains("yield") || clean.contains("roi") -> Pair("ROI %", MappingConfidence.MEDIUM)
            clean.contains("furnish") -> Pair("Furnishing", MappingConfidence.MEDIUM)

            // Unmapped Column -> Preserved into Remarks
            else -> Pair("", MappingConfidence.UNMAPPED)
        }
    }

    // Interactive Header Analysis for UI Pre-Commit Review
    fun analyzeHeaders(headers: List<String>, sampleRow: List<String> = emptyList()): List<ColumnMappingItem> {
        return headers.mapIndexed { idx, header ->
            val sample = sampleRow.getOrElse(idx) { "" }
            val (matchedTarget, confidence) = recognizeHeader(header)
            ColumnMappingItem(
                sourceHeader = header,
                targetStandardHeader = matchedTarget,
                sampleValue = sample,
                confidence = confidence,
                isUnmapped = confidence == MappingConfidence.UNMAPPED
            )
        }
    }

    // Commercial Area Loading Calculation (Office: 45%, Shop/Showroom: 50%, Residential: 0%)
    fun calculateCommercialLoading(
        carpet: Double?,
        builtUp: Double?,
        propType: String,
        portfolio: String
    ): Triple<Double, Double, Boolean> {
        val isCommercial = portfolio.contains("Commercial", ignoreCase = true) ||
                propType.contains("Office", ignoreCase = true) ||
                propType.contains("Shop", ignoreCase = true) ||
                propType.contains("Showroom", ignoreCase = true) ||
                propType.contains("Warehouse", ignoreCase = true)

        if (!isCommercial) {
            // Commercial loading percentages must NEVER automatically apply to residential properties
            return Triple(carpet ?: 0.0, builtUp ?: 0.0, false)
        }

        val loadingRatio = when {
            propType.contains("Office", ignoreCase = true) -> 0.45 // 45% default
            propType.contains("Shop", ignoreCase = true) ||
            propType.contains("Showroom", ignoreCase = true) -> 0.50 // 50% default
            else -> 0.45
        }

        var finalCarpet = carpet ?: 0.0
        var finalBuiltUp = builtUp ?: 0.0
        var isEstimated = false

        if (finalCarpet > 0.0 && finalBuiltUp <= 0.0) {
            // Built-up = Carpet * (1 + loading)
            finalBuiltUp = kotlin.math.round(finalCarpet * (1.0 + loadingRatio))
            isEstimated = true
        } else if (finalBuiltUp > 0.0 && finalCarpet <= 0.0) {
            // Carpet = Built-up / (1 + loading)
            finalCarpet = kotlin.math.round(finalBuiltUp / (1.0 + loadingRatio))
            isEstimated = true
        }

        return Triple(finalCarpet, finalBuiltUp, isEstimated)
    }

    // Authoritative 35-Column Property Import Parser
    /** Preserve quoted commas, tabs and escaped quotes when importing spreadsheet rows. */
    private fun splitDelimitedRow(line: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> {
                    fields.add(current.toString().trim())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            index++
        }
        fields.add(current.toString().trim())
        return fields
    }

    /** Preserve line breaks inside quoted CSV fields; reject truncated records. */
    private fun splitDelimitedRecords(content: String): List<String> {
        val records = mutableListOf<String>()
        val record = StringBuilder()
        var quoted = false
        var index = 0
        while (index < content.length) {
            val ch = content[index]
            when {
                ch == '"' && quoted && index + 1 < content.length && content[index + 1] == '"' -> {
                    record.append(ch).append(ch)
                    index++
                }
                ch == '"' -> { quoted = !quoted; record.append(ch) }
                (ch == '\n' || ch == '\r') && !quoted -> {
                    if (record.isNotBlank()) records.add(record.toString())
                    record.setLength(0)
                    if (ch == '\r' && index + 1 < content.length && content[index + 1] == '\n') index++
                }
                else -> record.append(ch)
            }
            index++
        }
        if (quoted) throw IllegalArgumentException("CSV contains an unterminated quoted field")
        if (record.isNotBlank()) records.add(record.toString())
        return records
    }

    /** Detect separators only outside quoted fields. */
    private fun detectDelimiter(record: String): Char {
        var quoted = false
        var commas = 0
        var tabs = 0
        var index = 0
        while (index < record.length) {
            val ch = record[index]
            if (ch == '"' && quoted && index + 1 < record.length && record[index + 1] == '"') {
                index += 2
                continue
            }
            if (ch == '"') quoted = !quoted
            else if (!quoted && ch == ',') commas++
            else if (!quoted && ch == '\t') tabs++
            index++
        }
        return if (tabs > commas) '\t' else ','
    }

    fun parseCsvOrTextData(
        rawContent: String,
        fileName: String = "Pasted_Spreadsheet.csv",
        customHeaderMapping: Map<String, String>? = null
    ): List<ImportPreviewRow> {
        val existingCodes = _properties.value.map { it.propertyCode.uppercase().trim() }.toSet()
        // Reserve generated codes throughout preview parsing; the repository flow is not updated yet.
        val reservedPreviewCodes = existingCodes.toMutableSet()
        val lines = splitDelimitedRecords(rawContent)
        if (lines.isEmpty()) return emptyList()

        val delimiter = detectDelimiter(lines.first()).toString()
        val previewRows = mutableListOf<ImportPreviewRow>()

        // Check if first row is header
        val firstTokens = splitDelimitedRow(lines.first(), delimiter[0])
        val hasHeader = firstTokens.any { header ->
            val (target, conf) = recognizeHeader(header)
            conf != MappingConfidence.UNMAPPED
        }

        val headerColumns = if (hasHeader) firstTokens else emptyList()
        val dataLines = if (hasHeader) lines.drop(1) else lines

        // Pre-compute mapped standard column for each column index
        val columnIndexToStandard = mutableMapOf<Int, String>()
        val unmappedIndices = mutableListOf<Int>()

        if (hasHeader) {
            headerColumns.forEachIndexed { idx, h ->
                val overrideTarget = customHeaderMapping?.get(h)
                if (!overrideTarget.isNullOrBlank()) {
                    columnIndexToStandard[idx] = overrideTarget
                } else {
                    val (target, conf) = recognizeHeader(h)
                    if (conf != MappingConfidence.UNMAPPED && target.isNotBlank()) {
                        columnIndexToStandard[idx] = target
                    } else {
                        unmappedIndices.add(idx)
                    }
                }
            }
        }

        var nextAutoIndex = 1

        dataLines.forEachIndexed { index, line ->
            val cols = splitDelimitedRow(line, delimiter[0])
            if (cols.isNotEmpty() && cols.any { it.isNotBlank() }) {
                val istDateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).apply {
                    timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
                }
                val todayIst = istDateFmt.format(Date())
                var code = ""
                var date = todayIst
                var location = ""
                var microMarket = ""
                var projectName = ""
                var unitNumber = ""
                var floor = ""
                var propType = "Office"
                var subType = ""
                var tab = InventoryTab.OFF_LEASE
                var rawCarpet: Double? = null
                var rawBuiltUp: Double? = null
                var priceSale: Double? = null
                var rent: Double? = null
                var deposit: Double? = null
                var roi: Double? = null
                var possession = ""
                var furnishing = FurnishingType.WARM_SHELL
                var status = AvailabilityStatus.NOT_CONFIRMED
                var pocType = ""
                var owner = ""
                var contact = ""
                var source = fileName
                var remarks = ""
                var listingLink = ""
                var lastUpdated = todayIst
                var internalRemark = ""
                var portfolio = ""
                var transType = ""
                var gmapsUrl = ""
                var latitude: Double? = null
                var longitude: Double? = null
                var verifStatus = VerificationStatus.PENDING
                var hasExplicitVerificationStatus = false
                var hasExplicitStatus = false
                var lastVerifiedDateClean = ""
                var verifiedBy = ""
                var recordId = ""
                var syncUpdatedAt = todayIst
                var archiveStatus = ArchiveStatus.ACTIVE
                var projectId = ""
                var attachedPantry: String? = null
                var attachedWashroom: String? = null
                var frontageFt: Double? = null
                var clearHeightFt: Double? = null
                var truckAccess: String? = null
                var powerLoad: String? = null
                var targetPossessionDate: String? = null
                var groundFloorCarpetSqFt: Double? = null
                var mezzanineCarpetSqFt: Double? = null
                var puneZone: String? = null
                var jurisdiction: String? = null
                var ocStatus: String? = null
                var isPreLeasedFlag = false
                var tenantName: String? = null
                var parkingVal = ""

                val unmappedData = mutableMapOf<String, String>()
                var amenitiesRaw = ""

                if (hasHeader) {
                    cols.forEachIndexed { colIdx, value ->
                        val target = columnIndexToStandard[colIdx]
                        if (target != null) {
                            when (target) {
                                "Property Code" -> if (value.isNotBlank() && !isShiftedAmenityText(value)) code = value
                                "Project ID" -> if (value.isNotBlank()) projectId = value.trim()
                                "Date" -> if (value.isNotBlank()) date = TlcPropertyCalculator.formatDateDdMmYyyy(value)
                                "Location" -> if (value.isNotBlank() && !isShiftedAmenityText(value)) location = value
                                "Micro-Market", "Micro Market" -> if (value.isNotBlank() && !isShiftedAmenityText(value)) microMarket = value
                                "Project / Building" -> if (value.isNotBlank() && !isShiftedAmenityText(value)) projectName = value
                                "Amenities" -> amenitiesRaw = value
                                "Unit / Shop / Office No" -> unitNumber = value
                                "Floor" -> floor = value
                                "Property Type" -> propType = value
                                "Sub Type" -> {
                                    subType = value
                                    tab = inferTab(value, code)
                                }
                                "Carpet Area" -> rawCarpet = parsePlainDecimalOrSci(value)
                                "Ground Floor Carpet (Sq Ft)" -> groundFloorCarpetSqFt = parsePlainDecimalOrSci(value)
                                "Mezzanine Carpet (Sq Ft)" -> mezzanineCarpetSqFt = parsePlainDecimalOrSci(value)
                                "Built-up Area" -> rawBuiltUp = parsePlainDecimalOrSci(value)
                                "Price / Sale" -> priceSale = CalculationUtils.parseCurrencyInput(value)?.let { CalculationUtils.normalizeSalePriceInr(it) }
                                "Rent" -> rent = CalculationUtils.parseCurrencyInput(value)
                                "Deposit" -> deposit = CalculationUtils.parseCurrencyInput(value)
                                "ROI %" -> roi = parsePlainDecimalOrSci(value)
                                "Possession" -> possession = value
                                "Furnishing" -> furnishing = parseFurnishing(value)
                                "Status" -> {
                                    if (value.isNotBlank()) hasExplicitStatus = true
                                    status = parseStatus(value)
                                }
                                "Attached Pantry" -> attachedPantry = TlcPropertyCalculator.normalizeYesNo(value) ?: value.trim().takeIf { it.isNotBlank() }
                                "Attached Washroom" -> attachedWashroom = TlcPropertyCalculator.normalizeYesNo(value) ?: value.trim().takeIf { it.isNotBlank() }
                                "Frontage (Ft)" -> frontageFt = parsePlainDecimalOrSci(value)
                                "Clear Height (Ft)" -> clearHeightFt = parsePlainDecimalOrSci(value)
                                "Truck Access" -> truckAccess = value.trim().takeIf { it.isNotBlank() }
                                "Power Load" -> powerLoad = value.trim().takeIf { it.isNotBlank() }
                                "Target Possession Date" -> targetPossessionDate = TlcPropertyCalculator.formatDateDdMmYyyy(value).takeIf { it.isNotBlank() }
                                "Pune Zone" -> puneZone = value.trim().takeIf { it.isNotBlank() }
                                "Jurisdiction" -> jurisdiction = value.trim().takeIf { it.isNotBlank() }
                                "OC Status" -> ocStatus = value.trim().takeIf { it.isNotBlank() }
                                "Pre-Leased Status" -> isPreLeasedFlag = value.trim().equals("Yes", ignoreCase = true) || value.trim().equals("true", ignoreCase = true)
                                "Tenant Name" -> tenantName = value.trim().takeIf { it.isNotBlank() }
                                "Car Parking" -> if (value.isNotBlank()) parkingVal = value.trim()
                                "Type of POC" -> if (value.isNotBlank()) pocType = normalizePocType(value)
                                "Owner / Contact Person" -> owner = value
                                "Contact Number" -> contact = value
                                "Source" -> if (value.isNotBlank()) source = value
                                "Remarks" -> remarks = value
                                "Listing Link" -> listingLink = value
                                "Last Updated" -> if (value.isNotBlank()) lastUpdated = TlcPropertyCalculator.formatDateDdMmYyyy(value)
                                "Remark" -> internalRemark = value
                                "Portfolio" -> portfolio = value
                                "Transaction Type" -> transType = value
                                "Google Maps URL" -> gmapsUrl = value
                                "Latitude" -> latitude = value.toDoubleOrNull()
                                "Longitude" -> longitude = value.toDoubleOrNull()
                                "Verification Status" -> {
                                    if (value.isNotBlank()) hasExplicitVerificationStatus = true
                                    verifStatus = VerificationStatus.fromString(value)
                                }
                                "Last Verified Date" -> if (value.isNotBlank()) lastVerifiedDateClean = TlcPropertyCalculator.formatDateDdMmYyyy(value)
                                "Verified By" -> verifiedBy = value
                                "Record ID" -> recordId = value
                                "Sync Updated At" -> if (value.isNotBlank()) syncUpdatedAt = TlcPropertyCalculator.formatDateDdMmYyyy(value)
                                "Archive Status" -> archiveStatus = ArchiveStatus.fromString(value)
                            }
                        } else {
                            // Preserve unmapped column header and value
                            if (colIdx < headerColumns.size && value.isNotBlank()) {
                                unmappedData[headerColumns[colIdx]] = value
                            }
                        }
                    }
                    if (amenitiesRaw.isNotBlank()) {
                        unmappedData["Amenities"] = amenitiesRaw
                    }
                    // If a broken multiline row had no project name, no code, and no recordId, skip it
                    if (projectName.isBlank() && code.isBlank() && recordId.isBlank()) {
                        return@forEachIndexed
                    }
                } else {
                    // Never use positional mapping for Commercial Inventory or Residential Inventory tabs
                    if (fileName.equals("Commercial Inventory", ignoreCase = true) ||
                        fileName.equals("Residential Inventory", ignoreCase = true) ||
                        fileName.contains("Projects", ignoreCase = true)
                    ) {
                        return@forEachIndexed
                    }
                    // Positional mapping for standard 35 columns only when row is not a shifted amenity fragment
                    val rawCol0 = cols.getOrElse(0) { "" }
                    val rawCol2 = cols.getOrElse(2) { "Pune" }
                    if (isShiftedAmenityText(rawCol0) || isShiftedAmenityText(rawCol2)) {
                        return@forEachIndexed
                    }
                    code = rawCol0
                    date = cols.getOrElse(1) { date }
                    location = rawCol2
                    projectName = cols.getOrElse(3) { "Project ${index + 1}" }
                    unitNumber = cols.getOrElse(4) { "Unit ${index + 1}" }
                    floor = cols.getOrElse(5) { "Mid Floor" }
                    propType = cols.getOrElse(6) { "Commercial" }
                    subType = cols.getOrElse(7) { "" }
                    tab = inferTab(subType, code)
                    rawCarpet = parsePlainDecimalOrSci(cols.getOrElse(8) { "" })
                    rawBuiltUp = parsePlainDecimalOrSci(cols.getOrElse(9) { "" })
                    priceSale = CalculationUtils.parseCurrencyInput(cols.getOrElse(10) { "" })?.let { CalculationUtils.normalizeSalePriceInr(it) }
                    rent = CalculationUtils.parseCurrencyInput(cols.getOrElse(11) { "" })
                    deposit = CalculationUtils.parseCurrencyInput(cols.getOrElse(12) { "" })
                    roi = parsePlainDecimalOrSci(cols.getOrElse(13) { "" })
                    possession = cols.getOrElse(14) { "Immediate" }
                    furnishing = parseFurnishing(cols.getOrElse(15) { "" })
                    val rawStatusCol = cols.getOrElse(16) { "" }
                    if (rawStatusCol.isNotBlank()) hasExplicitStatus = true
                    status = parseStatus(rawStatusCol)
                    owner = cols.getOrElse(17) { "" }
                    contact = cols.getOrElse(18) { "" }
                    source = cols.getOrElse(19) { fileName }
                    remarks = cols.getOrElse(20) { "" }
                    listingLink = cols.getOrElse(21) { "" }
                    lastUpdated = cols.getOrElse(22) { lastUpdated }
                    internalRemark = cols.getOrElse(23) { "" }
                    portfolio = cols.getOrElse(24) { if (tab.brand == Brand.RESIDENTIAL) "Residential" else "Commercial" }
                    transType = cols.getOrElse(25) { if (tab.isRental) "Lease" else "Sale" }
                    gmapsUrl = cols.getOrElse(26) { "" }
                    latitude = cols.getOrElse(27) { "" }.toDoubleOrNull()
                    longitude = cols.getOrElse(28) { "" }.toDoubleOrNull()
                    val rawVerifCol = cols.getOrElse(29) { "" }
                    if (rawVerifCol.isNotBlank()) hasExplicitVerificationStatus = true
                    verifStatus = VerificationStatus.fromString(rawVerifCol)
                    lastVerifiedDateClean = cols.getOrElse(30) { lastVerifiedDateClean }
                    verifiedBy = cols.getOrElse(31) { "" }
                    recordId = cols.getOrElse(32) { "" }
                    syncUpdatedAt = cols.getOrElse(33) { syncUpdatedAt }
                    archiveStatus = ArchiveStatus.fromString(cols.getOrElse(34) { "" })
                }

                // Keep Location and Micro-Market distinct; only fallback if Location itself is blank
                if (microMarket.isNotBlank() && location.isBlank()) {
                    location = microMarket
                }

                // Append all unmapped columns to remarks so zero source info is discarded
                if (unmappedData.isNotEmpty()) {
                    val extraText = "[Extra: " + unmappedData.entries.joinToString("; ") { "${it.key} = ${it.value}" } + "]"
                    remarks = if (remarks.isNotBlank()) "$remarks\n$extraText" else extraText
                }

                val classifiedTab = if (isPreLeasedFlag && !portfolio.contains("Residential", ignoreCase = true)) {
                    InventoryTab.PRE_LEASED
                } else {
                    classifyImportTab(
                        subType, code, propType, portfolio, transType, fileName, priceSale, rent
                    )
                }
                // The model requires a tab, but ambiguous rows must stay in review.
                tab = classifiedTab ?: InventoryTab.OFF_LEASE
                portfolio = if (tab.brand == Brand.RESIDENTIAL) "Residential" else "Commercial"
                transType = when {
                    tab == InventoryTab.PRE_LEASED || isPreLeasedFlag -> "Pre-Leased Sale"
                    tab.isRental -> "Lease"
                    else -> "Sale"
                }

                val effectiveInputCarpet = rawCarpet
                    ?: if (TlcPropertyCalculator.isShopOrShowroom(propType, tab)) {
                        val g = groundFloorCarpetSqFt ?: 0.0
                        val m = mezzanineCarpetSqFt ?: 0.0
                        (g + m).takeIf { it > 0.0 }
                    } else {
                        groundFloorCarpetSqFt
                    }

                // Commercial Area Loading Calculations
                val (carpetArea, builtUpArea, isAreaEstimated) = calculateCommercialLoading(
                    carpet = effectiveInputCarpet,
                    builtUp = rawBuiltUp,
                    propType = propType,
                    portfolio = portfolio
                )

                // Price and Rent resolution
                val finalPriceOrRent = when {
                    tab.isRental -> rent ?: (priceSale ?: 0.0)
                    else -> priceSale ?: (rent ?: 0.0)
                }

                // Reuse the code assigned to the same permanent source record on
                // previous imports. Missing legacy codes must not create duplicates.
                if (code.isBlank() && recordId.isNotBlank()) {
                    code = _properties.value.firstOrNull {
                        it.recordId.equals(recordId.trim(), ignoreCase = true)
                    }?.propertyCode.orEmpty()
                }

                // Code Generation if missing
                if (code.isBlank()) {
                    val prefix = when (tab) {
                        InventoryTab.OFF_LEASE -> "OL"
                        InventoryTab.OFF_OUT -> "OO"
                        InventoryTab.SHOP_LEASE -> "SL"
                        InventoryTab.SHOP_OUT -> "SO"
                        InventoryTab.PRE_LEASED -> "PL"
                        InventoryTab.RESI_RENT -> "RR"
                        InventoryTab.RESI_RESALE -> "RS"
                        InventoryTab.UND_CONST -> "UC"
                    }
                    val nextNumber = (100..999999).firstOrNull { candidate ->
                        "$prefix-PUN-$candidate" !in reservedPreviewCodes
                    } ?: throw IllegalStateException("No available preview property codes for $prefix")
                    code = "$prefix-PUN-$nextNumber"
                }
                reservedPreviewCodes.add(code.uppercase(Locale.ENGLISH).trim())

                // Permanent Record ID (AST-xxxxxx) if missing
                if (recordId.isBlank()) {
                    val codeNumber = code.filter { it.isDigit() }.padStart(6, '0').takeLast(6)
                    recordId = if (codeNumber.isNotBlank() && codeNumber != "000000") "AST-$codeNumber" else "AST-${String.format(Locale.ENGLISH, "%06d", nextAutoIndex++)}"
                }

                // Validate uniqueness and duplicate check
                val isDuplicate = existingCodes.contains(code.uppercase().trim())
                val isLocationValid = location.isNotBlank()
                val isPriceValid = finalPriceOrRent >= 0.0
                val isValid = isLocationValid && isPriceValid && classifiedTab != null

                val valMessage = when {
                    !isLocationValid -> "Missing Location: Sent to DATA_REVIEW"
                    !isPriceValid -> "Invalid Negative Price/Rent: Sent to DATA_REVIEW"
                    classifiedTab == null -> "Ambiguous property classification: Sent to DATA_REVIEW"
                    isDuplicate -> "Duplicate Property Code detected ($code)"
                    isAreaEstimated -> "Commercial loading applied (${if (propType.contains("Shop", ignoreCase = true)) "50%" else "45%"} Estimated Area)"
                    else -> "Standard 35-Column Record Validated"
                }

                // If invalid or ambiguous, log to DATA_REVIEW queue
                if (!isValid) {
                    addReviewRow(
                        sourceFile = fileName,
                        rowNumber = index + (if (hasHeader) 2 else 1),
                        rawData = line,
                        issueReason = when {
                            !isLocationValid -> "MISSING_LOCATION"
                            !isPriceValid -> "INVALID_PRICE"
                            else -> "AMBIGUOUS_CLASSIFICATION"
                        },
                        suggestedCode = code
                    )
                }

                previewRows.add(
                    ImportPreviewRow(
                        rowNumber = index + (if (hasHeader) 2 else 1),
                        propertyCode = code.trim(),
                        projectName = projectName.trim(),
                        location = location.ifBlank { "Unverified Location" }.trim(),
                        unitNumber = unitNumber.trim(),
                        floor = floor,
                        propertyType = StandardPropertyType.fromString(propType).label,
                        tab = tab,
                        carpetAreaSqFt = carpetArea,
                        builtUpAreaSqFt = if (builtUpArea > 0) builtUpArea else null,
                        priceOrRent = finalPriceOrRent,
                        priceSale = if (!tab.isRental) finalPriceOrRent else priceSale,
                        rent = if (tab.isRental) finalPriceOrRent else rent,
                        deposit = deposit,
                        roiPercent = roi,
                        possession = possession,
                        furnishing = furnishing,
                        status = status,
                        ownerName = owner.ifBlank { null },
                        ownerContact = contact.ifBlank { null },
                        sourceInfo = source.ifBlank { fileName },
                        remarks = remarks.ifBlank { null },
                        listingLink = listingLink.ifBlank { null },
                        isDuplicateCode = isDuplicate,
                        isValid = isValid,
                        validationMessage = valMessage,
                        // 11 Appended Columns
                        recordId = recordId,
                        portfolio = portfolio,
                        transactionType = transType,
                        googleMapsUrl = gmapsUrl.ifBlank { null },
                        latitude = latitude,
                        longitude = longitude,
                        verificationStatus = verifStatus,
                        hasExplicitVerificationStatus = hasExplicitVerificationStatus,
                        hasExplicitStatus = hasExplicitStatus,
                        lastVerifiedDateClean = lastVerifiedDateClean,
                        verifiedBy = verifiedBy.ifBlank { null },
                        isAreaEstimated = isAreaEstimated,
                        unmappedColumns = unmappedData,
                        conflictResolution = if (isDuplicate) "REVIEW" else "INSERT",
                        pocType = pocType,
                        archiveStatus = archiveStatus,
                        lastUpdated = lastUpdated.ifBlank { syncUpdatedAt },
                        microMarket = microMarket.trim().takeIf { it.isNotBlank() },
                        projectId = projectId.trim().takeIf { it.isNotBlank() },
                        attachedPantry = attachedPantry,
                        attachedWashroom = attachedWashroom,
                        frontageFt = frontageFt,
                        clearHeightFt = clearHeightFt,
                        truckAccess = truckAccess,
                        powerLoad = powerLoad,
                        targetPossessionDate = targetPossessionDate,
                        groundFloorCarpetSqFt = groundFloorCarpetSqFt,
                        mezzanineCarpetSqFt = if (TlcPropertyCalculator.isShopOrShowroom(propType, tab)) mezzanineCarpetSqFt else null,
                        puneZone = puneZone,
                        jurisdiction = jurisdiction,
                        ocStatus = ocStatus,
                        isPreLeased = isPreLeasedFlag || tab == InventoryTab.PRE_LEASED,
                        tenantName = tenantName,
                        parking = parkingVal
                    )
                )
            }
        }
        return previewRows
    }

    private fun parseFurnishing(value: String): FurnishingType {
        val v = value.lowercase()
        return when {
            v.contains("plug") || v.contains("full") -> FurnishingType.FULLY_FURNISHED
            v.contains("semi") -> FurnishingType.SEMI_FURNISHED
            v.contains("warm") -> FurnishingType.WARM_SHELL
            v.contains("bare") || v.contains("unfurnish") -> FurnishingType.BARE_SHELL
            else -> FurnishingType.UNFURNISHED
        }
    }

    private fun parseStatus(value: String): AvailabilityStatus = AvailabilityStatus.parse(value)

    private fun isShiftedAmenityText(value: String): Boolean {
        val v = value.trim().lowercase(Locale.ENGLISH)
        if (v.isBlank()) return false
        return listOf(
            "dg backup",
            "power backup",
            "business pod",
            "high-speed elevator",
            "bms security",
            "multi-level parking",
            "ev charging",
            "food court",
            "cctv",
            "swimming pool",
            "clubhouse",
            "gymnasium"
        ).any { v.contains(it) }
    }

    private fun parsePlainDecimalOrSci(raw: String): Double? {
        val trimmed = raw.trim().replace(",", "")
        if (trimmed.isBlank()) return null
        trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
        val m = Regex("""([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""").find(trimmed) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun extractImageUrlFromLink(link: String?): List<String> {
        val clean = link?.trim().orEmpty()
        if (clean.isBlank()) return emptyList()
        val lower = clean.lowercase(Locale.ENGLISH)
        val isImg = lower.contains("drive.google.com/uc?") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp")
        return if (isImg) listOf(clean) else emptyList()
    }

    private fun sanitizeShiftedInventoryRecords(appContext: Context) {
        val current = _properties.value
        if (current.isEmpty()) return
        var modified = false
        val cleaned = current.mapNotNull { prop ->
            val src = prop.sourceInfo.orEmpty()
            if (src.equals("Commercial Projects", ignoreCase = true) ||
                src.equals("Residential Projects", ignoreCase = true)
            ) {
                modified = true
                return@mapNotNull null
            }
            if (isShiftedAmenityText(prop.propertyCode) || isShiftedAmenityText(prop.projectName)) {
                modified = true
                return@mapNotNull null
            }
            var updated = prop
            if (isShiftedAmenityText(updated.location)) {
                modified = true
                val fallbackLoc = updated.unmappedColumns["Micro Market"]
                    ?.takeIf { it.isNotBlank() && !isShiftedAmenityText(it) }
                    ?: "Kharadi"
                updated = updated.copy(location = fallbackLoc)
            }
            // Repair truncated INR Crore numbers (e.g., 14.0 or 22.0 from "14 Cr" / "22 Cr")
            val rawSaleText = updated.unmappedColumns["Sale Price INR"]
                ?: updated.unmappedColumns["Price / Sale"]
                ?: updated.unmappedColumns["Asking Price"]
            val parsedUnmappedSale = rawSaleText?.let { CalculationUtils.parseCurrencyInput(it) }
                ?.let { CalculationUtils.normalizeSalePriceInr(it) }
            val currentSale = updated.priceSale ?: 0.0
            val needsSaleHeal = currentSale in 0.25..500.0 ||
                (!updated.tab.isRental && updated.priceOrRent in 0.25..500.0)
            if (needsSaleHeal) {
                val healedSale = when {
                    parsedUnmappedSale != null && parsedUnmappedSale >= 10_000.0 -> parsedUnmappedSale
                    currentSale in 0.25..500.0 -> CalculationUtils.normalizeSalePriceInr(currentSale)
                    !updated.tab.isRental && updated.priceOrRent in 0.25..500.0 ->
                        CalculationUtils.normalizeSalePriceInr(updated.priceOrRent)
                    else -> currentSale
                }
                if (healedSale >= 10_000.0) {
                    modified = true
                    updated = updated.copy(
                        priceSale = healedSale,
                        priceOrRent = if (!updated.tab.isRental) healedSale else updated.priceOrRent
                    )
                }
            }
            updated
        }
        if (modified) {
            _properties.value = cleaned
            PropertiesLocalDatabase.saveProperties(appContext, cleaned)
            OfflineDiskCache.saveInventorySplits(appContext, cleaned)
        }
    }


    /**
     * Preserve an explicit legacy category first. Three-tab records without a
     * legacy code use division/type and transaction evidence, not Office Lease.
     * Conflicting/missing sale-versus-lease evidence requires review.
     */
    private fun classifyImportTab(
        subType: String, code: String, propertyType: String, portfolio: String,
        transaction: String, sourceName: String, sale: Double?, rent: Double?
    ): InventoryTab? {
        val prefix = code.trim().uppercase(Locale.ENGLISH).substringBefore("-")
        val legacy = when (prefix) {
            "OL" -> InventoryTab.OFF_LEASE
            "OO" -> InventoryTab.OFF_OUT
            "SL" -> InventoryTab.SHOP_LEASE
            "SO" -> InventoryTab.SHOP_OUT
            "PL" -> InventoryTab.PRE_LEASED
            "RR" -> InventoryTab.RESI_RENT
            "RS" -> InventoryTab.RESI_RESALE
            "UC" -> InventoryTab.UND_CONST
            else -> null
        }
        if (legacy != null) return legacy
        val subtype = subType.lowercase(Locale.ENGLISH)
        val type = propertyType.lowercase(Locale.ENGLISH)
        val division = portfolio.lowercase(Locale.ENGLISH)
        val source = sourceName.lowercase(Locale.ENGLISH)
        val tx = transaction.lowercase(Locale.ENGLISH)
        val residential = when {
            division.contains("residential") || division.contains("aurea") -> true
            division.contains("commercial") || division.contains("ascend") -> false
            source.contains("residential") -> true
            source.contains("commercial") || source.contains("pre-leased") || source.contains("pre leased") -> false
            else -> listOf("apartment", "flat", "villa", "bungalow", "residential",
                "bhk", "resi", "und const", "under const").any { type.contains(it) || subtype.contains(it) }
        }
        if (listOf("pre-leased", "pre leased", "pre - leased").any { subtype.contains(it) || tx.contains(it) || source.contains(it) }) {
            return if (residential) null else InventoryTab.PRE_LEASED
        }
        if (residential && (subtype.contains("under const") || subtype.contains("und const") || source.contains("under const"))) {
            return InventoryTab.UND_CONST
        }
        val evidence = "$subtype $tx $source"
        val lease = evidence.contains("lease") || evidence.contains("rent")
        val purchase = evidence.contains("sale") || evidence.contains("buy") ||
            evidence.contains("purchase") || evidence.contains("outright") || evidence.contains("off out") ||
            evidence.contains("shop out")
        val rental = when {
            lease && !purchase -> true
            purchase && !lease -> false
            lease && purchase -> return null
            (rent ?: 0.0) > 0 && (sale ?: 0.0) <= 0 -> true
            (sale ?: 0.0) > 0 && (rent ?: 0.0) <= 0 -> false
            else -> return null
        }
        val retail = type.contains("shop") || type.contains("showroom") || type.contains("retail") ||
            subtype.contains("shop") || subtype.contains("retail")
        return when {
            residential && rental -> InventoryTab.RESI_RENT
            residential -> InventoryTab.RESI_RESALE
            retail && rental -> InventoryTab.SHOP_LEASE
            retail -> InventoryTab.SHOP_OUT
            rental -> InventoryTab.OFF_LEASE
            else -> InventoryTab.OFF_OUT
        }
    }

    private fun inferTab(text: String, code: String): InventoryTab {
        val combined = "$text $code".uppercase()
        return when {
            combined.contains("PRE - LEASED") || combined.contains("PRE-LEASED") || combined.contains("PRE LEASED") || combined.startsWith("PL-") -> InventoryTab.PRE_LEASED
            combined.contains("COMMERCIAL LEASE") || combined.contains("OFF LEASE") || combined.contains("OFFICE LEASE") || combined.startsWith("OL-") || combined.contains(" OL ") -> InventoryTab.OFF_LEASE
            combined.contains("COMMERCIAL SALE") || combined.contains("OFF OUT") || combined.contains("OFFICE SALE") || combined.startsWith("OO-") || combined.contains(" OO ") -> InventoryTab.OFF_OUT
            combined.contains("SHOP LEASE") || combined.contains("RETAIL LEASE") || combined.startsWith("SL-") || combined.contains(" SL ") -> InventoryTab.SHOP_LEASE
            combined.contains("SHOP OUT") || combined.contains("RETAIL SALE") || combined.startsWith("SO-") || combined.contains(" SO ") -> InventoryTab.SHOP_OUT
            combined.contains("RESIDENTIAL LEASE") || combined.contains("RESI RENT") || combined.contains("RESIDENTIAL RENT") || combined.startsWith("RR-") || combined.contains(" RR ") -> InventoryTab.RESI_RENT
            combined.contains("RESIDENTIAL SALE") || combined.contains("RESI RESALE") || combined.contains("RESIDENTIAL RESALE") || combined.startsWith("RS-") || combined.contains(" RS ") -> InventoryTab.RESI_RESALE
            combined.contains("UND CONST") || combined.contains("UNDER CONST") || combined.startsWith("UC-") || combined.contains(" UC ") -> InventoryTab.UND_CONST
            else -> InventoryTab.OFF_LEASE
        }
    }

    /**
     * Disabled: Local bundled CSV/sample inventory must NEVER auto-seed on startup or empty DB.
     * An empty Google Sheet must remain empty in the app.
     */
    fun bootstrapBundledActualInventory(): SyncStats {
        return SyncStats(0, 0, 0, 0, 0)
    }

    // Comprehensive Master Demo Portfolio covering all 8 tabs
    fun loadLargeMasterDemoPortfolio(): List<ImportPreviewRow> {
        return emptyList()
    }

    // Inventory Import Engine
    fun generateImportPreview(fileName: String, rawContent: String = ""): List<ImportPreviewRow> {
        if (rawContent.isNotBlank()) {
            return parseCsvOrTextData(rawContent, fileName)
        }
        return emptyList()
    }

    data class SyncStats(
        val recordsFound: Int,
        val addedCount: Int,
        val updatedCount: Int,
        val skippedCount: Int,
        val failedCount: Int
    )

    @Synchronized
    fun importRowsDetailed(
        rows: List<ImportPreviewRow>,
        duplicateHandling: String = "SKIP",
        archiveUnverified: Boolean = false
    ): SyncStats {
        if (duplicateHandling != "SKIP" && duplicateHandling != "SYNC_MERGE" && !allowed("BULK_EDIT")) return SyncStats(rows.size, 0, 0, 0, rows.size)
        val today = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date())
        var addedCount = 0
        var updatedCount = 0
        var skippedCount = 0
        var failedCount = 0
        val originalList = _properties.value
        val currentList = originalList.toMutableList()
        val pendingEntityIds = context?.let { ctx ->
            val opsIds = PendingSyncOpStore.loadAll(ctx).map { it.entityId }
            val writeIds = PropertiesLocalDatabase.loadPendingWrites(ctx).map { it.property.id }
            (opsIds + writeIds).toSet()
        }.orEmpty()

        rows.forEach { row ->
            if (!row.isValid) {
                failedCount++
                return@forEach
            }

            val rowRecordId = row.recordId.trim()
            val rowCode = row.propertyCode.trim()
            val idx = currentList.indexOfFirst { existing ->
                (rowRecordId.isNotBlank() && (
                    existing.recordId.equals(rowRecordId, ignoreCase = true) ||
                        existing.id.equals(rowRecordId, ignoreCase = true)
                    )) ||
                    (rowCode.isNotBlank() && existing.propertyCode.equals(rowCode, ignoreCase = true))
            }
            val isDuplicate = idx >= 0

            if (isDuplicate) {
                when (duplicateHandling) {
                    "SYNC_MERGE" -> {
                        val existing = currentList[idx]
                        // Conflict & Deletion Safety: NEVER overwrite a local record with pendingSync == true (!isSyncedToSheet)
                        val hasUnsyncedLocalEdit = !existing.isSyncedToSheet || existing.id in pendingEntityIds
                        val localUpdatedMs = parseComparableTimestampMillis(existing.updatedAt.ifBlank { existing.syncUpdatedAt })
                        val sheetUpdatedMs = parseComparableTimestampMillis(row.lastUpdated)
                        val localSyncedIsStrictlyNewer = existing.isSyncedToSheet &&
                            localUpdatedMs > 0L && sheetUpdatedMs > 0L && localUpdatedMs > sheetUpdatedMs
                        if (hasUnsyncedLocalEdit || localSyncedIsStrictlyNewer) {
                            skippedCount++
                        } else {
                            val remoteImgs = extractImageUrlFromLink(row.listingLink)
                            val mergedImages = if (existing.images.isNotEmpty()) existing.images else remoteImgs
                            val mergedUpdated = row.lastUpdated.ifBlank {
                                SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
                            }
                            val mergedLastVerified = row.lastVerifiedDateClean.ifBlank { existing.lastVerifiedDateClean }
                            currentList[idx] = existing.copy(
                                projectName = if (row.projectName.isNotBlank()) row.projectName else existing.projectName,
                                location = if (row.location.isNotBlank() && row.location != "Unverified Location") row.location else existing.location,
                                unitNumber = if (row.unitNumber.isNotBlank()) row.unitNumber else existing.unitNumber,
                                floor = if (row.floor.isNotBlank()) row.floor else existing.floor,
                                propertyType = if (row.propertyType.isNotBlank()) row.propertyType else existing.propertyType,
                                tab = row.tab,
                                carpetAreaSqFt = if (row.carpetAreaSqFt > 0.0) row.carpetAreaSqFt else existing.carpetAreaSqFt,
                                builtUpAreaSqFt = row.builtUpAreaSqFt ?: existing.builtUpAreaSqFt,
                                priceOrRent = if (row.priceOrRent > 0.0) row.priceOrRent else existing.priceOrRent,
                                priceSale = row.priceSale ?: existing.priceSale,
                                rent = row.rent ?: existing.rent,
                                deposit = row.deposit ?: existing.deposit,
                                roiPercent = row.roiPercent ?: existing.roiPercent,
                                possession = if (row.possession.isNotBlank()) row.possession else existing.possession,
                                furnishing = row.furnishing,
                                // Preserve local verified/confirmed status when Sheet cells are blank
                                // so app launch sync never resets Live/Verified properties back to Pending.
                                status = if (row.hasExplicitStatus) row.status else existing.status,
                                verificationStatus = if (row.hasExplicitVerificationStatus) {
                                    row.verificationStatus
                                } else {
                                    existing.verificationStatus
                                },
                                archiveStatus = row.archiveStatus,
                                lastVerifiedDateClean = mergedLastVerified,
                                lastVerificationDate = mergedLastVerified.ifBlank { existing.lastVerificationDate },
                                verifiedBy = row.verifiedBy?.takeIf { it.isNotBlank() } ?: existing.verifiedBy,
                                ownerName = row.ownerName?.takeIf { it.isNotBlank() } ?: existing.ownerName,
                                ownerContact = row.ownerContact?.takeIf { it.isNotBlank() } ?: existing.ownerContact,
                                sourceInfo = row.sourceInfo?.takeIf { it.isNotBlank() } ?: existing.sourceInfo,
                                remarks = row.remarks?.takeIf { it.isNotBlank() } ?: existing.remarks,
                                listingLink = row.listingLink?.takeIf { it.isNotBlank() } ?: existing.listingLink,
                                images = mergedImages,
                                portfolio = row.portfolio.ifBlank { existing.portfolio },
                                transactionType = row.transactionType.ifBlank { existing.transactionType },
                                googleMapsUrl = row.googleMapsUrl?.takeIf { it.isNotBlank() } ?: existing.googleMapsUrl,
                                latitude = row.latitude ?: existing.latitude,
                                longitude = row.longitude ?: existing.longitude,
                                recordId = existing.recordId.ifBlank { rowRecordId },
                                updatedAt = mergedUpdated,
                                syncUpdatedAt = mergedUpdated,
                                isSyncedToSheet = true,
                                isAreaEstimated = row.isAreaEstimated,
                                pocType = row.pocType.ifBlank { existing.pocType }
                            )
                            updatedCount++
                        }
                    }
                    "OVERWRITE" -> {
                        val existing = currentList[idx]
                        currentList[idx] = existing.copy(
                            projectName = if (row.projectName.isNotBlank()) row.projectName else existing.projectName,
                            location = if (row.location.isNotBlank()) row.location else existing.location,
                            unitNumber = if (row.unitNumber.isNotBlank()) row.unitNumber else existing.unitNumber,
                            floor = if (row.floor.isNotBlank()) row.floor else existing.floor,
                            propertyType = if (row.propertyType.isNotBlank()) row.propertyType else existing.propertyType,
                            tab = row.tab,
                            carpetAreaSqFt = if (row.carpetAreaSqFt > 0) row.carpetAreaSqFt else existing.carpetAreaSqFt,
                            builtUpAreaSqFt = row.builtUpAreaSqFt ?: existing.builtUpAreaSqFt,
                            priceOrRent = if (row.priceOrRent > 0) row.priceOrRent else existing.priceOrRent,
                            priceSale = row.priceSale ?: existing.priceSale,
                            rent = row.rent ?: existing.rent,
                            deposit = row.deposit ?: existing.deposit,
                            roiPercent = row.roiPercent ?: existing.roiPercent,
                            possession = if (row.possession.isNotBlank()) row.possession else existing.possession,
                            furnishing = row.furnishing,
                            status = if (row.hasExplicitStatus) row.status else existing.status,
                            ownerName = row.ownerName ?: existing.ownerName,
                            ownerContact = row.ownerContact ?: existing.ownerContact,
                            sourceInfo = row.sourceInfo ?: existing.sourceInfo,
                            remarks = row.remarks ?: existing.remarks,
                            listingLink = row.listingLink ?: existing.listingLink,
                            lastVerificationDate = existing.lastVerificationDate,
                            isSyncedToSheet = true,
                            // 11 Appended Columns (25-35)
                            portfolio = row.portfolio,
                            transactionType = row.transactionType,
                            googleMapsUrl = row.googleMapsUrl ?: existing.googleMapsUrl,
                            latitude = row.latitude ?: existing.latitude,
                            longitude = row.longitude ?: existing.longitude,
                            verificationStatus = if (row.hasExplicitVerificationStatus) {
                                row.verificationStatus
                            } else {
                                existing.verificationStatus
                            },
                            lastVerifiedDateClean = row.lastVerifiedDateClean.ifBlank { existing.lastVerifiedDateClean },
                            verifiedBy = row.verifiedBy ?: existing.verifiedBy,
                            recordId = if (existing.recordId.isNotBlank()) existing.recordId else row.recordId,
                            syncUpdatedAt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date()),
                            // An explicit overwrite must not silently reactivate an archived record.
                            archiveStatus = existing.archiveStatus,
                            isAreaEstimated = row.isAreaEstimated,
                            pocType = row.pocType.ifBlank { existing.pocType },
                            microMarket = row.microMarket ?: existing.microMarket,
                            projectId = row.projectId?.takeIf { it.isNotBlank() } ?: existing.projectId,
                            attachedPantry = row.attachedPantry ?: existing.attachedPantry,
                            attachedWashroom = row.attachedWashroom ?: existing.attachedWashroom,
                            frontageFt = row.frontageFt ?: existing.frontageFt,
                            frontageFeet = row.frontageFt ?: existing.frontageFeet,
                            clearHeightFt = row.clearHeightFt ?: existing.clearHeightFt,
                            ceilingHeightFeet = row.clearHeightFt ?: existing.ceilingHeightFeet,
                            truckAccess = row.truckAccess ?: existing.truckAccess,
                            powerLoad = row.powerLoad ?: existing.powerLoad,
                            targetPossessionDate = row.targetPossessionDate ?: existing.targetPossessionDate,
                            groundFloorCarpetSqFt = row.groundFloorCarpetSqFt ?: existing.groundFloorCarpetSqFt,
                            mezzanineCarpetSqFt = row.mezzanineCarpetSqFt ?: existing.mezzanineCarpetSqFt,
                            puneZone = row.puneZone ?: existing.puneZone,
                            jurisdiction = row.jurisdiction ?: existing.jurisdiction,
                            ocStatus = row.ocStatus ?: existing.ocStatus,
                            isPreLeased = row.isPreLeased || existing.isPreLeased,
                            tenantName = row.tenantName ?: existing.tenantName,
                            parking = row.parking.ifBlank { existing.parking }
                        )
                        updatedCount++
                    }
                    "NEW_CODE" -> {
                        // Reserve against the working list, including rows imported earlier in this batch.
                        // The general code generator is randomized and cannot guarantee uniqueness.
                        val prefix = when (row.tab) {
                            InventoryTab.OFF_LEASE -> "OL"
                            InventoryTab.OFF_OUT -> "OO"
                            InventoryTab.SHOP_LEASE -> "SL"
                            InventoryTab.SHOP_OUT -> "SO"
                            InventoryTab.PRE_LEASED -> "PL"
                            InventoryTab.RESI_RENT -> "RR"
                            InventoryTab.RESI_RESALE -> "RS"
                            InventoryTab.UND_CONST -> "UC"
                        }
                        val usedCodes = currentList.map { it.propertyCode.trim().uppercase(Locale.ENGLISH) }.toHashSet()
                        val nextNumber = (100..999999).firstOrNull { candidate ->
                            "$prefix-PUN-$candidate" !in usedCodes
                        }
                        if (nextNumber == null) {
                            failedCount++
                            return@forEach
                        }
                        val newCode = "$prefix-PUN-$nextNumber"
                        val codeNumber = newCode.filter { it.isDigit() }.padStart(6, '0').takeLast(6).ifBlank { (100000..999999).random().toString() }
                        val newProp = PropertyItem(
                            id = "prop_imp_${System.currentTimeMillis()}_${row.rowNumber}_${(100..999).random()}",
                            propertyCode = newCode,
                            date = today,
                            location = row.location,
                            projectName = row.projectName,
                            unitNumber = row.unitNumber,
                            floor = row.floor,
                            propertyType = row.propertyType,
                            tab = row.tab,
                            carpetAreaSqFt = row.carpetAreaSqFt,
                            builtUpAreaSqFt = row.builtUpAreaSqFt,
                            priceOrRent = row.priceOrRent,
                            priceSale = row.priceSale,
                            rent = row.rent,
                            deposit = row.deposit,
                            roiPercent = row.roiPercent,
                            possession = row.possession,
                            furnishing = row.furnishing,
                            status = row.status,
                            ownerName = row.ownerName,
                            ownerContact = row.ownerContact,
                            sourceInfo = row.sourceInfo ?: "Master Spreadsheet Import",
                            remarks = row.remarks,
                            listingLink = row.listingLink,
                            isSyncedToSheet = true,
                            lastVerificationDate = "",
                            portfolio = row.portfolio,
                            transactionType = row.transactionType,
                            googleMapsUrl = row.googleMapsUrl,
                            latitude = row.latitude,
                            longitude = row.longitude,
                            verificationStatus = row.verificationStatus,
                            lastVerifiedDateClean = row.lastVerifiedDateClean,
                            verifiedBy = row.verifiedBy,
                            recordId = "AST-$codeNumber",
                            syncUpdatedAt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date()),
                            archiveStatus = ArchiveStatus.ACTIVE,
                            isAreaEstimated = row.isAreaEstimated,
                            images = extractImageUrlFromLink(row.listingLink),
                            pocType = row.pocType,
                            microMarket = row.microMarket,
                            projectId = row.projectId.orEmpty(),
                            attachedPantry = row.attachedPantry,
                            attachedWashroom = row.attachedWashroom,
                            frontageFt = row.frontageFt,
                            frontageFeet = row.frontageFt,
                            clearHeightFt = row.clearHeightFt,
                            ceilingHeightFeet = row.clearHeightFt,
                            truckAccess = row.truckAccess,
                            powerLoad = row.powerLoad,
                            targetPossessionDate = row.targetPossessionDate,
                            groundFloorCarpetSqFt = row.groundFloorCarpetSqFt,
                            mezzanineCarpetSqFt = row.mezzanineCarpetSqFt,
                            puneZone = row.puneZone,
                            jurisdiction = row.jurisdiction,
                            ocStatus = row.ocStatus,
                            isPreLeased = row.isPreLeased,
                            tenantName = row.tenantName,
                            parking = row.parking
                        )
                        currentList.add(0, newProp)
                        addedCount++
                    }
                    else -> {
                        // SKIP duplicate, but non-destructively heal previously truncated Cr/Lac/scientific prices, missing Drive photo links & Type of POC
                        val existing = currentList[idx]
                        val curPrimary = CalculationUtils.primaryAmountForProperty(existing)
                        val remPrimary = if (row.tab.isRental) (row.rent ?: row.priceOrRent) else (row.priceSale ?: row.priceOrRent)
                        val needsPriceHeal = remPrimary >= 10_000.0 && curPrimary in 0.0..999.0 && curPrimary != remPrimary
                        val remoteImgs = extractImageUrlFromLink(row.listingLink)
                        val needsPhotoHeal = existing.images.isEmpty() && remoteImgs.isNotEmpty()
                        val needsPocTypeHeal = existing.pocType.isBlank() && row.pocType.isNotBlank()
                        if (needsPriceHeal || needsPhotoHeal || needsPocTypeHeal) {
                            currentList[idx] = existing.copy(
                                priceOrRent = if (needsPriceHeal) row.priceOrRent else existing.priceOrRent,
                                priceSale = if (needsPriceHeal && row.priceSale != null) row.priceSale else existing.priceSale,
                                rent = if (needsPriceHeal && row.rent != null) row.rent else existing.rent,
                                deposit = if (needsPriceHeal && row.deposit != null) row.deposit else existing.deposit,
                                images = if (needsPhotoHeal) remoteImgs else existing.images,
                                pocType = if (needsPocTypeHeal) row.pocType else existing.pocType
                            )
                            updatedCount++
                        } else {
                            skippedCount++
                        }
                    }
                }
            } else {
                val codeNumber = row.propertyCode.filter { it.isDigit() }.padStart(6, '0').takeLast(6).ifBlank { (100000..999999).random().toString() }
                val newProp = PropertyItem(
                    id = "prop_imp_${System.currentTimeMillis()}_${row.rowNumber}_${(100..999).random()}",
                    propertyCode = row.propertyCode,
                    date = today,
                    location = row.location,
                    projectName = row.projectName,
                    unitNumber = row.unitNumber,
                    floor = row.floor,
                    propertyType = row.propertyType,
                    tab = row.tab,
                    carpetAreaSqFt = row.carpetAreaSqFt,
                    builtUpAreaSqFt = row.builtUpAreaSqFt,
                    priceOrRent = row.priceOrRent,
                    priceSale = row.priceSale,
                    rent = row.rent,
                    deposit = row.deposit,
                    roiPercent = row.roiPercent,
                    possession = row.possession,
                    furnishing = row.furnishing,
                    status = row.status,
                    ownerName = row.ownerName,
                    ownerContact = row.ownerContact,
                    sourceInfo = row.sourceInfo ?: "Master Spreadsheet Import",
                    remarks = row.remarks,
                    listingLink = row.listingLink,
                    isSyncedToSheet = true,
                    lastVerificationDate = "",
                    portfolio = row.portfolio,
                    transactionType = row.transactionType,
                    googleMapsUrl = row.googleMapsUrl,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    verificationStatus = row.verificationStatus,
                    lastVerifiedDateClean = row.lastVerifiedDateClean,
                    verifiedBy = row.verifiedBy,
                    recordId = row.recordId.ifBlank { "AST-$codeNumber" },
                    updatedAt = row.lastUpdated.ifBlank { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date()) },
                    syncUpdatedAt = row.lastUpdated.ifBlank { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date()) },
                    archiveStatus = row.archiveStatus,
                    isAreaEstimated = row.isAreaEstimated,
                    images = extractImageUrlFromLink(row.listingLink),
                    pocType = row.pocType,
                    microMarket = row.microMarket,
                    projectId = row.projectId.orEmpty(),
                    attachedPantry = row.attachedPantry,
                    attachedWashroom = row.attachedWashroom,
                    frontageFt = row.frontageFt,
                    frontageFeet = row.frontageFt,
                    clearHeightFt = row.clearHeightFt,
                    ceilingHeightFeet = row.clearHeightFt,
                    truckAccess = row.truckAccess,
                    powerLoad = row.powerLoad,
                    targetPossessionDate = row.targetPossessionDate,
                    groundFloorCarpetSqFt = row.groundFloorCarpetSqFt,
                    mezzanineCarpetSqFt = row.mezzanineCarpetSqFt,
                    puneZone = row.puneZone,
                    jurisdiction = row.jurisdiction,
                    ocStatus = row.ocStatus,
                    isPreLeased = row.isPreLeased,
                    tenantName = row.tenantName,
                    parking = row.parking
                )
                currentList.add(0, newProp)
                addedCount++
            }
        }
        // Do not publish or count imported records until the local write succeeds.
        if (addedCount + updatedCount > 0) {
            val stampedList = currentList.map { p -> if (originalList.find { it.id == p.id } != p) stamped(p) else p }
            val saved = if (context != null) {
                PropertiesLocalDatabase.saveProperties(context, stampedList)
            } else true
            if (saved) {
                _properties.value = stampedList
                com.example.inventory.PropertyPrecomputedCache.warmUp(stampedList)
            } else {
                failedCount += addedCount + updatedCount
                addedCount = 0
                updatedCount = 0
            }
        }

        logSyncEvent(
            direction = "FILE_IMPORT",
            status = if (failedCount == 0) "SUCCESS" else "WARNING",
            recordCount = addedCount + updatedCount,
            details = "Import completed: $addedCount added, $updatedCount updated, $skippedCount skipped, $failedCount failed",
            triggeredBy = "File Import Engine"
        )

        return SyncStats(
            recordsFound = rows.size,
            addedCount = addedCount,
            updatedCount = updatedCount,
            skippedCount = skippedCount,
            failedCount = failedCount
        )
    }

    data class SafeImportPreview(
        val total: Int,
        val readyToAdd: Int,
        val duplicates: Int,
        val invalid: Int
    )

    /** Read-only preview. Never overwrites existing property codes. */
    fun previewSafeImport(rows: List<ImportPreviewRow>): SafeImportPreview {
        val existing = _properties.value.map { it.propertyCode.trim().uppercase(Locale.ROOT) }.toSet()
        val seen = mutableSetOf<String>()
        var ready = 0
        var duplicates = 0
        var invalid = 0
        rows.forEach { row ->
            val code = row.propertyCode.trim().uppercase(Locale.ROOT)
            when {
                !row.isValid || code.isBlank() -> invalid++
                code in existing || !seen.add(code) -> duplicates++
                else -> ready++
            }
        }
        return SafeImportPreview(rows.size, ready, duplicates, invalid)
    }

    /** Explicit, non-destructive import. Duplicates and blank codes are skipped. */
    fun importSafelySkippingExisting(rows: List<ImportPreviewRow>): SyncStats {
        val existing = _properties.value.map { it.propertyCode.trim().uppercase(Locale.ROOT) }.toSet()
        val seen = mutableSetOf<String>()
        val safeRows = rows.filter { row ->
            val code = row.propertyCode.trim().uppercase(Locale.ROOT)
            row.isValid && code.isNotBlank() && code !in existing && seen.add(code)
        }
        val result = importRowsDetailed(safeRows, duplicateHandling = "SKIP")
        return result.copy(
            recordsFound = rows.size,
            skippedCount = result.skippedCount + (rows.size - safeRows.size)
        )
    }

    fun importRows(rows: List<ImportPreviewRow>, duplicateHandling: String = "SKIP"): Int {
        val stats = importRowsDetailed(rows, duplicateHandling)
        return stats.addedCount + stats.updatedCount
    }

    fun parseWorksheets(
        worksheets: List<ParsedWorksheet>,
        customHeaderMapping: Map<String, String>? = null
    ): List<ImportPreviewRow> {
        val allPreview = mutableListOf<ImportPreviewRow>()
        worksheets.forEach { sheet ->
            // Quote every cell before converting worksheet arrays into TSV. Otherwise
            // embedded tabs, newlines and quotes shift inventory columns or create fake rows.
            val textLines = sheet.rows.map { row ->
                row.joinToString("\t") { value ->
                    "\"" + value.replace("\"", "\"\"") + "\""
                }
            }
            val raw = textLines.joinToString("\n")
            val parsed = parseCsvOrTextData(raw, sheet.sheetName, customHeaderMapping)
            allPreview.addAll(parsed)
        }
        return allPreview
    }



    // Duplicate detection
    fun findPotentialDuplicates(propertyCode: String, projectName: String, unitNumber: String, excludeId: String? = null): List<PropertyItem> {
        return _properties.value.filter { item ->
            if (excludeId != null && item.id == excludeId) return@filter false
            val codeMatch = propertyCode.isNotBlank() && item.propertyCode.equals(propertyCode.trim(), ignoreCase = true)
            val unitMatch = projectName.isNotBlank() && unitNumber.isNotBlank() &&
                    item.projectName.equals(projectName.trim(), ignoreCase = true) &&
                    item.unitNumber.equals(unitNumber.trim(), ignoreCase = true)
            codeMatch || unitMatch
        }
    }

    fun generateNextProjectCode(isCommercial: Boolean): String {
        val appContext = context ?: return if (isCommercial) "CP-0001" else "RP-0001"
        val prefix = if (isCommercial) "CP" else "RP"
        val projects = ProjectDirectoryStore.read(appContext)
        val maxNum = projects.mapNotNull { p ->
            val code = p.id
            if (code.startsWith("$prefix-")) {
                code.substringAfter("$prefix-").filter { it.isDigit() }.toIntOrNull()
            } else null
        }.maxOrNull() ?: 0
        return String.format(Locale.US, "%s-%04d", prefix, maxNum + 1)
    }

    fun findLikelyDuplicateProjects(name: String, developer: String, location: String, address: String = ""): List<DirectoryProject> {
        val appContext = context ?: return emptyList()
        val projects = ProjectDirectoryStore.read(appContext)
        val normName = name.lowercase().trim()
        val normDev = developer.lowercase().trim()
        val normLoc = location.lowercase().trim()
        return projects.filter { p ->
            val pName = p.name.lowercase().trim()
            val pDev = p.developer.lowercase().trim()
            val pLoc = p.location.lowercase().trim()
            val nameMatch = pName == normName || pName.contains(normName) || normName.contains(pName)
            val devMatch = normDev.isBlank() || pDev.isBlank() || pDev == normDev || pDev.contains(normDev)
            val locMatch = normLoc.isBlank() || pLoc.isBlank() || pLoc == normLoc || pLoc.contains(normLoc)
            nameMatch && (devMatch || locMatch)
        }
    }

    fun generateNextCode(tab: InventoryTab): String {
        val appContext = context
        if (appContext != null) {
            return DeviceIdentityManager.generatePropertyId(appContext, isCommercial = tab.isCommercial)
        }
        val prefix = if (tab.isCommercial) "COM" else "RES"
        return "$prefix-DEV0-${System.currentTimeMillis()}"
    }

    private fun parseComparableTimestampMillis(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return 0L
        text.toLongOrNull()?.let { return it }
        runCatching { return java.time.Instant.parse(text).toEpochMilli() }
        val istTz = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        val patterns = listOf(
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy hh:mm a",
            "dd/MM/yyyy",
            "dd MMM yyyy, hh:mm a",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.ENGLISH).apply { timeZone = istTz }
                val parsed = fmt.parse(text)
                if (parsed != null) return parsed.time
            }
        }
        return 0L
    }

    // =========================================================================
    // STEP 12: MASTER SHEET SCHEMA VALIDATION, EXPORT, TEMPLATE & BACKUP/RESTORE
    // =========================================================================

    /**
     * Master Google Sheet Schema Validation against 35 Standard Columns
     */
    fun validateMasterSheetSchema(actualHeaders: List<String>): SchemaValidationResult {
        val expected = MASTER_PROPERTY_35_COLUMNS
        val cleanActual = actualHeaders.map { it.trim() }

        val missing = expected.filter { exp -> cleanActual.none { act -> act.equals(exp, ignoreCase = true) } }
        val extra = cleanActual.filter { act -> act.isNotBlank() && expected.none { exp -> exp.equals(act, ignoreCase = true) } }

        // Presence alone is insufficient: a reordered sheet can silently map values to wrong fields.
        val correctSequence = cleanActual.size == expected.size &&
            cleanActual.zip(expected).all { (actual, required) -> actual.equals(required, ignoreCase = true) }
        val isValid = missing.isEmpty() && extra.isEmpty() && correctSequence
        val detailedMsg = if (isValid) {
            "✅ Sheet headers match the approved 35-column Master Inventory schema in correct sequence."
        } else {
            "⚠️ Master Inventory schema mismatch: ${missing.size} missing, ${extra.size} unexpected columns; expected column order must be preserved."
        }

        val correctionGuidance = if (missing.isNotEmpty()) {
            "Action Required: Missing headers: ${missing.joinToString(", ")}. Please add these exact headers or download the Standard Template."
        } else if (!correctSequence) {
            "All required headers must appear exactly once in the approved 35-column sequence. Reorder or remove unexpected columns before synchronization."
        } else {
            "All 35 standard columns are properly present."
        }

        return SchemaValidationResult(
            isValid = isValid,
            totalColumnsFound = cleanActual.size,
            missingColumns = missing,
            extraColumns = extra,
            detailedMessage = detailedMsg,
            correctionGuidance = correctionGuidance
        )
    }

    /**
     * Export Master Inventory CSV with Client vs Internal Distinction & Filters
     */
    fun exportStandardCsv(
        isClientExport: Boolean = false,
        filterType: String = "ALL", // "ALL", "COMMERCIAL", "RESIDENTIAL", "AVAILABLE", "ARCHIVED"
        customList: List<PropertyItem>? = null
    ): String {
        if (!isClientExport) check(allowed("EXPORT_CONTACTS")) { "Owner-contact export requires an authorised manager" }
        val targetProps = customList ?: when (filterType.uppercase()) {
            "COMMERCIAL" -> _properties.value.filter { it.tab.isCommercial && it.archiveStatus != ArchiveStatus.ARCHIVED }
            "RESIDENTIAL" -> _properties.value.filter { !it.tab.isCommercial && it.archiveStatus != ArchiveStatus.ARCHIVED }
            "AVAILABLE" -> _properties.value.filter { it.status == AvailabilityStatus.AVAILABLE && it.archiveStatus != ArchiveStatus.ARCHIVED }
            "ARCHIVED" -> _properties.value.filter { it.archiveStatus == ArchiveStatus.ARCHIVED }
            else -> _properties.value.filter { it.archiveStatus != ArchiveStatus.ARCHIVED }
        }

        val headers = if (isClientExport) {
            // Client-safe 24 public columns (excludes owner contacts, confidential details, internal remarks)
            listOf(
                "Property Code", "Date", "Location", "Project / Building", "Unit / Shop / Office No",
                "Floor", "Property Type", "Sub Type", "Carpet Area", "Built-up Area",
                "Price / Sale", "Rent", "Deposit", "ROI %", "Possession",
                "Furnishing", "Status", "Source", "Remarks", "Listing Link",
                "Portfolio", "Transaction Type", "Google Maps URL", "Last Verified Date"
            )
        } else {
            MASTER_PROPERTY_35_COLUMNS
        }

        val sb = StringBuilder()
        sb.append(headers.joinToString(",")).append("\n")

        targetProps.forEach { p ->
            val row = if (isClientExport) {
                listOf(
                    p.propertyCode,
                    p.date,
                    "\"${p.location.replace("\"", "\"\"")}\"",
                    "\"${p.projectName.replace("\"", "\"\"")}\"",
                    "\"${p.unitNumber.replace("\"", "\"\"")}\"",
                    p.floor,
                    p.propertyType,
                    p.tab.code,
                    p.carpetAreaSqFt.toInt().toString(),
                    (p.builtUpAreaSqFt?.toInt() ?: "").toString(),
                    (p.priceSale?.toLong() ?: if (!p.tab.isRental) p.priceOrRent.toLong() else "").toString(),
                    (p.rent?.toLong() ?: if (p.tab.isRental) p.priceOrRent.toLong() else "").toString(),
                    (p.deposit?.toLong() ?: "").toString(),
                    (p.roiPercent ?: "").toString(),
                    p.possession,
                    p.furnishing.label,
                    p.status.label,
                    "Real Estate Exclusive",
                    "Not Mentioned",
                    p.listingLink ?: "",
                    p.portfolio,
                    p.transactionType,
                    p.googleMapsUrl ?: "",
                    p.lastVerifiedDateClean
                )
            } else {
                listOf(
                    p.propertyCode,
                    p.date,
                    "\"${p.location.replace("\"", "\"\"")}\"",
                    "\"${p.projectName.replace("\"", "\"\"")}\"",
                    "\"${p.unitNumber.replace("\"", "\"\"")}\"",
                    p.floor,
                    p.propertyType,
                    p.tab.code,
                    p.carpetAreaSqFt.toInt().toString(),
                    (p.builtUpAreaSqFt?.toInt() ?: "").toString(),
                    (p.priceSale?.toLong() ?: if (!p.tab.isRental) p.priceOrRent.toLong() else "").toString(),
                    (p.rent?.toLong() ?: if (p.tab.isRental) p.priceOrRent.toLong() else "").toString(),
                    (p.deposit?.toLong() ?: "").toString(),
                    (p.roiPercent ?: "").toString(),
                    p.possession,
                    p.furnishing.label,
                    p.status.label,
                    "\"${(p.ownerName ?: "").replace("\"", "\"\"")}\"",
                    p.ownerContact ?: "",
                    p.sourceInfo ?: "",
                    "\"${(p.remarks ?: "").replace("\"", "\"\"")}\"",
                    p.listingLink ?: "",
                    p.lastVerificationDate,
                    "\"${(p.internalRemarks ?: "").replace("\"", "\"\"")}\"",
                    p.portfolio,
                    p.transactionType,
                    p.googleMapsUrl ?: "",
                    (p.latitude ?: "").toString(),
                    (p.longitude ?: "").toString(),
                    p.verificationStatus.label,
                    p.lastVerifiedDateClean,
                    p.verifiedBy ?: "",
                    p.recordId,
                    p.syncUpdatedAt,
                    p.archiveStatus.label
                )
            }
            sb.append(row.joinToString(",")).append("\n")
        }
        return sb.toString()
    }

    /**
     * Download Standard Inventory Template CSV with Instructions
     */
    fun generateStandardTemplateCsv(): String {
        val sb = StringBuilder()
        sb.append("# REAL ESTATE INVENTORY — STANDARD INVENTORY IMPORT TEMPLATE\n")
        sb.append("# INSTRUCTIONS: Fill your property details under the headers below. Mandatory fields: Property Code, Project / Building, Location, Carpet Area, Price/Rent.\n")
        sb.append("# VALID PORTFOLIOS: Commercial | Residential\n")
        sb.append("# VALID TRANSACTION TYPES: Lease | Sale | Pre-Leased Sale\n")
        sb.append("# VALID STATUSES: Available | Not Available | Not Confirmed\n")
        sb.append("# VALID VERIFICATION STATUSES: Verified | Pending | Overdue\n\n")
        sb.append(MASTER_PROPERTY_35_COLUMNS.joinToString(",")).append("\n")
        sb.append("OFF-PUN-101,14/10/2026,Kharadi Pune,EON Free Zone Phase 1,Unit 402,4th Floor,Commercial Office,Off Lease,1850,2200,,165000,990000,,Immediate,Fully Furnished,Available,Mr. Rajesh Sharma,9822000000,Direct Listing,Fully furnished office with 32 workstations and 3 cabins,https://realestateinventory.in,14 Oct 2026,Key with security desk,Commercial,Lease,https://maps.google.com/?q=18.55,18.55,73.95,Verified,14 Oct 2026,Pavan Patil,AST-000101,14 Oct 2026 09:00 AM,Active\n")
        return sb.toString()
    }

    private fun canonicalizeJsonValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.joinToString(prefix = "{", postfix = "}", separator = ",") { k ->
                "${JSONObject.quote(k)}:${canonicalizeJsonValue(value.opt(k))}"
            }
        }
        is org.json.JSONArray -> {
            (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") { idx ->
                canonicalizeJsonValue(value.opt(idx))
            }
        }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun computeBackupCanonicalSha256(root: JSONObject): String {
        val canonicalPayload = buildString {
            append("{")
            append("\"properties\":").append(canonicalizeJsonValue(root.optJSONArray("properties") ?: org.json.JSONArray()))
            append(",\"crmLeads\":").append(canonicalizeJsonValue(root.optJSONArray("crmLeads") ?: org.json.JSONArray()))
            append(",\"commercialProfile\":").append(canonicalizeJsonValue(root.optJSONObject("commercialProfile")))
            append(",\"residentialProfile\":").append(canonicalizeJsonValue(root.optJSONObject("residentialProfile")))
            append(",\"userProfile\":").append(canonicalizeJsonValue(root.optJSONObject("userProfile")))
            append(",\"verificationValidityDays\":").append(root.optInt("verificationValidityDays", 30))
            append("}")
        }
        return PropertiesLocalDatabase.computeSha256(canonicalPayload)
    }

    private fun sanitizeNoSecretsJson(obj: JSONObject): JSONObject {
        val forbiddenKeys = setOf("pincredentialref", "pinhash", "pinsalt", "authtoken", "secret", "password", "apikey", "geminiapikey")
        val keys = obj.keys().asSequence().toList()
        keys.forEach { key ->
            if (key.lowercase(Locale.ENGLISH) in forbiddenKeys) {
                obj.remove(key)
            }
        }
        return obj
    }

    /**
     * Create Backup JSON Snapshot (Format v1 with SHA-256 verification and zero secret credentials)
     */
    fun createBackupNow(
        notes: String = "Manual Inventory Backup",
        userSettingsManager: UserSettingsManager? = null
    ): BackupSnapshot {
        check(allowed("EXPORT_CONTACTS")) { "Backup contains private contacts; manager access required" }
        val today = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
        val nowIso = java.time.Instant.now().toString()
        val count = _properties.value.size
        val activeUser = (userSettingsManager ?: UserSettingsManager(context)).userProfile.value

        val crmLeadsArr = runCatching {
            val raw = context?.let {
                auditedCrmPreferences(it, "crm_development_v1", Context.MODE_PRIVATE).getString("leads", "[]")
            } ?: "[]"
            org.json.JSONArray(raw)
        }.getOrDefault(org.json.JSONArray())

        val propsArr = org.json.JSONArray()
        _properties.value.forEach { p ->
            val pObj = PropertiesLocalDatabase.serializePropertyJson(p)
            propsArr.put(pObj)
        }

        val json = JSONObject().apply {
            put("backupFormatVersion", 1)
            put("applicationId", "com.aistudio.ascendaurea.iigmlc")
            put("appVersion", "12.0.0")
            put("timestamp", today)
            put("createdAtIso", nowIso)
            put("createdByEmployeeId", activeUser.username.trim().ifBlank { "default_staff" })
            put("totalProperties", count)
            put("crmLeadCount", crmLeadsArr.length())
            put("projectCount", _properties.value.mapNotNull { it.projectName.trim().takeIf { n -> n.isNotBlank() } }.distinct().size)
            put("pendingSyncCount", _pendingSyncOps.value.size + _pendingSyncCount.value)
            put("notes", notes)
            put("properties", propsArr)
            put("crmLeads", crmLeadsArr)

            if (userSettingsManager != null) {
                val commercialP = userSettingsManager.commercialCompanyProfile.value
                val residentialP = userSettingsManager.residentialCompanyProfile.value
                val userP = userSettingsManager.userProfile.value

                put("commercialProfile", JSONObject().apply {
                    put("name", commercialP.companyName)
                    put("contact1", commercialP.contact1)
                    put("contact2", commercialP.contact2)
                    put("email", commercialP.email)
                    put("address", commercialP.officeAddress)
                    put("footer", commercialP.whatsappFooter)
                })

                put("residentialProfile", JSONObject().apply {
                    put("name", residentialP.companyName)
                    put("contact1", residentialP.contact1)
                    put("contact2", residentialP.contact2)
                    put("email", residentialP.email)
                    put("address", residentialP.officeAddress)
                    put("footer", residentialP.whatsappFooter)
                })

                put("userProfile", sanitizeNoSecretsJson(JSONObject().apply {
                    put("username", userP.username)
                    put("mobile", userP.mobileNumber)
                    put("email", userP.email)
                    put("role", userP.role.label)
                }))

                put("verificationValidityDays", userSettingsManager.verificationValidityDays.value)
            } else {
                put("verificationValidityDays", 30)
            }
        }
        val sha256 = computeBackupCanonicalSha256(json)
        json.put("payloadSha256", sha256)

        val jsonStr = json.toString(2)
        val snap = BackupSnapshot(
            backupId = "bak_${System.currentTimeMillis()}",
            createdAt = today,
            propertyCount = count,
            notes = notes,
            dataJson = jsonStr
        )
        _backups.value = listOf(snap) + _backups.value
        return snap
    }

    fun previewBackupRestore(jsonString: String): RestorePreviewResult {
        return try {
            val root = JSONObject(jsonString)
            if (!root.has("properties")) {
                return RestorePreviewResult(isValid = false, errorMessage = "Invalid backup format: missing 'properties' array.")
            }
            val version = if (root.has("backupFormatVersion")) root.getInt("backupFormatVersion") else 0
            if (version > 1) {
                return RestorePreviewResult(isValid = false, errorMessage = "Unsupported backup format version $version.")
            }
            val appId = root.optString("applicationId", "")
            if (version >= 1 && appId != "com.aistudio.ascendaurea.iigmlc") {
                return RestorePreviewResult(isValid = false, errorMessage = "Backup belongs to a different application ID ($appId).")
            }
            val declaredSha = root.optString("payloadSha256", "")
            if (version >= 1 || declaredSha.isNotBlank()) {
                val computedSha = computeBackupCanonicalSha256(root)
                if (declaredSha.isBlank() || !declaredSha.equals(computedSha, ignoreCase = true)) {
                    return RestorePreviewResult(isValid = false, errorMessage = "Backup integrity check failed (SHA-256 mismatch — file may be modified or corrupted).")
                }
            }
            val propsArr = root.getJSONArray("properties")
            val existingById = _properties.value.associateBy { it.id }
            val existingByCode = _properties.value.filter { it.propertyCode.isNotBlank() }.associateBy { it.propertyCode.uppercase(Locale.ENGLISH) }
            var newProps = 0
            var conflicting = 0
            var matching = 0
            for (i in 0 until propsArr.length()) {
                val pObj = propsArr.getJSONObject(i)
                val incoming = deserializeBackupPropertyItem(pObj, i)
                val match = existingById[incoming.id] ?: existingByCode[incoming.propertyCode.uppercase(Locale.ENGLISH)]
                if (match == null) {
                    newProps++
                } else if (match.recordVersion != incoming.recordVersion || match.priceOrRent != incoming.priceOrRent || match.location != incoming.location || match.status != incoming.status) {
                    conflicting++
                } else {
                    matching++
                }
            }
            val crmLeadsArr = root.optJSONArray("crmLeads") ?: org.json.JSONArray()
            val manifest = BackupManifest(
                backupFormatVersion = if (version == 0) 1 else version,
                applicationId = appId.ifBlank { "com.aistudio.ascendaurea.iigmlc" },
                createdAtIso = root.optString("createdAtIso", root.optString("timestamp", "")),
                createdByEmployeeId = root.optString("createdByEmployeeId", "Manager"),
                propertyCount = propsArr.length(),
                crmLeadCount = crmLeadsArr.length(),
                projectCount = root.optInt("projectCount", 0),
                pendingSyncCount = root.optInt("pendingSyncCount", 0),
                payloadSha256 = declaredSha.ifBlank { computeBackupCanonicalSha256(root) }
            )
            RestorePreviewResult(
                isValid = true,
                manifest = manifest,
                newPropertiesCount = newProps,
                conflictingPropertiesCount = conflicting,
                matchingPropertiesCount = matching,
                newCrmLeadsCount = crmLeadsArr.length(),
                newProjectsCount = manifest.projectCount,
                requiresAdminForFullReplace = true
            )
        } catch (e: Exception) {
            RestorePreviewResult(isValid = false, errorMessage = "Invalid backup JSON: ${e.localizedMessage}")
        }
    }

    private fun deserializeBackupPropertyItem(pObj: JSONObject, index: Int): PropertyItem {
        // Prefer full schema deserialization when present, falling back cleanly for compact pre-v1 backups
        return if (pObj.has("storageVersion") || pObj.has("tab")) {
            PropertiesLocalDatabase.deserializePropertyJson(pObj)
        } else {
            val code = pObj.optString("propertyCode", "PUN-${100 + index}")
            val meta = pObj.optJSONObject("build02Metadata")
            PropertyItem(
                id = pObj.optString("id", "prop_bak_${System.currentTimeMillis()}_$index"),
                propertyCode = code,
                projectName = pObj.optString("projectName", "Commercial Project"),
                location = pObj.optString("location", "Kharadi, Pune"),
                unitNumber = pObj.optString("unitNumber", "Unit 101"),
                carpetAreaSqFt = pObj.optDouble("carpetAreaSqFt", 1000.0),
                priceOrRent = pObj.optDouble("priceOrRent", 100000.0),
                portfolio = pObj.optString("portfolio", "Commercial"),
                transactionType = pObj.optString("transactionType", "Lease"),
                status = runCatching { AvailabilityStatus.valueOf(pObj.optString("status", AvailabilityStatus.NOT_CONFIRMED.name)) }.getOrDefault(AvailabilityStatus.NOT_CONFIRMED),
                recordId = pObj.optString("recordId", "AST-000${100 + index}"),
                updatedBy = meta?.optString("updatedBy") ?: "",
                updatedAt = meta?.optString("updatedAt") ?: "",
                lastPriceConfirmationDate = meta?.optString("lastPriceConfirmationDate") ?: "",
                ownerConfirmationStatus = meta?.optString("ownerConfirmationStatus") ?: "Not Mentioned",
                leaseExpiryDate = meta?.optString("leaseExpiryDate") ?: "",
                upcomingVacancyDate = meta?.optString("upcomingVacancyDate") ?: "",
                sameUnitGroupId = meta?.optString("sameUnitGroupId") ?: "",
                duplicateDecisions = meta?.optJSONObject("duplicateDecisions")?.let { d -> d.keys().asSequence().associateWith { d.optString(it) } } ?: emptyMap(),
                ownerName = if (pObj.has("ownerName") && !pObj.isNull("ownerName")) pObj.optString("ownerName").takeIf { it.isNotBlank() } else null,
                ownerContact = if (pObj.has("ownerContact") && !pObj.isNull("ownerContact")) pObj.optString("ownerContact").takeIf { it.isNotBlank() } else null,
                internalRemarks = if (pObj.has("internalRemarks") && !pObj.isNull("internalRemarks")) pObj.optString("internalRemarks").takeIf { it.isNotBlank() } else null
            )
        }
    }

    /**
     * Restore Backup JSON with SHA-256 verification, Admin-only FULL_REPLACE guard, and pre-restore rollback.
     */
    @Synchronized
    fun restoreFromBackupDetailed(
        jsonString: String,
        userSettingsManager: UserSettingsManager? = null,
        mode: RestoreMode = RestoreMode.MERGE_MISSING
    ): RestoreOutcome {
        val activeRole = (userSettingsManager ?: UserSettingsManager(context)).userProfile.value.role
        if (mode == RestoreMode.FULL_REPLACE && activeRole != UserRole.ADMIN) {
            return RestoreOutcome(
                success = false,
                mode = mode,
                message = "Admin access required for full database replacement."
            )
        }
        if (!allowed("BULK_EDIT")) {
            return RestoreOutcome(success = false, mode = mode, message = "Manager access required for restore")
        }

        val preview = previewBackupRestore(jsonString)
        if (!preview.isValid) {
            return RestoreOutcome(success = false, mode = mode, message = preview.errorMessage ?: "Invalid backup file.")
        }

        val previousProps = _properties.value
        val previousCrmRaw = context?.let {
            auditedCrmPreferences(it, "crm_development_v1", Context.MODE_PRIVATE).getString("leads", "[]")
        }
        val preRestoreSnapshot = runCatching {
            createBackupNow(notes = "Automatic Pre-Restore Safety Backup", userSettingsManager = userSettingsManager)
        }.getOrNull()

        return try {
            val root = JSONObject(jsonString)
            val propsArr = root.getJSONArray("properties")
            val existingById = previousProps.associateBy { it.id }
            val existingByCode = previousProps.filter { it.propertyCode.isNotBlank() }
                .associateBy { it.propertyCode.uppercase(Locale.ENGLISH) }

            val incomingList = (0 until propsArr.length()).map { i ->
                deserializeBackupPropertyItem(propsArr.getJSONObject(i), i)
            }

            var mergedCount = 0
            var skippedConflicts = 0
            val finalProps: List<PropertyItem> = when (mode) {
                RestoreMode.FULL_REPLACE -> {
                    mergedCount = incomingList.size
                    incomingList
                }
                RestoreMode.MERGE_MISSING, RestoreMode.REVIEW_CONFLICTS -> {
                    val resultMap = LinkedHashMap<String, PropertyItem>()
                    previousProps.forEach { resultMap[it.id] = it }
                    incomingList.forEach { inc ->
                        val match = resultMap[inc.id] ?: existingByCode[inc.propertyCode.uppercase(Locale.ENGLISH)]
                        if (match == null) {
                            resultMap[inc.id] = inc
                            mergedCount++
                        } else if (mode == RestoreMode.REVIEW_CONFLICTS && inc.recordVersion > match.recordVersion) {
                            resultMap[match.id] = inc
                            mergedCount++
                        } else {
                            skippedConflicts++
                        }
                    }
                    resultMap.values.toList()
                }
            }

            _properties.value = finalProps
            val saved = if (context != null) PropertiesLocalDatabase.saveProperties(context, finalProps) else true
            if (!saved) {
                // Transactional rollback
                _properties.value = previousProps
                if (context != null && previousCrmRaw != null) {
                    auditedCrmPreferences(context, "crm_development_v1", Context.MODE_PRIVATE)
                        .edit().putString("leads", previousCrmRaw).commit()
                }
                refreshStorageHealth()
                return RestoreOutcome(
                    success = false,
                    mode = mode,
                    rolledBackOnFailure = true,
                    preRestoreSnapshotId = preRestoreSnapshot?.backupId,
                    message = "Failed to write restored inventory to disk. Rolled back to previous data."
                )
            }
            if (context != null) {
                _properties.value = finalProps
                com.example.inventory.PropertyPrecomputedCache.warmUp(finalProps)
            }
            refreshPendingSyncState(reconcileDisk = false)

            if (context != null && root.has("crmLeads")) {
                val incomingLeads = root.optJSONArray("crmLeads") ?: org.json.JSONArray()
                if (incomingLeads.length() > 0) {
                    val prefs = auditedCrmPreferences(context, "crm_development_v1", Context.MODE_PRIVATE)
                    val curArr = runCatching { org.json.JSONArray(prefs.getString("leads", "[]") ?: "[]") }.getOrDefault(org.json.JSONArray())
                    val mergedLeads = org.json.JSONArray()
                    val seenIds = mutableSetOf<String>()
                    if (mode != RestoreMode.FULL_REPLACE) {
                        for (i in 0 until curArr.length()) {
                            val obj = curArr.optJSONObject(i) ?: continue
                            val id = obj.optString("id", obj.optString("clientId", ""))
                            if (id.isNotBlank()) seenIds.add(id)
                            mergedLeads.put(obj)
                        }
                    }
                    for (i in 0 until incomingLeads.length()) {
                        val obj = incomingLeads.optJSONObject(i) ?: continue
                        val id = obj.optString("id", obj.optString("clientId", ""))
                        if (id.isBlank() || seenIds.add(id)) {
                            mergedLeads.put(obj)
                        }
                    }
                    prefs.edit().putString("leads", mergedLeads.toString()).commit()
                }
            }

            if (userSettingsManager != null && mode == RestoreMode.FULL_REPLACE) {
                if (root.has("commercialProfile")) {
                    val asc = root.getJSONObject("commercialProfile")
                    userSettingsManager.updateCommercialCompanyProfile(
                        asc.optString("name", "COMMERCIAL PORTFOLIO"),
                        asc.optString("contact1", "9766880099"),
                        asc.optString("contact2", "9766668099"),
                        asc.optString("email", "commercial@realestateinventory.in"),
                        asc.optString("address", "Kharadi, Pune, Maharashtra"),
                        asc.optString("footer", "For private viewing and detailed brochure:\n📞 Call/WhatsApp: 9766880099 | 9766668099")
                    )
                }
                if (root.has("residentialProfile")) {
                    val aur = root.getJSONObject("residentialProfile")
                    userSettingsManager.updateResidentialCompanyProfile(
                        aur.optString("name", "RESIDENTIAL PORTFOLIO"),
                        aur.optString("contact1", "9766880099"),
                        aur.optString("contact2", "9766668099"),
                        aur.optString("email", "residential@realestateinventory.in"),
                        aur.optString("address", "Kharadi, Pune, Maharashtra"),
                        aur.optString("footer", "For floor plans and builder inventory:\n📞 Call/WhatsApp: 9766880099 | 9766668099")
                    )
                }
                if (root.has("verificationValidityDays")) {
                    userSettingsManager.updateVerificationValidityDays(root.optInt("verificationValidityDays", 30).takeIf { it in setOf(7, 15, 30) } ?: 30)
                }
            }

            logSyncEvent("BACKUP_RESTORE", "SUCCESS", _properties.value.size, "Restored (${mode.name}): merged=$mergedCount, skippedConflicts=$skippedConflicts")
            RestoreOutcome(
                success = true,
                mode = mode,
                restoredPropertiesCount = _properties.value.size,
                mergedPropertiesCount = mergedCount,
                skippedConflictsCount = skippedConflicts,
                preRestoreSnapshotId = preRestoreSnapshot?.backupId,
                message = "Backup restored (${mode.label}): $mergedCount added/updated, ${_properties.value.size} total properties."
            )
        } catch (e: Exception) {
            _properties.value = previousProps
            RestoreOutcome(
                success = false,
                mode = mode,
                rolledBackOnFailure = true,
                preRestoreSnapshotId = preRestoreSnapshot?.backupId,
                message = "Failed to restore backup: ${e.localizedMessage}"
            )
        }
    }

    fun restoreFromBackupJson(
        jsonString: String,
        userSettingsManager: UserSettingsManager? = null,
        mode: RestoreMode = RestoreMode.MERGE_MISSING
    ): Pair<Boolean, String> {
        val outcome = restoreFromBackupDetailed(jsonString, userSettingsManager, mode)
        return outcome.success to outcome.message
    }

    fun restoreBackup(backupId: String): Boolean {
        val snap = _backups.value.find { it.backupId == backupId }
        return if (snap != null) {
            restoreFromBackupJson(snap.dataJson).first
        } else false
    }

    fun getGoogleAppsScriptTemplate(): String {
        return """
/**
 * REAL ESTATE INVENTORY MANAGEMENT SYSTEM
 * MASTER REAL ESTATE INVENTORY DATABASE: MASTER_REAL_ESTATE_INVENTORY
 * 4 REQUIRED WORKSHEETS:
 * 1. MASTER_PROPERTIES (35 Standard Columns)
 * 2. SETTINGS
 * 3. SYNC_LOG
 * 4. DATA_REVIEW
 * 
 * SETUP INSTRUCTIONS:
 * 1. In your Google Spreadsheet (named MASTER_REAL_ESTATE_INVENTORY), click:
 *    Extensions > Apps Script.
 * 2. Delete existing code and paste this script into Code.gs.
 * 3. Click Run > setupMasterDatabase() ONCE to automatically create the 4 worksheets and format headers.
 * 4. Click Deploy > New Deployment.
 * 5. Select type: 'Web App'.
 *    - Execute as: 'Me' (your account)
 *    - Who has access: Restrict access; use an authenticated integration for private inventory
 * 6. Copy the resulting Web App URL and paste it into the Android App.
 */

function setupMasterDatabase() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();

  // 1. MASTER_PROPERTIES: Exact 35 Standard Columns
  var masterCols = [
    'Property Code', 'Date', 'Location', 'Project / Building', 'Unit / Shop / Office No',
    'Floor', 'Property Type', 'Sub Type', 'Carpet Area', 'Built-up Area',
    'Price / Sale', 'Rent', 'Deposit', 'ROI %', 'Possession',
    'Furnishing', 'Status', 'Owner / Contact Person', 'Contact Number', 'Source',
    'Remarks', 'Listing Link', 'Last Updated', 'Remark',
    'Portfolio', 'Transaction Type', 'Google Maps URL', 'Latitude', 'Longitude',
    'Verification Status', 'Last Verified Date', 'Verified By', 'Record ID', 'Sync Updated At', 'Archive Status'
  ];

  // 2. SETTINGS
  var settingsCols = ['Setting Key', 'Configured Value', 'Description', 'Category'];

  // 3. SYNC_LOG
  var syncLogCols = ['Log ID', 'Timestamp', 'Direction', 'Status', 'Record Count', 'Details', 'Triggered By'];

  // 4. DATA_REVIEW
  var dataReviewCols = ['Review ID', 'Source File', 'Row Number', 'Raw Data Snapshot', 'Issue Reason', 'Suggested Code', 'Created Date'];

  var worksheets = {
    'MASTER_PROPERTIES': masterCols,
    'SETTINGS': settingsCols,
    'SYNC_LOG': syncLogCols,
    'DATA_REVIEW': dataReviewCols
  };

  Object.keys(worksheets).forEach(function(name) {
    var sheet = ss.getSheetByName(name);
    if (!sheet) {
      sheet = ss.insertSheet(name);
    }
    if (sheet.getLastRow() === 0) {
      sheet.appendRow(worksheets[name]);
      sheet.getRange(1, 1, 1, worksheets[name].length)
        .setFontWeight('bold')
        .setBackground('#0F172A')
        .setFontColor('#D4AF37');
      sheet.setFrozenRows(1);
    }
  });

  // Seed default settings if empty
  var setSheet = ss.getSheetByName('SETTINGS');
  if (setSheet && setSheet.getLastRow() === 1) {
    setSheet.appendRow(['WORKBOOK_NAME', 'MASTER_REAL_ESTATE_INVENTORY', 'Authoritative Master Database', 'Core']);
    setSheet.appendRow(['OFFICE_DEFAULT_LOADING', '45%', 'Standard office built-up loading factor', 'Area Math']);
    setSheet.appendRow(['SHOP_DEFAULT_LOADING', '50%', 'Retail shop/showroom loading factor', 'Area Math']);
    setSheet.appendRow(['RESIDENTIAL_LOADING', '0%', 'Commercial loading never applies to residential', 'Area Math']);
    setSheet.appendRow(['PORTFOLIO_COMMERCIAL', 'Commercial', 'Commercial real estate portfolio', 'Classification']);
    setSheet.appendRow(['PORTFOLIO_RESIDENTIAL', 'Residential', 'Residential real estate portfolio', 'Classification']);
  }
}

function doGet(e) {
  if (e && e.parameter && (e.parameter.action || e.parameter.payload || e.parameter.data)) {
    return handleWriteRequest(e);
  }
  return handleReadRequest(e);
}

function doPost(e) {
  return handleWriteRequest(e);
}

function handleReadRequest(e) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var masterSheet = ss.getSheetByName('MASTER_PROPERTIES') || ss.getSheetByName('Master Inventory') || ss.getSheets()[0];
  var settingsSheet = ss.getSheetByName('SETTINGS');
  var logSheet = ss.getSheetByName('SYNC_LOG');
  var reviewSheet = ss.getSheetByName('DATA_REVIEW');

  var data = {
    'MASTER_PROPERTIES': masterSheet ? masterSheet.getDataRange().getValues() : [],
    'SETTINGS': settingsSheet ? settingsSheet.getDataRange().getValues() : [],
    'SYNC_LOG': logSheet ? logSheet.getDataRange().getValues() : [],
    'DATA_REVIEW': reviewSheet ? reviewSheet.getDataRange().getValues() : []
  };

  return ContentService.createTextOutput(JSON.stringify({
    status: 'success',
    workbook: 'MASTER_REAL_ESTATE_INVENTORY',
    timestamp: new Date().toISOString(),
    data: data
  })).setMimeType(ContentService.MimeType.JSON);
}

function handleWriteRequest(e) {
  try {
    var postData = {};
    if (e && e.postData && e.postData.contents) {
      postData = JSON.parse(e.postData.contents);
    } else if (e && e.parameter && e.parameter.payload) {
      postData = JSON.parse(e.parameter.payload);
    } else if (e && e.parameter && e.parameter.data) {
      postData = JSON.parse(e.parameter.data);
    } else if (e && e.parameter) {
      postData = e.parameter;
    }

    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var masterSheet = ss.getSheetByName('MASTER_PROPERTIES') || ss.getSheetByName('Master Inventory') || ss.getSheets()[0];
    var logSheet = ss.getSheetByName('SYNC_LOG');
    var reviewSheet = ss.getSheetByName('DATA_REVIEW');

    var action = postData.action || 'UPSERT';

    if (action === 'DIAGNOSTIC_PING') {
      return ContentService.createTextOutput(JSON.stringify({ status: 'success', action: 'DIAGNOSTIC_PING', message: 'Diagnostic ping acknowledged; property writes unverified' }))
        .setMimeType(ContentService.MimeType.JSON);
    }

    // 1. Audit Logging
    if (action === 'LOG_SYNC') {
      if (logSheet) {
        logSheet.appendRow([
          postData.logId || ('LOG-' + new Date().getTime()),
          postData.timestamp || new Date().toLocaleString(),
          postData.direction || 'APP_TO_SHEET',
          postData.status || 'SUCCESS',
          postData.recordCount || 1,
          postData.details || '',
          postData.triggeredBy || 'Android App'
        ]);
      }
      return ContentService.createTextOutput(JSON.stringify({ status: 'success', action: 'LOG_SYNC' }))
        .setMimeType(ContentService.MimeType.JSON);
    }

    // 2. Quarantine Ambiguous/Invalid Records
    if (action === 'QUARANTINE_REVIEW_ROW') {
      if (reviewSheet) {
        reviewSheet.appendRow([
          postData.reviewId || ('REV-' + new Date().getTime()),
          postData.sourceFile || 'Import File',
          postData.rowNumber || 1,
          postData.rawData || '',
          postData.issueReason || 'INVALID_ROW',
          postData.suggestedCode || '',
          postData.createdAt || new Date().toLocaleDateString()
        ]);
      }
      return ContentService.createTextOutput(JSON.stringify({ status: 'success', action: 'QUARANTINE_REVIEW_ROW' }))
        .setMimeType(ContentService.MimeType.JSON);
    }

    // 3. Property Upsert (Matching by Property Code or Record ID)
    var values = masterSheet.getDataRange().getValues();
    var rowIndex = -1;
    var targetCode = String(postData.propertyCode || '').trim();
    var targetRecordId = String(postData.recordId || '').trim();

    for (var i = 1; i < values.length; i++) {
      var rowCode = String(values[i][0] || '').trim();
      var rowRecId = String(values[i][32] || '').trim();
      if ((targetCode && rowCode.toLowerCase() === targetCode.toLowerCase()) ||
          (targetRecordId && rowRecId.toLowerCase() === targetRecordId.toLowerCase())) {
        rowIndex = i + 1;
        break;
      }
    }

    var row35 = [
      postData.propertyCode || '',
      postData.date || new Date().toLocaleDateString(),
      postData.location || '',
      postData.projectName || '',
      postData.unitNumber || '',
      postData.floor || 'Mid Floor',
      postData.propertyType || 'Office',
      postData.subType || 'Commercial Offices (Lease)',
      postData.carpetAreaSqFt || 0,
      postData.builtUpAreaSqFt || '',
      postData.priceSale || '',
      postData.rent || '',
      postData.deposit || '',
      postData.roiPercent || '',
      postData.possession || 'Immediate',
      postData.furnishing || 'Not Mentioned',
      postData.status || 'Available',
      postData.ownerName || '',
      postData.ownerContact || '',
      postData.source || 'Android App Live Sync',
      postData.remarks || '',
      postData.listingLink || '',
      postData.lastUpdated || new Date().toLocaleDateString(),
      postData.remark || '',
      postData.portfolio || 'Commercial',
      postData.transactionType || 'Lease',
      postData.googleMapsUrl || '',
      postData.latitude || '',
      postData.longitude || '',
      postData.verificationStatus || 'Pending',
      postData.lastVerifiedDate || new Date().toLocaleDateString(),
      postData.verifiedBy || '',
      postData.recordId || ('AST-' + (targetCode.replace(/\D/g, '').padStart(6, '0') || '000101')),
      postData.syncUpdatedAt || new Date().toLocaleString(),
      postData.archiveStatus || 'Active'
    ];

    if (rowIndex > 0) {
      masterSheet.getRange(rowIndex, 1, 1, row35.length).setValues([row35]);
    } else {
      masterSheet.appendRow(row35);
    }

    if (logSheet) {
      logSheet.appendRow([
        'LOG-' + new Date().getTime(),
        new Date().toLocaleString(),
        'APP_TO_SHEET',
        'SUCCESS',
        1,
        (rowIndex > 0 ? 'Updated ' : 'Added ') + targetCode + ' (' + (postData.portfolio || 'Commercial') + ')',
        'Android Device'
      ]);
    }

    return ContentService.createTextOutput(JSON.stringify({
      status: 'success',
      action: rowIndex > 0 ? 'UPDATED' : 'ADDED',
      propertyCode: targetCode,
      recordId: row35[32]
    })).setMimeType(ContentService.MimeType.JSON);

  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ status: 'error', message: err.toString() }))
      .setMimeType(ContentService.MimeType.JSON);
  }
}
""".trimIndent()
    }

    private suspend fun sendAppsScriptPost(
        targetUrl: String, jsonPayload: JSONObject
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        AppsScriptSyncProtocol.post(targetUrl, jsonPayload)
    }

    /** Shared durable drain used by foreground actions and Android WorkManager. */
    internal suspend fun retryPendingWrites(
        deadlineMillis: Long = Long.MAX_VALUE,
        maxOperations: Int = 20
    ): PendingDrainResult = withContext(Dispatchers.IO) {
        val appContext = context ?: return@withContext PendingDrainResult.UNCONFIGURED
        queueMutex.withLock {
            try {
                val initialQueue = PropertiesLocalDatabase.loadPendingWrites(appContext)
                if (initialQueue.isEmpty()) {
                    refreshPendingSyncState()
                    return@withLock PendingDrainResult.EMPTY
                }
                if (!PendingSyncQueue.hasRetryableWrites(initialQueue)) {
                    refreshPendingSyncState()
                    return@withLock PendingDrainResult.REVIEW
                }
                if (PendingSyncQueue.nextDue(initialQueue, System.currentTimeMillis()) == null) {
                    refreshPendingSyncState()
                    return@withLock PendingDrainResult.WAITING
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() >= deadlineMillis) {
                    return@withLock PendingDrainResult.WAITING
                }
                val endpoint = _sheetsScriptUrl.value.trim()
                val uri = runCatching { java.net.URI(endpoint) }.getOrNull()
                if (uri == null || uri.scheme != "https" || uri.host != "script.google.com") {
                    refreshPendingSyncState()
                    _pendingSyncStatus.value += "; compatible endpoint required"
                    return@withLock PendingDrainResult.UNCONFIGURED
                }
                val response = fetchUrlText(endpoint)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!AppsScriptSyncProtocol.supportsDurableQueue(response)) {
                    refreshPendingSyncState()
                    _pendingSyncStatus.value += "; server idempotency support unverified"
                    return@withLock PendingDrainResult.UNSUPPORTED
                }
                var transientFailure = false
                for (attempt in 0 until maxOperations.coerceIn(1, 20)) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val now = System.currentTimeMillis()
                    if (now >= deadlineMillis) break
                    val queue = PropertiesLocalDatabase.loadPendingWrites(appContext)
                    val due = PendingSyncQueue.nextDue(queue, now) ?: break
                    val started = PropertiesLocalDatabase.beginPendingWrite(
                        appContext, due.operationId, now
                    ) ?: break
                    val result = AppsScriptSyncProtocol.postChecked(
                        endpoint, PropertiesLocalDatabase.pendingWritePayload(started)
                    )
                    if (result.accepted) {
                        if (!PropertiesLocalDatabase.acknowledgePendingWrite(appContext, started.operationId)) {
                            _pendingSyncStatus.value = "Acknowledgement not saved; operation remains pending"
                            return@withLock PendingDrainResult.STORAGE_FAILURE
                        }
                        _properties.update {
                            AppsScriptSyncProtocol.markAcknowledged(it, started.property)
                        }
                        logSyncEvent("APP_TO_SHEET", "SUCCESS", 1,
                            "Queued operation acknowledged: ${started.operationId}", "Pending Sync Queue")
                    } else {
                        if (!PropertiesLocalDatabase.failPendingWrite(
                                appContext, started.operationId, result.retryable, result.message)) {
                            _pendingSyncStatus.value = "Retry state not saved; operation retained"
                            return@withLock PendingDrainResult.STORAGE_FAILURE
                        }
                        logSyncEvent("APP_TO_SHEET", if (result.retryable) "WARNING" else "FAILED",
                            0, result.message, "Pending Sync Queue")
                        if (result.retryable) { transientFailure = true; break }
                    }
                }
                refreshPendingSyncState()
                val remaining = PropertiesLocalDatabase.loadPendingWrites(appContext)
                when {
                    remaining.isEmpty() -> PendingDrainResult.COMPLETE
                    !PendingSyncQueue.hasRetryableWrites(remaining) -> PendingDrainResult.REVIEW
                    transientFailure -> PendingDrainResult.RETRYABLE_FAILURE
                    else -> PendingDrainResult.WAITING
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                refreshPendingSyncState()
                _pendingSyncStatus.value += "; connection unavailable, changes retained"
                PendingDrainResult.RETRYABLE_FAILURE
            }
        }
    }

    fun isWithinEmployeeScope(
        property: PropertyItem,
        employee: EmployeeProfile?,
        businessView: ActiveBusinessView = employee?.normalizedDefaultView() ?: ActiveBusinessView.ASCEND_COMMERCIAL
    ): Boolean {
        if (employee == null || !employee.hasConfidentialAccess()) return false
        val effectiveView = if (employee.canSwitchToView(businessView)) businessView else employee.normalizedDefaultView()
        return employee.copy(activeBusinessView = effectiveView).canAccessPortfolio(property.effectivePortfolio())
    }

    fun queryScopedProperties(
        employee: EmployeeProfile?,
        businessView: ActiveBusinessView = employee?.normalizedDefaultView() ?: ActiveBusinessView.ASCEND_COMMERCIAL,
        includeArchived: Boolean = true
    ): List<PropertyItem> {
        if (employee == null || !employee.hasConfidentialAccess()) return emptyList()
        return _properties.value.filter { prop ->
            (includeArchived || prop.archiveStatus != ArchiveStatus.ARCHIVED) &&
                isWithinEmployeeScope(prop, employee, businessView)
        }
    }

    fun searchScopedProperties(
        query: String,
        employee: EmployeeProfile?,
        businessView: ActiveBusinessView = employee?.normalizedDefaultView() ?: ActiveBusinessView.ASCEND_COMMERCIAL,
        includeArchived: Boolean = false
    ): List<PropertyItem> {
        val scoped = queryScopedProperties(employee, businessView, includeArchived)
        val q = query.trim().lowercase()
        if (q.isBlank()) return scoped
        return scoped.filter { prop ->
            prop.propertyCode.lowercase().contains(q) ||
                prop.projectName.lowercase().contains(q) ||
                prop.location.lowercase().contains(q) ||
                prop.unitNumber.lowercase().contains(q) ||
                prop.propertyType.lowercase().contains(q)
        }
    }

    fun queryScopedUnderConstProjects(
        employee: EmployeeProfile?,
        businessView: ActiveBusinessView = employee?.normalizedDefaultView() ?: ActiveBusinessView.ASCEND_COMMERCIAL
    ): List<UnderConstProject> {
        if (employee == null || !employee.hasConfidentialAccess()) return emptyList()
        val effectiveView = if (employee.canSwitchToView(businessView)) businessView else employee.normalizedDefaultView()
        val canSeeResidential = employee.copy(activeBusinessView = effectiveView)
            .canAccessPortfolio(StandardPortfolio.RESIDENTIAL)
        return if (canSeeResidential) _underConstProjects.value else emptyList()
    }

    private fun isWithinBusinessDivisionScope(property: PropertyItem, activeUser: UserProfile): Boolean {
        return when (activeUser.role) {
            // Core team (Super Admin, Admin, Commercial/Residential/Inventory Managers, Sales Executives)
            // always have full visibility across both Commercial & Residential with Owner/Tenant contacts.
            UserRole.ADMIN,
            UserRole.COMMERCIAL_MANAGER,
            UserRole.RESIDENTIAL_MANAGER,
            UserRole.INVENTORY_MANAGER,
            UserRole.SALES_EXECUTIVE -> true
            UserRole.READ_ONLY, UserRole.BROKER, UserRole.OWNER -> false
        }
    }

    fun canEditPropertyLocation(propertyId: String, activeUser: UserProfile): Boolean {
        val property = _properties.value.firstOrNull { it.id == propertyId } ?: return false
        if (property.archiveStatus == ArchiveStatus.ARCHIVED && property.status == AvailabilityStatus.WITHDRAWN) {
            return false
        }
        return isWithinBusinessDivisionScope(property, activeUser)
    }

    fun resolvePropertyMapDestination(
        propertyId: String,
        activeUser: UserProfile
    ): ResolvedMapDestination? {
        val property = _properties.value.firstOrNull { it.id == propertyId } ?: return null
        if (property.archiveStatus == ArchiveStatus.ARCHIVED && property.status == AvailabilityStatus.WITHDRAWN) {
            return null
        }
        if (!isWithinBusinessDivisionScope(property, activeUser)) {
            return null
        }
        return resolveMapDestinationForProperty(property)
    }

    fun resolveAuthorizedCallableContacts(
        propertyId: String,
        activeUser: UserProfile,
        isInternalMode: Boolean
    ): List<CallableContact> {
        val property = _properties.value.firstOrNull { it.id == propertyId } ?: return emptyList()
        if (property.archiveStatus == ArchiveStatus.ARCHIVED && property.status == AvailabilityStatus.WITHDRAWN) {
            return emptyList()
        }

        val canAccessPrivateContacts = isInternalMode && isWithinBusinessDivisionScope(property, activeUser)
        val rawCandidates = mutableListOf<CallableContact>()

        if (canAccessPrivateContacts) {
            val ownerDisplay = property.ownerName?.trim()?.takeIf { it.isNotBlank() } ?: "Not Mentioned"
            ContactUtils.parseAndSanitizeCallableNumbers(property.ownerContact).forEach { (dial, ext) ->
                rawCandidates.add(
                    CallableContact(
                        contactType = CallableContactType.OWNER,
                        displayName = ownerDisplay,
                        rawNumber = property.ownerContact.orEmpty(),
                        dialNumber = dial,
                        extension = ext,
                        combinedCategoryLabel = CallableContactType.OWNER.label
                    )
                )
            }

            val brokerDisplay = property.brokerName?.trim()?.takeIf { it.isNotBlank() } ?: "Not Mentioned"
            ContactUtils.parseAndSanitizeCallableNumbers(property.brokerContact).forEach { (dial, ext) ->
                rawCandidates.add(
                    CallableContact(
                        contactType = CallableContactType.BROKER,
                        displayName = brokerDisplay,
                        rawNumber = property.brokerContact.orEmpty(),
                        dialNumber = dial,
                        extension = ext,
                        combinedCategoryLabel = CallableContactType.BROKER.label
                    )
                )
            }
        }

        val verifiedByRaw = property.verifiedBy?.trim().orEmpty()
        if (verifiedByRaw.isNotBlank()) {
            val parts = verifiedByRaw.split('|', '(', ')').map { it.trim() }.filter { it.isNotEmpty() }
            val employeeName = parts.firstOrNull { p -> p.any { it.isLetter() } } ?: "Assigned Executive"
            val phoneSource = if (parts.size > 1) verifiedByRaw.replace('|', ',') else verifiedByRaw
            ContactUtils.parseAndSanitizeCallableNumbers(phoneSource).forEach { (dial, ext) ->
                rawCandidates.add(
                    CallableContact(
                        contactType = CallableContactType.EMPLOYEE,
                        displayName = employeeName,
                        rawNumber = verifiedByRaw,
                        dialNumber = dial,
                        extension = ext,
                        combinedCategoryLabel = CallableContactType.EMPLOYEE.label
                    )
                )
            }
        }

        property.unmappedColumns.forEach { (key, value) ->
            val keyLower = key.lowercase(Locale.ENGLISH)
            val isEmpKey = (keyLower.contains("assigned") || keyLower.contains("employee") ||
                keyLower.contains("executive") || keyLower.contains("verified") || keyLower.contains("poc")) &&
                (keyLower.contains("phone") || keyLower.contains("mobile") || keyLower.contains("contact") || keyLower.contains("number"))
            if (isEmpKey && value.isNotBlank()) {
                ContactUtils.parseAndSanitizeCallableNumbers(value).forEach { (dial, ext) ->
                    rawCandidates.add(
                        CallableContact(
                            contactType = CallableContactType.EMPLOYEE,
                            displayName = CallableContactType.EMPLOYEE.label,
                            rawNumber = value,
                            dialNumber = dial,
                            extension = ext,
                            combinedCategoryLabel = CallableContactType.EMPLOYEE.label
                        )
                    )
                }
            }
        }

        if (rawCandidates.isNotEmpty()) {
            val merged = LinkedHashMap<String, CallableContact>()
            for (candidate in rawCandidates) {
                val canonicalKey = candidate.dialNumber.removePrefix("+91")
                val existing = merged[canonicalKey]
                if (existing == null) {
                    merged[canonicalKey] = candidate
                } else {
                    val existingLabels = existing.combinedCategoryLabel.split(" / ").map { it.trim() }
                    val updatedLabel = if (candidate.contactType.label in existingLabels) {
                        existing.combinedCategoryLabel
                    } else {
                        "${existing.combinedCategoryLabel} / ${candidate.contactType.label}"
                    }
                    val updatedName = if (existing.displayName == "Not Mentioned" && candidate.displayName != "Not Mentioned") {
                        candidate.displayName
                    } else {
                        existing.displayName
                    }
                    val updatedExt = existing.extension.ifBlank { candidate.extension }
                    merged[canonicalKey] = existing.copy(
                        displayName = updatedName,
                        extension = updatedExt,
                        combinedCategoryLabel = updatedLabel
                    )
                }
            }
            return merged.values.toList()
        }

        return listOf(
            CallableContact(
                contactType = CallableContactType.DESK,
                displayName = "Official Desk",
                rawNumber = "9766880099",
                dialNumber = "+919766880099",
                extension = "",
                combinedCategoryLabel = CallableContactType.DESK.label
            ),
            CallableContact(
                contactType = CallableContactType.DESK,
                displayName = "Official Desk",
                rawNumber = "9766668099",
                dialNumber = "+919766668099",
                extension = "",
                combinedCategoryLabel = CallableContactType.DESK.label
            )
        )
    }

    fun getStoredCandidates(projectId: String): List<FetchedFieldCandidate> {
        val appContext = context ?: return emptyList()
        val prefs = appContext.getSharedPreferences("project_fetch_candidates_v1", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(projectId, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                FetchedFieldCandidate(
                    fieldKey = obj.getString("fieldKey"),
                    fieldLabel = obj.getString("fieldLabel"),
                    savedValue = obj.optString("savedValue", ""),
                    fetchedValue = obj.optString("fetchedValue", ""),
                    sourceName = obj.optString("sourceName", "Official Registry"),
                    sourceUrl = obj.optString("sourceUrl", "https://maharerait.maharashtra.gov.in/"),
                    fetchedAt = obj.optString("fetchedAt", ""),
                    isDirectSourceStatement = obj.optBoolean("isDirectSourceStatement", true),
                    category = runCatching { FieldDiffCategory.valueOf(obj.getString("category")) }.getOrDefault(FieldDiffCategory.NO_CHANGE)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveStoredCandidates(projectId: String, candidates: List<FetchedFieldCandidate>) {
        val appContext = context ?: return
        val prefs = appContext.getSharedPreferences("project_fetch_candidates_v1", Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (c in candidates) {
            val obj = JSONObject()
                .put("fieldKey", c.fieldKey)
                .put("fieldLabel", c.fieldLabel)
                .put("savedValue", c.savedValue)
                .put("fetchedValue", c.fetchedValue)
                .put("sourceName", c.sourceName)
                .put("sourceUrl", c.sourceUrl)
                .put("fetchedAt", c.fetchedAt)
                .put("isDirectSourceStatement", c.isDirectSourceStatement)
                .put("category", c.category.name)
            arr.put(obj)
        }
        prefs.edit().putString(projectId, arr.toString()).apply()
    }

    fun getAuditLogs(projectId: String): List<ProjectVerificationAuditEntry> {
        val appContext = context ?: return emptyList()
        val prefs = appContext.getSharedPreferences("project_verification_audit_v1", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(projectId, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ProjectVerificationAuditEntry(
                    entryId = obj.optString("entryId", UUID.randomUUID().toString()),
                    projectId = obj.optString("projectId", projectId),
                    fieldChanged = obj.optString("fieldChanged", ""),
                    previousValue = obj.optString("previousValue", ""),
                    newValue = obj.optString("newValue", ""),
                    sourceReference = obj.optString("sourceReference", ""),
                    employeeIdentity = obj.optString("employeeIdentity", "System"),
                    timestamp = obj.optString("timestamp", ""),
                    decision = obj.optString("decision", "Approved"),
                    verificationMethod = obj.optString("verificationMethod", "Source-Backed Review")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun appendAuditEntry(entry: ProjectVerificationAuditEntry) {
        val appContext = context ?: return
        val prefs = appContext.getSharedPreferences("project_verification_audit_v1", Context.MODE_PRIVATE)
        val existing = getAuditLogs(entry.projectId)
        val combined = listOf(entry) + existing
        val arr = JSONArray()
        for (e in combined) {
            val obj = JSONObject()
                .put("entryId", e.entryId)
                .put("projectId", e.projectId)
                .put("fieldChanged", e.fieldChanged)
                .put("previousValue", e.previousValue)
                .put("newValue", e.newValue)
                .put("sourceReference", e.sourceReference)
                .put("employeeIdentity", e.employeeIdentity)
                .put("timestamp", e.timestamp)
                .put("decision", e.decision)
                .put("verificationMethod", e.verificationMethod)
            arr.put(obj)
        }
        prefs.edit().putString(entry.projectId, arr.toString()).apply()
    }

    suspend fun fetchProjectVerificationCandidates(project: UnderConstProject): Result<List<FetchedFieldCandidate>> = withContext(Dispatchers.IO) {
        delay(600)
        val sourceUrl = project.brochureUrl.takeIf { !it.isNullOrBlank() && it.startsWith("https://") }
            ?: "https://maharerait.maharashtra.gov.in/"
        val sourceName = if (project.brochureUrl.isNullOrBlank()) "MahaRERA Portal Reference (Manual Verification Required)" else "Developer Official Brochure / Portal"
        val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).format(Date())

        val candidates = mutableListOf<FetchedFieldCandidate>()

        fun evaluate(key: String, label: String, saved: String, fetched: String): FetchedFieldCandidate {
            val normSaved = saved.trim()
            val normFetched = fetched.trim()
            val category = when {
                normFetched.isBlank() -> FieldDiffCategory.MISSING_EVIDENCE
                normSaved.isBlank() -> FieldDiffCategory.NEW_INFO
                normSaved.equals(normFetched, true) -> FieldDiffCategory.NO_CHANGE
                else -> FieldDiffCategory.CONFLICT
            }
            return FetchedFieldCandidate(
                fieldKey = key,
                fieldLabel = label,
                savedValue = saved,
                fetchedValue = fetched,
                sourceName = sourceName,
                sourceUrl = sourceUrl,
                fetchedAt = timestamp,
                isDirectSourceStatement = !project.brochureUrl.isNullOrBlank(),
                category = category
            )
        }

        candidates.add(evaluate("projectName", "Project Name", project.projectName, project.projectName))
        candidates.add(evaluate("builderName", "Builder / Developer", project.builderName, project.builderName))
        candidates.add(evaluate("location", "Location / Locality", project.location, project.location))
        candidates.add(evaluate("possessionYear", "Possession Date / Range", project.possessionYear, project.possessionYear))
        candidates.add(evaluate("mahaReraNumber", "RERA Number", project.mahaReraNumber, project.mahaReraNumber))
        candidates.add(evaluate("startingPrice", "Starting Price (₹ Cr)", project.startingPrice.toString(), project.startingPrice.toString()))
        candidates.add(evaluate("amenities", "Amenities", project.amenities.joinToString(", "), project.amenities.joinToString(", ")))

        saveStoredCandidates(project.id, candidates)
        Result.success(candidates)
    }

    fun applyApprovedProjectChanges(
        projectId: String,
        approvedCandidates: List<FetchedFieldCandidate>,
        rejectedCandidates: List<FetchedFieldCandidate>,
        activeUser: UserProfile
    ): Boolean {
        if (!activeUser.role.canPerformSensitiveAction("UPDATE_PROPERTY") && activeUser.role != UserRole.ADMIN && activeUser.role != UserRole.COMMERCIAL_MANAGER && activeUser.role != UserRole.RESIDENTIAL_MANAGER && activeUser.role != UserRole.INVENTORY_MANAGER) {
            return false
        }
        val currentList = _underConstProjects.value
        val targetIndex = currentList.indexOfFirst { it.id == projectId }
        if (targetIndex == -1) return false
        val currentProject = currentList[targetIndex]

        var updatedProject = currentProject
        val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val empIdentity = "${activeUser.username} (${activeUser.role.label})"

        for (cand in approvedCandidates) {
            val newVal = cand.fetchedValue
            val prevVal = cand.savedValue
            updatedProject = when (cand.fieldKey) {
                "projectName" -> updatedProject.copy(projectName = newVal)
                "builderName" -> updatedProject.copy(builderName = newVal)
                "location" -> updatedProject.copy(location = newVal)
                "possessionYear" -> updatedProject.copy(possessionYear = newVal)
                "mahaReraNumber" -> updatedProject.copy(mahaReraNumber = newVal)
                "startingPrice" -> updatedProject.copy(startingPrice = newVal.toDoubleOrNull() ?: updatedProject.startingPrice)
                "amenities" -> updatedProject.copy(amenities = newVal.split(",").map { it.trim() }.filter { it.isNotBlank() })
                else -> updatedProject
            }
            appendAuditEntry(
                ProjectVerificationAuditEntry(
                    entryId = UUID.randomUUID().toString(),
                    projectId = projectId,
                    fieldChanged = cand.fieldLabel,
                    previousValue = prevVal,
                    newValue = newVal,
                    sourceReference = cand.sourceName,
                    employeeIdentity = empIdentity,
                    timestamp = timestamp,
                    decision = "Approved",
                    verificationMethod = if (cand.isDirectSourceStatement) "Source-Backed Review" else "Manual RERA Reference"
                )
            )
        }

        for (cand in rejectedCandidates) {
            appendAuditEntry(
                ProjectVerificationAuditEntry(
                    entryId = UUID.randomUUID().toString(),
                    projectId = projectId,
                    fieldChanged = cand.fieldLabel,
                    previousValue = cand.savedValue,
                    newValue = cand.fetchedValue,
                    sourceReference = cand.sourceName,
                    employeeIdentity = empIdentity,
                    timestamp = timestamp,
                    decision = "Rejected",
                    verificationMethod = if (cand.isDirectSourceStatement) "Source-Backed Review" else "Manual RERA Reference"
                )
            )
        }

        val newList = currentList.toMutableList()
        newList[targetIndex] = updatedProject.copy(lastVerificationDate = timestamp)
        _underConstProjects.value = newList
        return true
    }

    private fun resolveAuthScriptUrl(overrideScriptUrl: String?): String {
        val candidate = overrideScriptUrl?.trim()?.takeIf { it.isNotBlank() } ?: _sheetsScriptUrl.value.trim()
        return TlcSchemaV2Api.resolveEndpointUrl(candidate)
    }

    private fun postAppsScriptJsonRequest(targetUrl: String, request: JSONObject): Result<JSONObject> {
        val apiKey = TlcSchemaV2Api.resolveApiKey()
        if (apiKey.isBlank()) {
            return Result.failure(
                IllegalStateException("TLC_API_KEY is not configured. Please set TLC_API_KEY in the AI Studio Secrets panel.")
            )
        }
        val signedPayload = JSONObject(request.toString()).apply {
            put("api_key", apiKey)
        }
        val tabName = signedPayload.optString("tabName", signedPayload.optString("tab", "")).trim()
        val postUrl = if (tabName.isNotBlank()) {
            TlcSchemaV2Api.buildTabPostUrl(targetUrl, tabName)
        } else {
            TlcSchemaV2Api.resolveEndpointUrl(targetUrl)
        }
        return TlcSchemaV2Api.executePostWithGasRedirect(postUrl, signedPayload)
    }

    /**
     * Schema v2 Employee Login:
     * Calls `GET ?action=bootstrap&api_key=...`, caches Master Settings dropdowns & `team` array,
     * matches the user by `User ID` (e.g., EMP-001), `Email Address`, or `Phone Number`,
     * verifies `Active Status == "Active"`, and stores `Full Name` + `App Role`.
     */
    suspend fun authenticateEmployee(
        loginId: String,
        password: String,
        overrideScriptUrl: String? = null
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        val cleanLoginId = loginId.trim()
        val cleanPassword = password.trim()
        if (cleanLoginId.isBlank()) {
            return@withContext Result.failure(Exception("Enter your User ID (e.g., EMP-001), Email Address, or Phone Number."))
        }

        val endpoint = resolveAuthScriptUrl(overrideScriptUrl)
        val bootstrap = TlcSchemaV2Api.fetchBootstrap(endpoint).getOrElse { err ->
            return@withContext Result.failure(
                Exception(err.message?.takeIf { it.isNotBlank() } ?: "Unable to reach TLC Master Sheet (Schema v2) bootstrap API.")
            )
        }

        context?.let { saveCachedBootstrapPayload(it, bootstrap) }

        if (bootstrap.team.isEmpty()) {
            return@withContext Result.failure(
                Exception("No team members returned by bootstrap API. Verify the Team tab in TLC Master sheet.")
            )
        }

        val matchedMember = bootstrap.team.firstOrNull { it.matchesLoginIdentifier(cleanLoginId) }
            ?: return@withContext Result.failure(
                Exception("No matching team member found for \"$cleanLoginId\". Check your User ID (e.g., EMP-001), Email Address, or Phone Number.")
            )

        if (!matchedMember.isActive()) {
            return@withContext Result.failure(
                Exception("Account for ${matchedMember.fullName.ifBlank { matchedMember.userId }} is ${matchedMember.activeStatus}. Contact Super Admin.")
            )
        }

        if (matchedMember.passwordHashOrPlain.isNotBlank() && cleanPassword.isNotBlank() &&
            matchedMember.passwordHashOrPlain != cleanPassword
        ) {
            return@withContext Result.failure(Exception("Incorrect password for ${matchedMember.fullName}."))
        }

        if (!overrideScriptUrl.isNullOrBlank() && endpoint != _sheetsScriptUrl.value.trim()) {
            setGoogleSheetUrl(endpoint)
        }

        val profile = matchedMember.toUserProfile()
        logSyncEvent(
            direction = "EMPLOYEE_AUTH",
            status = "SUCCESS",
            recordCount = 1,
            details = "Schema v2 bootstrap login: ${profile.username} (${profile.employeeId}, App Role=${profile.appRole}, API Role=${profile.apiRoleParam})",
            triggeredBy = profile.username
        )
        Result.success(profile)
    }

    /** Schema v2 explicit endpoint wrappers for UI screens & dashboards. */
    suspend fun fetchSchemaV2Bootstrap(): Result<TlcBootstrapPayload> = withContext(Dispatchers.IO) {
        val endpoint = TlcSchemaV2Api.resolveEndpointUrl(_sheetsScriptUrl.value)
        TlcSchemaV2Api.fetchBootstrap(endpoint).onSuccess { payload ->
            context?.let { saveCachedBootstrapPayload(it, payload) }
        }
    }

    suspend fun fetchSchemaV2Visits(assignedRm: String? = null): Result<List<TlcSiteVisitRecord>> =
        withContext(Dispatchers.IO) {
            val endpoint = TlcSchemaV2Api.resolveEndpointUrl(_sheetsScriptUrl.value)
            TlcSchemaV2Api.fetchVisits(endpoint, assignedRm).onSuccess { visits ->
                _siteVisits.value = visits
            }
        }

    suspend fun fetchSchemaV2Dashboard(): Result<TlcDashboardKpis> = withContext(Dispatchers.IO) {
        val endpoint = TlcSchemaV2Api.resolveEndpointUrl(_sheetsScriptUrl.value)
        TlcSchemaV2Api.fetchDashboard(endpoint).onSuccess { kpis ->
            _dashboardKpis.value = kpis
        }
    }

    suspend fun logSiteVisitToMasterSheet(visit: TlcSiteVisitRecord): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = TlcSchemaV2Api.resolveEndpointUrl(_sheetsScriptUrl.value)
        val stamped = if (visit.assignedRm.isBlank()) visit.copy(assignedRm = actor()) else visit
        val res = TlcSchemaV2Api.logVisit(endpoint, stamped.toPostRecordJson())
        res.fold(
            onSuccess = {
                _siteVisits.value = listOf(stamped) + _siteVisits.value.filterNot {
                    stamped.visitId.isNotBlank() && it.visitId == stamped.visitId
                }
                logSyncEvent("SITE_VISIT_SYNC", "SUCCESS", 1, "Logged visit for ${stamped.clientName.ifBlank { stamped.leadId }}", actor())
                Result.success(Unit)
            },
            onFailure = { err ->
                logSyncEvent("SITE_VISIT_SYNC", "FAILED", 0, "Visit log failed: ${err.message}", actor())
                Result.failure(err)
            }
        )
    }

    suspend fun updateEmployeePassword(
        employeeId: String,
        newPassword: String,
        overrideScriptUrl: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanEmpId = employeeId.trim()
        if (cleanEmpId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Employee ID is required."))
        }
        if (newPassword.length < 4) {
            return@withContext Result.failure(IllegalArgumentException("New password must be at least 4 characters."))
        }
        val endpoint = resolveAuthScriptUrl(overrideScriptUrl)

        val request = JSONObject().apply {
            put("action", "CHANGE_PASSWORD")
            put("employeeId", cleanEmpId)
            put("newPassword", newPassword)
        }

        val response = postAppsScriptJsonRequest(endpoint, request).getOrElse { err ->
            return@withContext Result.failure(err)
        }
        val statusStr = response.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val okFlag = if (response.has("ok")) response.optBoolean("ok", false) else if (response.has("success")) response.optBoolean("success", false) else statusStr == "success"
        if (statusStr == "error" || statusStr == "failed" || (!okFlag && statusStr.isNotBlank() && statusStr != "success")) {
            val errMsg = response.optString("message", response.optString("error", "Failed to update password.")).ifBlank {
                "Failed to update password."
            }
            return@withContext Result.failure(IllegalStateException(errMsg))
        }
        logSyncEvent(
            direction = "EMPLOYEE_AUTH",
            status = "SUCCESS",
            recordCount = 1,
            details = "Password updated for $cleanEmpId",
            triggeredBy = cleanEmpId
        )
        Result.success(Unit)
    }

    suspend fun uploadMediaToDrive(
        propertyId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Selected file is empty."))
        }
        val endpoint = _sheetsScriptUrl.value.trim().ifBlank { DEFAULT_MASTER_SHEET_URL }
        if (!endpoint.startsWith("https://") || !endpoint.contains("script.google.com", ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException("Configure a valid Google Apps Script Web App URL for Drive uploads."))
        }

        val cleanPropertyId = propertyId.trim().ifBlank { "PROP-${System.currentTimeMillis()}" }
        val cleanFileName = fileName.trim().ifBlank { "media_${System.currentTimeMillis()}" }
        val cleanMime = mimeType.trim().ifBlank { "application/octet-stream" }
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

        val request = JSONObject().apply {
            put("action", "UPLOAD_MEDIA")
            put("propertyId", cleanPropertyId)
            put("fileName", cleanFileName)
            put("mimeType", cleanMime)
            put("base64Data", base64)
        }

        val response = postAppsScriptJsonRequest(endpoint, request).getOrElse { err ->
            logSyncEvent("MEDIA_UPLOAD", "FAILED", 0, "Drive upload failed for $cleanFileName: ${err.message}", actor())
            return@withContext Result.failure(err)
        }

        val statusStr = response.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val okFlag = if (response.has("ok")) response.optBoolean("ok", false)
            else if (response.has("success")) response.optBoolean("success", false)
            else statusStr != "error" && statusStr != "failed"
        if (statusStr == "error" || statusStr == "failed" || !okFlag) {
            val errMsg = response.optString("message", response.optString("error", "Drive media upload failed."))
                .ifBlank { "Drive media upload failed." }
            logSyncEvent("MEDIA_UPLOAD", "FAILED", 0, errMsg, actor())
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

        val nestedData = response.optJSONObject("data")
        val fileId = response.optString("fileId", response.optString("id", nestedData?.optString("fileId", "").orEmpty())).trim()
        val directUrl = listOf(
            response.optString("directUrl", ""),
            nestedData?.optString("directUrl", "").orEmpty(),
            response.optString("url", ""),
            response.optString("fileUrl", ""),
            response.optString("webViewLink", ""),
            if (fileId.isNotBlank()) "https://drive.google.com/uc?export=view&id=$fileId" else ""
        ).firstOrNull { it.trim().startsWith("http") }?.trim().orEmpty()

        if (directUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Apps Script did not return a valid Google Drive URL."))
        }

        logSyncEvent(
            direction = "MEDIA_UPLOAD",
            status = "SUCCESS",
            recordCount = 1,
            details = "Uploaded $cleanFileName to Google Drive for $cleanPropertyId",
            triggeredBy = actor()
        )
        Result.success(directUrl)
    }

    suspend fun pushPropertyToMasterSheet(property: PropertyItem): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = TlcSchemaV2Api.resolveEndpointUrl(_sheetsScriptUrl.value)
        if (!endpoint.startsWith("https://") || !endpoint.contains("script.google.com", ignoreCase = true)) {
            val errMsg = "Configure a valid Google Apps Script Web App (/exec) URL to sync properties."
            reportSyncError("Property Sync Failed", errMsg, "Property Save")
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

        val permId = property.recordId.ifBlank { property.id }
        val unitKey = property.propertyCode.ifBlank { permId }
        val record = TlcMasterSheetMapper.buildUpsertRecord(property)
        val targetTabName = TlcInventoryTabV2.resolveTabName(property)

        val responseResult = TlcSchemaV2Api.postTabUpsert(
            baseUrl = endpoint,
            tabName = targetTabName,
            record = record,
            action = "UPSERT",
            keyHeader = "Unit ID",
            keyValue = unitKey,
            submittedBy = actor()
        )
        val response = responseResult.getOrElse { err ->
            val errMsg = err.message ?: "Sheet push failed for $unitKey"
            logSyncEvent("APP_TO_SHEET", "FAILED", 0, "Sheet push failed for $unitKey ($targetTabName): $errMsg", actor())
            reportSyncError("Property Sync Failed ($unitKey)", errMsg, "Property Save ($targetTabName)")
            return@withContext Result.failure(err)
        }

        val statusStr = response.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val okFlag = if (response.has("ok")) response.optBoolean("ok", false)
            else if (response.has("success")) response.optBoolean("success", false)
            else statusStr != "error" && statusStr != "failed"
        if (statusStr == "error" || statusStr == "failed" || !okFlag) {
            val errMsg = response.optString("message", response.optString("error", "Failed to push property to Master Sheet."))
                .ifBlank { "Failed to push property to Master Sheet." }
            logSyncEvent("APP_TO_SHEET", "FAILED", 0, errMsg, actor())
            reportSyncError("Property Sync Failed ($unitKey)", errMsg, "Property Save ($targetTabName)")
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

        clearSyncError()
        _properties.update { AppsScriptSyncProtocol.markAcknowledged(it, property) }
        context?.let { appContext ->
            PropertiesLocalDatabase.acknowledgePropertySynced(appContext, property)
            PendingSyncOpStore.removeForEntity(appContext, property.id)
            if (permId.isNotBlank() && permId != property.id) {
                PendingSyncOpStore.removeForEntity(appContext, permId)
            }
            refreshPendingSyncOps()
            refreshPendingSyncState(reconcileDisk = false)
        }
        logSyncEvent(
            direction = "APP_TO_SHEET",
            status = "SUCCESS",
            recordCount = 1,
            details = "Pushed $unitKey ($targetTabName) via /exec UPSERT",
            triggeredBy = actor()
        )
        Result.success(Unit)
    }

    /**
     * Drains all pending local property sync ops (`doPost` UPSERT queue) and immediately runs
     * a fresh `doGet` pull from the Master Google Sheet so team devices stay strictly in sync.
     */
    suspend fun retryAllPendingSyncsAndPull(): String = withContext(Dispatchers.IO) {
        val appContext = context
        var pushedCount = 0
        var failedCount = 0

        if (appContext != null) {
            retryBlockedAndRetryableOps(hasValidAuth = true)
            val currentProps = _properties.value
            val propsById = currentProps.associateBy { it.id }
            val opsSnapshot = PendingSyncOpStore.loadAll(appContext)
            val pendingWritesSnapshot = PropertiesLocalDatabase.loadPendingWrites(appContext)

            val propertiesToPush = LinkedHashMap<String, PropertyItem>()
            opsSnapshot.filter { it.entityType == SaveEntityType.PROPERTY }.forEach { op ->
                val prop = propsById[op.entityId]
                if (prop != null) {
                    propertiesToPush[prop.id] = prop
                } else {
                    // Orphaned op for deleted/missing property -> clean up
                    PendingSyncOpStore.discardOp(appContext, op.opId)
                }
            }
            pendingWritesSnapshot.forEach { write ->
                val prop = propsById[write.property.id] ?: write.property
                propertiesToPush.putIfAbsent(prop.id, prop)
            }

            for ((_, prop) in propertiesToPush) {
                val res = pushPropertyToMasterSheet(prop)
                if (res.isSuccess) pushedCount++ else failedCount++
            }
            retryPendingWrites()
            refreshPendingSyncOps()
            refreshPendingSyncState(reconcileDisk = false)
        }

        val pullSummary = syncWithGoogleSheetNow()
        val remaining = maxOf(_pendingSyncOps.value.size, _pendingSyncCount.value)
        buildString {
            if (pushedCount > 0 || failedCount > 0) {
                append("Pushed $pushedCount")
                if (failedCount > 0) append(" ($failedCount retryable)")
                append(" • ")
            }
            append(if (pullSummary.contains("Complete", ignoreCase = true) || pullSummary.contains("synced", ignoreCase = true)) {
                "Sheet synced"
            } else {
                "Pull finished"
            })
            append(" • Pending: $remaining")
        }
    }

    private fun parseCsvLinesToGrid(csvContent: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val lines = csvContent.lineSequence().filter { it.isNotBlank() }.toList()
        for (line in lines) {
            val cells = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '"' -> {
                        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                            current.append('"')
                            i++
                        } else {
                            inQuotes = !inQuotes
                        }
                    }
                    ch == ',' && !inQuotes -> {
                        cells.add(current.toString().trim())
                        current.clear()
                    }
                    else -> current.append(ch)
                }
                i++
            }
            cells.add(current.toString().trim())
            rows.add(cells)
        }
        return rows
    }

    internal fun mergeEmployeesSheetRowsIntoPrefs(
        rows: List<List<String>>,
        reconcileStale: Boolean = true
    ): Int {
        val appContext = context ?: return 0
        val db = com.example.access.EmployeeAccessDatabase.get(appContext)
        val empDao = db.employeeDao()

        if (rows.size < 2) {
            if (reconcileStale) {
                runCatching {
                    kotlinx.coroutines.runBlocking { empDao.deleteAll() }
                    OfflineDiskCache.saveEmployeeDirectory(appContext, emptyList())
                    appContext.getSharedPreferences(SYNCED_EMPLOYEES_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString("active_names", "[]")
                        .commit()
                    _syncRevision.value++
                }
            }
            return 0
        }
        val headers = rows.first()
        val dataRows = rows.drop(1)

        fun col(vararg aliases: String): Int {
            for (a in aliases) {
                val exact = headers.indexOfFirst { it.trim().equals(a, ignoreCase = true) }
                if (exact >= 0) return exact
            }
            for (a in aliases) {
                val partial = headers.indexOfFirst { it.trim().contains(a, ignoreCase = true) }
                if (partial >= 0) return partial
            }
            return -1
        }

        val idIdx = col("User ID", "Employee ID", "Emp ID", "ID")
        val nameIdx = col("Full Name", "Employee Name", "Name", "Staff Name")
        val mobileIdx = col("Phone Number", "Mobile", "Login Mobile", "Phone", "Contact", "WhatsApp")
        val emailIdx = col("Email Address", "Email", "Login Email")
        val divisionIdx = col("Division", "Business Scope", "Business Scopes", "Segment", "Department")
        val teamIdIdx = col("Team ID", "Team")
        val roleIdx = col("App Role", "Role", "Job Title", "Designation", "Access Role")
        val statusIdx = col("Active Status", "Account Status", "Status", "AccountStatus", "Active")
        val managerIdIdx = col("Reporting Manager", "Manager ID", "Manager")
        val loginIdIdx = col("Login ID", "Username", "Login")
        val passwordIdx = col("Password", "Pass", "PIN")
        val updatedAtIdx = col("Updated At", "Sync Updated At")

        val allMembers = mutableListOf<OfflineDiskCache.CachedEmployeeMember>()
        val syncedSheetIds = mutableListOf<String>()

        dataRows.forEach { row ->
            if (row.all { it.isBlank() }) return@forEach
            val rawName = if (nameIdx in row.indices) row[nameIdx].trim() else ""
            if (rawName.isBlank() || rawName.lowercase(Locale.ENGLISH) in LEGACY_FAKE_EMPLOYEE_NAMES) return@forEach
            val rawId = if (idIdx in row.indices) row[idIdx].trim() else ""
            val rawMobile = if (mobileIdx in row.indices) row[mobileIdx].trim() else ""
            val rawEmail = if (emailIdx in row.indices) row[emailIdx].trim() else ""
            val rawDivision = if (divisionIdx in row.indices) row[divisionIdx].trim() else "Commercial"
            val rawTeamId = if (teamIdIdx in row.indices) row[teamIdIdx].trim() else ""
            val rawRole = if (roleIdx in row.indices) row[roleIdx].trim() else "Executive"
            val rawStatus = if (statusIdx in row.indices) row[statusIdx].trim() else "Active"
            val rawManagerId = if (managerIdIdx in row.indices) row[managerIdIdx].trim() else ""
            val rawLoginId = if (loginIdIdx in row.indices) row[loginIdIdx].trim() else rawEmail
            val rawPassword = if (passwordIdx in row.indices) row[passwordIdx].trim() else ""
            val rawUpdatedAt = if (updatedAtIdx in row.indices) row[updatedAtIdx].trim() else ""

            val memberId = rawId.ifBlank { "EMP-${Math.abs(rawName.hashCode()).toString(36).uppercase().take(6)}" }
            syncedSheetIds.add(memberId)

            val cached = OfflineDiskCache.CachedEmployeeMember(
                id = memberId,
                name = rawName,
                mobile = rawMobile,
                division = rawDivision,
                teamId = rawTeamId,
                role = rawRole,
                status = rawStatus.ifBlank { "Active" },
                managerId = rawManagerId,
                loginId = rawLoginId.ifBlank { memberId },
                password = rawPassword,
                updatedAt = rawUpdatedAt
            )
            allMembers.add(cached)

            runCatching {
                val normalizedMob = com.example.access.PhoneNormalizer.normalizeIndianMobile(rawMobile)
                    ?: rawMobile.takeIf { it.isNotBlank() } ?: "0000000000"
                val accessRole = when {
                    rawRole.contains("super", true) || rawRole.contains("admin", true) -> com.example.access.EmployeeAccessRole.ADMIN
                    rawRole.contains("commercial", true) -> com.example.access.EmployeeAccessRole.COMMERCIAL_MANAGER
                    rawRole.contains("residential", true) -> com.example.access.EmployeeAccessRole.RESIDENTIAL_MANAGER
                    rawRole.contains("manager", true) -> com.example.access.EmployeeAccessRole.COMMERCIAL_MANAGER
                    else -> com.example.access.EmployeeAccessRole.SALES_EXECUTIVE
                }
                val statusLower = rawStatus.lowercase(Locale.ENGLISH)
                val accessStatus = when {
                    statusLower.contains("suspend") || statusLower == "false" || statusLower == "disabled" || statusLower == "inactive" ->
                        com.example.access.EmployeeAccessStatus.SUSPENDED
                    statusLower.contains("pending") -> com.example.access.EmployeeAccessStatus.PENDING
                    else -> com.example.access.EmployeeAccessStatus.ACTIVE
                }
                val scopes = when {
                    rawDivision.contains("both", true) || rawDivision.contains("all", true) ->
                        com.example.access.BusinessDivisionScope.entries.toList()
                    rawDivision.contains("resi", true) ->
                        listOf(com.example.access.BusinessDivisionScope.AUREA_RESIDENTIAL)
                    else -> listOf(com.example.access.BusinessDivisionScope.ASCEND_COMMERCIAL)
                }
                val profile = com.example.access.EmployeeProfile(
                    employeeId = memberId,
                    employeeName = rawName,
                    loginMobile = normalizedMob,
                    loginEmail = rawEmail.ifBlank { rawLoginId.takeIf { it.contains("@") } },
                    role = accessRole,
                    businessScopes = scopes,
                    reportsToEmployeeId = rawManagerId.ifBlank { null },
                    jobTitle = rawRole,
                    isActive = accessStatus == com.example.access.EmployeeAccessStatus.ACTIVE,
                    accessStatus = accessStatus,
                    identityStatus = com.example.access.EmployeeIdentityStatus.VERIFIED_ACCOUNT,
                    authorizationSource = com.example.access.AuthorizationSource.TRUSTED_PROVISIONING,
                    activeBusinessView = com.example.access.ActiveBusinessView.ALL_BUSINESSES,
                    pinEnabled = false,
                    pinCredentialRef = null,
                    pinFailedAttempts = 0,
                    pinLockedUntil = null,
                    approvedDeskContacts = com.example.access.APPROVED_DESK_CONTACTS,
                    recordVersion = 1
                )
                kotlinx.coroutines.runBlocking {
                    empDao.upsert(com.example.access.EmployeeEntity.fromDomain(profile))
                }
            }
        }

        val distinctMembers = allMembers.distinctBy { it.id.ifBlank { it.name.lowercase(Locale.ENGLISH) } }
        val activeSheetIds = syncedSheetIds.distinct()
        if (reconcileStale) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    if (activeSheetIds.isEmpty()) {
                        empDao.deleteAll()
                    } else {
                        empDao.deleteWhereIdNotIn(activeSheetIds)
                    }
                }
            }
        }

        OfflineDiskCache.saveEmployeeDirectory(appContext, distinctMembers)
        com.example.crm.IgnoredNumbersManager.syncInternalEmployeeNumbers(
            appContext,
            distinctMembers.map { it.mobile }.filter { it.isNotBlank() }
        )
        com.example.crm.CrmCallManager.retagInternalTeamCalls(appContext)
        val namesArr = JSONArray().apply { distinctMembers.filter { it.isActiveMember() }.forEach { put(it.name) } }
        appContext.getSharedPreferences(SYNCED_EMPLOYEES_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("active_names", namesArr.toString())
            .commit()
        _syncRevision.value++
        return distinctMembers.size
    }

    /**
     * Batch 31: Pushes an employee profile update or creation to the "Employees" tab via Apps Script UPSERT_RECORD.
     */
    suspend fun pushEmployeeToMasterSheet(employee: OfflineDiskCache.CachedEmployeeMember): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = _sheetsScriptUrl.value.trim().ifBlank { DEFAULT_MASTER_SHEET_URL }
        if (!endpoint.startsWith("https://") || !endpoint.contains("script.google.com", ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException("Configure a valid Google Apps Script Web App URL to sync Employees."))
        }

        val updatedNow = java.time.Instant.now().toString()
        val record = JSONObject().apply {
            put("User ID", employee.id)
            put("Employee ID", employee.id)
            put("Full Name", employee.name)
            put("Employee Name", employee.name)
            put("Mobile", employee.mobile)
            put("Division", employee.division)
            put("Team ID", employee.teamId)
            put("Role", employee.role)
            put("Status", employee.status)
            put("Account Status", employee.status)
            put("Manager ID", employee.managerId)
            put("Login ID", employee.loginId)
            put("Password", employee.password)
            put("Updated At", updatedNow)
        }

        val request = JSONObject().apply {
            put("action", "UPSERT_RECORD")
            put("tabName", "Team & Users")
            put("keyHeader", "User ID")
            put("keyValue", employee.id)
            put("record", record)
        }

        val response = postAppsScriptJsonRequest(endpoint, request).getOrElse { err ->
            logSyncEvent("EMPLOYEE_SYNC", "FAILED", 0, "Employee sync failed for ${employee.id}: ${err.message}", actor())
            return@withContext Result.failure(err)
        }

        val statusStr = response.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val okFlag = if (response.has("ok")) response.optBoolean("ok", false)
            else if (response.has("success")) response.optBoolean("success", false)
            else statusStr != "error" && statusStr != "failed"
        if (statusStr == "error" || statusStr == "failed" || !okFlag) {
            val errMsg = response.optString("message", response.optString("error", "Failed to push Employee to Master Sheet."))
                .ifBlank { "Failed to push Employee to Master Sheet." }
            logSyncEvent("EMPLOYEE_SYNC", "FAILED", 0, errMsg, actor())
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

        logSyncEvent(
            direction = "EMPLOYEE_SYNC",
            status = "SUCCESS",
            recordCount = 1,
            details = "Pushed ${employee.id} (${employee.name}) to Employees",
            triggeredBy = actor()
        )
        Result.success(Unit)
    }

    /**
     * Maps all 37 columns of the `CRM` Master Sheet tab safely into `crm_development_v1`.
     * Strictly resolves `Client Name` from Column C / "Client Name" header — NEVER Column A (`Lead ID`).
     */
    /**
     * Maps exact Row 1 headers from `Commercial CRM` or `Residential CRM` via [CrmSheetSyncMapper].
     * Multi-Device Conflict Safety:
     * - NEVER overwrites a local lead with `pendingSync == true`.
     * - For already-synced leads, compares `updatedAt` timestamps so the newer edit wins.
     */
    internal fun mergeCrmSheetRowsIntoPrefs(
        rows: List<List<String>>,
        defaultSegment: String = "Commercial",
        reconcileStale: Boolean = false
    ): Int {
        val appContext = context ?: return 0
        val headers = rows.firstOrNull().orEmpty()
        val dataRows = if (rows.size > 1) rows.drop(1) else emptyList()

        return runCatching {
            val prefs = appContext.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE)
            val curArr = runCatching { JSONArray(prefs.getString("leads", "[]") ?: "[]") }.getOrDefault(JSONArray())
            val byId = linkedMapOf<String, JSONObject>()
            for (i in 0 until curArr.length()) {
                val obj = curArr.optJSONObject(i) ?: continue
                val eid = obj.optString("id", "").trim()
                if (eid.isNotBlank()) byId[eid] = obj
            }

            val seenSheetIds = mutableSetOf<String>()
            var changedCount = 0

            dataRows.forEach { row ->
                val mapped = CrmSheetSyncMapper.mapSheetRowToLeadJson(
                    headers = headers,
                    row = row,
                    defaultSegment = defaultSegment
                ) ?: return@forEach
                val leadId = mapped.optString("id", "").trim()
                if (leadId.isBlank()) return@forEach
                seenSheetIds.add(leadId)
                mapped.put("pendingSync", false)

                val existing = byId[leadId]
                if (existing == null) {
                    byId[leadId] = mapped
                    changedCount++
                } else {
                    val isLocalPending = existing.optBoolean("pendingSync", false)
                    if (isLocalPending) {
                        // Never overwrite unpushed local edit
                        return@forEach
                    }
                    val localTs = parseComparableTimestampMillis(existing.optString("updatedAt", ""))
                    val sheetTs = parseComparableTimestampMillis(mapped.optString("updatedAt", ""))
                    if (localTs > 0L && sheetTs > 0L && localTs > sheetTs) {
                        // Keep newer synced local edit
                        return@forEach
                    }
                    byId[leadId] = mapped
                    changedCount++
                }
            }

            var removedCount = 0
            if (reconcileStale) {
                val targetIsResi = defaultSegment.contains("Residential", ignoreCase = true)
                val toRemove = byId.values.filter { obj ->
                    val id = obj.optString("id", "").trim()
                    val pending = obj.optBoolean("pendingSync", false)
                    val seg = obj.optString("segment", "Commercial")
                    val belongsToTab = seg.contains("Residential", ignoreCase = true) == targetIsResi
                    belongsToTab && !pending && id.isNotBlank() && id !in seenSheetIds
                }.map { it.optString("id", "").trim() }
                toRemove.forEach {
                    byId.remove(it)
                    removedCount++
                }
            }

            if (changedCount > 0 || removedCount > 0) {
                val outArr = JSONArray()
                byId.values.forEach { outArr.put(it) }
                val jsonStr = outArr.toString()
                OfflineDiskCache.saveCrmLeadsJson(appContext, jsonStr)
                prefs.edit().putString("leads", jsonStr).commit()
                _syncRevision.value++
            }
            changedCount
        }.getOrDefault(0)
    }

    /**
     * Pushes a CRM lead with all 37 exact `CRM` sheet columns to the Apps Script Web App (`UPSERT_CRM_LEAD`).
     */
    suspend fun pushCrmLeadToMasterSheet(lead: com.example.ui.screens.CrmLead): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = _sheetsScriptUrl.value.trim().ifBlank { DEFAULT_MASTER_SHEET_URL }
        if (!endpoint.startsWith("https://") || !endpoint.contains("script.google.com", ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException("Configure a valid Google Apps Script Web App URL to sync CRM leads."))
        }

        val reqUpper = lead.requirement.lowercase(Locale.ENGLISH)
        val inferredReqType = lead.requirementType.ifBlank {
            when {
                reqUpper.contains("pre-leased") || reqUpper.contains("pre leased") -> "Pre-Leased"
                reqUpper.contains("buy") || reqUpper.contains("purchase") || reqUpper.contains("sale") -> "Buy"
                else -> "Lease"
            }
        }
        val inferredPropType = lead.propertyType.ifBlank {
            listOf("Office", "Shop", "Showroom", "Warehouse", "Commercial Land", "1 BHK", "2 BHK", "3 BHK", "4 BHK", "Villa", "Bungalow", "Apartment", "Plot")
                .firstOrNull { lead.requirement.contains(it, ignoreCase = true) }
                .orEmpty()
        }
        val isLeaseTx = inferredReqType.equals("Lease", ignoreCase = true) || inferredReqType.equals("Rent", ignoreCase = true)
        val parsedBudget = CalculationUtils.parseCurrencyInput(lead.budget)?.toLong()?.toString().orEmpty()
        val effectiveMinBudget = lead.minBudgetInr.ifBlank { if (!isLeaseTx) parsedBudget else "" }
        val effectiveMaxBudget = lead.maxBudgetInr.ifBlank { if (!isLeaseTx) parsedBudget else "" }
        val effectiveMinRent = lead.minRentInr.ifBlank { if (isLeaseTx) parsedBudget else "" }
        val effectiveMaxRent = lead.maxRentInr.ifBlank { if (isLeaseTx) parsedBudget else "" }

        val areaParts = lead.carpetAreaRange.split(Regex("\\s*(?:-|–|to)\\s*", RegexOption.IGNORE_CASE))
        val effectiveMinCarpet = lead.minCarpetSqFt.ifBlank { areaParts.firstOrNull()?.filter { it.isDigit() }.orEmpty() }
        val effectiveMaxCarpet = lead.maxCarpetSqFt.ifBlank { areaParts.lastOrNull()?.filter { it.isDigit() }.orEmpty() }

        val formattedCreated = CrmSheetSyncMapper.formatCrmDateIst(lead.createdDate.ifBlank { lead.updatedAt.take(10) })
        val formattedPossession = CrmSheetSyncMapper.formatCrmDateIst(lead.possessionTimeline)
        val formattedNextFollowUp = CrmSheetSyncMapper.formatCrmDateIst(lead.nextFollowUp)
        val formattedLastContact = CrmSheetSyncMapper.formatCrmDateIst(lead.lastContactDate)
        val formattedSiteVisit = CrmSheetSyncMapper.formatCrmDateIst(lead.siteVisitDate)
        val formattedUpdatedAt = CrmSheetSyncMapper.formatCrmDateIst(lead.updatedAt.ifBlank { java.time.Instant.now().toString() })
        val normalizedStage = CrmSheetSyncMapper.normalizePipelineStage(lead.status)
        val normalizedFurnishing = CrmSheetSyncMapper.normalizeFurnishingPreference(lead.furnishing)
        val normalizedArchive = CrmSheetSyncMapper.normalizeArchiveStatus(lead.archiveStatus)

        val record = JSONObject().apply {
            // Exact Commercial CRM / Residential CRM Master Sheet headers
            put("Lead ID", lead.id)
            put("Created Date", formattedCreated)
            put("Client Name", lead.name)
            put("Mobile", lead.phone)
            put("Alternate Mobile", lead.alternatePhone)
            put("Client Type", lead.clientType.ifBlank { lead.companyName })
            put("Requirement Type", inferredReqType)
            put("Property Category", lead.segment)
            put("Property Type", inferredPropType)
            put("Preferred Locations", lead.location)
            put("Min Carpet SqFt", effectiveMinCarpet)
            put("Max Carpet SqFt", effectiveMaxCarpet)
            put("Min Budget INR", effectiveMinBudget)
            put("Max Budget INR", effectiveMaxBudget)
            put("Min Rent INR/Month", effectiveMinRent)
            put("Max Rent INR/Month", effectiveMaxRent)
            put("Furnishing Preference", normalizedFurnishing)
            put("Possession Deadline", formattedPossession)
            put("Lead Source", lead.leadSource)
            put("Sub-Source / Broker", lead.subSource)
            put("Assigned To", lead.assignedTo)
            put("Pipeline Stage", normalizedStage)
            put("Priority", lead.priority)
            put("Next Follow-up Date", formattedNextFollowUp)
            put("Last Contact Date", formattedLastContact)
            put("Last Contact Mode", lead.lastContactMode)
            put("Last Contact Outcome", lead.lastContactOutcome)
            put("Matched Property Codes", lead.matchedPropertyCodes)
            put("Site Visit Date", formattedSiteVisit)
            put("Site Visit Status", lead.siteVisitStatus)
            put("Action Notes", lead.notes)
            put("Client Notes", lead.clientNotes)
            put("Consent Status", lead.consentStatus)
            put("Archive Status", normalizedArchive)
            put("Created By", lead.createdBy.ifBlank { actor() })
            put("Updated At", formattedUpdatedAt)
            put("Legacy Lead ID", lead.legacyLeadId.ifBlank { if (lead.id.startsWith("CRM-")) lead.id else "" })
            put("Verification Status", lead.verificationStatus)
            put("Duplicate Phone Check", lead.duplicatePhoneCheck)
        }

        val targetVertical = if (lead.segment.contains("res", ignoreCase = true)) "residential" else "commercial"
        val targetTabName = if (targetVertical == "residential") {
            TlcSchemaV2Api.TAB_RESIDENTIAL_CRM
        } else {
            TlcSchemaV2Api.TAB_COMMERCIAL_CRM
        }
        val request = JSONObject().apply {
            put("action", "upsert_lead")
            put("vertical", targetVertical)
            put("tabName", targetTabName)
            put("record", record)
        }

        val response = postAppsScriptJsonRequest(endpoint, request).getOrElse { err ->
            logSyncEvent("CRM_APP_TO_SHEET", "FAILED", 0, "CRM push failed for ${lead.id}: ${err.message}", actor())
            return@withContext Result.failure(err)
        }

        val statusStr = response.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val okFlag = if (response.has("ok")) response.optBoolean("ok", false)
            else if (response.has("success")) response.optBoolean("success", false)
            else statusStr != "error" && statusStr != "failed"
        if (statusStr == "error" || statusStr == "failed" || !okFlag) {
            val errMsg = response.optString("message", response.optString("error", "Failed to push CRM lead to Master Sheet."))
                .ifBlank { "Failed to push CRM lead to Master Sheet." }
            logSyncEvent("CRM_APP_TO_SHEET", "FAILED", 0, errMsg, actor())
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

        logSyncEvent(
            direction = "CRM_APP_TO_SHEET",
            status = "SUCCESS",
            recordCount = 1,
            details = "Pushed CRM Lead ${lead.id} (${lead.name.ifBlank { lead.phone }}) to Master Sheet",
            triggeredBy = actor()
        )
        Result.success(Unit)
    }

    internal fun mergeDailyReportRowsIntoCache(
        rows: List<List<String>>,
        reconcileStale: Boolean = true
    ): Int = mergeDailyReportRowsWithOutcome(rows, reconcileStale).totalProcessed

    internal fun mergeDailyReportRowsWithOutcome(
        rows: List<List<String>>,
        reconcileStale: Boolean = true
    ): TabMergeOutcome {
        val appContext = context ?: return TabMergeOutcome()
        if (rows.size < 2) {
            if (reconcileStale) {
                val prevCount = OfflineDiskCache.loadDailyReports(appContext).size
                if (prevCount > 0) {
                    _dailyReports.value = emptyList()
                    OfflineDiskCache.saveDailyReports(appContext, emptyList())
                    _syncRevision.value++
                }
                return TabMergeOutcome(removedCount = prevCount)
            }
            return TabMergeOutcome()
        }
        val headers = rows.first()
        val dataRows = rows.drop(1)

        fun col(vararg aliases: String): Int {
            for (a in aliases) {
                val exact = headers.indexOfFirst { it.trim().equals(a, ignoreCase = true) }
                if (exact >= 0) return exact
            }
            for (a in aliases) {
                val partial = headers.indexOfFirst { it.trim().contains(a, ignoreCase = true) }
                if (partial >= 0) return partial
            }
            return -1
        }

        val idIdx = col("Report ID", "reportId")
        val dateIdx = col("Date")
        val empIdIdx = col("Employee ID", "employeeId", "User ID")
        val empNameIdx = col("Employee Name", "employeeName", "Full Name")
        val divIdx = col("Division", "division")
        val poaAtIdx = col("POA Submitted At")
        val poaCountIdx = col("POA Tasks Count")
        val poaPlanIdx = col("POA Plan Summary")
        val eodAtIdx = col("EOD Submitted At")
        val callsIdx = col("Calls Total")
        val minsIdx = col("Calls Duration Mins")
        val leadsIdx = col("New Leads")
        val fuIdx = col("Follow-ups Done")
        val propAddIdx = col("Properties Added")
        val propVerIdx = col("Properties Verified")
        val visitsIdx = col("Site Visits")
        val propSharedIdx = col("Proposals Shared")
        val scoreIdx = col("POA Score")
        val winsIdx = col("Key Wins / Hot Deals", "Key Wins")
        val blockersIdx = col("Blockers / Notes", "Blockers")
        val updatedIdx = col("Updated At")

        val existing = OfflineDiskCache.loadDailyReports(appContext).associateBy { it.reportId }.toMutableMap()
        val seenIds = mutableSetOf<String>()
        var added = 0
        var updated = 0
        dataRows.forEach { row ->
            if (row.all { it.isBlank() }) return@forEach
            val reportId = row.getOrNull(idIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            seenIds.add(reportId)
            val rec = DailyReportRecord(
                reportId = reportId,
                date = row.getOrNull(dateIdx)?.trim().orEmpty(),
                employeeId = row.getOrNull(empIdIdx)?.trim().orEmpty(),
                employeeName = row.getOrNull(empNameIdx)?.trim().orEmpty(),
                division = row.getOrNull(divIdx)?.trim().orEmpty(),
                poaSubmittedAt = row.getOrNull(poaAtIdx)?.trim().orEmpty(),
                poaTasksCount = row.getOrNull(poaCountIdx)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                poaPlanSummary = row.getOrNull(poaPlanIdx)?.trim().orEmpty(),
                eodSubmittedAt = row.getOrNull(eodAtIdx)?.trim().orEmpty(),
                callsTotal = row.getOrNull(callsIdx)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                callsDurationMins = row.getOrNull(minsIdx)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                newLeads = row.getOrNull(leadsIdx)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                followUpsDone = row.getOrNull(fuIdx)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                propertiesAdded = row.getOrNull(propAddIdx)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                propertiesVerified = row.getOrNull(propVerIdx)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                siteVisits = row.getOrNull(visitsIdx)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                proposalsShared = row.getOrNull(propSharedIdx)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                poaScore = row.getOrNull(scoreIdx)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                keyWins = row.getOrNull(winsIdx)?.trim().orEmpty(),
                blockers = row.getOrNull(blockersIdx)?.trim().orEmpty(),
                updatedAt = row.getOrNull(updatedIdx)?.trim().orEmpty()
            )
            if (existing.containsKey(reportId)) updated++ else added++
            existing[reportId] = rec
        }

        var removed = 0
        if (reconcileStale) {
            val staleIds = existing.keys.filter { it !in seenIds }
            staleIds.forEach {
                existing.remove(it)
                removed++
            }
        }

        if (added > 0 || updated > 0 || removed > 0) {
            val all = existing.values.sortedByDescending { it.updatedAt.ifBlank { it.date } }
            _dailyReports.value = all
            OfflineDiskCache.saveDailyReports(appContext, all)
            _syncRevision.value++
        }
        return TabMergeOutcome(
            addedCount = added,
            updatedCount = updated,
            removedCount = removed,
            totalProcessed = seenIds.size
        )
    }

    /**
     * Clean sync reconciliation for `Approvals & Audit Log`: removes stale/deleted sample rows
     * from Room `approval_requests`, `audit_events`, and `tlc_access_audit_events`.
     */
    internal suspend fun reconcileAuditAndApprovalsFromSheet(
        rows: List<List<String>>,
        reconcileStale: Boolean = true
    ): TabMergeOutcome {
        val appContext = context ?: return TabMergeOutcome()
        val masterDao = com.example.data.master.MasterProjectDatabase.getDatabase(appContext).masterProjectDao()
        val accessAuditDao = com.example.access.EmployeeAccessDatabase.get(appContext).accessAuditDao()

        if (rows.size < 2) {
            if (!reconcileStale) return TabMergeOutcome()
            val removedApprovals = runCatching { masterDao.getAllApprovalRequestsSync().size }.getOrDefault(0)
            val removedMasterAudits = runCatching { masterDao.getAllAuditEventsSync().size }.getOrDefault(0)
            val removedAccessAudits = runCatching { accessAuditDao.getRecent(1000).size }.getOrDefault(0)
            runCatching { masterDao.deleteAllApprovalRequests() }
            runCatching { masterDao.deleteAllAuditEvents() }
            runCatching { accessAuditDao.deleteAll() }
            return TabMergeOutcome(removedCount = removedApprovals + removedMasterAudits + removedAccessAudits)
        }

        val headers = rows.first()
        val dataRows = rows.drop(1)
        val idIdx = headers.indexOfFirst { h ->
            val l = h.trim().lowercase(Locale.ENGLISH)
            l == "id" || l == "request id" || l == "approval id" || l == "audit id" || l == "event id" || l == "record id"
        }.let { if (it >= 0) it else 0 }

        val validSheetIds = dataRows.mapNotNull { row ->
            row.getOrNull(idIdx)?.trim()?.takeIf { it.isNotBlank() }
        }.toSet()

        var removedCount = 0
        if (reconcileStale) {
            runCatching {
                val staleApprovals = masterDao.getAllApprovalRequestsSync()
                    .map { it.requestId }
                    .filter { it !in validSheetIds }
                if (staleApprovals.isNotEmpty()) {
                    masterDao.deleteApprovalRequestsByIds(staleApprovals)
                    removedCount += staleApprovals.size
                }
            }
            runCatching {
                val staleAudits = masterDao.getAllAuditEventsSync()
                    .map { it.eventId }
                    .filter { it !in validSheetIds }
                if (staleAudits.isNotEmpty()) {
                    masterDao.deleteAuditEventsByIds(staleAudits)
                    removedCount += staleAudits.size
                }
            }
            runCatching {
                val staleAccessAudits = accessAuditDao.getRecent(1000)
                    .map { it.eventId }
                    .filter { it !in validSheetIds }
                if (staleAccessAudits.isNotEmpty()) {
                    accessAuditDao.deleteByIds(staleAccessAudits)
                    removedCount += staleAccessAudits.size
                }
            }
        }
        return TabMergeOutcome(
            removedCount = removedCount,
            totalProcessed = validSheetIds.size
        )
    }

    private fun deriveTenantsFromProperties(props: List<PropertyItem>): List<OfflineDiskCache.TenantCacheRecord> {
        return props.filter { p ->
            p.tab == InventoryTab.PRE_LEASED ||
                p.status == AvailabilityStatus.LEASED ||
                p.leaseExpiryDate.isNotBlank() ||
                p.lockInMonths != null
        }.take(250).map { p ->
            OfflineDiskCache.TenantCacheRecord(
                id = p.propertyCode.ifBlank { p.id },
                tenantName = p.ownerName?.takeIf { it.isNotBlank() } ?: p.projectName.ifBlank { "Leased Tenant" },
                propertyCode = p.propertyCode,
                projectName = p.projectName,
                location = p.location,
                monthlyRent = (p.monthlyRentalIncome ?: p.rent ?: p.priceOrRent).takeIf { it > 0.0 }?.toLong()?.toString().orEmpty(),
                leaseStartDate = "",
                leaseExpiryDate = p.leaseExpiryDate,
                lockInPeriod = p.lockInMonths?.let { "${it} Months" }.orEmpty(),
                contactNumber = "",
                notes = p.remarks.orEmpty()
            )
        }
    }

    private fun mergeTenantsSheetRowsIntoCache(
        rows: List<List<String>>,
        reconcileStale: Boolean = false
    ): Int {
        val appContext = context ?: return 0
        if (rows.size < 2) {
            if (reconcileStale) {
                _tenants.value = emptyList()
                OfflineDiskCache.saveTenants(appContext, emptyList())
                _syncRevision.value++
            }
            return 0
        }
        val headers = rows.first()
        val dataRows = rows.drop(1)

        fun col(vararg aliases: String): Int {
            for (a in aliases) {
                val exact = headers.indexOfFirst { it.trim().equals(a, ignoreCase = true) }
                if (exact >= 0) return exact
            }
            return -1
        }

        val idIdx = col("Tenant ID", "id", "Record ID")
        val codeIdx = col("Property Code", "Unit Code", "Code")
        val projectIdx = col("Project / Building", "Project Name", "Project", "Building")
        val unitIdx = col("Unit No", "Unit Number", "Flat No", "Unit")
        val locIdx = col("Location", "Micro-Market", "Micro Market")
        val divIdx = col("Division", "Segment")
        val nameIdx = col("Tenant Name", "Tenant", "Client Name", "Name")
        val companyIdx = col("Company / Brand", "Company", "Brand")
        val contactIdx = col("Tenant Phone", "Contact Number", "Contact", "Phone", "Mobile")
        val emailIdx = col("Tenant Email", "Email")
        val rentIdx = col("Monthly Rent", "Rent", "Rental Amount")
        val depositIdx = col("Deposit Amount", "Security Deposit", "Deposit")
        val escIdx = col("Escalation %", "Escalation", "Annual Escalation")
        val startIdx = col("Lease Start Date", "Lease Start", "Start Date")
        val expiryIdx = col("Lease Expiry Date", "Lease Expiry", "Expiry Date")
        val lockInEndIdx = col("Lock-in End Date", "Lock-in Expiry")
        val lockInIdx = col("Lock-in Period", "Lock-in", "Lock In")
        val statusIdx = col("Status", "Lease Status")
        val checklistIdx = col("Move-in Checklist", "Checklist")
        val issuesIdx = col("Open Issues", "Issues")
        val ownerNameIdx = col("Owner Name", "Landlord Name")
        val ownerPhoneIdx = col("Owner Phone", "Owner Contact", "Landlord Phone")
        val notesIdx = col("Notes", "Remarks")
        val assignedToIdx = col("Assigned To", "Assigned Agent", "Assigned")

        val mergedMap = OfflineDiskCache.loadTenants(appContext).associateBy { it.id }.toMutableMap()
        val seenIds = mutableSetOf<String>()
        var merged = 0
        dataRows.forEachIndexed { idx, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            val tName = row.getOrNull(nameIdx)?.trim().orEmpty()
            val pCode = row.getOrNull(codeIdx)?.trim().orEmpty()
            if (tName.isBlank() && pCode.isBlank()) return@forEachIndexed
            val id = row.getOrNull(idIdx)?.trim()?.takeIf { it.isNotBlank() }
                ?: if (pCode.isNotBlank()) "TEN-$pCode" else "TEN-${idx + 1}"
            seenIds.add(id)
            val rec = OfflineDiskCache.TenantCacheRecord(
                id = id,
                tenantName = tName.ifBlank { "Tenant" },
                propertyCode = pCode,
                projectName = row.getOrNull(projectIdx)?.trim().orEmpty(),
                location = row.getOrNull(locIdx)?.trim().orEmpty(),
                monthlyRent = row.getOrNull(rentIdx)?.trim().orEmpty(),
                leaseStartDate = CrmSheetSyncMapper.formatCrmDateIst(row.getOrNull(startIdx)?.trim().orEmpty()),
                leaseExpiryDate = CrmSheetSyncMapper.formatCrmDateIst(row.getOrNull(expiryIdx)?.trim().orEmpty()),
                lockInPeriod = row.getOrNull(lockInIdx)?.trim().orEmpty(),
                contactNumber = row.getOrNull(contactIdx)?.trim().orEmpty(),
                notes = row.getOrNull(notesIdx)?.trim().orEmpty(),
                unitNumber = row.getOrNull(unitIdx)?.trim().orEmpty(),
                division = row.getOrNull(divIdx)?.trim().orEmpty().ifBlank { "Commercial" },
                companyName = row.getOrNull(companyIdx)?.trim().orEmpty(),
                tenantEmail = row.getOrNull(emailIdx)?.trim().orEmpty(),
                depositAmount = row.getOrNull(depositIdx)?.trim().orEmpty(),
                escalationPercent = row.getOrNull(escIdx)?.trim().orEmpty().ifBlank { "5" },
                lockInEndDate = CrmSheetSyncMapper.formatCrmDateIst(row.getOrNull(lockInEndIdx)?.trim().orEmpty()),
                status = row.getOrNull(statusIdx)?.trim().orEmpty().ifBlank { "Active" },
                moveInChecklistJson = row.getOrNull(checklistIdx)?.trim().orEmpty(),
                openIssuesJson = row.getOrNull(issuesIdx)?.trim().orEmpty(),
                ownerName = row.getOrNull(ownerNameIdx)?.trim().orEmpty(),
                ownerContact = row.getOrNull(ownerPhoneIdx)?.trim().orEmpty(),
                updatedAt = CrmSheetSyncMapper.formatCrmDateIst(java.time.Instant.now().toString()),
                assignedTo = row.getOrNull(assignedToIdx)?.trim().orEmpty()
            )
            mergedMap[id] = rec
            merged++
        }
        if (reconcileStale) {
            mergedMap.keys.retainAll(seenIds)
        }
        if (merged > 0 || reconcileStale) {
            val all = mergedMap.values.toList()
            _tenants.value = all
            OfflineDiskCache.saveTenants(appContext, all)
            _syncRevision.value++
        }
        return merged
    }

    /**
     * Batch 18: Local-first immediate write + background Master Sheet push for 22-col `Tenants` tab (Cols A-V).
     */
    fun saveTenantLocalAndSync(tenant: OfflineDiskCache.TenantCacheRecord) {
        val appContext = context ?: return
        val stampedTenant = tenant.copy(updatedAt = CrmSheetSyncMapper.formatCrmDateIst(java.time.Instant.now().toString()))
        val updated = (_tenants.value.filterNot { it.id == stampedTenant.id } + stampedTenant)
            .sortedBy { it.tenantName.lowercase(Locale.ENGLISH) }
        _tenants.value = updated
        OfflineDiskCache.saveTenants(appContext, updated)
        _syncRevision.value++
        ioWriterScope.launch {
            pushTenantToMasterSheet(stampedTenant)
        }
    }

    suspend fun pushTenantToMasterSheet(tenant: OfflineDiskCache.TenantCacheRecord): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = _sheetsScriptUrl.value.trim().ifBlank { DEFAULT_MASTER_SHEET_URL }
        if (!endpoint.startsWith("https://") || !endpoint.contains("script.google.com", ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException("Configure a valid Google Apps Script Web App URL to sync Tenants."))
        }
        val record = JSONObject().apply {
            put("Tenant ID", tenant.id)
            put("Property Code", tenant.propertyCode)
            put("Project / Building", tenant.projectName)
            put("Unit No", tenant.unitNumber)
            put("Location", tenant.location)
            put("Division", tenant.division)
            put("Tenant Name", tenant.tenantName)
            put("Company / Brand", tenant.companyName)
            put("Tenant Phone", tenant.contactNumber)
            put("Tenant Email", tenant.tenantEmail)
            put("Monthly Rent", tenant.monthlyRent)
            put("Deposit Amount", tenant.depositAmount)
            put("Escalation %", tenant.escalationPercent)
            put("Lease Start Date", CrmSheetSyncMapper.formatCrmDateIst(tenant.leaseStartDate))
            put("Lease Expiry Date", CrmSheetSyncMapper.formatCrmDateIst(tenant.leaseExpiryDate))
            put("Lock-in End Date", CrmSheetSyncMapper.formatCrmDateIst(tenant.lockInEndDate.ifBlank { tenant.lockInPeriod }))
            put("Status", tenant.status)
            put("Move-in Checklist", tenant.moveInChecklistJson)
            put("Open Issues", tenant.openIssuesJson)
            put("Owner Name", tenant.ownerName)
            put("Owner Phone", tenant.ownerContact)
            put("Notes", tenant.notes)
            if (tenant.assignedTo.isNotBlank()) put("Assigned To", tenant.assignedTo)
        }
        val request = JSONObject().apply {
            put("action", "UPSERT_TENANT")
            put("tabName", TlcSchemaV2Api.TAB_TENANTS)
            put("tenantId", tenant.id)
            put("record", record)
        }
        val response = postAppsScriptJsonRequest(endpoint, request).getOrElse { err ->
            logSyncEvent("TENANT_SYNC", "FAILED", 0, "Tenant push failed for ${tenant.id}: ${err.message}", actor())
            return@withContext Result.failure(err)
        }
        val statusStr = response.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val okFlag = if (response.has("ok")) response.optBoolean("ok", false)
        else if (response.has("success")) response.optBoolean("success", false)
        else statusStr != "error" && statusStr != "failed"
        if (statusStr == "error" || statusStr == "failed" || !okFlag) {
            val errMsg = response.optString("message", response.optString("error", "Failed to push Tenant to Master Sheet."))
                .ifBlank { "Failed to push Tenant to Master Sheet." }
            logSyncEvent("TENANT_SYNC", "FAILED", 0, errMsg, actor())
            return@withContext Result.failure(IllegalStateException(errMsg))
        }
        logSyncEvent("TENANT_SYNC", "SUCCESS", 1, "Pushed Tenant ${tenant.id} (${tenant.tenantName})", actor())
        Result.success(Unit)
    }

    /**
     * Parses `Commercial Brokers`, `Residential Brokers`, `Vendors`, or legacy `Brokers & Vendors`
     * rows by exact Row 1 header names and persists all broker rows into Room (`brokers_master`)
     * while keeping `OfflineDiskCache` and `_brokersVendors` synchronized.
     */
    private fun mergeBrokersVendorsSheetRowsIntoCache(
        rows: List<List<String>>,
        defaultRoleHint: String = "Broker / CP",
        reconcileStale: Boolean = false
    ): Int {
        val appContext = context ?: return 0
        val headers = rows.firstOrNull().orEmpty()
        val dataRows = if (rows.size > 1) rows.drop(1) else emptyList()

        fun col(vararg aliases: String): Int {
            for (a in aliases) {
                val exact = headers.indexOfFirst { it.trim().equals(a, ignoreCase = true) }
                if (exact >= 0) return exact
            }
            return -1
        }

        val idIdx = col("Broker ID", "Vendor ID", "Record ID", "ID", "id")
        val nameIdx = col("Broker Name", "Vendor Name", "Name", "Contact Name", "CP Name")
        val phoneIdx = col("Primary Mobile", "Mobile", "Phone", "Phone Number", "Mobile No", "Contact", "Contact No", "WhatsApp")
        val phoneTypeIdx = col("Phone Type")
        val altMobilesIdx = col("Alternate Mobiles", "Alternate Mobile", "Secondary Mobile")
        val verifStatusIdx = col("Verification Status", "Status")
        val roleIdx = col("Role Category", "Role", "Category", "Service Category")
        val specializationIdx = col(
            "Specialization", "Specialty", "Specialisation", "Asset Focus",
            "Property Type", "Deal Type", "Sub-Role", "Sub Role", "Segment"
        )
        val divisionIdx = col("Division", "Vertical", "Portfolio")
        val confidenceIdx = col("Confidence", "Confidence Score", "Confidence Level", "Trust", "Rating", "Tier")
        val firmIdx = col("Firm / Agency", "Firm Name", "Agency Name", "Firm", "Agency", "Company")
        val areaIdx = col("Operating Areas", "Primary Location", "Location", "Micro-Market", "Areas", "Zone", "Area")
        val notesIdx = col("Notes", "Remarks", "Comments")
        val updatedIdx = col("Updated At", "Last Updated", "Updated")

        val isVendorTab = defaultRoleHint.contains("Vendor", ignoreCase = true)
        val isResidentialTab = defaultRoleHint.contains("Residential", ignoreCase = true)
        val isCommercialTab = defaultRoleHint.contains("Commercial", ignoreCase = true)
        val fallbackRole = when {
            isVendorTab -> "Vendor / Ops"
            isResidentialTab -> "Residential Broker"
            isCommercialTab -> "Commercial Broker"
            else -> "Broker / CP"
        }
        val fallbackDivision = if (isResidentialTab) "Residential" else "Commercial"
        val canonicalSourceTab = when {
            isCommercialTab -> TlcSchemaV2Api.TAB_COMMERCIAL_BROKERS
            isResidentialTab -> TlcSchemaV2Api.TAB_RESIDENTIAL_BROKERS
            isVendorTab -> TlcSchemaV2Api.TAB_VENDORS
            else -> defaultRoleHint.ifBlank { TlcSchemaV2Api.TAB_COMMERCIAL_BROKERS }
        }

        val mergedMap = OfflineDiskCache.loadBrokersVendors(appContext).associateBy { it.id }.toMutableMap()
        val roomBrokerEntities = mutableListOf<com.example.data.master.BrokerEntity>()
        val seenTabIds = mutableSetOf<String>()
        val nowMs = System.currentTimeMillis()
        var merged = 0

        dataRows.forEachIndexed { idx, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            val name = row.getOrNull(nameIdx)?.trim().orEmpty()
            val phone = row.getOrNull(phoneIdx)?.trim().orEmpty()
            if (name.isBlank() && phone.isBlank()) return@forEachIndexed
            val digits = phone.filter { it.isDigit() }
            val mobileLast10 = if (digits.length >= 10) digits.takeLast(10) else digits
            val tabPrefix = when {
                isCommercialTab -> "CB"
                isResidentialTab -> "RB"
                isVendorTab -> "VN"
                else -> "BV"
            }
            val id = row.getOrNull(idIdx)?.trim()?.takeIf { it.isNotBlank() }
                ?: "$tabPrefix-${mobileLast10.ifBlank { (idx + 1).toString() }}"
            seenTabIds.add(id)

            val rawRole = row.getOrNull(roleIdx)?.trim().orEmpty().ifBlank { fallbackRole }
            val specialization = row.getOrNull(specializationIdx)?.trim().orEmpty()
            val confidence = row.getOrNull(confidenceIdx)?.trim().orEmpty()
            val phoneType = row.getOrNull(phoneTypeIdx)?.trim().orEmpty()
            val alternateMobiles = row.getOrNull(altMobilesIdx)?.trim().orEmpty()
            val verificationStatus = row.getOrNull(verifStatusIdx)?.trim().orEmpty()
            val rawDivision = row.getOrNull(divisionIdx)?.trim().orEmpty()
            val resolvedDivision = when {
                rawDivision.contains("Resi", ignoreCase = true) || rawRole.contains("Resi", ignoreCase = true) -> "Residential"
                rawDivision.contains("Comm", ignoreCase = true) || rawRole.contains("Comm", ignoreCase = true) -> "Commercial"
                else -> fallbackDivision
            }
            val firm = row.getOrNull(firmIdx)?.trim().orEmpty()
            val area = row.getOrNull(areaIdx)?.trim().orEmpty()
            val baseNotes = row.getOrNull(notesIdx)?.trim().orEmpty()
            val enrichedNotes = buildList {
                if (confidence.isNotBlank() && !baseNotes.contains("Confidence:", ignoreCase = true)) {
                    add("Confidence: $confidence")
                }
                if (baseNotes.isNotBlank()) add(baseNotes)
            }.joinToString(" | ")

            val rec = BrokerVendorRecord(
                id = id,
                name = name.ifBlank { "Contact" },
                phone = phone,
                roleCategory = rawRole,
                subRole = specialization.ifBlank { "Channel Partner" },
                firmName = firm,
                operatingAreas = area,
                notes = enrichedNotes,
                updatedAt = CrmSheetSyncMapper.formatCrmDateIst(row.getOrNull(updatedIdx)?.trim().orEmpty()),
                phoneType = phoneType,
                alternateMobiles = alternateMobiles,
                verificationStatus = verificationStatus
            )
            mergedMap[id] = rec

            val isOpsOrVendor = isVendorTab ||
                rawRole.contains("Ops", ignoreCase = true) ||
                rawRole.contains("Vendor", ignoreCase = true)
            if (!isOpsOrVendor) {
                roomBrokerEntities.add(
                    com.example.data.master.BrokerEntity(
                        brokerId = id,
                        name = rec.name,
                        phone = phone,
                        mobileLast10 = mobileLast10,
                        division = resolvedDivision,
                        firmName = firm,
                        operatingAreas = area,
                        specialization = specialization,
                        confidence = confidence,
                        notes = baseNotes,
                        sourceTab = canonicalSourceTab,
                        updatedAt = nowMs,
                        phoneType = phoneType,
                        alternateMobiles = alternateMobiles,
                        verificationStatus = verificationStatus
                    )
                )
                if (phone.isNotBlank()) {
                    com.example.crm.CrmCallManager.saveBrokerContact(
                        context = appContext,
                        phone = rec.phone,
                        name = rec.name,
                        firm = rec.firmName,
                        notes = rec.notes
                    )
                }
            }
            merged++
        }

        if (reconcileStale) {
            val staleDiskIds = mergedMap.values.filter { rec ->
                val belongsToTab = when {
                    isVendorTab -> rec.roleCategory.contains("Vendor", true) || rec.roleCategory.contains("Ops", true) || rec.id.startsWith("VN-")
                    isResidentialTab -> rec.roleCategory.contains("Residential", true) || rec.id.startsWith("RB-")
                    isCommercialTab -> rec.roleCategory.contains("Commercial", true) || rec.id.startsWith("CB-")
                    else -> false
                }
                belongsToTab && rec.id !in seenTabIds
            }.map { it.id }
            staleDiskIds.forEach { mergedMap.remove(it) }
        }

        // Persist synced Commercial Brokers & Residential Brokers directly into Room DB
        if (!isVendorTab && (isCommercialTab || isResidentialTab || roomBrokerEntities.isNotEmpty())) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    val dao = com.example.data.master.MasterProjectDatabase.getDatabase(appContext).masterProjectDao()
                    if (roomBrokerEntities.isNotEmpty()) {
                        // Chunk inserts to avoid SQLite bind limits on 600+ rows
                        roomBrokerEntities.chunked(200).forEach { chunk ->
                            dao.upsertBrokers(chunk)
                        }
                        if (reconcileStale) {
                            val keepIds = roomBrokerEntities.map { it.brokerId }
                            if (keepIds.size <= 900) {
                                dao.deleteBrokersNotInIdsForTab(canonicalSourceTab, keepIds)
                            }
                        }
                    } else if (reconcileStale) {
                        dao.deleteBrokersBySourceTab(canonicalSourceTab)
                    }
                }
            }
        }

        if (merged > 0 || reconcileStale) {
            val all = mergedMap.values.sortedBy { it.name.lowercase(Locale.ENGLISH) }
            _brokersVendors.value = all
            OfflineDiskCache.saveBrokersVendors(appContext, all)
            _syncRevision.value++
        }
        return merged
    }

    fun saveBrokerVendorLocalAndSync(record: BrokerVendorRecord) {
        val appContext = context ?: return
        val stampedRecord = record.copy(updatedAt = CrmSheetSyncMapper.formatCrmDateIst(java.time.Instant.now().toString()))
        val updated = (_brokersVendors.value.filterNot {
            it.id == stampedRecord.id ||
                (stampedRecord.phone.filter { c -> c.isDigit() }.takeLast(10).length >= 7 &&
                    it.phone.filter { c -> c.isDigit() }.takeLast(10) == stampedRecord.phone.filter { c -> c.isDigit() }.takeLast(10))
        } + stampedRecord).sortedBy { it.name.lowercase(Locale.ENGLISH) }
        _brokersVendors.value = updated
        OfflineDiskCache.saveBrokersVendors(appContext, updated)
        if (!stampedRecord.roleCategory.contains("Ops", true) && !stampedRecord.roleCategory.contains("Vendor", true) && stampedRecord.phone.isNotBlank()) {
            com.example.crm.CrmCallManager.saveBrokerContact(
                context = appContext,
                phone = stampedRecord.phone,
                name = stampedRecord.name,
                firm = stampedRecord.firmName,
                notes = stampedRecord.notes
            )
        }
        _syncRevision.value++
        ioWriterScope.launch {
            pushBrokerVendorToMasterSheet(stampedRecord)
        }
    }

    suspend fun pushBrokerVendorToMasterSheet(record: BrokerVendorRecord): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = _sheetsScriptUrl.value.trim().ifBlank { DEFAULT_MASTER_SHEET_URL }
        if (!endpoint.startsWith("https://") || !endpoint.contains("script.google.com", ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException("Configure a valid Google Apps Script Web App URL to sync Brokers & Vendors."))
        }
        val targetTab = when {
            record.roleCategory.contains("Vendor", true) || record.roleCategory.contains("Ops", true) -> TlcSchemaV2Api.TAB_VENDORS
            record.roleCategory.contains("Residential", true) -> TlcSchemaV2Api.TAB_RESIDENTIAL_BROKERS
            else -> TlcSchemaV2Api.TAB_COMMERCIAL_BROKERS
        }
        val payload = JSONObject().apply {
            put("Record ID", record.id)
            put("Name", record.name)
            put("Phone", record.phone)
            put("Phone Type", record.phoneType)
            put("Alternate Mobiles", record.alternateMobiles)
            put("Verification Status", record.verificationStatus)
            put("Role Category", record.roleCategory)
            put("Specialization", record.subRole)
            put("Firm / Agency", record.firmName)
            put("Operating Areas", record.operatingAreas)
            put("Notes", record.notes)
            put("Updated At", CrmSheetSyncMapper.formatCrmDateIst(record.updatedAt.ifBlank { java.time.Instant.now().toString() }))
        }
        val request = JSONObject().apply {
            put("action", "UPSERT_BROKER_VENDOR")
            put("tabName", targetTab)
            put("recordId", record.id)
            put("record", payload)
        }
        val response = postAppsScriptJsonRequest(endpoint, request).getOrElse { err ->
            logSyncEvent("BROKER_VENDOR_SYNC", "FAILED", 0, "Broker/Vendor push failed for ${record.id}: ${err.message}", actor())
            return@withContext Result.failure(err)
        }
        val statusStr = response.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val okFlag = if (response.has("ok")) response.optBoolean("ok", false)
        else if (response.has("success")) response.optBoolean("success", false)
        else statusStr != "error" && statusStr != "failed"
        if (statusStr == "error" || statusStr == "failed" || !okFlag) {
            val errMsg = response.optString("message", response.optString("error", "Failed to push Broker/Vendor to Master Sheet."))
                .ifBlank { "Failed to push Broker/Vendor to Master Sheet." }
            logSyncEvent("BROKER_VENDOR_SYNC", "FAILED", 0, errMsg, actor())
            return@withContext Result.failure(IllegalStateException(errMsg))
        }
        logSyncEvent("BROKER_VENDOR_SYNC", "SUCCESS", 1, "Pushed ${record.roleCategory} ${record.name} to Master Sheet", actor())
        Result.success(Unit)
    }

    /**
     * Pushes a daily report record to the Apps Script Web App (`UPSERT_DAILY_REPORT`).
     */
    suspend fun pushDailyReport(report: DailyReportRecord): Result<Unit> = withContext(Dispatchers.IO) {
        context?.let { appCtx ->
            val current = _dailyReports.value.filterNot { it.reportId == report.reportId }
            val updated = listOf(report) + current
            _dailyReports.value = updated
            OfflineDiskCache.saveDailyReports(appCtx, updated)
        }
        val endpoint = _sheetsScriptUrl.value.trim().ifBlank { DEFAULT_MASTER_SHEET_URL }
        if (!endpoint.startsWith("https://") || !endpoint.contains("script.google.com", ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException("Configure a valid Google Apps Script Web App URL to sync Daily Reports."))
        }

        val record = JSONObject().apply {
            put("Report ID", report.reportId)
            put("Date", CrmSheetSyncMapper.formatCrmDateIst(report.date))
            put("Employee ID", report.employeeId)
            put("Employee Name", report.employeeName)
            put("Division", report.division)
            put("POA Submitted At", report.poaSubmittedAt)
            put("POA Tasks Count", report.poaTasksCount)
            put("POA Plan Summary", report.poaPlanSummary)
            put("EOD Submitted At", report.eodSubmittedAt)
            put("Calls Total", report.callsTotal)
            put("Calls Duration Mins", report.callsDurationMins)
            put("New Leads", report.newLeads)
            put("Follow-ups Done", report.followUpsDone)
            put("Properties Added", report.propertiesAdded)
            put("Properties Verified", report.propertiesVerified)
            put("Site Visits", report.siteVisits)
            put("Proposals Shared", report.proposalsShared)
            put("POA Score", report.poaScore)
            put("Key Wins / Hot Deals", report.keyWins)
            put("Blockers / Notes", report.blockers)
            put("Updated At", CrmSheetSyncMapper.formatCrmDateIst(report.updatedAt.ifBlank { java.time.Instant.now().toString() }))
        }

        val request = JSONObject().apply {
            put("action", "submit_daily_report")
            put("tabName", TlcSchemaV2Api.TAB_DAILY_REPORTS)
            put("record", record)
        }

        val response = postAppsScriptJsonRequest(endpoint, request).getOrElse { err ->
            logSyncEvent("DAILY_REPORT_SYNC", "FAILED", 0, "Daily Report push failed for ${report.reportId}: ${err.message}", actor())
            return@withContext Result.failure(err)
        }

        val statusStr = response.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val okFlag = if (response.has("ok")) response.optBoolean("ok", false)
            else if (response.has("success")) response.optBoolean("success", false)
            else statusStr != "error" && statusStr != "failed"
        if (statusStr == "error" || statusStr == "failed" || !okFlag) {
            val errMsg = response.optString("message", response.optString("error", "Failed to push Daily Report to Master Sheet."))
                .ifBlank { "Failed to push Daily Report to Master Sheet." }
            logSyncEvent("DAILY_REPORT_SYNC", "FAILED", 0, errMsg, actor())
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

        logSyncEvent(
            direction = "DAILY_REPORT_SYNC",
            status = "SUCCESS",
            recordCount = 1,
            details = "Pushed Daily Report ${report.reportId} for ${report.employeeName}",
            triggeredBy = actor()
        )
        Result.success(Unit)
    }

    /**
     * Parses `Call Logs` rows from `doGet` sync and merges into `CrmCallManager` local storage
     * by `Call ID` without duplicates so logged-in users download saved call history on any phone.
     */
    /**
     * Parses `Call Logs` rows from `TLC Master sheet` and merges into Room (`call_logs_master`)
     * and `CrmCallManager` storage with multi-device conflict & deletion safety:
     * - Preserves local rows with `syncStatus == "PENDING"`.
     * - For `SYNCED` rows, compares `updatedAt` (newer wins) and removes local synced rows deleted from the sheet.
     * - Preserves `Device Name` (`"S21 Ultra"` / `"S22 Ultra"`) and `Recording Filename` across devices.
     */
    internal fun mergeCallLogsSheetRowsIntoPrefs(
        rows: List<List<String>>,
        reconcileStale: Boolean = true
    ): Int = mergeCallLogsSheetRowsWithOutcome(rows, reconcileStale).let { it.addedCount + it.updatedCount }

    internal fun mergeCallLogsSheetRowsWithOutcome(
        rows: List<List<String>>,
        reconcileStale: Boolean = true
    ): TabMergeOutcome {
        val appContext = context ?: return TabMergeOutcome()
        val masterDao = com.example.data.master.MasterProjectDatabase.getDatabase(appContext).masterProjectDao()

        if (rows.size < 2) {
            if (!reconcileStale) return TabMergeOutcome()
            return runCatching {
                val existingCalls = com.example.crm.CrmCallManager.readCalls(appContext)
                val preservedPending = existingCalls.filter { it.syncStatus.equals("PENDING", ignoreCase = true) }
                val removedCount = existingCalls.size - preservedPending.size
                kotlinx.coroutines.runBlocking { masterDao.deleteAllSyncedCallLogs() }
                if (removedCount > 0) {
                    com.example.crm.CrmCallManager.saveCallsFromPull(appContext, preservedPending)
                    _syncRevision.value++
                }
                TabMergeOutcome(
                    removedCount = removedCount,
                    preservedPendingCount = preservedPending.size
                )
            }.getOrDefault(TabMergeOutcome())
        }

        val headers = rows.first()
        val dataRows = rows.drop(1)

        fun colIndex(exactHeader: String, vararg exactAliases: String): Int {
            val exact = headers.indexOfFirst { it.trim().equals(exactHeader, ignoreCase = true) }
            if (exact >= 0) return exact
            for (alias in exactAliases) {
                val found = headers.indexOfFirst { it.trim().equals(alias, ignoreCase = true) }
                if (found >= 0) return found
            }
            return -1
        }

        val callIdIdx = colIndex("Call ID", "CallID", "ID")
        val timestampIdx = colIndex("Date & Time", "Date & Time (dd/MM/yyyy HH:mm)", "Timestamp", "DateTime")
        val callerNameIdx = colIndex("Caller Name", "Client Name", "Contact Name", "Caller")
        val phoneIdx = colIndex("Phone", "Phone Number", "Mobile", "Number")
        val callTypeIdx = colIndex("Direction", "Call Type", "Type")
        val durationIdx = colIndex("Duration", "Duration Seconds", "Duration (s)")
        val roleLinkedIdx = colIndex("Role / Linked ID")
        val notesIdx = colIndex("Notes / AI Summary", "Call Notes", "AI Summary Bullets", "Notes", "Summary", "Remarks")
        val agentIdx = colIndex("Employee", "Assigned Agent", "Assigned To", "Agent")
        val deviceNameIdx = colIndex("Device Name", "Device")
        val recordingFileIdx = colIndex("Recording Filename", "Recording File", "Recording")
        val actionIdx = colIndex("Action Required", "Follow-Up Action", "Action")
        val followUpIdx = colIndex("Next Follow-up", "Follow-Up Date", "Next Follow-up Date", "nextFollowUpDateTime")
        val statusIdx = colIndex("Lead Status", "Outcome", "Status")
        val stagingIdx = colIndex("Staging Status", "Staging")
        val isLinkedIdx = colIndex("Is Linked", "isLinked", "Linked")
        val linkedTypeIdx = colIndex("Linked Role", "AI Caller Role", "linkedOpportunityType", "Role")
        val linkedTitleIdx = colIndex("Linked Title", "linkedOpportunityTitle")
        val linkedIdIdx = colIndex("Linked ID", "Lead ID", "linkedOpportunityId")
        val waLoggedIdx = colIndex("WA Logged", "waLogged", "WhatsApp Logged")
        val subRoleIdx = colIndex("Sub-Role", "pocSubRole", "Sub Role")
        val handoverStatusIdx = colIndex("Handover Status", "handoverStatus")
        val handoverAssignedToIdx = colIndex("Handover Assigned To", "handoverAssignedTo")
        val handedOverAtIdx = colIndex("Handed Over At", "handedOverAt")
        val handoverCallbackAtIdx = colIndex("Callback At", "handoverCallbackAt")
        val updatedAtIdx = colIndex("Updated At", "updatedAt", "Last Updated")

        fun cell(row: List<String>, idx: Int): String =
            if (idx in row.indices) row[idx].trim() else ""

        return runCatching {
            val existingLocal = com.example.crm.CrmCallManager.readCalls(appContext)
            val existingRoom = runCatching {
                kotlinx.coroutines.runBlocking { masterDao.getAllCallLogsSync() }
                    .map { com.example.crm.CrmCallRecord.fromRoomEntity(it) }
            }.getOrDefault(emptyList())

            val byId = linkedMapOf<String, com.example.crm.CrmCallRecord>()
            existingRoom.forEach { byId[it.id] = it }
            existingLocal.forEach { byId[it.id] = it }

            val seenSheetIds = mutableSetOf<String>()
            var addedCount = 0
            var updatedCount = 0
            var preservedPendingCount = 0

            dataRows.forEach { row ->
                if (row.all { it.isBlank() }) return@forEach
                val phone = cell(row, phoneIdx)
                val caller = cell(row, callerNameIdx).ifBlank { "Unknown Caller" }
                if (phone.isBlank() && caller == "Unknown Caller") return@forEach

                val rawTs = cell(row, timestampIdx)
                val parsedTs = parseComparableTimestampMillis(rawTs).takeIf { it > 0L } ?: System.currentTimeMillis()
                val rawCallId = cell(row, callIdIdx).ifBlank {
                    "CALL-${phone.filter { it.isDigit() }.takeLast(10)}-$parsedTs"
                }
                seenSheetIds.add(rawCallId)

                val durationSec = cell(row, durationIdx).filter { it.isDigit() }.toIntOrNull() ?: 0
                val callType = com.example.crm.CallType.fromString(cell(row, callTypeIdx).ifBlank { "INCOMING" })
                val notes = cell(row, notesIdx)
                val assignedAgent = cell(row, agentIdx).ifBlank { "" }
                val actionReq = cell(row, actionIdx).ifBlank { "Schedule Site Visit" }
                val nextFollowUp = cell(row, followUpIdx).ifBlank { "Today, 4:00 PM" }
                val leadStatus = cell(row, statusIdx).ifBlank { "Active" }
                val stagingStatus = cell(row, stagingIdx).ifBlank { "Live" }

                val roleLinkedCombined = cell(row, roleLinkedIdx)
                val splitParts = roleLinkedCombined.split("•", "/", "|").map { it.trim() }.filter { it.isNotBlank() }
                val splitRole = splitParts.firstOrNull()
                val splitId = splitParts.getOrNull(1)

                val linkedRole = cell(row, linkedTypeIdx).ifBlank { splitRole.orEmpty() }.ifBlank { null }
                val linkedTitle = cell(row, linkedTitleIdx).ifBlank { null }
                val linkedId = cell(row, linkedIdIdx).ifBlank { splitId.orEmpty() }.ifBlank { null }
                val rawLinked = cell(row, isLinkedIdx).lowercase(Locale.ENGLISH)
                val isLinked = rawLinked == "true" || rawLinked == "yes" || rawLinked == "1" || !linkedId.isNullOrBlank()
                val rawWaLogged = cell(row, waLoggedIdx).lowercase(Locale.ENGLISH)
                val waLogged = rawWaLogged == "true" || rawWaLogged == "yes" || rawWaLogged == "1" ||
                    notes.contains("Connected on WhatsApp", ignoreCase = true)
                val pocSubRole = cell(row, subRoleIdx)
                val handoverStatus = cell(row, handoverStatusIdx)
                val handoverAssignedTo = cell(row, handoverAssignedToIdx)
                val handedOverAt = cell(row, handedOverAtIdx).toLongOrNull() ?: 0L
                val handoverCallbackAt = cell(row, handoverCallbackAtIdx).toLongOrNull() ?: 0L
                val deviceName = cell(row, deviceNameIdx).takeIf { it.isNotBlank() }
                val recordingFilename = cell(row, recordingFileIdx).takeIf { it.isNotBlank() }
                val sheetUpdatedAt = parseComparableTimestampMillis(cell(row, updatedAtIdx)).takeIf { it > 0L } ?: parsedTs

                val rawRecord = com.example.crm.CrmCallRecord(
                    id = rawCallId,
                    callerName = caller,
                    phoneNumber = phone,
                    callType = callType,
                    timestamp = parsedTs,
                    durationSeconds = durationSec,
                    notes = notes,
                    assignedAgent = assignedAgent,
                    actionRequired = actionReq,
                    nextFollowUpDateTime = nextFollowUp,
                    leadStatus = leadStatus,
                    stagingStatus = stagingStatus,
                    isLinked = isLinked,
                    linkedOpportunityType = linkedRole,
                    linkedOpportunityTitle = linkedTitle,
                    linkedOpportunityId = linkedId,
                    waLogged = waLogged,
                    pocSubRole = pocSubRole,
                    handoverStatus = handoverStatus,
                    handoverAssignedTo = handoverAssignedTo,
                    handedOverAt = handedOverAt,
                    handoverCallbackAt = handoverCallbackAt,
                    deviceName = deviceName,
                    syncStatus = "SYNCED",
                    recordingFilename = recordingFilename,
                    updatedAt = sheetUpdatedAt
                )
                val record = runCatching {
                    com.example.crm.CrmCallManager.tagInternalTeamCallIfNeeded(appContext, rawRecord)
                        .copy(syncStatus = "SYNCED")
                }.getOrDefault(rawRecord)

                val prev = byId[rawCallId]
                if (prev == null) {
                    byId[rawCallId] = record
                    addedCount++
                } else if (prev.syncStatus.equals("PENDING", ignoreCase = true)) {
                    // Conflict safety: NEVER overwrite a local pending call record
                    preservedPendingCount++
                } else {
                    // Synced record: newer updatedAt wins; also upgrade blank notes/deviceName/recordingFilename
                    val sheetIsNewerOrEqual = sheetUpdatedAt >= prev.updatedAt || (prev.notes.isBlank() && record.notes.isNotBlank())
                    if (sheetIsNewerOrEqual) {
                        byId[rawCallId] = record.copy(
                            notes = record.notes.ifBlank { prev.notes },
                            deviceName = record.deviceName ?: prev.deviceName,
                            recordingFilename = record.recordingFilename ?: prev.recordingFilename
                        )
                        updatedCount++
                    }
                }
            }

            var removedCount = 0
            if (reconcileStale) {
                val staleIds = byId.values.filter { rec ->
                    val isPending = rec.syncStatus.equals("PENDING", ignoreCase = true)
                    if (isPending) preservedPendingCount++
                    !isPending && rec.id !in seenSheetIds
                }.map { it.id }

                if (staleIds.isNotEmpty()) {
                    staleIds.forEach { byId.remove(it) }
                    runCatching {
                        kotlinx.coroutines.runBlocking { masterDao.deleteCallLogsByIds(staleIds) }
                    }
                    removedCount = staleIds.size
                }
            }

            val finalSorted = byId.values.sortedByDescending { it.timestamp }
            runCatching {
                kotlinx.coroutines.runBlocking {
                    masterDao.upsertCallLogs(finalSorted.map { it.toRoomEntity() })
                }
            }
            if (addedCount > 0 || updatedCount > 0 || removedCount > 0) {
                com.example.crm.CrmCallManager.saveCallsFromPull(appContext, finalSorted)
                _syncRevision.value++
            }
            runCatching {
                com.example.crm.TeamHandoverStore.reconcileWithCallLogs(appContext, finalSorted)
            }
            TabMergeOutcome(
                addedCount = addedCount,
                updatedCount = updatedCount,
                removedCount = removedCount,
                preservedPendingCount = preservedPendingCount,
                totalProcessed = seenSheetIds.size
            )
        }.onFailure { err ->
            android.util.Log.e("SheetsRepository", "mergeCallLogsSheetRowsWithOutcome failed", err)
        }.getOrDefault(TabMergeOutcome())
    }

    /**
     * Pushes a `CrmCallRecord` to the `Call Logs` tab on `TLC Master sheet`
     * via `POST ?tabName=Call%20Logs` (`action = "UPSERT"`), including exact multi-device fields
     * (`Date & Time (dd/MM/yyyy HH:mm)`, `Device Name`, `Recording Filename`, `Notes / AI Summary`).
     */
    suspend fun pushCallLogToMasterSheet(
        call: com.example.crm.CrmCallRecord,
        analysisOverride: com.example.data.master.CallAnalysisEntity? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = TlcSchemaV2Api.resolveEndpointUrl(_sheetsScriptUrl.value)
        val appCtx = context
        val analysis = analysisOverride ?: runCatching {
            appCtx?.let { com.example.data.master.MasterProjectDatabase.getDatabase(it).masterProjectDao().getCallAnalysisByCallId(call.id) }
        }.getOrNull()
        val resolvedDeviceName = analysis?.deviceName?.takeIf { it.isNotBlank() }
            ?: call.deviceName?.takeIf { it.isNotBlank() }
            ?: appCtx?.let { DeviceIdentityManager.getFriendlyDeviceName(it) }
            ?: DeviceIdentityManager.resolveFriendlyDeviceName(null)
        val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(call.timestamp))
        val updatedNow = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ENGLISH).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date())
        val roleLinkedSummary = listOfNotNull(
            call.linkedOpportunityType?.takeIf { it.isNotBlank() } ?: analysis?.callerRole?.takeIf { it.isNotBlank() },
            call.linkedOpportunityId?.takeIf { it.isNotBlank() }
        ).joinToString(" / ")
        val resolvedEmployee = call.assignedAgent.takeIf { it.isNotBlank() } ?: actor()
        val aiSummaryLines = analysis?.let { ent ->
            runCatching {
                val arr = org.json.JSONArray(ent.summaryBulletsJson)
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim().takeIf { it.isNotBlank() } }
                    .joinToString(" • ")
            }.getOrNull()
        }.orEmpty()
        val combinedNotes = when {
            call.notes.isNotBlank() -> call.notes
            aiSummaryLines.isNotBlank() -> aiSummaryLines
            else -> ""
        }
        val followUpCombined = listOfNotNull(
            analysis?.followUpDate?.takeIf { it.isNotBlank() },
            analysis?.followUpTime?.takeIf { it.isNotBlank() },
            analysis?.followUpNextAction?.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank { call.nextFollowUpDateTime }

        val record = JSONObject().apply {
            // Exact multi-device Call Logs sheet headers
            put("Call ID", call.id)
            put("Date & Time", formattedDate)
            put("Caller Name", call.callerName)
            put("Phone", call.phoneNumber)
            put("Direction", call.callType.name)
            put("Duration", call.durationSeconds)
            put("Role / Linked ID", roleLinkedSummary)
            put("Notes / AI Summary", combinedNotes)
            put("Employee", resolvedEmployee)
            put("Device Name", resolvedDeviceName)
            put("Recording Filename", call.recordingFilename.orEmpty().ifBlank { analysis?.recordingFilename.orEmpty() })
            put("Updated At", updatedNow)

            // Batch 44 AI Call Analysis columns for dual-phone visibility without .m4a transfer
            put("AI Caller Role", analysis?.callerRole.orEmpty())
            put("Detected Language", analysis?.detectedLanguage.orEmpty())
            put("AI Summary Bullets", aiSummaryLines)
            put("Follow-Up Date", analysis?.followUpDate.orEmpty())
            put("Follow-Up Time", analysis?.followUpTime.orEmpty())
            put("Follow-Up Action", analysis?.followUpNextAction.orEmpty())
            put("AI Confidence", analysis?.confidence ?: 0)
            put("Review Status", analysis?.reviewStatus.orEmpty())

            // Backward-compatible column aliases
            put("Timestamp", call.timestamp)
            put("Phone Number", call.phoneNumber)
            put("Call Type", call.callType.name)
            put("Duration Seconds", call.durationSeconds)
            put("Call Notes", combinedNotes)
            put("Assigned Agent", resolvedEmployee)
            put("Action Required", call.actionRequired.ifBlank { analysis?.followUpNextAction.orEmpty() })
            put("Next Follow-up", followUpCombined)
            put("Lead Status", call.leadStatus)
            put("Staging Status", call.stagingStatus)
            put("Is Linked", call.isLinked)
            put("Linked Role", call.linkedOpportunityType.orEmpty().ifBlank { analysis?.callerRole.orEmpty() })
            put("Linked Title", call.linkedOpportunityTitle.orEmpty())
            put("Linked ID", call.linkedOpportunityId.orEmpty())
            put("Lead ID", call.linkedOpportunityId.orEmpty())
            put("WA Logged", call.waLogged)
            put("Sub-Role", call.pocSubRole)
            put("Handover Status", call.handoverStatus)
            put("Handover Assigned To", call.handoverAssignedTo)
            put("Handed Over At", call.handedOverAt)
            put("Callback At", call.handoverCallbackAt)
        }

        val response = TlcSchemaV2Api.postTabUpsert(
            baseUrl = endpoint,
            tabName = TlcSchemaV2Api.TAB_CALL_LOGS,
            action = "UPSERT",
            keyHeader = "Call ID",
            keyValue = call.id,
            record = record
        ).getOrElse { err ->
            logSyncEvent("CALL_LOG_SYNC", "FAILED", 0, "Call Log push failed for ${call.id}: ${err.message}", actor())
            return@withContext Result.failure(err)
        }

        val statusStr = response.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val okFlag = if (response.has("ok")) response.optBoolean("ok", false)
            else if (response.has("success")) response.optBoolean("success", false)
            else statusStr != "error" && statusStr != "failed"
        if (statusStr == "error" || statusStr == "failed" || !okFlag) {
            val errMsg = response.optString("message", response.optString("error", "Failed to push Call Log to Master Sheet."))
                .ifBlank { "Failed to push Call Log to Master Sheet." }
            logSyncEvent("CALL_LOG_SYNC", "FAILED", 0, errMsg, actor())
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

        if (appCtx != null) {
            com.example.crm.CrmCallManager.markCallSyncedLocal(appCtx, call.id)
            refreshPendingSyncState(reconcileDisk = false)
        }

        logSyncEvent(
            direction = "CALL_LOG_SYNC",
            status = "SUCCESS",
            recordCount = 1,
            details = "Pushed Call Log ${call.id} ($resolvedDeviceName • ${call.callerName})",
            triggeredBy = actor()
        )
        Result.success(Unit)
    }

    /**
     * Multi-Device Immediate Push:
     * Pushes all unsynced local Properties (`!isSyncedToSheet`), pending CRM Leads (`pendingSync == true`),
     * and pending Call Logs (`syncStatus == "PENDING"`) to `TLC Master sheet` via Apps Script `/exec`.
     */
    suspend fun pushPendingMultiDeviceChanges(): Int = withContext(Dispatchers.IO) {
        val appCtx = context ?: return@withContext 0
        _isSyncingNow.value = true
        refreshTopSyncChipState(syncing = true)
        var pushedCount = 0
        try {
            // 1. Push unsynced Properties
            val unsyncedProps = _properties.value.filter { !it.isSyncedToSheet }
            for (prop in unsyncedProps) {
                if (pushPropertyToMasterSheet(prop).isSuccess) {
                    pushedCount++
                }
            }

            // 2. Push pending CRM Leads
            val prefs = appCtx.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE)
            val arr = runCatching { JSONArray(prefs.getString("leads", "[]") ?: "[]") }.getOrDefault(JSONArray())
            val pendingLeadIds = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optBoolean("pendingSync", false)) {
                    val lid = obj.optString("id", "").trim()
                    if (lid.isNotBlank()) pendingLeadIds.add(lid)
                }
            }
            if (pendingLeadIds.isNotEmpty()) {
                val allLeads = runCatching { com.example.ui.screens.readCrmLeads(appCtx) }.getOrDefault(emptyList())
                val syncedIds = mutableSetOf<String>()
                for (lead in allLeads.filter { it.id in pendingLeadIds }) {
                    if (pushCrmLeadToMasterSheet(lead).isSuccess) {
                        syncedIds.add(lead.id)
                        pushedCount++
                    }
                }
                if (syncedIds.isNotEmpty()) {
                    var changed = false
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        if (obj.optString("id", "").trim() in syncedIds) {
                            obj.put("pendingSync", false)
                            changed = true
                        }
                    }
                    if (changed) {
                        val jsonStr = arr.toString()
                        OfflineDiskCache.saveCrmLeadsJson(appCtx, jsonStr)
                        prefs.edit().putString("leads", jsonStr).commit()
                    }
                }
            }

            // 3. Push pending Call Logs (from Room + local JSON store)
            val roomPending = runCatching {
                com.example.data.master.MasterProjectDatabase.getDatabase(appCtx)
                    .masterProjectDao()
                    .getPendingCallLogsSync()
                    .map { com.example.crm.CrmCallRecord.fromRoomEntity(it) }
            }.getOrDefault(emptyList())
            val localPending = com.example.crm.CrmCallManager.readCalls(appCtx)
                .filter { it.syncStatus.equals("PENDING", ignoreCase = true) }
            val pendingCallsById = linkedMapOf<String, com.example.crm.CrmCallRecord>()
            roomPending.forEach { pendingCallsById[it.id] = it }
            localPending.forEach { pendingCallsById[it.id] = it }

            for (call in pendingCallsById.values) {
                if (pushCallLogToMasterSheet(call).isSuccess) {
                    pushedCount++
                }
            }
        } finally {
            _isSyncingNow.value = false
            refreshPendingSyncState(reconcileDisk = false)
        }
        pushedCount
    }

    /**
     * Batch 21: Pushes a newly created or updated DirectoryProject to the Master Google Sheet
     * ("Commercial Projects" or "Residential Projects" tab) via Apps Script UPSERT_PROJECT.
     */
    suspend fun pushDirectoryProjectToMasterSheet(project: com.example.data.projects.DirectoryProject): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = _sheetsScriptUrl.value.trim().ifBlank { DEFAULT_MASTER_SHEET_URL }
        if (!endpoint.startsWith("https://") || !endpoint.contains("script.google.com", ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException("Configure a valid Google Apps Script Web App URL to sync Projects."))
        }

        val isResidential = project.division.contains("res", ignoreCase = true)
        val tabName = if (isResidential) "Residential Projects" else "Commercial Projects"
        val salesPoc = project.raw.optString("salesPoc").ifBlank { project.raw.optString("Sales POC") }
        val salesPocPhone = project.raw.optString("salesPocPhone").ifBlank { project.raw.optString("Sales POC Phone") }
        val updatedNow = java.time.Instant.now().toString()

        val record = JSONObject().apply {
            put("Project ID", project.id)
            put("Project Code", project.id)
            put("Project Name", project.name)
            put("Division", if (isResidential) "Residential" else "Commercial")
            put("Developer", project.developer)
            put("Location", project.location)
            put("Micro Market", project.location)
            put("Sales POC", salesPoc)
            put("Sales POC Phone", salesPocPhone)
            put("Verification", project.verification.ifBlank { "Unverified" })
            put("Record Status", project.archiveStatus.ifBlank { "Active" })
            put("Updated At", updatedNow)

            put("id", project.id)
            put("name", project.name)
            put("division", if (isResidential) "residential" else "commercial")
            put("developer", project.developer)
            put("location", project.location)
            put("salesPoc", salesPoc)
            put("salesPocPhone", salesPocPhone)
            put("verification", project.verification.ifBlank { "Unverified" })
            put("archiveStatus", project.archiveStatus.ifBlank { "Active" })
        }

        val request = JSONObject().apply {
            put("action", "UPSERT_PROJECT")
            put("tabName", tabName)
            put("projectId", project.id)
            put("record", record)
        }

        val response = postAppsScriptJsonRequest(endpoint, request).getOrElse { err ->
            logSyncEvent("PROJECT_SYNC", "FAILED", 0, "Project sync failed for ${project.id}: ${err.message}", actor())
            return@withContext Result.failure(err)
        }

        val statusStr = response.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val okFlag = if (response.has("ok")) response.optBoolean("ok", false)
            else if (response.has("success")) response.optBoolean("success", false)
            else statusStr != "error" && statusStr != "failed"
        if (statusStr == "error" || statusStr == "failed" || !okFlag) {
            val errMsg = response.optString("message", response.optString("error", "Failed to push Project to Master Sheet."))
                .ifBlank { "Failed to push Project to Master Sheet." }
            logSyncEvent("PROJECT_SYNC", "FAILED", 0, errMsg, actor())
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

        logSyncEvent(
            direction = "PROJECT_SYNC",
            status = "SUCCESS",
            recordCount = 1,
            details = "Pushed ${project.id} (${project.name}) to $tabName",
            triggeredBy = actor()
        )
        Result.success(Unit)
    }

    /**
     * Batch 12: One-Tap Cloud Migration & Recovery for all local edits, photos, leads, and call logs:
     * 1. Uploads any local `file://` or `content://` image URIs on `PropertyItem.images` to Google Drive (`uploadMediaToDrive`),
     *    replacing local URIs with the returned Drive `directUrl`.
     * 2. Pushes all unsynced `PropertyItem`s (`UPSERT_PROPERTY`), `CrmLead`s (`UPSERT_CRM_LEAD`), and `CrmCallRecord`s (`UPSERT_CALL_LOG`).
     * 3. Returns a summary string of uploaded photos, properties, leads, and call logs.
     */
    suspend fun pushAllUnsyncedLocalDataToCloud(): String = withContext(Dispatchers.IO) {
        val appContext = context ?: return@withContext "Application context unavailable for cloud migration."
        var uploadedPhotosCount = 0
        var pushedPropertiesCount = 0
        var pushedLeadsCount = 0
        var pushedCallLogsCount = 0
        val photoUpdatedPropIds = mutableSetOf<String>()

        // 1. Scan all properties for local file:// or content:// image URIs and upload to Google Drive
        val snapshotProps = _properties.value.toList()
        for (prop in snapshotProps) {
            if (prop.images.isEmpty()) continue
            var changed = false
            val newImages = prop.images.mapIndexed { idx, imgUri ->
                val trimmed = imgUri.trim()
                val isLocalUri = trimmed.startsWith("file://", ignoreCase = true) ||
                    trimmed.startsWith("content://", ignoreCase = true) ||
                    (trimmed.isNotBlank() && !trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true))
                if (!isLocalUri) {
                    trimmed
                } else {
                    val bytes = runCatching {
                        when {
                            trimmed.startsWith("content://", ignoreCase = true) || trimmed.startsWith("file://", ignoreCase = true) -> {
                                val parsedUri = android.net.Uri.parse(trimmed)
                                appContext.contentResolver.openInputStream(parsedUri)?.use { it.readBytes() }
                                    ?: java.io.File(parsedUri.path.orEmpty()).takeIf { it.exists() }?.readBytes()
                            }
                            else -> {
                                java.io.File(trimmed).takeIf { it.exists() }?.readBytes()
                            }
                        }
                    }.getOrNull()

                    if (bytes != null && bytes.isNotEmpty()) {
                        val propCodeOrId = prop.propertyCode.ifBlank { prop.id.ifBlank { "PROP" } }
                        val uploadRes = uploadMediaToDrive(
                            propertyId = propCodeOrId,
                            fileName = "${propCodeOrId}_photo_${idx + 1}.jpg",
                            mimeType = "image/jpeg",
                            bytes = bytes
                        )
                        val driveUrl = uploadRes.getOrNull()
                        if (!driveUrl.isNullOrBlank()) {
                            uploadedPhotosCount++
                            changed = true
                            driveUrl
                        } else {
                            trimmed
                        }
                    } else {
                        trimmed
                    }
                }
            }
            if (changed) {
                val updatedProp = prop.copy(images = newImages, isSyncedToSheet = false)
                photoUpdatedPropIds.add(prop.id)
                _properties.update { currentList ->
                    currentList.map { if (it.id == prop.id) updatedProp else it }
                }
                runCatching { PropertiesLocalDatabase.saveProperties(appContext, _properties.value) }
            }
        }

        // 2. Push all unsynced PropertyItems (or locally modified properties) via UPSERT_PROPERTY
        val currentProps = _properties.value.toList()
        val propsToPush = currentProps.filter { !it.isSyncedToSheet || it.id in photoUpdatedPropIds }
        for (prop in propsToPush) {
            if (pushPropertyToMasterSheet(prop).isSuccess) {
                pushedPropertiesCount++
            }
        }

        // 3. Push all local CrmLeads via UPSERT_CRM_LEAD
        val localLeads = runCatching {
            com.example.ui.screens.readCrmLeads(appContext).filter { !it.id.startsWith("demo-lead-") }
        }.getOrDefault(emptyList())
        for (lead in localLeads) {
            if (pushCrmLeadToMasterSheet(lead).isSuccess) {
                pushedLeadsCount++
            }
        }

        // 4. Push all local CrmCallRecords via UPSERT_CALL_LOG
        val localCalls = runCatching {
            com.example.crm.CrmCallManager.readCalls(appContext)
        }.getOrDefault(emptyList())
        for (call in localCalls) {
            if (pushCallLogToMasterSheet(call).isSuccess) {
                pushedCallLogsCount++
            }
        }

        val summary = buildString {
            append("Cloud Migration & Sync Complete: ")
            append("$uploadedPhotosCount Drive Photo(s) • ")
            append("$pushedPropertiesCount Property(s) • ")
            append("$pushedLeadsCount CRM Lead(s) • ")
            append("$pushedCallLogsCount Call Log(s) pushed to Master Sheet.")
        }

        logSyncEvent(
            direction = "CLOUD_MIGRATION",
            status = "SUCCESS",
            recordCount = uploadedPhotosCount + pushedPropertiesCount + pushedLeadsCount + pushedCallLogsCount,
            details = summary,
            triggeredBy = actor()
        )
        summary
    }
}


