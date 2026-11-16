package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.components.PropertyCard
import com.example.ui.theme.*
import kotlin.math.roundToInt

data class GeoPoint(val lat: Double, val lng: Double)

object PuneGeoUtils {
    val microMarkets = mapOf(
        "baner" to GeoPoint(18.5590, 73.7868),
        "kharadi" to GeoPoint(18.5515, 73.9462),
        "hinjewadi" to GeoPoint(18.5912, 73.7389),
        "hinjawadi" to GeoPoint(18.5912, 73.7389),
        "kalyani nagar" to GeoPoint(18.5463, 73.9033),
        "viman nagar" to GeoPoint(18.5679, 73.9143),
        "balewadi" to GeoPoint(18.5789, 73.7707),
        "hadapsar" to GeoPoint(18.5089, 73.9259),
        "koregaon park" to GeoPoint(18.5362, 73.8940),
        "wakad" to GeoPoint(18.5987, 73.7661),
        "kothrud" to GeoPoint(18.5074, 73.8077),
        "pimple saudagar" to GeoPoint(18.5987, 73.7997),
        "senapati bapat road" to GeoPoint(18.5314, 73.8298),
        "sb road" to GeoPoint(18.5314, 73.8298),
        "shivaji nagar" to GeoPoint(18.5314, 73.8446),
        "bund garden" to GeoPoint(18.5362, 73.8786),
        "camp" to GeoPoint(18.5158, 73.8786),
        "aundh" to GeoPoint(18.5580, 73.8075),
        "bavdhan" to GeoPoint(18.5117, 73.7806),
        "magarpatta" to GeoPoint(18.5186, 73.9288),
        "deccan" to GeoPoint(18.5167, 73.8417),
        "chinchwad" to GeoPoint(18.6298, 73.7997),
        "pimpri" to GeoPoint(18.6298, 73.8120),
        "punawale" to GeoPoint(18.6250, 73.7360),
        "tathawade" to GeoPoint(18.6110, 73.7520),
        "ravet" to GeoPoint(18.6480, 73.7490),
        "undri" to GeoPoint(18.4600, 73.9100),
        "kondhwa" to GeoPoint(18.4700, 73.8800),
        "wagholi" to GeoPoint(18.5800, 73.9800),
        "warje" to GeoPoint(18.4800, 73.8000),
        "sinhagad road" to GeoPoint(18.4700, 73.8300)
    )

    fun resolveCoordinates(property: PropertyItem): GeoPoint? {
        val lat = property.lat
        val lng = property.lng
        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 &&
            !(lat == 0.0 && lng == 0.0)) {
            return GeoPoint(lat, lng)
        }
        // Unknown coordinates must never be presented as an exact building pin.
        return null
    }

}

