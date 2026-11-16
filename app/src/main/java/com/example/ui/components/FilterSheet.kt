package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.PropertiesLocalDatabase
import com.example.data.projects.ProjectDirectoryStore
import com.example.inventory.MatchTolerance
import com.example.model.AreaRateBasis
import com.example.model.AvailabilityStatus
import com.example.model.Brand
import com.example.model.CalculationUtils
import com.example.model.FurnishingType
import com.example.model.InventoryTab
import com.example.model.TlcStandardOptions
import com.example.ui.theme.*
import kotlinx.coroutines.delay

data class FilterState(
    val brand: Brand? = null,
    val tab: InventoryTab? = null,
    val status: AvailabilityStatus? = null,
    val furnishing: FurnishingType? = null,
    val minCarpet: Double = 0.0,
    val maxCarpet: Double = Double.MAX_VALUE,
    val minPrice: Double = 0.0,
    val maxPrice: Double = Double.MAX_VALUE,
    val verifiedOnly: Boolean = false,
    val transactionType: String = "All", // "All", "Sale", "Lease", "Pre-Leased"
    val bhkFilter: String? = null,
    val onlyMainRoad: Boolean = false,
    val propertyTypeFilter: String? = null,
    val locationFilter: String = "",
    val projectFilter: String = "",
    val possessionFilter: String? = null,
    val parkingFilter: String? = null,
    val tolerance: MatchTolerance = MatchTolerance.EXACT,
    val areaBasis: AreaRateBasis = AreaRateBasis.CARPET,
    val minWorkstations: Int = 0,
    val minCabins: Int = 0,
    val minFrontageFeet: Double = 0.0,
    val respectHardBudgetCeiling: Boolean = false
) {
    fun hasActiveFilters(): Boolean =
        brand != null ||
            tab != null ||
            status != null ||
            furnishing != null ||
            minCarpet > 0.0 ||
            maxCarpet < Double.MAX_VALUE ||
            minPrice > 0.0 ||
            maxPrice < Double.MAX_VALUE ||
            verifiedOnly ||
            transactionType != "All" ||
            !bhkFilter.isNullOrBlank() ||
            onlyMainRoad ||
            !propertyTypeFilter.isNullOrBlank() ||
            locationFilter.isNotBlank() ||
            projectFilter.isNotBlank() ||
            !possessionFilter.isNullOrBlank() ||
            !parkingFilter.isNullOrBlank() ||
            tolerance != MatchTolerance.EXACT ||
            areaBasis != AreaRateBasis.CARPET ||
            minWorkstations > 0 ||
            minCabins > 0 ||
            minFrontageFeet > 0.0
}

