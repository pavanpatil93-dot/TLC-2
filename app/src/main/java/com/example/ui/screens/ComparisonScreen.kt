package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SheetsRepository
import com.example.model.*
import com.example.ui.components.PropertyPhoto
import com.example.ui.theme.*
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val CompNavy: Color get() = TextPrimary
private val CompTeal: Color get() = FocusOutlineColor
private val CompTealSubtle: Color get() = SelectedSurfaceContainer
private val CompSoftWhite: Color get() = BackgroundSoft
private val CompLabelBg: Color get() = SurfaceMuted
private val CompBorder: Color get() = BorderGrey
private val CompSecondaryText: Color get() = SecondaryText
private val CompAmberBg: Color get() = WarningAmberSubtle
private val CompAmberBorder: Color get() = StatusWarningAmber
private val CompAmberText: Color get() = StatusWarningAmber

private val LABEL_COLUMN_WIDTH = 128.dp
private val PROPERTY_COLUMN_WIDTH = 210.dp

private data class ComparisonRowData(
    val key: String,
    val label: String,
    val values: List<String>
)

@Composable
fun ComparisonScreen(
    comparisonProperties: List<PropertyItem>,
    onRemoveProperty: (String) -> Unit,
    onClearAll: () -> Unit,
    onShareComparisonWhatsApp: () -> Unit,
    onBack: () -> Unit = {},
    onBrowseInventory: () -> Unit = onBack,
    onViewProperty: (PropertyItem) -> Unit = {},
    noticeMessage: String? = null
) {
    val activeProperties = remember(comparisonProperties) {
        comparisonProperties.distinctBy { it.id }.take(SheetsRepository.MAX_COMPARISON_COUNT)
    }
    var showDifferencesOnly by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CompSoftWhite)
            .statusBarsPadding()
            .testTag("comparison_screen")
    ) {
        // Header Bar
        Surface(
            color = SurfaceWhite,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("comparison_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = CompNavy
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Compare Properties (${activeProperties.size}/${SheetsRepository.MAX_COMPARISON_COUNT})",
                        color = CompNavy,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("comparison_header_title")
                    )
                    Text(
                        text = "SIDE-BY-SIDE INVENTORY MATRIX",
                        color = CompTeal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                        maxLines = 1
                    )
                }

                if (activeProperties.isNotEmpty()) {
                    TextButton(
                        onClick = onClearAll,
                        colors = ButtonDefaults.textButtonColors(contentColor = CompNavy),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("comparison_clear_all_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onShareComparisonWhatsApp,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("comparison_share_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Comparison",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Share", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Removed/Restricted Notice Banner
        if (!noticeMessage.isNullOrBlank()) {
            Surface(
                color = CompAmberBg,
                border = BorderStroke(1.dp, CompAmberBorder.copy(alpha = 0.65f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("comparison_notice_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = CompAmberText,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = noticeMessage,
                        color = CompAmberText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (activeProperties.size < 2) {
            FewerThanTwoComparisonState(
                selectedProperties = activeProperties,
                onViewProperty = onViewProperty,
                onRemoveProperty = onRemoveProperty,
                onBrowseInventory = onBrowseInventory
            )
        } else {
            // Display Control Row: All Details | Differences Only
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceWhite)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SegmentedComparisonChip(
                    label = "All Details",
                    selected = !showDifferencesOnly,
                    onClick = { showDifferencesOnly = false },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("comparison_all_details_chip")
                )
                SegmentedComparisonChip(
                    label = "Differences Only",
                    selected = showDifferencesOnly,
                    onClick = { showDifferencesOnly = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("comparison_differences_only_chip")
                )
            }

            // Mixed Transaction Warning Banner
            val distinctTxTypes = remember(activeProperties) {
                activeProperties.map(::resolveComparisonTransactionType).distinct()
            }
            if (distinctTxTypes.size > 1) {
                Surface(
                    color = CompAmberBg,
                    border = BorderStroke(1.dp, CompAmberBorder),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("comparison_mixed_transaction_warning")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Mixed transaction notice",
                            tint = CompAmberText,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Different transaction types selected — Monthly Rent and Sale Price are shown in separate rows.",
                            color = CompAmberText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            HorizontalDivider(color = CompBorder)

            // Build the 17 ordered comparison rows strictly from public & derived fields
            val allRows = remember(activeProperties) {
                buildComparisonMatrixRows(activeProperties)
            }
            val visibleRows = remember(allRows, showDifferencesOnly) {
                if (!showDifferencesOnly) {
                    allRows
                } else {
                    allRows.filter { row ->
                        row.values.map { it.trim().lowercase(Locale.ENGLISH) }.distinct().size > 1
                    }
                }
            }

            val verticalScrollState = rememberScrollState()
            val horizontalScrollState = rememberScrollState()
            val density = LocalDensity.current
            val propertyKeys = remember(activeProperties) {
                activeProperties.joinToString("|") { it.id }
            }
            val cellHeightsPx = remember(propertyKeys) { mutableStateMapOf<String, Int>() }

            fun maxRowHeightDp(rowKey: String, minDp: Dp): Dp {
                var maxPx = 0
                for (colIdx in -1 until activeProperties.size) {
                    val h = cellHeightsPx["${rowKey}_$colIdx"] ?: 0
                    if (h > maxPx) maxPx = h
                }
                val measuredDp = if (maxPx > 0) with(density) { maxPx.toDp() } else minDp
                return if (measuredDp > minDp) measuredDp else minDp
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(verticalScrollState)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .testTag("comparison_matrix_container")
            ) {
                // Top Identity Header Row (ALWAYS visible, even when Differences Only is true)
                val identityRowKey = "identity_header"
                val identityMinHeight = maxRowHeightDp(identityRowKey, 200.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceWhite)
                        .testTag("comparison_identity_row")
                ) {
                    // Fixed Left Corner Label
                    Box(
                        modifier = Modifier
                            .width(LABEL_COLUMN_WIDTH)
                            .heightIn(min = identityMinHeight)
                            .background(CompLabelBg)
                            .border(0.5.dp, CompBorder)
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        Column(
                            modifier = Modifier.onSizeChanged {
                                cellHeightsPx["${identityRowKey}_-1"] = it.height
                            }
                        ) {
                            Text(
                                text = "Property",
                                color = CompNavy,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Identity & Actions",
                                color = CompSecondaryText,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Horizontally Scrollable Identity Cards
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalScrollState)
                    ) {
                        activeProperties.forEachIndexed { colIdx, prop ->
                            IdentityHeaderPropertyCell(
                                property = prop,
                                minHeight = identityMinHeight,
                                onMeasuredHeight = { h ->
                                    cellHeightsPx["${identityRowKey}_$colIdx"] = h
                                },
                                onViewProperty = { onViewProperty(prop) },
                                onRemove = { onRemoveProperty(prop.id) }
                            )
                        }
                    }
                }

                HorizontalDivider(color = CompBorder, thickness = 1.dp)

                if (visibleRows.isEmpty() && showDifferencesOnly) {
                    Surface(
                        color = SurfaceWhite,
                        border = BorderStroke(1.dp, CompBorder),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .testTag("comparison_no_differences_notice")
                    ) {
                        Text(
                            text = "All 17 compared rows match across the selected properties.",
                            color = CompSecondaryText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    visibleRows.forEachIndexed { rowIndex, row ->
                        val rowHeight = maxRowHeightDp(row.key, 48.dp)
                        val zebraBg = if (rowIndex % 2 == 0) SurfaceWhite else CompSoftWhite
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(zebraBg)
                                .testTag("comparison_row_${row.key}")
                        ) {
                            // Fixed Left Label Cell
                            Box(
                                modifier = Modifier
                                    .width(LABEL_COLUMN_WIDTH)
                                    .heightIn(min = rowHeight)
                                    .background(CompLabelBg)
                                    .border(0.5.dp, CompBorder)
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = row.label,
                                    color = CompNavy,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.onSizeChanged {
                                        cellHeightsPx["${row.key}_-1"] = it.height
                                    }
                                )
                            }

                            // Synchronized Scrollable Value Cells
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(horizontalScrollState)
                            ) {
                                row.values.forEachIndexed { colIdx, value ->
                                    Box(
                                        modifier = Modifier
                                            .width(PROPERTY_COLUMN_WIDTH)
                                            .heightIn(min = rowHeight)
                                            .background(zebraBg)
                                            .border(0.5.dp, CompBorder)
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(
                                            text = value,
                                            color = if (value == "Not Applicable" || value == "Not Mentioned") {
                                                CompSecondaryText
                                            } else {
                                                CompNavy
                                            },
                                            fontSize = 12.sp,
                                            fontWeight = if (value == "Not Applicable" || value == "Not Mentioned") {
                                                FontWeight.Medium
                                            } else {
                                                FontWeight.SemiBold
                                            },
                                            lineHeight = 17.sp,
                                            modifier = Modifier
                                                .onSizeChanged {
                                                    cellHeightsPx["${row.key}_$colIdx"] = it.height
                                                }
                                                .testTag("comparison_cell_${row.key}_$colIdx")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedComparisonChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) PrimaryActionOrange else CompSoftWhite,
        border = BorderStroke(1.dp, if (selected) PrimaryActionOrange else CompBorder),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = label,
                color = if (selected) OnPrimaryActionColor else CompNavy,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun IdentityHeaderPropertyCell(
    property: PropertyItem,
    minHeight: Dp,
    onMeasuredHeight: (Int) -> Unit,
    onViewProperty: () -> Unit,
    onRemove: () -> Unit
) {
    val coverPhoto = remember(property.id, property.images) {
        property.images.firstOrNull { it.isNotBlank() }
    }
    Box(
        modifier = Modifier
            .width(PROPERTY_COLUMN_WIDTH)
            .heightIn(min = minHeight)
            .background(SurfaceWhite)
            .border(0.5.dp, CompBorder)
            .padding(10.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onMeasuredHeight(it.height) },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Cover photo thumbnail (90.dp) or No photo placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CompLabelBg)
                    .border(0.5.dp, CompBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (coverPhoto != null) {
                    PropertyPhoto(
                        model = coverPhoto,
                        contentDescription = property.projectName.ifBlank { property.propertyCode },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Apartment,
                            contentDescription = null,
                            tint = CompSecondaryText,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "No photo",
                            color = CompSecondaryText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Property Code badge
            Surface(
                color = CompTealSubtle,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = property.propertyCode.ifBlank { property.tab.code }.uppercase(Locale.ENGLISH),
                    color = CompTeal,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }

            // Project Name
            Text(
                text = property.projectName.ifBlank { "Project Not Mentioned" },
                color = CompNavy,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Location
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = CompTeal,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = property.location.ifBlank { "Location Not Mentioned" },
                    color = CompSecondaryText,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // View Property & Remove Buttons (min 48.dp touch target)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onViewProperty,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("comparison_view_property_${property.id}")
                ) {
                    Text(
                        text = "View Property",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onRemove,
                    border = BorderStroke(1.dp, CompBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CompNavy),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .testTag("comparison_remove_${property.id}")
                ) {
                    Text("Remove", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun FewerThanTwoComparisonState(
    selectedProperties: List<PropertyItem>,
    onViewProperty: (PropertyItem) -> Unit,
    onRemoveProperty: (String) -> Unit,
    onBrowseInventory: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, CompBorder),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CompTealSubtle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                        contentDescription = null,
                        tint = CompTeal,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = "Select at least 2 properties",
                    color = CompNavy,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("comparison_empty_heading")
                )

                Text(
                    text = if (selectedProperties.size == 1) {
                        "1 property (${selectedProperties.first().propertyCode.ifBlank { selectedProperties.first().projectName }}) is selected. Add at least 1 more listing to compare side-by-side."
                    } else {
                        "Choose 2 or 3 properties from inventory using the compare button or menu to inspect specs and commercials side-by-side."
                    },
                    color = CompSecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center
                )

                if (selectedProperties.size == 1) {
                    val onlyProp = selectedProperties.first()
                    Surface(
                        color = CompSoftWhite,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, CompBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = onlyProp.propertyCode.ifBlank { onlyProp.tab.code },
                                    color = CompTeal,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = onlyProp.projectName.ifBlank { "Selected Property" },
                                    color = CompNavy,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(
                                onClick = { onViewProperty(onlyProp) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("View", color = CompTeal, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { onRemoveProperty(onlyProp.id) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove selected property",
                                    tint = CompNavy
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = onBrowseInventory,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("comparison_browse_inventory_btn")
                ) {
                    Text(
                        text = "Browse Inventory",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun resolveComparisonTransactionType(property: PropertyItem): StandardTransactionType {
    return StandardTransactionType.fromString(property.transactionType, property.tab)
}

private fun resolvePublicPropertyType(property: PropertyItem): String {
    val raw = property.propertyType.trim()
    val generic = raw.equals("Commercial", true) || raw.equals("Residential", true) || raw.isBlank()
    return if (!generic) {
        raw
    } else {
        when (property.tab) {
            InventoryTab.OFF_LEASE, InventoryTab.OFF_OUT -> "Commercial Office"
            InventoryTab.SHOP_LEASE, InventoryTab.SHOP_OUT -> "Retail Shop / Showroom"
            InventoryTab.PRE_LEASED -> "Pre-Leased Commercial"
            InventoryTab.RESI_RENT, InventoryTab.RESI_RESALE -> "Residential Property"
            InventoryTab.UND_CONST -> "Under-Construction Residential"
        }
    }
}

private fun formatAreaSqFt(area: Double?): String {
    if (area == null || !area.isFinite() || area <= 0.0) return "Not Mentioned"
    val symbols = DecimalFormatSymbols(Locale.ENGLISH)
    val formatted = if (area % 1.0 == 0.0) {
        DecimalFormat("#,##0", symbols).format(area.toLong())
    } else {
        DecimalFormat("#,##0.##", symbols).format(area)
    }
    return "$formatted sq.ft"
}

private fun resolveMonthlyRentAmount(property: PropertyItem, txType: StandardTransactionType): Double? {
    return when (txType) {
        StandardTransactionType.LEASE -> {
            property.rent?.takeIf { it.isFinite() && it > 0.0 }
                ?: property.monthlyRentalIncome?.takeIf { it.isFinite() && it > 0.0 }
                ?: property.priceOrRent.takeIf { it.isFinite() && it > 0.0 }
        }
        StandardTransactionType.PRE_LEASED_SALE -> {
            property.monthlyRentalIncome?.takeIf { it.isFinite() && it > 0.0 }
                ?: property.rent?.takeIf { it.isFinite() && it > 0.0 }
        }
        StandardTransactionType.SALE -> null
    }
}

private fun resolveSalePriceAmount(property: PropertyItem, txType: StandardTransactionType): Double? {
    return when (txType) {
        StandardTransactionType.SALE, StandardTransactionType.PRE_LEASED_SALE -> {
            property.priceSale?.takeIf { it.isFinite() && it > 0.0 }
                ?: property.priceOrRent.takeIf { it.isFinite() && it > 0.0 }
        }
        StandardTransactionType.LEASE -> null
    }
}

private fun formatMonthlyRentCell(property: PropertyItem): String {
    val txType = resolveComparisonTransactionType(property)
    if (txType == StandardTransactionType.SALE) return "Not Applicable"
    val rentVal = resolveMonthlyRentAmount(property, txType) ?: return "Not Mentioned"
    return CalculationUtils.formatIndianCurrency(rentVal, isMonthly = true)
}

private fun formatSalePriceCell(property: PropertyItem): String {
    val txType = resolveComparisonTransactionType(property)
    if (txType == StandardTransactionType.LEASE) return "Not Applicable"
    val saleVal = resolveSalePriceAmount(property, txType) ?: return "Not Mentioned"
    return CalculationUtils.formatIndianCurrency(saleVal, isMonthly = false)
}

private fun formatPerSqFtRatesCell(property: PropertyItem): String {
    val txType = resolveComparisonTransactionType(property)
    val isRentPrimary = txType == StandardTransactionType.LEASE
    val baseAmount = if (isRentPrimary) {
        resolveMonthlyRentAmount(property, txType)
    } else {
        resolveSalePriceAmount(property, txType)
    } ?: return "Not Mentioned"

    if (!baseAmount.isFinite() || baseAmount <= 0.0) return "Not Mentioned"
    val unitSuffix = if (isRentPrimary) "/sq.ft/mo" else "/sq.ft"
    val parts = mutableListOf<String>()

    val carpet = property.carpetAreaSqFt
    if (carpet.isFinite() && carpet > 0.0) {
        val carpetRate = baseAmount / carpet
        if (carpetRate.isFinite() && carpetRate > 0.0) {
            parts.add("Carpet: ${CalculationUtils.formatIndianCurrencyFull(carpetRate)}$unitSuffix")
        }
    }

    val builtUp = property.builtUpAreaSqFt
    if (builtUp != null && builtUp.isFinite() && builtUp > 0.0) {
        val builtUpRate = baseAmount / builtUp
        if (builtUpRate.isFinite() && builtUpRate > 0.0) {
            parts.add("Built-up: ${CalculationUtils.formatIndianCurrencyFull(builtUpRate)}$unitSuffix")
        }
    }

    return if (parts.isEmpty()) "Not Mentioned" else parts.joinToString("\n")
}

private fun formatDepositCell(property: PropertyItem): String {
    val dep = property.deposit?.takeIf { it.isFinite() && it > 0.0 } ?: return "Not Mentioned"
    return CalculationUtils.formatIndianCurrency(dep, isMonthly = false)
}

private fun formatMaintenanceCell(property: PropertyItem): String {
    val amount = property.maintenance?.takeIf { it.isFinite() && it > 0.0 }
    val basisSuffix = when (property.maintenanceBasis.trim().uppercase(Locale.ENGLISH)) {
        "MONTHLY" -> "/mo"
        "PER_CARPET_SQFT_MONTH" -> "/carpet sq.ft/mo"
        "PER_BUILTUP_SQFT_MONTH" -> "/built-up sq.ft/mo"
        else -> " (basis not specified)"
    }
    return when {
        property.maintenanceIncluded == true && amount != null ->
            "Included (${CalculationUtils.formatIndianCurrencyFull(amount)}$basisSuffix)"
        property.maintenanceIncluded == true -> "Included"
        amount != null && property.maintenanceIncluded == false ->
            "${CalculationUtils.formatIndianCurrencyFull(amount)}$basisSuffix (Excluded)"
        amount != null ->
            "${CalculationUtils.formatIndianCurrencyFull(amount)}$basisSuffix"
        property.maintenanceIncluded == false -> "Excluded (amount not mentioned)"
        else -> "Not Mentioned"
    }
}

private fun formatGrossYieldCell(property: PropertyItem): String {
    val txType = resolveComparisonTransactionType(property)
    val monthlyRent = resolveMonthlyRentAmount(property, txType)
    val salePrice = resolveSalePriceAmount(property, txType)
    if (monthlyRent != null && monthlyRent.isFinite() && monthlyRent > 0.0 &&
        salePrice != null && salePrice.isFinite() && salePrice > 0.0
    ) {
        val grossYield = (monthlyRent * 12.0 / salePrice) * 100.0
        if (grossYield.isFinite() && grossYield > 0.0) {
            val formatted = DecimalFormat("0.00", DecimalFormatSymbols(Locale.ENGLISH)).format(grossYield)
            return "$formatted%"
        }
    }
    return if (txType == StandardTransactionType.PRE_LEASED_SALE) {
        "Not Mentioned"
    } else {
        "Not Applicable"
    }
}

private fun buildComparisonMatrixRows(properties: List<PropertyItem>): List<ComparisonRowData> {
    return listOf(
        ComparisonRowData(
            key = "type",
            label = "Type",
            values = properties.map { resolvePublicPropertyType(it) }
        ),
        ComparisonRowData(
            key = "transaction",
            label = "Transaction",
            values = properties.map { resolveComparisonTransactionType(it).label }
        ),
        ComparisonRowData(
            key = "carpet_area",
            label = "Carpet Area",
            values = properties.map { formatAreaSqFt(it.carpetAreaSqFt) }
        ),
        ComparisonRowData(
            key = "builtup_area",
            label = "Built-up Area",
            values = properties.map { formatAreaSqFt(it.builtUpAreaSqFt) }
        ),
        ComparisonRowData(
            key = "furnishing",
            label = "Furnishing",
            values = properties.map { it.furnishing.label }
        ),
        ComparisonRowData(
            key = "configuration",
            label = "Configuration",
            values = properties.map { it.bhk?.trim()?.takeIf { b -> b.isNotBlank() } ?: "Not Mentioned" }
        ),
        ComparisonRowData(
            key = "floor",
            label = "Floor",
            values = properties.map {
                it.floor.trim().ifBlank { it.floorLevel?.trim().orEmpty() }.ifBlank { "Not Mentioned" }
            }
        ),
        ComparisonRowData(
            key = "parking",
            label = "Parking",
            values = properties.map {
                it.parkingCount?.toString() ?: it.parking.trim().ifBlank { "Not Mentioned" }
            }
        ),
        ComparisonRowData(
            key = "possession",
            label = "Possession",
            values = properties.map { it.possession.trim().ifBlank { "Not Mentioned" } }
        ),
        ComparisonRowData(
            key = "availability",
            label = "Availability",
            values = properties.map { it.status.label }
        ),
        ComparisonRowData(
            key = "verification",
            label = "Verification",
            values = properties.map {
                val datePart = it.lastVerifiedDateClean.trim()
                    .ifBlank { it.lastVerificationDate.trim() }
                    .ifBlank { "Not Mentioned" }
                "${it.verificationStatus.label} ($datePart)"
            }
        ),
        ComparisonRowData(
            key = "monthly_rent",
            label = "Monthly Rent",
            values = properties.map(::formatMonthlyRentCell)
        ),
        ComparisonRowData(
            key = "sale_price",
            label = "Sale Price",
            values = properties.map(::formatSalePriceCell)
        ),
        ComparisonRowData(
            key = "per_sqft_rates",
            label = "Per-sq.ft Rates",
            values = properties.map(::formatPerSqFtRatesCell)
        ),
        ComparisonRowData(
            key = "deposit",
            label = "Deposit",
            values = properties.map(::formatDepositCell)
        ),
        ComparisonRowData(
            key = "maintenance",
            label = "Maintenance",
            values = properties.map(::formatMaintenanceCell)
        ),
        ComparisonRowData(
            key = "gross_yield",
            label = "Indicative Gross Yield",
            values = properties.map(::formatGrossYieldCell)
        )
    )
}
