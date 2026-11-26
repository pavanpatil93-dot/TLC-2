package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiProductivityEngine
import com.example.data.master.MasterProjectDatabase
import com.example.model.AvailabilityStatus
import com.example.model.FurnishingType
import com.example.model.InventoryTab
import com.example.model.PropertyItem
import com.example.ui.components.buildMultiPropertyShareMessage
import com.example.ui.shortlist.ShortlistMessageFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TlcMasterSheetSchemaV3Test {

    // -------------------------------------------------------------------------
    // 1. SAFE ROOM MIGRATION (v2 -> v3) — ZERO DATA LOSS
    // -------------------------------------------------------------------------
    @Test
    fun migration2To3_preservesExistingUnits_andAddsAll8NullableColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_master_migration_2_3.db"
        context.deleteDatabase(dbName)

        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // Exact v2 schema for unit_inventory_master prior to MIGRATION_2_3
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `unit_inventory_master` (
                        `unitId` TEXT NOT NULL PRIMARY KEY,
                        `projectId` TEXT NOT NULL,
                        `propertyCode` TEXT NOT NULL,
                        `towerBuilding` TEXT,
                        `unitNumber` TEXT,
                        `floor` INTEGER,
                        `areaSqFt` REAL NOT NULL,
                        `pricing` REAL NOT NULL,
                        `rentAmount` REAL,
                        `tab` TEXT NOT NULL,
                        `furnishing` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `ownerName` TEXT,
                        `ownerContact` TEXT,
                        `isArchived` INTEGER NOT NULL DEFAULT 0,
                        `lastUpdated` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `unit_inventory_master` (
                        unitId, projectId, propertyCode, towerBuilding, unitNumber, floor,
                        areaSqFt, pricing, rentAmount, tab, furnishing, status,
                        ownerName, ownerContact, isArchived, lastUpdated
                    ) VALUES (
                        'UNIT-LEGACY-01', 'CP-0001', 'OL-KHD-801', 'Tower 4', '801', 8,
                        2400.0, 36000000.0, 168000.0, 'Off Lease', 'Fully Furnished', 'Available',
                        'Rajesh Singhania', '9822011223', 0, 1700000000000
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )

        val db = helper.writableDatabase
        try {
            // Execute MIGRATION_2_3 and verify idempotency on second run
            MasterProjectDatabase.MIGRATION_2_3.migrate(db)
            MasterProjectDatabase.MIGRATION_2_3.migrate(db)

            val expectedColumns = setOf(
                "microMarket",
                "attachedPantry",
                "attachedWashroom",
                "frontageFt",
                "clearHeightFt",
                "truckAccess",
                "powerLoad",
                "targetPossessionDate"
            )

            val actualColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`unit_inventory_master`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    actualColumns.add(cursor.getString(nameIdx))
                }
            }
            expectedColumns.forEach { col ->
                assertTrue("Expected migrated column '$col' to exist", col in actualColumns)
            }

            // Existing row must remain completely intact with NULL defaults for new columns
            db.query("SELECT * FROM `unit_inventory_master` WHERE unitId = 'UNIT-LEGACY-01'").use { cursor ->
                assertTrue("Existing legacy unit must be preserved", cursor.moveToFirst())
                assertEquals("CP-0001", cursor.getString(cursor.getColumnIndexOrThrow("projectId")))
                assertEquals("OL-KHD-801", cursor.getString(cursor.getColumnIndexOrThrow("propertyCode")))
                assertEquals(2400.0, cursor.getDouble(cursor.getColumnIndexOrThrow("areaSqFt")), 0.001)
                expectedColumns.forEach { col ->
                    assertTrue(
                        "Migrated column '$col' must default to NULL on existing row",
                        cursor.isNull(cursor.getColumnIndexOrThrow(col))
                    )
                }
            }

            // Verify new columns accept updates cleanly
            db.execSQL(
                """
                UPDATE `unit_inventory_master`
                SET microMarket = 'EON IT Park Corridor',
                    attachedPantry = 'Yes',
                    attachedWashroom = 'Yes',
                    frontageFt = 32.5,
                    clearHeightFt = 14.0,
                    truckAccess = '40ft Container',
                    powerLoad = '120 kVA',
                    targetPossessionDate = '31/12/2026'
                WHERE unitId = 'UNIT-LEGACY-01'
                """.trimIndent()
            )
            db.query("SELECT * FROM `unit_inventory_master` WHERE unitId = 'UNIT-LEGACY-01'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("EON IT Park Corridor", cursor.getString(cursor.getColumnIndexOrThrow("microMarket")))
                assertEquals("Yes", cursor.getString(cursor.getColumnIndexOrThrow("attachedPantry")))
                assertEquals("Yes", cursor.getString(cursor.getColumnIndexOrThrow("attachedWashroom")))
                assertEquals(32.5, cursor.getDouble(cursor.getColumnIndexOrThrow("frontageFt")), 0.001)
                assertEquals(14.0, cursor.getDouble(cursor.getColumnIndexOrThrow("clearHeightFt")), 0.001)
                assertEquals("40ft Container", cursor.getString(cursor.getColumnIndexOrThrow("truckAccess")))
                assertEquals("120 kVA", cursor.getString(cursor.getColumnIndexOrThrow("powerLoad")))
                assertEquals("31/12/2026", cursor.getString(cursor.getColumnIndexOrThrow("targetPossessionDate")))
            }
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    // -------------------------------------------------------------------------
    // 2. EXACT 5 MASTER SHEET TABS + ROW 1 HEADERS + FORMULA PROTECTION
    // -------------------------------------------------------------------------
    @Test
    fun masterSheetTabs_matchExactSheetNames() {
        assertEquals("Commercial Lease Inventory", TlcInventoryTabV2.COMMERCIAL_LEASE)
        assertEquals("Commercial Resale Inventory", TlcInventoryTabV2.COMMERCIAL_RESALE)
        assertEquals("Residential Under Construction", TlcInventoryTabV2.RESIDENTIAL_UNDER_CONSTRUCTION)
        assertEquals("Residential Resale", TlcInventoryTabV2.RESIDENTIAL_RESALE)
        assertEquals("Residential Rentals", TlcInventoryTabV2.RESIDENTIAL_RENTALS)
    }

    @Test
    fun buildUpsertRecord_mapsExactRow1Headers_formatsDatesDdMmYyyy_andProtectsFormulas() {
        val shopProperty = PropertyItem(
            id = "prop_101",
            recordId = "TLC-SH-101",
            propertyCode = "SH-BAN-101",
            tab = InventoryTab.SHOP_LEASE,
            date = "2026-11-23",
            location = "Baner",
            microMarket = "High Street Corridor",
            projectName = "Solitaire Business Hub",
            projectId = "CP-0099",
            unitNumber = "G-12",
            floor = "Ground",
            propertyType = "Showroom",
            transactionType = "Lease",
            carpetAreaSqFt = 1000.0,
            groundFloorCarpetSqFt = 800.0,
            mezzanineCarpetSqFt = 200.0,
            builtUpAreaSqFt = 1500.0,
            priceOrRent = 240000.0,
            rent = 240000.0,
            attachedPantry = "Yes",
            attachedWashroom = "Yes",
            frontageFt = 28.0,
            clearHeightFt = 15.5,
            parking = "2 Car Parks",
            furnishing = FurnishingType.BARE_SHELL,
            possession = "Immediate",
            status = AvailabilityStatus.AVAILABLE,
            leaseExpiryDate = "2029-03-15",
            lastVerifiedDateClean = "2026-11-20",
            manualLoadingPercentOverride = null,
            isManualBuiltUpOverride = false
        )

        val record = TlcMasterSheetMapper.buildUpsertRecord(shopProperty)

        // Exact Row 1 Headers
        assertEquals("Baner", record.getString("Location"))
        assertEquals("High Street Corridor", record.getString("Micro-Market"))
        assertEquals("Yes", record.getString("Attached Pantry"))
        assertEquals("Yes", record.getString("Attached Washroom"))
        assertEquals(28.0, record.getDouble("Frontage (Ft)"), 0.001)
        assertEquals(15.5, record.getDouble("Clear Height (Ft)"), 0.001)
        assertEquals(800.0, record.getDouble("Ground Floor Carpet (Sq Ft)"), 0.001)
        assertEquals(200.0, record.getDouble("Mezzanine Carpet (Sq Ft)"), 0.001)

        // Dates strictly dd/MM/yyyy
        assertEquals("23/11/2026", record.getString("Date Added"))
        assertEquals("15/03/2029", record.getString("Lease End"))
        assertEquals("20/11/2026", record.getString("Last Verified"))

        // Formula Protection: ALL 11 formula-driven columns must be omitted by default
        TlcMasterSheetMapper.ALL_FORMULA_PROTECTED_HEADERS.forEach { formulaCol ->
            assertFalse(
                "Formula column '$formulaCol' MUST NOT be sent on default UPSERT",
                record.has(formulaCol)
            )
        }

        // Even when manualLoadingPercentOverride / isManualBuiltUpOverride are set on PropertyItem,
        // ALL formula-calculated sheet columns (including Loading % and Total Built-Up Area (Sq Ft))
        // MUST NEVER be overwritten on UPSERT so Google Sheet formulas stay intact.
        val overridden = shopProperty.copy(
            manualLoadingPercentOverride = 55.0,
            isManualBuiltUpOverride = true,
            builtUpAreaSqFt = 1550.0
        )
        val overriddenRecord = TlcMasterSheetMapper.buildUpsertRecord(overridden)
        assertFalse("Loading % formula column must NOT be overwritten on UPSERT", overriddenRecord.has("Loading %"))
        assertFalse(
            "Total Built-Up Area (Sq Ft) formula column must NOT be overwritten on UPSERT",
            overriddenRecord.has("Total Built-Up Area (Sq Ft)")
        )
        TlcMasterSheetMapper.ALWAYS_FORMULA_PROTECTED_HEADERS.forEach { alwaysProtected ->
            assertFalse(
                "Always-protected formula column '$alwaysProtected' must remain stripped",
                overriddenRecord.has(alwaysProtected)
            )
        }
    }

    @Test
    fun buildUpsertRecord_warehouseAndResaleFields_mappedAccurately() {
        val warehouseResale = PropertyItem(
            id = "prop_wh_1",
            recordId = "TLC-WH-1",
            propertyCode = "WH-CHK-01",
            tab = InventoryTab.OFF_OUT,
            date = "2026-11-23",
            location = "Chakan",
            microMarket = "MIDC Phase 2",
            projectName = "Indospace Chakan",
            projectId = "CP-0500",
            unitNumber = "W-04",
            floor = "Ground",
            propertyType = "Warehouse",
            transactionType = "Sale",
            carpetAreaSqFt = 12000.0,
            priceOrRent = 48000000.0,
            priceSale = 48000000.0,
            attachedPantry = "Yes",
            attachedWashroom = "Yes",
            clearHeightFt = 32.0,
            truckAccess = "40ft Container / 2 Dock Levelers",
            powerLoad = "250 kVA",
            possession = "Under Construction",
            targetPossessionDate = "2027-06-30",
            isPreLeased = true,
            tenantName = "DHL Supply Chain",
            monthlyRentalIncome = 320000.0
        )

        val record = TlcMasterSheetMapper.buildUpsertRecord(warehouseResale)
        assertEquals("Chakan", record.getString("Location"))
        assertEquals("MIDC Phase 2", record.getString("Micro-Market"))
        assertEquals(32.0, record.getDouble("Clear Height (Ft)"), 0.001)
        assertEquals("40ft Container / 2 Dock Levelers", record.getString("Truck Access"))
        assertEquals("250 kVA", record.getString("Power Load"))
        assertEquals("30/06/2027", record.getString("Target Possession Date"))
        assertEquals("Yes", record.getString("Pre-Leased Status"))
        assertEquals("DHL Supply Chain", record.getString("Tenant Name"))
        assertEquals(320000.0, record.getDouble("Current Monthly Rent"), 0.001)
        assertEquals("", record.getString("Mezzanine Carpet (Sq Ft)"))
        assertFalse(record.has("Project ID"))
        assertFalse(record.has("Current ROI / Yield (%)"))
    }

    // -------------------------------------------------------------------------
    // 3. PULL PARSER: EXACT ROW 1 HEADERS -> ImportPreviewRow
    // -------------------------------------------------------------------------
    @Test
    fun sheetsRepository_parseCsvOrTextData_preservesLocationMicroMarketProjectIdAndCommercialCols() {
        val csv = listOf(
            "Property ID,Date Added,Location,Micro-Market,Project ID,Project / Building,Unit No,Property Type,Transaction Type,Ground Floor Carpet (Sq Ft),Mezzanine Carpet (Sq Ft),Monthly Rent (₹),Attached Pantry,Attached Washroom,Frontage (Ft),Clear Height (Ft),Truck Access,Power Load,Target Possession Date",
            "TLC-100,2026-11-23,Kharadi,EON IT Park Corridor,CP-0001,World Trade Center (WTC),G-05,Showroom,Lease,900,250,275000,Yes,Yes,30,16,20ft Tempo,65 kVA,2027-01-15"
        ).joinToString("\n")

        val repo = SheetsRepository(context = null, startForegroundSync = false)
        val rows = repo.parseCsvOrTextData(csv, fileName = "Commercial Lease Inventory.csv")
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("Kharadi", row.location)
        assertEquals("EON IT Park Corridor", row.microMarket)
        assertEquals("CP-0001", row.projectId)
        assertEquals("World Trade Center (WTC)", row.projectName)
        assertEquals("Yes", row.attachedPantry)
        assertEquals("Yes", row.attachedWashroom)
        assertEquals(30.0, row.frontageFt ?: 0.0, 0.001)
        assertEquals(16.0, row.clearHeightFt ?: 0.0, 0.001)
        assertEquals("20ft Tempo", row.truckAccess)
        assertEquals("65 kVA", row.powerLoad)
        assertEquals("15/01/2027", row.targetPossessionDate)
        assertEquals(1150.0, row.carpetAreaSqFt, 0.001)
    }

    // -------------------------------------------------------------------------
    // 4. OFFLINE CALCULATOR & VALIDATION RULES
    // -------------------------------------------------------------------------
    @Test
    fun calculator_shopAndOfficeLoading_dualRates_roi_andUnitValidation() {
        // Shop/Showroom: Ground 800 + Mezzanine 200 = 1000 Total Carpet; auto 50% Built-Up = 1500
        val shopCalc = TlcPropertyCalculator.calculateAreaMetrics(
            groundFloorCarpet = 800.0,
            mezzanineCarpet = 200.0,
            directCarpet = null,
            builtUpInput = null,
            propertyType = "Showroom",
            isCommercial = true,
            tab = InventoryTab.SHOP_LEASE
        )
        assertEquals(1000.0, shopCalc.totalCarpetSqFt, 0.001)
        assertEquals(1500.0, shopCalc.totalBuiltUpSqFt ?: 0.0, 0.001)
        assertEquals(50.0, shopCalc.loadingPercent ?: 0.0, 0.001)
        assertTrue(shopCalc.isBuiltUpAutoCalculated)
        assertFalse(shopCalc.isManualBuiltUpOrLoadingOverride)

        // Office: Mezzanine must be zeroed/ignored; Carpet 1000 -> auto 45% Built-Up = 1450
        val officeForward = TlcPropertyCalculator.calculateAreaMetrics(
            groundFloorCarpet = 1000.0,
            mezzanineCarpet = 300.0,
            directCarpet = 1000.0,
            builtUpInput = null,
            propertyType = "Office Space",
            isCommercial = true,
            tab = InventoryTab.OFF_LEASE
        )
        assertNull(officeForward.mezzanineCarpetSqFt)
        assertEquals(1000.0, officeForward.totalCarpetSqFt, 0.001)
        assertEquals(1450.0, officeForward.totalBuiltUpSqFt ?: 0.0, 0.001)
        assertEquals(45.0, officeForward.loadingPercent ?: 0.0, 0.001)

        // Reverse: Only Built-Up 1450 entered for Office -> auto Carpet = 1000
        val officeReverse = TlcPropertyCalculator.calculateAreaMetrics(
            groundFloorCarpet = null,
            mezzanineCarpet = null,
            directCarpet = null,
            builtUpInput = 1450.0,
            propertyType = "Office Space",
            isCommercial = true,
            tab = InventoryTab.OFF_LEASE
        )
        assertEquals(1000.0, officeReverse.totalCarpetSqFt, 0.001)
        assertTrue(officeReverse.isCarpetAutoCalculated)

        // Both Carpet (1000) & Built-Up (1600) entered -> actual Loading % = 60.0 and manual override true
        val manualBoth = TlcPropertyCalculator.calculateAreaMetrics(
            groundFloorCarpet = null,
            mezzanineCarpet = null,
            directCarpet = 1000.0,
            builtUpInput = 1600.0,
            propertyType = "Office Space",
            isCommercial = true,
            tab = InventoryTab.OFF_LEASE
        )
        assertEquals(60.0, manualBoth.loadingPercent ?: 0.0, 0.001)
        assertTrue(manualBoth.isManualBuiltUpOrLoadingOverride)

        // Dual rates
        val dualRates = TlcPropertyCalculator.calculateDualRates(
            primaryAmount = 150000.0,
            totalCarpetSqFt = 1000.0,
            totalBuiltUpSqFt = 1500.0
        )
        assertEquals(150.0, dualRates.ratePerCarpetSqFt ?: 0.0, 0.001)
        assertEquals(100.0, dualRates.ratePerBuiltUpSqFt ?: 0.0, 0.001)

        // Pre-Leased Gross ROI % = (150,000 * 12 / 25,000,000) * 100 = 7.2%
        val roi = TlcPropertyCalculator.calculateGrossRoiPercent(
            monthlyRent = 150000.0,
            baseValue = 25000000.0
        )
        assertNotNull(roi)
        assertEquals(7.2, roi!!, 0.001)

        // Unit Number validation: required for Direct Owner & Developer, optional for Broker
        assertTrue(TlcPropertyCalculator.isUnitNumberRequired("Property Owner"))
        assertTrue(TlcPropertyCalculator.isUnitNumberRequired("Direct Owner"))
        assertTrue(TlcPropertyCalculator.isUnitNumberRequired("Developer Sales Team"))
        assertFalse(TlcPropertyCalculator.isUnitNumberRequired("Broker"))
        assertFalse(TlcPropertyCalculator.isUnitNumberRequired("Channel Partner"))
    }

    // -------------------------------------------------------------------------
    // 5. CLIENT WHATSAPP SHARE & SHORTLIST PRIVACY GUARD
    // -------------------------------------------------------------------------
    @Test
    fun clientWhatsAppShareAndShortlist_includeMasterFields_andStrictlyHidePrivateFields() {
        val property = PropertyItem(
            id = "prop_share_1",
            propertyCode = "PL-KHD-901",
            tab = InventoryTab.PRE_LEASED,
            location = "Kharadi",
            microMarket = "EON Free Zone Belt",
            projectName = "World Trade Center (WTC)",
            projectId = "CP-0001",
            unitNumber = "SECRET-UNIT-904B",
            floor = "9th",
            propertyType = "Showroom",
            transactionType = "Pre-Leased Sale",
            carpetAreaSqFt = 1000.0,
            builtUpAreaSqFt = 1500.0,
            priceOrRent = 25000000.0,
            priceSale = 25000000.0,
            isPreLeased = true,
            tenantName = "HDFC Bank",
            monthlyRentalIncome = 150000.0,
            attachedPantry = "Yes",
            attachedWashroom = "Yes",
            frontageFt = 30.0,
            clearHeightFt = 15.0,
            truckAccess = "20ft Tempo Bay",
            powerLoad = "75 kVA",
            targetPossessionDate = "31/12/2026",
            parking = "3 Covered",
            furnishing = FurnishingType.FULLY_FURNISHED,
            possession = "Under Construction",
            ownerName = "CONFIDENTIAL OWNER RAJESH",
            ownerContact = "9988776655",
            brokerName = "SECRET BROKER SURESH",
            brokerContact = "9123456780",
            sourceInfo = "PRIVATE REFERRAL SOURCE XYZ"
        )

        val whatsappClientMsg = buildMultiPropertyShareMessage(
            properties = listOf(property),
            employeeName = "Pavan Patil",
            employeeMobile = "9766880099"
        )

        val shortlistMsg = ShortlistMessageFormatter.formatPrivacySafeShortlist(
            properties = listOf(property),
            employeeName = "Pavan Patil"
        )

        val aiContext = GeminiProductivityEngine.buildPrivacySafePropertyContext(
            properties = listOf(property),
            employeeName = "Pavan Patil",
            employeeMobile = "9766880099"
        )

        listOf(
            "WhatsApp Client Share" to whatsappClientMsg,
            "Shortlist Share" to shortlistMsg,
            "AI Pitch Context" to aiContext
        ).forEach { (label, output) ->
            // Must include new public Master Sheet details
            assertTrue("$label should include Location", output.contains("Kharadi"))
            assertTrue("$label should include Micro-Market", output.contains("EON Free Zone Belt"))
            assertTrue("$label should include Attached Pantry", output.contains("Pantry", ignoreCase = true))
            assertTrue("$label should include Attached Washroom", output.contains("Washroom", ignoreCase = true))
            assertTrue("$label should include Frontage", output.contains("30"))
            assertTrue("$label should include Clear Height", output.contains("15"))
            assertTrue("$label should include Truck Access", output.contains("20ft Tempo Bay"))
            assertTrue("$label should include Power Load", output.contains("75 kVA"))
            assertTrue("$label should include Target Possession Date", output.contains("31/12/2026"))
            assertTrue("$label should include Gross ROI 7.2%", output.contains("7.2"))

            // Must STRICTLY hide Owner Name, Owner Phone, Broker, Unit No., and Source
            assertFalse("$label must hide Owner Name", output.contains("CONFIDENTIAL OWNER RAJESH", ignoreCase = true))
            assertFalse("$label must hide Owner Phone", output.contains("9988776655"))
            assertFalse("$label must hide Broker Name", output.contains("SECRET BROKER SURESH", ignoreCase = true))
            assertFalse("$label must hide Broker Phone", output.contains("9123456780"))
            assertFalse("$label must hide Unit No.", output.contains("SECRET-UNIT-904B", ignoreCase = true))
            assertFalse("$label must hide Source", output.contains("PRIVATE REFERRAL SOURCE XYZ", ignoreCase = true))
        }
    }
}
