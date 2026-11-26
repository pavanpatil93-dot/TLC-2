package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.access.ActiveBusinessView
import com.example.access.AuthorizationSource
import com.example.access.BusinessDivisionScope
import com.example.access.EmployeeAccessDatabase
import com.example.access.EmployeeAccessRole
import com.example.access.EmployeeAccessStatus
import com.example.access.EmployeeEntity
import com.example.access.EmployeeIdentityStatus
import com.example.crm.CallType
import com.example.crm.CrmCallManager
import com.example.crm.CrmCallRecord
import com.example.data.master.ApprovalRequestEntity
import com.example.data.master.AuditEventEntity
import com.example.data.master.MasterProjectDatabase
import com.example.model.TlcStandardOptions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultiDeviceSyncAndReconciliationTest {

    private lateinit var context: Context
    private lateinit var repository: SheetsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PropertiesLocalDatabase.clearAll(context)
        context.getSharedPreferences("tlc_device_identity_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tlc_crm_calls_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tlc_crm_leads_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tlc_crm_daily_reports_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tlc_synced_employees_v1", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking {
            val masterDao = MasterProjectDatabase.getDatabase(context).masterProjectDao()
            masterDao.deleteAllUnits()
            val existingCalls = masterDao.getAllCallLogsSync().map { it.callId }
            if (existingCalls.isNotEmpty()) masterDao.deleteCallLogsByIds(existingCalls)
            masterDao.deleteAllApprovalRequests()
            masterDao.deleteAllAuditEvents()
            EmployeeAccessDatabase.get(context).employeeDao().deleteAll()
        }
        repository = SheetsRepository(context = context, startForegroundSync = false)
    }

    @After
    fun tearDown() {
        repository.close()
        PropertiesLocalDatabase.clearAll(context)
    }

    @Test
    fun deviceIdentityGeneratesCollisionFreeIdsAcrossSamsungModels() {
        val s21Short = DeviceIdentityManager.resolveShortModelCode("SM-G998B")
        val s22Short = DeviceIdentityManager.resolveShortModelCode("SM-S908B")
        assertEquals("S21U", s21Short)
        assertEquals("S22U", s22Short)
        assertEquals("S21 Ultra", DeviceIdentityManager.resolveFriendlyDeviceName("SM-G998B"))
        assertEquals("S22 Ultra", DeviceIdentityManager.resolveFriendlyDeviceName("SM-S908B"))

        val persistedTag1 = DeviceIdentityManager.getDeviceTag(context)
        val persistedTag2 = DeviceIdentityManager.getDeviceTag(context)
        assertEquals("Device tag must persist identically across calls", persistedTag1, persistedTag2)

        val propId1 = DeviceIdentityManager.generatePropertyId(context, isCommercial = true)
        val propId2 = DeviceIdentityManager.generatePropertyId(context, isCommercial = true)
        assertTrue(propId1.startsWith("COM-$persistedTag1-"))
        assertNotEquals("Sequential property IDs must never collide", propId1, propId2)

        val resId = DeviceIdentityManager.generatePropertyId(context, isCommercial = false)
        assertTrue(resId.startsWith("RES-$persistedTag1-"))

        val crmId1 = DeviceIdentityManager.generateCrmLeadId(context)
        val crmId2 = DeviceIdentityManager.generateCrmLeadId(context)
        assertTrue(crmId1.startsWith("CRM-$persistedTag1-"))
        assertNotEquals(crmId1, crmId2)

        val callId1 = DeviceIdentityManager.generateCallLogId(context)
        val callId2 = DeviceIdentityManager.generateCallLogId(context)
        assertTrue(callId1.startsWith("CALL-$persistedTag1-"))
        assertNotEquals(callId1, callId2)

        val currentDevice = DeviceIdentityManager.getFriendlyDeviceName(context)
        assertTrue(DeviceIdentityManager.isFromOtherDevice("S22 Ultra", "S21 Ultra"))
        assertFalse(DeviceIdentityManager.isFromOtherDevice(currentDevice, currentDevice))
    }

    @Test
    fun appsScriptUrlsAndCanonicalTabNamesAreAlignedAnd401HintIsSurfaced() {
        val tabs = TlcSchemaV2Api.CANONICAL_SYNC_TABS
        val expected14 = listOf(
            "Commercial Lease Inventory",
            "Commercial Resale Inventory",
            "Residential Under Construction",
            "Residential Resale",
            "Residential Rentals",
            "Commercial CRM",
            "Residential CRM",
            "Commercial Projects",
            "Residential Projects",
            "Commercial Brokers",
            "Residential Brokers",
            "Vendors",
            "Team & Users",
            "Call Logs"
        )
        assertEquals(expected14, tabs)
        assertFalse("Legacy CRM tab must not be in canonical tabs", tabs.contains("CRM"))

        val execUrl = "https://script.google.com/macros/s/AKfycbTestDeployment/exec"
        // Direct docs.google.com/spreadsheets URL must be rejected/normalized to Apps Script /exec
        val normalized = TlcSchemaV2Api.resolveEndpointUrl(
            "https://docs.google.com/spreadsheets/d/1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0/edit"
        )
        assertTrue(normalized.contains("script.google.com"))
        assertTrue(normalized.endsWith("/exec"))

        val postUrl = TlcSchemaV2Api.buildTabPostUrl(execUrl, "Call Logs")
        assertTrue(postUrl.startsWith(execUrl))
        assertTrue(postUrl.contains("tabName=Call+Logs") || postUrl.contains("tabName=Call%20Logs"))
        assertFalse(postUrl.contains("docs.google.com/spreadsheets"))

        val getUrlResult = TlcSchemaV2Api.buildTabGetUrl(execUrl, "Commercial CRM")
        if (getUrlResult.isSuccess) {
            val getUrl = getUrlResult.getOrThrow()
            assertTrue(getUrl.startsWith(execUrl))
            assertTrue(getUrl.contains("tabName=Commercial+CRM") || getUrl.contains("tabName=Commercial%20CRM"))
            assertFalse(getUrl.contains("docs.google.com/spreadsheets"))
        }

        val msg401 = TlcSchemaV2Api.formatHttpErrorMessage("GET", 401, "")
        val msg403 = TlcSchemaV2Api.formatHttpErrorMessage("GET", 403, "")
        assertTrue(msg401.contains("Check Apps Script deployment: Execute as 'Me', Who has access 'Anyone'."))
        assertTrue(msg403.contains("Check Apps Script deployment: Execute as 'Me', Who has access 'Anyone'."))
    }

    @Test
    fun teamUsersSyncRemovesStaleHardcodedTestEmployeesAndLoadsOnlySixActiveMembers() = runBlocking {
        // Verify static options & demo calls contain zero hardcoded test employees
        assertEquals(listOf("Unassigned"), TlcStandardOptions.teamEmployees)
        assertTrue(CrmCallManager.getDemoCalls().isEmpty())

        // Insert a stale test employee into Room that must be purged on Team & Users sync
        val empDao = EmployeeAccessDatabase.get(context).employeeDao()
        empDao.upsert(
            EmployeeEntity(
                employeeId = "EMP-TEST-999",
                employeeName = "Amit Sharma",
                loginMobile = "9000000000",
                loginEmail = "amit@test.local",
                role = EmployeeAccessRole.SALES_EXECUTIVE,
                businessScopes = listOf(BusinessDivisionScope.ASCEND_COMMERCIAL),
                reportsToEmployeeId = null,
                jobTitle = "Executive",
                isActive = true,
                accessStatus = EmployeeAccessStatus.ACTIVE,
                identityStatus = EmployeeIdentityStatus.VERIFIED_ACCOUNT,
                authorizationSource = AuthorizationSource.TRUSTED_PROVISIONING,
                activeBusinessView = ActiveBusinessView.ASCEND_COMMERCIAL,
                pinEnabled = false,
                pinCredentialRef = null,
                pinFailedAttempts = 0,
                pinLockedUntil = null,
                approvedDeskContacts = emptyList(),
                recordVersion = 1
            )
        )
        assertEquals(1, empDao.getAll().size)

        val activeTeamRows = listOf(
            listOf("User ID", "Full Name", "Mobile", "Role", "Division", "Status"),
            listOf("USR-01", "Pawan Patil", "9822000001", "Director", "Commercial", "Active"),
            listOf("USR-02", "Himanshu M. Chordiya", "9822000002", "Director", "Commercial", "Active"),
            listOf("USR-03", "Sagar I. Chaudhari", "9822000003", "Manager", "Commercial", "Active"),
            listOf("USR-04", "Ayush S. Khedkar", "9822000004", "Executive", "Commercial", "Active"),
            listOf("USR-05", "Priya Thorat", "9822000005", "Executive", "Residential", "Active"),
            listOf("USR-06", "Parth Chavan", "9822000006", "Executive", "Residential", "Active"),
            listOf("USR-07", "Satish Fuke", "9822000007", "Executive", "Commercial", "Active"),
            listOf("USR-08", "Ram Kamble", "9822000008", "Executive", "Residential", "Active")
        )

        val outcome = repository.mergeTabRowsFromAppsScript(
            tabName = TlcSchemaV2Api.TAB_TEAM_USERS,
            rows = activeTeamRows,
            sheetStatusOk = true
        )
        assertEquals(5, outcome.addedCount)

        val roomEmployees = empDao.getAll()
        assertEquals(5, roomEmployees.size)
        assertFalse(
            "Stale test employee must be deleted from Room on Team & Users sync",
            roomEmployees.any { it.employeeId == "EMP-TEST-999" || it.employeeName.contains("Amit") }
        )

        val activeNames = SheetsRepository.getSyncedActiveEmployeeNames(context)
        assertEquals(5, activeNames.size)
        assertTrue(activeNames.contains("Pawan Patil"))
        assertTrue(activeNames.contains("Sagar I. Chaudhari"))
        assertTrue(activeNames.contains("Ayush S. Khedkar"))
        assertTrue(activeNames.contains("Priya Thorat"))
        assertTrue(activeNames.contains("Parth Chavan"))
        assertFalse(
            activeNames.any {
                it.contains("Amit") || it.contains("Neha") || it.contains("Rohan") ||
                    it.contains("Vikram") || it.contains("Himanshu") || it.contains("Satish") || it.contains("Ram Kamble")
            }
        )
    }

    @Test
    fun callLogsReconciliationPreservesPendingDeletesRemovedAndKeepsOtherDeviceMetadata() = runBlocking {
        val callDao = MasterProjectDatabase.getDatabase(context).masterProjectDao()
        val staleSynced = CrmCallRecord(
            id = "CALL-S21U-OLD",
            callerName = "Deleted Sample Call",
            phoneNumber = "9822000001",
            callType = CallType.INCOMING,
            timestamp = 1_700_000_000_000L,
            durationSeconds = 95,
            deviceName = "S21 Ultra",
            syncStatus = "SYNCED",
            updatedAt = 1_700_000_000_000L
        )
        val pendingLocal = CrmCallRecord(
            id = "CALL-S21U-PENDING",
            callerName = "Unsynced New Client",
            phoneNumber = "9822000002",
            callType = CallType.OUTGOING,
            timestamp = 1_700_000_500_000L,
            durationSeconds = 180,
            notes = "Local AI summary waiting to push",
            deviceName = "S21 Ultra",
            syncStatus = "PENDING",
            updatedAt = 1_700_000_500_000L
        )
        CrmCallManager.saveCallsFromPull(context, listOf(staleSynced, pendingLocal))
        callDao.upsertCallLogs(listOf(staleSynced.toRoomEntity(), pendingLocal.toRoomEntity()))

        val sheetRows = listOf(
            listOf(
                "Call ID", "Date & Time", "Caller Name", "Phone", "Direction",
                "Duration", "Role / Linked ID", "Notes / AI Summary", "Employee",
                "Device Name", "Recording Filename"
            ),
            listOf(
                "CALL-S22U-REMOTE", "23/11/2026 14:35", "Rajesh Kulkarni", "9890123456", "INCOMING",
                "135", "Client / CRM-S22U-991", "Looking for 3000 sqft furnished office in Baner", "Pawan Patil",
                "S22 Ultra", "Call_20261123_1435.m4a"
            )
        )

        val outcome = repository.mergeCallLogsSheetRowsWithOutcome(
            rows = sheetRows,
            reconcileStale = true
        )

        assertEquals("Unexpected outcome=$outcome", 1, outcome.removedCount)
        assertEquals("Unexpected outcome=$outcome", 1, outcome.preservedPendingCount)

        val calls = CrmCallManager.readCalls(context)
        val ids = calls.map { it.id }.toSet()
        assertFalse("Deleted synced call log must be removed locally", ids.contains("CALL-S21U-OLD"))
        assertTrue("Pending local call log must be preserved", ids.contains("CALL-S21U-PENDING"))
        assertTrue("Pulled S22 Ultra call log must be saved", ids.contains("CALL-S22U-REMOTE"))

        val remoteCall = calls.first { it.id == "CALL-S22U-REMOTE" }
        assertEquals("S22 Ultra", remoteCall.deviceName)
        assertEquals("Call_20261123_1435.m4a", remoteCall.recordingFilename)
        assertEquals("SYNCED", remoteCall.syncStatus)
        assertEquals("Looking for 3000 sqft furnished office in Baner", remoteCall.notes)

        val roomIds = callDao.getAllCallLogsSync().map { it.callId }.toSet()
        assertFalse(roomIds.contains("CALL-S21U-OLD"))
        assertTrue(roomIds.contains("CALL-S21U-PENDING"))
        assertTrue(roomIds.contains("CALL-S22U-REMOTE"))
    }

    @Test
    fun dailyReportsAndAuditApprovalsReconciliationDeletesRemovedSheetRows() = runBlocking {
        val masterDao = MasterProjectDatabase.getDatabase(context).masterProjectDao()
        masterDao.insertApprovalRequest(
            ApprovalRequestEntity(
                requestId = "APR-STALE-1",
                actionType = "DELETE_RECORD",
                entityType = "PROPERTY",
                targetId = "U1",
                requestedBy = "USR-99",
                requestedByName = "Tester",
                status = "APPROVED"
            )
        )
        masterDao.insertApprovalRequest(
            ApprovalRequestEntity(
                requestId = "APR-KEEP-2",
                actionType = "BULK_EDIT",
                entityType = "PROPERTY",
                targetId = "U2",
                requestedBy = "USR-01",
                requestedByName = "Pawan Patil",
                status = "PENDING"
            )
        )
        masterDao.insertAuditEvent(
            AuditEventEntity(
                eventId = "AUD-STALE-1",
                actionType = "SAMPLE",
                entityType = "PROPERTY",
                targetId = "U1",
                employeeId = "USR-99",
                employeeName = "Tester",
                details = "Old sample audit"
            )
        )

        val auditOutcome = repository.reconcileAuditAndApprovalsFromSheet(
            rows = listOf(
                listOf("Request ID", "Action"),
                listOf("APR-KEEP-2", "BULK_EDIT")
            ),
            reconcileStale = true
        )
        assertEquals(2, auditOutcome.removedCount)
        assertEquals(listOf("APR-KEEP-2"), masterDao.getAllApprovalRequestsSync().map { it.requestId })
        assertTrue(masterDao.getAllAuditEventsSync().isEmpty())

        // Daily reports: seed 2 cached rows, then reconcile with only 1 active row from sheet
        repository.mergeDailyReportRowsWithOutcome(
            rows = listOf(
                listOf("Report ID", "Employee Name", "Date"),
                listOf("DR-1", "Pawan Patil", "2026-11-23"),
                listOf("DR-SAMPLE", "Test User", "2026-11-01")
            ),
            reconcileStale = true
        )
        val drOutcome = repository.mergeDailyReportRowsWithOutcome(
            rows = listOf(
                listOf("Report ID", "Employee Name", "Date"),
                listOf("DR-1", "Pawan Patil", "2026-11-23")
            ),
            reconcileStale = true
        )
        assertEquals(1, drOutcome.updatedCount)
        assertEquals(1, drOutcome.removedCount)
    }
}
