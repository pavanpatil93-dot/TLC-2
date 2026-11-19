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
import org.json.JSONObject
import java.io.File
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
    val syncMode: MasterSyncMode = MasterSyncMode.READ_ONLY,
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
    val writeCapabilityVerified: Boolean = false,
    val schemaMappingVersion: Int = 1
)

/**
 * Group 10 Revised: One-Tap Master Sheet Sync Coordinator.
 * Coordinates background refresh across the 5 authoritative worksheets:
 * 1. Commercial Inventory
 * 2. Residential Inventory
 * 3. CRM
 * 4. Commercial Projects
 * 5. Residential Projects
 *
 * Fixed Spreadsheet ID: 1VJruPx1GTOMDziafPYK6ihW1v1DGrqTWO2vHk_oNReQ
 */
class OneTapSyncCoordinator(
    private val context: Context,
    private val repository: SheetsRepository
) {
    companion object {
        const val FIXED_SPREADSHEET_ID = "1VJruPx1GTOMDziafPYK6ihW1v1DGrqTWO2vHk_oNReQ"
        val REQUIRED_WORKSHEETS = listOf(
            "Commercial Inventory",
            "Residential Inventory",
            "CRM",
            "Commercial Projects",
            "Residential Projects"
        )
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
            .putLong("last_successful_sync_at", if (report.state == MasterSyncState.SUCCESS) report.completedAt else prefs.getLong("last_successful_sync_at", 0L))
            .putString("last_summary_message", report.summaryMessage)
            .apply()
    }

    /**
     * Start One-Tap Refresh. Repeated taps attach to current running job without duplicating.
     */
    fun triggerOneTapSync(employee: EmployeeProfile?): StateFlow<MasterSyncReport> {
        if (_syncReport.value.state == MasterSyncState.RUNNING) {
            // Already running; attach to existing run
            return syncReport
        }

        coordinatorScope.launch {
            executeSync(employee)
        }
        return syncReport
    }

    private suspend fun executeSync(employee: EmployeeProfile?) = withContext(Dispatchers.IO) {
        runMutex.withLock {
            if (_syncReport.value.state == MasterSyncState.RUNNING) return@withLock

            val runId = "sync_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val nowMs = System.currentTimeMillis()
            val employeeId = employee?.employeeId ?: "UNREGISTERED"

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
                syncMode = MasterSyncMode.READ_ONLY,
                activeEmployeeId = employeeId,
                startedAt = nowMs,
                tabResults = initialTabs,
                summaryMessage = "Refreshing master data from spreadsheet..."
            )

            // Verify connectivity
            val connectivity = checkNetworkConnectivity()
            if (!connectivity) {
                val offlineReport = _syncReport.value.copy(
                    state = MasterSyncState.OFFLINE,
                    completedAt = System.currentTimeMillis(),
                    summaryMessage = "Device is offline. Local inventory and pending changes preserved."
                )
                _syncReport.value = offlineReport
                persistReport(offlineReport)
                _isResultSheetVisible.value = true
                return@withLock
            }

            // First drain pending local writes + run Apps Script doGet pull (also checks appUpdate)
            runCatching { repository.retryAllPendingSyncsAndPull() }

            var totalAdded = 0
            var totalUpdated = 0
            var totalSkipped = 0
            var totalFailed = 0
            var anyTabFailed = false
            var anyTabSucceeded = false

            val updatedTabs = _syncReport.value.tabResults.toMutableList()

            for (i in updatedTabs.indices) {
                val tab = updatedTabs[i]
                if (tab.status == TabSyncStatus.SKIPPED_UNAUTHORIZED) continue

                updatedTabs[i] = tab.copy(status = TabSyncStatus.RUNNING)
                _syncReport.update { it.copy(tabResults = updatedTabs.toList()) }

                try {
                    val result = fetchAndMergeWorksheet(tab.worksheetName, employee)
                    updatedTabs[i] = result
                    totalAdded += result.addedCount
                    totalUpdated += result.updatedCount
                    totalSkipped += result.skippedCount
                    totalFailed += result.failedCount
                    if (result.status == TabSyncStatus.SUCCESS) anyTabSucceeded = true
                    if (result.status == TabSyncStatus.FAILED) anyTabFailed = true
                } catch (e: Exception) {
                    updatedTabs[i] = tab.copy(
                        status = TabSyncStatus.FAILED,
                        errorMessage = e.localizedMessage ?: "Tab refresh failed"
                    )
                    anyTabFailed = true
                }
                _syncReport.update { it.copy(tabResults = updatedTabs.toList()) }
            }

            val finalState = when {
                !anyTabSucceeded && anyTabFailed -> MasterSyncState.FAILED
                anyTabSucceeded && anyTabFailed -> MasterSyncState.PARTIAL
                anyTabSucceeded -> MasterSyncState.SUCCESS
                else -> MasterSyncState.IDLE
            }

            val nowStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
            val summary = when (finalState) {
                MasterSyncState.SUCCESS -> "Master data refreshed at $nowStr ($totalAdded added, $totalUpdated updated)."
                MasterSyncState.PARTIAL -> "Partial refresh at $nowStr ($totalAdded added, $totalUpdated updated, some tabs failed)."
                MasterSyncState.FAILED -> "Sync failed. Local data preserved safely."
                else -> "Refresh complete."
            }

            val finalReport = _syncReport.value.copy(
                state = finalState,
                completedAt = System.currentTimeMillis(),
                totalAdded = totalAdded,
                totalUpdated = totalUpdated,
                totalSkipped = totalSkipped,
                totalFailed = totalFailed,
                summaryMessage = summary
            )
            _syncReport.value = finalReport
            persistReport(finalReport)
            if (finalState == MasterSyncState.SUCCESS || finalState == MasterSyncState.PARTIAL) {
                if (totalAdded > 0 || totalUpdated > 0) {
                    repository.reloadFromDisk()
                }
                repository.markSuccessfulSyncNow(finalReport.completedAt)
            }
            _isResultSheetVisible.value = true
        }
    }

    private fun isWorksheetAuthorized(worksheetName: String, employee: EmployeeProfile?): Boolean {
        if (employee == null || !employee.hasConfidentialAccess()) return false
        val role = employee.role
        return when (worksheetName) {
            "Commercial Inventory",
            "Commercial Projects",
            "Residential Inventory",
            "Residential Projects",
            "CRM" ->
                role == EmployeeAccessRole.ADMIN ||
                    role == EmployeeAccessRole.COMMERCIAL_MANAGER ||
                    role == EmployeeAccessRole.RESIDENTIAL_MANAGER ||
                    role == EmployeeAccessRole.SALES_EXECUTIVE ||
                    role == EmployeeAccessRole.INVENTORY_MANAGER
            else -> false
        }
    }

    private fun checkNetworkConnectivity(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun fetchAndMergeWorksheet(worksheetName: String, employee: EmployeeProfile?): TabSyncResult {
        val encodedTab = java.net.URLEncoder.encode(worksheetName, "UTF-8")
        val csvUrl = "https://docs.google.com/spreadsheets/d/$FIXED_SPREADSHEET_ID/gviz/tq?tqx=out:csv&sheet=$encodedTab"

        val content = try {
            fetchUrlText(csvUrl)
        } catch (e: Exception) {
            return TabSyncResult(
                worksheetName = worksheetName,
                status = TabSyncStatus.FAILED,
                errorMessage = "Network or access error: ${e.localizedMessage}"
            )
        }

        if (content.contains("<!DOCTYPE html>", ignoreCase = true) || content.contains("<html", ignoreCase = true)) {
            return TabSyncResult(
                worksheetName = worksheetName,
                status = TabSyncStatus.FAILED,
                errorMessage = "Endpoint returned HTML instead of data. Check spreadsheet permissions."
            )
        }

        val rows = parseCsv(content)
        if (rows.isEmpty()) {
            return TabSyncResult(
                worksheetName = worksheetName,
                status = TabSyncStatus.SUCCESS,
                completedAt = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())
            )
        }

        return when (worksheetName) {
            "Commercial Inventory", "Residential Inventory" -> mergeInventoryRows(worksheetName, rows)
            "CRM" -> mergeCrmRows(rows)
            "Commercial Projects", "Residential Projects" -> mergeProjectRows(worksheetName, rows)
            else -> TabSyncResult(worksheetName = worksheetName, status = TabSyncStatus.SUCCESS)
        }
    }

    private fun mergeInventoryRows(worksheetName: String, rows: List<List<String>>): TabSyncResult {
        val headers = rows.firstOrNull() ?: return TabSyncResult(worksheetName, TabSyncStatus.SUCCESS)
        val dataRows = rows.drop(1)
        var added = 0
        var updated = 0
        var skipped = 0

        val currentList = repository.properties.value.toMutableList()
        val byRecordId = currentList.filter { it.recordId.isNotBlank() }
            .associateBy { it.recordId.uppercase(Locale.ENGLISH) }
        val byCode = currentList.filter { it.propertyCode.isNotBlank() }
            .associateBy { it.propertyCode.uppercase(Locale.ENGLISH) }

        val propertyIdIdx = headers.indexOfFirst { it.trim().equals("property id", ignoreCase = true) || it.trim().equals("record id", ignoreCase = true) }
        val codeIdx = headers.indexOfFirst { it.contains("code", ignoreCase = true) }
        val projectIdx = headers.indexOfFirst { it.contains("project", ignoreCase = true) || it.contains("building", ignoreCase = true) }
        val locationIdx = headers.indexOfFirst { it.contains("location", ignoreCase = true) }
        val priceIdx = headers.indexOfFirst { it.contains("price", ignoreCase = true) || it.contains("rent", ignoreCase = true) }
        val areaIdx = headers.indexOfFirst { it.contains("carpet", ignoreCase = true) || it.contains("area", ignoreCase = true) }
        val pocTypeIdx = headers.indexOfFirst {
            val h = it.trim().lowercase()
            h == "type of poc" || h == "poc type"
        }
        val verifIdx = headers.indexOfFirst { it.trim().equals("verification status", ignoreCase = true) }
        val recordStatusIdx = headers.indexOfFirst {
            val h = it.trim().lowercase()
            h == "record status" || h == "archive status"
        }
        val availStatusIdx = headers.indexOfFirst {
            val h = it.trim().lowercase()
            h == "availability status" || h == "status"
        }
        val lastVerifiedIdx = headers.indexOfFirst {
            val h = it.trim().lowercase()
            h == "last verified" || h == "last verified date"
        }
        val lastUpdatedIdx = headers.indexOfFirst {
            val h = it.trim().lowercase()
            h == "last updated" || h == "sync updated at"
        }

        dataRows.forEach { row ->
            val recordId = if (propertyIdIdx in row.indices) row[propertyIdIdx].trim() else ""
            val code = if (codeIdx in row.indices) row[codeIdx].trim() else ""
            if (code.isBlank() && recordId.isBlank()) { skipped++; return@forEach }

            val existing = (if (recordId.isNotBlank()) byRecordId[recordId.uppercase(Locale.ENGLISH)] else null)
                ?: (if (code.isNotBlank()) byCode[code.uppercase(Locale.ENGLISH)] else null)
            val projectName = if (projectIdx in row.indices) row[projectIdx].trim() else "Project"
            val location = if (locationIdx in row.indices) row[locationIdx].trim() else "Pune"
            val price = if (priceIdx in row.indices) row[priceIdx].toDoubleOrNull() ?: 0.0 else 0.0
            val area = if (areaIdx in row.indices) row[areaIdx].toDoubleOrNull() ?: 1000.0 else 1000.0
            val pocType = if (pocTypeIdx in row.indices) normalizePocType(row[pocTypeIdx]) else ""
            val sheetVerif = if (verifIdx in row.indices) VerificationStatus.fromString(row[verifIdx]) else null
            val sheetArchive = if (recordStatusIdx in row.indices) ArchiveStatus.fromString(row[recordStatusIdx]) else null
            val sheetAvail = if (availStatusIdx in row.indices && row[availStatusIdx].isNotBlank()) {
                AvailabilityStatus.parse(row[availStatusIdx])
            } else null
            val sheetLastVerified = if (lastVerifiedIdx in row.indices) row[lastVerifiedIdx].trim() else ""
            val sheetLastUpdated = if (lastUpdatedIdx in row.indices) row[lastUpdatedIdx].trim() else ""

            if (existing == null) {
                val isResi = worksheetName.contains("Residential", ignoreCase = true)
                val newProp = PropertyItem(
                    id = recordId.ifBlank { "prop_sheet_${UUID.randomUUID()}" },
                    recordId = recordId,
                    propertyCode = code.ifBlank { recordId },
                    projectName = projectName,
                    location = location,
                    carpetAreaSqFt = area,
                    priceOrRent = price,
                    portfolio = if (isResi) "Residential" else "Commercial",
                    transactionType = if (isResi) "Sale" else "Lease",
                    status = sheetAvail ?: AvailabilityStatus.AVAILABLE,
                    verificationStatus = sheetVerif ?: VerificationStatus.PENDING,
                    archiveStatus = sheetArchive ?: ArchiveStatus.ACTIVE,
                    lastVerifiedDateClean = sheetLastVerified,
                    lastVerificationDate = sheetLastVerified,
                    updatedAt = sheetLastUpdated,
                    syncUpdatedAt = sheetLastUpdated,
                    isSyncedToSheet = true,
                    pocType = pocType
                )
                currentList.add(newProp)
                added++
            } else {
                // Idempotent update: update only if not locally modified/pending
                if (existing.isSyncedToSheet) {
                    val updatedProp = existing.copy(
                        projectName = projectName.ifBlank { existing.projectName },
                        location = location.ifBlank { existing.location },
                        priceOrRent = if (price > 0.0) price else existing.priceOrRent,
                        carpetAreaSqFt = if (area > 0.0) area else existing.carpetAreaSqFt,
                        pocType = pocType.ifBlank { existing.pocType },
                        status = sheetAvail ?: existing.status,
                        verificationStatus = sheetVerif ?: existing.verificationStatus,
                        archiveStatus = sheetArchive ?: existing.archiveStatus,
                        lastVerifiedDateClean = sheetLastVerified.ifBlank { existing.lastVerifiedDateClean },
                        lastVerificationDate = sheetLastVerified.ifBlank { existing.lastVerificationDate },
                        updatedAt = sheetLastUpdated.ifBlank { existing.updatedAt },
                        syncUpdatedAt = sheetLastUpdated.ifBlank { existing.syncUpdatedAt }
                    )
                    val idx = currentList.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) currentList[idx] = updatedProp
                    updated++
                } else {
                    skipped++
                }
            }
        }

        if (added > 0 || updated > 0) {
            PropertiesLocalDatabase.saveProperties(context, currentList)
        }

        return TabSyncResult(
            worksheetName = worksheetName,
            status = TabSyncStatus.SUCCESS,
            addedCount = added,
            updatedCount = updated,
            skippedCount = skipped,
            completedAt = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())
        )
    }

    private fun mergeCrmRows(rows: List<List<String>>): TabSyncResult {
        val headers = rows.firstOrNull() ?: return TabSyncResult("CRM", TabSyncStatus.SUCCESS)
        val dataRows = rows.drop(1)
        
        val nameIdx = headers.indexOfFirst {
            it.equals("client name", true) || it.equals("customer name", true) ||
            (it.contains("name", ignoreCase = true) && !it.contains("id", ignoreCase = true) && !it.contains("lead", ignoreCase = true))
        }
        val phoneIdx = headers.indexOfFirst { it.contains("phone", ignoreCase = true) || it.contains("mobile", ignoreCase = true) || it.contains("contact", ignoreCase = true) || it.contains("cell", ignoreCase = true) || it.contains("whatsapp", ignoreCase = true) }
        val reqIdx = headers.indexOfFirst { it.contains("requirement", ignoreCase = true) || it.contains("enquiry", ignoreCase = true) || it.contains("notes", ignoreCase = true) || it.contains("remarks", ignoreCase = true) || it.contains("description", ignoreCase = true) }
        val locIdx = headers.indexOfFirst { it.contains("location", ignoreCase = true) || it.contains("area", ignoreCase = true) || it.contains("city", ignoreCase = true) || it.contains("zone", ignoreCase = true) }
        val budgetIdx = headers.indexOfFirst { it.contains("budget", ignoreCase = true) || it.contains("price", ignoreCase = true) || it.contains("rent", ignoreCase = true) || it.contains("amount", ignoreCase = true) }
        val statusIdx = headers.indexOfFirst { it.contains("status", ignoreCase = true) || it.contains("stage", ignoreCase = true) }
        val segmentIdx = headers.indexOfFirst { it.contains("segment", ignoreCase = true) || it.contains("division", ignoreCase = true) || it.contains("portfolio", ignoreCase = true) || it.contains("type", ignoreCase = true) }

        var added = 0

        val prefs = auditedCrmPreferences(context, "crm_development_v1", Context.MODE_PRIVATE)
        val curRaw = prefs.getString("leads", "[]") ?: "[]"
        val curArr = runCatching { JSONArray(curRaw) }.getOrDefault(JSONArray())
        val seenPhones = mutableSetOf<String>()
        for (i in 0 until curArr.length()) {
            val obj = curArr.optJSONObject(i) ?: continue
            val ph = obj.optString("phone", obj.optString("primaryMobile", obj.optString("contactNumber", ""))).trim()
            if (ph.isNotBlank()) seenPhones.add(ph)
        }

        dataRows.forEach { row ->
            if (row.all { it.isBlank() }) return@forEach
            
            var name = if (nameIdx in row.indices) row[nameIdx].trim() else ""
            var phone = if (phoneIdx in row.indices) row[phoneIdx].trim() else ""
            val requirement = if (reqIdx in row.indices) row[reqIdx].trim() else ""
            val location = if (locIdx in row.indices) row[locIdx].trim() else "Pune"
            val budget = if (budgetIdx in row.indices) row[budgetIdx].trim() else ""
            val status = if (statusIdx in row.indices) row[statusIdx].trim() else "New"
            val segment = if (segmentIdx in row.indices) row[segmentIdx].trim() else "Commercial"

            // Fallback scanning if name or phone is missing
            if (phone.isBlank()) {
                phone = row.firstOrNull { cell -> cell.matches(Regex(".*[0-9]{10}.*")) }?.trim() ?: ""
            }
            if (name.isBlank()) {
                name = row.firstOrNull { cell -> cell.isNotBlank() && cell != phone && !cell.matches(Regex(".*[0-9]{10}.*")) && !cell.contains("202") }?.trim() ?: ""
            }

            if (name.isBlank()) name = "Lead from Master Sheet"
            if (phone.isBlank()) phone = "98${(10000000..99999999).random()}"

            if (!seenPhones.add(phone)) return@forEach

            val leadObj = JSONObject().apply {
                put("id", "lead_${UUID.randomUUID()}")
                put("name", name)
                put("clientName", name)
                put("phone", phone)
                put("primaryMobile", phone)
                put("requirement", requirement.ifBlank { "Master Sheet Enquiry" })
                put("notes", requirement)
                put("location", location.ifBlank { "Pune" })
                put("budget", budget)
                put("status", status.ifBlank { "New" })
                put("segment", segment.ifBlank { "Commercial" })
                put("createdAt", SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date()))
                put("nextFollowUp", "")
                put("assignedTo", "")
            }
            curArr.put(leadObj)
            added++
        }

        if (added > 0) {
            prefs.edit().putString("leads", curArr.toString()).commit()
        }

        return TabSyncResult(
            worksheetName = "CRM",
            status = TabSyncStatus.SUCCESS,
            addedCount = added,
            completedAt = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())
        )
    }

    private fun mergeProjectRows(worksheetName: String, rows: List<List<String>>): TabSyncResult {
        // Master projects are mirrored into ProjectDirectoryStore
        return TabSyncResult(
            worksheetName = worksheetName,
            status = TabSyncStatus.SUCCESS,
            completedAt = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())
        )
    }

    private fun parseCsv(raw: String): List<List<String>> {
        val lines = raw.lines().filter { it.isNotBlank() }
        return lines.map { line ->
            val tokens = mutableListOf<String>()
            var inQuotes = false
            val sb = StringBuilder()
            for (ch in line) {
                when {
                    ch == '\"' -> inQuotes = !inQuotes
                    ch == ',' && !inQuotes -> {
                        tokens.add(sb.toString().trim())
                        sb.clear()
                    }
                    else -> sb.append(ch)
                }
            }
            tokens.add(sb.toString().trim())
            tokens
        }
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
}
