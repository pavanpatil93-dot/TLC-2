package com.example

import com.example.data.SheetsRepository
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class ExampleUnitTest {
    private fun adminContext(): android.content.Context {
        val c = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        c.getSharedPreferences("real_estate_user_settings", 0).edit().putString("user_role", "Admin").putString("user_username", "Test Admin").commit()
        return c
    }


    @Test
    fun testIndianCurrencyFormatting() {
        assertEquals("₹2.5 Cr", CalculationUtils.formatIndianCurrency(25000000.0))
        assertEquals("₹95 Lac", CalculationUtils.formatIndianCurrency(9500000.0))
        assertEquals("₹85k/mo", CalculationUtils.formatIndianCurrency(85000.0, isMonthly = true))
    }

    @Test
    fun testFinancialCalculations() {
        val rate = CalculationUtils.calculateRentPerCarpet(95000.0, 1000.0)
        assertNotNull(rate)
        assertEquals(95.0, rate!!, 0.01)

        val yield = CalculationUtils.calculateGrossYield(480000.0, 72000000.0, isMonthly = true)
        assertNotNull(yield)
        assertEquals(8.0, yield!!, 0.01)

        val loading = CalculationUtils.calculateLoadingPercentage(1000.0, 1350.0)
        assertNotNull(loading)
        assertEquals(35.0, loading!!, 0.01)
    }

    @Test
    fun testMasterInventoryAndCsvExport() {
        val repo = SheetsRepository(adminContext(), false)
        // Master inventory starts empty from scratch
        assertTrue(repo.properties.value.isEmpty())

        // 24 Standard Master Inventory CSV Export Header Validation
        val csv = repo.exportStandardCsv()
        assertTrue(csv.contains("Property Code,Date,Location,Project / Building,Unit / Shop / Office No"))
        assertTrue(csv.contains("Carpet Area,Built-up Area,Price / Sale,Rent,Deposit,ROI %"))
        assertTrue(csv.contains("Owner / Contact Person,Contact Number,Source,Remarks,Listing Link,Last Updated,Remark"))

        // Add Property to Master Inventory
        val newProp = PropertyItem(
            id = "test_prop_001",
            propertyCode = "TEST-OL-01",
            location = "Kharadi, Pune",
            projectName = "Gera Imperium Alpha",
            unitNumber = "Suite 302",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 922.0,
            priceOrRent = 85000.0,
            status = AvailabilityStatus.AVAILABLE
        )
        repo.addProperty(newProp)
        assertTrue(repo.properties.value.any { it.propertyCode == "TEST-OL-01" })

        // Duplicate code detection
        val dupes = repo.findPotentialDuplicates("TEST-OL-01", "Gera Imperium Alpha", "Suite 302")
        assertTrue(dupes.isNotEmpty())
    }
}

