package com.example.ui.shortlist

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.model.AvailabilityStatus
import com.example.model.FurnishingType
import com.example.model.InventoryTab
import com.example.model.PropertyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MultiPropertyShortlistTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleLease() = PropertyItem(
        id = "prop_lease_1",
        propertyCode = "OL-KHD-101",
        projectName = "EON IT Park",
        location = "Kharadi, Pune",
        tab = InventoryTab.OFF_LEASE,
        transactionType = "Lease",
        carpetAreaSqFt = 2000.0,
        builtUpAreaSqFt = 2500.0,
        priceOrRent = 200000.0,
        rent = 200000.0,
        furnishing = FurnishingType.WARM_SHELL,
        status = AvailabilityStatus.AVAILABLE,
        ownerName = "Secret Owner Ramesh",
        ownerContact = "9876500001",
        brokerName = "Secret Broker Suresh",
        internalRemarks = "CONFIDENTIAL_COMMISSION_NOTE"
    )

    private fun sampleSale() = PropertyItem(
        id = "prop_sale_2",
        propertyCode = "RS-BNR-202",
        projectName = "Pratham Meadows",
        location = "Baner, Pune",
        tab = InventoryTab.RESI_RESALE,
        transactionType = "Sale",
        carpetAreaSqFt = 1000.0,
        priceOrRent = 15000000.0,
        priceSale = 15000000.0,
        furnishing = FurnishingType.SEMI_FURNISHED,
        status = AvailabilityStatus.UNDER_NEGOTIATION,
        ownerName = "Private Seller Mahesh",
        ownerContact = "9822001122",
        internalRemarks = "INTERNAL_FLOOR_RESERVE"
    )

    @Test
    fun `toggleSelection preserves order and updates privacy-safe preview`() {
        val vm = ShortlistViewModel(
            initialInventory = listOf(sampleLease(), sampleSale()),
            initialEmployeeName = "Aarav Kulkarni"
        )

        vm.toggleSelection("prop_sale_2")
        vm.toggleSelection("prop_lease_1")

        val state = vm.state.value
        assertEquals(listOf("prop_sale_2", "prop_lease_1"), state.selectedIds)
        assertEquals(2, state.selectedCount)

        val preview = state.previewText
        // Allowlisted public fields & rate math present
        assertTrue(preview.contains("RS-BNR-202"))
        assertTrue(preview.contains("Pratham Meadows"))
        assertTrue(preview.contains("OL-KHD-101"))
        assertTrue(preview.contains("EON IT Park"))
        assertTrue(preview.contains("₹15,000/sq.ft")) // 1.5 Cr / 1000 sq.ft
        assertTrue(preview.contains("₹100/sq.ft/mo")) // 2,00,000 / 2000 sq.ft
        assertTrue(preview.contains("₹80/sq.ft/mo")) // 2,00,000 / 2500 sq.ft
        assertTrue(preview.contains("Under Negotiation"))
        assertTrue(preview.contains("Available"))

        // Footer format: Employee Name + desk numbers, no company name
        assertTrue(preview.contains("Aarav Kulkarni\n9766880099 | 9766668099"))
        assertFalse(preview.contains("TLC-1"))
        assertFalse(preview.contains("Ascend"))
        assertFalse(preview.contains("Aurea"))

        // Strict privacy exclusion
        assertFalse(preview.contains("Secret Owner"))
        assertFalse(preview.contains("9876500001"))
        assertFalse(preview.contains("Secret Broker"))
        assertFalse(preview.contains("CONFIDENTIAL_COMMISSION_NOTE"))
        assertFalse(preview.contains("Private Seller"))
        assertFalse(preview.contains("9822001122"))
        assertFalse(preview.contains("INTERNAL_FLOOR_RESERVE"))
    }

    @Test
    fun `buildWhatsAppShareIntent falls back to Android share chooser when WhatsApp missing`() {
        val context = RuntimeEnvironment.getApplication()
        val vm = ShortlistViewModel(
            initialInventory = listOf(sampleLease()),
            initialEmployeeName = ""
        )
        vm.toggleSelection("prop_lease_1")

        val intent = vm.buildWhatsAppShareIntent(context)
        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        val extraIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_SEND, extraIntent?.action)
        assertEquals(vm.state.value.previewText, extraIntent?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `pinned bar and review sheet display selection count and actions`() {
        var reviewClicked = false
        var clearClicked = false
        composeRule.setContent {
            ShortlistPinnedBottomBar(
                selectedCount = 2,
                onReviewShortlist = { reviewClicked = true },
                onClear = { clearClicked = true }
            )
        }

        composeRule.onNodeWithText("Selected: 2 properties").assertIsDisplayed()
        composeRule.onNodeWithTag("shortlist_review_btn").performClick()
        composeRule.onNodeWithTag("shortlist_clear_btn").performClick()
        assertTrue(reviewClicked)
        assertTrue(clearClicked)
    }
}
