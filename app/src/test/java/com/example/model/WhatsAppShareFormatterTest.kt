package com.example.model

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.MatchingBrokerResolver
import com.example.ui.components.WhatsAppShareHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhatsAppShareFormatterTest {

    // -------------------------------------------------------------------------
    // 1. ALL 13 TRANSACTION TEMPLATES RESOLUTION
    // -------------------------------------------------------------------------
    @Test
    fun all13TransactionTemplates_resolveExpectedHeadlines() {
        val cases = listOf(
            PropertyItem(tab = InventoryTab.RESI_RENT, portfolio = "Residential", propertyType = "Apartment", transactionType = "Rent")
                to UnifiedShareTransactionTemplate.RESIDENTIAL_RENTAL,
            PropertyItem(tab = InventoryTab.RESI_RESALE, portfolio = "Residential", propertyType = "Apartment", transactionType = "Resale")
                to UnifiedShareTransactionTemplate.RESIDENTIAL_RESALE,
            PropertyItem(tab = InventoryTab.UND_CONST, portfolio = "Residential", propertyType = "New Residential", transactionType = "Sale")
                to UnifiedShareTransactionTemplate.NEW_RESIDENTIAL_SALE,
            PropertyItem(tab = InventoryTab.OFF_LEASE, portfolio = "Commercial", propertyType = "Office Space", transactionType = "Lease")
                to UnifiedShareTransactionTemplate.COMMERCIAL_OFFICE_LEASE,
            PropertyItem(tab = InventoryTab.OFF_OUT, portfolio = "Commercial", propertyType = "Office Space", transactionType = "Sale")
                to UnifiedShareTransactionTemplate.COMMERCIAL_OFFICE_SALE,
            PropertyItem(tab = InventoryTab.PRE_LEASED, portfolio = "Commercial", propertyType = "Office Space", transactionType = "Pre-Leased Sale", isPreLeased = true)
                to UnifiedShareTransactionTemplate.PRE_LEASED_COMMERCIAL_INVESTMENT,
            PropertyItem(tab = InventoryTab.SHOP_LEASE, portfolio = "Commercial", propertyType = "Showroom", transactionType = "Lease")
                to UnifiedShareTransactionTemplate.SHOP_RETAIL_LEASE,
            PropertyItem(tab = InventoryTab.SHOP_OUT, portfolio = "Commercial", propertyType = "Shop", transactionType = "Sale")
                to UnifiedShareTransactionTemplate.SHOP_SHOWROOM_SALE,
            PropertyItem(tab = InventoryTab.OFF_OUT, portfolio = "Commercial", propertyType = "Commercial Land / Plot", transactionType = "Sale")
                to UnifiedShareTransactionTemplate.LAND_PLOT_SALE,
            PropertyItem(tab = InventoryTab.OFF_OUT, portfolio = "Commercial", propertyType = "Entire Building", transactionType = "Sale")
                to UnifiedShareTransactionTemplate.ENTIRE_BUILDING,
            PropertyItem(tab = InventoryTab.OFF_LEASE, portfolio = "Commercial", propertyType = "Warehouse / Industrial", transactionType = "Lease")
                to UnifiedShareTransactionTemplate.WAREHOUSE_INDUSTRIAL,
            PropertyItem(tab = InventoryTab.OFF_OUT, portfolio = "Commercial", propertyType = "JV / Redevelopment", transactionType = "Joint Venture")
                to UnifiedShareTransactionTemplate.JV_JD_REDEVELOPMENT,
            PropertyItem(tab = InventoryTab.OFF_OUT, portfolio = "Commercial", propertyType = "Hotel / Hospitality", transactionType = "Sale")
                to UnifiedShareTransactionTemplate.HOTEL_HOSPITALITY
        )

        cases.forEach { (prop, expected) ->
            assertEquals(expected, WhatsAppShareFormatter.resolveTransactionTemplate(prop))
            val msg = WhatsAppShareFormatter.formatProperty(prop, "Pavan Patil", "9766880099")
            assertTrue("Message must start with ${expected.headline}", msg.startsWith(expected.headline))
        }
    }

    // -------------------------------------------------------------------------
    // 2. STRICT PRIVACY + NO DEVELOPER/RERA + BUILDING HIGHLIGHTS LOWER PORTION
    // -------------------------------------------------------------------------
    @Test
    fun formatProperty_stripsPrivateAndDeveloperReraFields_andPlacesBuildingHighlightsInLowerPortion() {
        val property = PropertyItem(
            id = "prop_office_1",
            propertyCode = "OL-KHD-801",
            tab = InventoryTab.OFF_LEASE,
            location = "Kharadi",
            microMarket = "EON IT Park Corridor",
            projectName = "World Trade Center (WTC)",
            projectId = "CP-0001",
            unitNumber = "SECRET-OFFICE-804B",
            floor = "8th Floor",
            propertyType = "Office Space",
            transactionType = "Lease",
            carpetAreaSqFt = 2000.0,
            builtUpAreaSqFt = 2900.0,
            priceOrRent = 290000.0,
            rent = 290000.0,
            deposit = 6.0,
            maintenance = 25000.0,
            lockInMonths = 36,
            leaseDurationYears = 5,
            escalationPercent = 5.0,
            escalationIntervalYears = 1,
            possession = "Immediate",
            furnishing = FurnishingType.FULLY_FURNISHED,
            workstations = 32,
            cabins = 3,
            conferenceRooms = 1,
            attachedPantry = "Yes",
            attachedWashroom = "Yes",
            parking = "3 Covered Car Parks",
            builderName = "Panchshil Realty Pvt Ltd", // MUST NOT APPEAR
            ownerName = "CONFIDENTIAL OWNER RAJESH", // MUST NOT APPEAR
            ownerContact = "9988776655",             // MUST NOT APPEAR
            brokerName = "SECRET BROKER SURESH",     // MUST NOT APPEAR
            brokerContact = "9123456780",            // MUST NOT APPEAR
            sourceInfo = "PRIVATE REFERRAL XYZ",     // MUST NOT APPEAR
            remarks = "Owner desperate to close",    // MUST NOT APPEAR
            internalRemarks = "Commission 2 months", // MUST NOT APPEAR
            googleMapsUrl = "https://maps.google.com/?q=18.5515,73.9477",
            unmappedColumns = mapOf(
                "RERA Number" to "P52100001234",     // MUST NOT APPEAR
                "Developer" to "Panchshil Realty"    // MUST NOT APPEAR
            )
        )

        val highlights = LinkedProjectBuildingHighlights(
            powerBackupSpec = "100% DG Backup",
            hvacSystem = "Centralized Chiller HVAC",
            carParkingRatio = "1:1,000 Sq.Ft.",
            ocStatus = "OC Received",
            keyOccupantsAnchors = "Barclays, UBS, Credit Suisse",
            amenities = listOf("24x7 Security", "Food Court", "High-Speed Elevators")
        )

        val msg = WhatsAppShareFormatter.formatProperty(
            property = property,
            employeeName = "Pavan Patil",
            employeeMobile = "9766880099",
            linkedProjectHighlights = highlights
        )

        // Header checks
        assertTrue(msg.contains("*COMMERCIAL OFFICE SPACE FOR LEASE*"))
        assertTrue(msg.contains("📍 *Location:* Kharadi (EON IT Park Corridor), Pune"))
        assertTrue(msg.contains("🏢 *Project / Building:* World Trade Center (WTC)"))
        assertTrue(msg.contains("🏙️ *Floor:* 8th Floor"))

        // Space & Specs checks
        assertTrue(msg.contains("📐 *Carpet Area:* 2,000 Sq.Ft."))
        assertTrue(msg.contains("📐 *Built-Up Area:* 2,900 Sq.Ft."))
        assertTrue(msg.contains("🛋️ *Condition / Fit-out:* Fully Furnished"))
        assertTrue(msg.contains("Workstations: 32"))
        assertTrue(msg.contains("Cabins: 3"))
        assertTrue(msg.contains("Conference Rooms: 1"))
        assertTrue(msg.contains("☕ *Attached Pantry:* Yes"))
        assertTrue(msg.contains("🚻 *Attached Washroom:* Yes"))
        assertTrue(msg.contains("🚗 *Car Parking:* 3 Covered Car Parks"))

        // Financials & Dual PSF checks (290,000 / 2,000 = 145 Carpet; 290,000 / 2,900 = 100 Built-Up)
        assertTrue(msg.contains("💰 *Monthly Rent:* ₹"))
        assertTrue(msg.contains("📊 *Rate / Sq.Ft:* ₹145 (Carpet) | ₹100 (Built-Up)"))
        assertTrue(msg.contains("🔐 *Deposit:* 6 Months"))
        assertTrue(msg.contains("🔒 *Lock-in:* 3 Years"))
        assertTrue(msg.contains("📄 *Tenure:* 5 Years"))
        assertTrue(msg.contains("📈 *Escalation:* 5% every 1 Yr"))
        assertTrue(msg.contains("📅 *Possession:* Immediate"))

        // Lower-portion Building Highlights & Google Maps placement
        val financialIdx = msg.indexOf("💰 *Monthly Rent:*")
        val highlightsIdx = msg.indexOf("✨ *Building Highlights:*")
        val mapsIdx = msg.indexOf("📍 *Google Maps:*")
        val footerIdx = msg.indexOf("📞 *Contact for Details & Site Visit*")
        assertTrue("Building Highlights must be present", highlightsIdx > 0)
        assertTrue("Building Highlights must appear AFTER Financials", highlightsIdx > financialIdx)
        assertTrue("Google Maps must appear AFTER Building Highlights", mapsIdx > highlightsIdx)
        assertTrue("Standard Footer must appear AFTER Google Maps", footerIdx > mapsIdx)
        assertTrue(msg.contains("• Power Backup: 100% DG Backup"))
        assertTrue(msg.contains("• HVAC: Centralized Chiller HVAC"))
        assertTrue(msg.contains("• Car Parking Ratio: 1:1,000 Sq.Ft."))
        assertTrue(msg.contains("• OC Status: OC Received"))
        assertTrue(msg.contains("• Key Occupants / Anchors: Barclays, UBS, Credit Suisse"))
        assertTrue(msg.contains("• Amenities: 24x7 Security, Food Court, High-Speed Elevators"))

        // Standard Footer checks
        assertTrue(msg.contains("Pavan Patil | 9766880099"))
        assertTrue(msg.contains("*The Leasing Company*"))
        assertTrue(msg.contains("*Property Code:* OL-KHD-801 • *Availability subject to confirmation.*"))

        // Strict Privacy & Exclusion checks
        assertFalse("Must hide Owner Name", msg.contains("CONFIDENTIAL OWNER RAJESH", ignoreCase = true))
        assertFalse("Must hide Owner Phone", msg.contains("9988776655"))
        assertFalse("Must hide Broker Name", msg.contains("SECRET BROKER SURESH", ignoreCase = true))
        assertFalse("Must hide Broker Phone", msg.contains("9123456780"))
        assertFalse("Must hide Source", msg.contains("PRIVATE REFERRAL XYZ", ignoreCase = true))
        assertFalse("Must hide Remarks", msg.contains("Owner desperate", ignoreCase = true))
        assertFalse("Must hide Internal Remarks", msg.contains("Commission 2 months", ignoreCase = true))
        assertFalse("Must hide exact Unit No.", msg.contains("SECRET-OFFICE-804B", ignoreCase = true))
        assertFalse("Must exclude Developer Name", msg.contains("Panchshil", ignoreCase = true))
        assertFalse("Must exclude RERA Number", msg.contains("P52100001234", ignoreCase = true))
        assertFalse("Must exclude RERA label", msg.contains("RERA", ignoreCase = true))
    }

    // -------------------------------------------------------------------------
    // 3. PRE-LEASED ROI & CONDITIONAL GATING
    // -------------------------------------------------------------------------
    @Test
    fun preLeasedBlock_rendersOnlyWhenPreLeasedAndTenantAndRentExist() {
        val validPreLeased = PropertyItem(
            id = "pl_1",
            propertyCode = "PL-BAN-101",
            tab = InventoryTab.PRE_LEASED,
            location = "Baner",
            projectName = "Solitaire Business Hub",
            propertyType = "Showroom",
            transactionType = "Pre-Leased Sale",
            isPreLeased = true,
            carpetAreaSqFt = 1000.0,
            groundFloorCarpetSqFt = 800.0,
            mezzanineCarpetSqFt = 200.0,
            builtUpAreaSqFt = 1500.0,
            priceOrRent = 25000000.0,
            priceSale = 25000000.0,
            tenantName = "HDFC Bank",
            monthlyRentalIncome = 150000.0,
            lockInMonths = 36,
            leaseExpiryDate = "2030-03-31",
            unmappedColumns = mapOf("Maint / Tax Paid By" to "Tenant")
        )

        val msg = WhatsAppShareFormatter.formatProperty(validPreLeased, "Pavan Patil", "9766880099")
        assertTrue(msg.contains("*PRE-LEASED COMMERCIAL INVESTMENT*"))
        assertTrue(msg.contains("800 Sq.Ft. (Ground) + 200 Sq.Ft. (Mezzanine) = 1,000 Sq.Ft. Total"))
        assertTrue(msg.contains("🏢 *Tenant:* HDFC Bank"))
        assertTrue(msg.contains("💰 *Monthly Rent:* ₹"))
        assertTrue(msg.contains("💰 *Annual Rent:* ₹"))
        assertTrue(msg.contains("📈 *Gross ROI:* 7.20%"))
        assertTrue(msg.contains("🔒 *Remaining Lock-in:* 3 Years"))
        assertTrue(msg.contains("📅 *Lease Expiry:* 31/03/2030"))
        assertTrue(msg.contains("🧾 *Maint / Tax Paid By:* Tenant"))

        // If Tenant is blank, Pre-Leased block MUST NOT render
        val incompletePreLeased = validPreLeased.copy(tenantName = "")
        val incompleteMsg = WhatsAppShareFormatter.formatProperty(incompletePreLeased, "Pavan Patil", "9766880099")
        assertFalse("Tenant line must be omitted when blank", incompleteMsg.contains("🏢 *Tenant:*"))
        assertFalse("Gross ROI must be omitted when tenant missing", incompleteMsg.contains("📈 *Gross ROI:*"))
    }

    // -------------------------------------------------------------------------
    // 4. BLANK-FIELD OMISSION & NEVER SUBSTITUTE CARPET FOR BUILT-UP
    // -------------------------------------------------------------------------
    @Test
    fun blankFieldsAreOmitted_andCarpetIsNeverSubstitutedForBuiltUp() {
        val sparse = PropertyItem(
            id = "sparse_1",
            propertyCode = "OL-009",
            tab = InventoryTab.OFF_LEASE,
            location = "Viman Nagar",
            microMarket = null,
            projectName = "",
            floor = "",
            carpetAreaSqFt = 1200.0,
            builtUpAreaSqFt = null, // Missing Built-Up!
            priceOrRent = 120000.0,
            rent = 120000.0,
            possession = "",
            parking = "",
            attachedPantry = null,
            attachedWashroom = null,
            workstations = 0,
            cabins = 0
        )

        val msg = WhatsAppShareFormatter.formatProperty(sparse, "Pavan Patil", "9766880099")
        assertTrue(msg.contains("📍 *Location:* Viman Nagar, Pune"))
        assertFalse("No empty parentheses when Micro-Market blank", msg.contains("()"))
        assertFalse("Project line omitted when blank", msg.contains("🏢 *Project / Building:*"))
        assertFalse("Floor line omitted when blank", msg.contains("🏙️ *Floor:*"))
        assertTrue(msg.contains("📐 *Carpet Area:* 1,200 Sq.Ft."))
        assertFalse("Built-Up line MUST be omitted when null (never copy Carpet)", msg.contains("Built-Up Area"))
        assertTrue(msg.contains("📊 *Rate / Sq.Ft:* ₹100 (Carpet)"))
        assertFalse("Built-Up rate MUST NOT appear when Built-Up missing", msg.contains("(Built-Up)"))
        assertFalse("Pantry omitted when null", msg.contains("Attached Pantry"))
        assertFalse("Washroom omitted when null", msg.contains("Attached Washroom"))
        assertFalse("Office Layout omitted when 0", msg.contains("Office Layout"))
        assertFalse("Building Highlights omitted when empty", msg.contains("✨ *Building Highlights:*"))
        assertFalse("Google Maps omitted when absent", msg.contains("📍 *Google Maps:*"))
    }

    // -------------------------------------------------------------------------
    // 5. COMBINED PHOTO + TEXT INTENT BUILDER & LONG-CAPTION CLIPBOARD BACKUP
    // -------------------------------------------------------------------------
    @Test
    fun whatsAppShareHelper_buildsSingleAndMultiplePhotoIntents_andBacksUpLongCaptions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri1 = Uri.parse("content://com.example.fileprovider/internal_cache/wa_share_photos/p1.jpg")
        val uri2 = Uri.parse("content://com.example.fileprovider/internal_cache/wa_share_photos/p2.jpg")

        val singleIntent = WhatsAppShareHelper.buildPhotoAndTextShareIntent(
            context = context,
            message = "Test Listing Details",
            photoUris = listOf(uri1),
            targetPackage = WhatsAppShareHelper.WHATSAPP_PACKAGE
        )
        assertEquals(Intent.ACTION_SEND, singleIntent.action)
        assertEquals("image/*", singleIntent.type)
        assertEquals(WhatsAppShareHelper.WHATSAPP_PACKAGE, singleIntent.`package`)
        assertEquals("Test Listing Details", singleIntent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue((singleIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        val multiIntent = WhatsAppShareHelper.buildPhotoAndTextShareIntent(
            context = context,
            message = "Multi Photo Listing",
            photoUris = listOf(uri1, uri2),
            targetPackage = WhatsAppShareHelper.WHATSAPP_BUSINESS_PACKAGE
        )
        assertEquals(Intent.ACTION_SEND_MULTIPLE, multiIntent.action)
        assertEquals("image/*", multiIntent.type)
        assertEquals(WhatsAppShareHelper.WHATSAPP_BUSINESS_PACKAGE, multiIntent.`package`)
        assertEquals("Multi Photo Listing", multiIntent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue((multiIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        // Long caption (>1,000 chars) auto-copies full text to clipboard
        val longMessage = "A".repeat(1150)
        WhatsAppShareHelper.shareToWhatsApp(
            context = context,
            message = longMessage,
            photoUris = listOf(uri1)
        )
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(longMessage, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    // -------------------------------------------------------------------------
    // 6. MATCHING BROKER RESOLVER RANKING
    // -------------------------------------------------------------------------
    @Test
    fun matchingBrokerResolver_prioritizesAreaMatchedBrokersAndDeduplicatesMobiles() {
        val property = PropertyItem(location = "Kharadi", microMarket = "EON IT Park")
        val brokers = listOf(
            BrokerVendorRecord(id = "B1", name = "Amit Baner", phone = "+91 98220 11111", roleCategory = "Broker / CP", operatingAreas = "Baner, Balewadi"),
            BrokerVendorRecord(id = "B2", name = "Sagar Kharadi", phone = "9822022222", roleCategory = "Broker / CP", operatingAreas = "Kharadi, EON IT Park, Viman Nagar"),
            BrokerVendorRecord(id = "V1", name = "Ramesh Electrician", phone = "9822033333", roleCategory = "Ops / Vendor", subRole = "Electrician", operatingAreas = "Kharadi")
        )
        val resolved = MatchingBrokerResolver.resolveMatchingBrokers(
            property = property,
            brokersVendors = brokers,
            callBrokerNamesByPhone = mapOf("+919822022222" to "Duplicate Sagar", "9822044444" to "Call Broker")
        )

        assertEquals(3, resolved.size) // Electrician excluded, duplicate mobile deduplicated
        assertEquals("Sagar Kharadi", resolved.first().name)
        assertTrue(resolved.first().isAreaMatch)
        assertFalse(resolved.last().isAreaMatch)
    }
}
