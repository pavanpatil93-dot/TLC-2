package com.example.model

import com.example.crm.ContactUtils
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

enum class TenantIssueCategory(val label: String) {
    AGREEMENT("Agreement"),
    MAINTENANCE("Maintenance"),
    PARKING("Parking"),
    ELECTRICITY("Electricity"),
    OWNER_MESSAGE("Owner Message");

    companion object {
        fun fromString(raw: String?): TenantIssueCategory =
            entries.firstOrNull {
                it.label.equals(raw?.trim(), ignoreCase = true) ||
                    it.name.equals(raw?.trim(), ignoreCase = true)
            } ?: MAINTENANCE
    }
}

enum class TenantIssueStatus(val label: String) {
    OPEN("Open"),
    WAITING_ON_OWNER("Waiting on Owner"),
    RESOLVED("Resolved");

    companion object {
        fun fromString(raw: String?): TenantIssueStatus =
            entries.firstOrNull {
                it.label.equals(raw?.trim(), ignoreCase = true) ||
                    it.name.equals(raw?.trim(), ignoreCase = true)
            } ?: OPEN
    }
}

data class TenantIssueRecord(
    val id: String = "ISS-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}",
    val tenantId: String = "",
    val propertyCode: String = "",
    val category: TenantIssueCategory = TenantIssueCategory.MAINTENANCE,
    val status: TenantIssueStatus = TenantIssueStatus.OPEN,
    val description: String = "",
    val reportedAt: String = LocalDate.now().toString(),
    val reportedBy: String = "",
    val ownerNotifiedAt: String = "",
    val resolvedAt: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("tenantId", tenantId)
        put("propertyCode", propertyCode)
        put("category", category.label)
        put("status", status.label)
        put("description", description)
        put("reportedAt", reportedAt)
        put("reportedBy", reportedBy)
        put("ownerNotifiedAt", ownerNotifiedAt)
        put("resolvedAt", resolvedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): TenantIssueRecord = TenantIssueRecord(
            id = obj.optString("id", "").ifBlank {
                "ISS-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}"
            },
            tenantId = obj.optString("tenantId", ""),
            propertyCode = obj.optString("propertyCode", ""),
            category = TenantIssueCategory.fromString(obj.optString("category", "")),
            status = TenantIssueStatus.fromString(obj.optString("status", "")),
            description = obj.optString("description", ""),
            reportedAt = obj.optString("reportedAt", ""),
            reportedBy = obj.optString("reportedBy", ""),
            ownerNotifiedAt = obj.optString("ownerNotifiedAt", ""),
            resolvedAt = obj.optString("resolvedAt", "")
        )

        fun parseList(raw: String?): List<TenantIssueRecord> {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isBlank()) return emptyList()
            if (trimmed.startsWith("[")) {
                return runCatching {
                    val arr = JSONArray(trimmed)
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { fromJson(it) }
                    }
                }.getOrDefault(emptyList())
            }
            // Support semicolon-separated plain summary fallback from manual sheet edits
            return trimmed.split(';').mapNotNull { part ->
                val p = part.trim()
                if (p.isBlank()) null
                else TenantIssueRecord(
                    category = TenantIssueCategory.entries.firstOrNull {
                        p.contains(it.label, ignoreCase = true)
                    } ?: TenantIssueCategory.MAINTENANCE,
                    status = if (p.contains("Resolved", ignoreCase = true)) {
                        TenantIssueStatus.RESOLVED
                    } else if (p.contains("Waiting", ignoreCase = true)) {
                        TenantIssueStatus.WAITING_ON_OWNER
                    } else {
                        TenantIssueStatus.OPEN
                    },
                    description = p
                )
            }
        }

        fun serializeList(issues: List<TenantIssueRecord>): String {
            val arr = JSONArray()
            issues.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}

