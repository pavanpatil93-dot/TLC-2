package com.example.ai

import com.example.model.FurnishingType
import com.example.model.InventoryTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Build01WhatsAppExtractionTest {

    @Test
    fun case01_simpleOfficeLease_extractsCleanlyWithNoFabricatedDefaults() {
        val msg = "Office avl for lease at Gera Imperium Alpha, Kharadi. Unit 504, 5th floor, 1250 sqft CA, Rent 95k, Dep 3 months, Poss Imd."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        assertFalse(result.blocksAddPropertyForm)
        assertEquals(1, result.drafts.size)

        val d = result.drafts.first()
        assertEquals(InventoryTab.OFF_LEASE, d.tab)
        assertEquals("Office", d.propertyType)
        assertEquals("Gera Imperium Alpha", d.projectName)
        assertEquals("Kharadi", d.location)
        assertEquals("504", d.unitNumber)
        assertEquals("5th Floor", d.floor)
        assertEquals(1250.0, d.carpetAreaSqFt, 0.01)
        assertNull(d.builtUpAreaSqFt)
        assertEquals(95_000.0, d.rent!!, 0.01)
        assertEquals(285_000.0, d.deposit!!, 0.01)
        assertEquals("Immediate", d.possession)
        assertNull(d.priceSale)
        assertEquals(ConfidenceTier.HIGH, d.extractedFields["Carpet Area"]?.tier)
        assertEquals(ConfidenceTier.HIGH, d.extractedFields["Monthly Rent"]?.tier)
    }

    @Test
    fun case02_officeSale_outrightCommercialWithCrNormalization() {
        val msg = "Commercial office for outright sale in Amar Business Zone, Baner. 1800 sqft carpet, 2500 sqft BUA, Unit 702, Asking Price 2.45Cr Nego."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals(InventoryTab.OFF_OUT, d.tab)
        assertEquals("Office", d.propertyType)
        assertEquals("Amar Business Zone", d.projectName)
        assertEquals("Baner", d.location)
        assertEquals("702", d.unitNumber)
        assertEquals(1800.0, d.carpetAreaSqFt, 0.01)
        assertEquals(2500.0, d.builtUpAreaSqFt!!, 0.01)
        assertEquals(24_500_000.0, d.priceSale!!, 0.01)
        assertNull(d.rent)
        assertEquals(true, d.negotiable)
    }

    @Test
    fun case03_shopLease_groundFloorAndShorthands() {
        val msg = "Retail shop for lease on GF at Balewadi High Street, Balewadi. 650 sqft CA, BS, Rent 1.2L, SD 6 months, CAM extra."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals(InventoryTab.SHOP_LEASE, d.tab)
        assertEquals("Shop", d.propertyType)
        assertEquals("Balewadi High Street", d.projectName)
        assertEquals("Balewadi", d.location)
        assertEquals("Ground Floor", d.floor)
        assertEquals(FurnishingType.BARE_SHELL, d.furnishing)
        assertEquals(650.0, d.carpetAreaSqFt, 0.01)
        assertEquals(120_000.0, d.rent!!, 0.01)
        assertEquals(720_000.0, d.deposit!!, 0.01)
        assertTrue(d.maintenanceTerms.contains("extra", ignoreCase = true))
    }

    @Test
    fun case04_showroomSale_extractsShowroomAndSalePrice() {
        val msg = "Main road showroom for sale on FC Road. UG floor, 2200 sqft CA, Sale Price 6.50 Cr, 2 covered car parking."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals(InventoryTab.SHOP_OUT, d.tab)
        assertEquals("Showroom", d.propertyType)
        assertEquals("FC Road", d.location)
        assertEquals("Upper Ground Floor", d.floor)
        assertEquals(2200.0, d.carpetAreaSqFt, 0.01)
        assertEquals(65_000_000.0, d.priceSale!!, 0.01)
        assertTrue(d.parking.contains("2", ignoreCase = true))
    }

    @Test
    fun case05_preLeasedInvestment_extractsPlTenantRoiLockInEscalation() {
        val msg = "PL commercial office at World Trade Center, Kharadi. 1600 sqft CA, Tenant: HDFC Bank, Rent 1.4L, Sale Price 2.40 Cr, ROI 7%, LI 36 months, Esc 5% yearly."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals(InventoryTab.PRE_LEASED, d.tab)
        assertEquals("World Trade Center", d.projectName)
        assertEquals("Kharadi", d.location)
        assertEquals(1600.0, d.carpetAreaSqFt, 0.01)
        assertEquals(140_000.0, d.rent!!, 0.01)
        assertEquals(24_000_000.0, d.priceSale!!, 0.01)
        assertEquals(7.0, d.roiPercent!!, 0.01)
        assertTrue(d.tenantName.contains("HDFC", ignoreCase = true))
        assertTrue(d.lockInMonths.contains("36"))
        assertTrue(d.escalationTerms.contains("5%"))
    }

    @Test
    fun case06_residentialRental_extractsBhkRentDepositNegotiable() {
        val msg = "3 BHK flat avl for rent at Panchshil Towers, Kharadi. 1450 sqft CA, SF, Rent 55k Nego, Dep 1.5L, 1 covered car parking."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals(InventoryTab.RESI_RENT, d.tab)
        assertEquals("Apartment", d.propertyType)
        assertEquals("3 BHK", d.bhkConfiguration)
        assertEquals("Panchshil Towers", d.projectName)
        assertEquals(FurnishingType.SEMI_FURNISHED, d.furnishing)
        assertEquals(55_000.0, d.rent!!, 0.01)
        assertEquals(150_000.0, d.deposit!!, 0.01)
        assertEquals(true, d.negotiable)
    }

    @Test
    fun case07_residentialResale_keepsSbaSeparateFromCarpetArea() {
        val msg = "2 BHK resale flat at Amanora Park Town, Hadapsar. 1180 sqft SBA, 860 sqft CA, Asking Price 1.15Cr."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals(InventoryTab.RESI_RESALE, d.tab)
        assertEquals("2 BHK", d.bhkConfiguration)
        assertEquals("Amanora Park Town", d.projectName)
        assertEquals("Hadapsar", d.location)
        assertEquals(860.0, d.carpetAreaSqFt, 0.01)
        assertEquals(1180.0, d.saleableAreaSqFt!!, 0.01)
        assertNull(d.builtUpAreaSqFt)
        assertEquals(11_500_000.0, d.priceSale!!, 0.01)
    }

    @Test
    fun case08_underConstructionProject_extractsReraPossessionAndBuilder() {
        val msg = "Under construction 3 BHK at VTP Pegasus, Kharadi. Builder: VTP Realty, MahaRERA P52100028812, 1120 sqft CA, Price 1.32 Cr, Poss: Dec 2028."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals(InventoryTab.UND_CONST, d.tab)
        assertEquals("VTP Pegasus", d.projectName)
        assertEquals("Kharadi", d.location)
        assertEquals(1120.0, d.carpetAreaSqFt, 0.01)
        assertEquals(13_200_000.0, d.priceSale!!, 0.01)
        assertTrue(d.possession.contains("2028"))
        assertTrue(d.builderName.contains("VTP", ignoreCase = true))
    }

    @Test
    fun case09_gunthaLandSale_preservesGunthaUnitAnd712WithoutCarpetSubstitution() {
        val msg = "Clear title 7/12 agricultural land for sale in Wagholi. 15 Guntha, Asking Price 4.50 Cr Nego."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals("Commercial Land", d.propertyType)
        assertEquals("Wagholi", d.location)
        assertEquals(15.0, d.landAreaValue!!, 0.01)
        assertEquals("Guntha", d.landAreaUnit)
        assertEquals(0.0, d.carpetAreaSqFt, 0.01) // Never stuffed into carpetAreaSqFt!
        assertEquals(true, d.isSevenTwelveClear)
        assertEquals(45_000_000.0, d.priceSale!!, 0.01)
        assertEquals("15 Guntha", d.extractedFields["Land Area"]?.value)
    }

    @Test
    fun case10_warehouseLease_extractsWarehouseTypeAndLeaseTerms() {
        val msg = "Industrial warehouse for lease in Chakan. 12000 sqft CA, Rent 3.6L, SD 6 months, Poss Imd."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals(InventoryTab.OFF_LEASE, d.tab)
        assertEquals("Warehouse", d.propertyType)
        assertEquals("Chakan", d.location)
        assertEquals(12_000.0, d.carpetAreaSqFt, 0.01)
        assertEquals(360_000.0, d.rent!!, 0.01)
        assertEquals(2_160_000.0, d.deposit!!, 0.01)
    }

    @Test
    fun case11_multiPropertyMessage_isolatesRentPriceAreaFloorAndUnitPerDraft() {
        val msg = """
            Kharadi Direct Broker Options:
            1) Office for lease at Gera Imperium Alpha, Unit 301, 3rd floor, 900 sqft CA, Rent 65k
            2) Shop for lease at EON Free Zone, Unit 12, GF, 500 sqft CA, Rent 85k
            3) 2 BHK flat for sale at Riverdale Grand, Unit 904, 9th floor, 820 sqft CA, Price 1.05 Cr
            Broker: Suresh 9822011223
        """.trimIndent()
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        assertEquals(3, result.drafts.size)

        val d1 = result.drafts[0]
        assertEquals(InventoryTab.OFF_LEASE, d1.tab)
        assertEquals("Gera Imperium Alpha", d1.projectName)
        assertEquals("301", d1.unitNumber)
        assertEquals("3rd Floor", d1.floor)
        assertEquals(900.0, d1.carpetAreaSqFt, 0.01)
        assertEquals(65_000.0, d1.rent!!, 0.01)
        assertNull(d1.priceSale)

        val d2 = result.drafts[1]
        assertEquals(InventoryTab.SHOP_LEASE, d2.tab)
        assertEquals("EON Free Zone", d2.projectName)
        assertEquals("12", d2.unitNumber)
        assertEquals("Ground Floor", d2.floor)
        assertEquals(500.0, d2.carpetAreaSqFt, 0.01)
        assertEquals(85_000.0, d2.rent!!, 0.01)

        val d3 = result.drafts[2]
        assertEquals(InventoryTab.RESI_RESALE, d3.tab)
        assertEquals("Riverdale Grand", d3.projectName)
        assertEquals("904", d3.unitNumber)
        assertEquals("9th Floor", d3.floor)
        assertEquals(820.0, d3.carpetAreaSqFt, 0.01)
        assertEquals(10_500_000.0, d3.priceSale!!, 0.01)
        assertNull(d3.rent)

        // Shared footer contact attaches strictly as Source/Broker, never Owner
        assertEquals("9822011223", d1.sourceBrokerPhone)
        assertEquals("", d1.ownerPhone)
    }

    @Test
    fun case12_duplicateOrConflictingPriceUpdates_flagsExistingPropertyUpdateAndNeedsReview() {
        val msg = "Update on Unit 405, Gera Imperium Alpha, Kharadi, 1200 sqft CA. Earlier rent 1.2L, now revised rent 1.05L."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.EXISTING_PROPERTY_UPDATE, result.classification)
        val d = result.drafts.single()
        assertTrue(d.isPriceConflictOrUpdate)
        assertEquals(105_000.0, d.rent!!, 0.01)
        assertEquals(ConfidenceTier.CONFIRM_REQUIRED, d.extractedFields["Monthly Rent"]?.tier)
        assertTrue(d.reviewWarnings.any { it.contains("Conflicting", ignoreCase = true) })
    }

    @Test
    fun case13_ffShorthandAsFirstFloor_resolvesFloorContextWithoutMislabelingFurnishing() {
        val msg = "Shop for lease on FF at Viman Nagar, 700 sqft CA, BS condition, Rent 80k."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        val d = result.drafts.single()
        assertEquals("First Floor", d.floor)
        assertEquals(FurnishingType.BARE_SHELL, d.furnishing)
        assertEquals(ConfidenceTier.HIGH, d.extractedFields["Floor"]?.tier)
    }

    @Test
    fun case14_ffShorthandAsFullyFurnished_resolvesFurnishingContextWithWorkstations() {
        val msg = "Office for lease at Nyati Enthral, Kharadi, 6th floor, 1500 sqft CA, FF with 25 WS and 2 Cab, Rent 1.35L."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        val d = result.drafts.single()
        assertEquals("6th Floor", d.floor)
        assertEquals(FurnishingType.FULLY_FURNISHED, d.furnishing)
        assertEquals(25, d.workstations)
        assertEquals(2, d.cabins)
        assertEquals(ConfidenceTier.HIGH, d.extractedFields["Furnishing"]?.tier)
    }

    @Test
    fun case15_wsShorthandAsWorkstations_extractsCountAndNotWarmShell() {
        val msg = "Office for lease in Baner, 2000 sqft CA, 35 WS, 2 Cab, 1 Conf, Fully Furnished, Rent 1.8L."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        val d = result.drafts.single()
        assertEquals(35, d.workstations)
        assertEquals(2, d.cabins)
        assertEquals(1, d.conferenceRooms)
        assertEquals(FurnishingType.FULLY_FURNISHED, d.furnishing)
    }

    @Test
    fun case16_wsShorthandAsWarmShell_resolvesWarmShellWithoutWorkstationCount() {
        val msg = "Office for lease at Supreme HQ, Baner, 4th floor, 1800 sqft CA, WS condition, Rent 1.45L."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        val d = result.drafts.single()
        assertEquals(FurnishingType.WARM_SHELL, d.furnishing)
        assertNull(d.workstations)
        assertTrue(d.hasExplicitFurnishing)
    }

    @Test
    fun case17_ambiguousDecimalKRent_flagsNeedsReviewAndLowConfidence() {
        val msg = "Office for lease at Gera Imperium Alpha, Kharadi, 2200 sqft CA, 2.55k rent."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        val d = result.drafts.single()
        val rentField = d.extractedFields["Monthly Rent"]
        assertNotNull(rentField)
        assertTrue(rentField!!.needsReview)
        assertTrue(rentField.confidence < 70)
        assertEquals(ConfidenceTier.CONFIRM_REQUIRED, rentField.tier)
        assertTrue(d.reviewWarnings.any { it.contains("2.55k", ignoreCase = true) })
    }

    @Test
    fun case18_missingProjectName_remainsBlankNeverPrimeCenter() {
        val msg = "Office for lease in Wakad, 1100 sqft CA, Rent 70k."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        val d = result.drafts.single()
        assertEquals("", d.projectName)
        assertFalse(d.projectName.equals("Prime Center", ignoreCase = true))
        assertTrue(d.missingFields.contains("Project / Building"))
    }

    @Test
    fun case19_missingCarpetAreaWhenOnlyBuiltUpGiven_neverSubstitutesBuaAsCarpet() {
        val msg = "Office for lease at Cerebrum IT Park, Kalyani Nagar. 1900 sqft BUA, Rent 1.3L."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        val d = result.drafts.single()
        assertEquals(1900.0, d.builtUpAreaSqFt!!, 0.01)
        assertEquals(0.0, d.carpetAreaSqFt, 0.01) // MUST NOT substitute BUA into Carpet Area!
        assertTrue(d.missingFields.contains("Carpet Area"))
        assertNull(d.extractedFields["Carpet Area"])
    }

    @Test
    fun case20_missingPriceAndDeposit_remainNullNeverFabricated() {
        val msg = "Office avl for lease at Global Business Hub, Kharadi. 1350 sqft CA, 20 WS."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        val d = result.drafts.single()
        assertNull(d.rent)
        assertNull(d.priceSale)
        assertNull(d.deposit)
        assertEquals(0.0, d.priceOrRent, 0.01)
        assertEquals("", d.floor) // Never "Mid Floor"
        assertEquals("", d.possession) // Never "Immediate"
        assertTrue(d.missingFields.contains("Monthly Rent"))
    }

    @Test
    fun case21_clientRequirementDisguisedAsListing_classifiedAndBlockedFromAddProperty() {
        val msg = "Available requirement: 2000 sqft FF office in Kharadi, budget 1.6L, client looking to shift next month. Please share options."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.CLIENT_REQUIREMENT, result.classification)
        assertTrue(result.blocksAddPropertyForm)
        assertTrue(result.drafts.isEmpty())
    }

    @Test
    fun case22_unrelatedChatMessage_classifiedUnrelatedAndBlocked() {
        val msg = "Good morning team! Let's meet at 1:30 PM for lunch near the coffee shop."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.UNRELATED, result.classification)
        assertTrue(result.blocksAddPropertyForm)
        assertTrue(result.drafts.isEmpty())
    }

    @Test
    fun case23_multiplePhoneNumbers_separatesOwnerFromBrokerAndCapturesAdditionalPhones() {
        val msg = """
            Office for lease at Solitaire Business Hub, Viman Nagar, 1400 sqft CA, Rent 1.1L.
            Broker: Amit Patil 9822011111
            Alt Desk: 9822022222
        """.trimIndent()
        val result = SafeWhatsAppExtractor.extractOffline(
            rawText = msg,
            senderName = "WhatsApp Forwarder",
            senderPhone = "9822033333"
        )

        val d = result.drafts.single()
        // Never assume broker or WhatsApp sender is Owner!
        assertEquals("", d.ownerPhone)
        assertEquals("", d.ownerName)
        assertEquals("9822011111", d.sourceBrokerPhone)
        assertTrue(d.additionalPhones.contains("9822022222"))
        assertTrue(d.reviewWarnings.any { it.contains("Multiple phone numbers", ignoreCase = true) })
    }

    @Test
    fun case24_mixedEnglishMarathiHinglishListing_extractsShopLeaseAndDirectMalak() {
        val msg = "Kharadi madhe dukan bhadyane upalabdh, 450 sqft CA, GF, mahina 45 hajar, 3 mahine dep, malak Rajesh 9823012345."
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals(InventoryTab.SHOP_LEASE, d.tab)
        assertEquals("Shop", d.propertyType)
        assertEquals("Kharadi", d.location)
        assertEquals("Ground Floor", d.floor)
        assertEquals(450.0, d.carpetAreaSqFt, 0.01)
        assertEquals(45_000.0, d.rent!!, 0.01)
        assertEquals(135_000.0, d.deposit!!, 0.01)
        assertEquals("9823012345", d.ownerPhone)
        assertTrue(d.ownerName.contains("Rajesh", ignoreCase = true))
    }

    @Test
    fun case25_densePuneShorthandsAndAcreLand_normalizesAllTokensAndConfidenceTiers() {
        val msg = "Commercial land avl for sale in Mulshi, 2.5 Acre, 7/12 clear, Price 1.50Cr Nego. Broker: Vikram 9765012345"
        val result = SafeWhatsAppExtractor.extractOffline(msg)

        assertEquals(WhatsAppMessageClassification.PROPERTY_LISTING, result.classification)
        val d = result.drafts.single()
        assertEquals("Mulshi", d.location)
        assertEquals(2.5, d.landAreaValue!!, 0.01)
        assertEquals("Acre", d.landAreaUnit)
        assertEquals(15_000_000.0, d.priceSale!!, 0.01)
        assertEquals(true, d.negotiable)
        assertEquals(true, d.isSevenTwelveClear)
        assertEquals("9765012345", d.sourceBrokerPhone)
        assertEquals("", d.ownerPhone)
        assertEquals(ConfidenceTier.HIGH, d.extractedFields["Land Area"]?.tier)
        assertEquals(ConfidenceTier.HIGH, d.extractedFields["Sale Price"]?.tier)
    }
}
