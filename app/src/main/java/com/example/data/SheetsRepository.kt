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
    private val queueMutex = sharedQueueMutex

    internal fun close() {
        repoScope.cancel()
        ioWriterScope.cancel()
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
            _pendingSyncCount.value = queue.size
            val review = queue.count { it.state == PendingWriteState.REVIEW }
            _pendingSyncStatus.value = if (queue.isEmpty()) "No pending sync changes"
                else "${queue.size} pending changes; $review require review"
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
        const val DEFAULT_MASTER_SHEET_URL = "https://script.google.com/macros/s/AKfycbxLZh3AEON1wweJc3jBWjjWOTPltAmGIgVqRtFCqX9kmzz7JEeMoilTMG8u9X3RiU8_/exec"
        const val AUTO_SYNC_INTERVAL_MS = 15L * 60L * 1000L
        const val FOREGROUND_SYNC_INTERVAL_MS = 3L * 60L * 1000L
        private const val LAST_SYNC_AT_MILLIS_KEY = "last_successful_sync_millis"
        val DEFAULT_SYNCED_TABS = listOf(
            "Commercial Inventory",
            "Residential Inventory",
            "Commercial Projects",
            "Residential Projects",
            "CRM",
            "Employees",
            "Daily Reports",
            "Call Logs",
            "Tenants",
            "Brokers & Vendors"
        )
        const val MAX_COMPARISON_COUNT = 3
        private const val COMPARISON_PREFS_NAME = "property_comparison_selection_v1"
        private const val MUTATION_DEDUP_PREFS_NAME = "processed_mutation_ids_v1"
        private const val SYNCED_EMPLOYEES_PREFS = "tlc_synced_employees_v1"
        private val LEGACY_FAKE_EMPLOYEE_NAMES = setOf("amit", "neha", "rohan", "vikram", "admin", "staff")
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
            val baseDefaults = TlcStandardOptions.teamEmployees.filterNot {
                it.equals("Unassigned", ignoreCase = true)
            }
            if (context == null) return baseDefaults
            val diskCached = OfflineDiskCache.loadEmployeeNames(context)
            val synced = if (diskCached.isNotEmpty()) {
                diskCached
            } else {
                runCatching {
                    val prefs = context.getSharedPreferences(SYNCED_EMPLOYEES_PREFS, Context.MODE_PRIVATE)
                    val raw = prefs.getString("active_names", "[]") ?: "[]"
                    val arr = JSONArray(raw)
                    (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { n -> n.isNotBlank() } }
                }.getOrDefault(emptyList())
            }
            val merged = (synced + baseDefaults)
                .map { it.trim() }
                .filter { name ->
                    name.isNotBlank() &&
                        !name.equals("Unassigned", ignoreCase = true) &&
                        name.lowercase(Locale.ENGLISH) !in LEGACY_FAKE_EMPLOYEE_NAMES
                }
                .distinctBy { it.lowercase(Locale.ENGLISH) }
            return merged
        }

        fun getSyncedActiveEmployeeDirectory(context: Context?): List<OfflineDiskCache.CachedEmployeeMember> {
            if (context == null) return emptyList()
            val diskMembers = OfflineDiskCache.loadEmployeeDirectory(context)
                .filter { it.isActiveMember() && it.name.lowercase(Locale.ENGLISH) !in LEGACY_FAKE_EMPLOYEE_NAMES }
            if (diskMembers.isNotEmpty()) return diskMembers
            // Fallback to active names in prefs/defaults so list is never empty if sheet has only names
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
        val savedUrl = if (rawSavedUrl.isNotBlank()) rawSavedUrl else DEFAULT_MASTER_SHEET_URL
        _sheetsScriptUrl.value = savedUrl
        _isLiveConnected.value = false
        _connectionStatus.value = if (savedUrl.isNotBlank()) "Sheet URL saved - connection not yet verified" else "Not Connected - Ready for Google Sheet Setup"
        sanitizeShiftedInventoryRecords(startupContext)
        if (_properties.value.isNotEmpty()) {
            runCatching { OfflineDiskCache.saveInventorySplits(startupContext, _properties.value) }
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
        val trimmed = url.trim()
        _sheetsScriptUrl.value = trimmed
        _isLiveConnected.value = false
        _connectionStatus.value = if (trimmed.isBlank()) "Not Connected - Ready for Google Sheet Setup" else "Sheet URL saved - verifying connection"
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
            if (rawUrl.contains("script.google.com")) {
                val responseText = fetchUrlText(rawUrl)
                val latency = System.currentTimeMillis() - startTime
                val trimmed = responseText.trim()
                if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
                    val result = ConnectionDiagnostic(
                        isSuccess = false,
                        readStatus = "Error: Endpoint returned HTML",
                        writeStatus = "Access restricted",
                        latencyMs = latency,
                        recordsCount = 0,
                        message = "The endpoint requires authentication. Keep owner and CRM data private; configure an authenticated integration rather than enabling anonymous access.",
                        checkedAt = timestamp
                    )
                    _lastDiagnostic.value = result
                    return@withContext result
                }

                if (!AppsScriptSyncProtocol.hasInventoryResponse(responseText)) {
                    throw java.io.IOException("Endpoint did not return inventory data; connection unverified")
                }
                val parsedRows = parseAppsScriptJsonData(responseText)
                readSuccess = parsedRows.any { it.isValid }
                recordsParsed = parsedRows.size

                try {
                    val pingPayload = JSONObject().apply {
                        put("action", "DIAGNOSTIC_PING")
                        put("timestamp", timestamp)
                        put("appBrand", "Real Estate Inventory")
                    }
                    val (postSuccess, postResp) = sendAppsScriptPost(rawUrl, pingPayload)
                    val postLatency = System.currentTimeMillis() - startTime
                    writeSuccess = postSuccess
                    diagnosticMessage = if (postSuccess) {
                        "Read request succeeded ($recordsParsed parsed records). Diagnostic POST acknowledged (${postLatency}ms); actual property write, authentication, and conflict-safe bidirectional sync remain unverified."
                    } else {
                        "Read access verified ($recordsParsed records found), but write ping reported: $postResp."
                    }
                } catch (we: Exception) {
                    writeSuccess = false
                    diagnosticMessage = "Read access verified ($recordsParsed records), but write ping reported: ${we.localizedMessage ?: "timeout"}."
                }

                val finalResult = ConnectionDiagnostic(
                    isSuccess = readSuccess,
                    readStatus = if (readSuccess) "Verified (GET OK)" else "Read Failed",
                    writeStatus = if (writeSuccess) "Diagnostic POST acknowledged; data writes unverified" else "Write unverified",
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

            } else {
                val sheetId = extractSpreadsheetId(rawUrl)
                val testUrl = if (sheetId.isNotBlank()) {
                    "https://docs.google.com/spreadsheets/d/$sheetId/gviz/tq?tqx=out:csv"
                } else rawUrl

                val content = fetchUrlText(testUrl)
                val latency = System.currentTimeMillis() - startTime
                if (content.contains("<!DOCTYPE html>") || content.contains("<html", ignoreCase = true)) {
                    val result = ConnectionDiagnostic(
                        isSuccess = false,
                        readStatus = "Access Denied (HTML returned)",
                        writeStatus = "Read-Only Sheet",
                        latencyMs = latency,
                        recordsCount = 0,
                        message = "This sheet is not anonymously readable. Keep private data restricted and configure authenticated access.",
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

                val parsed = parseCsvOrTextData(content, "TestSheet")
                recordsParsed = parsed.size

                val hasValidRows = parsed.any { it.isValid }
                val finalResult = ConnectionDiagnostic(
                    isSuccess = hasValidRows,
                    readStatus = if (hasValidRows) "Verified ($recordsParsed records found)" else "Unverified (no valid inventory rows)",
                    writeStatus = "Read-Only (Direct Google Sheet CSV - Use Apps Script for 2-way write sync)",
                    latencyMs = latency,
                    recordsCount = recordsParsed,
                    message = "Read-only CSV access responded, but this does not verify authenticated access, private-data protection, or reverse synchronization. Keep owner and CRM data restricted; use an authenticated integration.",
                    checkedAt = timestamp
                )
                _lastDiagnostic.value = finalResult
                logSyncEvent(
                    direction = "VERIFICATION_TEST",
                    status = if (finalResult.isSuccess) "SUCCESS" else "FAILED",
                    recordCount = recordsParsed,
                    details = finalResult.message,
                    triggeredBy = "Diagnostics Test"
                )
                return@withContext finalResult
            }

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
            val configured = _sheetsScriptUrl.value.trim()
            val endpoint = if (configured.startsWith("https://") && configured.contains("script.google.com", ignoreCase = true)) {
                configured
            } else {
                DEFAULT_MASTER_SHEET_URL
            }
            val payload = JSONObject().apply {
                put("action", "DELETE")
                put("tabName", tabName)
                put("keyHeader", keyHeader)
                put("keyValue", cleanKey)
            }
            var url = java.net.URL(endpoint)
            val origin = url
            var method = "POST"
            val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
            var succeeded = false
            var lastError: String? = null

            for (hop in 0 until 5) {
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = url.openConnection() as java.net.HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.requestMethod = method
                    conn.setRequestProperty("User-Agent", "TLC-1")
                    if (method == "POST") {
                        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        conn.doOutput = true
                        conn.outputStream.use { os -> os.write(bodyBytes) }
                    }
                    val responseCode = conn.responseCode
                    if (responseCode in 200..299) {
                        val responseText = runCatching {
                            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                        }.getOrDefault("")
                        val parsed = runCatching { JSONObject(responseText) }.getOrNull()
                        if (parsed != null) {
                            val statusStr = parsed.optString("status", "").trim().lowercase(Locale.ENGLISH)
                            val okFlag = if (parsed.has("ok")) parsed.optBoolean("ok", true)
                                else if (parsed.has("success")) parsed.optBoolean("success", true)
                                else statusStr != "error" && statusStr != "failed"
                            if (statusStr == "error" || statusStr == "failed" || !okFlag) {
                                lastError = parsed.optString("message", parsed.optString("error", "Delete failed on Master Sheet"))
                                break
                            }
                        }
                        succeeded = true
                        break
                    } else if (responseCode == 301 || responseCode == 302 || responseCode == 303) {
                        val location = conn.getHeaderField("Location")
                            ?: throw Exception("Redirect missing Location header from Apps Script")
                        val next = java.net.URL(url, location)
                        val sameOrigin = next.protocol == origin.protocol &&
                            next.host == origin.host && next.port == origin.port
                        val googleResult = origin.protocol == "https" &&
                            origin.host == "script.google.com" &&
                            next.protocol == "https" &&
                            next.host == "script.googleusercontent.com"
                        if (!sameOrigin && !googleResult) {
                            throw Exception("Blocked unexpected Apps Script redirect")
                        }
                        url = next
                        method = "GET"
                    } else {
                        lastError = "HTTP $responseCode from Apps Script"
                        break
                    }
                } finally {
                    conn?.disconnect()
                }
            }
            if (!succeeded) {
                throw Exception(lastError ?: "Delete request did not complete on Apps Script")
            }
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

    suspend fun syncWithGoogleSheetNow(): String = withContext(Dispatchers.IO) {
        val rawUrl = _sheetsScriptUrl.value.trim()

        try {
            lastNonInventorySyncedCount = 0
            _connectionStatus.value = "Syncing with Google Sheet..."
            val allImportRows = mutableListOf<ImportPreviewRow>()

            if (rawUrl.isNotBlank()) {
                if (rawUrl.contains("script.google.com")) {
                    // Google Apps Script JSON Endpoint
                    val jsonText = fetchUrlText(rawUrl)
                    val trimmed = jsonText.trim()
                    if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
                        _isLiveConnected.value = false
                        _connectionStatus.value = "Error: Endpoint returned HTML page"
                        val err = "Sync Failed: Google Apps Script URL returned HTML instead of JSON.\n\n" +
                                "Fix: Verify the Apps Script deployment URL and authenticated access. Do not publish private inventory or owner contacts to Anyone."
                        logSyncEvent("SHEET_TO_APP", "FAILED", 0, err, "Google Sheet Sync")
                        return@withContext err
                    }
                    if (!AppsScriptSyncProtocol.hasInventoryResponse(jsonText)) {
                        throw java.io.IOException("Endpoint did not return inventory data; sync unverified")
                    }
                    val parsedRows = parseAppsScriptJsonData(jsonText)
                    allImportRows.addAll(parsedRows)
                } else {
                    // Google Sheet URL or Spreadsheet ID
                    val sheetId = extractSpreadsheetId(rawUrl)
                    if (sheetId.isNotBlank()) {
                        var syncedCrmRowCount = 0
                        for (tabName in DEFAULT_SYNCED_TABS) {
                            try {
                                val encodedTab = java.net.URLEncoder.encode(tabName, "UTF-8")
                                val csvUrl = "https://docs.google.com/spreadsheets/d/$sheetId/gviz/tq?tqx=out:csv&sheet=$encodedTab"
                                val csvContent = fetchUrlText(csvUrl)
                                if (csvContent.isNotBlank() && !csvContent.contains("<!DOCTYPE html>") && !csvContent.contains("<html", ignoreCase = true)) {
                                    if (tabName.equals("CRM", ignoreCase = true)) {
                                        val crmRows = parseCsvLinesToGrid(csvContent)
                                        syncedCrmRowCount = (crmRows.size - 1).coerceAtLeast(0)
                                        if (crmRows.size > 1) {
                                            lastNonInventorySyncedCount += mergeCrmSheetRowsIntoPrefs(crmRows)
                                        }
                                    } else if (tabName.equals("Employees", ignoreCase = true)) {
                                        val empRows = parseCsvLinesToGrid(csvContent)
                                        if (empRows.size > 1) {
                                            lastNonInventorySyncedCount += mergeEmployeesSheetRowsIntoPrefs(empRows)
                                        }
                                    } else if (tabName.equals("Call Logs", ignoreCase = true)) {
                                        val callRows = parseCsvLinesToGrid(csvContent)
                                        if (callRows.size > 1) {
                                            lastNonInventorySyncedCount += mergeCallLogsSheetRowsIntoPrefs(callRows)
                                        }
                                    } else if (tabName.equals("Commercial Projects", ignoreCase = true) ||
                                        tabName.equals("Residential Projects", ignoreCase = true)
                                    ) {
                                        val projRows = parseCsvLinesToGrid(csvContent)
                                        if (projRows.size > 1 && context != null) {
                                            lastNonInventorySyncedCount += ProjectDirectoryStore.mergeSyncedProjectRows(context, tabName, projRows)
                                            _syncRevision.value++
                                        }
                                    } else if (tabName.equals("Daily Reports", ignoreCase = true)) {
                                        val repRows = parseCsvLinesToGrid(csvContent)
                                        if (repRows.size > 1) {
                                            lastNonInventorySyncedCount += mergeDailyReportRowsIntoCache(repRows)
                                        }
                                    } else if (tabName.equals("Tenants", ignoreCase = true)) {
                                        val tenantRows = parseCsvLinesToGrid(csvContent)
                                        if (tenantRows.size > 1) {
                                            lastNonInventorySyncedCount += mergeTenantsSheetRowsIntoCache(tenantRows)
                                        }
                                    } else if (tabName.equals("Brokers & Vendors", ignoreCase = true)) {
                                        val bvRows = parseCsvLinesToGrid(csvContent)
                                        if (bvRows.size > 1) {
                                            lastNonInventorySyncedCount += mergeBrokersVendorsSheetRowsIntoCache(bvRows)
                                        }
                                    } else {
                                        val rows = parseCsvOrTextData(csvContent, tabName)
                                        allImportRows.addAll(rows)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        if (syncedCrmRowCount > 0) {
                            logSyncEvent(
                                direction = "SHEET_TO_APP",
                                status = "SUCCESS",
                                recordCount = syncedCrmRowCount,
                                details = "CRM tab reachable ($syncedCrmRowCount rows)",
                                triggeredBy = "Google Sheet Sync"
                            )
                        }

                        // Fallback: If tab-based gviz produced no rows, try direct CSV export
                        if (allImportRows.isEmpty()) {
                            try {
                                val exportUrl = "https://docs.google.com/spreadsheets/d/$sheetId/export?format=csv"
                                val csvContent = fetchUrlText(exportUrl)
                                if (csvContent.isNotBlank() && !csvContent.contains("<!DOCTYPE html>") && !csvContent.contains("<html", ignoreCase = true)) {
                                    val rows = parseCsvOrTextData(csvContent, "Google Sheet Master")
                                    allImportRows.addAll(rows)
                                }
                            } catch (_: Exception) {}
                        }
                    } else {
                        // Direct CSV URL
                        val csvContent = fetchUrlText(rawUrl)
                        if (csvContent.isNotBlank() && !csvContent.contains("<!DOCTYPE html>") && !csvContent.contains("<html", ignoreCase = true)) {
                            val rows = parseCsvOrTextData(csvContent, "Google Sheet Import")
                            allImportRows.addAll(rows)
                        }
                    }
                }
            } else {
                _isLiveConnected.value = false
                _connectionStatus.value = "URL Not Configured"
                val err = "No Master Google Sheet or Web App URL configured.\n\nPlease configure your Google Apps Script Web App URL or Google Sheet URL in the 'Master Sheet Sync' tab to enable live synchronization."
                logSyncEvent("SHEET_TO_APP", "WARNING", 0, err, "Manual Sync")
                return@withContext err
            }

            if (allImportRows.isNotEmpty()) {
                val stats = importRowsDetailed(allImportRows, duplicateHandling = "SYNC_MERGE")
                val nowMs = System.currentTimeMillis()
                val nowStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(nowMs))
                if (stats.failedCount == 0) {
                    markSuccessfulSyncNow(nowMs)
                }
                context?.let { appCtx ->
                    runCatching {
                        com.example.notifications.TeamSyncDiffEngine.processPostSyncDiff(
                            context = appCtx,
                            properties = _properties.value
                        )
                    }
                }
                _isLiveConnected.value = stats.failedCount == 0
                _connectionStatus.value = if (stats.failedCount == 0) "Two-way Master Sheet sync verified (${_properties.value.size} properties)" else "Sheet sync incomplete: ${stats.failedCount} records failed"
                logSyncEvent(
                    direction = "SHEET_TO_APP",
                    status = if (stats.failedCount == 0) "SUCCESS" else "WARNING",
                    recordCount = stats.recordsFound,
                    details = "Sheet import: ${stats.addedCount} added, ${stats.updatedCount} updated, ${stats.skippedCount} skipped, ${stats.failedCount} failed",
                    triggeredBy = "Google Sheet Sync"
                )
                return@withContext (if (stats.failedCount == 0) "Google Sheet Import Complete!\n\n" else "Google Sheet Import Incomplete — ${stats.failedCount} records failed. Review failed rows before relying on this inventory.\n\n") +
                        "• Source Records Found: ${stats.recordsFound}\n" +
                        "• New Properties Added: ${stats.addedCount}\n" +
                        "• Existing Properties Updated: ${stats.updatedCount}\n" +
                        "• Duplicates Skipped: ${stats.skippedCount}\n" +
                        "• Failed Records: ${stats.failedCount}\n" +
                        "• Total Active Inventory: ${_properties.value.size} Properties\n" +
                        (if (stats.failedCount == 0) "• Last Successful Import: $nowStr" else "• Last Successful Import: ${_lastSyncTimestamp.value} (current import incomplete)")
            } else {
                if (lastNonInventorySyncedCount > 0) {
                    markSuccessfulSyncNow()
                    context?.let { appCtx ->
                        runCatching {
                            com.example.notifications.TeamSyncDiffEngine.processPostSyncDiff(
                                context = appCtx,
                                properties = _properties.value
                            )
                        }
                    }
                }
                _isLiveConnected.value = lastNonInventorySyncedCount > 0
                _connectionStatus.value = "Sheet import not verified (0 valid records)"
                logSyncEvent(
                    direction = "SHEET_TO_APP",
                    status = "WARNING",
                    recordCount = 0,
                    details = "Connected to sheet but 0 rows parsed",
                    triggeredBy = "Google Sheet Sync"
                )
                return@withContext "Connected to Google Sheet, but no valid property rows could be parsed.\n\n" +
                        "Check the sheet tab names, column mapping, and authenticated endpoint access. Keep private inventory and owner contacts restricted."
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
            return@withContext "Failed to sync Google Sheet: $err\n\nCheck the endpoint URL and authenticated access. Do not publish private inventory."
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
                val dataObj = root.optJSONObject("data") ?: root.optJSONObject("properties") ?: root
                val keys = dataObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val sheetArray = dataObj.optJSONArray(key)
                    if (sheetArray != null) {
                        val parsedRows = convertJsonArrayToRows(sheetArray)
                        if (parsedRows.isNotEmpty()) {
                            if (key.equals("CRM", ignoreCase = true) ||
                                key.equals("CRM Leads", ignoreCase = true) ||
                                key.equals("Leads", ignoreCase = true)
                            ) {
                                lastNonInventorySyncedCount += mergeCrmSheetRowsIntoPrefs(parsedRows)
                            } else if (key.equals("Employees", ignoreCase = true) ||
                                key.equals("Team", ignoreCase = true) ||
                                key.equals("Staff", ignoreCase = true)
                            ) {
                                lastNonInventorySyncedCount += mergeEmployeesSheetRowsIntoPrefs(parsedRows)
                            } else if (key.equals("Call Logs", ignoreCase = true) ||
                                key.equals("CallLogs", ignoreCase = true) ||
                                key.equals("Calls", ignoreCase = true)
                            ) {
                                lastNonInventorySyncedCount += mergeCallLogsSheetRowsIntoPrefs(parsedRows)
                            } else if (key.equals("Commercial Projects", ignoreCase = true) ||
                                key.equals("Residential Projects", ignoreCase = true)
                            ) {
                                if (context != null) {
                                    lastNonInventorySyncedCount += ProjectDirectoryStore.mergeSyncedProjectRows(context, key, parsedRows)
                                    _syncRevision.value++
                                }
                            } else if (key.equals("Daily Reports", ignoreCase = true)) {
                                lastNonInventorySyncedCount += mergeDailyReportRowsIntoCache(parsedRows)
                            } else if (key.equals("Tenants", ignoreCase = true) ||
                                key.equals("Tenant", ignoreCase = true) ||
                                key.equals("Tenant Directory", ignoreCase = true)
                            ) {
                                lastNonInventorySyncedCount += mergeTenantsSheetRowsIntoCache(parsedRows)
                            } else if (key.equals("Brokers & Vendors", ignoreCase = true) ||
                                key.equals("Brokers and Vendors", ignoreCase = true) ||
                                key.equals("Brokers", ignoreCase = true) ||
                                key.equals("Vendors", ignoreCase = true)
                            ) {
                                lastNonInventorySyncedCount += mergeBrokersVendorsSheetRowsIntoCache(parsedRows)
                            } else if (key.contains("Projects", ignoreCase = true) ||
                                key.equals("SETTINGS", ignoreCase = true) ||
                                key.equals("SYNC_LOG", ignoreCase = true) ||
                                key.equals("DATA_REVIEW", ignoreCase = true)
                            ) {
                                // Skip other project & system tabs when parsing Property Inventory
                            } else {
                                val worksheet = ParsedWorksheet(sheetName = key, rows = parsedRows)
                                results.addAll(parseWorksheets(listOf(worksheet)))
                            }
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

        val remaining = com.example.ui.screens.readCrmLeads(appContext).filterNot {
            it.id.equals(cleanId, ignoreCase = true)
        }
        com.example.ui.screens.saveCrmLeads(appContext, remaining)
        _syncRevision.value++

        repoScope.launch(Dispatchers.IO) {
            deleteRecordPermanently(
                tabName = "CRM",
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
        val preVerifiedLive = newItem.verificationStatus == VerificationStatus.VERIFIED &&
            com.example.inventory.InventoryQuality.isReadyForLiveVerification(newItem)
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
        val preVerifiedLive = newItem.verificationStatus == VerificationStatus.VERIFIED &&
            com.example.inventory.InventoryQuality.isReadyForLiveVerification(newItem)
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
     * Batch 38: Fetch exact building photos via Google Places Text Search & Photos API first
     * (with coordinate bias when available), falling back to exact-quoted Bing Images only if
     * Google Places returns 0 photos.
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
            "micro market" to "Micro Market",
            "legacy property code" to "Property Code",
            "date added" to "Date",
            "location" to "Location",
            "project / building" to "Project / Building",
            "amenities" to "Amenities",
            "unit no" to "Unit / Shop / Office No",
            "carpet sqft" to "Carpet Area",
            "built up sqft" to "Built-up Area",
            "sale price inr" to "Price / Sale",
            "rent inr/month" to "Rent",
            "deposit inr" to "Deposit",
            "roi percent" to "ROI %",
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
                var code = ""
                var date = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).format(Date())
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
                var lastUpdated = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())
                var internalRemark = ""
                var portfolio = ""
                var transType = ""
                var gmapsUrl = ""
                var latitude: Double? = null
                var longitude: Double? = null
                var verifStatus = VerificationStatus.PENDING
                var lastVerifiedDateClean = ""
                var verifiedBy = ""
                var recordId = ""
                var syncUpdatedAt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
                var archiveStatus = ArchiveStatus.ACTIVE

                val unmappedData = mutableMapOf<String, String>()
                var amenitiesRaw = ""

                if (hasHeader) {
                    cols.forEachIndexed { colIdx, value ->
                        val target = columnIndexToStandard[colIdx]
                        if (target != null) {
                            when (target) {
                                "Property Code" -> if (value.isNotBlank() && !isShiftedAmenityText(value)) code = value
                                "Date" -> if (value.isNotBlank()) date = value
                                "Location" -> if (value.isNotBlank() && !isShiftedAmenityText(value)) location = value
                                "Micro Market" -> if (value.isNotBlank() && !isShiftedAmenityText(value)) microMarket = value
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
                                "Built-up Area" -> rawBuiltUp = parsePlainDecimalOrSci(value)
                                "Price / Sale" -> priceSale = CalculationUtils.parseCurrencyInput(value)?.let { CalculationUtils.normalizeSalePriceInr(it) }
                                "Rent" -> rent = CalculationUtils.parseCurrencyInput(value)
                                "Deposit" -> deposit = CalculationUtils.parseCurrencyInput(value)
                                "ROI %" -> roi = parsePlainDecimalOrSci(value)
                                "Possession" -> possession = value
                                "Furnishing" -> furnishing = parseFurnishing(value)
                                "Status" -> status = parseStatus(value)
                                "Type of POC" -> if (value.isNotBlank()) pocType = normalizePocType(value)
                                "Owner / Contact Person" -> owner = value
                                "Contact Number" -> contact = value
                                "Source" -> if (value.isNotBlank()) source = value
                                "Remarks" -> remarks = value
                                "Listing Link" -> listingLink = value
                                "Last Updated" -> if (value.isNotBlank()) lastUpdated = value
                                "Remark" -> internalRemark = value
                                "Portfolio" -> portfolio = value
                                "Transaction Type" -> transType = value
                                "Google Maps URL" -> gmapsUrl = value
                                "Latitude" -> latitude = value.toDoubleOrNull()
                                "Longitude" -> longitude = value.toDoubleOrNull()
                                "Verification Status" -> verifStatus = VerificationStatus.fromString(value)
                                "Last Verified Date" -> if (value.isNotBlank()) lastVerifiedDateClean = value
                                "Verified By" -> verifiedBy = value
                                "Record ID" -> recordId = value
                                "Sync Updated At" -> if (value.isNotBlank()) syncUpdatedAt = value
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
                    status = parseStatus(cols.getOrElse(16) { "" })
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
                    verifStatus = VerificationStatus.fromString(cols.getOrElse(29) { "" })
                    lastVerifiedDateClean = cols.getOrElse(30) { lastVerifiedDateClean }
                    verifiedBy = cols.getOrElse(31) { "" }
                    recordId = cols.getOrElse(32) { "" }
                    syncUpdatedAt = cols.getOrElse(33) { syncUpdatedAt }
                    archiveStatus = ArchiveStatus.fromString(cols.getOrElse(34) { "" })
                }

                // A micro-market is a fallback location, never a replacement for an
                // explicitly supplied locality. Preserve the source column as evidence.
                if (microMarket.isNotBlank()) {
                    if (location.isBlank()) location = microMarket
                    unmappedData["Micro Market"] = microMarket
                }

                // Append all unmapped columns to remarks so zero source info is discarded
                if (unmappedData.isNotEmpty()) {
                    val extraText = "[Extra: " + unmappedData.entries.joinToString("; ") { "${it.key} = ${it.value}" } + "]"
                    remarks = if (remarks.isNotBlank()) "$remarks\n$extraText" else extraText
                }

                val classifiedTab = classifyImportTab(
                    subType, code, propType, portfolio, transType, fileName, priceSale, rent
                )
                // The model requires a tab, but ambiguous rows must stay in review.
                tab = classifiedTab ?: InventoryTab.OFF_LEASE
                portfolio = if (tab.brand == Brand.RESIDENTIAL) "Residential" else "Commercial"
                transType = when {
                    tab == InventoryTab.PRE_LEASED -> "Pre-Leased Sale"
                    tab.isRental -> "Lease"
                    else -> "Sale"
                }

                // Commercial Area Loading Calculations
                val (carpetArea, builtUpArea, isAreaEstimated) = calculateCommercialLoading(
                    carpet = rawCarpet,
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
                        lastVerifiedDateClean = lastVerifiedDateClean,
                        verifiedBy = verifiedBy.ifBlank { null },
                        isAreaEstimated = isAreaEstimated,
                        unmappedColumns = unmappedData,
                        conflictResolution = if (isDuplicate) "REVIEW" else "INSERT",
                        pocType = pocType,
                        archiveStatus = archiveStatus,
                        lastUpdated = lastUpdated.ifBlank { syncUpdatedAt }
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
            source == "residential inventory" -> true
            source == "commercial inventory" -> false
            else -> listOf("apartment", "flat", "villa", "bungalow", "residential",
                "bhk", "resi", "und const", "under const").any { type.contains(it) || subtype.contains(it) }
        }
        if (listOf("pre-leased", "pre leased", "pre - leased").any { subtype.contains(it) || tx.contains(it) }) {
            return if (residential) null else InventoryTab.PRE_LEASED
        }
        if (residential && (subtype.contains("under const") || subtype.contains("und const"))) {
            return InventoryTab.UND_CONST
        }
        val evidence = "$subtype $tx"
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
            combined.contains("OFF LEASE") || combined.contains("OFFICE LEASE") || combined.startsWith("OL-") || combined.contains(" OL ") -> InventoryTab.OFF_LEASE
            combined.contains("OFF OUT") || combined.contains("OFFICE SALE") || combined.startsWith("OO-") || combined.contains(" OO ") -> InventoryTab.OFF_OUT
            combined.contains("SHOP LEASE") || combined.contains("RETAIL LEASE") || combined.startsWith("SL-") || combined.contains(" SL ") -> InventoryTab.SHOP_LEASE
            combined.contains("SHOP OUT") || combined.contains("RETAIL SALE") || combined.startsWith("SO-") || combined.contains(" SO ") -> InventoryTab.SHOP_OUT
            combined.contains("PRE - LEASED") || combined.contains("PRE-LEASED") || combined.contains("PRE LEASED") || combined.startsWith("PL-") -> InventoryTab.PRE_LEASED
            combined.contains("RESI RENT") || combined.contains("RESIDENTIAL RENT") || combined.startsWith("RR-") || combined.contains(" RR ") -> InventoryTab.RESI_RENT
            combined.contains("RESI RESALE") || combined.contains("RESIDENTIAL RESALE") || combined.startsWith("RS-") || combined.contains(" RS ") -> InventoryTab.RESI_RESALE
            combined.contains("UND CONST") || combined.contains("UNDER CONST") || combined.startsWith("UC-") || combined.contains(" UC ") -> InventoryTab.UND_CONST
            else -> InventoryTab.OFF_LEASE
        }
    }

    /** Seeds the packaged master export into Archive when local inventory is empty, and migrates unverified seeded records to Archive once. */
    fun bootstrapBundledActualInventory(): SyncStats {
        val appContext = context ?: return SyncStats(0, 0, 0, 0, 0)
        val prefs = appContext.getSharedPreferences("tlc_actual_data_bootstrap_v1", Context.MODE_PRIVATE)
        if (prefs.getBoolean("inventory_seeded", false) || _properties.value.isNotEmpty()) {
            // One-time non-destructive archive-first migration for existing unverified properties
            if (!prefs.getBoolean("archive_first_migration_v1", false) && _properties.value.isNotEmpty()) {
                val migrated = _properties.value.map { prop ->
                    if (prop.verificationStatus != VerificationStatus.VERIFIED) {
                        prop.copy(archiveStatus = ArchiveStatus.ARCHIVED)
                    } else prop
                }
                if (PropertiesLocalDatabase.saveProperties(appContext, migrated)) {
                    _properties.value = migrated
                    prefs.edit().putBoolean("archive_first_migration_v1", true).commit()
                }
            }
            return SyncStats(0, 0, 0, 0, 0)
        }
        return runCatching {
            val raw = appContext.assets.open("actual_inventory.csv").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val rows = parseCsvOrTextData(raw, "TLC-1 Master Database — actual inventory.csv")
            val stats = importRowsDetailed(rows, duplicateHandling = "SKIP", archiveUnverified = true)
            if (stats.addedCount > 0 && stats.failedCount == 0) {
                prefs.edit()
                    .putBoolean("inventory_seeded", true)
                    .putBoolean("archive_first_migration_v1", true)
                    .commit()
            }
            stats
        }.getOrElse { SyncStats(0, 0, 0, 0, 1) }
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
        val today = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).format(Date())
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
                        // Respect unpushed local edits unless this property is already synced
                        val hasUnsyncedLocalEdit = !existing.isSyncedToSheet && existing.id in pendingEntityIds
                        if (hasUnsyncedLocalEdit) {
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
                                // Compute Live vs Pending strictly from Sheet headers
                                status = row.status,
                                verificationStatus = row.verificationStatus,
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
                            status = row.status,
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
                            verificationStatus = row.verificationStatus,
                            lastVerifiedDateClean = row.lastVerifiedDateClean.ifBlank { existing.lastVerifiedDateClean },
                            verifiedBy = row.verifiedBy ?: existing.verifiedBy,
                            recordId = if (existing.recordId.isNotBlank()) existing.recordId else row.recordId,
                            syncUpdatedAt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date()),
                            // An explicit overwrite must not silently reactivate an archived record.
                            archiveStatus = existing.archiveStatus,
                            isAreaEstimated = row.isAreaEstimated,
                            pocType = row.pocType.ifBlank { existing.pocType }
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
                            pocType = row.pocType
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
                    archiveStatus = if (archiveUnverified && row.verificationStatus != VerificationStatus.VERIFIED) {
                        ArchiveStatus.ARCHIVED
                    } else {
                        row.archiveStatus
                    },
                    isAreaEstimated = row.isAreaEstimated,
                    images = extractImageUrlFromLink(row.listingLink),
                    pocType = row.pocType
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
        val allProps = _properties.value
        val maxNum = allProps.mapNotNull { p ->
            val code = p.propertyCode
            if (code.startsWith("$prefix-")) {
                code.substringAfter("$prefix-").filter { it.isDigit() }.toIntOrNull()
            } else null
        }.maxOrNull() ?: 0
        return String.format(Locale.US, "%s-PUN-%04d", prefix, maxNum + 1)
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

    private fun resolveAuthScriptUrl(overrideScriptUrl: String?): String? {
        val candidate = overrideScriptUrl?.trim()?.takeIf { it.isNotBlank() } ?: _sheetsScriptUrl.value.trim()
        if (candidate.isBlank()) return null
        val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        if (!candidate.contains("script.google.com", ignoreCase = true)) return null
        return candidate
    }

    private fun postAppsScriptJsonRequest(targetUrl: String, request: JSONObject): Result<JSONObject> {
        var url = java.net.URL(targetUrl.trim())
        val origin = url
        var method = "POST"
        val payload = request.toString().toByteArray(Charsets.UTF_8)

        repeat(5) {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.requestMethod = method
                connection.setRequestProperty("User-Agent", "TLC-1")
                if (method == "POST") {
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connection.doOutput = true
                    connection.outputStream.use { it.write(payload) }
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                    if (responseText.startsWith("<!DOCTYPE", ignoreCase = true) || responseText.startsWith("<html", ignoreCase = true)) {
                        return Result.failure(IllegalStateException("Apps Script endpoint returned HTML instead of JSON. Check Web App access deployment."))
                    }
                    val parsed = runCatching { JSONObject(responseText) }.getOrNull()
                        ?: return Result.failure(IllegalStateException("Invalid JSON response from employee authentication endpoint."))
                    return Result.success(parsed)
                }
                if (code == 301 || code == 302 || code == 303) {
                    val location = connection.getHeaderField("Location")
                        ?: return Result.failure(IllegalStateException("Apps Script redirect missing Location header."))
                    val next = java.net.URL(url, location)
                    val sameOrigin = next.protocol == origin.protocol &&
                        next.host == origin.host && next.port == origin.port
                    val googleResult = origin.protocol == "https" &&
                        origin.host == "script.google.com" &&
                        next.protocol == "https" &&
                        next.host == "script.googleusercontent.com"
                    if (!sameOrigin && !googleResult) {
                        return Result.failure(IllegalStateException("Blocked unexpected Apps Script redirect."))
                    }
                    url = next
                    method = "GET"
                } else {
                    return Result.failure(IllegalStateException("Server returned HTTP $code during employee authentication."))
                }
            } catch (e: Exception) {
                return Result.failure(e)
            } finally {
                connection?.disconnect()
            }
        }
        return Result.failure(IllegalStateException("Too many redirects from Apps Script endpoint."))
    }

    suspend fun authenticateEmployee(
        loginId: String,
        password: String,
        overrideScriptUrl: String? = null
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        val cleanLoginId = loginId.trim()
        val cleanPassword = password.trim()
        if (cleanLoginId.isBlank()) {
            return@withContext Result.failure(Exception("Enter your Login ID or Mobile Number."))
        }
        if (cleanPassword.isBlank()) {
            return@withContext Result.failure(Exception("Enter your Password."))
        }

        val endpoint = resolveAuthScriptUrl(overrideScriptUrl)
            ?: return@withContext Result.failure(
                Exception("Configure your Google Apps Script Web App URL (script.google.com/macros/s/.../exec) to sign in.")
            )

        val requestBodyJson = JSONObject().apply {
            put("action", "LOGIN_EMPLOYEE")
            put("loginId", cleanLoginId)
            put("password", cleanPassword)
        }

        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "TLC-1")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                val bodyText = response.body?.string()?.trim().orEmpty()

                if (bodyText.startsWith("<!DOCTYPE", ignoreCase = true) || bodyText.startsWith("<html", ignoreCase = true)) {
                    return@withContext Result.failure(Exception("Apps Script Web App returned HTML (HTTP $responseCode). Check deployment sharing permissions ('Anyone')."))
                }

                val root = runCatching { JSONObject(bodyText) }.getOrNull()
                if (root == null) {
                    val msg = if (bodyText.isNotBlank()) bodyText else "Server error: HTTP $responseCode"
                    return@withContext Result.failure(Exception(msg))
                }

                val isOk = root.optString("status", "").equals("OK", true) ||
                    root.optString("status", "").equals("success", true) ||
                    root.optBoolean("success", false) ||
                    root.optBoolean("ok", false)

                if (!isOk) {
                    val serverMsg = root.optString("message", "").ifBlank {
                        root.optString("error", "").ifBlank { "Server error: HTTP $responseCode" }
                    }
                    return@withContext Result.failure(Exception(serverMsg))
                }

                val empObj = root.optJSONObject("employee")
                    ?: root.optJSONObject("user")
                    ?: root.optJSONObject("data")
                    ?: root

                val empId = empObj.optString("employeeId", empObj.optString("id", cleanLoginId)).trim().ifBlank { cleanLoginId }
                val username = empObj.optString("name", empObj.optString("employeeName", empObj.optString("username", empObj.optString("fullName", "")))).trim()
                if (username.isBlank()) {
                    val msg = root.optString("message", "").ifBlank { "Employee profile not found in server response." }
                    return@withContext Result.failure(Exception(msg))
                }

                val mobile = empObj.optString("mobile", empObj.optString("mobileNumber", empObj.optString("phone", ""))).trim()
                val email = empObj.optString("email", "").trim()
                val division = empObj.optString("division", empObj.optString("department", "Commercial + Residential")).trim().ifBlank {
                    "Commercial + Residential"
                }
                val rawRole = empObj.optString("role", "Sales Executive").trim()
                val parsedRole = when {
                    username.equals("Pawan Patil", ignoreCase = true) ||
                        rawRole.contains("Super Admin", ignoreCase = true) -> UserRole.ADMIN
                    rawRole.equals("Admin", ignoreCase = true) -> UserRole.ADMIN
                    rawRole.contains("Commercial", ignoreCase = true) -> UserRole.COMMERCIAL_MANAGER
                    rawRole.contains("Residential", ignoreCase = true) -> UserRole.RESIDENTIAL_MANAGER
                    rawRole.contains("Inventory", ignoreCase = true) -> UserRole.INVENTORY_MANAGER
                    rawRole.contains("Read", ignoreCase = true) -> UserRole.READ_ONLY
                    else -> UserRole.fromString(rawRole)
                }
                val accountStatus = empObj.optString("accountStatus", empObj.optString("status", "Active")).trim().ifBlank { "Active" }
                if (accountStatus.equals("Suspended", ignoreCase = true) ||
                    accountStatus.equals("Inactive", ignoreCase = true) ||
                    accountStatus.equals("Disabled", ignoreCase = true)
                ) {
                    return@withContext Result.failure(Exception("Account is $accountStatus. Contact Admin."))
                }

                if (!overrideScriptUrl.isNullOrBlank() && overrideScriptUrl.trim() != _sheetsScriptUrl.value.trim()) {
                    setGoogleSheetUrl(endpoint)
                }

                val profile = UserProfile(
                    username = username,
                    mobileNumber = mobile,
                    email = email,
                    role = parsedRole,
                    profilePhotoUri = null,
                    employeeId = empId,
                    division = division,
                    accountStatus = accountStatus
                )
                logSyncEvent(
                    direction = "EMPLOYEE_AUTH",
                    status = "SUCCESS",
                    recordCount = 1,
                    details = "Authenticated $username ($empId, $division, $accountStatus)",
                    triggeredBy = username
                )
                Result.success(profile)
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message?.takeIf { it.isNotBlank() } ?: "Network connection failed during authentication."))
        }
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
            ?: return@withContext Result.failure(
                IllegalStateException("Configure your Google Apps Script Web App URL (script.google.com/macros/s/.../exec) first.")
            )

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
        val endpoint = _sheetsScriptUrl.value.trim().ifBlank { DEFAULT_MASTER_SHEET_URL }
        if (!endpoint.startsWith("https://") || !endpoint.contains("script.google.com", ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException("Configure a valid Google Apps Script Web App URL to sync properties."))
        }

        val isCommercial = property.isCommercial()
        val permId = property.recordId.ifBlank { property.id }
        val saleVal = property.priceSale ?: if (!property.tab.isRental && property.priceOrRent > 0.0) property.priceOrRent else null
        val rentVal = property.rent ?: if (property.tab.isRental && property.priceOrRent > 0.0) property.priceOrRent else null
        val effectiveListingUrl = property.listingLink?.trim()?.takeIf { it.isNotBlank() }
            ?: property.images.firstOrNull { it.startsWith("http") }.orEmpty()
        val microMarketVal = property.unmappedColumns["Micro Market"]?.takeIf { it.isNotBlank() } ?: property.location
        val ownerDisplay = property.ownerName?.takeIf { it.isNotBlank() } ?: property.brokerName.orEmpty()
        val contactDisplay = property.ownerContact?.takeIf { it.isNotBlank() } ?: property.brokerContact.orEmpty()
        val verifiedDate = property.lastVerifiedDateClean.ifBlank { property.lastVerificationDate }
        val updatedDate = property.updatedAt.ifBlank { property.syncUpdatedAt }

        val record = JSONObject().apply {
            if (isCommercial) {
                // Exact 40 Commercial Inventory Sheet Columns
                put("Property ID", permId)
                put("Legacy Property Code", property.propertyCode)
                put("Date Added", property.date)
                put("Location", property.location)
                put("Micro Market", microMarketVal)
                put("Project / Building", property.projectName)
                put("Unit No", property.unitNumber)
                put("Floor", property.floor)
                put("Property Type", property.propertyType)
                put("Sub Type", property.tab.code)
                put("Transaction Type", property.transactionType)
                put("Carpet SqFt", if (property.carpetAreaSqFt > 0.0) property.carpetAreaSqFt else "")
                put("Built-up SqFt", property.builtUpAreaSqFt ?: "")
                put("Chargeable SqFt", property.builtUpAreaSqFt ?: property.carpetAreaSqFt.takeIf { it > 0.0 } ?: "")
                put("Sale Price INR", saleVal ?: "")
                put("Rent INR/Month", rentVal ?: "")
                put("Deposit INR", property.deposit ?: "")
                put("ROI Percent", property.roiPercent ?: "")
                put("Possession Date", property.possession)
                put("Furnishing", property.furnishing.label)
                put("Availability Status", property.status.label)
                put("Tenant / Brand", property.tenantName.orEmpty())
                put("Lease Start", property.unmappedColumns["Lease Start"].orEmpty())
                put("Lease End", property.leaseExpiryDate)
                put("Lock-in Months", property.lockInMonths ?: property.lockInPeriodYears?.let { it * 12 } ?: "")
                put("Escalation Terms", property.escalation)
                put("Maintenance Terms", property.maintenance?.toString() ?: property.unmappedColumns["Maintenance Terms"].orEmpty())
                put("Parking", property.parking)
                put("Type of POC", property.pocType)
                put("Owner / POC", ownerDisplay)
                put("Owner Contact", contactDisplay)
                put("Source", property.sourceInfo.orEmpty())
                put("Source Reference", property.unmappedColumns["Source Reference"].orEmpty())
                put("Remarks", property.remarks.orEmpty())
                put("Listing URL", effectiveListingUrl)
                put("Latitude", property.latitude ?: property.lat ?: "")
                put("Longitude", property.longitude ?: property.lng ?: "")
                put("Last Verified", verifiedDate)
                put("Last Updated", updatedDate)
                put("Verification Status", property.verificationStatus.label)
                put("Record Status", property.archiveStatus.label)
            } else {
                // Exact 37 Residential Inventory Sheet Columns
                put("Property ID", permId)
                put("Legacy Property Code", property.propertyCode)
                put("Date Added", property.date)
                put("Location", property.location)
                put("Micro Market", microMarketVal)
                put("Project / Building", property.projectName)
                put("Developer", property.builderName.orEmpty())
                put("Tower / Wing", property.unmappedColumns["Tower / Wing"].orEmpty())
                put("Unit No", property.unitNumber)
                put("Floor", property.floor)
                put("Property Type", property.propertyType)
                put("Configuration", property.bhk.orEmpty())
                put("Transaction Type", property.transactionType)
                put("RERA Number", property.unmappedColumns["RERA Number"].orEmpty())
                put("Carpet SqFt", if (property.carpetAreaSqFt > 0.0) property.carpetAreaSqFt else "")
                put("Built-up SqFt", property.builtUpAreaSqFt ?: "")
                put("Plot SqFt", property.unmappedColumns["Plot SqFt"].orEmpty())
                put("Sale Price INR", saleVal ?: "")
                put("Rent INR/Month", rentVal ?: "")
                put("Deposit INR", property.deposit ?: "")
                put("Possession Date", property.possession)
                put("Furnishing", property.furnishing.label)
                put("Availability Status", property.status.label)
                put("Parking", property.parking)
                put("Amenities", property.amenities.joinToString(", "))
                put("Type of POC", property.pocType)
                put("Owner / POC", ownerDisplay)
                put("Owner Contact", contactDisplay)
                put("Source", property.sourceInfo.orEmpty())
                put("Source Reference", property.unmappedColumns["Source Reference"].orEmpty())
                put("Remarks", property.remarks.orEmpty())
                put("Listing URL", effectiveListingUrl)
                put("Latitude", property.latitude ?: property.lat ?: "")
                put("Longitude", property.longitude ?: property.lng ?: "")
                put("Last Verified", verifiedDate)
                put("Last Updated", updatedDate)
                put("Verification Status", property.verificationStatus.label)
                put("Record Status", property.archiveStatus.label)
            }
        }

        val targetTabName = if (isCommercial) "Commercial Inventory" else "Residential Inventory"
        val request = JSONObject().apply {
            put("action", "UPSERT")
            put("tabName", targetTabName)
            put("keyHeader", "Property ID")
            put("keyValue", permId)
            put("isCommercial", isCommercial)
            put("propertyId", permId)
            put("propertyCode", property.propertyCode)
            put("record", record)
            if (property.images.isNotEmpty()) {
                put("photos", JSONArray(property.images))
            }
            if (property.attachments.isNotEmpty()) {
                put("attachments", JSONArray(property.attachments.map { att ->
                    JSONObject().apply {
                        put("name", att.name)
                        put("type", att.type.name)
                        put("url", att.localUriOrPath)
                    }
                }))
            }
        }

        val response = postAppsScriptJsonRequest(endpoint, request).getOrElse { err ->
            logSyncEvent("APP_TO_SHEET", "FAILED", 0, "Sheet push failed for ${property.propertyCode}: ${err.message}", actor())
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
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

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
            details = "Pushed ${property.propertyCode.ifBlank { permId }} ($targetTabName) to Master Sheet",
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

    private fun mergeEmployeesSheetRowsIntoPrefs(rows: List<List<String>>): Int {
        val appContext = context ?: return 0
        if (rows.size < 2) return 0
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

        val idIdx = col("Employee ID", "Emp ID", "ID")
        val nameIdx = col("Employee Name", "Name", "Full Name", "Staff Name")
        val mobileIdx = col("Mobile", "Login Mobile", "Phone", "Phone Number", "Contact", "WhatsApp")
        val divisionIdx = col("Division", "Business Scope", "Business Scopes", "Segment", "Department")
        val teamIdIdx = col("Team ID", "Team")
        val roleIdx = col("Role", "Job Title", "Designation", "Access Role")
        val statusIdx = col("Account Status", "Status", "AccountStatus", "Active")
        val managerIdIdx = col("Manager ID", "Manager")
        val loginIdIdx = col("Login ID", "Username", "Login")
        val passwordIdx = col("Password", "Pass", "PIN")
        val updatedAtIdx = col("Updated At", "Sync Updated At")

        val allMembers = mutableListOf<OfflineDiskCache.CachedEmployeeMember>()
        val db = com.example.access.EmployeeAccessDatabase.get(appContext)
        val empDao = db.employeeDao()

        dataRows.forEach { row ->
            if (row.all { it.isBlank() }) return@forEach
            val rawName = if (nameIdx in row.indices) row[nameIdx].trim() else ""
            if (rawName.isBlank() || rawName.lowercase(Locale.ENGLISH) in LEGACY_FAKE_EMPLOYEE_NAMES) return@forEach
            val rawId = if (idIdx in row.indices) row[idIdx].trim() else ""
            val rawMobile = if (mobileIdx in row.indices) row[mobileIdx].trim() else ""
            val rawDivision = if (divisionIdx in row.indices) row[divisionIdx].trim() else "Commercial"
            val rawTeamId = if (teamIdIdx in row.indices) row[teamIdIdx].trim() else ""
            val rawRole = if (roleIdx in row.indices) row[roleIdx].trim() else "Executive"
            val rawStatus = if (statusIdx in row.indices) row[statusIdx].trim() else "Active"
            val rawManagerId = if (managerIdIdx in row.indices) row[managerIdIdx].trim() else ""
            val rawLoginId = if (loginIdIdx in row.indices) row[loginIdIdx].trim() else ""
            val rawPassword = if (passwordIdx in row.indices) row[passwordIdx].trim() else ""
            val rawUpdatedAt = if (updatedAtIdx in row.indices) row[updatedAtIdx].trim() else ""

            val memberId = rawId.ifBlank { "EMP-${Math.abs(rawName.hashCode()).toString(36).uppercase().take(6)}" }

            val cached = OfflineDiskCache.CachedEmployeeMember(
                id = memberId,
                name = rawName,
                mobile = rawMobile,
                division = rawDivision,
                teamId = rawTeamId,
                role = rawRole,
                status = rawStatus.ifBlank { "Active" },
                managerId = rawManagerId,
                loginId = rawLoginId,
                password = rawPassword,
                updatedAt = rawUpdatedAt
            )
            allMembers.add(cached)

            // Upsert into Room EmployeeDatabase so sessionState.teamMembers includes all employees
            runCatching {
                val normalizedMob = com.example.access.PhoneNormalizer.normalizeIndianMobile(rawMobile) ?: rawMobile.takeIf { it.isNotBlank() } ?: "0000000000"
                val accessRole = when {
                    rawRole.contains("super", true) || rawRole.contains("admin", true) -> com.example.access.EmployeeAccessRole.ADMIN
                    rawRole.contains("commercial", true) -> com.example.access.EmployeeAccessRole.COMMERCIAL_MANAGER
                    rawRole.contains("residential", true) -> com.example.access.EmployeeAccessRole.RESIDENTIAL_MANAGER
                    rawRole.contains("manager", true) -> com.example.access.EmployeeAccessRole.COMMERCIAL_MANAGER
                    else -> com.example.access.EmployeeAccessRole.SALES_EXECUTIVE
                }
                val statusLower = rawStatus.lowercase(Locale.ENGLISH)
                val accessStatus = when {
                    statusLower.contains("suspend") || statusLower == "false" || statusLower == "disabled" -> com.example.access.EmployeeAccessStatus.SUSPENDED
                    statusLower.contains("pending") -> com.example.access.EmployeeAccessStatus.PENDING
                    else -> com.example.access.EmployeeAccessStatus.ACTIVE
                }
                val scopes = when {
                    rawDivision.contains("both", true) || rawDivision.contains("all", true) -> com.example.access.BusinessDivisionScope.entries.toList()
                    rawDivision.contains("resi", true) -> listOf(com.example.access.BusinessDivisionScope.AUREA_RESIDENTIAL)
                    else -> listOf(com.example.access.BusinessDivisionScope.ASCEND_COMMERCIAL)
                }
                val profile = com.example.access.EmployeeProfile(
                    employeeId = memberId,
                    employeeName = rawName,
                    loginMobile = normalizedMob,
                    loginEmail = rawLoginId.ifBlank { null },
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
        if (distinctMembers.isNotEmpty()) {
            OfflineDiskCache.saveEmployeeDirectory(appContext, distinctMembers)
            com.example.crm.IgnoredNumbersManager.syncInternalEmployeeNumbers(
                appContext,
                distinctMembers.map { it.mobile }
            )
            com.example.crm.CrmCallManager.retagInternalTeamCalls(appContext)
            val namesArr = JSONArray().apply { distinctMembers.filter { it.isActiveMember() }.forEach { put(it.name) } }
            appContext.getSharedPreferences(SYNCED_EMPLOYEES_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("active_names", namesArr.toString())
                .commit()
            _syncRevision.value++
        }
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
            put("Employee ID", employee.id)
            put("Employee Name", employee.name)
            put("Mobile", employee.mobile)
            put("Division", employee.division)
            put("Team ID", employee.teamId)
            put("Role", employee.role)
            put("Account Status", employee.status)
            put("Manager ID", employee.managerId)
            put("Login ID", employee.loginId)
            put("Password", employee.password)
            put("Updated At", updatedNow)
        }

        val request = JSONObject().apply {
            put("action", "UPSERT_RECORD")
            put("tabName", "Employees")
            put("keyHeader", "Employee ID")
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
    private fun mergeCrmSheetRowsIntoPrefs(rows: List<List<String>>): Int {
        val appContext = context ?: return 0
        if (rows.size < 2) return 0
        val headers = rows.first()
        val dataRows = rows.drop(1)

        fun colIndex(exactHeader: String, fallbackIndex: Int? = null, vararg containsAliases: String): Int {
            val exact = headers.indexOfFirst { it.trim().equals(exactHeader, ignoreCase = true) }
            if (exact >= 0) return exact
            for (alias in containsAliases) {
                val found = headers.indexOfFirst {
                    it.trim().contains(alias, ignoreCase = true) && !it.trim().contains("id", ignoreCase = true)
                }
                if (found >= 0) return found
            }
            return fallbackIndex?.takeIf { it in headers.indices } ?: -1
        }

        val leadIdIdx = headers.indexOfFirst { it.trim().equals("Lead ID", true) || it.trim().equals("id", true) }
            .let { if (it >= 0) it else if (headers.isNotEmpty()) 0 else -1 }
        val createdDateIdx = colIndex("Created Date", 1)
        // Column C (index 2) is strictly Client Name, never Column A (Lead ID)
        val clientNameIdx = headers.indexOfFirst {
            it.trim().equals("Client Name", true) || it.trim().equals("Customer Name", true)
        }.let { if (it >= 0) it else if (headers.size > 2) 2 else -1 }
        val mobileIdx = colIndex("Mobile", 3, "mobile", "phone", "contact")
        val altMobileIdx = colIndex("Alternate Mobile", 4)
        val clientTypeIdx = colIndex("Client Type", 5)
        val reqTypeIdx = colIndex("Requirement Type", 6, "transaction")
        val categoryIdx = colIndex("Property Category", 7, "division", "segment")
        val propTypeIdx = colIndex("Property Type", 8)
        val locationsIdx = colIndex("Preferred Locations", 9, "location")
        val minCarpetIdx = colIndex("Min Carpet SqFt", 10)
        val maxCarpetIdx = colIndex("Max Carpet SqFt", 11)
        val minBudgetIdx = colIndex("Min Budget INR", 12)
        val maxBudgetIdx = colIndex("Max Budget INR", 13)
        val minRentIdx = colIndex("Min Rent INR/Month", 14)
        val maxRentIdx = colIndex("Max Rent INR/Month", 15)
        val furnishingIdx = colIndex("Furnishing", 16)
        val possessionIdx = colIndex("Possession Deadline", 17, "possession")
        val leadSourceIdx = colIndex("Lead Source", 18)
        val assignedToIdx = colIndex("Assigned To", 19, "assigned")
        val stageIdx = colIndex("Lead Stage", 20, "stage", "status")
        val priorityIdx = colIndex("Priority", 21)
        val nextFollowUpIdx = colIndex("Next Follow-up Date", 22, "follow-up", "followup")
        val lastContactDateIdx = colIndex("Last Contact Date", 23)
        val lastContactModeIdx = colIndex("Last Contact Mode", 24)
        val lastContactOutcomeIdx = colIndex("Last Contact Outcome", 25)
        val matchedCodesIdx = colIndex("Matched Property Codes", 26)
        val siteVisitDateIdx = colIndex("Site Visit Date", 27)
        val siteVisitStatusIdx = colIndex("Site Visit Status", 28)
        val actionNotesIdx = colIndex("Action Notes", 29, "notes")
        val clientNotesIdx = colIndex("Client Notes", 30)
        val consentStatusIdx = colIndex("Consent Status", 31)
        val createdByIdx = colIndex("Created By", 32)
        val updatedAtIdx = colIndex("Updated At", 33)
        val legacyLeadIdIdx = colIndex("Legacy Lead ID", 34)
        val verificationStatusIdx = colIndex("Verification Status", 35)
        val duplicatePhoneIdx = colIndex("Duplicate Phone Check", 36)

        fun cell(row: List<String>, idx: Int): String =
            if (idx in row.indices) row[idx].trim() else ""

        return runCatching {
            val prefs = auditedCrmPreferences(appContext, "crm_development_v1", Context.MODE_PRIVATE)
            val curArr = runCatching { JSONArray(prefs.getString("leads", "[]") ?: "[]") }.getOrDefault(JSONArray())
            val existingIds = mutableSetOf<String>()
            for (i in 0 until curArr.length()) {
                val obj = curArr.optJSONObject(i) ?: continue
                val eid = obj.optString("id", "").trim()
                if (eid.isNotBlank()) existingIds.add(eid)
            }

            var addedCount = 0
            dataRows.forEach { row ->
                if (row.all { it.isBlank() }) return@forEach
                val leadId = cell(row, leadIdIdx).ifBlank { "CRM-${UUID.randomUUID().toString().take(8)}" }
                if (!existingIds.add(leadId)) return@forEach

                val rawClientName = cell(row, clientNameIdx)
                val cleanClientName = if (
                    rawClientName.isBlank() ||
                    rawClientName.equals(leadId, ignoreCase = true) ||
                    rawClientName.matches(Regex("^CRM[-_]?\\d+$", RegexOption.IGNORE_CASE))
                ) "" else rawClientName

                val mobile = cell(row, mobileIdx)
                val altMobile = cell(row, altMobileIdx)
                val reqType = cell(row, reqTypeIdx)
                val propType = cell(row, propTypeIdx)
                val furnishing = cell(row, furnishingIdx)
                val minCarpet = cell(row, minCarpetIdx)
                val maxCarpet = cell(row, maxCarpetIdx)
                val carpetRange = when {
                    minCarpet.isNotBlank() && maxCarpet.isNotBlank() && minCarpet != maxCarpet -> "$minCarpet–$maxCarpet"
                    maxCarpet.isNotBlank() -> maxCarpet
                    else -> minCarpet
                }
                val minBudget = cell(row, minBudgetIdx)
                val maxBudget = cell(row, maxBudgetIdx)
                val minRent = cell(row, minRentIdx)
                val maxRent = cell(row, maxRentIdx)
                val isLease = reqType.contains("Lease", true) || reqType.contains("Rent", true)
                val budgetStr = if (isLease && (minRent.isNotBlank() || maxRent.isNotBlank())) {
                    when {
                        minRent.isNotBlank() && maxRent.isNotBlank() && minRent != maxRent -> "$minRent - $maxRent"
                        maxRent.isNotBlank() -> maxRent
                        else -> minRent
                    }
                } else {
                    when {
                        minBudget.isNotBlank() && maxBudget.isNotBlank() && minBudget != maxBudget -> "$minBudget - $maxBudget"
                        maxBudget.isNotBlank() -> maxBudget
                        minBudget.isNotBlank() -> minBudget
                        maxRent.isNotBlank() -> maxRent
                        else -> minRent
                    }
                }
                val requirementSummary = listOf(reqType, propType, furnishing)
                    .filter { it.isNotBlank() && !it.equals("Not Mentioned", true) }
                    .joinToString(" ")
                    .ifBlank { cell(row, clientNotesIdx) }
                val actionNotes = cell(row, actionNotesIdx)
                val clientNotes = cell(row, clientNotesIdx)
                val cleanNotes = actionNotes.ifBlank { clientNotes }

                val obj = JSONObject().apply {
                    put("id", leadId)
                    put("name", cleanClientName)
                    put("clientName", cleanClientName)
                    put("phone", mobile)
                    put("alternatePhone", altMobile)
                    put("createdDate", cell(row, createdDateIdx))
                    put("clientType", cell(row, clientTypeIdx))
                    put("companyName", cell(row, clientTypeIdx))
                    put("requirementType", reqType)
                    put("segment", cell(row, categoryIdx).ifBlank { "Commercial" })
                    put("propertyType", propType)
                    put("requirement", requirementSummary)
                    put("location", cell(row, locationsIdx))
                    put("carpetAreaRange", carpetRange)
                    put("areaSqFt", carpetRange)
                    put("minCarpetSqFt", minCarpet)
                    put("maxCarpetSqFt", maxCarpet)
                    put("minBudgetInr", minBudget)
                    put("maxBudgetInr", maxBudget)
                    put("minRentInr", minRent)
                    put("maxRentInr", maxRent)
                    put("budget", budgetStr)
                    put("furnishing", furnishing)
                    put("possessionTimeline", cell(row, possessionIdx))
                    put("leadSource", cell(row, leadSourceIdx))
                    put("assignedTo", cell(row, assignedToIdx))
                    put("status", cell(row, stageIdx).ifBlank { "New" })
                    put("priority", cell(row, priorityIdx))
                    put("nextFollowUp", cell(row, nextFollowUpIdx))
                    put("lastContactDate", cell(row, lastContactDateIdx))
                    put("lastContactMode", cell(row, lastContactModeIdx))
                    put("lastContactOutcome", cell(row, lastContactOutcomeIdx))
                    put("matchedPropertyCodes", cell(row, matchedCodesIdx))
                    put("siteVisitDate", cell(row, siteVisitDateIdx))
                    put("siteVisitStatus", cell(row, siteVisitStatusIdx))
                    put("notes", cleanNotes)
                    put("clientNotes", clientNotes)
                    put("consentStatus", cell(row, consentStatusIdx))
                    put("createdBy", cell(row, createdByIdx))
                    put("updatedAt", cell(row, updatedAtIdx))
                    put("legacyLeadId", cell(row, legacyLeadIdIdx))
                    put("verificationStatus", cell(row, verificationStatusIdx))
                    put("duplicatePhoneCheck", cell(row, duplicatePhoneIdx))
                }
                curArr.put(obj)
                addedCount++
            }
            if (addedCount > 0) {
                val jsonStr = curArr.toString()
                OfflineDiskCache.saveCrmLeadsJson(appContext, jsonStr)
                prefs.edit().putString("leads", jsonStr).commit()
                _syncRevision.value++
            }
            addedCount
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

        val record = JSONObject().apply {
            // Exact 37 CRM Master Sheet Columns
            put("Lead ID", lead.id)
            put("Created Date", lead.createdDate.ifBlank { lead.updatedAt.take(10) })
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
            put("Furnishing", lead.furnishing)
            put("Possession Deadline", lead.possessionTimeline)
            put("Lead Source", lead.leadSource)
            put("Assigned To", lead.assignedTo)
            put("Lead Stage", lead.status)
            put("Priority", lead.priority)
            put("Next Follow-up Date", lead.nextFollowUp)
            put("Last Contact Date", lead.lastContactDate)
            put("Last Contact Mode", lead.lastContactMode)
            put("Last Contact Outcome", lead.lastContactOutcome)
            put("Matched Property Codes", lead.matchedPropertyCodes)
            put("Site Visit Date", lead.siteVisitDate)
            put("Site Visit Status", lead.siteVisitStatus)
            put("Action Notes", lead.notes)
            put("Client Notes", lead.clientNotes)
            put("Consent Status", lead.consentStatus)
            put("Created By", lead.createdBy.ifBlank { actor() })
            put("Updated At", lead.updatedAt.ifBlank { java.time.Instant.now().toString() })
            put("Legacy Lead ID", lead.legacyLeadId.ifBlank { if (lead.id.startsWith("CRM-")) lead.id else "" })
            put("Verification Status", lead.verificationStatus)
            put("Duplicate Phone Check", lead.duplicatePhoneCheck)
        }

        val request = JSONObject().apply {
            put("action", "UPSERT_CRM_LEAD")
            put("leadId", lead.id)
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

    private fun mergeDailyReportRowsIntoCache(rows: List<List<String>>): Int {
        val appContext = context ?: return 0
        if (rows.size < 2) return 0
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
        val empIdIdx = col("Employee ID", "employeeId")
        val empNameIdx = col("Employee Name", "employeeName")
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
        var merged = 0
        dataRows.forEach { row ->
            if (row.all { it.isBlank() }) return@forEach
            val reportId = row.getOrNull(idIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
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
            existing[reportId] = rec
            merged++
        }
        if (merged > 0) {
            val all = existing.values.sortedByDescending { it.updatedAt.ifBlank { it.date } }
            _dailyReports.value = all
            OfflineDiskCache.saveDailyReports(appContext, all)
            _syncRevision.value++
        }
        return merged
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

    private fun mergeTenantsSheetRowsIntoCache(rows: List<List<String>>): Int {
        val appContext = context ?: return 0
        if (rows.size < 2) return 0
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

        val idIdx = col("Tenant ID", "id", "Record ID")
        val codeIdx = col("Property Code", "Unit Code", "Code")
        val projectIdx = col("Project / Building", "Project Name", "Project", "Building")
        val unitIdx = col("Unit No", "Unit Number", "Flat No", "Unit")
        val locIdx = col("Location", "Micro Market")
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
        var merged = 0
        dataRows.forEachIndexed { idx, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            val tName = row.getOrNull(nameIdx)?.trim().orEmpty()
            val pCode = row.getOrNull(codeIdx)?.trim().orEmpty()
            if (tName.isBlank() && pCode.isBlank()) return@forEachIndexed
            val id = row.getOrNull(idIdx)?.trim()?.takeIf { it.isNotBlank() }
                ?: if (pCode.isNotBlank()) "TEN-$pCode" else "TEN-${idx + 1}"
            val rec = OfflineDiskCache.TenantCacheRecord(
                id = id,
                tenantName = tName.ifBlank { "Tenant" },
                propertyCode = pCode,
                projectName = row.getOrNull(projectIdx)?.trim().orEmpty(),
                location = row.getOrNull(locIdx)?.trim().orEmpty(),
                monthlyRent = row.getOrNull(rentIdx)?.trim().orEmpty(),
                leaseStartDate = row.getOrNull(startIdx)?.trim().orEmpty(),
                leaseExpiryDate = row.getOrNull(expiryIdx)?.trim().orEmpty(),
                lockInPeriod = row.getOrNull(lockInIdx)?.trim().orEmpty(),
                contactNumber = row.getOrNull(contactIdx)?.trim().orEmpty(),
                notes = row.getOrNull(notesIdx)?.trim().orEmpty(),
                unitNumber = row.getOrNull(unitIdx)?.trim().orEmpty(),
                division = row.getOrNull(divIdx)?.trim().orEmpty().ifBlank { "Commercial" },
                companyName = row.getOrNull(companyIdx)?.trim().orEmpty(),
                tenantEmail = row.getOrNull(emailIdx)?.trim().orEmpty(),
                depositAmount = row.getOrNull(depositIdx)?.trim().orEmpty(),
                escalationPercent = row.getOrNull(escIdx)?.trim().orEmpty().ifBlank { "5" },
                lockInEndDate = row.getOrNull(lockInEndIdx)?.trim().orEmpty(),
                status = row.getOrNull(statusIdx)?.trim().orEmpty().ifBlank { "Active" },
                moveInChecklistJson = row.getOrNull(checklistIdx)?.trim().orEmpty(),
                openIssuesJson = row.getOrNull(issuesIdx)?.trim().orEmpty(),
                ownerName = row.getOrNull(ownerNameIdx)?.trim().orEmpty(),
                ownerContact = row.getOrNull(ownerPhoneIdx)?.trim().orEmpty(),
                updatedAt = java.time.Instant.now().toString(),
                assignedTo = row.getOrNull(assignedToIdx)?.trim().orEmpty()
            )
            mergedMap[id] = rec
            merged++
        }
        if (merged > 0) {
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
        val stampedTenant = tenant.copy(updatedAt = java.time.Instant.now().toString())
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
            put("Lease Start Date", tenant.leaseStartDate)
            put("Lease Expiry Date", tenant.leaseExpiryDate)
            put("Lock-in End Date", tenant.lockInEndDate.ifBlank { tenant.lockInPeriod })
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
     * Batch 18: 9-Column `Brokers & Vendors` Sheet Parser (Cols A-I).
     */
    private fun mergeBrokersVendorsSheetRowsIntoCache(rows: List<List<String>>): Int {
        val appContext = context ?: return 0
        if (rows.size < 2) return 0
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

        val idIdx = col("Record ID", "ID", "id")
        val nameIdx = col("Name", "Contact Name")
        val phoneIdx = col("Phone", "Mobile", "Contact")
        val roleIdx = col("Role Category", "Role", "Category")
        val subRoleIdx = col("Sub-Role", "Specialty", "Sub Role")
        val firmIdx = col("Firm / Agency", "Firm", "Agency", "Company")
        val areaIdx = col("Operating Areas", "Areas", "Location")
        val notesIdx = col("Notes", "Remarks")
        val updatedIdx = col("Updated At", "Updated")

        val mergedMap = OfflineDiskCache.loadBrokersVendors(appContext).associateBy { it.id }.toMutableMap()
        var merged = 0
        dataRows.forEachIndexed { idx, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            val name = row.getOrNull(nameIdx)?.trim().orEmpty()
            val phone = row.getOrNull(phoneIdx)?.trim().orEmpty()
            if (name.isBlank() && phone.isBlank()) return@forEachIndexed
            val id = row.getOrNull(idIdx)?.trim()?.takeIf { it.isNotBlank() }
                ?: "BV-${phone.filter { it.isDigit() }.takeLast(10).ifBlank { (idx + 1).toString() }}"
            val rec = BrokerVendorRecord(
                id = id,
                name = name.ifBlank { "Contact" },
                phone = phone,
                roleCategory = row.getOrNull(roleIdx)?.trim().orEmpty().ifBlank { "Broker / CP" },
                subRole = row.getOrNull(subRoleIdx)?.trim().orEmpty().ifBlank { "Channel Partner" },
                firmName = row.getOrNull(firmIdx)?.trim().orEmpty(),
                operatingAreas = row.getOrNull(areaIdx)?.trim().orEmpty(),
                notes = row.getOrNull(notesIdx)?.trim().orEmpty(),
                updatedAt = row.getOrNull(updatedIdx)?.trim().orEmpty()
            )
            mergedMap[id] = rec
            if (!rec.roleCategory.contains("Ops", true) && !rec.roleCategory.contains("Vendor", true) && rec.phone.isNotBlank()) {
                com.example.crm.CrmCallManager.saveBrokerContact(
                    context = appContext,
                    phone = rec.phone,
                    name = rec.name,
                    firm = rec.firmName,
                    notes = rec.notes
                )
            }
            merged++
        }
        if (merged > 0) {
            val all = mergedMap.values.sortedBy { it.name.lowercase(Locale.ENGLISH) }
            _brokersVendors.value = all
            OfflineDiskCache.saveBrokersVendors(appContext, all)
            _syncRevision.value++
        }
        return merged
    }

    fun saveBrokerVendorLocalAndSync(record: BrokerVendorRecord) {
        val appContext = context ?: return
        val stampedRecord = record.copy(updatedAt = java.time.Instant.now().toString())
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
        val payload = JSONObject().apply {
            put("Record ID", record.id)
            put("Name", record.name)
            put("Phone", record.phone)
            put("Role Category", record.roleCategory)
            put("Sub-Role", record.subRole)
            put("Firm / Agency", record.firmName)
            put("Operating Areas", record.operatingAreas)
            put("Notes", record.notes)
            put("Updated At", record.updatedAt.ifBlank { java.time.Instant.now().toString() })
        }
        val request = JSONObject().apply {
            put("action", "UPSERT_BROKER_VENDOR")
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
            put("Date", report.date)
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
            put("Updated At", report.updatedAt.ifBlank { java.time.Instant.now().toString() })
        }

        val request = JSONObject().apply {
            put("action", "UPSERT_DAILY_REPORT")
            put("reportId", report.reportId)
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
    private fun mergeCallLogsSheetRowsIntoPrefs(rows: List<List<String>>): Int {
        val appContext = context ?: return 0
        if (rows.size < 2) return 0
        val headers = rows.first()
        val dataRows = rows.drop(1)

        fun colIndex(exactHeader: String, fallbackIndex: Int? = null, vararg containsAliases: String): Int {
            val exact = headers.indexOfFirst { it.trim().equals(exactHeader, ignoreCase = true) }
            if (exact >= 0) return exact
            for (alias in containsAliases) {
                val found = headers.indexOfFirst { it.trim().contains(alias, ignoreCase = true) }
                if (found >= 0) return found
            }
            return fallbackIndex?.takeIf { it in headers.indices } ?: -1
        }

        val callIdIdx = colIndex("Call ID", 0, "callid", "id")
        val timestampIdx = colIndex("Timestamp", 1, "date & time", "datetime", "timestamp", "time")
        val callerNameIdx = colIndex("Caller Name", 2, "caller", "client name", "contact name")
        val phoneIdx = colIndex("Phone Number", 3, "phone", "mobile", "number")
        val callTypeIdx = colIndex("Call Type", 4, "type", "direction")
        val durationIdx = colIndex("Duration Seconds", 5, "duration")
        val notesIdx = colIndex("Call Notes", 6, "notes", "summary", "remark")
        val agentIdx = colIndex("Assigned Agent", 7, "agent", "employee", "assigned")
        val actionIdx = colIndex("Action Required", 8, "action")
        val followUpIdx = colIndex("Next Follow-up", 9, "follow-up", "followup", "nextFollowUpDateTime")
        val statusIdx = colIndex("Lead Status", 10, "leadStatus", "outcome", "status")
        val stagingIdx = colIndex("Staging Status", 11, "staging")
        val isLinkedIdx = colIndex("Is Linked", 12, "isLinked", "linked")
        val linkedTypeIdx = colIndex("Linked Role", 13, "linkedOpportunityType", "role")
        val linkedTitleIdx = colIndex("Linked Title", 14, "linkedOpportunityTitle")
        val linkedIdIdx = colIndex("Linked ID", 15, "linkedOpportunityId")
        val waLoggedIdx = colIndex("WA Logged", -1, "waLogged", "whatsapp logged")
        val subRoleIdx = colIndex("Sub-Role", -1, "pocSubRole", "sub role")
        val handoverStatusIdx = colIndex("Handover Status", -1, "handoverStatus")
        val handoverAssignedToIdx = colIndex("Handover Assigned To", -1, "handoverAssignedTo")
        val handedOverAtIdx = colIndex("Handed Over At", -1, "handedOverAt")
        val handoverCallbackAtIdx = colIndex("Callback At", -1, "handoverCallbackAt")

        fun cell(row: List<String>, idx: Int): String =
            if (idx in row.indices) row[idx].trim() else ""

        return runCatching {
            val existing = com.example.crm.CrmCallManager.readCalls(appContext).toMutableList()
            val byId = existing.associateBy { it.id }.toMutableMap()
            var mergedCount = 0

            dataRows.forEach { row ->
                if (row.all { it.isBlank() }) return@forEach
                val phone = cell(row, phoneIdx)
                val caller = cell(row, callerNameIdx).ifBlank { "Unknown Caller" }
                if (phone.isBlank() && caller == "Unknown Caller") return@forEach

                val rawTs = cell(row, timestampIdx)
                val parsedTs = rawTs.toLongOrNull() ?: runCatching {
                    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).parse(rawTs)?.time
                }.getOrNull() ?: runCatching {
                    java.time.Instant.parse(rawTs).toEpochMilli()
                }.getOrNull() ?: System.currentTimeMillis()

                val rawCallId = cell(row, callIdIdx).ifBlank {
                    "CALL-${phone.filter { it.isDigit() }.takeLast(10)}-$parsedTs"
                }

                val durationSec = cell(row, durationIdx).filter { it.isDigit() }.toIntOrNull() ?: 0
                val callType = com.example.crm.CallType.fromString(cell(row, callTypeIdx).ifBlank { "INCOMING" })
                val notes = cell(row, notesIdx)
                val assignedAgent = cell(row, agentIdx).ifBlank { "Executive" }
                val actionReq = cell(row, actionIdx).ifBlank { "Schedule Site Visit" }
                val nextFollowUp = cell(row, followUpIdx).ifBlank { "Today, 4:00 PM" }
                val leadStatus = cell(row, statusIdx).ifBlank { "Active" }
                val stagingStatus = cell(row, stagingIdx).ifBlank { "Live" }
                val linkedRole = cell(row, linkedTypeIdx).ifBlank { null }
                val linkedTitle = cell(row, linkedTitleIdx).ifBlank { null }
                val linkedId = cell(row, linkedIdIdx).ifBlank { null }
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
                    handoverCallbackAt = handoverCallbackAt
                )
                val record = com.example.crm.CrmCallManager.tagInternalTeamCallIfNeeded(appContext, rawRecord)

                val prev = byId[rawCallId]
                if (prev == null) {
                    // Check if same phone & timestamp within 3 seconds already exists
                    val nearMatch = byId.values.firstOrNull {
                        it.phoneNumber.filter { c -> c.isDigit() }.takeLast(10) == phone.filter { c -> c.isDigit() }.takeLast(10) &&
                            kotlin.math.abs(it.timestamp - parsedTs) <= 3000L
                    }
                    if (nearMatch == null) {
                        byId[rawCallId] = record
                        mergedCount++
                    } else if (nearMatch.notes.isBlank() && record.notes.isNotBlank()) {
                        byId[nearMatch.id] = record.copy(id = nearMatch.id)
                        mergedCount++
                    }
                } else if (prev.notes.isBlank() && record.notes.isNotBlank()) {
                    byId[rawCallId] = record
                    mergedCount++
                }
            }

            val finalSorted = byId.values.sortedByDescending { it.timestamp }
            if (mergedCount > 0) {
                com.example.crm.CrmCallManager.saveCalls(appContext, finalSorted)
                _syncRevision.value++
            }
            com.example.crm.TeamHandoverStore.reconcileWithCallLogs(appContext, finalSorted)
            mergedCount
        }.getOrDefault(0)
    }

    /**
     * Pushes a `CrmCallRecord` to the `Call Logs` tab on the Master Sheet (`UPSERT_CALL_LOG`).
     */
    suspend fun pushCallLogToMasterSheet(call: com.example.crm.CrmCallRecord): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = _sheetsScriptUrl.value.trim().ifBlank { DEFAULT_MASTER_SHEET_URL }
        if (!endpoint.startsWith("https://") || !endpoint.contains("script.google.com", ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException("Configure a valid Google Apps Script Web App URL to sync Call Logs."))
        }

        val formattedDate = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(call.timestamp))
        val updatedNow = java.time.Instant.now().toString()

        val record = JSONObject().apply {
            put("Call ID", call.id)
            put("Date & Time", formattedDate)
            put("Timestamp", call.timestamp)
            put("Caller Name", call.callerName)
            put("Phone Number", call.phoneNumber)
            put("Call Type", call.callType.name)
            put("Duration Seconds", call.durationSeconds)
            put("Call Notes", call.notes)
            put("Assigned Agent", call.assignedAgent)
            put("Action Required", call.actionRequired)
            put("Next Follow-up", call.nextFollowUpDateTime)
            put("Lead Status", call.leadStatus)
            put("Staging Status", call.stagingStatus)
            put("Is Linked", call.isLinked)
            put("Linked Role", call.linkedOpportunityType.orEmpty())
            put("Linked Title", call.linkedOpportunityTitle.orEmpty())
            put("Linked ID", call.linkedOpportunityId.orEmpty())
            put("WA Logged", call.waLogged)
            put("Sub-Role", call.pocSubRole)
            put("Handover Status", call.handoverStatus)
            put("Handover Assigned To", call.handoverAssignedTo)
            put("Handed Over At", call.handedOverAt)
            put("Callback At", call.handoverCallbackAt)
            put("Updated At", updatedNow)

            // Also include camelCase keys for flexible Apps Script handlers
            put("id", call.id)
            put("callerName", call.callerName)
            put("phoneNumber", call.phoneNumber)
            put("callType", call.callType.name)
            put("timestamp", call.timestamp)
            put("durationSeconds", call.durationSeconds)
            put("notes", call.notes)
            put("assignedAgent", call.assignedAgent)
            put("actionRequired", call.actionRequired)
            put("nextFollowUpDateTime", call.nextFollowUpDateTime)
            put("leadStatus", call.leadStatus)
            put("stagingStatus", call.stagingStatus)
            put("isLinked", call.isLinked)
            put("linkedOpportunityType", call.linkedOpportunityType.orEmpty())
            put("linkedOpportunityTitle", call.linkedOpportunityTitle.orEmpty())
            put("linkedOpportunityId", call.linkedOpportunityId.orEmpty())
            put("waLogged", call.waLogged)
            put("pocSubRole", call.pocSubRole)
            put("handoverStatus", call.handoverStatus)
            put("handoverAssignedTo", call.handoverAssignedTo)
            put("handedOverAt", call.handedOverAt)
            put("handoverCallbackAt", call.handoverCallbackAt)
        }

        val request = JSONObject().apply {
            put("action", "UPSERT_CALL_LOG")
            put("callId", call.id)
            put("record", record)
        }

        val response = postAppsScriptJsonRequest(endpoint, request).getOrElse { err ->
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

        logSyncEvent(
            direction = "CALL_LOG_SYNC",
            status = "SUCCESS",
            recordCount = 1,
            details = "Pushed Call Log ${call.id} (${call.callerName} • ${call.phoneNumber})",
            triggeredBy = actor()
        )
        Result.success(Unit)
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


