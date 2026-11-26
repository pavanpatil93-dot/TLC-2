package com.example.data

import android.content.Context
import com.example.access.EmployeeAccessRole
import com.example.access.EmployeeProfile
import com.example.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class MasterSyncMode(val label: String) {
    READ_ONLY("Refresh from Master Sheet"),
    AUTHENTICATED_TWO_WAY("Bidirectional Sync (Verified)")
}

enum class MasterSyncState {
    IDLE,
    RUNNING,
    SUCCESS,
    PARTIAL,
    OFFLINE,
    AUTH_REQUIRED,
    FAILED
}

enum class TabSyncStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED_UNAUTHORIZED
}

data class TabSyncResult(
    val worksheetName: String,
    val status: TabSyncStatus = TabSyncStatus.PENDING,
    val addedCount: Int = 0,
    val updatedCount: Int = 0,
    val skippedCount: Int = 0,
    val reviewCount: Int = 0,
    val failedCount: Int = 0,
    val completedAt: String = "",
    val errorMessage: String? = null
)

data class MasterSyncReport(
    val syncRunId: String = "",
    val state: MasterSyncState = MasterSyncState.IDLE,
    val syncMode: MasterSyncMode = MasterSyncMode.AUTHENTICATED_TWO_WAY,
    val spreadsheetId: String = OneTapSyncCoordinator.FIXED_SPREADSHEET_ID,
    val activeEmployeeId: String = "",
    val startedAt: Long = 0L,
    val completedAt: Long = 0L,
    val totalAdded: Int = 0,
    val totalUpdated: Int = 0,
    val totalSkipped: Int = 0,
    val totalReview: Int = 0,
    val totalFailed: Int = 0,
    val tabResults: List<TabSyncResult> = emptyList(),
    val summaryMessage: String = "",
    val writeCapabilityVerified: Boolean = true,
    val schemaMappingVersion: Int = 2
)

/**
 * One-Tap Master Sheet Sync Coordinator.
 * Routes ALL sync requests exclusively through the configured TlcWebApp.gs Apps Script Web App
 * (`/exec`, `GET ?tabName=<encodedTabName>`) with OkHttp 302 redirect handling — never direct
 * `docs.google.com/spreadsheets/...` URLs (which return HTTP 401 on private sheets).
 *
 * Syncs exact tabs in TLC Master sheet (`1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0`):
 * - Commercial Lease Inventory, Commercial Resale Inventory,
 *   Residential Under Construction, Residential Resale, Residential Rentals
 * - Commercial CRM, Residential CRM
 * - Commercial Projects, Residential Projects
 * - Commercial Brokers, Residential Brokers, Vendors
 * - Team & Users, Call Logs, Daily Reports, Approvals & Audit Log
 */
