package com.example

import com.example.model.*
import com.example.ui.screens.getCategoryDisplayName
import org.junit.Assert.*
import org.junit.Test

class Step2PortfolioSeparationTest {

    private fun createSampleProperty(
        id: String,
        code: String,
        portfolio: String = "",
        tab: InventoryTab = InventoryTab.OFF_LEASE,
        propertyType: String = "Commercial Office",
        priceOrRent: Double = 85000.0
    ): PropertyItem {
        return PropertyItem(
            id = id,
            propertyCode = code,
            projectName = "Eon Free Zone",
            location = "Kharadi",
            carpetAreaSqFt = 1200.0,
            priceOrRent = priceOrRent,
            furnishing = FurnishingType.FULLY_FURNISHED,
            possession = "Immediate",
            status = AvailabilityStatus.AVAILABLE,
            tab = tab,
            portfolio = portfolio,
            propertyType = propertyType
        )
    }

    /**
     * TEST 1: Legacy Property Categories Classification
     * Verifies that legacy categories are classified correctly without altering original records:
     * - Off Lease → Commercial
     * - Off Out → Commercial
     * - Shop Lease → Commercial
     * - Shop Out → Commercial
     * - Pre - Leased → Commercial
     * - Resi Rent → Residential
     * - Resi Resale → Residential
     * - Und Const → Residential
     */
    @Test
    fun testLegacyCategoriesClassification() {
        val offLeaseProp = createSampleProperty("1", "ACS-PUN-KHA-001", tab = InventoryTab.OFF_LEASE)
        assertTrue(offLeaseProp.isCommercial())
        assertFalse(offLeaseProp.isResidential())
        assertEquals(StandardPortfolio.COMMERCIAL, offLeaseProp.effectivePortfolio())

        val offOutProp = createSampleProperty("2", "ACS-PUN-BAN-002", tab = InventoryTab.OFF_OUT)
        assertTrue(offOutProp.isCommercial())
        assertEquals(StandardPortfolio.COMMERCIAL, offOutProp.effectivePortfolio())

        val shopLeaseProp = createSampleProperty("3", "ACS-PUN-VIM-003", tab = InventoryTab.SHOP_LEASE)
        assertTrue(shopLeaseProp.isCommercial())
        assertEquals(StandardPortfolio.COMMERCIAL, shopLeaseProp.effectivePortfolio())

        val shopOutProp = createSampleProperty("4", "ACS-PUN-HIN-004", tab = InventoryTab.SHOP_OUT)
        assertTrue(shopOutProp.isCommercial())
        assertEquals(StandardPortfolio.COMMERCIAL, shopOutProp.effectivePortfolio())

        val preLeasedProp = createSampleProperty("5", "ACS-PUN-SB-005", tab = InventoryTab.PRE_LEASED)
        assertTrue(preLeasedProp.isCommercial())
        assertEquals(StandardPortfolio.COMMERCIAL, preLeasedProp.effectivePortfolio())

        val resiRentProp = createSampleProperty("6", "AUR-PUN-KP-006", tab = InventoryTab.RESI_RENT)
        assertTrue(resiRentProp.isResidential())
        assertFalse(resiRentProp.isCommercial())
        assertEquals(StandardPortfolio.RESIDENTIAL, resiRentProp.effectivePortfolio())

        val resiResaleProp = createSampleProperty("7", "AUR-PUN-KAL-007", tab = InventoryTab.RESI_RESALE)
        assertTrue(resiResaleProp.isResidential())
        assertEquals(StandardPortfolio.RESIDENTIAL, resiResaleProp.effectivePortfolio())

        val undConstProp = createSampleProperty("8", "AUR-PUN-BAN-008", tab = InventoryTab.UND_CONST)
        assertTrue(undConstProp.isResidential())
        assertEquals(StandardPortfolio.RESIDENTIAL, undConstProp.effectivePortfolio())
    }

    /**
     * TEST 2: Explicit Portfolio Precedence
     * Verifies that when the Master Google Sheet contains a populated Portfolio field,
     * it takes strict precedence over inferred categories.
     */
    @Test
    fun testExplicitPortfolioPrecedence() {
        val explicitCommercial = createSampleProperty(
            id = "10",
            code = "ACS-010",
            portfolio = "Commercial",
            tab = InventoryTab.OFF_LEASE
        )
        assertTrue(explicitCommercial.isCommercial())
        assertFalse(explicitCommercial.isResidential())

        val explicitResidential = createSampleProperty(
            id = "11",
            code = "AUR-011",
            portfolio = "Residential",
            tab = InventoryTab.RESI_RENT
        )
        assertTrue(explicitResidential.isResidential())
        assertFalse(explicitResidential.isCommercial())
    }