data class TenantMoveInChecklist(
    val agreementSigned: Boolean = false,
    val policeVerification: Boolean = false,
    val societyNoc: Boolean = false,
    val keysHandedOver: Boolean = false,
    val inventoryListSigned: Boolean = false
) {
    fun completedCount(): Int = listOf(
        agreementSigned,
        policeVerification,
        societyNoc,
        keysHandedOver,
        inventoryListSigned
    ).count { it }

    fun toJson(): JSONObject = JSONObject().apply {
        put("agreementSigned", agreementSigned)
        put("policeVerification", policeVerification)
        put("societyNoc", societyNoc)
        put("keysHandedOver", keysHandedOver)
        put("inventoryListSigned", inventoryListSigned)
    }

    fun toSummaryString(): String {
        val ticks = mutableListOf<String>()
        if (agreementSigned) ticks.add("Agreement")
        if (policeVerification) ticks.add("Police")
        if (societyNoc) ticks.add("NOC")
        if (keysHandedOver) ticks.add("Keys")
        if (inventoryListSigned) ticks.add("Inventory")
        return ticks.joinToString(", ")
    }

    companion object {
        fun parse(raw: String?): TenantMoveInChecklist {
            val clean = raw?.trim().orEmpty()
            if (clean.isBlank()) return TenantMoveInChecklist()
            if (clean.startsWith("{")) {
                return runCatching {
                    val obj = JSONObject(clean)
                    TenantMoveInChecklist(
                        agreementSigned = obj.optBoolean("agreementSigned", false),
                        policeVerification = obj.optBoolean("policeVerification", false),
                        societyNoc = obj.optBoolean("societyNoc", false),
                        keysHandedOver = obj.optBoolean("keysHandedOver", false),
                        inventoryListSigned = obj.optBoolean("inventoryListSigned", false)
                    )
                }.getOrDefault(TenantMoveInChecklist())
            }
            return TenantMoveInChecklist(
                agreementSigned = clean.contains("Agreement", ignoreCase = true),
                policeVerification = clean.contains("Police", ignoreCase = true),
                societyNoc = clean.contains("NOC", ignoreCase = true),
                keysHandedOver = clean.contains("Key", ignoreCase = true),
                inventoryListSigned = clean.contains("Inventory", ignoreCase = true)
            )
        }
    }
}

/**
 * 9-Column schema matching the "Brokers & Vendors" Google Sheet tab (Cols A-I).
 */
data class BrokerVendorRecord(
    val id: String = "BV-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}",
    val name: String = "",
    val phone: String = "",
    val roleCategory: String = "Broker / CP", // "Broker / CP" or "Ops / Vendor"
    val subRole: String = "Channel Partner", // "Channel Partner", "Builder / MD", "Advocate", "Facility Mgr", "Society Chairman", "Electrician", "Plumber", "Carpenter", "Other"
    val firmName: String = "",
    val operatingAreas: String = "",
    val notes: String = "",
    val updatedAt: String = "",
    val phoneType: String = "",
    val alternateMobiles: String = "",
    val verificationStatus: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("phone", phone)
        put("roleCategory", roleCategory)
        put("subRole", subRole)
        put("firmName", firmName)
        put("operatingAreas", operatingAreas)
        put("notes", notes)
        put("updatedAt", updatedAt)
        put("phoneType", phoneType)
        put("alternateMobiles", alternateMobiles)
        put("verificationStatus", verificationStatus)
    }

    companion object {
        val opsSubRoles = listOf(
            "Builder / MD",
            "Advocate",
            "Facility Mgr",
            "Society Chairman",
            "Electrician",
            "Plumber",
            "Carpenter",
            "Other"
        )

        fun fromJson(obj: JSONObject): BrokerVendorRecord = BrokerVendorRecord(
            id = obj.optString("id", obj.optString("Record ID", "")).ifBlank {
                "BV-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}"
            },
            name = obj.optString("name", obj.optString("Name", "")),
            phone = obj.optString("phone", obj.optString("Phone", "")),
            roleCategory = obj.optString("roleCategory", obj.optString("Role Category", "Broker / CP")),
            subRole = obj.optString("subRole", obj.optString("Specialization", obj.optString("Sub-Role", obj.optString("Specialty", "Channel Partner")))),
            firmName = obj.optString("firmName", obj.optString("Firm / Agency", "")),
            operatingAreas = obj.optString("operatingAreas", obj.optString("Operating Areas", "")),
            notes = obj.optString("notes", obj.optString("Notes", "")),
            updatedAt = obj.optString("updatedAt", obj.optString("Updated At", "")),
            phoneType = obj.optString("phoneType", obj.optString("Phone Type", "")),
            alternateMobiles = obj.optString("alternateMobiles", obj.optString("Alternate Mobiles", "")),
            verificationStatus = obj.optString("verificationStatus", obj.optString("Verification Status", ""))
        )
    }
}

