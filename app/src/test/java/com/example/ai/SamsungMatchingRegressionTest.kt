package com.example.ai

import com.example.model.*
import org.junit.Assert.*
import org.junit.Test

class SamsungMatchingRegressionTest {
    private val service = GeminiRealEstateService()
    private val property = PropertyItem(id = "a", projectName = "Test Building", location = "Kharadi",
        tab = InventoryTab.OFF_LEASE, propertyType = "Office", status = AvailabilityStatus.AVAILABLE,
        carpetAreaSqFt = 500.0, priceOrRent = 45000.0, furnishing = FurnishingType.FULLY_FURNISHED)
    private val request = "Office rent Kharadi, 400-700 sqft, budget 50000"

    @Test fun excludesWrongLocationBudgetAreaTypeTransactionAndAvailability() {
        val wrong = listOf(property.copy(id = "location", location = "Baner"),
            property.copy(id = "budget", priceOrRent = 60000.0),
            property.copy(id = "area", carpetAreaSqFt = 800.0),
            property.copy(id = "type", propertyType = "Showroom"),
            property.copy(id = "sale", tab = InventoryTab.OFF_OUT),
            property.copy(id = "unavailable", status = AvailabilityStatus.NOT_AVAILABLE),
            property.copy(id = "archived", archiveStatus = ArchiveStatus.ARCHIVED))
        val results = service.matchClientRequirementsCategorized(request, wrong + property).flatMap { it.results }
        assertEquals(listOf("a"), results.map { it.property.id })
    }

    @Test fun unknownPriceOrAreaNeverBecomesExact() {
        val results = service.matchClientRequirementsCategorized(request,
            listOf(property.copy(priceOrRent = 0.0), property.copy(id = "b", carpetAreaSqFt = 0.0))).flatMap { it.results }
        assertEquals(2, results.size)
        assertTrue(results.all { it.matchType == "Needs Verification" && it.matchingReasons.any { reason -> reason.startsWith("Unknown") } })
    }

    @Test fun semiFurnishedIsNotParsedAsFullyFurnished() {
        assertEquals(FurnishingType.SEMI_FURNISHED, service.fallbackExtractRequirements("semi furnished office").furnishing)
        assertEquals(FurnishingType.UNFURNISHED, service.fallbackExtractRequirements("unfurnished office").furnishing)
    }

    @Test fun preleasedInvestmentIsSaleAndPlainRupeeBudgetIsRecognised() {
        assertEquals("Sale", service.fallbackExtractRequirements("buy pre-leased office Kharadi").transactionType)
        assertEquals(50000.0, service.fallbackExtractRequirements(request).maxPriceOrRent!!, 0.01)
        assertEquals(50000.0, service.fallbackExtractRequirements("rent office budget 50k").maxPriceOrRent!!, 0.01)
    }

    @Test fun missingOrUnsupportedRequirementDoesNotClaimExact() {
        val results = service.matchClientRequirementsCategorized("office Kharadi immediate possession", listOf(property)).flatMap { it.results }
        assertTrue(results.none { it.matchType == "Exact Match" })
        assertTrue(service.matchClientRequirementsCategorized("", listOf(property)).isEmpty())
    }
}

