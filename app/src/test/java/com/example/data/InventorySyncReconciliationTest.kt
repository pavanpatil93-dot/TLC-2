package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.master.MasterProjectDatabase
import com.example.data.master.ProjectEntity
import com.example.data.master.PropertyUnitEntity
import com.example.model.InventoryTab
import com.example.model.PropertyItem
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InventorySyncReconciliationTest {

    private lateinit var context: Context
    private lateinit var repository: SheetsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PropertiesLocalDatabase.clearAll(context)
        context.getSharedPreferences("tlc_sync_diagnostics_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tlc_actual_data_bootstrap_v1", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking {
            val dao = MasterProjectDatabase.getDatabase(context).masterProjectDao()
            dao.deleteAllUnits()
        }
        repository = SheetsRepository(context = context, startForegroundSync = false)
    }

    @After
    fun tearDown() {
        repository.close()
        PropertiesLocalDatabase.clearAll(context)
    }

    @Test
    fun freshStartupHasZeroSeededOrSampleInventory() = runBlocking {
        assertTrue(
            "Default sample properties must be empty",
            PropertiesLocalDatabase.getDefaultSampleProperties().isEmpty()
        )
        val stats = repository.bootstrapBundledActualInventory()
        assertEquals(0, stats.addedCount)
        assertEquals(0, repository.properties.value.size)

        val roomUnits = MasterProjectDatabase.getDatabase(context).masterProjectDao().getAllUnitsSync()
        assertEquals(
            "Room unit_inventory_master must not seed mock units on creation",
            0,
            roomUnits.size
        )
    }

    @Test
    fun clearedSheetTabDeletesSyncedLocalAndRoomRowsButPreservesPendingDraftsAndOtherTabs() = runBlocking {
        val syncedLease1 = PropertyItem(
            id = "UNIT-CL-101",
            recordId = "UNIT-CL-101",
            propertyCode = "CL-101",
            projectName = "Panchshil Business Park",
            location = "Viman Nagar",
            tab = InventoryTab.OFF_LEASE,
            isSyncedToSheet = true
        )
        val pendingLeaseDraft = PropertyItem(
            id = "UNIT-CL-DRAFT",
            recordId = "UNIT-CL-DRAFT",
            propertyCode = "CL-DRAFT",
            projectName = "Unsaved Local Office",
            location = "Baner",
            tab = InventoryTab.OFF_LEASE,
            isSyncedToSheet = false
        )
        val otherTabResale = PropertyItem(
            id = "UNIT-RR-201",
            recordId = "UNIT-RR-201",
            propertyCode = "RR-201",
            projectName = "Lodha Belmondo",
            location = "Gahunje",
            tab = InventoryTab.RESI_RESALE,
            isSyncedToSheet = true
        )

        PropertiesLocalDatabase.saveProperties(context, listOf(syncedLease1, pendingLeaseDraft, otherTabResale))
        repository.reloadFromDisk()
        assertEquals(3, repository.properties.value.size)

        val dao = MasterProjectDatabase.getDatabase(context).masterProjectDao()
        dao.insertProject(
            ProjectEntity(
                projectId = "PROJ-TEST-1",
                projectName = "Panchshil Business Park",
                projectType = com.example.data.master.ProjectType.COMMERCIAL,
                developerName = "Panchshil",
                location = "East Pune",
                microMarket = "Viman Nagar",
                category = "Office"
            )
        )
        dao.insertUnit(
            PropertyUnitEntity(
                unitId = "UNIT-CL-101",
                projectId = "PROJ-TEST-1",
                propertyCode = "CL-101",
                unitNumber = "401",
                tab = "Off Lease"
            )
        )
        dao.insertUnit(
            PropertyUnitEntity(
                unitId = "UNIT-CL-DRAFT",
                projectId = "PROJ-TEST-1",
                propertyCode = "CL-DRAFT",
                unitNumber = "402",
                tab = "Off Lease"
            )
        )
        dao.insertUnit(
            PropertyUnitEntity(
                unitId = "UNIT-RR-201",
                projectId = "PROJ-TEST-1",
                propertyCode = "RR-201",
                unitNumber = "1201",
                tab = "Resi Resale"
            )
        )

        // Google Sheet returns status == "OK" with 0 rows for Commercial Lease Inventory (tab was cleared)
        val outcome = repository.reconcileInventoryTabWithSheet(
            tabName = TlcInventoryTabV2.COMMERCIAL_LEASE,
            sheetStatusOk = true,
            sheetRows = emptyList()
        )

        assertTrue(outcome.statusOk)
        assertEquals(0, outcome.sheetRowCount)
        assertEquals(1, outcome.removedLocalCount)
        assertEquals(1, outcome.removedRoomCount)
        assertEquals(1, outcome.preservedPendingCount)

        val remainingIds = repository.properties.value.map { it.id }.toSet()
        assertFalse("Synced stale row must be removed", remainingIds.contains("UNIT-CL-101"))
        assertTrue("Pending unsaved draft must be preserved", remainingIds.contains("UNIT-CL-DRAFT"))
        assertTrue("Other tab synced row must remain untouched", remainingIds.contains("UNIT-RR-201"))

        // Verify disk reload does NOT resurrect deleted records from OfflineDiskCache
        val reloaded = PropertiesLocalDatabase.loadProperties(context).map { it.id }.toSet()
        assertEquals(remainingIds, reloaded)

        // Verify Room unit_inventory_master reconciliation
        val remainingRoomIds = dao.getAllUnitsSync().map { it.unitId }.toSet()
        assertFalse(remainingRoomIds.contains("UNIT-CL-101"))
        assertTrue(remainingRoomIds.contains("UNIT-CL-DRAFT"))
        assertTrue(remainingRoomIds.contains("UNIT-RR-201"))
    }

    @Test
    fun partialSheetResponseKeepsMatchingUnitIdsAndDeletesRemovedRows() = runBlocking {
        val keptUnit = PropertyItem(
            id = "UNIT-CS-01",
            recordId = "UNIT-CS-01",
            propertyCode = "CS-01",
            projectName = "Solitaire Business Hub",
            location = "Viman Nagar",
            tab = InventoryTab.OFF_OUT,
            isSyncedToSheet = true
        )
        val removedUnit = PropertyItem(
            id = "UNIT-CS-02",
            recordId = "UNIT-CS-02",
            propertyCode = "CS-02",
            projectName = "Removed Commercial Office",
            location = "Kharadi",
            tab = InventoryTab.OFF_OUT,
            isSyncedToSheet = true
        )
        PropertiesLocalDatabase.saveProperties(context, listOf(keptUnit, removedUnit))
        repository.reloadFromDisk()

        val sheetRow = com.example.model.ImportPreviewRow(
            rowNumber = 1,
            propertyCode = "CS-01",
            projectName = "Solitaire Business Hub",
            location = "Viman Nagar",
            unitNumber = "501",
            tab = InventoryTab.OFF_OUT,
            carpetAreaSqFt = 1200.0,
            priceOrRent = 18000000.0,
            isDuplicateCode = false,
            isValid = true,
            recordId = "UNIT-CS-01"
        )

        val outcome = repository.reconcileInventoryTabWithSheet(
            tabName = TlcInventoryTabV2.COMMERCIAL_RESALE,
            sheetStatusOk = true,
            sheetRows = listOf(sheetRow)
        )

        assertEquals(1, outcome.removedLocalCount)
        assertEquals(listOf("UNIT-CS-01"), repository.properties.value.map { it.id })
    }

    @Test
    fun nonOkServerResponseNeverDeletesLocalRecords() = runBlocking {
        val syncedRental = PropertyItem(
            id = "UNIT-RENT-01",
            recordId = "UNIT-RENT-01",
            propertyCode = "RR-01",
            projectName = "Amanora Park Town",
            location = "Hadapsar",
            tab = InventoryTab.RESI_RENT,
            isSyncedToSheet = true
        )
        PropertiesLocalDatabase.saveProperties(context, listOf(syncedRental))
        repository.reloadFromDisk()

        val outcome = repository.reconcileInventoryTabWithSheet(
            tabName = TlcInventoryTabV2.RESIDENTIAL_RENTALS,
            sheetStatusOk = false,
            sheetRows = emptyList()
        )

        assertFalse(outcome.statusOk)
        assertEquals(0, outcome.removedLocalCount)
        assertEquals(1, repository.properties.value.size)
    }

    @Test
    fun verifiesTlcMasterSheetIdentityStatusParsingAndSyncDiagnosticsPersistence() {
        assertEquals("TLC Master sheet", TlcSchemaV2Api.EXPECTED_SPREADSHEET_NAME)
        assertEquals(
            "1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0",
            TlcSchemaV2Api.EXPECTED_SPREADSHEET_ID
        )
        assertEquals(
            "1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0",
            OneTapSyncCoordinator.FIXED_SPREADSHEET_ID
        )

        assertTrue(TlcSchemaV2Api.isValidOkStatus(JSONObject("""{"status":"OK","rows":[]}""")))
        assertTrue(TlcSchemaV2Api.isValidOkStatus(JSONObject("""{"status":"success","data":[]}""")))
        assertFalse(TlcSchemaV2Api.isValidOkStatus(JSONObject("""{"status":"error","message":"denied"}""")))
        assertFalse(TlcSchemaV2Api.isValidOkStatus(JSONObject("""{}""")))

        val (name, id) = TlcSchemaV2Api.extractSpreadsheetMetadata(
            JSONObject(
                """{
                    "status":"OK",
                    "spreadsheetName":"TLC Master sheet",
                    "spreadsheetId":"1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0",
                    "rows":[]
                }"""
            )
        )
        assertEquals("TLC Master sheet", name)
        assertEquals("1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0", id)

        val snapshot = repository.updateSyncDiagnostics(
            activeSpreadsheetName = "TLC Master sheet",
            activeSpreadsheetId = "1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0",
            tabRowCounts = mapOf(
                TlcInventoryTabV2.COMMERCIAL_LEASE to 0,
                TlcInventoryTabV2.COMMERCIAL_RESALE to 2,
                TlcInventoryTabV2.RESIDENTIAL_UNDER_CONSTRUCTION to 0,
                TlcInventoryTabV2.RESIDENTIAL_RESALE to 1,
                TlcInventoryTabV2.RESIDENTIAL_RENTALS to 0
            ),
            lastRemovedStaleCount = 5,
            lastPreservedPendingCount = 1,
            lastSyncedAt = "23 Nov 2026, 08:40 PM",
            lastStatusSummary = "OK (3 sheet rows, 5 stale removed)"
        )
        assertTrue(snapshot.isExpectedSpreadsheetConnected)
        assertEquals(3, snapshot.totalSyncedSheetRows)
        assertEquals(5, snapshot.lastRemovedStaleCount)
        assertEquals(1, snapshot.lastPreservedPendingCount)

        // Verify diagnostics survive repository recreation
        val repo2 = SheetsRepository(context = context, startForegroundSync = false)
        try {
            val loaded = repo2.syncDiagnostics.value
            assertEquals("TLC Master sheet", loaded.activeSpreadsheetName)
            assertEquals("1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0", loaded.activeSpreadsheetId)
            assertEquals(3, loaded.totalSyncedSheetRows)
            assertEquals(2, loaded.tabRowCounts[TlcInventoryTabV2.COMMERCIAL_RESALE])
            assertEquals(5, loaded.lastRemovedStaleCount)
        } finally {
            repo2.close()
        }
    }
}