object PhoneMaskingUtils {
    /**
     * Admins & Managers always see unmasked confidential numbers.
     * Junior Executives see masked numbers unless they explicitly toggle full visibility ON.
     */
    fun canViewUnmaskedConfidentialPhone(
        userProfile: UserProfile,
        revealToggleOn: Boolean = false
    ): Boolean {
        val roleName = userProfile.role.name.uppercase()
        val username = userProfile.username.trim()
        val isSuperAdminOrAdminOrManager = userProfile.role == UserRole.ADMIN ||
            userProfile.role == UserRole.COMMERCIAL_MANAGER ||
            userProfile.role == UserRole.RESIDENTIAL_MANAGER ||
            userProfile.role == UserRole.INVENTORY_MANAGER ||
            roleName.contains("SUPER", true) ||
            roleName.contains("ADMIN", true) ||
            username.equals("Pawan", true) ||
            username.equals("Pawan Patil", true) ||
            username.equals("Priya", true) ||
            username.equals("Priya Thorat", true) ||
            username.equals("Sagar", true) ||
            username.equals("Sagar I. Chaudhari", true) ||
            username.equals("Ayush", true) ||
            username.equals("Ayush S. Khedkar", true)
        return isSuperAdminOrAdminOrManager || revealToggleOn
    }

    fun formatPhoneForRole(rawPhone: String?, canViewFull: Boolean): String {
        val clean10 = ContactUtils.normalizePhoneNumber(rawPhone)
        if (clean10.isBlank()) return rawPhone?.trim().orEmpty()
        if (canViewFull) return ContactUtils.formatDisplayPhone(clean10)
        return if (clean10.length == 10) {
            "+91 ${clean10.take(2)}••• ••${clean10.takeLast(3)}"
        } else {
            "••••${clean10.takeLast(3)}"
        }
    }
}

object TenantLeaseAlertHelper {
    private val DD_MM_YYYY_FMT = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH)

    fun parseFlexibleLocalDate(raw: String?): LocalDate? {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank()) return null
        val normalized = com.example.data.CrmSheetSyncMapper.formatCrmDateIst(clean)
        runCatching { return LocalDate.parse(normalized, DD_MM_YYYY_FMT) }
        val candidate10 = if (clean.length >= 10 && clean[4] == '-' && clean[7] == '-') clean.take(10) else clean
        return runCatching { LocalDate.parse(candidate10) }.getOrNull()
    }

    /** Returns days remaining until lease expiry, or null if invalid/empty. */
    fun daysUntilExpiry(leaseExpiryDate: String?, today: LocalDate = LocalDate.now()): Long? {
        val parsed = parseFlexibleLocalDate(leaseExpiryDate) ?: return null
        return ChronoUnit.DAYS.between(today, parsed)
    }

    fun isRenewal90dAlert(leaseExpiryDate: String?, today: LocalDate = LocalDate.now()): Boolean {
        val days = daysUntilExpiry(leaseExpiryDate, today) ?: return false
        return days in -15..90
    }

    /**
     * Checks if annual rent escalation anniversary from `leaseStartDate` is due within the next 30 days.
     */
    fun daysUntilNextEscalation(leaseStartDate: String?, leaseExpiryDate: String?, today: LocalDate = LocalDate.now()): Long? {
        val start = parseFlexibleLocalDate(leaseStartDate) ?: return null
        val expiry = parseFlexibleLocalDate(leaseExpiryDate)
        if (expiry != null && expiry.isBefore(today)) return null
        var nextAnniversary = runCatching {
            LocalDate.of(today.year, start.month, minOf(start.dayOfMonth, start.month.length(today.isLeapYear)))
        }.getOrNull() ?: return null
        if (nextAnniversary.isBefore(today.minusDays(7))) {
            nextAnniversary = nextAnniversary.plusYears(1)
        }
        if (!nextAnniversary.isAfter(start)) {
            nextAnniversary = nextAnniversary.plusYears(1)
        }
        if (expiry != null && nextAnniversary.isAfter(expiry)) return null
        return ChronoUnit.DAYS.between(today, nextAnniversary)
    }

    fun isEscalation30dAlert(leaseStartDate: String?, leaseExpiryDate: String?, today: LocalDate = LocalDate.now()): Boolean {
        val days = daysUntilNextEscalation(leaseStartDate, leaseExpiryDate, today) ?: return false
        return days in -7..30
    }
}
