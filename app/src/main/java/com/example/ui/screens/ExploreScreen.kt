package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.example.data.pune.PuneIntelligenceRepository
import com.example.inventory.InventoryQuality
import com.example.inventory.PropertyPrecomputedCache
import com.example.model.*
import com.example.ui.components.*
import com.example.ui.shortlist.ShortlistPinnedBottomBar
import com.example.ui.theme.*

private data class ExploreComputedState(
    val verifiedCount: Int = 0,
    val pendingCount: Int = 0,
    val archiveCount: Int = 0,
    val activeTotalCount: Int = 0,
    val unclassifiedCount: Int = 0,
    val filteredProperties: List<PropertyItem> = emptyList(),
    val approximateMatchPropertyIds: Set<String> = emptySet(),
    val linkedUnitCounts: Map<String, Int> = emptyMap()
)

enum class VerificationFilterTab(val label: String) {
    ALL("All Active"),
    VERIFIED("Verified"),
    PENDING("Pending"),
    ARCHIVE("Archive")
}

sealed interface CategoryFilterOption {
    data object AllCommercial : CategoryFilterOption
    data object AllResidential : CategoryFilterOption
    data class TabCategory(val tab: InventoryTab) : CategoryFilterOption
    data class TypeCategory(val typeName: String, val label: String) : CategoryFilterOption
}

fun getCategoryDisplayName(tab: InventoryTab): String = when (tab) {
    InventoryTab.OFF_LEASE -> "Office Lease"
    InventoryTab.OFF_OUT -> "Office Sale"
    InventoryTab.SHOP_LEASE -> "Shop Lease"
    InventoryTab.SHOP_OUT -> "Shop Sale"
    InventoryTab.PRE_LEASED -> "Pre-Leased Commercial"
    InventoryTab.RESI_RENT -> "Residential Rent"
    InventoryTab.RESI_RESALE -> "Residential Resale"
    InventoryTab.UND_CONST -> "Under Construction"
}

fun getCategoriesForBrand(brand: Brand?): List<CategoryFilterOption> {
    return when (brand) {
        Brand.COMMERCIAL -> listOf(
            CategoryFilterOption.AllCommercial,
            CategoryFilterOption.TabCategory(InventoryTab.OFF_LEASE),
            CategoryFilterOption.TabCategory(InventoryTab.OFF_OUT),
            CategoryFilterOption.TabCategory(InventoryTab.SHOP_LEASE),
            CategoryFilterOption.TabCategory(InventoryTab.SHOP_OUT),
            CategoryFilterOption.TypeCategory("showroom", "Showroom"),
            CategoryFilterOption.TabCategory(InventoryTab.PRE_LEASED),
            CategoryFilterOption.TypeCategory("warehouse", "Warehouse"),
            CategoryFilterOption.TypeCategory("commercial land", "Commercial Land")
        )
        Brand.RESIDENTIAL -> listOf(
            CategoryFilterOption.AllResidential,
            CategoryFilterOption.TabCategory(InventoryTab.RESI_RENT),
            CategoryFilterOption.TabCategory(InventoryTab.RESI_RESALE),
            CategoryFilterOption.TabCategory(InventoryTab.UND_CONST),
            CategoryFilterOption.TypeCategory("apartment", "Ready-to-Move"),
            CategoryFilterOption.TypeCategory("villa", "Villas / Bungalows"),
            CategoryFilterOption.TypeCategory("plot", "Residential Plots")
        )
        null -> listOf(
            CategoryFilterOption.AllCommercial,
            CategoryFilterOption.AllResidential
        )
    }
}

fun categoryMatches(property: PropertyItem, option: CategoryFilterOption): Boolean {
    val type = property.propertyType.lowercase()
    return when (option) {
        is CategoryFilterOption.AllCommercial -> property.isCommercial()
        is CategoryFilterOption.AllResidential -> property.isResidential()
        is CategoryFilterOption.TabCategory -> property.tab == option.tab
        is CategoryFilterOption.TypeCategory -> {
            when (option.typeName) {
                "showroom" -> type.contains("showroom") || property.tab == InventoryTab.SHOP_LEASE
                "warehouse" -> type.contains("warehouse") || type.contains("godown")
                "commercial land" -> type.contains("commercial land") || type.contains("land")
                "apartment" -> type.contains("apartment") || type.contains("flat") || type.contains("ready")
                "villa" -> type.contains("villa") || type.contains("bungalow")
                "plot" -> type.contains("plot") || type.contains("residential plot")
                else -> type.contains(option.typeName)
            }
        }
    }
}

private val CategoryFilterOptionSaver: Saver<CategoryFilterOption?, Any> = listSaver(
    save = { option ->
        when (option) {
            null -> emptyList()
            CategoryFilterOption.AllCommercial -> listOf("ALL_COM")
            CategoryFilterOption.AllResidential -> listOf("ALL_RES")
            is CategoryFilterOption.TabCategory -> listOf("TAB", option.tab.name)
            is CategoryFilterOption.TypeCategory -> listOf("TYPE", option.typeName, option.label)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull() as? String) {
            "ALL_COM" -> CategoryFilterOption.AllCommercial
            "ALL_RES" -> CategoryFilterOption.AllResidential
            "TAB" -> (saved.getOrNull(1) as? String)?.let { name ->
                InventoryTab.entries.firstOrNull { it.name == name }?.let { CategoryFilterOption.TabCategory(it) }
            }
            "TYPE" -> {
                val typeName = saved.getOrNull(1) as? String
                val label = saved.getOrNull(2) as? String
                if (typeName != null && label != null) CategoryFilterOption.TypeCategory(typeName, label) else null
            }
            else -> null
        }
    }
)

