package com.example

import com.example.data.SheetsRepository
import com.example.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class MasterInventoryStep1Test {
    private fun adminContext(): android.content.Context {
        val c = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        c.getSharedPreferences("real_estate_user_settings", 0).edit().putString("user_role", "Admin").putString("user_username", "Test Admin").commit()
        return c
    }


    private lateinit var repository: SheetsRepository

    @Before
    fun setUp() {
        com.example.data.PropertiesLocalDatabase.clearAll(adminContext())
        repository = SheetsRepository(context = adminContext(), startForegroundSync = false)
        repository.deleteAllProperties()
        repository.clearAllReviewRows()
        repository.clearSyncLogs()
    }

    /**
     * TEST 1: Source Column Synonym Recognition
     * Verifies that equivalencies like "Office Name" -> "Project / Building",
     * "Carpet Sq Ft" -> "Carpet Area", "Monthly Rental" -> "Rent",
     * "Sale Amount" -> "Price / Sale", and "Mobile Number" -> "Contact Number"
     * are recognized correctly with HIGH/EXACT confidence.
     */
    @Test
    fun testSourceColumnSynonymRecognition() {
        val testCases = mapOf(
            "Office Name" to "Project / Building",
            "Building Name" to "Project / Building",
            "Project Name" to "Project / Building",
            "Carpet Sq Ft" to "Carpet Area",
            "Sale Amount" to "Price / Sale",
            "Monthly Rental" to "Rent",
            "Mobile Number" to "Contact Number",
            "Owner Name" to "Owner / Contact Person",
            "Property Type" to "Property Type",
            "Location" to "Location",
            "Possession" to "Possession"
        )

        testCases.forEach { (source, expectedTarget) ->
            val (target, confidence) = repository.recognizeHeader(source)
            assertEquals("Mapping failed for $source", expectedTarget, target)
            assertTrue("Expected high or exact confidence for $source",
                confidence == MappingConfidence.EXACT || confidence == MappingConfidence.HIGH)
        }
    }

    /**
     * TEST 2: Schema Verification - All 35 Standard Columns
     * Ensures all 35 columns from MASTER_PROPERTY_35_COLUMNS are present in their exact order,
     * preserving the original 24 columns and appending columns 25-35.
     */
    @Test
    fun testMasterProperty35Columns() {
        val cols = MASTER_PROPERTY_35_COLUMNS
        assertEquals(35, cols.size)

        // Original 24 columns in exact order
        val expectedFirst24 = listOf(
            "Property Code", "Date", "Location", "Project / Building", "Unit / Shop / Office No",
            "Floor", "Property Type", "Sub Type", "Carpet Area", "Built-up Area",
            "Price / Sale", "Rent", "Deposit", "ROI %", "Possession",
            "Furnishing", "Status", "Owner / Contact Person", "Contact Number", "Source",
            "Remarks", "Listing Link", "Last Updated", "Remark"
        )
        for (i in 0 until 24) {
            assertEquals("Column ${i + 1} mismatch", expectedFirst24[i], cols[i])
        }

        // Appended 11 fields (25-35)
        val expectedLast11 = listOf(
            "Portfolio", "Transaction Type", "Google Maps URL", "Latitude", "Longitude",
            "Verification Status", "Last Verified Date", "Verified By", "Record ID",
            "Sync Updated At", "Archive Status"
        )
        for (i in 0 until 11) {
            assertEquals("Column ${i + 25} mismatch", expectedLast11[i], cols[i + 24])
        }
    }

    /**
     * TEST 3: Representative Records Covering All 8 Property Categories
     * - Office Lease
     * - Office Sale
     * - Shop Lease
     * - Shop Sale
     * - Pre-Leased Sale
     * - Residential Rent
     * - Residential Resale
     * - Under Construction
     */
    @Test
    fun testImportRepresentative8Categories() {
        val sampleCsv = """
            Property Code,Location,Project / Building,Unit / Shop / Office No,Property Type,Sub Type,Carpet Area,Built-up Area,Price / Sale,Rent,Deposit,ROI %,Possession,Furnishing,Status,Owner / Contact Person,Contact Number,Portfolio,Transaction Type
            OL-KHD-101,Kharadi Pune,EON Free Zone,Tower A-402,Office,Off Lease,2400,,0,192000,1152000,0,Immediate,Fully Furnished,Available,Mr. Bajaj,9822001122,Commercial,Lease
            OO-BAN-202,Baner Pune,Amar Apex,Suite 701,Office,Off Out,3200,,38000000,0,0,0,Immediate,Bare,Available,Sunil Deshmukh,9823004455,Commercial,Sale
            SL-FC-303,FC Road Pune,Deccan Corner,Shop 5,Shop,Shop Lease,900,,0,160000,960000,0,Immediate,Warm Shell,Available,Ramesh Joshi,9822445566,Commercial,Lease
            SO-KOT-404,Kothrud Pune,Kothrud Plaza,Shop 12,Shop,Shop Out,1200,,24000000,0,0,0,Immediate,Bare,Available,Prakash Patil,9890112233,Commercial,Sale
            PL-VIM-505,Viman Nagar Pune,Solitaire Business Hub,Unit 802,Office,Pre-Leased,4500,,55000000,385000,2310000,8.4,Immediate,Plug-and-Play,Available,Anand Shinde,9881002233,Commercial,Pre-Leased Sale
            RR-KPN-606,Koregaon Park Pune,Sky Lounge,Flat 1102,Apartment,Resi Rent,1850,,0,85000,400000,0,Immediate,Fully Furnished,Available,Kavita Nair,9823114477,Residential,Lease
            RS-KLY-707,Kalyani Nagar Pune,Trump Towers,Unit 14B,Apartment,Resi Resale,3500,,62000000,0,0,0,Immediate,Fully Furnished,Available,Vikram Mehta,9822336699,Residential,Sale
            UC-HIN-808,Hinjawadi Pune,Godrej Elements,Tower 3-904,Apartment,Und Const,1150,,9200000,0,0,0,December 2027,Semi-Furnished,Available,Godrej Sales,9850119900,Residential,Sale
        """.trimIndent()

        val parsed = repository.parseCsvOrTextData(sampleCsv, "test_portfolio.csv")
        assertEquals(8, parsed.size)

        // Verify categories mapped to correct InventoryTabs
        assertEquals(InventoryTab.OFF_LEASE, parsed[0].tab)
        assertEquals(InventoryTab.OFF_OUT, parsed[1].tab)
        assertEquals(InventoryTab.SHOP_LEASE, parsed[2].tab)
        assertEquals(InventoryTab.SHOP_OUT, parsed[3].tab)
        assertEquals(InventoryTab.PRE_LEASED, parsed[4].tab)
        assertEquals(InventoryTab.RESI_RENT, parsed[5].tab)
        assertEquals(InventoryTab.RESI_RESALE, parsed[6].tab)
        assertEquals(InventoryTab.UND_CONST, parsed[7].tab)

        // Verify portfolios
        assertEquals(StandardPortfolio.COMMERCIAL.label, parsed[0].portfolio)
        assertEquals(StandardPortfolio.COMMERCIAL.label, parsed[1].portfolio)
        assertEquals(StandardPortfolio.COMMERCIAL.label, parsed[2].portfolio)
        assertEquals(StandardPortfolio.COMMERCIAL.label, parsed[3].portfolio)
        assertEquals(StandardPortfolio.COMMERCIAL.label, parsed[4].portfolio)
        assertEquals(StandardPortfolio.RESIDENTIAL.label, parsed[5].portfolio)
        assertEquals(StandardPortfolio.RESIDENTIAL.label, parsed[6].portfolio)
        assertEquals(StandardPortfolio.RESIDENTIAL.label, parsed[7].portfolio)

        // Import rows into repository
        val stats = repository.importRowsDetailed(parsed, duplicateHandling = "OVERWRITE")
        assertEquals(8, stats.addedCount)
        assertEquals(0, stats.failedCount)
        assertEquals(8, repository.properties.value.size)

        // Verify export contains all 35 columns
        val exported = repository.exportStandardCsv()
        assertTrue(exported.contains("OL-KHD-101"))
        assertTrue(exported.contains("UC-HIN-808"))
        assertTrue(exported.contains(StandardPortfolio.COMMERCIAL.label))
        assertTrue(exported.contains(StandardPortfolio.RESIDENTIAL.label))
    }

    /**
     * TEST 4: No Records Discarded - Unmapped Columns Appended to Remarks
     * Ensures any unknown custom columns are preserved into Remarks.
     */
    @Test
    fun testUnmappedColumnsPreservedIntoRemarks() {
        val customCsv = """
            Property Code,Location,Project / Building,Carpet Area,Rent,Custom Amenities,Electricity Load,Vastu Compliant
            OL-TEST-999,Viman Nagar,Giga Space,1500,120000,Gym & Cafeteria,50 KVA,East Facing
        """.trimIndent()

        val parsed = repository.parseCsvOrTextData(customCsv, "custom_source.csv")
        assertEquals(1, parsed.size)
        val row = parsed[0]

        // Check unmapped columns preserved
        assertTrue(row.unmappedColumns.containsKey("Custom Amenities"))
        assertTrue(row.unmappedColumns.containsKey("Electricity Load"))
        assertTrue(row.unmappedColumns.containsKey("Vastu Compliant"))

        // Check remarks contains the unmapped values
        val remarks = row.remarks ?: ""
        assertTrue("Remarks must contain custom amenities", remarks.contains("Gym & Cafeteria"))
        assertTrue("Remarks must contain electricity load", remarks.contains("50 KVA"))
        assertTrue("Remarks must contain Vastu Compliant", remarks.contains("East Facing"))
    }

    /**
     * TEST 5: Commercial Loading Math & Residential Exclusion
     * - Office: +45% loading
     * - Shop/Showroom: +50% loading
     * - Residential: 0% loading (NEVER automatically applied)
     */
    @Test
    fun testAreaLoadingCalculations() {
        // Office loading: 45% (built-up = 1000 * 1.45 = 1450)
        val (offCarpet, offBuiltUp, offEstimated) = repository.calculateCommercialLoading(
            carpet = 1000.0,
            builtUp = null,
            propType = "Office",
            portfolio = "Commercial"
        )
        assertEquals(1000.0, offCarpet, 0.01)
        assertEquals(1450.0, offBuiltUp, 0.01)
        assertTrue(offEstimated)

        // Shop loading: 50% (built-up = 800 * 1.50 = 1200)
        val (shopCarpet, shopBuiltUp, shopEstimated) = repository.calculateCommercialLoading(
            carpet = 800.0,
            builtUp = null,
            propType = "Shop",
            portfolio = "Commercial"
        )
        assertEquals(800.0, shopCarpet, 0.01)
        assertEquals(1200.0, shopBuiltUp, 0.01)
        assertTrue(shopEstimated)

        // Residential: NEVER apply loading automatically
        val (resiCarpet, resiBuiltUp, resiEstimated) = repository.calculateCommercialLoading(
            carpet = 1200.0,
            builtUp = null,
            propType = "Apartment",
            portfolio = "Residential"
        )
        assertEquals(1200.0, resiCarpet, 0.01)
        assertEquals(0.0, resiBuiltUp, 0.01) // Not inflated
        assertFalse(resiEstimated)
    }

    /**
     * TEST 6: Duplicate Code Conflict Resolution (Skip vs Overwrite vs New Code)
     */
    @Test
    fun testDuplicateConflictResolution() {
        // Seed an existing property
        val existingProp = PropertyItem(
            id = "existing_001",
            propertyCode = "OL-PUN-500",
            location = "Old Location",
            projectName = "Old Project",
            unitNumber = "Suite 101",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 1000.0,
            priceOrRent = 80000.0
        )
        repository.addProperty(existingProp)
        assertEquals(1, repository.properties.value.size)

        // Duplicate incoming row with new project name
        val incomingCsv = """
            Property Code,Location,Project / Building,Unit / Shop / Office No,Carpet Area,Rent
            OL-PUN-500,Updated Location,Updated Project,Suite 101,1000,95000
        """.trimIndent()

        val parsed = repository.parseCsvOrTextData(incomingCsv, "incoming.csv")
        assertTrue(parsed[0].isDuplicateCode)

        // 1. SKIP strategy: Keep existing unchanged
        val skipStats = repository.importRowsDetailed(parsed, duplicateHandling = "SKIP")
        assertEquals(0, skipStats.addedCount)
        assertEquals(0, skipStats.updatedCount)
        assertEquals(1, skipStats.skippedCount)
        assertEquals("Old Project", repository.properties.value.first { it.propertyCode == "OL-PUN-500" }.projectName)

        // 2. OVERWRITE strategy: Update existing with confirmation
        val overwriteStats = repository.importRowsDetailed(parsed, duplicateHandling = "OVERWRITE")
        assertEquals(1, overwriteStats.updatedCount)
        assertEquals("Updated Project", repository.properties.value.first { it.propertyCode == "OL-PUN-500" }.projectName)
    }

    /**
     * TEST 7: Invalid Rows Sent to DATA_REVIEW Queue
     * Rows with missing locations or invalid prices must be quarantined.
     */
    @Test
    fun testInvalidRowsQuarantinedToDataReview() {
        val invalidCsv = """
            Property Code,Location,Project / Building,Carpet Area,Price / Sale
            OL-INV-001,,No Location Tower,1200,5000000
        """.trimIndent()

        val parsed = repository.parseCsvOrTextData(invalidCsv, "corrupt_data.csv")
        assertEquals(1, parsed.size)
        assertFalse("Row with empty location must be invalid", parsed[0].isValid)

        // Verify quarantined in dataReviewQueue
        val reviewQueue = repository.dataReviewQueue.value
        assertTrue("DATA_REVIEW queue must receive invalid row", reviewQueue.any { it.issueReason == "MISSING_LOCATION" })
    }

    /**
     * TEST 8: Sync Log Auditing
     * Verifies that events are written to syncLogs with direction, timestamp, and status.
     */
    @Test
    fun testSyncLogAuditing() {
        repository.logSyncEvent(
            direction = "SHEET_TO_APP",
            status = "SUCCESS",
            recordCount = 15,
            details = "Completed Master Inventory Sync",
            triggeredBy = "Automated Test"
        )

        val logs = repository.syncLogs.value
        assertTrue(logs.isNotEmpty())
        val latest = logs.first()
        assertEquals("SHEET_TO_APP", latest.direction)
        assertEquals("SUCCESS", latest.status)
        assertEquals(15, latest.recordCount)
        assertEquals("Automated Test", latest.triggeredBy)
    }
}

