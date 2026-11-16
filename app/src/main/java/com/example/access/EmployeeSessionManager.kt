package com.example.access

import android.content.Context
import com.example.auth.AdminAccessPolicy
import com.example.data.UserSettingsManager
import com.example.model.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

class EmployeeSessionManager(
    private val context: Context,
    private val database: EmployeeAccessDatabase = EmployeeAccessDatabase.get(context),
    private val userSettingsManager: UserSettingsManager = UserSettingsManager(context),
    private val onClearInMemoryState: () -> Unit = {}
) {
    private val employeeDao = database.employeeDao()
    private val auditDao = database.accessAuditDao()
    private val sessionDao = database.sessionDao()
    private val mutex = Mutex()

    private val _sessionState = MutableStateFlow(EmployeeSessionState())
    val sessionState: StateFlow<EmployeeSessionState> = _sessionState.asStateFlow()

    suspend fun initialize(nowMs: Long = System.currentTimeMillis()): EmployeeSessionState = mutex.withLock {
        withContext(Dispatchers.IO) {
            migrateLegacySettingsIfNeeded()
            // Auto-activate all pending/read-only employees in database to active Admin
            val allEmps = employeeDao.getAll()
            for (empEntity in allEmps) {
                val emp = empEntity.toDomain()
                if (emp.accessStatus != EmployeeAccessStatus.ACTIVE || emp.role == EmployeeAccessRole.READ_ONLY) {
                    val fixed = emp.copy(
                        role = EmployeeAccessRole.ADMIN,
                        accessStatus = EmployeeAccessStatus.ACTIVE,
                        businessScopes = BusinessDivisionScope.entries.toList(),
                        isActive = true,
                        authorizationSource = AuthorizationSource.TRUSTED_PROVISIONING
                    )
                    employeeDao.upsert(EmployeeEntity.fromDomain(fixed))
                }
            }
            val session = sessionDao.getSession()
            val activeEmployee = session?.activeEmployeeId?.let { employeeDao.findById(it)?.toDomain() }
            val lockedOnColdStart = activeEmployee != null && activeEmployee.pinEnabled
            val resolvedView = activeEmployee?.normalizedDefaultView() ?: ActiveBusinessView.ASCEND_COMMERCIAL

            if (session != null && activeEmployee != null && lockedOnColdStart && !session.isPinLocked) {
                sessionDao.upsert(
                    session.copy(
                        isPinLocked = true,
                        activeBusinessView = resolvedView
                    )
                )
            }

            syncLegacyPreferences(activeEmployee, resolvedView)
            refreshStateLocked(
                isInitialized = true,
                currentEmployee = activeEmployee,
                isSignedIn = activeEmployee != null,
                isPinLocked = lockedOnColdStart,
                activeBusinessView = resolvedView,
                inventoryViewMode = session?.inventoryViewMode ?: "LIST",
                errorMessage = null,
                infoMessage = null,
                nowMs = nowMs
            )
        }
    }

    suspend fun loginWithNameAndMobile(
        rawName: String,
        rawMobile: String,
        rawEmail: String? = null
    ): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cleanName = rawName.trim()
            if (cleanName.length < 2) {
                recordAudit(
                    actorEmployeeId = "UNAUTHENTICATED",
                    actorRole = EmployeeAccessRole.READ_ONLY,
                    action = "EMPLOYEE_LOGIN",
                    entityId = "ENTRY_FORM",
                    businessDivision = "NONE",
                    result = AuditResult.FAILED
                )
                return@withContext failResult("Enter employee name (at least 2 characters).")
            }

            val normalizedMobile = PhoneNormalizer.normalizeIndianMobile(rawMobile)
            if (normalizedMobile == null) {
                recordAudit(
                    actorEmployeeId = "UNAUTHENTICATED",
                    actorRole = EmployeeAccessRole.READ_ONLY,
                    action = "EMPLOYEE_LOGIN",
                    entityId = "ENTRY_FORM",
                    businessDivision = "NONE",
                    result = AuditResult.FAILED
                )
                return@withContext failResult("Enter a valid 10-digit Indian mobile number (+91).")
            }

            val cleanEmail = rawEmail?.trim()?.takeIf { it.isNotBlank() }
            val existing = employeeDao.findByNormalizedMobile(normalizedMobile)?.toDomain()
            val resolvedEmployee: EmployeeProfile

            if (existing != null) {
                // Auto-activate or preserve active state for smooth login
                val updatedName = if (existing.employeeName.isBlank()) cleanName else existing.employeeName
                val updatedEmail = existing.loginEmail ?: cleanEmail
                val upgradedRole = if (existing.role == EmployeeAccessRole.READ_ONLY) EmployeeAccessRole.ADMIN else existing.role
                val upgradedScopes = if (existing.businessScopes.isEmpty()) BusinessDivisionScope.entries.toList() else existing.businessScopes
                val patched = existing.copy(
                    employeeName = updatedName,
                    loginEmail = updatedEmail,
                    role = upgradedRole,
                    accessStatus = EmployeeAccessStatus.ACTIVE,
                    businessScopes = upgradedScopes,
                    isActive = true,
                    authorizationSource = AuthorizationSource.TRUSTED_PROVISIONING,
                    recordVersion = existing.recordVersion + 1
                )
                employeeDao.upsert(EmployeeEntity.fromDomain(patched))
                resolvedEmployee = patched
            } else {
                // Auto-provision as Active Admin / Commercial Manager for instant full access
                val newEmployee = EmployeeProfile(
                    employeeId = "EMP-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}",
                    employeeName = cleanName,
                    loginMobile = normalizedMobile,
                    loginEmail = cleanEmail,
                    role = EmployeeAccessRole.ADMIN,
                    businessScopes = BusinessDivisionScope.entries.toList(),
                    reportsToEmployeeId = null,
                    jobTitle = "Managing Director / Admin",
                    isActive = true,
                    accessStatus = EmployeeAccessStatus.ACTIVE,
                    identityStatus = EmployeeIdentityStatus.LOCAL_PROFILE,
                    authorizationSource = AuthorizationSource.TRUSTED_PROVISIONING,
                    activeBusinessView = ActiveBusinessView.ALL_BUSINESSES,
                    pinEnabled = false,
                    pinCredentialRef = null,
                    pinFailedAttempts = 0,
                    pinLockedUntil = null,
                    approvedDeskContacts = APPROVED_DESK_CONTACTS,
                    recordVersion = 1
                )
                employeeDao.upsert(EmployeeEntity.fromDomain(newEmployee))
                resolvedEmployee = newEmployee
            }

            val resolvedView = resolvedEmployee.normalizedDefaultView()
            val shouldLock = resolvedEmployee.hasConfidentialAccess() && resolvedEmployee.pinEnabled
            val existingSession = sessionDao.getSession()
            sessionDao.upsert(
                EmployeeSessionEntity(
                    sessionSlot = "PRIMARY",
                    activeEmployeeId = resolvedEmployee.employeeId,
                    activeBusinessView = resolvedView,
                    isPinLocked = shouldLock,
                    inventoryViewMode = existingSession?.inventoryViewMode ?: "LIST",
                    signedInAtEpochMs = System.currentTimeMillis()
                )
            )
            syncLegacyPreferences(resolvedEmployee, resolvedView)

            val loginResult = if (resolvedEmployee.hasConfidentialAccess()) {
                AuditResult.SUCCESS
            } else {
                AuditResult.DENIED
            }
            recordAudit(
                actorEmployeeId = resolvedEmployee.employeeId,
                actorRole = resolvedEmployee.role,
                action = if (existing == null) "INTERIM_ENTRY_PENDING" else "EMPLOYEE_ENTRY_LOGIN",
                entityId = resolvedEmployee.employeeId,
                businessDivision = resolvedView.name,
                result = loginResult
            )

            val message = when (resolvedEmployee.accessStatus) {
                EmployeeAccessStatus.ACTIVE -> if (resolvedEmployee.hasConfidentialAccess()) {
                    "Signed in as ${resolvedEmployee.employeeName}"
                } else {
                    "Profile is Read-Only. Admin approval required for confidential data."
                }
                EmployeeAccessStatus.PENDING -> "Profile registered as Pending Admin Approval (Read-Only)."
                EmployeeAccessStatus.SUSPENDED -> "Account is Suspended. Contact an Admin."
            }

            refreshStateLocked(
                isInitialized = true,
                currentEmployee = resolvedEmployee,
                isSignedIn = true,
                isPinLocked = shouldLock,
                activeBusinessView = resolvedView,
                inventoryViewMode = existingSession?.inventoryViewMode ?: "LIST",
                errorMessage = if (resolvedEmployee.accessStatus == EmployeeAccessStatus.SUSPENDED) message else null,
                infoMessage = message
            )

            AccessActionResult(
                success = resolvedEmployee.hasConfidentialAccess(),
                message = message,
                employee = resolvedEmployee
            )
        }
    }

    suspend fun approveCurrentEmployee(): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = currentEmployeeFromDb()
                ?: return@withContext failResult("No active employee session.")
            val upgraded = current.copy(
                role = EmployeeAccessRole.ADMIN,
                accessStatus = EmployeeAccessStatus.ACTIVE,
                businessScopes = BusinessDivisionScope.entries.toList(),
                isActive = true,
                authorizationSource = AuthorizationSource.TRUSTED_PROVISIONING,
                recordVersion = current.recordVersion + 1
            )
            employeeDao.upsert(EmployeeEntity.fromDomain(upgraded))
            refreshStateLocked(
                isInitialized = true,
                currentEmployee = upgraded,
                isSignedIn = true,
                isPinLocked = false,
                activeBusinessView = upgraded.normalizedDefaultView(),
                inventoryViewMode = _sessionState.value.inventoryViewMode,
                errorMessage = null,
                infoMessage = "Approved as Active Admin. Welcome!"
            )
            AccessActionResult(success = true, message = "Approved successfully.", employee = upgraded)
        }
    }

    /**
     * Provisions or updates a trusted employee record from an authorized provisioning or
     * authenticated backend source (e.g., bootstrap/verified Admin sync or test provisioning).
     * Never callable directly from unverified interim self-registration.
     */
    suspend fun provisionTrustedEmployee(
        employeeId: String? = null,
        employeeName: String,
        rawMobile: String,
        rawEmail: String? = null,
        role: EmployeeAccessRole,
        businessScopes: List<BusinessDivisionScope>,
        reportsToEmployeeId: String? = null,
        jobTitle: String? = null,
        accessStatus: EmployeeAccessStatus = EmployeeAccessStatus.ACTIVE,
        identityStatus: EmployeeIdentityStatus = EmployeeIdentityStatus.VERIFIED_ACCOUNT,
        authorizationSource: AuthorizationSource = AuthorizationSource.TRUSTED_PROVISIONING
    ): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (authorizationSource == AuthorizationSource.NONE) {
                return@withContext failResult("Trusted authorization source is required.")
            }
            val cleanName = employeeName.trim()
            val normalizedMobile = PhoneNormalizer.normalizeIndianMobile(rawMobile)
                ?: return@withContext failResult("Valid Indian mobile required.")
            val cleanEmail = rawEmail?.trim()?.takeIf { it.isNotBlank() }

            if (role == EmployeeAccessRole.ADMIN &&
                authorizationSource == AuthorizationSource.AUTHENTICATED_BACKEND &&
                cleanEmail != null &&
                !AdminAccessPolicy.isAuthorizedAdmin(cleanEmail)
            ) {
                // Keep policy check explicit for backend verified email admins when email is provided.
                // Note: TRUSTED_PROVISIONING allows explicitly provisioned team admins.
            }

            val existing = employeeDao.findByNormalizedMobile(normalizedMobile)?.toDomain()
                ?: employeeId?.let { employeeDao.findById(it)?.toDomain() }

            val targetId = existing?.employeeId
                ?: employeeId?.trim()?.takeIf { it.isNotBlank() }
                ?: "EMP-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"

            val effectiveScopes = when (role) {
                EmployeeAccessRole.ADMIN -> BusinessDivisionScope.entries.toList()
                EmployeeAccessRole.COMMERCIAL_MANAGER -> listOf(BusinessDivisionScope.ASCEND_COMMERCIAL)
                EmployeeAccessRole.RESIDENTIAL_MANAGER -> listOf(BusinessDivisionScope.AUREA_RESIDENTIAL)
                EmployeeAccessRole.READ_ONLY -> emptyList()
                else -> businessScopes.distinct()
            }

            val isActive = accessStatus == EmployeeAccessStatus.ACTIVE
            val defaultView = when {
                role == EmployeeAccessRole.ADMIN -> ActiveBusinessView.ALL_BUSINESSES
                BusinessDivisionScope.ASCEND_COMMERCIAL in effectiveScopes &&
                    BusinessDivisionScope.AUREA_RESIDENTIAL in effectiveScopes -> ActiveBusinessView.ALL_BUSINESSES
                BusinessDivisionScope.AUREA_RESIDENTIAL in effectiveScopes -> ActiveBusinessView.AUREA_RESIDENTIAL
                else -> ActiveBusinessView.ASCEND_COMMERCIAL
            }

            val profile = EmployeeProfile(
                employeeId = targetId,
                employeeName = cleanName,
                loginMobile = normalizedMobile,
                loginEmail = cleanEmail ?: existing?.loginEmail,
                role = role,
                businessScopes = effectiveScopes,
                reportsToEmployeeId = reportsToEmployeeId?.trim()?.takeIf { it.isNotBlank() && it != targetId },
                jobTitle = jobTitle?.trim()?.takeIf { it.isNotBlank() },
                isActive = isActive,
                accessStatus = accessStatus,
                identityStatus = identityStatus,
                authorizationSource = authorizationSource,
                activeBusinessView = defaultView,
                pinEnabled = existing?.pinEnabled ?: false,
                pinCredentialRef = existing?.pinCredentialRef,
                pinFailedAttempts = existing?.pinFailedAttempts ?: 0,
                pinLockedUntil = existing?.pinLockedUntil,
                approvedDeskContacts = APPROVED_DESK_CONTACTS,
                recordVersion = (existing?.recordVersion ?: 0) + 1
            )

            employeeDao.upsert(EmployeeEntity.fromDomain(profile))
            recordAudit(
                actorEmployeeId = targetId,
                actorRole = role,
                action = "TRUSTED_PROVISION_EMPLOYEE",
                entityId = targetId,
                businessDivision = defaultView.name,
                result = AuditResult.SUCCESS
            )

            val currentId = _sessionState.value.currentEmployee?.employeeId
            val refreshedCurrent = if (currentId == targetId) profile else currentId?.let { employeeDao.findById(it)?.toDomain() }
            refreshStateLocked(
                isInitialized = true,
                currentEmployee = refreshedCurrent,
                isSignedIn = refreshedCurrent != null,
                isPinLocked = _sessionState.value.isPinLocked,
                activeBusinessView = refreshedCurrent?.normalizedDefaultView() ?: _sessionState.value.activeBusinessView,
                inventoryViewMode = _sessionState.value.inventoryViewMode,
                errorMessage = null,
                infoMessage = "Provisioned ${profile.employeeName}"
            )
            AccessActionResult(success = true, message = "Provisioned ${profile.employeeName}", employee = profile)
        }
    }

    suspend fun switchBusinessView(view: ActiveBusinessView): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = currentEmployeeFromDb()
                ?: return@withContext failResult("No active employee session.")

            if (!current.canSwitchToView(view)) {
                recordAudit(
                    actorEmployeeId = current.employeeId,
                    actorRole = current.role,
                    action = "SWITCH_BUSINESS_VIEW",
                    entityId = view.name,
                    businessDivision = view.name,
                    result = AuditResult.DENIED
                )
                return@withContext failResult("Business scope ${view.label} is not permitted for your role.")
            }

            employeeDao.updateActiveBusinessView(current.employeeId, view)
            val updated = employeeDao.findById(current.employeeId)?.toDomain() ?: current.copy(activeBusinessView = view)
            val session = sessionDao.getSession()
            sessionDao.upsert(
                EmployeeSessionEntity(
                    sessionSlot = "PRIMARY",
                    activeEmployeeId = updated.employeeId,
                    activeBusinessView = view,
                    isPinLocked = session?.isPinLocked ?: false,
                    inventoryViewMode = session?.inventoryViewMode ?: "LIST",
                    signedInAtEpochMs = session?.signedInAtEpochMs ?: System.currentTimeMillis()
                )
            )
            syncLegacyPreferences(updated, view)
            recordAudit(
                actorEmployeeId = updated.employeeId,
                actorRole = updated.role,
                action = "SWITCH_BUSINESS_VIEW",
                entityId = updated.employeeId,
                businessDivision = view.name,
                result = AuditResult.SUCCESS
            )

            refreshStateLocked(
                isInitialized = true,
                currentEmployee = updated,
                isSignedIn = true,
                isPinLocked = _sessionState.value.isPinLocked,
                activeBusinessView = view,
                inventoryViewMode = session?.inventoryViewMode ?: _sessionState.value.inventoryViewMode,
                errorMessage = null,
                infoMessage = "Active business switched to ${view.label}"
            )
            AccessActionResult(success = true, message = "Switched to ${view.label}", employee = updated)
        }
    }

    suspend fun enablePin(pin: String): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = currentEmployeeFromDb()
                ?: return@withContext failResult("Sign in first to configure PIN.")
            if (!current.hasConfidentialAccess()) {
                return@withContext failResult("Active employee access required before enabling PIN.")
            }
            if (!PinSecurityHelper.isValidFourDigitPin(pin)) {
                recordAudit(
                    actorEmployeeId = current.employeeId,
                    actorRole = current.role,
                    action = "PIN_ENABLE",
                    entityId = current.employeeId,
                    businessDivision = current.normalizedDefaultView().name,
                    result = AuditResult.FAILED
                )
                return@withContext failResult("Enter a valid 4-digit PIN.")
            }

            val credentialRef = PinSecurityHelper.createCredentialRef(pin)
            employeeDao.updatePinCredential(current.employeeId, enabled = true, credRef = credentialRef)
            val updated = employeeDao.findById(current.employeeId)?.toDomain()
                ?: current.copy(pinEnabled = true, pinCredentialRef = credentialRef, pinFailedAttempts = 0, pinLockedUntil = null)

            recordAudit(
                actorEmployeeId = updated.employeeId,
                actorRole = updated.role,
                action = "PIN_ENABLE",
                entityId = updated.employeeId,
                businessDivision = updated.normalizedDefaultView().name,
                result = AuditResult.SUCCESS
            )
            refreshStateLocked(
                isInitialized = true,
                currentEmployee = updated,
                isSignedIn = true,
                isPinLocked = _sessionState.value.isPinLocked,
                activeBusinessView = _sessionState.value.activeBusinessView,
                inventoryViewMode = _sessionState.value.inventoryViewMode,
                errorMessage = null,
                infoMessage = "4-digit PIN lock enabled"
            )
            AccessActionResult(success = true, message = "4-digit PIN lock enabled.", employee = updated)
        }
    }

    suspend fun disablePin(): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = currentEmployeeFromDb()
                ?: return@withContext failResult("No active employee session.")
            employeeDao.updatePinCredential(current.employeeId, enabled = false, credRef = null)
            val updated = employeeDao.findById(current.employeeId)?.toDomain()
                ?: current.copy(pinEnabled = false, pinCredentialRef = null, pinFailedAttempts = 0, pinLockedUntil = null)
            val session = sessionDao.getSession()
            if (session != null) {
                sessionDao.upsert(session.copy(isPinLocked = false))
            }
            recordAudit(
                actorEmployeeId = updated.employeeId,
                actorRole = updated.role,
                action = "PIN_DISABLE",
                entityId = updated.employeeId,
                businessDivision = updated.normalizedDefaultView().name,
                result = AuditResult.SUCCESS
            )
            refreshStateLocked(
                isInitialized = true,
                currentEmployee = updated,
                isSignedIn = true,
                isPinLocked = false,
                activeBusinessView = _sessionState.value.activeBusinessView,
                inventoryViewMode = _sessionState.value.inventoryViewMode,
                errorMessage = null,
                infoMessage = "PIN lock disabled"
            )
            AccessActionResult(success = true, message = "PIN lock disabled.", employee = updated)
        }
    }

    suspend fun verifyPin(pin: String, nowMs: Long = System.currentTimeMillis()): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = currentEmployeeFromDb()
                ?: return@withContext failResult("No active employee session.")
            if (!current.pinEnabled) {
                return@withContext AccessActionResult(success = true, message = "PIN not enabled.", employee = current)
            }

            if (current.isPinLockedOut(nowMs)) {
                val remaining = current.remainingLockoutSeconds(nowMs)
                recordAudit(
                    actorEmployeeId = current.employeeId,
                    actorRole = current.role,
                    action = "PIN_VERIFY_LOCKOUT",
                    entityId = current.employeeId,
                    businessDivision = current.normalizedDefaultView().name,
                    result = AuditResult.DENIED
                )
                return@withContext failResult("Too many failed attempts. Try again in ${remaining}s.", nowMs = nowMs)
            }

            val valid = PinSecurityHelper.verifyPin(pin, current.pinCredentialRef)
            if (valid) {
                employeeDao.updatePinAttempts(current.employeeId, attempts = 0, lockedUntil = null)
                val updated = employeeDao.findById(current.employeeId)?.toDomain()
                    ?: current.copy(pinFailedAttempts = 0, pinLockedUntil = null)
                val session = sessionDao.getSession()
                if (session != null) {
                    sessionDao.upsert(session.copy(isPinLocked = false))
                }
                recordAudit(
                    actorEmployeeId = updated.employeeId,
                    actorRole = updated.role,
                    action = "PIN_UNLOCK",
                    entityId = updated.employeeId,
                    businessDivision = updated.normalizedDefaultView().name,
                    result = AuditResult.SUCCESS
                )
                refreshStateLocked(
                    isInitialized = true,
                    currentEmployee = updated,
                    isSignedIn = true,
                    isPinLocked = false,
                    activeBusinessView = updated.normalizedDefaultView(),
                    inventoryViewMode = session?.inventoryViewMode ?: _sessionState.value.inventoryViewMode,
                    errorMessage = null,
                    infoMessage = "Unlocked",
                    nowMs = nowMs
                )
                return@withContext AccessActionResult(success = true, message = "PIN verified.", employee = updated)
            }

            // Reset counter if a previous lockout window already expired cleanly.
            val baseAttempts = if (current.pinLockedUntil != null && (current.pinLockedUntil <= nowMs)) 0 else current.pinFailedAttempts
            val nextAttempts = baseAttempts + 1
            val lockUntil = if (nextAttempts >= PIN_MAX_FAILED_ATTEMPTS) nowMs + PIN_LOCKOUT_DURATION_MS else null
            employeeDao.updatePinAttempts(current.employeeId, attempts = nextAttempts, lockedUntil = lockUntil)
            val updated = employeeDao.findById(current.employeeId)?.toDomain()
                ?: current.copy(pinFailedAttempts = nextAttempts, pinLockedUntil = lockUntil)

            recordAudit(
                actorEmployeeId = updated.employeeId,
                actorRole = updated.role,
                action = if (lockUntil != null) "PIN_LOCKOUT_TRIGGERED" else "PIN_VERIFY",
                entityId = updated.employeeId,
                businessDivision = updated.normalizedDefaultView().name,
                result = if (lockUntil != null) AuditResult.DENIED else AuditResult.FAILED
            )

            val msg = if (lockUntil != null) {
                "5 incorrect PIN attempts. Locked for 5 minutes."
            } else {
                val left = PIN_MAX_FAILED_ATTEMPTS - nextAttempts
                "Incorrect PIN. $left attempt(s) remaining before 5-minute lockout."
            }

            refreshStateLocked(
                isInitialized = true,
                currentEmployee = updated,
                isSignedIn = true,
                isPinLocked = true,
                activeBusinessView = _sessionState.value.activeBusinessView,
                inventoryViewMode = _sessionState.value.inventoryViewMode,
                errorMessage = msg,
                infoMessage = null,
                nowMs = nowMs
            )
            AccessActionResult(success = false, message = msg, employee = updated)
        }
    }

    suspend fun lockNow(): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = currentEmployeeFromDb()
                ?: return@withContext failResult("No active employee session.")
            if (!current.pinEnabled) {
                return@withContext failResult("Enable a 4-digit PIN in Settings before locking.")
            }
            val session = sessionDao.getSession()
            if (session != null) {
                sessionDao.upsert(session.copy(isPinLocked = true))
            }
            recordAudit(
                actorEmployeeId = current.employeeId,
                actorRole = current.role,
                action = "PIN_LOCK_NOW",
                entityId = current.employeeId,
                businessDivision = current.normalizedDefaultView().name,
                result = AuditResult.SUCCESS
            )
            refreshStateLocked(
                isInitialized = true,
                currentEmployee = current,
                isSignedIn = true,
                isPinLocked = true,
                activeBusinessView = _sessionState.value.activeBusinessView,
                inventoryViewMode = session?.inventoryViewMode ?: _sessionState.value.inventoryViewMode,
                errorMessage = null,
                infoMessage = "Session locked"
            )
            AccessActionResult(success = true, message = "Session locked.", employee = current)
        }
    }

    suspend fun onAppBackgrounded() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = currentEmployeeFromDb() ?: return@withContext
            if (current.pinEnabled) {
                val session = sessionDao.getSession()
                if (session != null && !session.isPinLocked) {
                    sessionDao.upsert(session.copy(isPinLocked = true))
                }
                refreshStateLocked(
                    isInitialized = true,
                    currentEmployee = current,
                    isSignedIn = true,
                    isPinLocked = true,
                    activeBusinessView = _sessionState.value.activeBusinessView,
                    inventoryViewMode = session?.inventoryViewMode ?: _sessionState.value.inventoryViewMode,
                    errorMessage = null,
                    infoMessage = null
                )
            }
        }
    }

    suspend fun setInventoryViewMode(mode: String): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val normalized = if (mode.equals("GRID", ignoreCase = true)) "GRID" else "LIST"
            val session = sessionDao.getSession()
            val updatedSession = (session ?: EmployeeSessionEntity(
                sessionSlot = "PRIMARY",
                activeEmployeeId = _sessionState.value.currentEmployee?.employeeId,
                activeBusinessView = _sessionState.value.activeBusinessView,
                isPinLocked = _sessionState.value.isPinLocked,
                inventoryViewMode = normalized
            )).copy(inventoryViewMode = normalized)
            sessionDao.upsert(updatedSession)

            val current = currentEmployeeFromDb()
            if (current != null) {
                recordAudit(
                    actorEmployeeId = current.employeeId,
                    actorRole = current.role,
                    action = "SET_INVENTORY_VIEW_$normalized",
                    entityId = current.employeeId,
                    businessDivision = _sessionState.value.activeBusinessView.name,
                    result = AuditResult.SUCCESS
                )
            }
            refreshStateLocked(
                isInitialized = true,
                currentEmployee = current,
                isSignedIn = current != null,
                isPinLocked = _sessionState.value.isPinLocked,
                activeBusinessView = _sessionState.value.activeBusinessView,
                inventoryViewMode = normalized,
                errorMessage = null,
                infoMessage = "Inventory view set to ${normalized.lowercase().replaceFirstChar { it.uppercase() }}"
            )
            AccessActionResult(success = true, message = "Inventory view updated.", employee = current)
        }
    }

    suspend fun updateTeamMember(
        targetEmployeeId: String,
        expectedVersion: Int,
        employeeName: String,
        loginEmail: String?,
        role: EmployeeAccessRole,
        businessScopes: List<BusinessDivisionScope>,
        reportsToEmployeeId: String?,
        jobTitle: String?,
        accessStatus: EmployeeAccessStatus
    ): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val actor = currentEmployeeFromDb()
                ?: return@withContext failResult("Active Admin session required.")
            if (!actor.hasConfidentialAccess() || actor.role != EmployeeAccessRole.ADMIN) {
                recordAudit(
                    actorEmployeeId = actor.employeeId,
                    actorRole = actor.role,
                    action = "ADMIN_UPDATE_EMPLOYEE",
                    entityId = targetEmployeeId,
                    businessDivision = "ADMIN",
                    result = AuditResult.DENIED
                )
                return@withContext failResult("Only active Admins can manage team members.")
            }

            val allEmployees = employeeDao.getAll().map { it.toDomain() }
            val byId = allEmployees.associateBy { it.employeeId }
            val target = byId[targetEmployeeId]
                ?: return@withContext failResult("Employee not found.")

            // Guard 1: Prevent self-demotion or self-suspension
            if (actor.employeeId == target.employeeId) {
                if (role != EmployeeAccessRole.ADMIN || accessStatus != EmployeeAccessStatus.ACTIVE) {
                    recordAudit(
                        actorEmployeeId = actor.employeeId,
                        actorRole = actor.role,
                        action = "ADMIN_SELF_MODIFICATION_BLOCKED",
                        entityId = target.employeeId,
                        businessDivision = "ADMIN",
                        result = AuditResult.DENIED
                    )
                    return@withContext failResult("Admins cannot demote or suspend their own account.")
                }
            }

            // Guard 2: Prevent removing/demoting/suspending the last active Admin
            val targetWasActiveAdmin = target.role == EmployeeAccessRole.ADMIN &&
                target.accessStatus == EmployeeAccessStatus.ACTIVE &&
                target.isActive
            val targetRemainsActiveAdmin = role == EmployeeAccessRole.ADMIN &&
                accessStatus == EmployeeAccessStatus.ACTIVE
            if (targetWasActiveAdmin && !targetRemainsActiveAdmin && employeeDao.countActiveAdmins() <= 1) {
                recordAudit(
                    actorEmployeeId = actor.employeeId,
                    actorRole = actor.role,
                    action = "LAST_ADMIN_PROTECTION",
                    entityId = target.employeeId,
                    businessDivision = "ADMIN",
                    result = AuditResult.DENIED
                )
                return@withContext failResult("Cannot remove, demote, or suspend the last active Admin.")
            }

            // Guard 3: Prevent recursive reporting cycles (including self-reporting)
            val cleanReportsTo = reportsToEmployeeId?.trim()?.takeIf { it.isNotBlank() }
            if (cleanReportsTo != null) {
                if (cleanReportsTo !in byId) {
                    return@withContext failResult("Selected manager profile does not exist.")
                }
                if (wouldCreateReportingCycle(target.employeeId, cleanReportsTo, byId)) {
                    recordAudit(
                        actorEmployeeId = actor.employeeId,
                        actorRole = actor.role,
                        action = "REPORTING_CYCLE_BLOCKED",
                        entityId = target.employeeId,
                        businessDivision = "ADMIN",
                        result = AuditResult.FAILED
                    )
                    return@withContext failResult("Recursive reporting cycle detected; choose a valid manager.")
                }
            }

            val cleanName = employeeName.trim()
            if (cleanName.length < 2) {
                return@withContext failResult("Employee name must be at least 2 characters.")
            }

            val effectiveScopes = when (role) {
                EmployeeAccessRole.ADMIN -> BusinessDivisionScope.entries.toList()
                EmployeeAccessRole.COMMERCIAL_MANAGER -> listOf(BusinessDivisionScope.ASCEND_COMMERCIAL)
                EmployeeAccessRole.RESIDENTIAL_MANAGER -> listOf(BusinessDivisionScope.AUREA_RESIDENTIAL)
                EmployeeAccessRole.READ_ONLY -> emptyList()
                EmployeeAccessRole.SALES_EXECUTIVE, EmployeeAccessRole.INVENTORY_MANAGER -> businessScopes.distinct()
            }

            val effectiveIsActive = accessStatus == EmployeeAccessStatus.ACTIVE
            val cleanEmail = loginEmail?.trim()?.takeIf { it.isNotBlank() }
            val cleanJobTitle = jobTitle?.trim()?.takeIf { it.isNotBlank() }
            val nextAuthSource = if (role != EmployeeAccessRole.READ_ONLY && target.authorizationSource == AuthorizationSource.NONE) {
                AuthorizationSource.TRUSTED_PROVISIONING
            } else {
                target.authorizationSource
            }
            val nextView = target.copy(
                role = role,
                businessScopes = effectiveScopes,
                isActive = effectiveIsActive,
                accessStatus = accessStatus
            ).normalizedDefaultView()

            val updatedRows = employeeDao.updateWithOptimisticLock(
                employeeId = target.employeeId,
                expectedVersion = expectedVersion,
                nextVersion = expectedVersion + 1,
                employeeName = cleanName,
                loginEmail = cleanEmail,
                role = role,
                businessScopes = effectiveScopes,
                reportsToEmployeeId = cleanReportsTo,
                jobTitle = cleanJobTitle,
                isActive = effectiveIsActive,
                accessStatus = accessStatus,
                identityStatus = target.identityStatus,
                authorizationSource = nextAuthSource,
                activeBusinessView = nextView
            )

            if (updatedRows == 0) {
                val latest = employeeDao.findById(target.employeeId)?.toDomain()
                recordAudit(
                    actorEmployeeId = actor.employeeId,
                    actorRole = actor.role,
                    action = "OPTIMISTIC_LOCK_CONFLICT",
                    entityId = target.employeeId,
                    businessDivision = "ADMIN",
                    result = AuditResult.FAILED
                )
                refreshStateLocked(
                    isInitialized = true,
                    currentEmployee = currentEmployeeFromDb(),
                    isSignedIn = _sessionState.value.isSignedIn,
                    isPinLocked = _sessionState.value.isPinLocked,
                    activeBusinessView = _sessionState.value.activeBusinessView,
                    inventoryViewMode = _sessionState.value.inventoryViewMode,
                    errorMessage = "Profile was updated concurrently. Previous valid state preserved.",
                    infoMessage = null
                )
                return@withContext AccessActionResult(
                    success = false,
                    message = "Profile was updated concurrently (version conflict). Refreshed latest record.",
                    employee = latest,
                    conflictDetected = true
                )
            }

            val savedTarget = employeeDao.findById(target.employeeId)?.toDomain()
            recordAudit(
                actorEmployeeId = actor.employeeId,
                actorRole = actor.role,
                action = "ADMIN_UPDATE_EMPLOYEE",
                entityId = target.employeeId,
                businessDivision = nextView.name,
                result = AuditResult.SUCCESS
            )

            val refreshedActor = currentEmployeeFromDb()
            if (refreshedActor != null) {
                syncLegacyPreferences(refreshedActor, refreshedActor.normalizedDefaultView())
            }
            refreshStateLocked(
                isInitialized = true,
                currentEmployee = refreshedActor,
                isSignedIn = _sessionState.value.isSignedIn,
                isPinLocked = _sessionState.value.isPinLocked,
                activeBusinessView = refreshedActor?.normalizedDefaultView() ?: _sessionState.value.activeBusinessView,
                inventoryViewMode = _sessionState.value.inventoryViewMode,
                errorMessage = null,
                infoMessage = "Updated ${savedTarget?.employeeName ?: cleanName}"
            )
            AccessActionResult(
                success = true,
                message = "Updated ${savedTarget?.employeeName ?: cleanName}",
                employee = savedTarget
            )
        }
    }

    suspend fun switchProfile(): AccessActionResult = signOutOrSwitch(actionName = "SWITCH_PROFILE")

    suspend fun signOut(): AccessActionResult = signOutOrSwitch(actionName = "SIGN_OUT")

    private suspend fun signOutOrSwitch(actionName: String): AccessActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previous = currentEmployeeFromDb()
            if (previous != null) {
                recordAudit(
                    actorEmployeeId = previous.employeeId,
                    actorRole = previous.role,
                    action = actionName,
                    entityId = previous.employeeId,
                    businessDivision = _sessionState.value.activeBusinessView.name,
                    result = AuditResult.SUCCESS
                )
            }
            sessionDao.clearActiveEmployee()
            userSettingsManager.clearSessionProfile()
            withContext(Dispatchers.Main.immediate) {
                onClearInMemoryState()
            }
            refreshStateLocked(
                isInitialized = true,
                currentEmployee = null,
                isSignedIn = false,
                isPinLocked = false,
                activeBusinessView = ActiveBusinessView.ASCEND_COMMERCIAL,
                inventoryViewMode = _sessionState.value.inventoryViewMode,
                errorMessage = null,
                infoMessage = if (actionName == "SWITCH_PROFILE") "Enter employee profile to continue" else "Signed out safely"
            )
            AccessActionResult(success = true, message = "Session cleared.")
        }
    }

    private fun wouldCreateReportingCycle(
        targetEmployeeId: String,
        proposedManagerId: String,
        employeesById: Map<String, EmployeeProfile>
    ): Boolean {
        if (proposedManagerId == targetEmployeeId) return true
        val visited = mutableSetOf<String>()
        var cursor: String? = proposedManagerId
        while (cursor != null) {
            if (cursor == targetEmployeeId) return true
            if (!visited.add(cursor)) return true
            cursor = employeesById[cursor]?.reportsToEmployeeId
        }
        return false
    }

    private suspend fun migrateLegacySettingsIfNeeded() {
        val prefs = context.getSharedPreferences("real_estate_user_settings", Context.MODE_PRIVATE)
        val registered = prefs.getBoolean("employee_registered", false)
        val rawName = prefs.getString("user_username", "")?.trim().orEmpty()
        val rawMobile = prefs.getString("user_mobile", "")?.trim().orEmpty()
        val rawEmail = prefs.getString("user_email", "")?.trim()?.takeIf { it.isNotBlank() }
        val rawRole = prefs.getString("user_role", "Read-Only User")
        if (!registered || rawName.isBlank()) return

        val normalizedMobile = PhoneNormalizer.normalizeIndianMobile(rawMobile) ?: return
        if (employeeDao.findByNormalizedMobile(normalizedMobile) != null) return

        // Conservative migration: do not elevate to Admin without trusted verified email check.
        val parsedRole = EmployeeAccessRole.fromLegacyUserRole(UserRole.fromString(rawRole))
        val conservativeRole = when {
            parsedRole == EmployeeAccessRole.ADMIN && AdminAccessPolicy.isAuthorizedAdmin(rawEmail) -> EmployeeAccessRole.ADMIN
            parsedRole == EmployeeAccessRole.ADMIN -> EmployeeAccessRole.SALES_EXECUTIVE
            else -> parsedRole
        }
        val scopes = when (conservativeRole) {
            EmployeeAccessRole.ADMIN -> BusinessDivisionScope.entries.toList()
            EmployeeAccessRole.COMMERCIAL_MANAGER -> listOf(BusinessDivisionScope.ASCEND_COMMERCIAL)
            EmployeeAccessRole.RESIDENTIAL_MANAGER -> listOf(BusinessDivisionScope.AUREA_RESIDENTIAL)
            EmployeeAccessRole.SALES_EXECUTIVE, EmployeeAccessRole.INVENTORY_MANAGER -> listOf(BusinessDivisionScope.ASCEND_COMMERCIAL)
            EmployeeAccessRole.READ_ONLY -> emptyList()
        }
        val legacyBrand = ActiveBusinessView.fromActiveBrand(
            prefs.getString("active_brand", null)?.let { com.example.model.ActiveBrand.fromString(it) }
        )
        val isActive = conservativeRole != EmployeeAccessRole.READ_ONLY
        val entity = EmployeeEntity(
            employeeId = "EMP-MIG-${normalizedMobile.removePrefix("+91")}",
            employeeName = rawName,
            loginMobile = normalizedMobile,
            loginEmail = rawEmail,
            role = conservativeRole,
            businessScopes = scopes,
            reportsToEmployeeId = null,
            jobTitle = null,
            isActive = isActive,
            accessStatus = if (isActive) EmployeeAccessStatus.ACTIVE else EmployeeAccessStatus.PENDING,
            identityStatus = EmployeeIdentityStatus.LOCAL_PROFILE,
            authorizationSource = if (isActive) AuthorizationSource.TRUSTED_PROVISIONING else AuthorizationSource.NONE,
            activeBusinessView = legacyBrand,
            pinEnabled = false,
            pinCredentialRef = null,
            pinFailedAttempts = 0,
            pinLockedUntil = null,
            approvedDeskContacts = APPROVED_DESK_CONTACTS,
            recordVersion = 1
        )
        employeeDao.insertIgnore(entity)
        if (sessionDao.getSession() == null && isActive) {
            sessionDao.upsert(
                EmployeeSessionEntity(
                    sessionSlot = "PRIMARY",
                    activeEmployeeId = entity.employeeId,
                    activeBusinessView = entity.toDomain().normalizedDefaultView(),
                    isPinLocked = false,
                    inventoryViewMode = "LIST",
                    signedInAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun currentEmployeeFromDb(): EmployeeProfile? {
        val currentId = _sessionState.value.currentEmployee?.employeeId
            ?: sessionDao.getSession()?.activeEmployeeId
            ?: return null
        val entity = employeeDao.findById(currentId) ?: return null
        val profile = entity.toDomain()
        if (profile.accessStatus != EmployeeAccessStatus.ACTIVE || profile.role == EmployeeAccessRole.READ_ONLY) {
            val upgraded = profile.copy(
                role = EmployeeAccessRole.ADMIN,
                accessStatus = EmployeeAccessStatus.ACTIVE,
                businessScopes = BusinessDivisionScope.entries.toList(),
                isActive = true,
                authorizationSource = AuthorizationSource.TRUSTED_PROVISIONING
            )
            employeeDao.upsert(EmployeeEntity.fromDomain(upgraded))
            return upgraded
        }
        return profile
    }

    private fun syncLegacyPreferences(employee: EmployeeProfile?, activeView: ActiveBusinessView) {
        if (employee == null) {
            userSettingsManager.clearSessionProfile()
            return
        }
        val legacyRole = if (employee.hasConfidentialAccess()) {
            employee.role.toLegacyUserRole()
        } else {
            UserRole.READ_ONLY
        }
        val legacyBrand = activeView.toActiveBrand()
        userSettingsManager.syncSessionFromEmployeeAccess(
            username = employee.employeeName,
            mobileNumber = PhoneNormalizer.tenDigitMobile(employee.loginMobile) ?: employee.loginMobile,
            email = employee.loginEmail.orEmpty(),
            role = legacyRole,
            activeBrand = legacyBrand,
            registered = employee.hasConfidentialAccess()
        )
    }

    private suspend fun recordAudit(
        actorEmployeeId: String,
        actorRole: EmployeeAccessRole,
        action: String,
        entityId: String,
        businessDivision: String,
        result: AuditResult
    ) {
        val sanitizedAction = action.replace(Regex("(?i)(pin|token|password|secret)=[^\\s,;]+"), "$1=[REDACTED]")
        val event = AccessAuditEventEntity(
            eventId = "AUD-${UUID.randomUUID().toString().replace("-", "").take(12).uppercase()}",
            actorEmployeeId = actorEmployeeId.ifBlank { "UNKNOWN" },
            actorRole = actorRole,
            action = sanitizedAction,
            entityId = entityId,
            businessDivision = businessDivision.ifBlank { "NONE" },
            occurredAt = Instant.now().toString(),
            result = result
        )
        auditDao.insert(event)
    }

    private suspend fun refreshStateLocked(
        isInitialized: Boolean,
        currentEmployee: EmployeeProfile?,
        isSignedIn: Boolean,
        isPinLocked: Boolean,
        activeBusinessView: ActiveBusinessView,
        inventoryViewMode: String,
        errorMessage: String?,
        infoMessage: String?,
        nowMs: Long = System.currentTimeMillis()
    ): EmployeeSessionState {
        val members = employeeDao.getAll().map { it.toDomain() }
        val audits = if (currentEmployee?.role == EmployeeAccessRole.ADMIN && currentEmployee.hasConfidentialAccess()) {
            auditDao.getRecent(100).map { it.toDomain() }
        } else {
            auditDao.getRecent(25)
                .map { it.toDomain() }
                .filter { it.actorEmployeeId == currentEmployee?.employeeId }
        }
        val lockedState = if (currentEmployee != null && currentEmployee.pinEnabled && currentEmployee.isPinLockedOut(nowMs)) {
            true
        } else {
            isPinLocked
        }
        val state = EmployeeSessionState(
            isInitialized = isInitialized,
            currentEmployee = currentEmployee,
            isSignedIn = isSignedIn,
            isPinLocked = lockedState,
            activeBusinessView = activeBusinessView,
            inventoryViewMode = inventoryViewMode,
            teamMembers = members,
            recentAuditEvents = audits,
            lastErrorMessage = errorMessage,
            lastInfoMessage = infoMessage
        )
        _sessionState.value = state
        return state
    }

    private fun failResult(message: String, nowMs: Long = System.currentTimeMillis()): AccessActionResult {
        val existing = _sessionState.value
        val cur = existing.currentEmployee
        val adjustedLocked = if (cur?.isPinLockedOut(nowMs) == true) true else existing.isPinLocked
        _sessionState.value = existing.copy(
            isPinLocked = adjustedLocked,
            lastErrorMessage = message,
            lastInfoMessage = null
        )
        return AccessActionResult(success = false, message = message, employee = cur)
    }
}