private val FilterStateSaver: Saver<FilterState, Any> = listSaver(
    save = { state ->
        listOf(
            state.brand?.name ?: "",
            state.tab?.name ?: "",
            state.status?.name ?: "",
            state.furnishing?.name ?: "",
            state.minCarpet,
            state.maxCarpet,
            state.minPrice,
            state.maxPrice,
            state.verifiedOnly,
            state.transactionType,
            state.bhkFilter ?: "",
            state.onlyMainRoad,
            state.propertyTypeFilter ?: "",
            state.locationFilter,
            state.projectFilter,
            state.possessionFilter ?: "",
            state.parkingFilter ?: "",
            state.tolerance.name,
            state.areaBasis.name,
            state.minWorkstations,
            state.minCabins,
            state.minFrontageFeet,
            state.respectHardBudgetCeiling
        )
    },
    restore = { list ->
        FilterState(
            brand = (list.getOrNull(0) as? String)?.takeIf { it.isNotBlank() }?.let { name ->
                Brand.entries.firstOrNull { it.name == name }
            },
            tab = (list.getOrNull(1) as? String)?.takeIf { it.isNotBlank() }?.let { name ->
                InventoryTab.entries.firstOrNull { it.name == name }
            },
            status = (list.getOrNull(2) as? String)?.takeIf { it.isNotBlank() }?.let { name ->
                AvailabilityStatus.entries.firstOrNull { it.name == name }
            },
            furnishing = (list.getOrNull(3) as? String)?.takeIf { it.isNotBlank() }?.let { name ->
                FurnishingType.entries.firstOrNull { it.name == name }
            },
            minCarpet = (list.getOrNull(4) as? Double) ?: 0.0,
            maxCarpet = (list.getOrNull(5) as? Double) ?: Double.MAX_VALUE,
            minPrice = (list.getOrNull(6) as? Double) ?: 0.0,
            maxPrice = (list.getOrNull(7) as? Double) ?: Double.MAX_VALUE,
            verifiedOnly = (list.getOrNull(8) as? Boolean) ?: false,
            transactionType = (list.getOrNull(9) as? String) ?: "All",
            bhkFilter = (list.getOrNull(10) as? String)?.takeIf { it.isNotBlank() },
            onlyMainRoad = (list.getOrNull(11) as? Boolean) ?: false,
            propertyTypeFilter = (list.getOrNull(12) as? String)?.takeIf { it.isNotBlank() },
            locationFilter = (list.getOrNull(13) as? String) ?: "",
            projectFilter = (list.getOrNull(14) as? String) ?: "",
            possessionFilter = (list.getOrNull(15) as? String)?.takeIf { it.isNotBlank() },
            parkingFilter = (list.getOrNull(16) as? String)?.takeIf { it.isNotBlank() },
            tolerance = (list.getOrNull(17) as? String)?.let { name ->
                com.example.inventory.MatchTolerance.entries.firstOrNull { it.name == name }
            } ?: com.example.inventory.MatchTolerance.EXACT,
            areaBasis = (list.getOrNull(18) as? String)?.let { name ->
                AreaRateBasis.entries.firstOrNull { it.name == name }
            } ?: AreaRateBasis.CARPET,
            minWorkstations = (list.getOrNull(19) as? Int) ?: 0,
            minCabins = (list.getOrNull(20) as? Int) ?: 0,
            minFrontageFeet = (list.getOrNull(21) as? Double) ?: 0.0,
            respectHardBudgetCeiling = (list.getOrNull(22) as? Boolean) ?: false
        )
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    properties: List<PropertyItem>,
    selectedBrand: Brand?,
    onSelectBrand: (Brand?) -> Unit,
    isInternalMode: Boolean,
    favoriteIds: Set<String>,
    comparisonIds: List<String>,
    onPropertyClick: (PropertyItem) -> Unit,
    onEditProperty: (PropertyItem) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onToggleCompare: (String) -> Unit,
    onShareWhatsApp: (PropertyItem) -> Unit,
    onStatusChange: (String, AvailabilityStatus) -> Unit,
    onOpenAddProperty: () -> Unit,
    onOpenImport: () -> Unit,
    isLiveConnected: Boolean = false,
    lastSyncTimestamp: String = "",
    showUnclassifiedOnly: Boolean = false,
    onClearUnclassifiedOnly: (() -> Unit)? = null,
    onOpenComparison: (() -> Unit)? = null,
    onClearComparison: (() -> Unit)? = null,
    shortlistIds: List<String> = emptyList(),
    onToggleShortlist: ((String) -> Unit)? = null,
    onReviewShortlist: (() -> Unit)? = null,
    onClearShortlist: (() -> Unit)? = null,
    preferredGridView: Boolean? = null,
    onInventoryViewChange: ((Boolean) -> Unit)? = null,
    initialSearchQuery: String = "",
    onConsumeInitialSearchQuery: (() -> Unit)? = null,
    onVerifyProperty: ((String, AvailabilityStatus, String, String) -> Unit)? = null,
    onToggleVerifyLive: ((PropertyItem, Boolean) -> Unit)? = null,
    onInlineUpdateProperty: ((PropertyItem, Boolean) -> Unit)? = null,
    onUploadPhoto: (suspend (String, String, String, ByteArray) -> Result<String>)? = null
) {
    var upcomingOnly by rememberSaveable { mutableStateOf(initialSearchQuery == "RENEWAL_RADAR_90D") }
    var searchQuery by rememberSaveable {
        mutableStateOf(if (initialSearchQuery == "RENEWAL_RADAR_90D") "" else initialSearchQuery)
    }
    LaunchedEffect(initialSearchQuery) {
        if (initialSearchQuery == "RENEWAL_RADAR_90D") {
            upcomingOnly = true
            searchQuery = ""
            onConsumeInitialSearchQuery?.invoke()
        } else if (initialSearchQuery.isNotBlank()) {
            searchQuery = initialSearchQuery
            onConsumeInitialSearchQuery?.invoke()
        }
    }
    var selectedVerificationTab by rememberSaveable { mutableStateOf(VerificationFilterTab.ALL) }
    var selectedCategoryOption by rememberSaveable(stateSaver = CategoryFilterOptionSaver) {
        mutableStateOf<CategoryFilterOption?>(null)
    }
    var showFilterSheet by remember { mutableStateOf(false) }
    var filterState by rememberSaveable(stateSaver = FilterStateSaver) { mutableStateOf(FilterState()) }
    var sortBy by rememberSaveable { mutableStateOf("Default") }
    var isGridView by rememberSaveable { mutableStateOf(preferredGridView ?: false) }
    LaunchedEffect(preferredGridView) {
        if (preferredGridView != null && preferredGridView != isGridView) {
            isGridView = preferredGridView
        }
    }
    var instantQuickFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var isSelectMode by rememberSaveable { mutableStateOf(false) }
    var selectedMultiPropertyIds by rememberSaveable { mutableStateOf(setOf<String>()) }

    // Scroll-aware collapsing header state
    var isHeaderExpanded by remember { mutableStateOf(true) }

    // NestedScrollConnection to detect downward scroll (collapse) and upward scroll (expand)
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val deltaY = available.y
                if (deltaY < -12f && isHeaderExpanded) {
                    isHeaderExpanded = false
                } else if (deltaY > 12f && !isHeaderExpanded) {
                    isHeaderExpanded = true
                }
                return Offset.Zero
            }
        }
    }

    // Active filter badge count
    val activeFilterCount = remember(
        filterState,
        selectedBrand,
        selectedCategoryOption,
        selectedVerificationTab,
        showUnclassifiedOnly,
        upcomingOnly,
        instantQuickFilter
    ) {
        var count = 0
        if (filterState.brand != null || selectedBrand != null) count++
        if (filterState.tab != null) count++
        if (filterState.status != null) count++
        if (filterState.furnishing != null) count++
        if (filterState.minCarpet > 0.0 || filterState.maxCarpet < Double.MAX_VALUE) count++
        if (filterState.minPrice > 0.0 || filterState.maxPrice < Double.MAX_VALUE) count++
        if (filterState.verifiedOnly) count++
        if (filterState.transactionType != "All") count++
        if (!filterState.propertyTypeFilter.isNullOrBlank()) count++
        if (!filterState.bhkFilter.isNullOrBlank()) count++
        if (filterState.onlyMainRoad) count++
        if (filterState.minWorkstations > 0) count++
        if (filterState.minCabins > 0) count++
        if (filterState.minFrontageFeet > 0.0) count++
        if (filterState.tolerance != com.example.inventory.MatchTolerance.EXACT) count++
        if (filterState.locationFilter.isNotBlank()) count++
        if (filterState.projectFilter.isNotBlank()) count++
        if (!filterState.possessionFilter.isNullOrBlank()) count++
        if (!filterState.parkingFilter.isNullOrBlank()) count++
        if (selectedCategoryOption != null) count++
        if (selectedVerificationTab != VerificationFilterTab.ALL) count++
        if (showUnclassifiedOnly) count++
        if (upcomingOnly) count++
        if (!instantQuickFilter.isNullOrBlank()) count++
        count
    }

    val context = LocalContext.current
    val puneRepo = remember { PuneIntelligenceRepository.getInstance(context) }
    var debouncedSearchQuery by rememberSaveable { mutableStateOf(searchQuery) }
    var resolvedSearchTokens by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedSearchQuery = ""
            resolvedSearchTokens = emptyList()
        } else {
            delay(250)
            val q = searchQuery.trim().lowercase()
            val res = withContext(Dispatchers.Default) { puneRepo.resolveSearchQuery(q) }
            debouncedSearchQuery = searchQuery
            resolvedSearchTokens = (listOf(q) + res.searchTerms).distinct()
        }
    }

    val effectiveBrandFilter = filterState.brand ?: selectedBrand
    var computedState by remember { mutableStateOf(ExploreComputedState()) }

    LaunchedEffect(
        properties,
        effectiveBrandFilter,
        showUnclassifiedOnly,
        selectedVerificationTab,
        selectedCategoryOption,
        debouncedSearchQuery,
        resolvedSearchTokens,
        filterState,
        upcomingOnly,
        sortBy,
        instantQuickFilter
    ) {
        val next = withContext(Dispatchers.Default) {
            val directUnits = ArrayList<PropertyItem>(properties.size)
            val linkedCountsMap = HashMap<String, Int>(properties.size)
            for (prop in properties) {
                val pre = PropertyPrecomputedCache.get(prop)
                if (!pre.isBuilderStock) {
                    directUnits.add(prop)
                }
                val unitKey = prop.sameUnitGroupId.takeIf { it.isNotBlank() }
                    ?: prop.physicalUnitKey.takeIf { it.isNotBlank() }
                    ?: prop.id
                linkedCountsMap[unitKey] = (linkedCountsMap[unitKey] ?: 0) + 1
            }

            val divisionList = if (showUnclassifiedOnly) {
                directUnits.filter { it.isUnclassified() }
            } else {
                when (effectiveBrandFilter) {
                    Brand.COMMERCIAL -> directUnits.filter { it.isCommercial() }
                    Brand.RESIDENTIAL -> directUnits.filter { it.isResidential() }
                    null -> directUnits
                }
            }

            var activeCount = 0
            var vCount = 0
            var pCount = 0
            var aCount = 0
            for (prop in divisionList) {
                val pre = PropertyPrecomputedCache.get(prop)
                if (pre.isActiveInventory) {
                    activeCount++
                    if (prop.verificationStatus == VerificationStatus.VERIFIED) vCount++
                    else pCount++
                } else {
                    aCount++
                }
            }
            val unclassCount = directUnits.count { it.isUnclassified() }

            var list: List<PropertyItem> = divisionList

            if (!showUnclassifiedOnly) {
                list = when (selectedVerificationTab) {
                    VerificationFilterTab.ALL ->
                        list.filter { PropertyPrecomputedCache.get(it).isActiveInventory }
                    VerificationFilterTab.VERIFIED ->
                        list.filter {
                            PropertyPrecomputedCache.get(it).isActiveInventory &&
                                it.verificationStatus == VerificationStatus.VERIFIED
                        }
                    VerificationFilterTab.PENDING ->
                        list.filter {
                            PropertyPrecomputedCache.get(it).isActiveInventory &&
                                it.verificationStatus != VerificationStatus.VERIFIED
                        }
                    VerificationFilterTab.ARCHIVE ->
                        list.filter { PropertyPrecomputedCache.get(it).isClosedOrArchived }
                }

                if (selectedCategoryOption != null) {
                    list = list.filter { categoryMatches(it, selectedCategoryOption!!) }
                }
            }

            if (!instantQuickFilter.isNullOrBlank()) {
                val qf = instantQuickFilter!!.lowercase()
                list = list.filter {
                    when (qf) {
                        "ready office" -> it.tab == InventoryTab.OFF_LEASE || it.tab == InventoryTab.OFF_OUT || it.propertyType.contains("office", ignoreCase = true)
                        "pre-leased" -> it.tab == InventoryTab.PRE_LEASED || (it.roiPercent != null && it.roiPercent > 0.0)
                        "luxury 3bhk+" -> (it.bhk != null && (it.bhk.contains("3") || it.bhk.contains("4") || it.bhk.contains("5"))) || it.priceOrRent >= 20000000.0
                        else -> it.location.lowercase().contains(qf) || it.projectName.lowercase().contains(qf)
                    }
                }
            }

            if (debouncedSearchQuery.isNotBlank()) {
                val tokens = if (resolvedSearchTokens.isNotEmpty()) {
                    resolvedSearchTokens
                } else {
                    listOf(debouncedSearchQuery.trim().lowercase())
                }
                list = list.filter { prop ->
                    val blob = PropertyPrecomputedCache.get(prop).searchBlobLower
                    tokens.any { token -> blob.contains(token) }
                }
            }

            if (upcomingOnly) {
                list = list.filter { RenewalRadar90dHelper.matchesRenewalRadar90d(it) }
            }

            if (filterState.tab != null) {
                list = list.filter { it.tab == filterState.tab }
            }
            if (filterState.status != null) {
                list = list.filter { it.status == filterState.status }
            }
            if (filterState.furnishing != null) {
                list = list.filter { it.furnishing == filterState.furnishing }
            }
            if (filterState.verifiedOnly) {
                list = list.filter { it.verificationStatus == VerificationStatus.VERIFIED }
            }
            if (filterState.transactionType != "All") {
                list = list.filter { prop ->
                    val stdTx = PropertyPrecomputedCache.get(prop).stdTx
                    when (filterState.transactionType) {
                        "Sale" -> stdTx == StandardTransactionType.SALE
                        "Lease" -> stdTx == StandardTransactionType.LEASE
                        "Pre-Leased" -> stdTx == StandardTransactionType.PRE_LEASED_SALE || prop.tab == InventoryTab.PRE_LEASED
                        else -> true
                    }
                }
            }
            if (!filterState.propertyTypeFilter.isNullOrBlank()) {
                val typeQuery = filterState.propertyTypeFilter!!.lowercase()
                list = list.filter { prop ->
                    prop.propertyType.lowercase().contains(typeQuery) ||
                        StandardPropertyType.fromString(prop.propertyType).label.lowercase().contains(typeQuery) ||
                        prop.tab.title.lowercase().contains(typeQuery)
                }
            }
            if (!filterState.bhkFilter.isNullOrBlank()) {
                val bhkTarget = filterState.bhkFilter!!.lowercase()
                list = list.filter { prop ->
                    prop.configuration.lowercase().contains(bhkTarget) ||
                        prop.propertyType.lowercase().contains(bhkTarget)
                }
            }
            if (filterState.onlyMainRoad) {
                list = list.filter { it.mainRoadFacing }
            }
            if (filterState.minWorkstations > 0) {
                list = list.filter { (it.workstations ?: 0) >= filterState.minWorkstations }
            }
            if (filterState.minCabins > 0) {
                list = list.filter { (it.cabins ?: 0) >= filterState.minCabins }
            }
            if (filterState.minFrontageFeet > 0.0) {
                list = list.filter { (it.frontageFeet ?: 0.0) >= filterState.minFrontageFeet }
            }
            if (filterState.locationFilter.isNotBlank()) {
                val locQuery = filterState.locationFilter.trim().lowercase()
                list = list.filter { prop ->
                    prop.location.lowercase().contains(locQuery) ||
                        prop.fullAddress.lowercase().contains(locQuery)
                }
            }
            if (filterState.projectFilter.isNotBlank()) {
                val projQuery = filterState.projectFilter.trim().lowercase()
                list = list.filter { prop ->
                    prop.projectName.lowercase().contains(projQuery) ||
                        prop.projectId.lowercase().contains(projQuery)
                }
            }
            if (!filterState.possessionFilter.isNullOrBlank()) {
                val possQuery = filterState.possessionFilter!!.lowercase()
                list = list.filter { prop ->
                    when (possQuery) {
                        "immediate", "ready", "ready to move" -> prop.possession.contains("immediate", ignoreCase = true) ||
                            prop.possession.contains("ready", ignoreCase = true) ||
                            prop.status == AvailabilityStatus.AVAILABLE
                        "under construction" -> prop.tab == InventoryTab.UND_CONST ||
                            prop.possession.contains("under", ignoreCase = true)
                        else -> prop.possession.lowercase().contains(possQuery)
                    }
                }
            }
            if (!filterState.parkingFilter.isNullOrBlank()) {
                val parkQuery = filterState.parkingFilter!!.lowercase()
                list = list.filter { prop ->
                    val parkText = prop.parking.lowercase()
                    val parkCount = prop.parkingCount ?: parkText.filter { it.isDigit() }.toIntOrNull() ?: 0
                    when {
                        parkQuery.contains("2+") -> parkCount >= 2
                        parkQuery.contains("covered") || parkQuery.contains("reserved") ->
                            parkText.contains("cover") || parkText.contains("reserv") || parkCount >= 1
                        else -> (parkText.isNotBlank() && !parkText.equals("none", ignoreCase = true) && !parkText.equals("no", ignoreCase = true)) || parkCount >= 1
                    }
                }
            }

            // Range Matching with Approximate Match Tolerance (Area + Price)
            val hasAreaRange = filterState.minCarpet > 0.0 || filterState.maxCarpet < Double.MAX_VALUE
            val hasPriceRange = filterState.minPrice > 0.0 || filterState.maxPrice < Double.MAX_VALUE
            val approxIds = HashSet<String>()

            if (hasAreaRange || hasPriceRange) {
                val exactMatched = mutableListOf<PropertyItem>()
                val approxMatched = mutableListOf<PropertyItem>()

                for (prop in list) {
                    val areaVal = if (filterState.areaBasis == AreaRateBasis.BUILT_UP && prop.builtUpArea > 0.0) {
                        prop.builtUpArea
                    } else {
                        prop.carpetAreaSqFt
                    }
                    val areaTier = if (hasAreaRange) {
                        InventoryQuality.evaluateRangeMatch(
                            value = areaVal,
                            minTarget = filterState.minCarpet,
                            maxTarget = filterState.maxCarpet,
                            tolerance = filterState.tolerance,
                            respectHardCeiling = false
                        )
                    } else {
                        com.example.inventory.RangeMatchTier.EXACT_MATCH
                    }

                    if (areaTier == com.example.inventory.RangeMatchTier.NO_MATCH) continue

                    val effectivePrice = when {
                        prop.tab.isRental && (prop.rent ?: 0.0) > 0.0 -> prop.rent ?: 0.0
                        !prop.tab.isRental && (prop.priceSale ?: 0.0) > 0.0 -> prop.priceSale ?: 0.0
                        else -> prop.priceOrRent
                    }
                    val priceTier = if (hasPriceRange) {
                        InventoryQuality.evaluateRangeMatch(
                            value = effectivePrice,
                            minTarget = filterState.minPrice,
                            maxTarget = filterState.maxPrice,
                            tolerance = filterState.tolerance,
                            respectHardCeiling = filterState.respectHardBudgetCeiling
                        )
                    } else {
                        com.example.inventory.RangeMatchTier.EXACT_MATCH
                    }

                    if (priceTier == com.example.inventory.RangeMatchTier.NO_MATCH) continue

                    if (areaTier == com.example.inventory.RangeMatchTier.EXACT_MATCH &&
                        priceTier == com.example.inventory.RangeMatchTier.EXACT_MATCH
                    ) {
                        exactMatched.add(prop)
                    } else {
                        approxIds.add(prop.id)
                        approxMatched.add(prop)
                    }
                }

                list = exactMatched + approxMatched
            }

            val sortedList = if (upcomingOnly && sortBy == "Default") {
                list.sortedBy { RenewalRadar90dHelper.daysUntilRenewalOrLockInEnd(it) ?: 999L }
            } else {
                when (sortBy) {
                    "PriceAsc", "Price: Low to High" -> {
                        val lease = list.filter { it.isLeaseTab() }.sortedBy { PropertyPrecomputedCache.get(it).primaryAmount }
                        val sale = list.filter { !it.isLeaseTab() }.sortedBy { PropertyPrecomputedCache.get(it).primaryAmount }
                        lease + sale
                    }
                    "PriceDesc", "Price: High to Low" -> {
                        val lease = list.filter { it.isLeaseTab() }.sortedByDescending { PropertyPrecomputedCache.get(it).primaryAmount }
                        val sale = list.filter { !it.isLeaseTab() }.sortedByDescending { PropertyPrecomputedCache.get(it).primaryAmount }
                        sale + lease
                    }
                    "AreaDesc", "Area: Large to Small" -> list.sortedByDescending { it.carpetAreaSqFt }
                    "Area: Small to Large" -> list.sortedBy { it.carpetAreaSqFt }
                    "CodeAsc" -> list.sortedBy { it.propertyCode }
                    "Recently Updated" -> list.sortedByDescending { it.lastVerificationDate }
                    "Recently Verified" -> list.sortedByDescending { it.lastVerifiedDateClean }
                    else -> list
                }
            }

            ExploreComputedState(
                verifiedCount = vCount,
                pendingCount = pCount,
                archiveCount = aCount,
                activeTotalCount = activeCount,
                unclassifiedCount = unclassCount,
                filteredProperties = sortedList,
                approximateMatchPropertyIds = approxIds,
                linkedUnitCounts = linkedCountsMap
            )
        }
        computedState = next
    }

    val verifiedCount = computedState.verifiedCount
    val pendingCount = computedState.pendingCount
    val archiveCount = computedState.archiveCount
    val activeTotalCount = computedState.activeTotalCount
    val unclassifiedCount = computedState.unclassifiedCount
    val filteredProperties = computedState.filteredProperties
    val approximateMatchPropertyIds = computedState.approximateMatchPropertyIds
    val linkedUnitCounts = computedState.linkedUnitCounts

    // Animated inventory list state for entrance and exit animations via AnimatedVisibility API
    var displayedProperties by remember { mutableStateOf(filteredProperties) }
    var visiblePropertyIds by remember { mutableStateOf(filteredProperties.map { it.id }.toSet()) }

    LaunchedEffect(filteredProperties) {
        val targetIds = withContext(Dispatchers.Default) {
            filteredProperties.mapTo(HashSet(filteredProperties.size)) { it.id }
        }
        val exitingIds = visiblePropertyIds - targetIds
        if (exitingIds.isNotEmpty() && visiblePropertyIds.isNotEmpty() && exitingIds.size <= 30) {
            visiblePropertyIds = targetIds
            delay(200)
            displayedProperties = filteredProperties
        } else {
            displayedProperties = filteredProperties
            visiblePropertyIds = targetIds
        }
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .nestedScroll(nestedScrollConnection)
            .testTag("explore_screen")
    ) {
        // Scroll-Aware Header Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandCharcoalDark)
        ) {
            // ==========================================
            // Collapsible Section (Title, Subtitle & Expanded Search / Categories)
            // ==========================================
            AnimatedVisibility(
                visible = isHeaderExpanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Compact Single-Line Search + Filter + View + Add Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        val searchInteractionSource = remember { MutableInteractionSource() }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            maxLines = 1,
                            cursorBrush = SolidColor(BrandGold),
                            textStyle = TextStyle(
                                color = TextPrimaryDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            interactionSource = searchInteractionSource,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("search_text_input")
                        ) { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = searchQuery,
                                innerTextField = innerTextField,
                                enabled = true,
                                singleLine = true,
                                visualTransformation = VisualTransformation.None,
                                interactionSource = searchInteractionSource,
                                placeholder = {
                                    Text(
                                        text = "Search location, building, code...",
                                        color = TextMutedDark,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = BrandGold,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = if (searchQuery.isNotEmpty()) {
                                    {
                                        IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear",
                                                tint = TextMutedDark,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                } else null,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                container = {
                                    OutlinedTextFieldDefaults.Container(
                                        enabled = true,
                                        isError = false,
                                        interactionSource = searchInteractionSource,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = BrandCharcoalSurface,
                                            unfocusedContainerColor = BrandCharcoalSurface,
                                            focusedBorderColor = BrandGold,
                                            unfocusedBorderColor = BrandCharcoalBorder
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        focusedBorderThickness = 1.5.dp,
                                        unfocusedBorderThickness = 1.dp
                                    )
                                }
                            )
                        }

                        // Filter Sheet Button
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (activeFilterCount > 0) BrandGold else BrandCharcoalSurface)
                                .border(1.dp, if (activeFilterCount > 0) BrandGold else BrandCharcoalBorder, RoundedCornerShape(10.dp))
                                .clickable { showFilterSheet = true }
                                .testTag("filter_sheet_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            BadgedBox(
                                badge = {
                                    if (activeFilterCount > 0) {
                                        Badge(
                                            containerColor = BrandCharcoalDark,
                                            contentColor = BrandGold
                                        ) {
                                            Text(
                                                text = "$activeFilterCount",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Filters",
                                    tint = if (activeFilterCount > 0) BrandCharcoalDark else BrandGold,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Grid/List View Toggle
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BrandCharcoalSurface)
                                .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    val next = !isGridView
                                    isGridView = next
                                    onInventoryViewChange?.invoke(next)
                                }
                                .testTag("grid_list_toggle_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isGridView) Icons.Default.ViewAgenda else Icons.Default.GridView,
                                contentDescription = "Toggle View",
                                tint = TextSecondaryDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Compact Add Property Icon Button
                        IconButton(
                            onClick = onOpenAddProperty,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BrandGold)
                                .testTag("explore_add_property_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Property",
                                tint = BrandCharcoalDark,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Unified 2-way Inventory Toggle: All Active (no verification gate) | Archive / Closed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VerificationTabChip(
                            label = "All Active ($activeTotalCount)",
                            isSelected = selectedVerificationTab != VerificationFilterTab.ARCHIVE && !showUnclassifiedOnly,
                            onClick = {
                                selectedVerificationTab = VerificationFilterTab.ALL
                                onClearUnclassifiedOnly?.invoke()
                            },
                            modifier = Modifier
                                .weight(1.25f)
                                .testTag("inventory_tab_live"),
                            badgeColor = StatusAvailable
                        )
                        VerificationTabChip(
                            label = "Archive / Closed ($archiveCount)",
                            isSelected = selectedVerificationTab == VerificationFilterTab.ARCHIVE && !showUnclassifiedOnly,
                            onClick = {
                                selectedVerificationTab = VerificationFilterTab.ARCHIVE
                                onClearUnclassifiedOnly?.invoke()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("inventory_tab_archive"),
                            badgeColor = TextMutedDark
                        )
                    }

                    // Compact optional status filter chips + sub-type & upcoming chip strip
                    val subCategories = remember(selectedBrand) {
                        getCategoriesForBrand(selectedBrand).filter {
                            it !is CategoryFilterOption.AllCommercial && it !is CategoryFilterOption.AllResidential
                        }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (selectedVerificationTab != VerificationFilterTab.ARCHIVE) {
                            item(key = "chip_status_verified") {
                                val isVerifiedChip = selectedVerificationTab == VerificationFilterTab.VERIFIED && !showUnclassifiedOnly
                                CategoryChip(
                                    label = "Verified ($verifiedCount)",
                                    isSelected = isVerifiedChip,
                                    onClick = {
                                        selectedVerificationTab = if (isVerifiedChip) {
                                            VerificationFilterTab.ALL
                                        } else {
                                            VerificationFilterTab.VERIFIED
                                        }
                                        onClearUnclassifiedOnly?.invoke()
                                    },
                                    modifier = Modifier.testTag("inventory_tab_verified")
                                )
                            }
                            item(key = "chip_status_pending") {
                                val isPendingChip = selectedVerificationTab == VerificationFilterTab.PENDING && !showUnclassifiedOnly
                                CategoryChip(
                                    label = "Pending / Unconfirmed ($pendingCount)",
                                    isSelected = isPendingChip,
                                    onClick = {
                                        selectedVerificationTab = if (isPendingChip) {
                                            VerificationFilterTab.ALL
                                        } else {
                                            VerificationFilterTab.PENDING
                                        }
                                        onClearUnclassifiedOnly?.invoke()
                                    },
                                    modifier = Modifier.testTag("inventory_tab_pending")
                                )
                            }
                        }

                        item(key = "chip_renewal_radar") {
                            CategoryChip(
                                label = "🔔 Renewal Radar (90d)",
                                isSelected = upcomingOnly,
                                onClick = { upcomingOnly = !upcomingOnly },
                                modifier = Modifier.testTag("renewal_radar_90d_chip")
                            )
                        }

                        items(
                            items = subCategories,
                            key = { opt ->
                                when (opt) {
                                    is CategoryFilterOption.TabCategory -> "tab_${opt.tab.name}"
                                    is CategoryFilterOption.TypeCategory -> "type_${opt.typeName}"
                                    else -> opt.toString()
                                }
                            }
                        ) { option ->
                            val isSelected = selectedCategoryOption == option && !showUnclassifiedOnly
                            val label = when (option) {
                                is CategoryFilterOption.TabCategory -> getCategoryDisplayName(option.tab)
                                is CategoryFilterOption.TypeCategory -> option.label
                                else -> ""
                            }
                            if (label.isNotBlank()) {
                                CategoryChip(
                                    label = label,
                                    isSelected = isSelected,
                                    onClick = {
                                        selectedCategoryOption = if (isSelected) null else option
                                        onClearUnclassifiedOnly?.invoke()
                                    }
                                )
                            }
                        }

                        if (unclassifiedCount > 0) {
                            item(key = "chip_unclassified") {
                                CategoryChip(
                                    label = "Unclassified ($unclassifiedCount)",
                                    isSelected = showUnclassifiedOnly,
                                    onClick = {
                                        if (showUnclassifiedOnly) {
                                            onClearUnclassifiedOnly?.invoke()
                                        } else {
                                            selectedCategoryOption = null
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // Compact Collapsed Header (Shown when collapsed)
            // Provides compact search access and preserves state with tap-to-expand
            // ==========================================
            AnimatedVisibility(
                visible = !isHeaderExpanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Compact Search Bar (Tap expands or types directly)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandCharcoalSurface)
                            .border(1.dp, if (searchQuery.isNotEmpty()) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
                            .clickable { isHeaderExpanded = true }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = BrandGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) searchQuery else "Search inventory...",
                            color = if (searchQuery.isNotEmpty()) TextPrimaryDark else TextMutedDark,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = TextMutedDark,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    // Compact Filter Trigger
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (activeFilterCount > 0) BrandGold else BrandCharcoalSurface)
                            .border(1.dp, if (activeFilterCount > 0) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
                            .clickable { showFilterSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        BadgedBox(
                            badge = {
                                if (activeFilterCount > 0) {
                                    Badge(
                                        containerColor = BrandCharcoalDark,
                                        contentColor = BrandGold
                                    ) {
                                        Text(
                                            text = "$activeFilterCount",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Filters",
                                tint = if (activeFilterCount > 0) BrandCharcoalDark else BrandGold,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // View Toggle
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandCharcoalSurface)
                            .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                            .clickable {
                                val next = !isGridView
                                isGridView = next
                                onInventoryViewChange?.invoke(next)
                            }
                            .testTag("grid_list_toggle_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.ViewAgenda else Icons.Default.GridView,
                            contentDescription = "Toggle View",
                            tint = TextSecondaryDark,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Expand / Collapse Chevron
                    IconButton(
                        onClick = { isHeaderExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expand Header",
                            tint = BrandGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // ==========================================
            // Persistent Sort & Results Summary Bar
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${filteredProperties.size} Properties",
                        color = TextSecondaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (activeFilterCount > 0 || searchQuery.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(BrandGold.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (activeFilterCount > 0) "$activeFilterCount active filter${if (activeFilterCount > 1) "s" else ""}" else "Search active",
                                color = BrandGold,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(
                            onClick = {
                                searchQuery = ""
                                selectedCategoryOption = null
                                selectedVerificationTab = VerificationFilterTab.ALL
                                filterState = FilterState()
                                upcomingOnly = false
                                instantQuickFilter = null
                                onSelectBrand(null)
                                onClearUnclassifiedOnly?.invoke()
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("explore_clear_all_filters_btn")
                        ) {
                            Text("Clear All", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Quick Sort Dropdown
                var sortExpanded by remember { mutableStateOf(false) }
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { sortExpanded = true }
                            .padding(4.dp)
                    ) {
                        Text(
                            text = when (sortBy) {
                                "PriceAsc" -> "Price: Low to High"
                                "PriceDesc" -> "Price: High to Low"
                                "AreaDesc" -> "Carpet Area: Highest"
                                "CodeAsc" -> "Property Code"
                                else -> "Sort: Default"
                            },
                            color = BrandGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = BrandGold, modifier = Modifier.size(16.dp))
                    }

                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                        modifier = Modifier.background(BrandCharcoalSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Default Sorting", color = TextPrimaryDark, fontSize = 12.sp) },
                            onClick = { sortBy = "Default"; sortExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Price: Low to High", color = TextPrimaryDark, fontSize = 12.sp) },
                            onClick = { sortBy = "PriceAsc"; sortExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Price: High to Low", color = TextPrimaryDark, fontSize = 12.sp) },
                            onClick = { sortBy = "PriceDesc"; sortExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Carpet Area: Largest", color = TextPrimaryDark, fontSize = 12.sp) },
                            onClick = { sortBy = "AreaDesc"; sortExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Property Code: A-Z", color = TextPrimaryDark, fontSize = 12.sp) },
                            onClick = { sortBy = "CodeAsc"; sortExpanded = false }
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = BrandCharcoalBorder, thickness = 0.8.dp)

        // Main Property Content: Grid or List inside weight(1f) so sticky comparison bar never overlaps
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
        if (properties.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(BrandGold.copy(alpha = 0.5f))
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(BrandGold.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = BrandGold,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Inventory Master Connected",
                            color = TextPrimaryDark,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Commercial & Residential inventory divisions are ready for your master records.",
                            color = TextSecondaryDark,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = onOpenImport,
                                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sync Google Sheet", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = onOpenAddProperty,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold.copy(alpha = 0.6f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = BrandGold, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Property", color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        } else if (displayedProperties.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (selectedVerificationTab != VerificationFilterTab.ARCHIVE && archiveCount > 0 && activeTotalCount == 0) {
                            Icons.Default.Inventory2
                        } else {
                            Icons.Default.SearchOff
                        },
                        contentDescription = null,
                        tint = TextMutedDark,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val isArchiveOnlyPool = selectedVerificationTab != VerificationFilterTab.ARCHIVE && activeTotalCount == 0 && archiveCount > 0
                    Text(
                        text = if (isArchiveOnlyPool) "All properties are in Archive ($archiveCount)" else "No properties match your filter",
                        color = TextPrimaryDark,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isArchiveOnlyPool) {
                            "Review archived properties, complete 100% required details, and toggle Verify ON to publish Live."
                        } else {
                            "Try clearing your search query or switching verification tab"
                        },
                        color = TextSecondaryDark,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (selectedVerificationTab != VerificationFilterTab.ARCHIVE && archiveCount > 0) {
                            Button(
                                onClick = { selectedVerificationTab = VerificationFilterTab.ARCHIVE },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("explore_open_archive_btn")
                            ) {
                                Text("Review Archive ($archiveCount)", fontWeight = FontWeight.Bold)
                            }
                        }
                        if (!isArchiveOnlyPool) {
                            Button(
                                onClick = {
                                    searchQuery = ""
                                    selectedCategoryOption = null
                                    selectedVerificationTab = VerificationFilterTab.ALL
                                    filterState = FilterState()
                                    upcomingOnly = false
                                    instantQuickFilter = null
                                    onSelectBrand(null)
                                    onClearUnclassifiedOnly?.invoke()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Clear All Filters", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else if (isGridView) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 156.dp),
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("explore_property_grid")
            ) {
                items(displayedProperties, key = { it.id }) { property ->
                    val approxBadge = if (approximateMatchPropertyIds.contains(property.id)) {
                        "Approx. Match (${filterState.tolerance.label})"
                    } else null
                    val unitKey = property.sameUnitGroupId.takeIf { it.isNotBlank() }
                        ?: property.physicalUnitKey.takeIf { it.isNotBlank() }
                        ?: property.id

                    AnimatedPropertyCardContainer(
                        property = property,
                        visible = visiblePropertyIds.contains(property.id),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PropertyCard(
                            property = property,
                            isInternalMode = isInternalMode,
                            isFavorite = favoriteIds.contains(property.id),
                            isInComparison = comparisonIds.contains(property.id),
                            onCardClick = { onPropertyClick(property) },
                            onEditClick = { onEditProperty(property) },
                            onToggleFavorite = { onToggleFavorite(property.id) },
                            onToggleCompare = { onToggleCompare(property.id) },
                            onShareWhatsApp = { onShareWhatsApp(property) },
                            onStatusChange = { newStatus -> onStatusChange(property.id, newStatus) },
                            compact = true,
                            isInShortlist = shortlistIds.contains(property.id),
                            onToggleShortlist = onToggleShortlist?.let { cb -> { cb(property.id) } },
                            matchBadgeLabel = approxBadge,
                            isSelectMode = isSelectMode,
                            isSelectedForShare = selectedMultiPropertyIds.contains(property.id),
                            onToggleSelectForShare = {
                                selectedMultiPropertyIds = if (selectedMultiPropertyIds.contains(property.id)) {
                                    selectedMultiPropertyIds - property.id
                                } else {
                                    selectedMultiPropertyIds + property.id
                                }
                            },
                            onSaveVerification = { status, method, notes ->
                                onStatusChange(property.id, status)
                                onVerifyProperty?.invoke(property.id, status, method, notes)
                            },
                            onVerificationSwitchChange = { goLive ->
                                if (onToggleVerifyLive != null) {
                                    onToggleVerifyLive(property, goLive)
                                } else if (goLive) {
                                    onVerifyProperty?.invoke(property.id, property.status, "Quick Switch", "")
                                }
                            },
                            linkedUnitCount = linkedUnitCounts[unitKey] ?: 1,
                            onInlineUpdateProperty = onInlineUpdateProperty,
                            onUploadPhoto = onUploadPhoto
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("explore_property_list")
            ) {
                items(displayedProperties, key = { it.id }) { property ->
                    val approxBadge = if (approximateMatchPropertyIds.contains(property.id)) {
                        "Approx. Match (${filterState.tolerance.label})"
                    } else null
                    val unitKey = property.sameUnitGroupId.takeIf { it.isNotBlank() }
                        ?: property.physicalUnitKey.takeIf { it.isNotBlank() }
                        ?: property.id

                    AnimatedPropertyCardContainer(
                        property = property,
                        visible = visiblePropertyIds.contains(property.id),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PropertyCard(
                            property = property,
                            isInternalMode = isInternalMode,
                            isFavorite = favoriteIds.contains(property.id),
                            isInComparison = comparisonIds.contains(property.id),
                            onCardClick = { onPropertyClick(property) },
                            onEditClick = { onEditProperty(property) },
                            onToggleFavorite = { onToggleFavorite(property.id) },
                            onToggleCompare = { onToggleCompare(property.id) },
                            onShareWhatsApp = { onShareWhatsApp(property) },
                            onStatusChange = { newStatus -> onStatusChange(property.id, newStatus) },
                            isInShortlist = shortlistIds.contains(property.id),
                            onToggleShortlist = onToggleShortlist?.let { cb -> { cb(property.id) } },
                            matchBadgeLabel = approxBadge,
                            isSelectMode = isSelectMode,
                            isSelectedForShare = selectedMultiPropertyIds.contains(property.id),
                            onToggleSelectForShare = {
                                selectedMultiPropertyIds = if (selectedMultiPropertyIds.contains(property.id)) {
                                    selectedMultiPropertyIds - property.id
                                } else {
                                    selectedMultiPropertyIds + property.id
                                }
                            },
                            onSaveVerification = { status, method, notes ->
                                onStatusChange(property.id, status)
                                onVerifyProperty?.invoke(property.id, status, method, notes)
                            },
                            onVerificationSwitchChange = { goLive ->
                                if (onToggleVerifyLive != null) {
                                    onToggleVerifyLive(property, goLive)
                                } else if (goLive) {
                                    onVerifyProperty?.invoke(property.id, property.status, "Quick Switch", "")
                                }
                            },
                            linkedUnitCount = linkedUnitCounts[unitKey] ?: 1,
                            onInlineUpdateProperty = onInlineUpdateProperty,
                            onUploadPhoto = onUploadPhoto
                        )
                    }
                }
            }
        }
        }

        if (comparisonIds.isNotEmpty() || shortlistIds.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                if (comparisonIds.isNotEmpty()) {
                    ComparisonSelectionBar(
                        selectedCount = comparisonIds.size,
                        onCompareSelected = { onOpenComparison?.invoke() },
                        onClear = { onClearComparison?.invoke() }
                    )
                }
                if (shortlistIds.isNotEmpty()) {
                    ShortlistPinnedBottomBar(
                        selectedCount = shortlistIds.size,
                        onReviewShortlist = { onReviewShortlist?.invoke() },
                        onClear = { onClearShortlist?.invoke() }
                    )
                }
            }
        }
    }

    // Filter Bottom Sheet
    if (showFilterSheet) {
        FilterSheet(
            filterState = filterState,
            onFilterChange = { newState ->
                filterState = newState
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false },
            matchingCountPreview = filteredProperties.size
        )
    }
}

@Composable
private fun ComparisonSelectionBar(
    selectedCount: Int,
    onCompareSelected: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        color = PrimaryNavy,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("comparison_selection_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "${selectedCount.coerceAtMost(3)}/3 Selected for Comparison",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("comparison_selection_count")
            )
            TextButton(
                onClick = onClear,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.9f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("comparison_bar_clear_btn")
            ) {
                Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onCompareSelected,
                enabled = selectedCount >= 2,
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor,
                    disabledContainerColor = PrimaryActionOrange.copy(alpha = 0.4f),
                    disabledContentColor = OnPrimaryActionColor.copy(alpha = 0.6f)
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("comparison_bar_open_btn")
            ) {
                Text("Compare Selected", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
fun VerificationTabChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeColor: Color = BrandGold
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) badgeColor else BrandCharcoalSurface)
            .border(
                1.dp,
                if (isSelected) badgeColor else BrandCharcoalBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) BrandCharcoalDark else TextSecondaryDark,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun CategoryChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) BrandGold else BrandCharcoalSurface)
            .border(
                1.dp,
                if (isSelected) BrandGoldLight else BrandCharcoalBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) BrandCharcoalDark else TextSecondaryDark,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

object RenewalRadar90dHelper {
    private val dateFormats = listOf(
        "yyyy-MM-dd",
        "dd/MM/yyyy",
        "dd-MM-yyyy",
        "d MMM yyyy",
        "dd MMM yyyy",
        "MMM yyyy"
    )

    fun daysUntilRenewalOrLockInEnd(p: PropertyItem): Long? {
        val candidateDates = listOfNotNull(
            p.leaseExpiryDate.takeIf { it.isNotBlank() },
            p.upcomingVacancyDate.takeIf { it.isNotBlank() },
            p.unmappedColumns["Lease End Date"]?.takeIf { it.isNotBlank() },
            p.unmappedColumns["leaseEndDate"]?.takeIf { it.isNotBlank() },
            p.unmappedColumns["Lock-in End Date"]?.takeIf { it.isNotBlank() },
            p.unmappedColumns["lockInEndDate"]?.takeIf { it.isNotBlank() },
            p.unmappedColumns["Lock-In Expiry"]?.takeIf { it.isNotBlank() }
        )

        val todayEpochDay = java.time.LocalDate.now().toEpochDay()
        val parsedDays = mutableListOf<Long>()

        for (raw in candidateDates) {
            parseEpochDay(raw)?.let { targetDay ->
                parsedDays.add(targetDay - todayEpochDay)
            }
        }

        // Also check if leaseStartDate in unmappedColumns + lockInMonths or leaseTenureYears falls within window
        val rawLeaseStart = p.unmappedColumns["Lease Start Date"]
            ?: p.unmappedColumns["leaseStartDate"]
            ?: ""
        if (parsedDays.isEmpty() && rawLeaseStart.isNotBlank()) {
            parseEpochDay(rawLeaseStart)?.let { startEpochDay ->
                val startLocal = java.time.LocalDate.ofEpochDay(startEpochDay)
                val lockYearsDouble = p.lockInPeriodYears?.toString()?.toDoubleOrNull() ?: 0.0
                val lockMonths = p.lockInMonths ?: (lockYearsDouble * 12.0).toInt().takeIf { it > 0 }
                if (lockMonths != null && lockMonths > 0) {
                    val lockEndDays = startLocal.plusMonths(lockMonths.toLong()).toEpochDay() - todayEpochDay
                    parsedDays.add(lockEndDays)
                }
                val tenureYearsDouble = p.leaseTenureYears?.toString()?.toDoubleOrNull() ?: 0.0
                if (tenureYearsDouble > 0.0) {
                    val leaseEndDays = startLocal.plusMonths((tenureYearsDouble * 12.0).toLong()).toEpochDay() - todayEpochDay
                    parsedDays.add(leaseEndDays)
                }
            }
        }

        val inWindow = parsedDays.filter { it in -30L..90L }
        if (inWindow.isNotEmpty()) {
            return inWindow.minOrNull()
        }
        if (com.example.inventory.InventoryQuality.upcoming(p)) {
            return parsedDays.minOrNull() ?: 45L
        }
        return null
    }

    fun matchesRenewalRadar90d(p: PropertyItem): Boolean {
        return daysUntilRenewalOrLockInEnd(p) != null
    }

    private fun parseEpochDay(raw: String): Long? {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return null
        try {
            return java.time.LocalDate.parse(cleaned).toEpochDay()
        } catch (_: Exception) {
        }
        for (pattern in dateFormats) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    isLenient = true
                }
                val d = sdf.parse(cleaned)
                if (d != null) {
                    return d.time / 86_400_000L
                }
            } catch (_: Exception) {
            }
        }
        return null
    }
}

/**
 * Polished entrance and exit transition container for inventory property cards using Jetpack Compose's AnimatedVisibility API.
 * Provides smooth staggered entrance (fade-in, slide-up, expand) and exit (fade-out, slide-up, shrink).
 */
@Composable
fun AnimatedPropertyCardContainer(
    property: PropertyItem,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isVisible = visible

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(
            animationSpec = tween(durationMillis = 280, easing = LinearOutSlowInEasing)
        ) + slideInVertically(
            initialOffsetY = { fullHeight -> (fullHeight * 0.15f).toInt() },
            animationSpec = tween(durationMillis = 280, easing = LinearOutSlowInEasing)
        ) + expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = tween(durationMillis = 280, easing = LinearOutSlowInEasing)
        ),
        exit = fadeOut(
            animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
        ) + slideOutVertically(
            targetOffsetY = { fullHeight -> (-fullHeight * 0.10f).toInt() },
            animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
        ) + shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
        ),
        modifier = modifier
    ) {
        content()
    }
}


