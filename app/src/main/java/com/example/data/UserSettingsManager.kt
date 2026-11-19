package com.example.data

import android.content.Context
import android.net.Uri
import com.example.model.BrandLogos
import com.example.model.CompanyProfile
import com.example.model.UserProfile
import com.example.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import com.example.model.ActiveBrand
import com.example.ui.theme.TlcBusinessDivisionToken
import com.example.ui.theme.TlcBusinessViewToken
import com.example.ui.theme.TlcThemeController
import com.example.ui.theme.TlcThemeMode
import com.example.ui.theme.TlcThemePreferenceContract
import com.example.notifications.VocalAlertMode

class UserSettingsManager(private val context: Context?) {

    private val prefs = context?.getSharedPreferences("ascend_aurea_user_settings", Context.MODE_PRIVATE)
    private val legacyPrefs = context?.getSharedPreferences("real_estate_user_settings", Context.MODE_PRIVATE)

    private val _isLoggedIn = MutableStateFlow(prefs?.getBoolean("is_logged_in", false) ?: false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    // Separate explicit employee identity from legacy profile defaults.
    private val _employeeRegistered = MutableStateFlow(
        prefs?.getBoolean("employee_registered", false)
            ?: legacyPrefs?.getBoolean("employee_registered", false)
            ?: false
    )
    val employeeRegistered: StateFlow<Boolean> = _employeeRegistered.asStateFlow()

    private val _themePreferenceOwnerId = MutableStateFlow(
        prefs?.getString("theme_preference_owner_id", null)
            ?: legacyPrefs?.getString("theme_preference_owner_id", null)
    )
    val themePreferenceOwnerId: StateFlow<String?> = _themePreferenceOwnerId.asStateFlow()

    private val _themeMode = MutableStateFlow(loadThemeMode(_themePreferenceOwnerId.value)).also {
        TlcThemeController.updateThemeMode(it.value)
    }
    val themeMode: StateFlow<TlcThemeMode> = _themeMode.asStateFlow()

    fun registerEmployee(name: String, phone: String) {
        require(name.trim().length >= 2 && phone.matches(Regex("[6-9][0-9]{9}"))) { "Valid name and 10-digit Indian mobile required" }
        prefs?.edit()?.putBoolean("employee_registered", true)?.apply()
        legacyPrefs?.edit()?.putBoolean("employee_registered", true)?.apply()
        persistProfile(name.trim(), phone, _userProfile.value.email, UserRole.SALES_EXECUTIVE)
        _employeeRegistered.value = true
    }

    private val _userProfile = MutableStateFlow(loadProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _activeBrand = MutableStateFlow(loadActiveBrand())
    val activeBrand: StateFlow<ActiveBrand> = _activeBrand.asStateFlow()

    val pinConfigured: Boolean
        get() = !(prefs?.getString("employee_pin_code", "").orEmpty().isBlank())

    private val _brandLogos = MutableStateFlow(loadLogos())
    val brandLogos: StateFlow<BrandLogos> = _brandLogos.asStateFlow()

    private val _commercialCompanyProfile = MutableStateFlow(loadCommercialProfile())
    val commercialCompanyProfile: StateFlow<CompanyProfile> = _commercialCompanyProfile.asStateFlow()

    private val _residentialCompanyProfile = MutableStateFlow(loadResidentialProfile())
    val residentialCompanyProfile: StateFlow<CompanyProfile> = _residentialCompanyProfile.asStateFlow()

    private val _verificationValidityDays = MutableStateFlow(prefs?.getInt("verification_validity_days", 60)?.takeIf { it in setOf(7, 15, 30, 60) } ?: 60)
    val verificationValidityDays: StateFlow<Int> = _verificationValidityDays.asStateFlow()

    private val _autoSaveGoogleContacts = MutableStateFlow(prefs?.getBoolean("auto_save_google_contacts", true) ?: true)
    val autoSaveGoogleContacts: StateFlow<Boolean> = _autoSaveGoogleContacts.asStateFlow()

    private val _revealConfidentialPhonesForExec = MutableStateFlow(prefs?.getBoolean("reveal_confidential_phones", false) ?: false)
    val revealConfidentialPhonesForExec: StateFlow<Boolean> = _revealConfidentialPhonesForExec.asStateFlow()

    private val _vocalAlertMode = MutableStateFlow(
        VocalAlertMode.fromString(prefs?.getString(KEY_VOCAL_ALERT_MODE, VocalAlertMode.BLUETOOTH_DRIVING_ONLY.name))
    )
    val vocalAlertMode: StateFlow<VocalAlertMode> = _vocalAlertMode.asStateFlow()

    fun setVocalAlertMode(mode: VocalAlertMode) {
        _vocalAlertMode.value = mode
        prefs?.edit()?.putString(KEY_VOCAL_ALERT_MODE, mode.name)?.apply()
    }

    fun setAutoSaveGoogleContacts(enabled: Boolean) {
        _autoSaveGoogleContacts.value = enabled
        prefs?.edit()?.putBoolean("auto_save_google_contacts", enabled)?.apply()
    }

    fun setRevealConfidentialPhonesForExec(reveal: Boolean) {
        _revealConfidentialPhonesForExec.value = reveal
        prefs?.edit()?.putBoolean("reveal_confidential_phones", reveal)?.apply()
    }

    fun auditIdentity(): String = userProfile.value.let { "${it.username.ifBlank { "Unregistered" }} | ${it.role.name}" }

    private fun loadThemeMode(ownerId: String?): TlcThemeMode {
        if (prefs == null) return TlcThemeMode.LIGHT
        return try {
            val schemaVer = prefs.getInt("theme_schema_version", 1)
            if (schemaVer != 1) return TlcThemeMode.LIGHT
            val ownerKey = if (!ownerId.isNullOrBlank()) "theme_mode_${ownerId.trim()}" else "theme_mode_global"
            val raw = prefs.getString(ownerKey, null) ?: prefs.getString("theme_mode_global", TlcThemeMode.LIGHT.name)
            TlcThemeMode.fromString(raw)
        } catch (_: Exception) {
            TlcThemeMode.LIGHT
        }
    }

    fun setThemeMode(mode: TlcThemeMode, employeeId: String? = _themePreferenceOwnerId.value) {
        val cleanOwner = employeeId?.trim()?.takeIf { it.isNotBlank() }
        _themePreferenceOwnerId.value = cleanOwner
        _themeMode.value = mode
        TlcThemeController.updateThemeMode(mode)
        try {
            prefs?.edit()?.apply {
                putInt("theme_schema_version", 1)
                putString("theme_mode_global", mode.name)
                if (cleanOwner != null) {
                    putString("theme_preference_owner_id", cleanOwner)
                    putString("theme_mode_$cleanOwner", mode.name)
                }
                apply()
            }
        } catch (_: Exception) {
            // Keep app usable in memory even if preference write fails
        }
    }

    fun syncThemeForEmployee(employeeId: String?) {
        val cleanOwner = employeeId?.trim()?.takeIf { it.isNotBlank() }
        _themePreferenceOwnerId.value = cleanOwner
        val resolved = loadThemeMode(cleanOwner)
        _themeMode.value = resolved
        TlcThemeController.updateThemeMode(resolved)
        try {
            prefs?.edit()?.apply {
                if (cleanOwner != null) putString("theme_preference_owner_id", cleanOwner)
                apply()
            }
        } catch (_: Exception) {
            // Ignore write failure
        }
    }

    fun currentThemeContract(): TlcThemePreferenceContract {
        val division = when (_activeBrand.value) {
            ActiveBrand.AUREA_LIVING -> TlcBusinessDivisionToken.RESIDENTIAL
            else -> TlcBusinessDivisionToken.COMMERCIAL
        }
        val view = when (_activeBrand.value) {
            ActiveBrand.ASCEND_COMMERCIAL -> TlcBusinessViewToken.COMMERCIAL
            ActiveBrand.AUREA_LIVING -> TlcBusinessViewToken.RESIDENTIAL
            ActiveBrand.ALL_BUSINESSES -> TlcBusinessViewToken.ALL_AUTHORIZED
        }
        return TlcThemePreferenceContract(
            appDisplayName = "The Leasing Company",
            businessDivision = division,
            businessView = view,
            themeMode = _themeMode.value,
            themePreferenceOwnerId = _themePreferenceOwnerId.value,
            logoAssetRef = "tlc_brand_monogram_v1",
            themeSchemaVersion = 1
        )
    }

    private fun loadActiveBrand(): ActiveBrand {
        val stored = prefs?.getString("active_brand", null) ?: legacyPrefs?.getString("active_brand", null)
        val profile = userProfile.value
        return when {
            profile.role == UserRole.ADMIN || profile.username.equals("Pawan Patil", ignoreCase = true) ->
                if (stored != null) ActiveBrand.fromString(stored) else ActiveBrand.ALL_BUSINESSES
            profile.role == UserRole.COMMERCIAL_MANAGER -> ActiveBrand.ASCEND_COMMERCIAL
            profile.role == UserRole.RESIDENTIAL_MANAGER -> ActiveBrand.AUREA_LIVING
            profile.division.contains("Commercial", ignoreCase = true) &&
                !profile.division.contains("Residential", ignoreCase = true) -> ActiveBrand.ASCEND_COMMERCIAL
            profile.division.contains("Residential", ignoreCase = true) &&
                !profile.division.contains("Commercial", ignoreCase = true) -> ActiveBrand.AUREA_LIVING
            else -> ActiveBrand.fromString(stored)
        }
    }

    fun canSwitchBrand(): Boolean =
        userProfile.value.role == UserRole.ADMIN ||
            userProfile.value.username.equals("Pawan Patil", ignoreCase = true)

    fun setActiveBrand(brand: ActiveBrand): Boolean {
        if (!canSwitchBrand() && brand != loadActiveBrand()) return false
        _activeBrand.value = brand
        prefs?.edit()?.putString("active_brand", brand.name)?.apply()
        legacyPrefs?.edit()?.putString("active_brand", brand.name)?.apply()
        return true
    }

    fun setEmployeePin(pin: String): Boolean {
        if (!pin.matches(Regex("\\d{4}"))) return false
        prefs?.edit()?.putString("employee_pin_code", hashPin(pin))?.apply()
        return true
    }

    fun verifyEmployeePin(pin: String): Boolean {
        if (!pin.matches(Regex("\\d{4}"))) return false
        val stored = prefs?.getString("employee_pin_code", "").orEmpty()
        return stored.isBlank() || stored == hashPin(pin)
    }

    fun clearEmployeePin() {
        prefs?.edit()?.remove("employee_pin_code")?.apply()
    }

    private fun hashPin(pin: String): String = MessageDigest.getInstance("SHA-256")
        .digest(pin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun updateVerificationValidityDays(days: Int) {
        require(days in setOf(7, 15, 30, 60)) { "Choose 7, 15, 30 or 60 days" }
        _verificationValidityDays.value = days
        prefs?.edit()?.putInt("verification_validity_days", days)?.apply()
    }

    private fun loadProfile(): UserProfile {
        if (prefs == null && legacyPrefs == null) return UserProfile()
        val loggedIn = prefs?.getBoolean("is_logged_in", false) == true
        val primary = if (loggedIn) prefs else (legacyPrefs ?: prefs)
        val fallback = if (loggedIn) legacyPrefs else prefs
        val username = primary?.getString("user_username", null)
            ?: fallback?.getString("user_username", "") ?: ""
        val mobile = primary?.getString("user_mobile", null)
            ?: fallback?.getString("user_mobile", "") ?: ""
        val email = primary?.getString("user_email", null)
            ?: fallback?.getString("user_email", "") ?: ""
        val roleStr = primary?.getString("user_role", null)
            ?: fallback?.getString("user_role", "Read-Only User") ?: "Read-Only User"
        val photoUri = primary?.getString("user_photo_uri", null)
            ?: fallback?.getString("user_photo_uri", null)
        val employeeId = primary?.getString("employee_id", null)
            ?: fallback?.getString("employee_id", "") ?: ""
        val division = primary?.getString("user_division", null)
            ?: fallback?.getString("user_division", "Commercial + Residential")
            ?: "Commercial + Residential"
        val accountStatus = primary?.getString("user_account_status", null)
            ?: fallback?.getString("user_account_status", "Active") ?: "Active"
        val roleEnum = UserRole.fromString(roleStr)
        val canView = primary?.getBoolean("can_view_owner", roleEnum == UserRole.ADMIN || roleEnum == UserRole.COMMERCIAL_MANAGER || roleEnum == UserRole.RESIDENTIAL_MANAGER || roleEnum == UserRole.INVENTORY_MANAGER) ?: true
        val canBulk = primary?.getBoolean("can_bulk_export", roleEnum == UserRole.ADMIN || roleEnum == UserRole.COMMERCIAL_MANAGER || roleEnum == UserRole.RESIDENTIAL_MANAGER || roleEnum == UserRole.INVENTORY_MANAGER) ?: true
        val canDel = primary?.getBoolean("can_delete", roleEnum == UserRole.ADMIN || roleEnum == UserRole.COMMERCIAL_MANAGER || roleEnum == UserRole.RESIDENTIAL_MANAGER) ?: true
        return UserProfile(
            username = username,
            mobileNumber = mobile,
            email = email,
            role = roleEnum,
            profilePhotoUri = photoUri,
            employeeId = employeeId,
            division = division,
            accountStatus = accountStatus,
            canViewFullOwnerContacts = canView,
            canBulkExport = canBulk,
            canDeleteRecords = canDel
        )
    }

    private fun loadLogos(): BrandLogos {
        if (prefs == null) return BrandLogos()
        val commercialUri = prefs.getString("brand_commercial_logo", null)
        val residentialUri = prefs.getString("brand_residential_logo", null)
        return BrandLogos(
            commercialLogoUri = commercialUri,
            residentialLogoUri = residentialUri
        )
    }

    private fun normalizeCompanyDisplayName(stored: String?, defaultTitle: String): String {
        val raw = stored?.trim().orEmpty()
        return if (raw.isBlank() ||
            raw.equals("COMMERCIAL PORTFOLIO", ignoreCase = true) ||
            raw.equals("RESIDENTIAL PORTFOLIO", ignoreCase = true) ||
            raw.contains("Ascend", ignoreCase = true) ||
            raw.contains("Aurea", ignoreCase = true)
        ) {
            defaultTitle
        } else {
            raw
        }
    }

    private fun loadCommercialProfile(): CompanyProfile {
        if (prefs == null) {
            return CompanyProfile(
                companyName = "The Leasing Company",
                contact1 = "9766880099",
                contact2 = "9766668099",
                portfolio = "Commercial",
                email = "commercial@theleasingcompany.in",
                officeAddress = "Kharadi, Pune, Maharashtra",
                whatsappFooter = "For private viewing and detailed brochure:\n📞 Call/WhatsApp: 9766880099 | 9766668099"
            )
        }
        return CompanyProfile(
            companyName = normalizeCompanyDisplayName(prefs.getString("commercial_name", null), "The Leasing Company"),
            contact1 = prefs.getString("commercial_contact1", "9766880099") ?: "9766880099",
            contact2 = prefs.getString("commercial_contact2", "9766668099") ?: "9766668099",
            portfolio = "Commercial",
            email = prefs.getString("commercial_email", "commercial@theleasingcompany.in") ?: "commercial@theleasingcompany.in",
            officeAddress = prefs.getString("commercial_address", "Kharadi, Pune, Maharashtra") ?: "Kharadi, Pune, Maharashtra",
            whatsappFooter = prefs.getString("commercial_footer", "For private viewing and detailed brochure:\n📞 Call/WhatsApp: 9766880099 | 9766668099") ?: "For private viewing and detailed brochure:\n📞 Call/WhatsApp: 9766880099 | 9766668099",
            logoUri = prefs.getString("brand_commercial_logo", null)
        )
    }

    private fun loadResidentialProfile(): CompanyProfile {
        if (prefs == null) {
            return CompanyProfile(
                companyName = "The Leasing Company",
                contact1 = "9766880099",
                contact2 = "9766668099",
                portfolio = "Residential",
                email = "residential@theleasingcompany.in",
                officeAddress = "Kharadi, Pune, Maharashtra",
                whatsappFooter = "For floor plans and builder inventory:\n📞 Call/WhatsApp: 9766880099 | 9766668099"
            )
        }
        return CompanyProfile(
            companyName = normalizeCompanyDisplayName(prefs.getString("residential_name", null), "The Leasing Company"),
            contact1 = prefs.getString("residential_contact1", "9766880099") ?: "9766880099",
            contact2 = prefs.getString("residential_contact2", "9766668099") ?: "9766668099",
            portfolio = "Residential",
            email = prefs.getString("residential_email", "residential@theleasingcompany.in") ?: "residential@theleasingcompany.in",
            officeAddress = prefs.getString("residential_address", "Kharadi, Pune, Maharashtra") ?: "Kharadi, Pune, Maharashtra",
            whatsappFooter = prefs.getString("residential_footer", "For floor plans and builder inventory:\n📞 Call/WhatsApp: 9766880099 | 9766668099") ?: "For floor plans and builder inventory:\n📞 Call/WhatsApp: 9766880099 | 9766668099",
            logoUri = prefs.getString("brand_residential_logo", null)
        )
    }

    // Editing profile text must never grant a different role.
    @Suppress("UNUSED_PARAMETER")
    fun updateProfile(username: String, mobileNumber: String, email: String, role: UserRole,
        photoUri: String? = _userProfile.value.profilePhotoUri) {
        persistProfile(username, mobileNumber, email, _userProfile.value.role, photoUri)
    }

    internal fun syncSessionFromEmployeeAccess(
        username: String,
        mobileNumber: String,
        email: String,
        role: UserRole,
        activeBrand: ActiveBrand,
        registered: Boolean
    ) {
        persistProfile(username, mobileNumber, email, role, _userProfile.value.profilePhotoUri)
        _activeBrand.value = activeBrand
        _employeeRegistered.value = registered
        prefs?.edit()?.apply {
            putString("active_brand", activeBrand.name)
            putBoolean("employee_registered", registered)
            apply()
        }
    }

    fun loginSuccess(profile: UserProfile) {
        val cleanProfile = profile.copy(
            username = profile.username.trim(),
            mobileNumber = profile.mobileNumber.trim(),
            email = profile.email.trim(),
            employeeId = profile.employeeId.trim(),
            division = profile.division.ifBlank { "Commercial + Residential" },
            accountStatus = profile.accountStatus.ifBlank { "Active" }
        )
        val resolvedBrand = when {
            cleanProfile.role == UserRole.ADMIN ||
                cleanProfile.username.equals("Pawan Patil", ignoreCase = true) ||
                (cleanProfile.division.contains("Commercial", ignoreCase = true) &&
                    cleanProfile.division.contains("Residential", ignoreCase = true)) -> ActiveBrand.ALL_BUSINESSES
            cleanProfile.division.contains("Residential", ignoreCase = true) -> ActiveBrand.AUREA_LIVING
            else -> ActiveBrand.ASCEND_COMMERCIAL
        }
        _userProfile.value = cleanProfile
        _activeBrand.value = resolvedBrand
        _isLoggedIn.value = true
        _employeeRegistered.value = true

        listOfNotNull(prefs, legacyPrefs).forEach { targetPrefs ->
            targetPrefs.edit().apply {
                putBoolean("is_logged_in", true)
                putBoolean("employee_registered", true)
                putString("employee_id", cleanProfile.employeeId)
                putString("user_username", cleanProfile.username)
                putString("user_mobile", cleanProfile.mobileNumber)
                putString("user_email", cleanProfile.email)
                putString("user_division", cleanProfile.division)
                putString("user_role", cleanProfile.role.label)
                putString("user_account_status", cleanProfile.accountStatus)
                putString("active_brand", resolvedBrand.name)
                putBoolean("can_view_owner", cleanProfile.canViewFullOwnerContacts)
                putBoolean("can_bulk_export", cleanProfile.canBulkExport)
                putBoolean("can_delete", cleanProfile.canDeleteRecords)
                if (cleanProfile.profilePhotoUri != null) {
                    putString("user_photo_uri", cleanProfile.profilePhotoUri)
                }
                apply()
            }
        }
    }

    fun logout() {
        _isLoggedIn.value = false
        _employeeRegistered.value = false
        _userProfile.value = UserProfile()
        _activeBrand.value = ActiveBrand.ASCEND_COMMERCIAL
        listOfNotNull(prefs, legacyPrefs).forEach { targetPrefs ->
            targetPrefs.edit().apply {
                putBoolean("is_logged_in", false)
                putBoolean("employee_registered", false)
                remove("employee_id")
                remove("user_username")
                remove("user_mobile")
                remove("user_email")
                remove("user_division")
                putString("user_role", UserRole.READ_ONLY.label)
                remove("user_account_status")
                putString("active_brand", ActiveBrand.ASCEND_COMMERCIAL.name)
                apply()
            }
        }
    }

    internal fun clearSessionProfile() {
        persistProfile("", "", "", UserRole.READ_ONLY, _userProfile.value.profilePhotoUri)
        _activeBrand.value = ActiveBrand.ASCEND_COMMERCIAL
        _employeeRegistered.value = false
        listOfNotNull(prefs, legacyPrefs).forEach { targetPrefs ->
            targetPrefs.edit().apply {
                putString("active_brand", ActiveBrand.ASCEND_COMMERCIAL.name)
                putBoolean("employee_registered", false)
                apply()
            }
        }
    }

    private fun persistProfile(
        username: String,
        mobileNumber: String,
        email: String,
        role: UserRole,
        photoUri: String? = _userProfile.value.profilePhotoUri
    ) {
        val current = _userProfile.value
        val updated = current.copy(
            username = username.trim(),
            mobileNumber = mobileNumber.trim(),
            email = email.trim(),
            role = role,
            profilePhotoUri = photoUri
        )
        _userProfile.value = updated
        listOfNotNull(prefs, legacyPrefs).forEach { targetPrefs ->
            targetPrefs.edit().apply {
                putString("user_username", updated.username)
                putString("user_mobile", updated.mobileNumber)
                putString("user_email", updated.email)
                putString("user_role", updated.role.label)
                putString("employee_id", updated.employeeId)
                putString("user_division", updated.division)
                putString("user_account_status", updated.accountStatus)
                if (photoUri != null) putString("user_photo_uri", photoUri) else remove("user_photo_uri")
                apply()
            }
        }
    }

    fun saveProfilePhoto(uri: Uri) {
        val localPath = copyUriToInternalStorage(uri, "user_photo")
        updateProfile(
            username = _userProfile.value.username,
            mobileNumber = _userProfile.value.mobileNumber,
            email = _userProfile.value.email,
            role = _userProfile.value.role,
            photoUri = localPath ?: uri.toString()
        )
    }

    fun updateCommercialCompanyProfile(
        name: String,
        contact1: String,
        contact2: String,
        email: String,
        address: String,
        footer: String
    ) {
        val updated = CompanyProfile(
            companyName = name.ifBlank { "COMMERCIAL PORTFOLIO" },
            contact1 = contact1.ifBlank { "9766880099" },
            contact2 = contact2.ifBlank { "9766668099" },
            portfolio = "Commercial",
            email = email,
            officeAddress = address,
            whatsappFooter = footer,
            logoUri = _brandLogos.value.commercialLogoUri
        )
        _commercialCompanyProfile.value = updated
        prefs?.edit()?.apply {
            putString("commercial_name", updated.companyName)
            putString("commercial_contact1", updated.contact1)
            putString("commercial_contact2", updated.contact2)
            putString("commercial_email", updated.email)
            putString("commercial_address", updated.officeAddress)
            putString("commercial_footer", updated.whatsappFooter)
            apply()
        }
    }

    fun updateResidentialCompanyProfile(
        name: String,
        contact1: String,
        contact2: String,
        email: String,
        address: String,
        footer: String
    ) {
        val updated = CompanyProfile(
            companyName = name.ifBlank { "RESIDENTIAL PORTFOLIO" },
            contact1 = contact1.ifBlank { "9766880099" },
            contact2 = contact2.ifBlank { "9766668099" },
            portfolio = "Residential",
            email = email,
            officeAddress = address,
            whatsappFooter = footer,
            logoUri = _brandLogos.value.residentialLogoUri
        )
        _residentialCompanyProfile.value = updated
        prefs?.edit()?.apply {
            putString("residential_name", updated.companyName)
            putString("residential_contact1", updated.contact1)
            putString("residential_contact2", updated.contact2)
            putString("residential_email", updated.email)
            putString("residential_address", updated.officeAddress)
            putString("residential_footer", updated.whatsappFooter)
            apply()
        }
    }

    fun saveCommercialLogo(uri: Uri) {
        val localPath = copyUriToInternalStorage(uri, "commercial_logo")
        val logoPath = localPath ?: uri.toString()
        val updated = _brandLogos.value.copy(commercialLogoUri = logoPath)
        _brandLogos.value = updated
        _commercialCompanyProfile.value = _commercialCompanyProfile.value.copy(logoUri = logoPath)
        prefs?.edit()?.putString("brand_commercial_logo", logoPath)?.apply()
    }

    fun saveResidentialLogo(uri: Uri) {
        val localPath = copyUriToInternalStorage(uri, "residential_logo")
        val logoPath = localPath ?: uri.toString()
        val updated = _brandLogos.value.copy(residentialLogoUri = logoPath)
        _brandLogos.value = updated
        _residentialCompanyProfile.value = _residentialCompanyProfile.value.copy(logoUri = logoPath)
        prefs?.edit()?.putString("brand_residential_logo", logoPath)?.apply()
    }

    fun resetCommercialLogo() {
        val updated = _brandLogos.value.copy(commercialLogoUri = null)
        _brandLogos.value = updated
        _commercialCompanyProfile.value = _commercialCompanyProfile.value.copy(logoUri = null)
        prefs?.edit()?.remove("brand_commercial_logo")?.apply()
    }

    fun resetResidentialLogo() {
        val updated = _brandLogos.value.copy(residentialLogoUri = null)
        _brandLogos.value = updated
        _residentialCompanyProfile.value = _residentialCompanyProfile.value.copy(logoUri = null)
        prefs?.edit()?.remove("brand_residential_logo")?.apply()
    }

    private fun copyUriToInternalStorage(uri: Uri, prefix: String): String? {
        if (context == null) return uri.toString()
        return try {
            val extension = context.contentResolver.getType(uri)?.substringAfterLast("/") ?: "png"
            val destFile = File(context.filesDir, "${prefix}_${System.currentTimeMillis()}.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            uri.toString()
        }
    }

    // ── Batch 20: Call Recording Folder (On-Demand Only — Never Scanned on Startup) ──
    private val _callRecordingFolderUri = MutableStateFlow(
        prefs?.getString("call_recording_folder_uri", "").orEmpty()
    )
    val callRecordingFolderUri: StateFlow<String> = _callRecordingFolderUri.asStateFlow()

    private val _callRecordingFolderLabel = MutableStateFlow(
        prefs?.getString("call_recording_folder_label", "Auto-detect OEM (Samsung / Xiaomi / OnePlus)").orEmpty()
    )
    val callRecordingFolderLabel: StateFlow<String> = _callRecordingFolderLabel.asStateFlow()

    fun setCallRecordingFolder(uriOrPath: String, label: String) {
        val cleanUri = uriOrPath.trim()
        val cleanLabel = label.trim().ifBlank { cleanUri.ifBlank { "Auto-detect OEM (Samsung / Xiaomi / OnePlus)" } }
        _callRecordingFolderUri.value = cleanUri
        _callRecordingFolderLabel.value = cleanLabel
        prefs?.edit()?.apply {
            putString("call_recording_folder_uri", cleanUri)
            putString("call_recording_folder_label", cleanLabel)
            apply()
        }
    }

    fun clearCallRecordingFolder() {
        setCallRecordingFolder("", "Auto-detect OEM (Samsung / Xiaomi / OnePlus)")
    }

    companion object {
        const val KEY_VOCAL_ALERT_MODE = "vocal_alert_mode"

        fun readVocalAlertMode(context: Context): VocalAlertMode {
            val prefs = context.applicationContext.getSharedPreferences("ascend_aurea_user_settings", Context.MODE_PRIVATE)
            return VocalAlertMode.fromString(prefs.getString(KEY_VOCAL_ALERT_MODE, VocalAlertMode.BLUETOOTH_DRIVING_ONLY.name))
        }

        fun readCurrentUserIdentifiers(context: Context): Set<String> {
            val prefs = context.applicationContext.getSharedPreferences("ascend_aurea_user_settings", Context.MODE_PRIVATE)
            val legacy = context.applicationContext.getSharedPreferences("real_estate_user_settings", Context.MODE_PRIVATE)
            val username = prefs.getString("user_username", null)
                ?: legacy.getString("user_username", "").orEmpty()
            val email = prefs.getString("user_email", null)
                ?: legacy.getString("user_email", "").orEmpty()
            val empId = prefs.getString("employee_id", null)
                ?: legacy.getString("employee_id", "").orEmpty()
            val ids = mutableSetOf<String>()
            fun addCleaned(raw: String) {
                val norm = raw.substringBefore("(").substringBefore("|").trim().lowercase()
                if (norm.isNotBlank()) {
                    ids.add(norm)
                    val first = norm.split(Regex("\\s+")).firstOrNull().orEmpty()
                    if (first.length >= 3) ids.add(first)
                }
            }
            addCleaned(username)
            addCleaned(email)
            addCleaned(empId)
            return ids
        }
    }
}




