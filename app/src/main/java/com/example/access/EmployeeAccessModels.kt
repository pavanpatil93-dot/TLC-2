package com.example.access

import com.example.model.ActiveBrand
import com.example.model.Brand
import com.example.model.StandardPortfolio
import com.example.model.UserProfile
import com.example.model.UserRole
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

val APPROVED_DESK_CONTACTS: List<String> = listOf("9766880099", "9766668099")
const val APPROVED_DESK_FOOTER: String = "9766880099 | 9766668099"
const val NOT_MENTIONED: String = "Not Mentioned"
const val PIN_MAX_FAILED_ATTEMPTS: Int = 5
const val PIN_LOCKOUT_DURATION_MS: Long = 5 * 60 * 1000L

enum class EmployeeAccessRole(val label: String) {
    ADMIN("Admin"),
    COMMERCIAL_MANAGER("Commercial Manager"),
    RESIDENTIAL_MANAGER("Residential Manager"),
    SALES_EXECUTIVE("Sales Executive"),
    INVENTORY_MANAGER("Inventory Manager"),
    READ_ONLY("Read-Only");

    fun toLegacyUserRole(): UserRole = when (this) {
        ADMIN -> UserRole.ADMIN
        COMMERCIAL_MANAGER -> UserRole.COMMERCIAL_MANAGER
        RESIDENTIAL_MANAGER -> UserRole.RESIDENTIAL_MANAGER
        SALES_EXECUTIVE -> UserRole.SALES_EXECUTIVE
        INVENTORY_MANAGER -> UserRole.INVENTORY_MANAGER
        READ_ONLY -> UserRole.READ_ONLY
    }

    companion object {
        fun fromLegacyUserRole(role: UserRole): EmployeeAccessRole = when (role) {
            UserRole.ADMIN -> ADMIN
            UserRole.COMMERCIAL_MANAGER -> COMMERCIAL_MANAGER
            UserRole.RESIDENTIAL_MANAGER -> RESIDENTIAL_MANAGER
            UserRole.SALES_EXECUTIVE -> SALES_EXECUTIVE
            UserRole.INVENTORY_MANAGER -> INVENTORY_MANAGER
            UserRole.READ_ONLY, UserRole.BROKER, UserRole.OWNER -> READ_ONLY
        }

        fun fromString(raw: String?): EmployeeAccessRole =
            entries.firstOrNull {
                it.name.equals(raw?.trim(), ignoreCase = true) ||
                    it.label.equals(raw?.trim(), ignoreCase = true)
            } ?: READ_ONLY
    }
}

enum class BusinessDivisionScope(val label: String) {
    ASCEND_COMMERCIAL("Commercial"),
    AUREA_RESIDENTIAL("Residential");

    fun matchesPortfolio(portfolio: StandardPortfolio?): Boolean = when (this) {
        ASCEND_COMMERCIAL -> portfolio == StandardPortfolio.COMMERCIAL
        AUREA_RESIDENTIAL -> portfolio == StandardPortfolio.RESIDENTIAL
    }

    companion object {
        fun fromString(raw: String?): BusinessDivisionScope? {
            val clean = raw?.trim() ?: return null
            return entries.firstOrNull {
                it.name.equals(clean, ignoreCase = true) ||
                    it.label.equals(clean, ignoreCase = true) ||
                    (it == ASCEND_COMMERCIAL && (clean.equals("Ascend Commercial", ignoreCase = true) || clean.equals("COMMERCIAL", ignoreCase = true))) ||
                    (it == AUREA_RESIDENTIAL && (clean.equals("Aurea Living", ignoreCase = true) || clean.equals("AUREA_LIVING", ignoreCase = true) || clean.equals("RESIDENTIAL", ignoreCase = true)))
            }
        }
    }
}

enum class ActiveBusinessView(val label: String) {
    ASCEND_COMMERCIAL("Commercial"),
    AUREA_RESIDENTIAL("Residential"),
    ALL_BUSINESSES("All");

    fun toActiveBrand(): ActiveBrand = when (this) {
        ASCEND_COMMERCIAL -> ActiveBrand.ASCEND_COMMERCIAL
        AUREA_RESIDENTIAL -> ActiveBrand.AUREA_LIVING
        ALL_BUSINESSES -> ActiveBrand.ALL_BUSINESSES
    }

    fun toBrandFilter(): Brand? = when (this) {
        ASCEND_COMMERCIAL -> Brand.COMMERCIAL
        AUREA_RESIDENTIAL -> Brand.RESIDENTIAL
        ALL_BUSINESSES -> null
    }