enum class MapViewMode {
    MAP,
    LIST
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapSearchScreen(
    allProperties: List<PropertyItem>,
    initialTargetProperty: PropertyItem? = null,
    initialPortfolio: Brand? = null,
    initialLocationFilter: String? = null,
    onPropertyClick: (PropertyItem) -> Unit,
    onShareWhatsApp: (PropertyItem) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // State
    var showFilterScreen by remember { mutableStateOf(initialTargetProperty == null && initialLocationFilter == null) }
    var viewMode by remember { mutableStateOf(MapViewMode.MAP) }

    // Filter Fields
    var selectedPortfolio by remember { mutableStateOf(initialPortfolio ?: Brand.COMMERCIAL) }
    var selectedTypes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedBhkFilter by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedTransactionType by remember { mutableStateOf("Both") }
    var minPriceText by remember { mutableStateOf("") }
    var maxPriceText by remember { mutableStateOf("") }
    var minAreaText by remember { mutableStateOf("") }
    var maxAreaText by remember { mutableStateOf("") }
    var selectedLocation by remember { mutableStateOf(initialLocationFilter ?: "All Areas") }

    var selectedPropertyOnMap by remember { mutableStateOf<PropertyItem?>(initialTargetProperty) }

    val puneLocations = listOf(
        "All Areas", "Baner", "Kharadi", "Hinjewadi", "Kalyani Nagar", "Viman Nagar",
        "Balewadi", "Hadapsar", "Koregaon Park", "Wakad", "Kothrud", "Pimple Saudagar",
        "Aundh", "Senapati Bapat Road", "Bavdhan", "Magarpatta", "Wagholi", "Chinchwad"
    )

    // Compute Filtered Properties
    val filteredProperties = remember(
        allProperties, selectedPortfolio, selectedTypes, selectedBhkFilter,
        selectedTransactionType, minPriceText, maxPriceText, minAreaText, maxAreaText, selectedLocation
    ) {
        allProperties.filter { prop ->
            // Portfolio filter
            val matchesPortfolio = when (selectedPortfolio) {
                Brand.COMMERCIAL -> prop.isCommercial()
                Brand.RESIDENTIAL -> prop.isResidential()
            }
            if (!matchesPortfolio) return@filter false

            // Property type filter
            if (selectedTypes.isNotEmpty()) {
                val propTypeLower = prop.propertyType.lowercase()
                val matchesType = selectedTypes.any { type ->
                    val t = type.lowercase()
                    propTypeLower.contains(t) ||
                            (t == "office" && prop.isCommercial()) ||
                            (t == "apartment" && (propTypeLower.contains("flat") || propTypeLower.contains("ready"))) ||
                            (t == "villa" && propTypeLower.contains("bungalow"))
                }
                if (!matchesType) return@filter false
            }

            // BHK filter (Residential)
            if (selectedBhkFilter.isNotEmpty() && prop.isResidential()) {
                val propBhk = prop.bhk?.uppercase() ?: ""
                val matchesBhk = selectedBhkFilter.any { bhk ->
                    if (bhk == "4+ BHK") {
                        propBhk.contains("4.") || propBhk.contains("5") || propBhk.contains("6")
                    } else {
                        propBhk.contains(bhk.replace(" BHK", "").trim())
                    }
                }
                if (!matchesBhk) return@filter false
            }

            // Transaction type filter
            if (selectedTransactionType != "Both") {
                val isLease = prop.tab == InventoryTab.OFF_LEASE || prop.tab == InventoryTab.SHOP_LEASE || prop.tab == InventoryTab.RESI_RENT
                if (selectedTransactionType == "Lease / Rent" && !isLease) return@filter false
                if (selectedTransactionType == "Outright / Sale" && isLease) return@filter false
            }

            // Price filter
            val minP = minPriceText.toDoubleOrNull()
            val maxP = maxPriceText.toDoubleOrNull()
            if (minP != null && prop.priceOrRent < minP) return@filter false
            if (maxP != null && prop.priceOrRent > maxP) return@filter false

            // Area filter
            val minA = minAreaText.toDoubleOrNull()
            val maxA = maxAreaText.toDoubleOrNull()
            if (minA != null && prop.carpetAreaSqFt < minA) return@filter false
            if (maxA != null && prop.carpetAreaSqFt > maxA) return@filter false

            // Location filter
            if (selectedLocation != "All Areas") {
                val locLower = prop.location.lowercase()
                val projLower = prop.projectName.lowercase()
                val targetLoc = selectedLocation.lowercase()
                if (!locLower.contains(targetLoc) && !projLower.contains(targetLoc)) return@filter false
            }

            true
        }
    }

    BackHandler {
        if (!showFilterScreen && initialTargetProperty == null) {
            showFilterScreen = true
        } else {
            onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .testTag("map_search_screen")
    ) {
        // TOP APP BAR
        Surface(
            color = BrandCharcoalSurface,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(
                        onClick = {
                            if (!showFilterScreen && initialTargetProperty == null) {
                                showFilterScreen = true
                            } else {
                                onBack()
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandCharcoalCard)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimaryDark)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = BrandGold, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Property Map",
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                maxLines = 1,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = if (showFilterScreen) "Select Search Criteria" else "${filteredProperties.size} Properties Found",
                            color = BrandGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (!showFilterScreen) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Filter button
                        IconButton(
                            onClick = { showFilterScreen = true },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(BrandCharcoalCard)
                                .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "Edit Filters", tint = BrandGold)
                        }

                        // Map / List Toggle
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(BrandCharcoalCard)
                                .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                                .padding(2.dp)
                        ) {
                            Row {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (viewMode == MapViewMode.MAP) BrandGold else Color.Transparent)
                                        .clickable { viewMode = MapViewMode.MAP }
                                        .widthIn(min = 48.dp)
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Map",
                                        color = if (viewMode == MapViewMode.MAP) Color.White else TextSecondaryDark,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (viewMode == MapViewMode.LIST) BrandGold else Color.Transparent)
                                        .clickable { viewMode = MapViewMode.LIST }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "List",
                                        color = if (viewMode == MapViewMode.LIST) Color.White else TextSecondaryDark,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showFilterScreen) {
            // STEP 2: CLEAN FILTER SCREEN BEFORE MAP
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = "SEARCH FILTERS",
                    color = BrandGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Filter Pune Master Inventory",
                    color = TextPrimaryDark,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 1. Portfolio Selector
                Text("Select Inventory", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PortfolioChip(
                        title = "Commercial Inventory",
                        subtitle = "Office, Shop, Land",
                        isSelected = selectedPortfolio == Brand.COMMERCIAL,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedPortfolio = Brand.COMMERCIAL
                            selectedTypes = emptySet()
                        }
                    )
                    PortfolioChip(
                        title = "Residential Inventory",
                        subtitle = "Flat, Villa, Plot",
                        isSelected = selectedPortfolio == Brand.RESIDENTIAL,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedPortfolio = Brand.RESIDENTIAL
                            selectedTypes = emptySet()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Property Type Selector
                Text("Property Type", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                val availableTypes = if (selectedPortfolio == Brand.COMMERCIAL) {
                    listOf("Office", "Shop", "Showroom", "Warehouse", "Pre-Leased", "Commercial Land")
                } else {
                    listOf("Apartment", "Villa", "Bungalow", "Residential Plot")
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterMultiChip(
                        label = "All Types",
                        isSelected = selectedTypes.isEmpty(),
                        onClick = { selectedTypes = emptySet() }
                    )
                    availableTypes.forEach { type ->
                        val isSel = selectedTypes.contains(type)
                        FilterMultiChip(
                            label = type,
                            isSelected = isSel,
                            onClick = {
                                selectedTypes = if (isSel) selectedTypes - type else selectedTypes + type
                            }
                        )
                    }
                }

                // 3. BHK Filter for Residential
                if (selectedPortfolio == Brand.RESIDENTIAL) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Configuration (BHK)", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("1 BHK", "2 BHK", "3 BHK", "4 BHK", "4+ BHK").forEach { bhk ->
                            val isSel = selectedBhkFilter.contains(bhk)
                            FilterMultiChip(
                                label = bhk,
                                isSelected = isSel,
                                onClick = {
                                    selectedBhkFilter = if (isSel) selectedBhkFilter - bhk else selectedBhkFilter + bhk
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 4. Transaction Type
                Text("Transaction Type", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Both", "Lease / Rent", "Outright / Sale").forEach { tx ->
                        FilterSingleChip(
                            label = tx,
                            isSelected = selectedTransactionType == tx,
                            onClick = { selectedTransactionType = tx }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 5. Price & Area Ranges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Min Price / Rent (₹)", color = TextSecondaryDark, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = minPriceText,
                            onValueChange = { minPriceText = it },
                            placeholder = { Text("e.g. 50000", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandGold,
                                unfocusedBorderColor = BrandCharcoalBorder,
                                focusedContainerColor = BrandCharcoalSurface,
                                unfocusedContainerColor = BrandCharcoalSurface,
                                focusedTextColor = TextPrimaryDark,
                                unfocusedTextColor = TextPrimaryDark
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Max Price / Rent (₹)", color = TextSecondaryDark, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = maxPriceText,
                            onValueChange = { maxPriceText = it },
                            placeholder = { Text("e.g. 500000", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandGold,
                                unfocusedBorderColor = BrandCharcoalBorder,
                                focusedContainerColor = BrandCharcoalSurface,
                                unfocusedContainerColor = BrandCharcoalSurface,
                                focusedTextColor = TextPrimaryDark,
                                unfocusedTextColor = TextPrimaryDark
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Min Carpet Area (sq.ft)", color = TextSecondaryDark, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = minAreaText,
                            onValueChange = { minAreaText = it },
                            placeholder = { Text("e.g. 800", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandGold,
                                unfocusedBorderColor = BrandCharcoalBorder,
                                focusedContainerColor = BrandCharcoalSurface,
                                unfocusedContainerColor = BrandCharcoalSurface,
                                focusedTextColor = TextPrimaryDark,
                                unfocusedTextColor = TextPrimaryDark
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Max Carpet Area (sq.ft)", color = TextSecondaryDark, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = maxAreaText,
                            onValueChange = { maxAreaText = it },
                            placeholder = { Text("e.g. 3000", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandGold,
                                unfocusedBorderColor = BrandCharcoalBorder,
                                focusedContainerColor = BrandCharcoalSurface,
                                unfocusedContainerColor = BrandCharcoalSurface,
                                focusedTextColor = TextPrimaryDark,
                                unfocusedTextColor = TextPrimaryDark
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 6. Pune Location Selector
                Text("Pune Micro-Market / Area", color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(puneLocations) { loc ->
                        FilterSingleChip(
                            label = loc,
                            isSelected = selectedLocation == loc,
                            onClick = { selectedLocation = loc }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            selectedTypes = emptySet()
                            selectedBhkFilter = emptySet()
                            selectedTransactionType = "Both"
                            minPriceText = ""
                            maxPriceText = ""
                            minAreaText = ""
                            maxAreaText = ""
                            selectedLocation = "All Areas"
                        },
                        modifier = Modifier.height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondaryDark)
                    ) {
                        Text("Clear Filters", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { showFilterScreen = false },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "View ${filteredProperties.size} Properties on Map",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        } else {
            // MAP / LIST VIEW SCREEN
            if (filteredProperties.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(BrandCharcoalSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = TextMutedDark, modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No Properties Match Your Map Filters", color = TextPrimaryDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Try widening your price range, carpet area or area criteria.",
                            color = TextSecondaryDark,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = { showFilterScreen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
                        ) {
                            Text("Adjust Filters", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (viewMode == MapViewMode.LIST) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredProperties) { prop ->
                        PropertyCard(
                            property = prop,
                            isInternalMode = true,
                            isFavorite = false,
                            isInComparison = false,
                            onCardClick = { onPropertyClick(prop) },
                            onToggleFavorite = { },
                            onToggleCompare = { },
                            onShareWhatsApp = { onShareWhatsApp(prop) }
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (filteredProperties.any { PuneGeoUtils.resolveCoordinates(it) == null }) {
                        Text(
                            "Only listings with saved coordinates appear as pins. Others are available in List view.",
                            color = TextPrimaryDark,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.TopCenter)
                                .background(BrandCharcoalSurface, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        )
                    }
                    InteractivePuneCanvasMap(
                        properties = filteredProperties,
                        selectedProperty = selectedPropertyOnMap,
                        onSelectProperty = { selectedPropertyOnMap = it }
                    )

                    // Property Summary Card at bottom
                    if (selectedPropertyOnMap != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        ) {
                            MapPropertySummaryCard(
                                property = selectedPropertyOnMap!!,
                                onViewDetails = { onPropertyClick(selectedPropertyOnMap!!) },
                                onShareWhatsApp = { onShareWhatsApp(selectedPropertyOnMap!!) },
                                onGetDirections = {
                                    val loc = Uri.encode("${selectedPropertyOnMap!!.projectName}, ${selectedPropertyOnMap!!.location}, Pune")
                                    val gmapsUri = Uri.parse("geo:0,0?q=$loc")
                                    val mapIntent = Intent(Intent.ACTION_VIEW, gmapsUri).apply {
                                        setPackage("com.google.android.apps.maps")
                                    }
                                    try {
                                        context.startActivity(mapIntent)
                                    } catch (e: Exception) {
                                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$loc"))
                                        context.startActivity(webIntent)
                                    }
                                },
                                onClose = { selectedPropertyOnMap = null }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InteractivePuneCanvasMap(
    properties: List<PropertyItem>,
    selectedProperty: PropertyItem?,
    onSelectProperty: (PropertyItem) -> Unit
) {
    val propertyCoords = remember(properties) {
        properties.mapNotNull { prop -> PuneGeoUtils.resolveCoordinates(prop)?.let { prop to it } }
    }

    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }

    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF13171F))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    zoomScale = (zoomScale * zoom).coerceIn(0.6f, 3.0f)
                    panOffset += pan
                }
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSpacing = 80f * zoomScale
            val startX = (panOffset.x % gridSpacing)
            val startY = (panOffset.y % gridSpacing)

            var x = startX
            while (x < size.width) {
                drawLine(
                    color = Color(0xFF222938),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f
                )
                x += gridSpacing
            }
            var y = startY
            while (y < size.height) {
                drawLine(
                    color = Color(0xFF222938),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += gridSpacing
            }

            val riverPath = Path().apply {
                moveTo(0f, size.height * 0.45f)
                cubicTo(
                    size.width * 0.3f, size.height * 0.35f,
                    size.width * 0.6f, size.height * 0.55f,
                    size.width, size.height * 0.40f
                )
            }
            drawPath(
                path = riverPath,
                color = Color(0xFF1E3A5F).copy(alpha = 0.5f),
                style = Stroke(width = 18f * zoomScale)
            )

            drawLine(
                color = Color(0xFF2A364F),
                start = Offset(size.width * 0.1f, 0f),
                end = Offset(size.width * 0.9f, size.height),
                strokeWidth = 4f * zoomScale
            )
            drawLine(
                color = Color(0xFF2A364F),
                start = Offset(size.width * 0.2f, size.height * 0.2f),
                end = Offset(size.width * 0.95f, size.height * 0.45f),
                strokeWidth = 4f * zoomScale
            )
        }

        val minLat = propertyCoords.minOfOrNull { it.second.lat } ?: 18.4500
        val maxLat = propertyCoords.maxOfOrNull { it.second.lat } ?: 18.6500
        val minLng = propertyCoords.minOfOrNull { it.second.lng } ?: 73.7200
        val maxLng = propertyCoords.maxOfOrNull { it.second.lng } ?: 73.9800

        val latSpan = (maxLat - minLat).coerceAtLeast(0.02)
        val lngSpan = (maxLng - minLng).coerceAtLeast(0.02)

        val paddingPx = with(density) { 80.dp.toPx() }

        propertyCoords.forEach { (prop, geo) ->
            val normX = ((geo.lng - minLng) / lngSpan).toFloat()
            val normY = (1.0f - ((geo.lat - minLat) / latSpan)).toFloat()

            val basePxX = paddingPx + normX * (widthPx - 2 * paddingPx)
            val basePxY = paddingPx + normY * (heightPx - 2 * paddingPx)

            val finalX = (basePxX * zoomScale) + panOffset.x
            val finalY = (basePxY * zoomScale) + panOffset.y

            val isSelected = selectedProperty?.id == prop.id
            val isCommercial = prop.isCommercial()
            val pinColor = if (isCommercial) BrandGold else Color(0xFF10B981)

            Box(
                modifier = Modifier
                    .offset { IntOffset(finalX.roundToInt() - 24, finalY.roundToInt() - 40) }
                    .clickable { onSelectProperty(prop) }
                    .testTag("map_pin_${prop.propertyCode.lowercase()}")
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) BrandGold else BrandCharcoalSurface)
                            .border(1.2.dp, if (isSelected) BrandGold else pinColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = prop.propertyCode,
                            color = if (isSelected) Color.White else TextPrimaryDark,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = prop.projectName,
                        tint = if (isSelected) BrandGold else pinColor,
                        modifier = Modifier.size(if (isSelected) 36.dp else 28.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BrandCharcoalSurface.copy(alpha = 0.85f))
                .border(0.8.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(BrandGold))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Commercial", color = TextPrimaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.width(12.dp))

                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF10B981)))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Residential", color = TextPrimaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MapPropertySummaryCard(
    property: PropertyItem,
    onViewDetails: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onGetDirections: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("map_summary_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(BrandGold.copy(alpha = 0.2f))
                            .border(0.8.dp, BrandGold, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(property.propertyCode, color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val verifiedColor = if (property.verificationStatus == VerificationStatus.VERIFIED) StatusAvailable else StatusUnconfirmed
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(verifiedColor.copy(alpha = 0.2f))
                            .border(0.8.dp, verifiedColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(property.verificationStatus.label, color = verifiedColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }

                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = property.projectName,
                color = TextPrimaryDark,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Default.Place, contentDescription = null, tint = BrandGold, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${property.unitNumber} • ${property.location}",
                    color = TextSecondaryDark,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("PRICE / RENT", color = TextMutedDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "₹${CalculationUtils.formatIndianCurrency(property.priceOrRent)}",
                        color = BrandGold,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("CARPET AREA", color = TextMutedDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${property.carpetAreaSqFt.toInt()} sq.ft",
                        color = TextPrimaryDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onGetDirections,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, BrandCharcoalBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Directions", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                OutlinedButton(
                    onClick = onShareWhatsApp,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, BrandGold),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandGold),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onViewDetails,
                    modifier = Modifier.weight(1.2f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("View Details", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun PortfolioChip(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) BrandGold.copy(alpha = 0.15f) else BrandCharcoalSurface)
            .border(1.2.dp, if (isSelected) BrandGold else BrandCharcoalBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Column {
            Text(title, color = if (isSelected) BrandGold else TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSecondaryDark, fontSize = 10.sp)
        }
    }
}

@Composable
fun FilterMultiChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) BrandGold else BrandCharcoalSurface)
            .border(0.8.dp, if (isSelected) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) BrandCharcoalDark else TextPrimaryDark,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun FilterSingleChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) BrandGold else BrandCharcoalSurface)
            .border(0.8.dp, if (isSelected) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) BrandCharcoalDark else TextPrimaryDark,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

