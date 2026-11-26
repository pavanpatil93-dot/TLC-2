package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.master.MasterProjectDatabase
import com.example.data.projects.ProjectDirectoryStore
import com.example.model.InventoryTab
import com.example.model.PropertyItem
import com.example.model.TenantLeaseAlertHelper
import com.example.ui.screens.CrmDealStorage
import com.example.ui.screens.readCrmLeads
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterSheetAuditVerificationTest {

    private lateinit var context: Context
    private lateinit var repository: SheetsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tlc_project_directory_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("crm_deal_pipeline_v2", Context.MODE_PRIVATE).edit().clear().commit()
        repository = SheetsRepository(context)
    }

    @Test
    fun appsScriptRouting_targetsExpectedSpreadsheetIdAndExecUrlsOnly() {
        assertEquals(
            "1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0",
            TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID
        )
        assertTrue(TlcSchemaV2Api.CANONICAL_SYNC_TABS.contains(TlcSchemaV2Api.TAB_TENANTS))
        assertTrue(TlcSchemaV2Api.CANONICAL_SYNC_TABS.contains(TlcSchemaV2Api.TAB_COMMERCIAL_CRM))
        assertTrue(TlcSchemaV2Api.CANONICAL_SYNC_TABS.contains(TlcSchemaV2Api.TAB_RESIDENTIAL_CRM))
        assertFalse(TlcSchemaV2Api.CANONICAL_SYNC_TABS.contains("CRM"))

        val directSheetUrl = "https://docs.google.com/spreadsheets/d/1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0/edit"
        val resolved = TlcSchemaV2Api.resolveEndpointUrl(directSheetUrl)
        assertTrue(resolved.contains("script.google.com"))
        assertTrue(resolved.endsWith("/exec"))
        assertFalse(resolved.contains("docs.google.com/spreadsheets"))

        val postUrl = TlcSchemaV2Api.buildTabPostUrl(directSheetUrl, TlcSchemaV2Api.TAB_TENANTS)
        assertTrue(postUrl.contains("/exec?tabName=Tenants"))
        assertFalse(postUrl.contains("docs.google.com/spreadsheets"))
    }

    @Test
    fun inventoryUpsert_protectsAllFormulaColumnsEvenWhenManualOverridesSet() {
        val prop = PropertyItem(
            id = "PROP-AUDIT-1",
            recordId = "REC-AUDIT-1",
            propertyCode = "CO-BAN-101",
            tab = InventoryTab.OFF_LEASE,
            date = "2026-11-17T18:30:00.000Z",
            location = "Baner",
            microMarket = "Baner High Street",
            projectName = "Solitaire Business Hub",
            projectId = "CP-0099",
            carpetAreaSqFt = 2000.0,
            builtUpAreaSqFt = 2800.0,
            rent = 200000.0,
            deposit = 1200000.0,
            manualLoadingPercentOverride = 40.0,
            isManualBuiltUpOverride = true,
            attachedPantry = "Yes",
            attachedWashroom = "Yes",
            frontageFt = 30.0,
            clearHeightFt = 14.0,
            truckAccess = "20 Ft Container",
            powerLoad = "50 kVA",
            targetPossessionDate = "2026-12-31T18:30:00.000Z"
        )
        val upsert = TlcMasterSheetMapper.buildUpsertRecord(prop)

        // Exact Location & Micro-Market & extended columns
        assertEquals("Baner", upsert.getString("Location"))
        assertEquals("Baner High Street", upsert.getString("Micro-Market"))
        assertEquals("Yes", upsert.getString("Attached Pantry"))
        assertEquals("Yes", upsert.getString("Attached Washroom"))
        assertEquals(30.0, upsert.getDouble("Frontage (Ft)"), 0.001)
        assertEquals(14.0, upsert.getDouble("Clear Height (Ft)"), 0.001)
        assertEquals("20 Ft Container", upsert.getString("Truck Access"))
        assertEquals("50 kVA", upsert.getString("Power Load"))

        // IST dd/MM/yyyy conversion from UTC ISO timestamps
        assertEquals("18/11/2026", upsert.getString("Date Added"))
        assertEquals("01/01/2027", upsert.getString("Target Possession Date"))

        // All 9 formula-calculated columns must NEVER be sent in UPSERT
        val mustNeverOverwrite = listOf(
            "Project ID",
            "Total Carpet Area (Sq Ft)",
            "Loading %",
            "Total Built-Up Area (Sq Ft)",
            "Rent / Sq Ft",
            "Rate / Sq Ft",
            "Current ROI / Yield (%)",
            "Total Security Deposit (₹)",
            "Total Monthly Outflow (₹)"
        )
        mustNeverOverwrite.forEach { header ->
            assertFalse("Formula column '$header' must not be overwritten", upsert.has(header))
        }
    }

    @Test
    fun crmExactHeaderMapping_handlesPipelineStageFurnishingArchiveAndSubSource() = runBlocking {
        val crmRows = listOf(
            listOf(
                "Lead ID", "Created Date", "Client Name", "Mobile",
                "Pipeline Stage", "Furnishing Preference", "Archive Status",
                "Lead Source", "Sub-Source / Broker", "Assigned To", "Next Follow-up Date"
            ),
            listOf(
                "CRM-AUD-01", "2026-11-17T18:30:00.000Z", "Aarav Kulkarni", "9822112233",
                "Site Visit Scheduled", "Plug & Play", "Active",
                "Channel Partner", "Metro Realty CP", "Sagar I. Chaudhari", "2026-11-24T18:30:00.000Z"
            )
        )

        repository.mergeTabRowsFromAppsScript(
            tabName = TlcSchemaV2Api.TAB_COMMERCIAL_CRM,
            rows = crmRows,
            sheetStatusOk = true
        )

        val leads = readCrmLeads(context)
        assertEquals(1, leads.size)
        val lead = leads.single()
        assertEquals("CRM-AUD-01", lead.id)
        assertEquals("18/11/2026", lead.createdDate)
        assertEquals("25/11/2026", lead.nextFollowUp)
        assertEquals("Site Visit Scheduled", lead.status)
        assertEquals("Plug & Play", lead.furnishing)
        assertEquals("Active", lead.archiveStatus)
        assertEquals("Channel Partner", lead.leadSource)
        assertEquals("Metro Realty CP", lead.subSource)
        assertEquals("Sagar I. Chaudhari", lead.assignedTo)
    }

    @Test
    fun projectsAndBrokersExactHeaders_persistToRoomAndReconcileStaleRows() = runBlocking {
        val dao = MasterProjectDatabase.getDatabase(context).masterProjectDao()

        // Projects sync (exact Location, Micro-Market, Project ID, Project Name, OC / Construction Status)
        val projectRows = listOf(
            listOf("Project ID", "Project Name", "Developer", "Location", "Micro-Market", "OC / Construction Status", "Possession"),
            listOf("CP-501", "Panchshil Tech Park", "Panchshil", "Kharadi", "EON IT Corridor", "Ready / OC Received", "2026-11-17T18:30:00.000Z")
        )
        repository.mergeTabRowsFromAppsScript(
            tabName = TlcSchemaV2Api.TAB_COMMERCIAL_PROJECTS,
            rows = projectRows,
            sheetStatusOk = true
        )
        val syncedProjects = ProjectDirectoryStore.read(context).filter { it.id == "CP-501" }
        assertEquals(1, syncedProjects.size)
        assertEquals("Kharadi", syncedProjects.single().location)
        assertEquals("EON IT Corridor", syncedProjects.single().microMarket)
        assertEquals("Ready / OC Received", syncedProjects.single().ocStatus)
        assertEquals("18/11/2026", syncedProjects.single().possession)

        // Brokers sync (Phone Type, Alternate Mobiles, Verification Status, Specialization) across BOTH tabs
        val commBrokerRows = listOf(
            listOf("Broker ID", "Broker Name", "Primary Mobile", "Phone Type", "Alternate Mobiles", "Verification Status", "Specialization", "Operating Areas"),
            listOf("CB-101", "Ramesh Joshi", "9822334455", "WhatsApp + Call", "9822334456", "Confirmed / Verified", "IT Office Leasing", "Kharadi, Baner")
        )
        val resiBrokerRows = listOf(
            listOf("Broker ID", "Broker Name", "Primary Mobile", "Phone Type", "Alternate Mobiles", "Verification Status", "Specialization", "Operating Areas"),
            listOf("RB-201", "Sneha Deshpande", "9822556677", "Mobile", "9822556678", "Confirmed / Verified", "Luxury 3/4 BHK", "Koregaon Park")
        )
        repository.mergeTabRowsFromAppsScript(TlcSchemaV2Api.TAB_COMMERCIAL_BROKERS, commBrokerRows, sheetStatusOk = true)
        repository.mergeTabRowsFromAppsScript(TlcSchemaV2Api.TAB_RESIDENTIAL_BROKERS, resiBrokerRows, sheetStatusOk = true)

        val brokersInRoom = dao.getAllBrokersSync()
        val cb = brokersInRoom.find { it.brokerId == "CB-101" }
        val rb = brokersInRoom.find { it.brokerId == "RB-201" }
        assertNotNull(cb)
        assertNotNull(rb)
        assertEquals("WhatsApp + Call", cb?.phoneType)
        assertEquals("9822334456", cb?.alternateMobiles)
        assertEquals("Confirmed / Verified", cb?.verificationStatus)
        assertEquals("IT Office Leasing", cb?.specialization)
        assertEquals("Luxury 3/4 BHK", rb?.specialization)
    }

    @Test
    fun tenantsSync_reconcilesEmptyTabAndFormatsIstDates() = runBlocking {
        val tenantRows = listOf(
            listOf("Tenant ID", "Property Code", "Tenant Name", "Lease Start Date", "Lease Expiry Date"),
            listOf("TEN-01", "CO-BAN-101", "Acme Tech Pvt Ltd", "2026-11-17T18:30:00.000Z", "2029-11-17T18:30:00.000Z")
        )
        repository.mergeTabRowsFromAppsScript(TlcSchemaV2Api.TAB_TENANTS, tenantRows, sheetStatusOk = true)
        assertEquals(1, repository.tenants.value.size)
        assertEquals("18/11/2026", repository.tenants.value.single().leaseStartDate)
        assertEquals("18/11/2029", repository.tenants.value.single().leaseExpiryDate)

        // Clearing Tenants sheet tab reconciles local cache back to 0
        repository.mergeTabRowsFromAppsScript(
            TlcSchemaV2Api.TAB_TENANTS,
            listOf(listOf("Tenant ID", "Property Code", "Tenant Name")),
            sheetStatusOk = true
        )
        assertEquals(0, repository.tenants.value.size)
    }

    @Test
    fun zeroGhostDataAndIstDateParsing_verifiedAcrossModels() {
        // Deal pipeline must not seed fake deals (DL-101..DL-103)
        assertEquals(0, CrmDealStorage.loadDeals(context, emptyList()).size)

        // IST date formatting across ISO UTC & JS Date strings
        assertEquals("18/11/2026", CrmSheetSyncMapper.formatCrmDateIst("2026-11-17T18:30:00.000Z"))
        assertEquals("18/11/2026", TlcPropertyCalculator.formatDateDdMmYyyy("2026-11-17T18:30:00.000Z"))
        assertEquals(
            LocalDate.of(2026, 11, 18),
            TenantLeaseAlertHelper.parseFlexibleLocalDate("2026-11-17T18:30:00.000Z")
        )
    }
}