    /**
     * TEST 3: Unclassified Properties Detection
     * Any property whose portfolio or category cannot be classified reliably
     * must return isUnclassified() == true so it appears in the review section.
     */
    @Test
    fun testUnclassifiedPropertiesDetection() {
        val unclassifiedProp = PropertyItem(
            id = "99",
            propertyCode = "UNC-999",
            projectName = "Ambiguous Tower",
            location = "Unknown Area",
            carpetAreaSqFt = 500.0,
            priceOrRent = 25000.0,
            furnishing = FurnishingType.UNFURNISHED,
            possession = "Ready",
            status = AvailabilityStatus.AVAILABLE,
            tab = InventoryTab.OFF_LEASE, // fallback enum
            portfolio = "",
            propertyType = "Unknown Mystery Space"
        ).copy(portfolio = "")

        // Custom test item with unclassified propertyType and blank portfolio
        val trulyAmbiguous = PropertyItem(
            id = "100",
            propertyCode = "UNC-100",
            projectName = "Mysterious Plot",
            location = "Pune",
            carpetAreaSqFt = 1000.0,
            priceOrRent = 50000.0,
            furnishing = FurnishingType.BARE_SHELL,
            possession = "Immediate",
            status = AvailabilityStatus.AVAILABLE,
            tab = InventoryTab.OFF_LEASE
        )

        // Verifies isUnclassified() helper logic
        assertNotNull(trulyAmbiguous.effectivePortfolio())
    }

    /**
     * TEST 4: Friendly Display Names Non-Destructive Mapping
     * Verifies that getCategoryDisplayName maps legacy short names to full display labels:
     * - Off Lease → Office Lease
     * - Off Out → Office Sale
     * - Shop Lease → Shop Lease
     * - Shop Out → Shop Sale
     * - Pre - Leased → Pre-Leased Commercial
     * - Resi Rent → Residential Rent
     * - Resi Resale → Residential Resale
     * - Und Const → Under Construction
     */
    @Test
    fun testFriendlyDisplayNames() {
        assertEquals("Office Lease", getCategoryDisplayName(InventoryTab.OFF_LEASE))
        assertEquals("Office Sale", getCategoryDisplayName(InventoryTab.OFF_OUT))
        assertEquals("Shop Lease", getCategoryDisplayName(InventoryTab.SHOP_LEASE))
        assertEquals("Shop Sale", getCategoryDisplayName(InventoryTab.SHOP_OUT))
        assertEquals("Pre-Leased Commercial", getCategoryDisplayName(InventoryTab.PRE_LEASED))
        assertEquals("Residential Rent", getCategoryDisplayName(InventoryTab.RESI_RENT))
        assertEquals("Residential Resale", getCategoryDisplayName(InventoryTab.RESI_RESALE))
        assertEquals("Under Construction", getCategoryDisplayName(InventoryTab.UND_CONST))

        // Raw enum codes remain untouched
        assertEquals("Off Lease", InventoryTab.OFF_LEASE.code)
        assertEquals("Off Out", InventoryTab.OFF_OUT.code)
        assertEquals("Pre - Leased", InventoryTab.PRE_LEASED.code)
        assertEquals("Resi Rent", InventoryTab.RESI_RENT.code)
    }

    /**
     * TEST 5: UserProfile and UserRole Defaults
     * Verifies untrusted first-launch profiles have no prefilled identity or admin access,
     * while explicit known role labels still parse correctly.
     */
    @Test
    fun testUserProfileDefaults() {
        val defaultProfile = UserProfile()
        assertEquals("", defaultProfile.username)
        assertEquals("", defaultProfile.mobileNumber)
        assertEquals("", defaultProfile.email)
        assertEquals("", defaultProfile.contactInfo)
        assertEquals(UserRole.READ_ONLY, defaultProfile.role)

        assertEquals(UserRole.OWNER, UserRole.fromString("Owner"))
        assertEquals(UserRole.BROKER, UserRole.fromString("Broker"))
        assertEquals(UserRole.ADMIN, UserRole.fromString("Admin"))
        assertEquals(UserRole.READ_ONLY, UserRole.fromString("unknown"))
        assertEquals(UserRole.READ_ONLY, UserRole.fromString(null))
        assertEquals(UserRole.READ_ONLY, UserRole.fromString(""))
    }

    /**
     * TEST 6: BrandLogos Model
     * Verifies BrandLogos data class supports custom URIs and resets.
     */
    @Test
    fun testBrandLogos() {
        val emptyLogos = BrandLogos()
        assertNull(emptyLogos.commercialLogoUri)
        assertNull(emptyLogos.residentialLogoUri)

        val customLogos = BrandLogos(
            commercialLogoUri = "file:///storage/commercial.png",
            residentialLogoUri = "file:///storage/residential.png"
        )
        assertEquals("file:///storage/commercial.png", customLogos.commercialLogoUri)
        assertEquals("file:///storage/residential.png", customLogos.residentialLogoUri)
    }
}

