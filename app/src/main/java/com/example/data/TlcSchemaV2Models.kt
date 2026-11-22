package com.example.data

import com.example.model.InventoryTab
import com.example.model.PropertyItem
import com.example.model.UserProfile
import com.example.model.UserRole
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Schema v2 Master Settings dropdowns returned by `GET ?action=bootstrap&api_key=...`.
 */
data class TlcMasterDropdowns(
    val locations: List<String> = emptyList(),
    val inventoryStatus: List<String> = emptyList(),
    val verificationStatus: List<String> = emptyList(),
    val commercialAssetTypes: List<String> = emptyList(),
    val residentialConfigurations: List<String> = emptyList(),
    val puneZones: List<String> = emptyList(),
    val residentialPropertyTypes: List<String> = emptyList(),
    val preferredCallTimes: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("locations", JSONArray(locations))
        put("inventoryStatus", JSONArray(inventoryStatus))
        put("verificationStatus", JSONArray(verificationStatus))
        put("commercialAssetTypes", JSONArray(commercialAssetTypes))
        put("residentialConfigurations", JSONArray(residentialConfigurations))
        put("puneZones", JSONArray(puneZones))
        put("residentialPropertyTypes", JSONArray(residentialPropertyTypes))
        put("preferredCallTimes", JSONArray(preferredCallTimes))
    }

    companion object {
        private fun readStringList(obj: JSONObject, vararg keys: String): List<String> {
            for (key in keys) {
                val arr = obj.optJSONArray(key) ?: continue
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "").trim()
                        if (v.isNotBlank()) add(v)
                    }
                }
                if (list.isNotEmpty()) return list
            }
            return emptyList()
        }

        fun fromJson(obj: JSONObject?): TlcMasterDropdowns {
            if (obj == null) return TlcMasterDropdowns()
            val nested = obj.optJSONObject("dropdowns")
                ?: obj.optJSONObject("masterSettings")
                ?: obj.optJSONObject("settings")
                ?: obj
            return TlcMasterDropdowns(
                locations = readStringList(nested, "locations", "Locations"),
                inventoryStatus = readStringList(nested, "inventoryStatus", "inventory_status", "Inventory Status"),
                verificationStatus = readStringList(nested, "verificationStatus", "verification_status", "Verification Status"),
                commercialAssetTypes = readStringList(nested, "commercialAssetTypes", "commercial_asset_types", "Commercial Asset Types"),
                residentialConfigurations = readStringList(nested, "residentialConfigurations", "residential_configurations", "Residential Configurations"),
                puneZones = readStringList(nested, "puneZones", "pune_zones", "Pune Zones"),
                residentialPropertyTypes = readStringList(nested, "residentialPropertyTypes", "residential_property_types", "Residential Property Types"),
                preferredCallTimes = readStringList(nested, "preferredCallTimes", "preferred_call_times", "Preferred Call Times")
            )
        }
    }
}

/**
 * Schema v2 Team member returned in the `team` array of `GET ?action=bootstrap&api_key=...`.
 */
