package com.example.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.model.AvailabilityStatus
import com.example.model.InventoryTab
import com.example.model.PropertyItem
import com.example.model.WhatsAppShareTemplates
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.PropertyDetailScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class WhatsAppShareUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val propOne = PropertyItem(
        id = "prop-1",
        propertyCode = "OL-001",
        projectName = "Alpha Business Park",
        location = "Kharadi, Pune",
        tab = InventoryTab.OFF_LEASE,
        carpetAreaSqFt = 1200.0,
        priceOrRent = 120000.0,
        possession = "Immediate",
        status = AvailabilityStatus.SOLD,
        ownerName = "SECRET OWNER ONE",
        ownerContact = "9999900001"
    )

    private val propTwo = PropertyItem(
        id = "prop-2",
        propertyCode = "RS-002",
        projectName = "Beta Residences",
        location = "Baner, Pune",
        tab = InventoryTab.RESI_RESALE,
        carpetAreaSqFt = 1500.0,
        priceOrRent = 22500000.0,
        possession = "Ready",
        status = AvailabilityStatus.AVAILABLE,
        ownerName = "SECRET OWNER TWO",
        ownerContact = "9999900002"
    )

    @Test
    fun shareAcrossListGridSavedAndDetailResolvesExactIdWithZeroSideEffectsAndConsistentOutput() {
        var clickedPropertyId: String? = null
        var editedPropertyId: String? = null
        var favoriteToggledId: String? = null
        var sharedFromExplore: PropertyItem? = null
        var sharedFromSaved: PropertyItem? = null
        var sharedFromDetail = false

        var useSavedOnly by mutableStateOf(false)
        var showDetail by mutableStateOf(false)

        compose.setContent {
            MyApplicationTheme {
                if (!showDetail) {
                    ExploreScreen(
                        properties = if (useSavedOnly) listOf(propTwo) else listOf(propOne, propTwo),
                        selectedBrand = null,
                        onSelectBrand = {},
                        isInternalMode = true,
                        favoriteIds = setOf(propTwo.id),
                        comparisonIds = emptyList(),
                        onPropertyClick = { clickedPropertyId = it.id },
                        onEditProperty = { editedPropertyId = it.id },
                        onToggleFavorite = { favoriteToggledId = it },
                        onToggleCompare = {},
                        onShareWhatsApp = { prop ->
                            if (useSavedOnly) sharedFromSaved = prop else sharedFromExplore = prop
                        },
                        onStatusChange = { _, _ -> },
                        onOpenAddProperty = {},
                        onOpenImport = {}
                    )
                } else {
                    PropertyDetailScreen(
                        property = propTwo,
                        allProperties = listOf(propOne, propTwo),
                        isInternalMode = true,
                        isFavorite = true,
                        isInComparison = false,
                        onBack = {},
                        onToggleFavorite = {},
                        onToggleCompare = {},
                        onShareWhatsApp = { sharedFromDetail = true },
                        onVerifyProperty = {},
                        onStatusChange = {},
                        onPropertySelect = {}
                    )
                }
            }
        }

        // 1. Inventory List view share resolves exact selected property without side effects
        compose.waitForIdle()
        compose.onNodeWithTag("explore_property_list")
            .performScrollToNode(hasTestTag("property_card_whatsapp_${propTwo.id}"))
        compose.onNodeWithTag("property_card_whatsapp_${propTwo.id}").performClick()
        compose.waitForIdle()

        assertEquals(propTwo.id, sharedFromExplore?.id)
        assertEquals(null, clickedPropertyId)
        assertEquals(null, editedPropertyId)
        assertEquals(null, favoriteToggledId)

        // 2. Inventory Grid view share resolves exact selected property without side effects
        sharedFromExplore = null
        compose.onNodeWithTag("grid_list_toggle_btn").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("explore_property_grid")
            .performScrollToNode(hasTestTag("property_card_whatsapp_${propTwo.id}"))
        compose.onNodeWithTag("property_card_whatsapp_${propTwo.id}").performClick()
        compose.waitForIdle()

        assertEquals(propTwo.id, sharedFromExplore?.id)
        assertEquals(null, clickedPropertyId)
        assertEquals(null, editedPropertyId)
        assertEquals(null, favoriteToggledId)

        // 3. Saved Properties share resolves exact saved property without side effects
        useSavedOnly = true
        compose.waitForIdle()
        compose.onNodeWithTag("explore_property_grid")
            .performScrollToNode(hasTestTag("property_card_whatsapp_${propTwo.id}"))
        compose.onNodeWithTag("property_card_whatsapp_${propTwo.id}").performClick()
        compose.waitForIdle()

        assertEquals(propTwo.id, sharedFromSaved?.id)
        assertEquals(null, clickedPropertyId)
        assertEquals(null, editedPropertyId)
        assertEquals(null, favoriteToggledId)

        // 4. Property Details uses identical single-property formatter
        showDetail = true
        compose.waitForIdle()
        compose.onNodeWithTag("detail_share_whatsapp_btn").performClick()
        compose.waitForIdle()
        assertTrue(sharedFromDetail)
        assertEquals(
            WhatsAppShareTemplates.property(propTwo),
            WhatsAppShareTemplates.buildSinglePropertyPreview(propTwo).message
        )
    }

    @Test
    fun consecutiveSharesResetStateCopyConfirmationMissingInfoBannerAndGateSoldListings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val missingInfoProp = PropertyItem(
            id = "prop-missing",
            propertyCode = "OL-003",
            projectName = "Incomplete Tower",
            location = "",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 0.0,
            priceOrRent = 0.0,
            possession = "",
            status = AvailabilityStatus.NOT_CONFIRMED
        )
        var activeShareProperty by mutableStateOf<PropertyItem?>(propOne)
        var dismissCount = 0

        compose.setContent {
            MyApplicationTheme {
                activeShareProperty?.let { target ->
                    WhatsAppShareDialog(
                        property = target,
                        senderName = "Neha Joshi",
                        onDismiss = {
                            dismissCount++
                            activeShareProperty = null
                        }
                    )
                }
            }
        }

        compose.waitForIdle()
        // propOne is SOLD -> restricted banner displayed
        compose.onNodeWithTag("restricted_status_banner").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("STATUS: SOLD").performScrollTo().assertIsDisplayed()

        // Copy message -> updates clipboard & shows confirmation
        compose.onNodeWithTag("copy_message_btn").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("copy_success_indicator").performScrollTo().assertIsDisplayed()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copied.contains("OL-001"))
        assertTrue(copied.contains("Shared by: Neha Joshi"))
        assertFalse(copied.contains("SECRET OWNER ONE"))

        // Tapping Share on SOLD listing requires second explicit confirmation
        compose.onNodeWithTag("share_to_whatsapp_btn").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("restricted_status_confirm_dialog").assertIsDisplayed()
        compose.onNodeWithTag("cancel_restricted_share_btn").performClick()
        compose.waitForIdle()

        // Cancel dismisses dialog
        compose.onNodeWithTag("cancel_share_btn").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, dismissCount)

        // Open second property consecutively -> NO carry-over from propOne!
        activeShareProperty = propTwo
        compose.waitForIdle()

        assertTrue(compose.onAllNodesWithTag("copy_success_indicator").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("restricted_status_banner").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("restricted_status_confirm_dialog").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("STATUS: AVAILABLE").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("RS-002").performScrollTo().assertIsDisplayed()

        // Open third property with missing essential info & unconfirmed status -> shows banner and notice
        activeShareProperty = missingInfoProp
        compose.waitForIdle()
        compose.onNodeWithTag("missing_essential_info_banner").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("STATUS: NOT CONFIRMED").performScrollTo().assertIsDisplayed()
    }
}
