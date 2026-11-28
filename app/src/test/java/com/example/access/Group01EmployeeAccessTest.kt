package com.example.access

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.SheetsRepository
import com.example.data.UserSettingsManager
import com.example.model.Brand
import com.example.model.BrandLogos
import com.example.model.InventoryTab
import com.example.model.PropertyItem
import com.example.ui.components.PortalTopBar
import com.example.ui.screens.EmployeeEntryScreen
import com.example.ui.screens.PinLockScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class Group01EmployeeAccessTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: EmployeeAccessDatabase
    private lateinit var settings: UserSettingsManager
    private var inMemoryClearedCount = 0

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("real_estate_user_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = EmployeeAccessDatabase.createInMemory(context)
        settings = UserSettingsManager(context)
        inMemoryClearedCount = 0
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newManager(): EmployeeSessionManager =
        EmployeeSessionManager(
            context = context,
            database = db,
            userSettingsManager = settings,
            onClearInMemoryState = { inMemoryClearedCount++ }
        )

    @Test
    fun indianMobileNormalizationAndUnrecognizedInterimEntryStayPendingReadOnly() = runBlocking {
        assertEquals("+919876543210", PhoneNormalizer.normalizeIndianMobile("+91 98765-43210"))
        assertEquals("+919876543210", PhoneNormalizer.normalizeIndianMobile("919876543210"))
        assertEquals("+919876543210", PhoneNormalizer.normalizeIndianMobile("09876543210"))
        assertEquals("+919876543210", PhoneNormalizer.normalizeIndianMobile("9876543210"))
        assertNull(PhoneNormalizer.normalizeIndianMobile("1234567890"))

        val manager = newManager()
        manager.initialize()

        val result = manager.loginWithNameAndMobile("Rohan Mehta", "098765 43210", null)
        assertFalse(result.success)
        val emp = result.employee!!
        assertEquals("+919876543210", emp.loginMobile)
        assertEquals(EmployeeAccessRole.READ_ONLY, emp.role)
        assertEquals(EmployeeAccessStatus.PENDING, emp.accessStatus)
        assertEquals(AuthorizationSource.NONE, emp.authorizationSource)
        assertFalse(emp.hasConfidentialAccess())
        assertEquals(NOT_MENTIONED, emp.displayEmail)
        assertEquals(NOT_MENTIONED, emp.displayJobTitle)
        assertEquals(APPROVED_DESK_CONTACTS, emp.approvedDeskContacts)

        // Conservative re-login preserves employeeId and does NOT elevate role
        val secondLogin = manager.loginWithNameAndMobile("Rohan Mehta", "+91-9876543210", "rohan@example.com")
        assertEquals(emp.employeeId, secondLogin.employee?.employeeId)
        assertEquals(EmployeeAccessRole.READ_ONLY, secondLogin.employee?.role)
        assertEquals(EmployeeAccessStatus.PENDING, secondLogin.employee?.accessStatus)
    }

    @Test
    fun businessScopeFilteringAcrossRepositoryAndRoleGuardsWorkStrictly() = runBlocking {
        com.example.data.PropertiesLocalDatabase.clearAll(context)
        val repo = SheetsRepository(context, startForegroundSync = false)
        repo.addProperty(
            PropertyItem(
                id = "COM-1",
                propertyCode = "COM-01",
                projectName = "World Trade Center",
                location = "Kharadi",
                tab = InventoryTab.OFF_LEASE,
                portfolio = "Ascend Commercial"
            )
        )
        repo.addProperty(
            PropertyItem(
                id = "RES-1",
                propertyCode = "RES-01",
                projectName = "Ganga Trueno",
                location = "Viman Nagar",
                tab = InventoryTab.RESI_RESALE,
                portfolio = "Aurea Living"
            )
        )

        val manager = newManager()
        manager.initialize()
        manager.provisionTrustedEmployee(
            employeeId = "EMP-ADM-1",
            employeeName = "Pavan Patil",
            rawMobile = "9822001122",
            role = EmployeeAccessRole.ADMIN,
            businessScopes = BusinessDivisionScope.entries
        )
        manager.provisionTrustedEmployee(
            employeeId = "EMP-RES-1",
            employeeName = "Sneha Joshi",
            rawMobile = "9822003344",
            role = EmployeeAccessRole.SALES_EXECUTIVE,
            businessScopes = listOf(BusinessDivisionScope.AUREA_RESIDENTIAL)
        )

        // Admin sees All Businesses and can switch between divisions
        manager.loginWithNameAndMobile("Pavan Patil", "9822001122")
        val adminEmp = manager.sessionState.value.currentEmployee!!
        assertEquals(2, repo.queryScopedProperties(adminEmp, ActiveBusinessView.ALL_BUSINESSES).size)
        val switchCom = manager.switchBusinessView(ActiveBusinessView.ASCEND_COMMERCIAL)
        assertTrue(switchCom.success)
        assertEquals(
            listOf("COM-1"),
            repo.queryScopedProperties(manager.sessionState.value.currentEmployee, ActiveBusinessView.ASCEND_COMMERCIAL).map { it.id }
        )

        // Residential-scoped executive sees ONLY Aurea Residential & cannot switch to Commercial
        manager.loginWithNameAndMobile("Sneha Joshi", "+91 98220 03344")
        val resEmp = manager.sessionState.value.currentEmployee!!
        val deniedSwitch = manager.switchBusinessView(ActiveBusinessView.ASCEND_COMMERCIAL)
        assertFalse(deniedSwitch.success)
        assertEquals(
            listOf("RES-1"),
            repo.searchScopedProperties("", resEmp, ActiveBusinessView.ASCEND_COMMERCIAL).map { it.id }
        )

        // Unapproved Pending user receives zero properties
        manager.loginWithNameAndMobile("Visitor", "9765432109")
        val pendingEmp = manager.sessionState.value.currentEmployee!!
        assertEquals(0, repo.queryScopedProperties(pendingEmp, ActiveBusinessView.ALL_BUSINESSES).size)
    }

    @Test
    fun pinLockFiveAttemptsTriggerFiveMinuteLockoutColdStartAndBackgroundLockZeroAuditLeak() = runBlocking {
        val manager = newManager()
        manager.initialize()
        manager.provisionTrustedEmployee(
            employeeId = "EMP-PIN-1",
            employeeName = "Amit Kulkarni",
            rawMobile = "9811112222",
            role = EmployeeAccessRole.COMMERCIAL_MANAGER,
            businessScopes = listOf(BusinessDivisionScope.ASCEND_COMMERCIAL)
        )
        manager.loginWithNameAndMobile("Amit Kulkarni", "9811112222")

        assertTrue(manager.enablePin("2580").success)
        val storedRef = db.employeeDao().findById("EMP-PIN-1")?.pinCredentialRef
        assertNotNull(storedRef)
        assertFalse(storedRef!!.contains("2580"))

        val baseTime = 1_700_000_000_000L
        repeat(4) { attempt ->
            val bad = manager.verifyPin("9999", nowMs = baseTime + attempt * 1000L)
            assertFalse(bad.success)
            assertFalse(bad.employee!!.isPinLockedOut(baseTime + attempt * 1000L))
        }
        // 5th failure triggers 5-minute lockout
        val fifthBad = manager.verifyPin("9999", nowMs = baseTime + 4000L)
        assertFalse(fifthBad.success)
        assertTrue(fifthBad.employee!!.isPinLockedOut(baseTime + 4000L))
        assertEquals(baseTime + 4000L + PIN_LOCKOUT_DURATION_MS, fifthBad.employee!!.pinLockedUntil)

        // Even correct PIN is blocked during 5-minute lockout
        val blockedDuringLockout = manager.verifyPin("2580", nowMs = baseTime + 60_000L)
        assertFalse(blockedDuringLockout.success)

        // After 5-minute lockout expires, valid PIN unlocks
        val afterLockout = manager.verifyPin("2580", nowMs = baseTime + 4000L + PIN_LOCKOUT_DURATION_MS + 1000L)
        assertTrue(afterLockout.success)
        assertFalse(manager.sessionState.value.isPinLocked)

        // Backgrounding locks session immediately
        manager.onAppBackgrounded()
        assertTrue(manager.sessionState.value.isPinLocked)

        // Cold-start re-initialization locks session automatically when PIN is enabled
        manager.verifyPin("2580", nowMs = baseTime + 4000L + PIN_LOCKOUT_DURATION_MS + 2000L)
        assertFalse(manager.sessionState.value.isPinLocked)
        val coldStartManager = newManager()
        val coldState = coldStartManager.initialize(nowMs = baseTime + 4000L + PIN_LOCKOUT_DURATION_MS + 3000L)
        assertTrue(coldState.isSignedIn)
        assertTrue(coldState.isPinLocked)

        // Audit log must never store plaintext PINs
        val allAudits = db.accessAuditDao().getRecent(100)
        assertTrue(allAudits.isNotEmpty())
        allAudits.forEach { ev ->
            assertFalse(ev.action.contains("2580"))
            assertFalse(ev.action.contains("9999"))
            assertFalse(ev.entityId.contains("2580"))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun adminGuardsBlockSelfDemotionLastAdminCycleAndOptimisticConflict() = runBlocking {
        val manager = newManager()
        manager.initialize()
        manager.provisionTrustedEmployee(
            employeeId = "EMP-ADM",
            employeeName = "Primary Admin",
            rawMobile = "9800000001",
            role = EmployeeAccessRole.ADMIN,
            businessScopes = BusinessDivisionScope.entries
        )
        manager.provisionTrustedEmployee(
            employeeId = "EMP-MGR",
            employeeName = "Branch Manager",
            rawMobile = "9800000002",
            role = EmployeeAccessRole.COMMERCIAL_MANAGER,
            businessScopes = listOf(BusinessDivisionScope.ASCEND_COMMERCIAL)
        )
        manager.provisionTrustedEmployee(
            employeeId = "EMP-EXEC",
            employeeName = "Field Exec",
            rawMobile = "9800000003",
            role = EmployeeAccessRole.SALES_EXECUTIVE,
            businessScopes = listOf(BusinessDivisionScope.ASCEND_COMMERCIAL),
            reportsToEmployeeId = "EMP-MGR"
        )

        manager.loginWithNameAndMobile("Primary Admin", "9800000001")
        val adminBefore = db.employeeDao().findById("EMP-ADM")!!.toDomain()

        // 1. Self-demotion & last-admin protection
        val selfDemote = manager.updateTeamMember(
            targetEmployeeId = "EMP-ADM",
            expectedVersion = adminBefore.recordVersion,
            employeeName = adminBefore.employeeName,
            loginEmail = null,
            role = EmployeeAccessRole.SALES_EXECUTIVE,
            businessScopes = listOf(BusinessDivisionScope.ASCEND_COMMERCIAL),
            reportsToEmployeeId = null,
            jobTitle = null,
            accessStatus = EmployeeAccessStatus.ACTIVE
        )
        assertFalse(selfDemote.success)
        assertEquals(EmployeeAccessRole.ADMIN, db.employeeDao().findById("EMP-ADM")!!.role)

        // 2. Recursive reporting cycle (EMP-EXEC reports to EMP-MGR, so EMP-MGR cannot report to EMP-EXEC)
        val mgrBefore = db.employeeDao().findById("EMP-MGR")!!.toDomain()
        val cycleAttempt = manager.updateTeamMember(
            targetEmployeeId = "EMP-MGR",
            expectedVersion = mgrBefore.recordVersion,
            employeeName = mgrBefore.employeeName,
            loginEmail = "mgr@tlc.in",
            role = mgrBefore.role,
            businessScopes = mgrBefore.businessScopes,
            reportsToEmployeeId = "EMP-EXEC",
            jobTitle = "Commercial Lead",
            accessStatus = EmployeeAccessStatus.ACTIVE
        )
        assertFalse(cycleAttempt.success)
        assertNull(db.employeeDao().findById("EMP-MGR")!!.reportsToEmployeeId)

        // 3. Optimistic concurrency conflict preserves previous valid state
        val execBefore = db.employeeDao().findById("EMP-EXEC")!!.toDomain()
        val validUpdate = manager.updateTeamMember(
            targetEmployeeId = "EMP-EXEC",
            expectedVersion = execBefore.recordVersion,
            employeeName = "Field Exec Senior",
            loginEmail = null,
            role = EmployeeAccessRole.SALES_EXECUTIVE,
            businessScopes = listOf(BusinessDivisionScope.ASCEND_COMMERCIAL, BusinessDivisionScope.AUREA_RESIDENTIAL),
            reportsToEmployeeId = "EMP-MGR",
            jobTitle = "Senior Exec",
            accessStatus = EmployeeAccessStatus.ACTIVE
        )
        assertTrue(validUpdate.success)

        // Stale version update must fail cleanly and NOT overwrite "Field Exec Senior"
        val staleUpdate = manager.updateTeamMember(
            targetEmployeeId = "EMP-EXEC",
            expectedVersion = execBefore.recordVersion, // stale version!
            employeeName = "Stale Overwrite Attempt",
            loginEmail = null,
            role = EmployeeAccessRole.READ_ONLY,
            businessScopes = emptyList(),
            reportsToEmployeeId = null,
            jobTitle = null,
            accessStatus = EmployeeAccessStatus.SUSPENDED
        )
        assertFalse(staleUpdate.success)
        assertTrue(staleUpdate.conflictDetected)
        val persistedExec = db.employeeDao().findById("EMP-EXEC")!!.toDomain()
        assertEquals("Field Exec Senior", persistedExec.employeeName)
        assertEquals(EmployeeAccessStatus.ACTIVE, persistedExec.accessStatus)

        // 4. Switch Profile / Sign Out clears in-memory callback while retaining Room employee rows
        // The Robolectric test thread is blocked by runBlocking; use an unconfined
        // test Main dispatcher for the session-clear callback to avoid posting to it.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            manager.switchProfile()
        } finally {
            Dispatchers.resetMain()
        }
        assertFalse(manager.sessionState.value.isSignedIn)
        assertTrue(inMemoryClearedCount >= 1)
        assertEquals(3, db.employeeDao().getAll().size)
    }

    @Test
    fun uiEntryHoldingPinAndTopBarRenderWithMissingFieldsAndScopeChips() {
        var selectedView: ActiveBusinessView? = null
        var profileClicked = false

        composeRule.setContent {
            MyApplicationTheme {
                androidx.compose.foundation.layout.Column {
                    PortalTopBar(
                        selectedBrand = Brand.COMMERCIAL,
                        onSelectBrand = {},
                        brandLogos = BrandLogos(),
                        onOpenDrawer = {},
                        onOpenAddProperty = {},
                        onOpenImport = {},
                        comparisonCount = 0,
                        onOpenComparison = {},
                        onNavigateHome = {},
                        screenTitle = "Inventory",
                        activeBusinessView = ActiveBusinessView.ASCEND_COMMERCIAL,
                        availableBusinessViews = ActiveBusinessView.entries,
                        onSelectBusinessView = { selectedView = it },
                        employeeDisplayName = "Pavan Patil",
                        onOpenProfile = { profileClicked = true }
                    )
                }
            }
        }

        assertTrue(composeRule.onAllNodesWithTag("topbar_scope_AUREA_RESIDENTIAL").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("topbar_scope_ASCEND_COMMERCIAL").fetchSemanticsNodes().isEmpty())
        assertEquals(null, selectedView)
        composeRule.onNodeWithTag("hamburger_menu_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("topbar_profile_btn").performClick()
        assertTrue(profileClicked)
    }

    @Test
    fun pinPadScreenAndPendingHoldingCardWorkAsExpected() {
        val pendingState = EmployeeSessionState(
            isInitialized = true,
            currentEmployee = EmployeeProfile(
                employeeId = "EMP-P2",
                employeeName = "New Joiner",
                loginMobile = "+919876543210",
                loginEmail = null,
                jobTitle = null,
                role = EmployeeAccessRole.READ_ONLY,
                accessStatus = EmployeeAccessStatus.PENDING
            ),
            isSignedIn = true
        )
        composeRule.setContent {
            MyApplicationTheme {
                EmployeeEntryScreen(
                    sessionState = pendingState,
                    onLogin = { _, _, _ -> },
                    onSwitchProfile = {},
                    onSignOut = {}
                )
            }
        }
        composeRule.onNodeWithTag("employee_holding_card").assertIsDisplayed()
        composeRule.onNodeWithText("Pending Admin Approval").assertIsDisplayed()
        composeRule.onAllNodesWithText(NOT_MENTIONED, substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun pinLockKeypadEntersFourDigitsAndInvokesVerify() {
        val activePinEmp = EmployeeProfile(
            employeeId = "EMP-A1",
            employeeName = "Amit",
            loginMobile = "+919876543210",
            role = EmployeeAccessRole.ADMIN,
            accessStatus = EmployeeAccessStatus.ACTIVE,
            isActive = true,
            pinEnabled = true
        )
        var submittedPin = ""
        composeRule.setContent {
            MyApplicationTheme {
                PinLockScreen(
                    employee = activePinEmp,
                    errorMessage = null,
                    onVerifyPin = { submittedPin = it },
                    onSwitchProfile = {},
                    onSignOut = {}
                )
            }
        }
        composeRule.onNodeWithTag("pin_key_2").performClick()
        composeRule.onNodeWithTag("pin_key_5").performClick()
        composeRule.onNodeWithTag("pin_key_8").performClick()
        composeRule.onNodeWithTag("pin_key_0").performClick()
        composeRule.onNodeWithTag("pin_unlock_btn").performClick()
        assertEquals("2580", submittedPin)
    }
}