class OneTapSyncCoordinator(
    private val context: Context,
    private val repository: SheetsRepository
) {
    companion object {
        const val FIXED_SPREADSHEET_ID = TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID
        val REQUIRED_WORKSHEETS: List<String> =
            TlcSchemaV2Api.CANONICAL_SYNC_TABS + TlcSchemaV2Api.OPERATIONAL_RECONCILE_TABS
        private const val SYNC_PREFS_NAME = "one_tap_sync_state_v1"
        private val runMutex = Mutex()
    }

    private val coordinatorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _syncReport = MutableStateFlow(loadPersistedReport())
    val syncReport: StateFlow<MasterSyncReport> = _syncReport.asStateFlow()

    private val _isResultSheetVisible = MutableStateFlow(false)
    val isResultSheetVisible: StateFlow<Boolean> = _isResultSheetVisible.asStateFlow()

    fun showResultSheet() { _isResultSheetVisible.value = true }
    fun dismissResultSheet() { _isResultSheetVisible.value = false }

    private fun loadPersistedReport(): MasterSyncReport {
        val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        val lastSuccess = prefs.getLong("last_successful_sync_at", 0L)
        val lastRunId = prefs.getString("last_run_id", "") ?: ""
        val lastMsg = prefs.getString("last_summary_message", "Never synced") ?: "Never synced"
        return MasterSyncReport(
            syncRunId = lastRunId,
            state = if (lastSuccess > 0L) MasterSyncState.SUCCESS else MasterSyncState.IDLE,
            completedAt = lastSuccess,
            summaryMessage = lastMsg
        )
    }

    private fun persistReport(report: MasterSyncReport) {
        val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("last_run_id", report.syncRunId)
            .putLong(
                "last_successful_sync_at",
                if (report.state == MasterSyncState.SUCCESS) report.completedAt else prefs.getLong("last_successful_sync_at", 0L)
            )
            .putString("last_summary_message", report.summaryMessage)
            .apply()
    }

    /**
     * Start One-Tap Two-Way Refresh (Push pending + Pull exact TLC Master sheet tabs via /exec).
     * Repeated taps attach to the current running job without duplicating.
     */
    fun triggerOneTapSync(employee: EmployeeProfile?, showSheetOnFinish: Boolean = true): StateFlow<MasterSyncReport> {
        if (_syncReport.value.state == MasterSyncState.RUNNING) {
            return syncReport
        }

        coordinatorScope.launch {
            executeSync(employee, showSheetOnFinish)
        }
        return syncReport
    }

    private suspend fun executeSync(employee: EmployeeProfile?, showSheetOnFinish: Boolean) = withContext(Dispatchers.IO) {
        runMutex.withLock {
            if (_syncReport.value.state == MasterSyncState.RUNNING) return@withLock

            val runId = "sync_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val nowMs = System.currentTimeMillis()
            val employeeId = employee?.employeeId ?: "ACTIVE_USER"

            val initialTabs = REQUIRED_WORKSHEETS.map { name ->
                val authorized = isWorksheetAuthorized(name, employee)
                TabSyncResult(
                    worksheetName = name,
                    status = if (authorized) TabSyncStatus.PENDING else TabSyncStatus.SKIPPED_UNAUTHORIZED
                )
            }

            _syncReport.value = MasterSyncReport(
                syncRunId = runId,
                state = MasterSyncState.RUNNING,
                syncMode = MasterSyncMode.AUTHENTICATED_TWO_WAY,
                activeEmployeeId = employeeId,
                startedAt = nowMs,
                tabResults = initialTabs,
                summaryMessage = "Syncing with TLC Master sheet via Apps Script (/exec)..."
            )
            repository.refreshTopSyncChipState(syncing = true)
            repository.setSyncBannerRetrying(true)

            val connectivity = checkNetworkConnectivity()
            if (!connectivity) {
                val offlineReport = _syncReport.value.copy(
                    state = MasterSyncState.OFFLINE,
                    completedAt = System.currentTimeMillis(),
                    summaryMessage = "Device is offline. Local data and pending changes preserved."
                )
                _syncReport.value = offlineReport
                persistReport(offlineReport)
                repository.refreshTopSyncChipState(syncing = false)
                repository.reportSyncError(
                    title = "Offline — Sync Pending",
                    rawError = "Device is offline. Local data and pending changes are preserved.",
                    failedAction = "Master Sheet Refresh"
                )
                if (showSheetOnFinish) _isResultSheetVisible.value = true
                return@withLock
            }

            // 1. Push pending multi-device local changes (Properties, CRM Leads, Call Logs) first
            runCatching { repository.pushPendingMultiDeviceChanges() }
            runCatching { repository.retryPendingWrites() }

            val endpoint = TlcSchemaV2Api.resolveEndpointUrl(repository.sheetsScriptUrl.value)
            val apiRole = UserSettingsManager.readCurrentApiRole(context)

            var totalAdded = 0
            var totalUpdated = 0
            var totalSkipped = 0
            var totalFailed = 0
            var anyTabFailed = false
            var anyTabSucceeded = false
            var firstAuthError: String? = null
            var firstRealError: String? = null
            val perInvTabCounts = linkedMapOf<String, Int>()
            var totalRemovedStale = 0
            var totalPreservedPending = 0
            var discoveredSpreadsheetName: String? = null
            var discoveredSpreadsheetId: String? = null

            val updatedTabs = _syncReport.value.tabResults.toMutableList()

            for (i in updatedTabs.indices) {
                val tab = updatedTabs[i]
                if (tab.status == TabSyncStatus.SKIPPED_UNAUTHORIZED) continue

                updatedTabs[i] = tab.copy(status = TabSyncStatus.RUNNING)
                _syncReport.update { it.copy(tabResults = updatedTabs.toList()) }

                try {
                    val outcome = fetchAndMergeWorksheetViaAppsScript(
                        endpoint = endpoint,
                        worksheetName = tab.worksheetName,
                        apiRole = apiRole
                    )
                    updatedTabs[i] = outcome.tabResult
                    if (!outcome.spreadsheetName.isNullOrBlank() && discoveredSpreadsheetName == null) {
                        discoveredSpreadsheetName = outcome.spreadsheetName
                    }
                    if (!outcome.spreadsheetId.isNullOrBlank() && discoveredSpreadsheetId == null) {
                        discoveredSpreadsheetId = outcome.spreadsheetId
                    }
                    if (tab.worksheetName in TlcInventoryTabV2.ALL_FIVE_TABS && outcome.tabResult.status == TabSyncStatus.SUCCESS) {
                        perInvTabCounts[tab.worksheetName] = outcome.sheetRowCount
                    }
                    totalAdded += outcome.tabResult.addedCount
                    totalUpdated += outcome.tabResult.updatedCount
                    totalSkipped += outcome.tabResult.skippedCount
                    totalFailed += outcome.tabResult.failedCount
                    totalRemovedStale += outcome.removedStaleCount
                    totalPreservedPending += outcome.preservedPendingCount
                    if (outcome.tabResult.status == TabSyncStatus.SUCCESS) anyTabSucceeded = true
                    if (outcome.tabResult.status == TabSyncStatus.FAILED) {
                        anyTabFailed = true
                        val err = outcome.tabResult.errorMessage
                        if (!err.isNullOrBlank()) {
                            if (firstRealError == null) firstRealError = err
                            if (firstAuthError == null && (err.contains("401") || err.contains("403") || err.contains("Apps Script deployment", ignoreCase = true))) {
                                firstAuthError = err
                            }
                        }
                    }
                } catch (e: Exception) {
                    val err = e.localizedMessage ?: "Tab refresh failed"
                    updatedTabs[i] = tab.copy(
                        status = TabSyncStatus.FAILED,
                        errorMessage = err
                    )
                    if (firstRealError == null) firstRealError = err
                    anyTabFailed = true
                }
                _syncReport.update { it.copy(tabResults = updatedTabs.toList()) }
            }

            val finalState = when {
                !anyTabSucceeded && firstAuthError != null -> MasterSyncState.AUTH_REQUIRED
                !anyTabSucceeded && anyTabFailed -> MasterSyncState.FAILED
                anyTabSucceeded && anyTabFailed -> MasterSyncState.PARTIAL
                anyTabSucceeded -> MasterSyncState.SUCCESS
                else -> MasterSyncState.IDLE
            }

            val nowStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
            val summary = when (finalState) {
                MasterSyncState.SUCCESS ->
                    "TLC Master sheet refreshed at $nowStr ($totalAdded added, $totalUpdated updated, $totalRemovedStale stale cleared)."
                MasterSyncState.PARTIAL ->
                    "Partial refresh at $nowStr ($totalAdded added, $totalUpdated updated). ${firstAuthError ?: firstRealError ?: "Some tabs failed."}"
                MasterSyncState.AUTH_REQUIRED ->
                    firstAuthError ?: TlcSchemaV2Api.AUTH_401_403_HINT
                MasterSyncState.FAILED ->
                    firstRealError ?: "Sync failed. Local data preserved safely."
                else -> "Refresh complete."
            }

            val completedAtMs = System.currentTimeMillis()
            val finalReport = _syncReport.value.copy(
                state = finalState,
                completedAt = completedAtMs,
                totalAdded = totalAdded,
                totalUpdated = totalUpdated,
                totalSkipped = totalSkipped,
                totalFailed = totalFailed,
                summaryMessage = summary
            )
            _syncReport.value = finalReport
            persistReport(finalReport)

            if (finalState == MasterSyncState.SUCCESS || finalState == MasterSyncState.PARTIAL) {
                repository.updateSyncDiagnostics(
                    configuredScriptUrl = endpoint,
                    activeSpreadsheetName = discoveredSpreadsheetName ?: TlcSchemaV2Api.EXPECTED_SPREADSHEET_NAME,
                    activeSpreadsheetId = discoveredSpreadsheetId ?: FIXED_SPREADSHEET_ID,
                    tabRowCounts = perInvTabCounts.takeIf { it.isNotEmpty() },
                    lastRemovedStaleCount = totalRemovedStale,
                    lastPreservedPendingCount = totalPreservedPending,
                    lastSyncedAt = nowStr,
                    lastStatusSummary = summary
                )
                repository.reloadFromDisk()
                repository.markSuccessfulSyncNow(completedAtMs)
            }
            when (finalState) {
                MasterSyncState.SUCCESS -> repository.clearSyncError()
                MasterSyncState.AUTH_REQUIRED -> repository.reportSyncError(
                    title = "Apps Script Sync Blocked (HTTP 401)",
                    rawError = firstAuthError ?: TlcSchemaV2Api.AUTH_401_403_HINT,
                    failedAction = "Master Sheet Refresh"
                )
                MasterSyncState.FAILED -> repository.reportSyncError(
                    title = "Master Sheet Sync Failed",
                    rawError = firstRealError ?: summary,
                    failedAction = "Master Sheet Refresh"
                )
                MasterSyncState.PARTIAL -> {
                    if (firstAuthError != null) {
                        repository.reportSyncError(
                            title = "Apps Script Sync Partially Blocked",
                            rawError = firstAuthError,
                            failedAction = "Master Sheet Refresh"
                        )
                    } else if (totalFailed > 0 && firstRealError != null) {
                        repository.reportSyncError(
                            title = "Some Tabs Failed to Sync",
                            rawError = firstRealError,
                            failedAction = "Master Sheet Refresh"
                        )
                    } else {
                        repository.clearSyncError()
                    }
                }
                else -> repository.setSyncBannerRetrying(false)
            }
            repository.refreshTopSyncChipState(
                syncing = false,
                lastError = if (finalState == MasterSyncState.FAILED || finalState == MasterSyncState.AUTH_REQUIRED) summary else null
            )
            if (showSheetOnFinish) _isResultSheetVisible.value = true
        }
    }

    private fun isWorksheetAuthorized(worksheetName: String, employee: EmployeeProfile?): Boolean {
        if (worksheetName !in REQUIRED_WORKSHEETS) return false
        if (employee == null) {
            // Logged-in UserSettingsManager profile may exist without EmployeeSessionManager profile
            val userRole = UserSettingsManager(context).userProfile.value.role
            return userRole != UserRole.READ_ONLY && userRole != UserRole.BROKER && userRole != UserRole.OWNER
        }
        if (!employee.hasConfidentialAccess()) return false
        return employee.role in setOf(
            EmployeeAccessRole.ADMIN,
            EmployeeAccessRole.COMMERCIAL_MANAGER,
            EmployeeAccessRole.RESIDENTIAL_MANAGER,
            EmployeeAccessRole.SALES_EXECUTIVE,
            EmployeeAccessRole.INVENTORY_MANAGER
        )
    }

    private fun checkNetworkConnectivity(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private data class WorksheetFetchOutcome(
        val tabResult: TabSyncResult,
        val sheetRowCount: Int = 0,
        val removedStaleCount: Int = 0,
        val preservedPendingCount: Int = 0,
        val spreadsheetName: String? = null,
        val spreadsheetId: String? = null
    )

    private suspend fun fetchAndMergeWorksheetViaAppsScript(
        endpoint: String,
        worksheetName: String,
        apiRole: String
    ): WorksheetFetchOutcome {
        val tabRes = TlcSchemaV2Api.fetchTabRaw(
            baseUrl = endpoint,
            tabName = worksheetName,
            role = apiRole
        )

        val payload = tabRes.getOrElse { err ->
            val rawErr = err.message?.takeIf { it.isNotBlank() } ?: "Apps Script request failed"
            val friendlyErr = if (rawErr.contains("401") || rawErr.contains("403")) {
                if (rawErr.contains("Apps Script deployment", ignoreCase = true)) rawErr
                else "$rawErr — ${TlcSchemaV2Api.AUTH_401_403_HINT}"
            } else {
                rawErr
            }
            return WorksheetFetchOutcome(
                tabResult = TabSyncResult(
                    worksheetName = worksheetName,
                    status = TabSyncStatus.FAILED,
                    failedCount = 1,
                    errorMessage = friendlyErr
                )
            )
        }

        if (!payload.isStatusOk) {
            val serverErr = payload.rawJson.optString("message", payload.rawJson.optString("error", ""))
                .ifBlank { "Apps Script returned non-OK status for \"$worksheetName\"." }
            return WorksheetFetchOutcome(
                tabResult = TabSyncResult(
                    worksheetName = worksheetName,
                    status = TabSyncStatus.FAILED,
                    failedCount = 1,
                    errorMessage = serverErr
                )
            )
        }

        val primaryArr = TlcSchemaV2Api.extractPrimaryArray(
            payload.rawJson,
            worksheetName,
            "data",
            "rows",
            "records",
            "leads",
            "team",
            "calls"
        )
        val rows = if (primaryArr != null && primaryArr.length() > 0) {
            convertJsonArrayToRows(primaryArr)
        } else {
            emptyList()
        }

        val mergeOutcome = repository.mergeTabRowsFromAppsScript(
            tabName = worksheetName,
            rows = rows,
            sheetStatusOk = true
        )

        return WorksheetFetchOutcome(
            tabResult = TabSyncResult(
                worksheetName = worksheetName,
                status = TabSyncStatus.SUCCESS,
                addedCount = mergeOutcome.addedCount,
                updatedCount = mergeOutcome.updatedCount,
                skippedCount = mergeOutcome.preservedPendingCount,
                completedAt = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())
            ),
            sheetRowCount = mergeOutcome.totalProcessed,
            removedStaleCount = mergeOutcome.removedCount,
            preservedPendingCount = mergeOutcome.preservedPendingCount,
            spreadsheetName = payload.spreadsheetName,
            spreadsheetId = payload.spreadsheetId
        )
    }

    private fun convertJsonArrayToRows(array: JSONArray): List<List<String>> {
        if (array.length() == 0) return emptyList()
        val first = array.opt(0)
        if (first is JSONArray) {
            val grid = mutableListOf<List<String>>()
            for (i in 0 until array.length()) {
                val rowArr = array.optJSONArray(i) ?: continue
                val row = mutableListOf<String>()
                for (j in 0 until rowArr.length()) {
                    val v = rowArr.opt(j)
                    row.add(if (v == null || v == org.json.JSONObject.NULL) "" else v.toString().trim())
                }
                grid.add(row)
            }
            return grid
        }

        val headerOrder = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val keys = obj.keys()
            while (keys.hasNext()) {
                headerOrder.add(keys.next())
            }
        }
        val headers = headerOrder.toList()
        if (headers.isEmpty()) return emptyList()
        val grid = mutableListOf<List<String>>()
        grid.add(headers)
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val row = headers.map { key ->
                val v = obj.opt(key)
                if (v == null || v == org.json.JSONObject.NULL) "" else v.toString().trim()
            }
            grid.add(row)
        }
        return grid
    }
}