    companion object {
        fun fromActiveBrand(brand: ActiveBrand?): ActiveBusinessView = when (brand) {
            ActiveBrand.ASCEND_COMMERCIAL, null -> ASCEND_COMMERCIAL
            ActiveBrand.AUREA_LIVING -> AUREA_RESIDENTIAL
            ActiveBrand.ALL_BUSINESSES -> ALL_BUSINESSES
        }

        fun fromString(raw: String?): ActiveBusinessView {
            val clean = raw?.trim() ?: return ASCEND_COMMERCIAL
            return entries.firstOrNull {
                it.name.equals(clean, ignoreCase = true) ||
                    it.label.equals(clean, ignoreCase = true) ||
                    (it == ASCEND_COMMERCIAL && (clean.equals("Ascend Commercial", ignoreCase = true) || clean.equals("COMMERCIAL", ignoreCase = true))) ||
                    (it == AUREA_RESIDENTIAL && (clean.equals("Aurea Living", ignoreCase = true) || clean.equals("AUREA_LIVING", ignoreCase = true) || clean.equals("RESIDENTIAL", ignoreCase = true))) ||
                    (it == ALL_BUSINESSES && (clean.equals("All Businesses", ignoreCase = true) || clean.equals("ALL_AUTHORIZED", ignoreCase = true) || clean.equals("ALL", ignoreCase = true)))
            } ?: ASCEND_COMMERCIAL
        }
    }
}

enum class EmployeeAccessStatus(val label: String) {
    PENDING("Pending"),
    ACTIVE("Active"),
    SUSPENDED("Suspended")
}

enum class EmployeeIdentityStatus(val label: String) {
    LOCAL_PROFILE("Local Profile"),
    VERIFIED_ACCOUNT("Verified Account")
}

enum class AuthorizationSource(val label: String) {
    NONE("None"),
    TRUSTED_PROVISIONING("Trusted Provisioning"),
    AUTHENTICATED_BACKEND("Authenticated Backend")
}

enum class AuditResult {
    SUCCESS,
    DENIED,
    FAILED
}

data class EmployeeProfile(
    val employeeId: String,
    val employeeName: String,
    val loginMobile: String,
    val loginEmail: String? = null,
    val role: EmployeeAccessRole = EmployeeAccessRole.READ_ONLY,
    val businessScopes: List<BusinessDivisionScope> = emptyList(),
    val reportsToEmployeeId: String? = null,
    val jobTitle: String? = null,
    val isActive: Boolean = false,
    val accessStatus: EmployeeAccessStatus = EmployeeAccessStatus.PENDING,
    val identityStatus: EmployeeIdentityStatus = EmployeeIdentityStatus.LOCAL_PROFILE,
    val authorizationSource: AuthorizationSource = AuthorizationSource.NONE,
    val activeBusinessView: ActiveBusinessView = ActiveBusinessView.ASCEND_COMMERCIAL,
    val pinEnabled: Boolean = false,
    val pinCredentialRef: String? = null,
    val pinFailedAttempts: Int = 0,
    val pinLockedUntil: Long? = null,
    val approvedDeskContacts: List<String> = APPROVED_DESK_CONTACTS,
    val recordVersion: Int = 1
) {
    val displayEmail: String
        get() = loginEmail?.trim()?.takeIf { it.isNotBlank() } ?: NOT_MENTIONED

    val displayJobTitle: String
        get() = jobTitle?.trim()?.takeIf { it.isNotBlank() } ?: NOT_MENTIONED

    fun hasConfidentialAccess(): Boolean =
        isActive &&
            accessStatus == EmployeeAccessStatus.ACTIVE &&
            role != EmployeeAccessRole.READ_ONLY

    fun effectiveAllowedDivisions(): Set<BusinessDivisionScope> {
        if (!hasConfidentialAccess()) return emptySet()
        return when (role) {
            // Core team (Super Admin, Admin, Managers, Sales Executives) all see 100% of Commercial & Residential
            EmployeeAccessRole.ADMIN,
            EmployeeAccessRole.COMMERCIAL_MANAGER,
            EmployeeAccessRole.RESIDENTIAL_MANAGER,
            EmployeeAccessRole.INVENTORY_MANAGER,
            EmployeeAccessRole.SALES_EXECUTIVE -> BusinessDivisionScope.entries.toSet()
            EmployeeAccessRole.READ_ONLY -> emptySet()
        }
    }

    fun availableBusinessViews(): List<ActiveBusinessView> {
        val allowed = effectiveAllowedDivisions()
        if (allowed.isEmpty()) return emptyList()
        val views = mutableListOf<ActiveBusinessView>()
        if (BusinessDivisionScope.ASCEND_COMMERCIAL in allowed) {
            views.add(ActiveBusinessView.ASCEND_COMMERCIAL)
        }
        if (BusinessDivisionScope.AUREA_RESIDENTIAL in allowed) {
            views.add(ActiveBusinessView.AUREA_RESIDENTIAL)
        }
        if (role == EmployeeAccessRole.ADMIN || allowed.size == BusinessDivisionScope.entries.size) {
            views.add(ActiveBusinessView.ALL_BUSINESSES)
        }
        return views
    }

    fun canSwitchToView(view: ActiveBusinessView): Boolean =
        view in availableBusinessViews()

    fun normalizedDefaultView(): ActiveBusinessView {
        val allowed = availableBusinessViews()
        return when {
            ActiveBusinessView.ALL_BUSINESSES in allowed -> ActiveBusinessView.ALL_BUSINESSES
            activeBusinessView in allowed -> activeBusinessView
            allowed.isNotEmpty() -> allowed.first()
            else -> ActiveBusinessView.ASCEND_COMMERCIAL
        }
    }

    fun canAccessPortfolio(portfolio: StandardPortfolio?): Boolean {
        if (!hasConfidentialAccess()) return false
        val allowedDivisions = effectiveAllowedDivisions()
        if (allowedDivisions.isEmpty()) return false
        return allowedDivisions.any { it.matchesPortfolio(portfolio) }
    }

    fun isPinLockedOut(nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = pinLockedUntil ?: return false
        return until > nowMs
    }

    fun remainingLockoutSeconds(nowMs: Long = System.currentTimeMillis()): Long {
        val until = pinLockedUntil ?: return 0L
        return ((until - nowMs).coerceAtLeast(0L) + 999L) / 1000L
    }

    fun toLegacyUserProfile(): UserProfile = UserProfile(
        username = employeeName,
        mobileNumber = PhoneNormalizer.tenDigitMobile(loginMobile) ?: loginMobile,
        email = loginEmail.orEmpty(),
        role = if (hasConfidentialAccess()) role.toLegacyUserRole() else UserRole.READ_ONLY
    )
}

