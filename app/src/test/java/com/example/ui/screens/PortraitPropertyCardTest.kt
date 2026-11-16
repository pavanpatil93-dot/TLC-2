package com.example.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.model.PropertyItem
import com.example.model.InventoryTab
import com.example.ui.components.PropertyCard
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PortraitPropertyCardTest {
    @get:Rule val compose = createComposeRule()

    private fun render(width: Int, compact: Boolean, scale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                MyApplicationTheme {
                    androidx.compose.foundation.layout.Box(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                        PropertyCard(
                            property = PropertyItem(id = "layout", propertyCode = "SO-TEST", projectName = "Long Project Name With Several Words", location = "Kharadi, Pune", tab = InventoryTab.OFF_OUT, priceOrRent = 47000000.0),
                            isInternalMode = true, isFavorite = false, isInComparison = false,
                            onCardClick = {}, onEditClick = {}, onToggleFavorite = {}, onToggleCompare = {}, onShareWhatsApp = {}, compact = compact
                        )
                    }
                }
            }
        }
    }

    @Test fun priceHasFullRowAtSmallPhoneWidthAndLargerFont() {
        render(320, false, 1.3f)
        compose.onNodeWithTag("card_price", useUnmergedTree = true).performScrollTo()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("card_price", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
        assertFalse(results.single().hasVisualOverflow)
        assertTrue(compose.onNodeWithTag("card_price_block", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width >= 280f)
    }

    @Test fun compactGridPriceDoesNotWrapCharacterByCharacter() {
        render(160, true, 1f)
        compose.onNodeWithTag("card_price", useUnmergedTree = true).performScrollTo()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("card_price", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
        assertFalse(results.single().hasVisualOverflow)
    }
}