data class TlcTeamMember(
    val userId: String,
    val fullName: String,
    val emailAddress: String,
    val phoneNumber: String,
    val appRole: String,
    val activeStatus: String,
    val division: String = "Commercial + Residential",
    val passwordHashOrPlain: String = ""
) {
    fun isActive(): Boolean = activeStatus.trim().equals("Active", ignoreCase = true)

    fun isAdminPermission(): Boolean {
        val r = appRole.trim().lowercase(Locale.ENGLISH)
        return r.contains("super admin") ||
            r == "admin" ||
            r.contains("team lead") ||
            r.contains("manager")
    }

    fun apiRoleParam(): String = if (isAdminPermission()) "Admin" else "RM"

    fun toUserRole(): UserRole {
        val r = appRole.trim().lowercase(Locale.ENGLISH)
        return when {
            r.contains("super admin") || r == "admin" -> UserRole.ADMIN
            r.contains("team lead") || r.contains("manager") -> {
                when {
                    division.contains("Residential", ignoreCase = true) &&
                        !division.contains("Commercial", ignoreCase = true) -> UserRole.RESIDENTIAL_MANAGER
                    division.contains("Commercial", ignoreCase = true) &&
                        !division.contains("Residential", ignoreCase = true) -> UserRole.COMMERCIAL_MANAGER
                    else -> UserRole.ADMIN
                }
            }
            r.contains("backoffice") || r.contains("ops") || r.contains("inventory") -> UserRole.INVENTORY_MANAGER
            r.contains("read") -> UserRole.READ_ONLY
            else -> UserRole.SALES_EXECUTIVE
        }
    }

    fun toUserProfile(): UserProfile {
        val mappedRole = toUserRole()
        val isAdmin = isAdminPermission()
        val isOps = appRole.contains("Backoffice", ignoreCase = true) ||
            appRole.contains("Ops", ignoreCase = true)
        return UserProfile(
            username = fullName,
            mobileNumber = phoneNumber,
            email = emailAddress,
            role = mappedRole,
            profilePhotoUri = null,
            employeeId = userId,
            division = division.ifBlank { "Commercial + Residential" },
            accountStatus = activeStatus.ifBlank { "Active" },
            appRole = appRole.ifBlank { mappedRole.label },
            canViewFullOwnerContacts = isAdmin || isOps,
            canBulkExport = isAdmin || isOps,
            canDeleteRecords = isAdmin
        )
    }

    fun matchesLoginIdentifier(rawIdentifier: String): Boolean {
        val clean = rawIdentifier.trim()
        if (clean.isBlank()) return false
        if (userId.isNotBlank() && userId.equals(clean, ignoreCase = true)) return true
        if (emailAddress.isNotBlank() && emailAddress.equals(clean, ignoreCase = true)) return true
        val inputDigits = clean.filter(Char::isDigit).takeLast(10)
        val memberDigits = phoneNumber.filter(Char::isDigit).takeLast(10)
        if (inputDigits.length == 10 && memberDigits.length == 10 && inputDigits == memberDigits) {
            return true
        }
        return false
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("User ID", userId)
        put("Full Name", fullName)
        put("Email Address", emailAddress)
        put("Phone Number", phoneNumber)
        put("App Role", appRole)
        put("Active Status", activeStatus)
        put("Division", division)
    }

    companion object {
        private fun optAny(obj: JSONObject, vararg keys: String): String {
            for (k in keys) {
                val v = obj.optString(k, "").trim()
                if (v.isNotBlank() && !v.equals("null", ignoreCase = true)) return v
            }
            return ""
        }

        fun fromJson(obj: JSONObject): TlcTeamMember = TlcTeamMember(
            userId = optAny(obj, "User ID", "userId", "user_id", "Employee ID", "employeeId", "id"),
            fullName = optAny(obj, "Full Name", "fullName", "full_name", "Employee Name", "name"),
            emailAddress = optAny(obj, "Email Address", "emailAddress", "email_address", "Email", "email"),
            phoneNumber = optAny(obj, "Phone Number", "phoneNumber", "phone_number", "Mobile", "phone", "mobile"),
            appRole = optAny(obj, "App Role", "appRole", "app_role", "Role", "role").ifBlank { "RM" },
            activeStatus = optAny(obj, "Active Status", "activeStatus", "active_status", "Account Status", "status").ifBlank { "Active" },
            division = optAny(obj, "Division", "division", "Vertical", "vertical", "Department").ifBlank { "Commercial + Residential" },
            passwordHashOrPlain = optAny(obj, "Password", "password", "PIN", "pin")
        )
    }
}

data class TlcBootstrapPayload(
    val dropdowns: TlcMasterDropdowns = TlcMasterDropdowns(),
    val team: List<TlcTeamMember> = emptyList(),
    val fetchedAtMillis: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("dropdowns", dropdowns.toJson())
        put("team", JSONArray().apply { team.forEach { put(it.toJson()) } })
        put("fetchedAtMillis", fetchedAtMillis)
    }

    companion object {
        fun fromJson(root: JSONObject): TlcBootstrapPayload {
            val container = root.optJSONObject("data") ?: root
            val dropdowns = TlcMasterDropdowns.fromJson(container)
            val teamArr = container.optJSONArray("team")
                ?: root.optJSONArray("team")
                ?: container.optJSONArray("Team")
                ?: container.optJSONArray("employees")
            val members = buildList {
                if (teamArr != null) {
                    for (i in 0 until teamArr.length()) {
                        val obj = teamArr.optJSONObject(i) ?: continue
                        val member = TlcTeamMember.fromJson(obj)
                        if (member.userId.isNotBlank() || member.fullName.isNotBlank() || member.emailAddress.isNotBlank() || member.phoneNumber.isNotBlank()) {
                            add(member)
                        }
                    }
                }
            }
            return TlcBootstrapPayload(
                dropdowns = dropdowns,
                team = members,
                fetchedAtMillis = root.optLong("fetchedAtMillis", System.currentTimeMillis())
            )
        }
    }
}