data class AuditEventContract(
    val eventId: String,
    val actorEmployeeId: String,
    val actorRole: EmployeeAccessRole,
    val action: String,
    val entityId: String,
    val businessDivision: String,
    val occurredAt: String = Instant.now().toString(),
    val result: AuditResult
)

data class EmployeeSessionState(
    val isInitialized: Boolean = false,
    val currentEmployee: EmployeeProfile? = null,
    val isSignedIn: Boolean = false,
    val isPinLocked: Boolean = false,
    val activeBusinessView: ActiveBusinessView = ActiveBusinessView.ASCEND_COMMERCIAL,
    val inventoryViewMode: String = "LIST",
    val teamMembers: List<EmployeeProfile> = emptyList(),
    val recentAuditEvents: List<AuditEventContract> = emptyList(),
    val lastErrorMessage: String? = null,
    val lastInfoMessage: String? = null
) {
    val canAccessWorkspace: Boolean
        get() = isSignedIn &&
            !isPinLocked &&
            currentEmployee?.hasConfidentialAccess() == true
}

data class AccessActionResult(
    val success: Boolean,
    val message: String,
    val employee: EmployeeProfile? = null,
    val conflictDetected: Boolean = false
)

object PhoneNormalizer {
    private val indianTenDigitRegex = Regex("^[6-9][0-9]{9}$")

    fun normalizeIndianMobile(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace(Regex("[\\s\\-().+]"), "")
        val tenDigits = when {
            cleaned.length == 10 -> cleaned
            cleaned.length == 11 && cleaned.startsWith("0") -> cleaned.substring(1)
            cleaned.length == 12 && cleaned.startsWith("91") -> cleaned.substring(2)
            cleaned.length == 13 && cleaned.startsWith("091") -> cleaned.substring(3)
            else -> return null
        }
        return if (indianTenDigitRegex.matches(tenDigits)) "+91$tenDigits" else null
    }

    fun tenDigitMobile(raw: String?): String? =
        normalizeIndianMobile(raw)?.removePrefix("+91")
}

object PinSecurityHelper {
    private val fourDigitRegex = Regex("^\\d{4}$")
    private val secureRandom = SecureRandom()

    fun isValidFourDigitPin(pin: String): Boolean = fourDigitRegex.matches(pin)

    fun createCredentialRef(pin: String): String {
        require(isValidFourDigitPin(pin)) { "PIN must be exactly 4 digits" }
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = sha256Hex("$saltHex:$pin")
        return "v1:$saltHex:$hashHex"
    }

    fun verifyPin(pin: String, credentialRef: String?): Boolean {
        if (!isValidFourDigitPin(pin) || credentialRef.isNullOrBlank()) return false
        val parts = credentialRef.split(":")
        if (parts.size != 3 || parts[0] != "v1") return false
        val saltHex = parts[1]
        val expectedHash = parts[2]
        val candidateHash = sha256Hex("$saltHex:$pin")
        return MessageDigest.isEqual(
            expectedHash.toByteArray(Charsets.UTF_8),
            candidateHash.toByteArray(Charsets.UTF_8)
        )
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
