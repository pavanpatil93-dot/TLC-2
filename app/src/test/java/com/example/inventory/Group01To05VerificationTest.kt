package com.example.inventory

import com.example.model.*
import com.example.model.AvailabilityStatus
import com.example.model.VerificationStatus
import org.junit.Assert.*
import org.junit.Test

class Group01To05VerificationTest {

    @Test
    fun group01_unifiedCategoryAndIdentityMapping_preservesCommercialAndResidential() {
        val office = PropertyItem(
            id = "prop_ol_531",
            propertyCode = "OL-0531",
            date = "2026-11-13",
            location = "Kharadi",
            projectName = "Gera Imperium Alpha",
            unitNumber = "602",
            floor = "6th Floor",
            propertyType = "Office",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 1100.0,
            builtUpAreaSqFt = 1540.0,
            priceOrRent = 80000.0,
            rent = 80000.0,
            deposit = 240000.0,
            furnishing = FurnishingType.FULLY_FURNISHED,
            possession = "Immediate",
            status = AvailabilityStatus.AVAILABLE,
            updatedBy = "emp_01"
        )

        val apartment = PropertyItem(
            id = "prop_rr_102",
            propertyCode = "RR-0102",
            date = "2026-11-13",
            location = "Kharadi",
            projectName = "Riverdale Grand",
            unitNumber = "B-1204",
            floor = "12th Floor",
            propertyType = "3 BHK Apartment",
            tab = InventoryTab.RESI_RESALE,
            carpetAreaSqFt = 1417.0,
            priceOrRent = 25100000.0,
            priceSale = 25100000.0,
            furnishing = FurnishingType.SEMI_FURNISHED,
            possession = "Mar 2029",
            status = AvailabilityStatus.AVAILABLE
        )

        assertEquals("prop_ol_531", office.propertyId)
        assertEquals(PropertyCategory.COMMERCIAL, office.propertyCategory)
        assertEquals("emp_01", office.employeeId)

        assertEquals("prop_rr_102", apartment.propertyId)
        assertEquals(PropertyCategory.RESIDENTIAL, apartment.propertyCategory)
        assertNull(apartment.employeeId)
    }

    @Test
    fun group02_carpetAndBuiltUpRateCalculations_matchPromptSpecification() {
        val geraOffice = PropertyItem(
            id = "prop_ol_531",
            propertyCode = "OL-0531",
            date = "2026-11-13",
            location = "Kharadi",
            projectName = "Gera Imperium Alpha",
            unitNumber = "6th Floor",
            floor = "6th Floor",
            propertyType = "Office",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 1100.0,
            builtUpAreaSqFt = 1540.0,
            priceOrRent = 80000.0,
            rent = 80000.0,
            deposit = 240000.0,
            furnishing = FurnishingType.FULLY_FURNISHED,
            possession = "Immediate",
            status = AvailabilityStatus.AVAILABLE
        )

        // 80,000 / 1,100 = 72.7272... -> ₹72.73 / sq.ft. (Carpet)
        assertEquals(
            "₹72.73 / sq.ft. (Carpet)",
            CalculationUtils.formatRateForBasis(geraOffice, AreaRateBasis.CARPET)
        )

        // 80,000 / 1,540 = 51.9480... -> ₹51.95 / sq.ft. (Built-up)
        assertEquals(
            "₹51.95 / sq.ft. (Built-up)",
            CalculationUtils.formatRateForBasis(geraOffice, AreaRateBasis.BUILT_UP)
        )

        // Loading % = (1540 - 1100) / 1100 * 100 = 40.0%
        val loading = CalculationUtils.calculateLoadingPercentage(1100.0, 1540.0) ?: 0.0
        assertEquals(40.0, loading, 0.01)
    }

    @Test
    fun group02_missingBuiltUpArea_returnsNotAvailableInsteadOfZeroOrAssumedLoading() {
        val noBuiltUpProperty = PropertyItem(
            id = "prop_no_bu",
            propertyCode = "OL-0999",
            date = "2026-11-13",
            location = "Baner",
            projectName = "Amar Business Park",
            unitNumber = "301",
            floor = "3rd Floor",
            propertyType = "Office",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 1000.0,
            builtUpAreaSqFt = null,
            priceOrRent = 75000.0,
            rent = 75000.0,
            deposit = 225000.0,
            furnishing = FurnishingType.WARM_SHELL,
            possession = "Immediate",
            status = AvailabilityStatus.AVAILABLE
        )

        assertNull(CalculationUtils.calculateRateForBasis(noBuiltUpProperty, AreaRateBasis.BUILT_UP))
        assertEquals(
            "Not Available",
            CalculationUtils.formatRateForBasis(noBuiltUpProperty, AreaRateBasis.BUILT_UP)
        )
    }

