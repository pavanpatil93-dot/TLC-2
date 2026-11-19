package com.example.data

import android.content.Context
import android.util.AtomicFile
import com.example.crm.CrmCallRecord
import com.example.data.projects.DirectoryProject
import com.example.model.BrokerVendorRecord
import com.example.model.DailyReportRecord
import com.example.model.PropertyItem
import com.example.model.TenantIssueRecord
import com.example.model.TenantMoveInChecklist
import com.example.model.isResidential
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Batch 13 + Batch 18: Full Offline Disk Caching in `context.filesDir`.
 *
 * Persists all synced datasets atomically as JSON files inside `context.filesDir`
 * so app launch renders instantaneously offline or online, local mutations write
 * immediately to disk before background cloud sync, and fresh Sheet sync responses
 * overwrite/merge seamlessly.
 */
object OfflineDiskCache {

    const val FILE_COMMERCIAL_INVENTORY = "cache_commercial_inventory.json"
    const val FILE_RESIDENTIAL_INVENTORY = "cache_residential_inventory.json"
    const val FILE_COMMERCIAL_PROJECTS = "cache_commercial_projects.json"
    const val FILE_RESIDENTIAL_PROJECTS = "cache_residential_projects.json"
    const val FILE_CRM_LEADS = "cache_crm_leads.json"
    const val FILE_EMPLOYEES = "cache_employees.json"
    const val FILE_DAILY_REPORTS = "cache_daily_reports.json"
    const val FILE_CALL_LOGS = "cache_call_logs.json"
    const val FILE_TENANTS = "cache_tenants.json"
    const val FILE_BROKERS_VENDORS = "cache_brokers_vendors.json"
    const val FILE_TEAM_HANDOVERS = "cache_team_handovers.json"

    internal fun writeTextAtomically(context: Context, fileName: String, content: String): Boolean {
        return synchronized(this) {
            runCatching {
                val target = File(context.filesDir, fileName)
                val atomic = AtomicFile(target)
                val stream = atomic.startWrite()
                try {
                    stream.write(content.toByteArray(Charsets.UTF_8))
                    stream.flush()
                    atomic.finishWrite(stream)
                    true
                } catch (t: Throwable) {
                    atomic.failWrite(stream)
                    throw t
                }
            }.getOrDefault(false)
        }
    }