private val COMMERCIAL_ONLY_TYPES = setOf("Office", "Shop", "Showroom", "Retail", "Warehouse")
private val RESIDENTIAL_ONLY_TYPES = setOf("Apartment", "Villa", "Bungalow", "Duplex", "Penthouse")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowCustom: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }
    val displayText = selectedValue.ifBlank { "Select $label" }

    Column(modifier = modifier) {
        Text(
            text = label,
            color = SecondaryText,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = SurfaceWhite,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(if (expanded) 1.5.dp else 1.dp, if (expanded) PrimaryTeal else BorderGrey),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = displayText,
                        color = if (selectedValue.isBlank()) SecondaryText else TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (selectedValue.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Select $label",
                        tint = if (expanded) PrimaryTeal else SecondaryText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = SurfaceWhite
            ) {
                options.forEach { option ->
                    val isSelected = option.equals(selectedValue, ignoreCase = true)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) PrimaryTeal else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = PrimaryTeal,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        onClick = {
                            onValueSelected(option)
                            expanded = false
                        }
                    )
                }
                if (allowCustom) {
                    HorizontalDivider(color = BorderGrey)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "+ Custom / Other...",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryTeal
                            )
                        },
                        onClick = {
                            expanded = false
                            customInput = selectedValue
                            showCustomDialog = true
                        }
                    )
                }
            }
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            containerColor = SurfaceWhite,
            title = {
                Text(
                    text = "Custom $label",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = { Text(label, fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleaned = customInput.trim()
                        if (cleaned.isNotBlank()) {
                            onValueSelected(cleaned)
                        }
                        showCustomDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryTeal,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Apply", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowAddNew: Boolean = true
) {
    var showSearchSheet by remember { mutableStateOf(false) }
    val displayText = selectedValue.ifBlank { "Search $label" }

    Column(modifier = modifier) {
        Text(
            text = label,
            color = SecondaryText,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
        )
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(if (showSearchSheet) 1.5.dp else 1.dp, if (showSearchSheet) PrimaryTeal else BorderGrey),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { showSearchSheet = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = displayText,
                    color = if (selectedValue.isBlank()) SecondaryText else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (selectedValue.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (selectedValue.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear $label",
                            tint = SecondaryText,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onValueSelected("") }
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Open $label search",
                        tint = SecondaryText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showSearchSheet) {
        var query by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        val filteredOptions = remember(query, options) {
            val clean = query.trim()
            if (clean.isBlank()) options else options.filter { it.contains(clean, ignoreCase = true) }
        }
        val hasExactMatch = remember(query, options) {
            val clean = query.trim()
            clean.isNotBlank() && options.any { it.equals(clean, ignoreCase = true) }
        }

        LaunchedEffect(showSearchSheet) {
            delay(80)
            runCatching { focusRequester.requestFocus() }
            keyboardController?.show()
        }

        ModalBottomSheet(
            onDismissRequest = { showSearchSheet = false },
            containerColor = BackgroundSoft
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select $label",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (selectedValue.isNotBlank()) {
                        TextButton(onClick = {
                            onValueSelected("")
                            showSearchSheet = false
                        }) {
                            Text("Clear", color = PrimaryTeal, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Type to search $label...", fontSize = 13.sp, color = SecondaryText) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = PrimaryTeal,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = SecondaryText)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite,
                        focusedBorderColor = PrimaryTeal,
                        unfocusedBorderColor = BorderGrey
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredOptions) { option ->
                        val isSelected = option.equals(selectedValue, ignoreCase = true) ||
                            (option.contains("•") && option.substringAfter("•").trim().equals(selectedValue, ignoreCase = true))
                        Surface(
                            color = if (isSelected) PrimaryTeal.copy(alpha = 0.1f) else SurfaceWhite,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (isSelected) PrimaryTeal else BorderGrey.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    onValueSelected(option)
                                    showSearchSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = option,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) PrimaryTeal else TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = PrimaryTeal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (allowAddNew && query.isNotBlank() && !hasExactMatch) {
                        item {
                            Surface(
                                color = SelectedSurfaceContainer,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, PrimaryTeal),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        val custom = query.trim()
                                        onValueSelected(custom)
                                        showSearchSheet = false
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = "+ Use \"${query.trim()}\"",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryTeal
                                    )
                                }
                            }
                        }
                    }

                    if (filteredOptions.isEmpty() && (!allowAddNew || query.isBlank())) {
                        item {
                            Text(
                                text = "No matching $label found",
                                color = SecondaryText,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    filterState: FilterState,
    onFilterChange: (FilterState) -> Unit,
    onDismiss: () -> Unit,
    matchingCountPreview: Int? = null
) {
    var state by remember(filterState) { mutableStateOf(filterState) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showCustomBudgetInputs by rememberSaveable { mutableStateOf(false) }
    var showCustomAreaInputs by rememberSaveable { mutableStateOf(false) }

    var minCarpetInput by remember(filterState.minCarpet) {
        mutableStateOf(if (filterState.minCarpet > 0.0) filterState.minCarpet.toLong().toString() else "")
    }
    var maxCarpetInput by remember(filterState.maxCarpet) {
        mutableStateOf(
            if (filterState.maxCarpet < Double.MAX_VALUE && filterState.maxCarpet > 0.0) {
                filterState.maxCarpet.toLong().toString()
            } else ""
        )
    }
    var minPriceInput by remember(filterState.minPrice) {
        mutableStateOf(if (filterState.minPrice > 0.0) filterState.minPrice.toLong().toString() else "")
    }
    var maxPriceInput by remember(filterState.maxPrice) {
        mutableStateOf(
            if (filterState.maxPrice < Double.MAX_VALUE && filterState.maxPrice > 0.0) {
                filterState.maxPrice.toLong().toString()
            } else ""
        )
    }

    val leaseBudgetPresets = remember {
        listOf(
            Triple(0.0, Double.MAX_VALUE, "Any Budget"),
            Triple(0.0, 25_000.0, "Up to ₹25k"),
            Triple(26_000.0, 50_000.0, "₹26k–₹50k"),
            Triple(50_000.0, 75_000.0, "₹50k–₹75k"),
            Triple(75_000.0, 100_000.0, "₹75k–₹1 L"),
            Triple(100_000.0, 125_000.0, "₹1–₹1.25 L"),
            Triple(126_000.0, 150_000.0, "₹1.26–₹1.5 L"),
            Triple(150_000.0, 200_000.0, "₹1.5–₹2 L"),
            Triple(200_000.0, 300_000.0, "₹2–₹3 L"),
            Triple(300_000.0, 500_000.0, "₹3–₹5 L"),
            Triple(500_000.0, 1_000_000.0, "₹5–₹10 L")
        )
    }
    val saleBudgetPresets = remember {
        listOf(
            Triple(0.0, Double.MAX_VALUE, "Any Budget"),
            Triple(0.0, 5_000_000.0, "Up to ₹50 L"),
            Triple(5_000_000.0, 7_500_000.0, "₹50–₹75 L"),
            Triple(7_500_000.0, 10_000_000.0, "₹75 L–₹1 Cr"),
            Triple(10_000_000.0, 12_500_000.0, "₹1–₹1.25 Cr"),
            Triple(12_600_000.0, 15_000_000.0, "₹1.26–₹1.5 Cr"),
            Triple(15_100_000.0, 20_000_000.0, "₹1.51–₹2 Cr"),
            Triple(20_100_000.0, 25_000_000.0, "₹2.01–₹2.5 Cr"),
            Triple(25_100_000.0, 35_000_000.0, "₹2.51–₹3.5 Cr"),
            Triple(35_100_000.0, 50_000_000.0, "₹3.51–₹5 Cr"),
            Triple(50_000_000.0, 75_000_000.0, "₹5–₹7.5 Cr"),
            Triple(75_100_000.0, 100_000_000.0, "₹7.51–₹10 Cr"),
            Triple(100_000_000.0, Double.MAX_VALUE, "₹10 Cr+")
        )
    }
    val budgetPresets = remember(state.transactionType) {
        when (state.transactionType) {
            "Lease" -> leaseBudgetPresets
            "Sale", "Pre-Leased" -> saleBudgetPresets
            else -> leaseBudgetPresets + saleBudgetPresets.drop(1)
        }
    }
    val budgetOptions = remember(budgetPresets) {
        budgetPresets.map { it.third } + "Custom"
    }

    val areaPresets = remember {
        listOf(
            Triple(0.0, Double.MAX_VALUE, "Any Area"),
            Triple(0.0, 300.0, "Up to 300"),
            Triple(301.0, 500.0, "301–500"),
            Triple(501.0, 750.0, "501–750"),
            Triple(751.0, 1100.0, "751–1,100"),
            Triple(1101.0, 1500.0, "1,101–1,500"),
            Triple(1501.0, 2000.0, "1,501–2,000"),
            Triple(2001.0, 3000.0, "2,001–3,000"),
            Triple(3001.0, 5000.0, "3,001–5,000"),
            Triple(5001.0, 7500.0, "5,001–7,500"),
            Triple(7501.0, 10000.0, "7,501–10,000"),
            Triple(10000.0, Double.MAX_VALUE, "10,000+ sq.ft")
        )
    }
    val areaOptions = remember(areaPresets) {
        areaPresets.map { it.third } + "Custom"
    }

    val categoryOptions = listOf("All Categories", "Commercial", "Residential")
    val categoryDisplay = when (state.brand) {
        Brand.COMMERCIAL -> "Commercial"
        Brand.RESIDENTIAL -> "Residential"
        null -> "All Categories"
    }

    val transactionDisplayMap = remember {
        listOf(
            "All" to "All",
            "Lease" to "Lease / Rent",
            "Sale" to "Outright Sale",
            "Pre-Leased" to "Pre-Leased ROI"
        )
    }
    val availableTxPairs = remember(state.brand) {
        if (state.brand == Brand.RESIDENTIAL) {
            transactionDisplayMap.filter { it.first != "Pre-Leased" }
        } else {
            transactionDisplayMap
        }
    }
    val transactionDisplay = availableTxPairs.firstOrNull { it.first == state.transactionType }?.second ?: "All"

    val availablePropertyTypes = remember(state.brand) {
        val base = when (state.brand) {
            Brand.COMMERCIAL -> TlcStandardOptions.commercialPropertyTypes
            Brand.RESIDENTIAL -> TlcStandardOptions.residentialPropertyTypes
            null -> (TlcStandardOptions.commercialPropertyTypes + TlcStandardOptions.residentialPropertyTypes).distinct()
        }
        listOf("All Types") + base
    }
    val propertyTypeDisplay = state.propertyTypeFilter ?: "All Types"

    val furnishingOptions = remember {
        listOf("Any Furnishing") + FurnishingType.values().map { it.label }
    }
    val furnishingDisplay = state.furnishing?.label ?: "Any Furnishing"

    val isLeaseTx = state.transactionType == "Lease"
    val budgetDisplay = remember(state.minPrice, state.maxPrice, budgetPresets, showCustomBudgetInputs, isLeaseTx) {
        if (showCustomBudgetInputs && state.minPrice == 0.0 && state.maxPrice == Double.MAX_VALUE) {
            "Custom"
        } else {
            budgetPresets.firstOrNull { it.first == state.minPrice && it.second == state.maxPrice }?.third
                ?: buildString {
                    append(if (state.minPrice > 0.0) CalculationUtils.formatIndianCurrency(state.minPrice, isMonthly = false) else "₹0")
                    append("–")
                    append(if (state.maxPrice < Double.MAX_VALUE) CalculationUtils.formatIndianCurrency(state.maxPrice, isMonthly = isLeaseTx) else "Any")
                }
        }
    }

    val areaDisplay = remember(state.minCarpet, state.maxCarpet, areaPresets, showCustomAreaInputs) {
        if (showCustomAreaInputs && state.minCarpet == 0.0 && state.maxCarpet == Double.MAX_VALUE) {
            "Custom"
        } else {
            areaPresets.firstOrNull { it.first == state.minCarpet && it.second == state.maxCarpet }?.third
                ?: buildString {
                    append(if (state.minCarpet > 0.0) CalculationUtils.formatNumber(state.minCarpet) else "0")
                    append("–")
                    append(if (state.maxCarpet < Double.MAX_VALUE) "${CalculationUtils.formatNumber(state.maxCarpet)} sq.ft" else "Any")
                }
        }
    }

    val filterContext = LocalContext.current
    val locationOptions = remember { listOf("All Locations") + TlcStandardOptions.puneLocations }
    val projectOptions = remember(filterContext) {
        val dirProjects = runCatching { ProjectDirectoryStore.read(filterContext) }.getOrDefault(emptyList())
            .map { proj ->
                if (proj.id.startsWith("CP-", true) || proj.id.startsWith("RP-", true)) {
                    "${proj.id} • ${proj.name}"
                } else {
                    proj.name
                }
            }
        val invProjects = runCatching { PropertiesLocalDatabase.loadProperties(filterContext) }.getOrDefault(emptyList())
            .mapNotNull { prop ->
                val name = prop.projectName.trim()
                if (name.isBlank()) null
                else if (prop.projectId.startsWith("CP-", true) || prop.projectId.startsWith("RP-", true)) {
                    "${prop.projectId} • $name"
                } else {
                    name
                }
            }
        val fallbackKnown = listOf(
            "CP-0001 • Panchshil Business Park",
            "CP-0002 • World Trade Center Kharadi",
            "CP-0003 • Gera Imperium Alpha",
            "CP-0004 • Phoenix Marketcity High Street",
            "RP-0001 • Kasturi Apostrophe",
            "RP-0002 • Kumar Primavera",
            "RP-0003 • Godrej Infinity"
        )
        val combined = (dirProjects + invProjects + fallbackKnown + TlcStandardOptions.kharadiMicroMarkets)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.substringAfter("•").trim().lowercase() }
        listOf("All Projects") + combined
    }
    val statusOptions = remember { listOf("All Statuses") + AvailabilityStatus.values().map { it.label } }
    val possessionOptions = remember { listOf("Any Possession") + TlcStandardOptions.possessionTimelines }
    val parkingOptions = remember { listOf("Any Parking") + TlcStandardOptions.parkingOptions }
    val tolerancePairs = remember {
        listOf(
            MatchTolerance.EXACT to "Exact",
            MatchTolerance.TOL_15 to "±15%",
            MatchTolerance.TOL_20 to "±20%",
            MatchTolerance.TOL_25 to "±25%",
            MatchTolerance.TOL_50 to "±50%"
        )
    }
    val toleranceDisplay = tolerancePairs.firstOrNull { it.first == state.tolerance }?.second ?: "Exact"
    val areaBasisOptions = remember { AreaRateBasis.entries.map { it.label } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft,
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(BorderGrey)
                )
            }
        },
        modifier = Modifier.testTag("filter_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
        ) {
            // 1. Zero-Gap Tight 1-Line Header ("Filters" | "Reset" + "✕")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BackgroundSoft)
                    .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Filters",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            state = FilterState()
                            showCustomBudgetInputs = false
                            showCustomAreaInputs = false
                            minCarpetInput = ""
                            maxCarpetInput = ""
                            minPriceInput = ""
                            maxPriceInput = ""
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("filter_reset_btn")
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = PrimaryActionOrange
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset", color = PrimaryActionOrange, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("filter_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Filters",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = BorderGrey, thickness = 1.dp)

            // 2. Scrollable Middle Container (4 Compact 2-Column Standardized Dropdown Rows)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Row 1: Category | Transaction
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactDropdownField(
                        label = "Category",
                        selectedValue = categoryDisplay,
                        options = categoryOptions,
                        onValueSelected = { chosen ->
                            state = when (chosen) {
                                "Commercial" -> {
                                    val cleanedType = state.propertyTypeFilter?.takeUnless { it in RESIDENTIAL_ONLY_TYPES }
                                    state.copy(
                                        brand = Brand.COMMERCIAL,
                                        propertyTypeFilter = cleanedType,
                                        bhkFilter = null
                                    )
                                }
                                "Residential" -> {
                                    val cleanedType = state.propertyTypeFilter?.takeUnless { it in COMMERCIAL_ONLY_TYPES }
                                    val cleanedTx = if (state.transactionType == "Pre-Leased") "All" else state.transactionType
                                    state.copy(
                                        brand = Brand.RESIDENTIAL,
                                        propertyTypeFilter = cleanedType,
                                        transactionType = cleanedTx,
                                        minWorkstations = 0,
                                        minCabins = 0,
                                        minFrontageFeet = 0.0,
                                        onlyMainRoad = false
                                    )
                                }
                                else -> state.copy(brand = null)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter_category_dropdown"),
                        allowCustom = false
                    )

                    CompactDropdownField(
                        label = "Transaction",
                        selectedValue = transactionDisplay,
                        options = availableTxPairs.map { it.second },
                        onValueSelected = { chosenDisplay ->
                            val rawTx = availableTxPairs.firstOrNull { it.second == chosenDisplay }?.first ?: "All"
                            state = state.copy(transactionType = rawTx)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter_transaction_dropdown"),
                        allowCustom = false
                    )
                }

                // Row 2: Property Type | Furnishing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactDropdownField(
                        label = "Property Type",
                        selectedValue = propertyTypeDisplay,
                        options = availablePropertyTypes,
                        onValueSelected = { chosen ->
                            state = state.copy(propertyTypeFilter = if (chosen == "All Types") null else chosen)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter_property_type_dropdown"),
                        allowCustom = false
                    )

                    CompactDropdownField(
                        label = "Furnishing",
                        selectedValue = furnishingDisplay,
                        options = furnishingOptions,
                        onValueSelected = { chosen ->
                            val matched = if (chosen == "Any Furnishing") null
                            else FurnishingType.values().firstOrNull { it.label.equals(chosen, ignoreCase = true) }
                            state = state.copy(furnishing = matched)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter_furnishing_dropdown"),
                        allowCustom = false
                    )
                }

                // Row 3: Budget Range | Carpet Area
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactDropdownField(
                        label = "Budget Range",
                        selectedValue = budgetDisplay,
                        options = budgetOptions,
                        onValueSelected = { label ->
                            if (label == "Custom") {
                                showCustomBudgetInputs = true
                            } else {
                                showCustomBudgetInputs = false
                                val match = budgetPresets.firstOrNull { it.third == label }
                                if (match != null) {
                                    state = state.copy(minPrice = match.first, maxPrice = match.second)
                                    minPriceInput = if (match.first > 0.0) match.first.toLong().toString() else ""
                                    maxPriceInput = if (match.second < Double.MAX_VALUE) match.second.toLong().toString() else ""
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter_budget_preset_dropdown"),
                        allowCustom = false
                    )

                    CompactDropdownField(
                        label = "Carpet Area (sq.ft)",
                        selectedValue = areaDisplay,
                        options = areaOptions,
                        onValueSelected = { label ->
                            if (label == "Custom") {
                                showCustomAreaInputs = true
                            } else {
                                showCustomAreaInputs = false
                                val match = areaPresets.firstOrNull { it.third == label }
                                if (match != null) {
                                    state = state.copy(minCarpet = match.first, maxCarpet = match.second)
                                    minCarpetInput = if (match.first > 0.0) match.first.toLong().toString() else ""
                                    maxCarpetInput = if (match.second < Double.MAX_VALUE) match.second.toLong().toString() else ""
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter_area_preset_dropdown"),
                        allowCustom = false
                    )
                }

                if (showCustomBudgetInputs) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = minPriceInput,
                            onValueChange = { raw ->
                                minPriceInput = raw
                                val parsed = CalculationUtils.parseCurrencyInput(raw) ?: 0.0
                                state = state.copy(minPrice = parsed)
                            },
                            label = { Text("Min Budget (50k/1Cr)", fontSize = 10.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("filter_min_price_input")
                        )
                        OutlinedTextField(
                            value = maxPriceInput,
                            onValueChange = { raw ->
                                maxPriceInput = raw
                                val parsed = CalculationUtils.parseCurrencyInput(raw) ?: Double.MAX_VALUE
                                state = state.copy(maxPrice = parsed)
                            },
                            label = { Text("Max Budget (5L/3Cr)", fontSize = 10.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("filter_max_price_input")
                        )
                    }
                }

                if (showCustomAreaInputs) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = minCarpetInput,
                            onValueChange = { raw ->
                                val cleaned = raw.filter { it.isDigit() || it == '.' }
                                minCarpetInput = cleaned
                                state = state.copy(minCarpet = cleaned.toDoubleOrNull() ?: 0.0)
                            },
                            label = { Text("Min ${state.areaBasis.label} (sq.ft)", fontSize = 10.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("filter_min_carpet_input")
                        )
                        OutlinedTextField(
                            value = maxCarpetInput,
                            onValueChange = { raw ->
                                val cleaned = raw.filter { it.isDigit() || it == '.' }
                                maxCarpetInput = cleaned
                                state = state.copy(maxCarpet = cleaned.toDoubleOrNull() ?: Double.MAX_VALUE)
                            },
                            label = { Text("Max ${state.areaBasis.label} (sq.ft)", fontSize = 10.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("filter_max_carpet_input")
                        )
                    }
                }

                // Row 4: Location | Tolerance
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchableDropdownField(
                        label = "Location",
                        selectedValue = state.locationFilter,
                        options = locationOptions,
                        onValueSelected = { chosen ->
                            val clean = if (chosen == "All Locations") "" else chosen
                            state = state.copy(locationFilter = clean)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter_location_input"),
                        allowAddNew = true
                    )

                    CompactDropdownField(
                        label = "Tolerance",
                        selectedValue = toleranceDisplay,
                        options = tolerancePairs.map { it.second },
                        onValueSelected = { lbl ->
                            val tol = tolerancePairs.firstOrNull { it.second == lbl }?.first ?: MatchTolerance.EXACT
                            state = state.copy(tolerance = tol)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter_tolerance_dropdown"),
                        allowCustom = false
                    )
                }

                // ── EXPANDABLE ADVANCED FILTERS (Status, Parking, Possession) ──
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (showAdvanced) PrimaryActionOrange else BorderGrey),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { showAdvanced = !showAdvanced }
                        .testTag("toggle_advanced_filters_btn")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Advanced Filters (Status, Parking, Possession)",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (showAdvanced) "Collapse Advanced Filters" else "Expand Advanced Filters",
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (showAdvanced) {
                    val isOfficeFocus = state.brand == Brand.COMMERCIAL &&
                        (state.propertyTypeFilter == null || state.propertyTypeFilter?.equals("Office", ignoreCase = true) == true || state.propertyTypeFilter?.contains("Office", ignoreCase = true) == true)
                    val isRetailFocus = state.brand == Brand.COMMERCIAL &&
                        (state.propertyTypeFilter?.contains("Shop", ignoreCase = true) == true ||
                            state.propertyTypeFilter?.contains("Showroom", ignoreCase = true) == true ||
                            state.propertyTypeFilter?.contains("Retail", ignoreCase = true) == true)
                    val isResidentialFocus = state.brand == Brand.RESIDENTIAL ||
                        state.propertyTypeFilter in RESIDENTIAL_ONLY_TYPES

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CompactDropdownField(
                            label = "Status",
                            selectedValue = state.status?.label ?: "All Statuses",
                            options = statusOptions,
                            onValueSelected = { lbl ->
                                val st = if (lbl == "All Statuses") null
                                else AvailabilityStatus.values().firstOrNull { it.label.equals(lbl, ignoreCase = true) }
                                state = state.copy(status = st)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("filter_availability_dropdown"),
                            allowCustom = false
                        )

                        CompactDropdownField(
                            label = "Possession",
                            selectedValue = state.possessionFilter ?: "Any Possession",
                            options = possessionOptions,
                            onValueSelected = { lbl ->
                                state = state.copy(possessionFilter = if (lbl == "Any Possession") null else lbl)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("filter_possession_dropdown"),
                            allowCustom = false
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CompactDropdownField(
                            label = "Parking",
                            selectedValue = state.parkingFilter ?: "Any Parking",
                            options = parkingOptions,
                            onValueSelected = { lbl ->
                                state = state.copy(parkingFilter = if (lbl == "Any Parking") null else lbl)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("filter_parking_dropdown"),
                            allowCustom = false
                        )

                        SearchableDropdownField(
                            label = "Project / Building",
                            selectedValue = state.projectFilter,
                            options = projectOptions,
                            onValueSelected = { chosen ->
                                val clean = if (chosen == "All Projects") "" else chosen.substringAfter("•").trim()
                                state = state.copy(projectFilter = clean)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("filter_project_input"),
                            allowAddNew = true
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CompactDropdownField(
                            label = "Area Basis",
                            selectedValue = state.areaBasis.label,
                            options = areaBasisOptions,
                            onValueSelected = { lbl ->
                                val basis = AreaRateBasis.entries.firstOrNull { it.label == lbl } ?: AreaRateBasis.CARPET
                                state = state.copy(areaBasis = basis)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("filter_area_basis_dropdown"),
                            allowCustom = false
                        )

                        CompactDropdownField(
                            label = "Listing Verification",
                            selectedValue = if (state.verifiedOnly) "Verified Only" else "All Listings",
                            options = listOf("All Listings", "Verified Only"),
                            onValueSelected = { lbl ->
                                state = state.copy(verifiedOnly = lbl == "Verified Only")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("filter_verified_dropdown"),
                            allowCustom = false
                        )
                    }

                    if (isResidentialFocus) {
                        CompactDropdownField(
                            label = "Configuration (BHK)",
                            selectedValue = state.bhkFilter ?: "Any BHK",
                            options = listOf("Any BHK") + TlcStandardOptions.bhkConfigs,
                            onValueSelected = { chosen ->
                                state = state.copy(bhkFilter = if (chosen == "Any BHK") null else chosen)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("filter_bhk_dropdown"),
                            allowCustom = false
                        )
                    }

                    if (isOfficeFocus) {
                        val seatPairs = listOf(0 to "Any Seats", 10 to "10+ Seats", 25 to "25+ Seats", 50 to "50+ Seats", 100 to "100+ Seats")
                        val cabinPairs = listOf(0 to "Any Cabins", 1 to "1+ Cabin", 2 to "2+ Cabins", 4 to "4+ Cabins")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Workstations",
                                selectedValue = seatPairs.firstOrNull { it.first == state.minWorkstations }?.second ?: "Any Seats",
                                options = seatPairs.map { it.second },
                                onValueSelected = { lbl ->
                                    val seats = seatPairs.firstOrNull { it.second == lbl }?.first ?: 0
                                    state = state.copy(minWorkstations = seats)
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                            CompactDropdownField(
                                label = "Cabins",
                                selectedValue = cabinPairs.firstOrNull { it.first == state.minCabins }?.second ?: "Any Cabins",
                                options = cabinPairs.map { it.second },
                                onValueSelected = { lbl ->
                                    val cabins = cabinPairs.firstOrNull { it.second == lbl }?.first ?: 0
                                    state = state.copy(minCabins = cabins)
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                        }
                    }

                    if (isRetailFocus) {
                        val frontagePairs = listOf(0.0 to "Any Frontage", 15.0 to "15+ ft Frontage", 25.0 to "25+ ft Frontage", 40.0 to "40+ ft Frontage")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Road Exposure",
                                selectedValue = if (state.onlyMainRoad) "Main Road Facing Only" else "Any Road",
                                options = listOf("Any Road", "Main Road Facing Only"),
                                onValueSelected = { lbl ->
                                    state = state.copy(onlyMainRoad = lbl == "Main Road Facing Only")
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                            CompactDropdownField(
                                label = "Min Frontage",
                                selectedValue = frontagePairs.firstOrNull { it.first == state.minFrontageFeet }?.second ?: "Any Frontage",
                                options = frontagePairs.map { it.second },
                                onValueSelected = { lbl ->
                                    val ft = frontagePairs.firstOrNull { it.second == lbl }?.first ?: 0.0
                                    state = state.copy(minFrontageFeet = ft)
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                        }
                    }

                    // Verified Listings Quick Switch Card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceWhite)
                            .border(
                                1.dp,
                                if (state.verifiedOnly) PrimaryActionOrange else BorderGrey,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { state = state.copy(verifiedOnly = !state.verifiedOnly) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Verified,
                                contentDescription = "Verified Filter",
                                tint = if (state.verifiedOnly) PrimaryActionOrange else SecondaryText,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Verified Listings Only",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Show only verified properties ($toleranceDisplay match)",
                                    color = SecondaryText,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Switch(
                            checked = state.verifiedOnly,
                            onCheckedChange = { state = state.copy(verifiedOnly = it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnPrimaryActionColor,
                                checkedTrackColor = PrimaryActionOrange
                            )
                        )
                    }
                }
            }

            HorizontalDivider(color = BorderGrey, thickness = 1.dp)

            // 3. Permanently Pinned Fixed Bottom Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BackgroundSoft)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        val resetState = FilterState()
                        state = resetState
                        showCustomBudgetInputs = false
                        showCustomAreaInputs = false
                        minCarpetInput = ""
                        maxCarpetInput = ""
                        minPriceInput = ""
                        maxPriceInput = ""
                        onFilterChange(resetState)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("filter_bottom_clear_btn"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderGrey)
                ) {
                    Text(
                        "Clear All",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Button(
                    onClick = {
                        onFilterChange(state)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1.6f)
                        .heightIn(min = 48.dp)
                        .testTag("filter_apply_btn"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text(
                        text = if (matchingCountPreview != null) {
                            "Show Results ($matchingCountPreview)"
                        } else {
                            "Show Results"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
fun FilterSectionTitle(title: String) {
    Text(
        text = title,
        color = TextPrimary,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
fun FilterChipItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) PrimaryActionOrange else SurfaceWhite,
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) PrimaryActionOrange else BorderGrey
        ),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = OnPrimaryActionColor,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                color = if (isSelected) OnPrimaryActionColor else TextPrimary,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