    @Test
    fun group02_completenessReport_isTypeSpecificAndIndependentOfVerification() {
        val unverifiedCompleteOffice = PropertyItem(
            id = "prop_comp_1",
            propertyCode = "OL-0531",
            date = "2026-11-13",
            location = "Kharadi",
            projectName = "Gera Imperium Alpha",
            unitNumber = "602, 6th Floor",
            floor = "6th Floor",
            propertyType = "Office",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 1100.0,
            builtUpAreaSqFt = 1540.0,
            priceOrRent = 80000.0,
            rent = 80000.0,
            deposit = 240000.0,
            furnishing = FurnishingType.FULLY_FURNISHED,
            possession = "Immediate",
            status = AvailabilityStatus.AVAILABLE,
            ownerName = "Mr. Sharma",
            ownerContact = "9822012345",
            images = listOf("https://example.com/photo1.jpg"),
            listingLink = "https://maps.google.com/?q=18.55,73.95",
            verificationStatus = VerificationStatus.PENDING
        )

        val verifiedSameOffice = unverifiedCompleteOffice.copy(
            verificationStatus = VerificationStatus.VERIFIED
        )

        val reportUnverified = InventoryQuality.completenessReport(unverifiedCompleteOffice)
        val reportVerified = InventoryQuality.completenessReport(verifiedSameOffice)

        // Verification status does not inflate or reduce data completion percentage
        assertEquals(reportUnverified.percentage, reportVerified.percentage)
        assertEquals(100, reportUnverified.percentage)
        assertEquals(CompletenessColorTier.GREEN, reportUnverified.colorTier)
    }

    @Test
    fun group02_sourceBadgePrivacy_masksConfidentialNamesWhenUnauthorized() {
        val property = PropertyItem(
            id = "prop_src_1",
            propertyCode = "OL-0531",
            date = "2026-11-13",
            location = "Kharadi",
            projectName = "Gera Imperium Alpha",
            unitNumber = "602",
            floor = "6th Floor",
            propertyType = "Office",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 1100.0,
            priceOrRent = 80000.0,
            rent = 80000.0,
            furnishing = FurnishingType.FULLY_FURNISHED,
            possession = "Immediate",
            status = AvailabilityStatus.AVAILABLE,
            sourceInfo = "Owner - Mr. Sharma",
            ownerName = "Mr. Sharma",
            ownerContact = "9822012345"
        )

        val authorizedInfo = InventoryQuality.resolveSourceInfo(property, isAuthorized = true)
        val restrictedInfo = InventoryQuality.resolveSourceInfo(property, isAuthorized = false)

        assertEquals(SourceBadgeType.OWNER, authorizedInfo.type)
        assertTrue(authorizedInfo.summaryLine.contains("Mr. Sharma"))

        assertEquals(SourceBadgeType.OWNER, restrictedInfo.type)
        assertFalse(restrictedInfo.summaryLine.contains("Mr. Sharma"))
        assertEquals("Protected", restrictedInfo.displayName)
    }

    @Test
    fun group02_relatedProperties_neverMixesCommercialAndResidential() {
        val targetOffice = PropertyItem(
            id = "off_1",
            propertyCode = "OL-0100",
            date = "2026-11-13",
            location = "Kharadi",
            projectName = "Gera Imperium Alpha",
            unitNumber = "101",
            floor = "1st Floor",
            propertyType = "Office",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 1000.0,
            priceOrRent = 75000.0,
            rent = 75000.0,
            furnishing = FurnishingType.FULLY_FURNISHED,
            possession = "Immediate",
            status = AvailabilityStatus.AVAILABLE
        )

        val sameProjectResidential = targetOffice.copy(
            id = "res_1",
            propertyCode = "RR-0100",
            propertyType = "2 BHK Apartment",
            tab = InventoryTab.RESI_RENT
        )

        val sameProjectOffice = targetOffice.copy(
            id = "off_2",
            propertyCode = "OL-0101",
            unitNumber = "102",
            carpetAreaSqFt = 1150.0,
            priceOrRent = 82000.0,
            rent = 82000.0
        )

        val related = InventoryQuality.findRelatedProperties(
            property = targetOffice,
            allProperties = listOf(targetOffice, sameProjectResidential, sameProjectOffice)
        )

        assertEquals(1, related.size)
        assertEquals("off_2", related.first().id)
    }

    @Test
    fun group03_approximateMatchTolerance_evaluatesExactAndExpandedRangesCorrectly() {
        // Selected range: 1,000 to 2,000 sq.ft with 20% tolerance -> Expanded: 800 to 2,400 sq.ft
        val exactTier = InventoryQuality.evaluateRangeMatch(
            value = 1500.0,
            minTarget = 1000.0,
            maxTarget = 2000.0,
            tolerance = MatchTolerance.TOL_20
        )
        val approxLowerTier = InventoryQuality.evaluateRangeMatch(
            value = 850.0,
            minTarget = 1000.0,
            maxTarget = 2000.0,
            tolerance = MatchTolerance.TOL_20
        )
        val approxUpperTier = InventoryQuality.evaluateRangeMatch(
            value = 2350.0,
            minTarget = 1000.0,
            maxTarget = 2000.0,
            tolerance = MatchTolerance.TOL_20
        )
        val outOfRangeTier = InventoryQuality.evaluateRangeMatch(
            value = 2500.0,
            minTarget = 1000.0,
            maxTarget = 2000.0,
            tolerance = MatchTolerance.TOL_20
        )

        assertEquals(RangeMatchTier.EXACT_MATCH, exactTier)
        assertEquals(RangeMatchTier.APPROXIMATE_MATCH, approxLowerTier)
        assertEquals(RangeMatchTier.APPROXIMATE_MATCH, approxUpperTier)
        assertEquals(RangeMatchTier.NO_MATCH, outOfRangeTier)
    }
}