data class TlcSiteVisitRecord(
    val visitId: String = "",
    val leadId: String = "",
    val clientName: String = "",
    val propertyCode: String = "",
    val projectName: String = "",
    val zone: String = "",
    val visitDate: String = "",
    val visitTime: String = "",
    val assignedRm: String = "",
    val status: String = "Scheduled",
    val feedback: String = "",
    val nextAction: String = "",
    val raw: JSONObject = JSONObject()
) {
    fun toPostRecordJson(): JSONObject = JSONObject().apply {
        if (visitId.isNotBlank()) put("Visit ID", visitId)
        put("Lead ID", leadId)
        put("Client Name", clientName)
        put("Property ID", propertyCode)
        put("Property Code", propertyCode)
        put("Project / Building", projectName)
        put("Zone", zone)
        put("Visit Date", visitDate)
        put("Visit Time", visitTime)
        put("Assigned RM", assignedRm)
        put("Status", status)
        put("Client Feedback", feedback)
        put("Next Action", nextAction)
    }

    companion object {
        private fun optAny(obj: JSONObject, vararg keys: String): String {
            for (k in keys) {
                val v = obj.optString(k, "").trim()
                if (v.isNotBlank() && !v.equals("null", ignoreCase = true)) return v
            }
            return ""
        }

        fun fromJson(obj: JSONObject): TlcSiteVisitRecord = TlcSiteVisitRecord(
            visitId = optAny(obj, "Visit ID", "visitId", "id"),
            leadId = optAny(obj, "Lead ID", "leadId"),
            clientName = optAny(obj, "Client Name", "clientName", "Lead Name"),
            propertyCode = optAny(obj, "Property ID", "Property Code", "propertyCode", "unitCode"),
            projectName = optAny(obj, "Project / Building", "Project Name", "projectName", "Property"),
            zone = optAny(obj, "Zone", "Pune Zone", "Location", "zone"),
            visitDate = optAny(obj, "Visit Date", "Date", "visitDate"),
            visitTime = optAny(obj, "Visit Time", "Time", "visitTime"),
            assignedRm = optAny(obj, "Assigned RM", "Assigned To", "assigned_rm", "RM"),
            status = optAny(obj, "Status", "Visit Status", "status").ifBlank { "Scheduled" },
            feedback = optAny(obj, "Client Feedback", "Feedback", "Notes", "Remarks"),
            nextAction = optAny(obj, "Next Action", "nextAction"),
            raw = obj
        )
    }
}

data class TlcDashboardKpis(
    val totalActiveLeads: Int = 0,
    val totalAvailableInventory: Int = 0,
    val visitsScheduled: Int = 0,
    val visitsCompleted: Int = 0,
    val hotLeadsCount: Int = 0,
    val fetchedAtMillis: Long = System.currentTimeMillis(),
    val raw: JSONObject = JSONObject()
) {
    companion object {
        private fun optIntAny(obj: JSONObject, vararg keys: String): Int {
            for (k in keys) {
                if (obj.has(k)) {
                    val v = obj.optInt(k, Int.MIN_VALUE)
                    if (v != Int.MIN_VALUE) return v
                    val s = obj.optString(k, "").trim().toIntOrNull()
                    if (s != null) return s
                }
            }
            return 0
        }

        fun fromJson(root: JSONObject): TlcDashboardKpis {
            val data = root.optJSONObject("data") ?: root.optJSONObject("kpis") ?: root
            return TlcDashboardKpis(
                totalActiveLeads = optIntAny(data, "totalActiveLeads", "activeLeads", "total_leads", "leadsCount"),
                totalAvailableInventory = optIntAny(data, "totalAvailableInventory", "availableInventory", "total_inventory", "inventoryCount"),
                visitsScheduled = optIntAny(data, "visitsScheduled", "scheduledVisits", "upcomingVisits"),
                visitsCompleted = optIntAny(data, "visitsCompleted", "completedVisits"),
                hotLeadsCount = optIntAny(data, "hotLeads", "hotLeadsCount", "hot_leads"),
                fetchedAtMillis = System.currentTimeMillis(),
                raw = data
            )
        }
    }
}

/**
 * Maps internal PropertyItem tabs to the 5 Schema v2 inventory tab names expected by `submit_inventory`.
 */
object TlcInventoryTabV2 {
    const val COMMERCIAL_LEASE = "Commercial Lease Inventory"
    const val COMMERCIAL_SALE = "Commercial Sale Inventory"
    const val PRE_LEASED = "Pre-Leased Inventory"
    const val RESIDENTIAL_LEASE = "Residential Lease Inventory"
    const val RESIDENTIAL_SALE = "Residential Sale Inventory"

    val ALL_FIVE_TABS = listOf(
        COMMERCIAL_LEASE,
        COMMERCIAL_SALE,
        PRE_LEASED,
        RESIDENTIAL_LEASE,
        RESIDENTIAL_SALE
    )

    fun resolveTabName(property: PropertyItem): String = when (property.tab) {
        InventoryTab.OFF_LEASE, InventoryTab.SHOP_LEASE -> COMMERCIAL_LEASE
        InventoryTab.OFF_OUT, InventoryTab.SHOP_OUT -> COMMERCIAL_SALE
        InventoryTab.PRE_LEASED -> PRE_LEASED
        InventoryTab.RESI_RENT -> RESIDENTIAL_LEASE
        InventoryTab.RESI_RESALE, InventoryTab.UND_CONST -> RESIDENTIAL_SALE
    }
}