    internal fun readTextSafely(context: Context, fileName: String): String? {
        return synchronized(this) {
            runCatching {
                val target = File(context.filesDir, fileName)
                val bak = File(context.filesDir, "$fileName.bak")
                if (!target.exists() && !bak.exists()) return@runCatching null
                AtomicFile(target).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()
        }
    }

    // ── 1 & 2. COMMERCIAL & RESIDENTIAL INVENTORY ──────────────────────────────────────

    fun saveInventorySplits(context: Context, properties: List<PropertyItem>) {
        val residential = properties.filter { it.isResidential() }
        val commercial = properties.filterNot { it.isResidential() }
        val commArr = JSONArray().apply {
            commercial.forEach { put(PropertiesLocalDatabase.serializePropertyJson(it)) }
        }
        val resArr = JSONArray().apply {
            residential.forEach { put(PropertiesLocalDatabase.serializePropertyJson(it)) }
        }
        writeTextAtomically(context, FILE_COMMERCIAL_INVENTORY, commArr.toString())
        writeTextAtomically(context, FILE_RESIDENTIAL_INVENTORY, resArr.toString())
    }

    fun loadInventorySplits(context: Context): List<PropertyItem> {
        val commRaw = readTextSafely(context, FILE_COMMERCIAL_INVENTORY)
        val resRaw = readTextSafely(context, FILE_RESIDENTIAL_INVENTORY)
        if (commRaw.isNullOrBlank() && resRaw.isNullOrBlank()) return emptyList()
        val combined = mutableListOf<PropertyItem>()
        listOfNotNull(commRaw, resRaw).forEach { raw ->
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    combined.add(PropertiesLocalDatabase.deserializePropertyJson(arr.getJSONObject(i)))
                }
            }
        }
        return combined.distinctBy { it.id }
    }

    // ── 3 & 4. COMMERCIAL & RESIDENTIAL PROJECTS ───────────────────────────────────────

    fun saveProjectsJsonArray(context: Context, division: String, projectsArray: JSONArray) {
        val fileName = if (division.equals("residential", ignoreCase = true)) {
            FILE_RESIDENTIAL_PROJECTS
        } else {
            FILE_COMMERCIAL_PROJECTS
        }
        writeTextAtomically(context, fileName, projectsArray.toString())
    }

    fun saveProjects(context: Context, projects: List<DirectoryProject>) {
        val comm = JSONArray()
        val res = JSONArray()
        projects.forEach { p ->
            val obj = JSONObject(p.raw.toString()).apply {
                put("id", p.id)
                put("division", p.division)
                put("name", p.name)
                put("developer", p.developer)
                put("location", p.location)
                put("category", p.category)
                put("phase", p.phase)
                put("configurations", p.configurations)
                put("areaRange", p.areaRange)
                put("priceRange", p.priceRange)
                put("possession", p.possession)
                put("sourceUrl", p.sourceUrl)
                put("otherSourceUrl", p.otherSourceUrl)
                put("verification", p.verification)
                put("archiveStatus", p.archiveStatus)
                put("coverImageUrl", p.coverImageUrl)
                put("galleryImageUrls", JSONArray(p.galleryImageUrls))
            }
            if (p.division.equals("residential", ignoreCase = true)) res.put(obj) else comm.put(obj)
        }
        writeTextAtomically(context, FILE_COMMERCIAL_PROJECTS, comm.toString())
        writeTextAtomically(context, FILE_RESIDENTIAL_PROJECTS, res.toString())
    }

    fun loadProjectsRaw(context: Context, division: String): String? {
        val fileName = if (division.equals("residential", ignoreCase = true)) {
            FILE_RESIDENTIAL_PROJECTS
        } else {
            FILE_COMMERCIAL_PROJECTS
        }
        return readTextSafely(context, fileName)
    }

    // ── 5. CRM LEADS ───────────────────────────────────────────────────────────────────

    fun saveCrmLeadsJson(context: Context, leadsJsonArrayString: String) {
        writeTextAtomically(context, FILE_CRM_LEADS, leadsJsonArrayString)
    }

    fun loadCrmLeadsJson(context: Context): String? {
        return readTextSafely(context, FILE_CRM_LEADS)
    }

    // ── 6. EMPLOYEES ───────────────────────────────────────────────────────────────────

    data class CachedEmployeeMember(
        val id: String = "",
        val name: String,
        val mobile: String = "",
        val division: String = "",
        val teamId: String = "",
        val role: String = "",
        val status: String = "Active",
        val managerId: String = "",
        val loginId: String = "",
        val password: String = "",
        val updatedAt: String = ""
    ) {
        fun isActiveMember(): Boolean {
            val s = status.trim().lowercase(java.util.Locale.ENGLISH)
            return s.isBlank() || (!s.contains("inactive") && !s.contains("suspend") && s != "false" && s != "disabled")
        }

        fun initials(): String {
            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            return when {
                parts.size >= 2 -> "${parts.first().first().uppercaseChar()}${parts.last().first().uppercaseChar()}"
                parts.size == 1 -> parts.first().take(2).uppercase(java.util.Locale.ENGLISH)
                else -> "TL"
            }
        }

        fun roleAndDivisionLabel(): String {
            val cleanRole = role.trim().ifBlank { "Team Member" }
            val cleanDiv = division.trim()
            return if (cleanDiv.isNotBlank() && !cleanRole.contains(cleanDiv, ignoreCase = true)) {
                "$cleanRole • $cleanDiv"
            } else {
                cleanRole
            }
        }

        fun tenDigitMobile(): String {
            val digits = mobile.filter { it.isDigit() }
            return if (digits.length > 10) digits.takeLast(10) else digits
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("Employee ID", id)
            put("Employee Name", name)
            put("Mobile", mobile)
            put("Division", division)
            put("Team ID", teamId)
            put("Role", role)
            put("Account Status", status)
            put("Manager ID", managerId)
            put("Login ID", loginId)
            put("Password", password)
            put("Updated At", updatedAt)
            put("id", id)
            put("name", name)
            put("mobile", mobile)
            put("division", division)
            put("role", role)
            put("status", status)
        }

        companion object {
            fun fromJson(obj: JSONObject): CachedEmployeeMember = CachedEmployeeMember(
                id = obj.optString("Employee ID", obj.optString("id", "")).trim(),
                name = obj.optString("Employee Name", obj.optString("name", obj.optString("Name", ""))).trim(),
                mobile = obj.optString("Mobile", obj.optString("mobile", obj.optString("Phone", ""))).trim(),
                division = obj.optString("Division", obj.optString("division", obj.optString("Business Scope", ""))).trim(),
                teamId = obj.optString("Team ID", obj.optString("teamId", "")).trim(),
                role = obj.optString("Role", obj.optString("role", "")).trim(),
                status = obj.optString("Account Status", obj.optString("status", obj.optString("Status", "Active"))).trim().ifBlank { "Active" },
                managerId = obj.optString("Manager ID", obj.optString("managerId", "")).trim(),
                loginId = obj.optString("Login ID", obj.optString("loginId", "")).trim(),
                password = obj.optString("Password", obj.optString("password", "")).trim(),
                updatedAt = obj.optString("Updated At", obj.optString("updatedAt", "")).trim()
            )
        }
    }

    fun saveEmployeesJson(context: Context, employeesArray: JSONArray) {
        writeTextAtomically(context, FILE_EMPLOYEES, employeesArray.toString())
    }

    fun saveEmployeeDirectory(context: Context, employees: List<CachedEmployeeMember>) {
        val arr = JSONArray().apply {
            employees.forEach { put(it.toJson()) }
        }
        writeTextAtomically(context, FILE_EMPLOYEES, arr.toString())
    }

    fun loadEmployeeDirectory(context: Context): List<CachedEmployeeMember> {
        val raw = readTextSafely(context, FILE_EMPLOYEES) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                when (val item = arr.opt(i)) {
                    is JSONObject -> CachedEmployeeMember.fromJson(item)
                        .takeIf { it.name.isNotBlank() && it.isActiveMember() }
                    is String -> item.trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { CachedEmployeeMember(name = it) }
                    else -> null
                }
            }.distinctBy { it.name.lowercase(java.util.Locale.ENGLISH) }
        }.getOrDefault(emptyList())
    }

    fun loadEmployeeNames(context: Context): List<String> {
        val raw = readTextSafely(context, FILE_EMPLOYEES) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                when (val item = arr.opt(i)) {
                    is String -> item.trim().takeIf { it.isNotBlank() }
                    is JSONObject -> item.optString("name", item.optString("Name", "")).trim().takeIf { it.isNotBlank() }
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    // ── 7. DAILY REPORTS ───────────────────────────────────────────────────────────────

    fun saveDailyReports(context: Context, reports: List<DailyReportRecord>) {
        val arr = JSONArray().apply {
            reports.forEach { put(it.toJson()) }
        }
        writeTextAtomically(context, FILE_DAILY_REPORTS, arr.toString())
    }

    fun loadDailyReports(context: Context): List<DailyReportRecord> {
        val raw = readTextSafely(context, FILE_DAILY_REPORTS) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { DailyReportRecord.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    // ── 8. CALL LOGS ───────────────────────────────────────────────────────────────────

    fun saveCallLogs(context: Context, calls: List<CrmCallRecord>) {
        val arr = JSONArray().apply {
            calls.forEach { put(it.toJson()) }
        }
        writeTextAtomically(context, FILE_CALL_LOGS, arr.toString())
    }

    fun loadCallLogs(context: Context): List<CrmCallRecord> {
        val raw = readTextSafely(context, FILE_CALL_LOGS) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { CrmCallRecord.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    // ── 9. TENANTS (22-COLUMN SCHEMA COLS A–V) ─────────────────────────────────────────

    data class TenantCacheRecord(
        val id: String,
        val tenantName: String,
        val propertyCode: String = "",
        val projectName: String = "",
        val location: String = "",
        val monthlyRent: String = "",
        val leaseStartDate: String = "",
        val leaseExpiryDate: String = "",
        val lockInPeriod: String = "",
        val contactNumber: String = "",
        val notes: String = "",
        // Batch 18: Extended 22-col schema fields (Cols A-V)
        val unitNumber: String = "",
        val division: String = "Commercial",
        val companyName: String = "",
        val tenantEmail: String = "",
        val depositAmount: String = "",
        val escalationPercent: String = "5",
        val lockInEndDate: String = "",
        val status: String = "Active",
        val moveInChecklistJson: String = "",
        val openIssuesJson: String = "",
        val ownerName: String = "",
        val ownerContact: String = "",
        val updatedAt: String = "",
        val assignedTo: String = ""
    ) {
        fun parsedChecklist(): TenantMoveInChecklist = TenantMoveInChecklist.parse(moveInChecklistJson)

        fun parsedIssues(): List<TenantIssueRecord> = TenantIssueRecord.parseList(openIssuesJson)

        fun openIssuesCount(): Int = parsedIssues().count {
            it.status != com.example.model.TenantIssueStatus.RESOLVED
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("tenantName", tenantName)
            put("propertyCode", propertyCode)
            put("projectName", projectName)
            put("location", location)
            put("monthlyRent", monthlyRent)
            put("leaseStartDate", leaseStartDate)
            put("leaseExpiryDate", leaseExpiryDate)
            put("lockInPeriod", lockInPeriod)
            put("contactNumber", contactNumber)
            put("notes", notes)
            put("unitNumber", unitNumber)
            put("division", division)
            put("companyName", companyName)
            put("tenantEmail", tenantEmail)
            put("depositAmount", depositAmount)
            put("escalationPercent", escalationPercent)
            put("lockInEndDate", lockInEndDate)
            put("status", status)
            put("moveInChecklistJson", moveInChecklistJson)
            put("openIssuesJson", openIssuesJson)
            put("ownerName", ownerName)
            put("ownerContact", ownerContact)
            put("updatedAt", updatedAt)
            put("assignedTo", assignedTo)
        }

        companion object {
            fun fromJson(obj: JSONObject): TenantCacheRecord = TenantCacheRecord(
                id = obj.optString("id", "").ifBlank {
                    obj.optString("Tenant ID", obj.optString("propertyCode", ""))
                },
                tenantName = obj.optString("tenantName", "").ifBlank {
                    obj.optString("Tenant Name", obj.optString("Tenant", obj.optString("Name", "")))
                },
                propertyCode = obj.optString("propertyCode", obj.optString("Property Code", "")),
                projectName = obj.optString("projectName", obj.optString("Project / Building", obj.optString("Project", ""))),
                location = obj.optString("location", obj.optString("Location", "")),
                monthlyRent = obj.optString("monthlyRent", obj.optString("Monthly Rent", obj.optString("Rent", ""))),
                leaseStartDate = obj.optString("leaseStartDate", obj.optString("Lease Start Date", "")),
                leaseExpiryDate = obj.optString("leaseExpiryDate", obj.optString("Lease Expiry Date", "")),
                lockInPeriod = obj.optString("lockInPeriod", obj.optString("Lock-in Period", "")),
                contactNumber = obj.optString("contactNumber", "").ifBlank {
                    obj.optString("Tenant Phone", obj.optString("Contact Number", ""))
                },
                notes = obj.optString("notes", obj.optString("Notes", "")),
                unitNumber = obj.optString("unitNumber", obj.optString("Unit No", "")),
                division = obj.optString("division", obj.optString("Division", "Commercial")),
                companyName = obj.optString("companyName", obj.optString("Company / Brand", "")),
                tenantEmail = obj.optString("tenantEmail", obj.optString("Tenant Email", "")),
                depositAmount = obj.optString("depositAmount", obj.optString("Deposit Amount", "")),
                escalationPercent = obj.optString("escalationPercent", obj.optString("Escalation %", "5")),
                lockInEndDate = obj.optString("lockInEndDate", obj.optString("Lock-in End Date", "")),
                status = obj.optString("status", obj.optString("Status", "Active")),
                moveInChecklistJson = obj.optString("moveInChecklistJson", obj.optString("Move-in Checklist", "")),
                openIssuesJson = obj.optString("openIssuesJson", obj.optString("Open Issues", "")),
                ownerName = obj.optString("ownerName", obj.optString("Owner Name", "")),
                ownerContact = obj.optString("ownerContact", obj.optString("Owner Phone", "")),
                updatedAt = obj.optString("updatedAt", obj.optString("Updated At", "")),
                assignedTo = obj.optString("assignedTo", obj.optString("Assigned To", ""))
            )
        }
    }

    fun saveTenants(context: Context, tenants: List<TenantCacheRecord>) {
        val arr = JSONArray().apply {
            tenants.forEach { put(it.toJson()) }
        }
        writeTextAtomically(context, FILE_TENANTS, arr.toString())
    }

    fun loadTenants(context: Context): List<TenantCacheRecord> {
        val raw = readTextSafely(context, FILE_TENANTS) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { TenantCacheRecord.fromJson(arr.getJSONObject(i)) }
                    .getOrNull()
                    ?.takeIf { it.id.isNotBlank() || it.tenantName.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    // ── 10. BROKERS & VENDORS (9-COLUMN SCHEMA COLS A–I) ───────────────────────────────

    fun saveBrokersVendors(context: Context, records: List<BrokerVendorRecord>) {
        val arr = JSONArray().apply {
            records.forEach { put(it.toJson()) }
        }
        writeTextAtomically(context, FILE_BROKERS_VENDORS, arr.toString())
    }

    fun loadBrokersVendors(context: Context): List<BrokerVendorRecord> {
        val raw = readTextSafely(context, FILE_BROKERS_VENDORS) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { BrokerVendorRecord.fromJson(arr.getJSONObject(i)) }
                    .getOrNull()
                    ?.takeIf { it.name.isNotBlank() || it.phone.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }
}
