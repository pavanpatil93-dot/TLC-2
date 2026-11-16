package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inventory.MatchTolerance
import com.example.model.*
import com.example.ui.components.FilterChipItem
import com.example.ui.shortlist.ShortlistReviewBottomSheet
import com.example.ui.shortlist.ShortlistViewModel
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor

enum class RequirementTransactionBasis(val label: String) {
    LEASE("Lease"),
    PURCHASE("Purchase"),
    PRE_LEASED_INVESTMENT("Pre-Leased Investment"),
    UNKNOWN("Unknown")
}

enum class RequirementPriceBasis(val label: String) {
    MONTHLY_RENT("Monthly Rent"),
    TOTAL_PURCHASE_PRICE("Total Purchase Price"),
    UNKNOWN("Unknown")
}

enum class RequirementAreaBasis(val label: String) {
    CARPET("Carpet Area"),
    BUILT_UP("Built-up Area"),
    UNKNOWN("Unknown")
}

enum class MatchResultCategory(val label: String) {
    PRIMARY("Primary"),
    NEARBY("Nearby"),
    PRICE("Price"),
    SIZE("Size"),
    POSSESSION("Possession")
}

internal data class CrmMatchCriteria(
    val brand: Brand? = null,
    val transactionType: String = "All", // "Lease", "Sale", "Pre-Leased", "All"
    val propertyType: String = "",
    val preferredLocations: List<String> = emptyList(),
    val preferredProjectIds: List<String> = emptyList(),
    val minBudget: Double? = null,
    val maxBudget: Double? = null,
    val priceBasis: RequirementPriceBasis = RequirementPriceBasis.UNKNOWN,
    val minCarpetSqFt: Double? = null,
    val maxCarpetSqFt: Double? = null,
    val areaBasis: RequirementAreaBasis = RequirementAreaBasis.CARPET,
    val bhk: String? = null,
    val furnishing: FurnishingType? = null,
    val minParkingCount: Int? = null,
    val possession: String? = null,
    val flexibilityPercent: Int = 15, // 0, 5, 10, 15, 20, 25
    val includeNearbyAlternatives: Boolean = true,
    val tolerance: MatchTolerance = MatchTolerance.TOL_15
)

internal data class MatchedPropertyItem(
    val property: PropertyItem,
    val physicalUnitId: String = property.sameUnitGroupId.takeIf { it.isNotBlank() } ?: property.physicalUnitKey.ifBlank { property.id },
    val sourceListings: List<PropertyItem> = listOf(property),
    val selectedSourceListingId: String = property.id,
    val matchScorePercent: Int? = null,
    val matchReasons: List<String> = emptyList(),
    val missingCriteria: List<String> = emptyList(),
    val deviationReasons: List<String> = emptyList(),
    val resultCategory: MatchResultCategory = MatchResultCategory.PRIMARY,
    val isExact: Boolean = true,
    val isRecheckDue: Boolean = false
)

internal fun parseBudgetRange(budgetStr: String): Pair<Double?, Double?> {
    if (budgetStr.isBlank()) return null to null
    val clean = budgetStr.lowercase().replace(",", "")
    val numbers = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(cr|crore|l|lac|lakh|k)?(\/mo|pm)?""", RegexOption.IGNORE_CASE).findAll(clean).toList()
    if (numbers.isEmpty()) return null to null

    fun parseVal(match: MatchResult): Double {
        val n = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val unit = match.groupValues[2].lowercase()
        return when {
            unit.contains("cr") || unit.contains("crore") -> n * 10_000_000.0
            unit.contains("l") || unit.contains("lac") || unit.contains("lakh") -> n * 100_000.0
            unit.contains("k") -> n * 1_000.0
            else -> n
        }
    }

    return if (numbers.size >= 2) {
        val first = parseVal(numbers[0]).takeIf { it.isFinite() && it > 0.0 }
        val second = parseVal(numbers[1]).takeIf { it.isFinite() && it > 0.0 }
        if (first != null && second != null && first > second) second to first else first to second
    } else {
        null to parseVal(numbers[0]).takeIf { it.isFinite() && it > 0.0 }
    }
}

internal fun parseAreaRange(areaStr: String): Pair<Double?, Double?> {
    if (areaStr.isBlank()) return null to null
    val clean = areaStr.lowercase().replace(",", "")
    val match = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(?:-|to|–)?\s*([0-9]+(?:\.[0-9]+)?)?""").find(clean) ?: return null to null
    val min = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    val max = match.groupValues[2].takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: min
    return if (min != null && max != null && min > max) max to min else min to max
}

internal fun extractMatchCriteria(lead: CrmLead): CrmMatchCriteria {
    val brand = if (lead.segment.equals("Residential", true)) Brand.RESIDENTIAL else Brand.COMMERCIAL
    val req = lead.requirement.lowercase()
    val tx = when {
        req.contains("pre-leased") || req.contains("roi") -> "Pre-Leased"
        req.contains("rent") || req.contains("lease") -> "Lease"
        req.contains("buy") || req.contains("purchase") || req.contains("sale") || req.contains("resale") -> "Sale"
        else -> "All"
    }
    val propType = when {
        req.contains("showroom") -> "Showroom"
        req.contains("office") -> "Office"
        req.contains("shop") || req.contains("retail") -> "Shop"
        req.contains("warehouse") || req.contains("godown") -> "Warehouse"
        req.contains("apartment") || req.contains("flat") -> "Apartment"
        req.contains("villa") || req.contains("bungalow") -> "Villa"
        req.contains("plot") || req.contains("land") -> "Plot"
        else -> ""
    }
    val locs = lead.location.split(",", "/", ";", "|").map { it.trim() }.filter { it.length >= 2 }
    val (minB, maxB) = parseBudgetRange(lead.budget)
    val (minA, maxA) = parseAreaRange(lead.carpetAreaRange)

    val priceBasis = when (tx) {
        "Lease" -> RequirementPriceBasis.MONTHLY_RENT
        "Sale", "Pre-Leased" -> RequirementPriceBasis.TOTAL_PURCHASE_PRICE
        else -> RequirementPriceBasis.UNKNOWN
    }
    val areaBasis = when {
        lead.carpetAreaRange.isNotBlank() -> RequirementAreaBasis.CARPET
        req.contains("built-up") || req.contains("built up") -> RequirementAreaBasis.BUILT_UP
        else -> RequirementAreaBasis.UNKNOWN
    }

    val bhk = Regex("""(\d+(?:\.\d+)?)\s*bhk""", RegexOption.IGNORE_CASE).find(lead.requirement)?.groupValues?.getOrNull(1)?.let { "$it BHK" }

    return CrmMatchCriteria(
        brand = brand,
        transactionType = tx,
        propertyType = propType,
        preferredLocations = locs,
        minBudget = minB,
        maxBudget = maxB,
        priceBasis = priceBasis,
        minCarpetSqFt = minA,
        maxCarpetSqFt = maxA,
        areaBasis = areaBasis,
        bhk = bhk,
        possession = lead.possessionTimeline.trim().takeIf { it.isNotBlank() }
    )
}

internal fun isRecheckDue(lastVerifiedDate: String): Boolean {
    if (lastVerifiedDate.isBlank()) return true
    return runCatching {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val d = sdf.parse(lastVerifiedDate) ?: return true
        val diffDays = (System.currentTimeMillis() - d.time) / (1000L * 60 * 60 * 24)
        diffDays >= 60
    }.getOrDefault(false)
}

/**
 * Deterministic, explainable property matching engine with physical-unit deduplication.
 * Formula: score = floor(100 * sum(weight * criterionScore) / sum(applicableWeights))
 * Missing property data on an active criterion gets criterionScore = 0.0 (never earns 100%).
 */
internal fun evaluateExplainableMatches(
    inventory: List<PropertyItem>,
    criteria: CrmMatchCriteria
): List<MatchedPropertyItem> {
    val activeList = inventory.filter {
        it.archiveStatus == ArchiveStatus.ACTIVE &&
            it.status != AvailabilityStatus.NOT_AVAILABLE &&
            it.status != AvailabilityStatus.SOLD &&
            it.status != AvailabilityStatus.LEASED &&
            it.status != AvailabilityStatus.WITHDRAWN
    }

    // Group reviewed same-unit listings so each physical unit appears once per compatible transaction
    val groupedByUnit = activeList.groupBy { prop ->
        val tx = StandardTransactionType.fromString(prop.transactionType, prop.tab)
        val unitKey = prop.sameUnitGroupId.takeIf { it.isNotBlank() } ?: prop.physicalUnitKey.ifBlank { "${prop.projectName.trim().lowercase()}_${prop.unitNumber.trim().lowercase()}_${prop.floor.trim().lowercase()}".takeIf { it != "__" } ?: prop.id }
        "${unitKey}::${tx.name}"
    }

    val flexFraction = (criteria.flexibilityPercent.coerceIn(0, 25)) / 100.0
    val results = mutableListOf<MatchedPropertyItem>()

    for ((_, unitListings) in groupedByUnit) {
        val sortedSources = unitListings.sortedWith(
            compareByDescending<PropertyItem> { it.verificationStatus == VerificationStatus.VERIFIED }
                .thenBy { it.verificationAgeDays() ?: Int.MAX_VALUE }
                .thenBy { it.id }
        )
        val prop = sortedSources.first()

        // Hard Gate 1: Business Division
        if (criteria.brand != null) {
            val isPropComm = prop.isCommercial()
            if (criteria.brand == Brand.COMMERCIAL && !isPropComm) continue
            if (criteria.brand == Brand.RESIDENTIAL && isPropComm) continue
        }

        // Hard Gate 2: Transaction Type
        val stdTx = StandardTransactionType.fromString(prop.transactionType, prop.tab)
        if (criteria.transactionType != "All" && criteria.transactionType != "Unknown") {
            val matchesTx = when (criteria.transactionType) {
                "Lease" -> stdTx == StandardTransactionType.LEASE
                "Sale", "Purchase" -> stdTx == StandardTransactionType.SALE
                "Pre-Leased", "Pre-Leased Investment" -> stdTx == StandardTransactionType.PRE_LEASED_SALE || prop.tab == InventoryTab.PRE_LEASED
                else -> true
            }
            if (!matchesTx) continue
        }

        var totalWeight = 0.0
        var weightedScoreSum = 0.0
        val matchReasons = mutableListOf<String>()
        val missingCriteria = mutableListOf<String>()
        val deviations = mutableListOf<String>()
        var category = MatchResultCategory.PRIMARY

        // 1. Property Type (Weight 25)
        if (criteria.propertyType.isNotBlank()) {
            val weight = 25.0
            totalWeight += weight
            if (prop.propertyType.isBlank()) {
                missingCriteria.add("Property type not recorded on listing")
            } else if (prop.propertyType.contains(criteria.propertyType, ignoreCase = true) ||
                prop.tab.title.contains(criteria.propertyType, ignoreCase = true)
            ) {
                weightedScoreSum += weight * 1.0
                matchReasons.add("Property type matches ${criteria.propertyType}")
            } else {
                deviations.add("Property type: ${prop.propertyType} (requested ${criteria.propertyType})")
            }
        }

        // 2. Location / Project (Weight 30)
        if (criteria.preferredLocations.isNotEmpty() || criteria.preferredProjectIds.isNotEmpty()) {
            val weight = 30.0
            totalWeight += weight
            if (prop.location.isBlank() && prop.projectName.isBlank()) {
                missingCriteria.add("Location/Project not recorded on listing")
            } else {
                val exactLoc = criteria.preferredLocations.any { loc ->
                    prop.location.contains(loc, ignoreCase = true) || prop.projectName.contains(loc, ignoreCase = true)
                } || criteria.preferredProjectIds.any { pid ->
                    prop.projectName.contains(pid, ignoreCase = true)
                }
                if (exactLoc) {
                    weightedScoreSum += weight * 1.0
                    matchReasons.add("Located in preferred location (${prop.location.ifBlank { prop.projectName }})")
                } else if (criteria.includeNearbyAlternatives) {
                    weightedScoreSum += weight * 0.45
                    deviations.add("Alternative location: ${prop.location.ifBlank { "Pune" }}")
                    if (category == MatchResultCategory.PRIMARY) category = MatchResultCategory.NEARBY
                } else {
                    continue
                }
            }
        }

        // 3. Budget / Price (Weight 25)
        if (criteria.minBudget != null || criteria.maxBudget != null) {
            val weight = 25.0
            totalWeight += weight
            val propAmount = CalculationUtils.primaryAmountForProperty(prop)
            if (propAmount <= 0.0 || !propAmount.isFinite()) {
                missingCriteria.add("Price/Rent not recorded on listing")
            } else {
                val minB = criteria.minBudget ?: 0.0
                val maxB = criteria.maxBudget ?: Double.MAX_VALUE
                val flexMin = minB * (1.0 - flexFraction)
                val flexMax = if (maxB == Double.MAX_VALUE) Double.MAX_VALUE else maxB * (1.0 + flexFraction)

                when {
                    propAmount in minB..maxB -> {
                        weightedScoreSum += weight * 1.0
                        matchReasons.add("Within target budget (${CalculationUtils.formatIndianCurrency(propAmount, stdTx == StandardTransactionType.LEASE)})")
                    }
                    propAmount in flexMin..flexMax -> {
                        weightedScoreSum += weight * 0.65
                        deviations.add("Price within ${criteria.flexibilityPercent}% flexibility (${CalculationUtils.formatIndianCurrency(propAmount, stdTx == StandardTransactionType.LEASE)})")
                        if (category == MatchResultCategory.PRIMARY) category = MatchResultCategory.PRICE
                    }
                    else -> {
                        deviations.add(if (propAmount > flexMax) "Exceeds maximum budget" else "Below minimum budget")
                        if (category == MatchResultCategory.PRIMARY) category = MatchResultCategory.PRICE
                    }
                }
            }
        }

        // 4. Area (Weight 20) — Respect Carpet vs Built-Up basis; never assume loading
        if (criteria.minCarpetSqFt != null || criteria.maxCarpetSqFt != null) {
            val weight = 20.0
            totalWeight += weight
            val propArea: Double? = when (criteria.areaBasis) {
                RequirementAreaBasis.CARPET -> prop.carpetAreaSqFt.takeIf { it.isFinite() && it > 0.0 }
                RequirementAreaBasis.BUILT_UP -> prop.builtUpAreaSqFt?.takeIf { it.isFinite() && it > 0.0 }
                RequirementAreaBasis.UNKNOWN -> prop.carpetAreaSqFt.takeIf { it.isFinite() && it > 0.0 }
                    ?: prop.builtUpAreaSqFt?.takeIf { it.isFinite() && it > 0.0 }
            }
            if (propArea == null) {
                missingCriteria.add("${criteria.areaBasis.label} not recorded on listing")
            } else {
                val minA = criteria.minCarpetSqFt ?: 0.0
                val maxA = criteria.maxCarpetSqFt ?: Double.MAX_VALUE
                val flexMin = minA * (1.0 - flexFraction)
                val flexMax = if (maxA == Double.MAX_VALUE) Double.MAX_VALUE else maxA * (1.0 + flexFraction)

                when {
                    propArea in minA..maxA -> {
                        weightedScoreSum += weight * 1.0
                        matchReasons.add("${criteria.areaBasis.label} matches (${propArea.toInt()} sq.ft)")
                    }
                    propArea in flexMin..flexMax -> {
                        weightedScoreSum += weight * 0.65
                        deviations.add("Area within ${criteria.flexibilityPercent}% flexibility (${propArea.toInt()} sq.ft)")
                        if (category == MatchResultCategory.PRIMARY) category = MatchResultCategory.SIZE
                    }
                    else -> {
                        deviations.add("Area ${propArea.toInt()} sq.ft outside target range")
                        if (category == MatchResultCategory.PRIMARY) category = MatchResultCategory.SIZE
                    }
                }
            }
        }

        // 5. Configuration / BHK (Weight 10)
        if (!criteria.bhk.isNullOrBlank()) {
            val weight = 10.0
            totalWeight += weight
            val propBhk = prop.bhk.orEmpty().trim()
            if (propBhk.isBlank()) {
                missingCriteria.add("Configuration (BHK) not recorded on listing")
            } else if (propBhk.contains(criteria.bhk, ignoreCase = true)) {
                weightedScoreSum += weight * 1.0
                matchReasons.add("Configuration matches ${criteria.bhk}")
            } else {
                deviations.add("Configuration: $propBhk (requested ${criteria.bhk})")
                if (category == MatchResultCategory.PRIMARY) category = MatchResultCategory.SIZE
            }
        }

        // 6. Possession Timeline (Weight 10)
        if (!criteria.possession.isNullOrBlank()) {
            val weight = 10.0
            totalWeight += weight
            val propPoss = prop.possession.orEmpty().trim()
            if (propPoss.isBlank()) {
                missingCriteria.add("Possession date not recorded on listing")
            } else if (propPoss.contains(criteria.possession, ignoreCase = true) ||
                propPoss.contains("Ready", ignoreCase = true)
            ) {
                weightedScoreSum += weight * 1.0
                matchReasons.add("Possession timeline compatible ($propPoss)")
            } else {
                weightedScoreSum += weight * 0.5
                deviations.add("Possession: $propPoss (requested ${criteria.possession})")
                if (category == MatchResultCategory.PRIMARY) category = MatchResultCategory.POSSESSION
            }
        }

        val scorePercent: Int? = if (totalWeight > 0.0) {
            floor(100.0 * weightedScoreSum / totalWeight).toInt().coerceIn(0, 100)
        } else {
            null
        }

        val isExactMatch = deviations.isEmpty() && missingCriteria.isEmpty()
        if (isExactMatch || deviations.size + missingCriteria.size <= 2) {
            results.add(
                MatchedPropertyItem(
                    property = prop,
                    physicalUnitId = prop.sameUnitGroupId.takeIf { it.isNotBlank() } ?: prop.physicalUnitKey.ifBlank { prop.id },
                    sourceListings = sortedSources,
                    selectedSourceListingId = prop.id,
                    matchScorePercent = scorePercent,
                    matchReasons = matchReasons,
                    missingCriteria = missingCriteria,
                    deviationReasons = deviations,
                    resultCategory = if (isExactMatch) MatchResultCategory.PRIMARY else category,
                    isExact = isExactMatch,
                    isRecheckDue = prop.needsReverification(60)
                )
            )
        }
    }

    return results.sortedWith(
        compareByDescending<MatchedPropertyItem> { it.matchScorePercent ?: -1 }
            .thenBy { it.property.verificationAgeDays() ?: Int.MAX_VALUE }
            .thenBy { it.property.id }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrmInventoryMatchesDialog(
    lead: CrmLead?,
    inventory: List<PropertyItem>,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var criteria by remember(lead) { mutableStateOf(if (lead != null) extractMatchCriteria(lead) else CrmMatchCriteria()) }
    var showCriteriaEditor by remember { mutableStateOf(false) }
    var activeCategory by rememberSaveable { mutableStateOf("Primary") }
    var selectedSourceByUnit by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val shortlistViewModel = remember {
        ShortlistViewModel(
            initialInventory = inventory,
            initialEmployeeName = lead?.assignedTo.orEmpty()
        )
    }
    LaunchedEffect(inventory) {
        shortlistViewModel.updateInventory(inventory)
    }
    val shortlistState by shortlistViewModel.state.collectAsState()

    val allEvaluated = remember(inventory, criteria) {
        evaluateExplainableMatches(inventory, criteria)
    }

    val categorizedMatches = remember(allEvaluated, activeCategory) {
        when (activeCategory) {
            "Primary" -> allEvaluated.filter { it.resultCategory == MatchResultCategory.PRIMARY }
            "Nearby" -> allEvaluated.filter { it.resultCategory == MatchResultCategory.NEARBY }
            "Price" -> allEvaluated.filter { it.resultCategory == MatchResultCategory.PRICE }
            "Size" -> allEvaluated.filter { it.resultCategory == MatchResultCategory.SIZE }
            "Possession" -> allEvaluated.filter { it.resultCategory == MatchResultCategory.POSSESSION }
            else -> allEvaluated
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = BackgroundSoft) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Header
                Surface(
                    color = SurfaceWhite,
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Client Requirement Matcher",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Client: ${lead?.name?.ifBlank { "Selected Client" } ?: "Selected Client"} (${lead?.segment ?: "Commercial"})",
                                color = SecondaryText,
                                fontSize = 12.sp
                            )
                        }
                        TextButton(
                            onClick = onClose,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Close", color = PrimaryActionOrange, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Active Criteria Bar
                Surface(
                    color = SurfaceMuted,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(0.8.dp, BorderGrey)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buildString {
                                    append("Target: ${criteria.propertyType.ifBlank { "All Types" }} · ")
                                    append("${criteria.transactionType} · ")
                                    append(if (criteria.preferredLocations.isNotEmpty()) criteria.preferredLocations.joinToString() else "Location Not Mentioned")
                                    append(" · ±${criteria.flexibilityPercent}%")
                                },
                                fontSize = 11.5.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (lead?.requirement?.isNotBlank() == true) {
                                Text(
                                    text = "Imported Text: \"${lead.requirement}\"",
                                    fontSize = 10.5.sp,
                                    color = SecondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { showCriteriaEditor = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, PrimaryActionOrange),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryActionOrange),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("matcher_edit_criteria_btn")
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit Criteria", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Category Tabs: Primary, Nearby, Price, Size, Possession
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceWhite)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val categories = listOf(
                        "Primary" to allEvaluated.count { it.resultCategory == MatchResultCategory.PRIMARY },
                        "Nearby" to allEvaluated.count { it.resultCategory == MatchResultCategory.NEARBY },
                        "Price" to allEvaluated.count { it.resultCategory == MatchResultCategory.PRICE },
                        "Size" to allEvaluated.count { it.resultCategory == MatchResultCategory.SIZE },
                        "Possession" to allEvaluated.count { it.resultCategory == MatchResultCategory.POSSESSION }
                    )
                    categories.forEach { (tabName, count) ->
                        MatchTabButton(
                            title = "$tabName ($count)",
                            isSelected = activeCategory == tabName,
                            onClick = { activeCategory = tabName }
                        )
                    }
                }

                HorizontalDivider(color = BorderGrey)

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(categorizedMatches, key = { it.physicalUnitId + "_" + it.property.id }) { item ->
                        val activeListingId = selectedSourceByUnit[item.physicalUnitId] ?: item.selectedSourceListingId
                        val activeListing = item.sourceListings.firstOrNull { it.id == activeListingId } ?: item.property
                        MatchResultCard(
                            item = item.copy(property = activeListing, selectedSourceListingId = activeListing.id),
                            isSelected = shortlistState.selectedIds.contains(activeListing.id),
                            onSelectSourceListing = { newSourceId ->
                                selectedSourceByUnit = selectedSourceByUnit + (item.physicalUnitId to newSourceId)
                            },
                            onToggleSelect = {
                                shortlistViewModel.toggleSelection(activeListing.id)
                            }
                        )
                    }

                    if (categorizedMatches.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "No properties in '$activeCategory' match the current requirement criteria.",
                                    color = SecondaryText,
                                    fontSize = 13.sp
                                )
                                OutlinedButton(
                                    onClick = { showCriteriaEditor = true },
                                    border = BorderStroke(1.dp, PrimaryActionOrange),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Adjust Requirement Criteria", color = PrimaryActionOrange, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Pinned Shortlist Bar
                if (shortlistState.selectedCount > 0) {
                    Surface(
                        color = SurfaceWhite,
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${shortlistState.selectedCount} Properties Shortlisted",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Privacy-safe WhatsApp draft ready",
                                    color = SecondaryText,
                                    fontSize = 10.5.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = { shortlistViewModel.clearShortlist() },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Clear", color = SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { shortlistViewModel.openReviewSheet() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = OnPrimaryActionColor
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Review Shortlist (${shortlistState.selectedCount})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCriteriaEditor) {
        CriteriaEditorSheet(
            initial = criteria,
            onDismiss = { showCriteriaEditor = false },
            onApply = { updated ->
                criteria = updated
                showCriteriaEditor = false
            }
        )
    }

    if (shortlistState.isReviewSheetOpen) {
        ShortlistReviewBottomSheet(
            state = shortlistState,
            onRemoveProperty = { shortlistViewModel.removeProperty(it) },
            onCopyText = { shortlistViewModel.copyShortlistToClipboard(context) },
            onShareWhatsApp = { shortlistViewModel.shareToWhatsApp(context) },
            onDismiss = { shortlistViewModel.closeReviewSheet() },
            onMovePropertyUp = { shortlistViewModel.movePropertyUp(it) },
            onMovePropertyDown = { shortlistViewModel.movePropertyDown(it) },
            onUpdatePreviewText = { shortlistViewModel.updatePreviewText(it) },
            onResetPreviewText = { shortlistViewModel.resetPreviewText() }
        )
    }
}

@Composable
internal fun MatchTabButton(title: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = if (isSelected) PrimaryActionOrange else SurfaceMuted,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (isSelected) PrimaryActionOrange else BorderGrey),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                text = title,
                color = if (isSelected) OnPrimaryActionColor else TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun MatchResultCard(
    item: MatchedPropertyItem,
    isSelected: Boolean,
    onSelectSourceListing: (String) -> Unit = {},
    onToggleSelect: () -> Unit
) {
    val prop = item.property
    val stdTx = StandardTransactionType.fromString(prop.transactionType, prop.tab)
    val isRental = stdTx == StandardTransactionType.LEASE
    val primaryAmt = CalculationUtils.primaryAmountForProperty(prop)
    val priceText = if (primaryAmt > 0.0) {
        CalculationUtils.formatIndianCurrency(primaryAmt, isMonthly = isRental)
    } else {
        "Price Pending"
    }
    var showWhyExpanded by remember { mutableStateOf(false) }

    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isSelected) PrimaryActionOrange else BorderGrey),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = prop.projectName.ifBlank { prop.propertyType.ifBlank { "Project Not Mentioned" } },
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.matchScorePercent != null) {
                            Surface(
                                color = SelectedSurfaceContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "${item.matchScorePercent}% Match",
                                    color = PrimaryActionOrange,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "${prop.location.ifBlank { "Location Not Mentioned" }} · Code: ${prop.propertyCode.ifBlank { prop.id }}",
                        color = SecondaryText,
                        fontSize = 11.5.sp
                    )
                }

                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = PrimaryActionOrange, checkmarkColor = OnPrimaryActionColor)
                )
            }

            // Linked Source Listings Selector when multiple listings share the same physical unit
            if (item.sourceListings.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Unit Sources (${item.sourceListings.size}):", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = SecondaryText)
                    item.sourceListings.forEachIndexed { idx, srcProp ->
                        val selectedSrc = srcProp.id == item.selectedSourceListingId
                        FilterChip(
                            selected = selectedSrc,
                            onClick = { onSelectSourceListing(srcProp.id) },
                            label = {
                                Text(
                                    text = "Source ${idx + 1}: ${srcProp.propertyCode.ifBlank { srcProp.id }}",
                                    fontSize = 10.5.sp
                                )
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val areaLabel = if (prop.carpetAreaSqFt > 0.0) {
                    "${CalculationUtils.formatNumber(prop.carpetAreaSqFt)} sq.ft Carpet"
                } else if ((prop.builtUpAreaSqFt ?: 0.0) > 0.0) {
                    "${CalculationUtils.formatNumber(prop.builtUpAreaSqFt ?: 0.0)} sq.ft Built-up"
                } else {
                    "Area Pending"
                }
                Text(
                    text = "$priceText · $areaLabel",
                    color = PrimaryActionOrange,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (item.isRecheckDue) {
                    Surface(color = StatusCheckAvailability.copy(alpha = 0.16f), shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = "Re-verification Due (60d+)",
                            color = StatusCheckAvailability,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else if (prop.verificationStatus == VerificationStatus.VERIFIED) {
                    Surface(color = StatusAvailable.copy(alpha = 0.16f), shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = "Verified",
                            color = StatusAvailable,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Why This Result? Explainable summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceMuted)
                    .clickable { showWhyExpanded = !showWhyExpanded }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (item.isExact) "Matches all confirmed criteria" else "Why This Result? (${item.matchReasons.size} matched, ${item.deviationReasons.size + item.missingCriteria.size} differences)",
                    color = if (item.isExact) StatusAvailable else TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (showWhyExpanded) "Hide ▲" else "Why? ▼",
                    color = PrimaryActionOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (showWhyExpanded || !item.isExact) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    item.matchReasons.forEach { reason ->
                        Text("✓ $reason", color = StatusAvailable, fontSize = 11.sp)
                    }
                    item.deviationReasons.forEach { dev ->
                        Text("• Relaxed: $dev", color = StatusCheckAvailability, fontSize = 11.sp)
                    }
                    item.missingCriteria.forEach { miss ->
                        Text("• Missing data: $miss (0 pts)", color = StatusNotAvailable, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CriteriaEditorSheet(
    initial: CrmMatchCriteria,
    onDismiss: () -> Unit,
    onApply: (CrmMatchCriteria) -> Unit
) {
    var brand by remember { mutableStateOf(initial.brand ?: Brand.COMMERCIAL) }
    var transactionType by remember { mutableStateOf(initial.transactionType) }
    var propertyType by remember { mutableStateOf(initial.propertyType) }
    var locationStr by remember { mutableStateOf(initial.preferredLocations.joinToString(", ")) }
    var minBudgetStr by remember { mutableStateOf(initial.minBudget?.toLong()?.toString().orEmpty()) }
    var maxBudgetStr by remember { mutableStateOf(initial.maxBudget?.toLong()?.toString().orEmpty()) }
    var minCarpetStr by remember { mutableStateOf(initial.minCarpetSqFt?.toInt()?.toString().orEmpty()) }
    var maxCarpetStr by remember { mutableStateOf(initial.maxCarpetSqFt?.toInt()?.toString().orEmpty()) }
    var areaBasis by remember { mutableStateOf(initial.areaBasis) }
    var flexibilityPercent by remember { mutableIntStateOf(initial.flexibilityPercent) }
    var includeNearby by remember { mutableStateOf(initial.includeNearbyAlternatives) }
    var validationError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundSoft
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding()
                .imePadding()
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Edit Requirement Matching Criteria", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Refine division, transaction, area basis, and flexibility (0–25%)", fontSize = 11.5.sp, color = SecondaryText)

            HorizontalDivider(color = BorderGrey)

            Text("Business Division", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipItem(
                    label = "Commercial",
                    isSelected = brand == Brand.COMMERCIAL,
                    onClick = { brand = Brand.COMMERCIAL }
                )
                FilterChipItem(
                    label = "Residential",
                    isSelected = brand == Brand.RESIDENTIAL,
                    onClick = { brand = Brand.RESIDENTIAL }
                )
            }

            Text("Transaction Type", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Lease", "Sale", "Pre-Leased", "All").forEach { tx ->
                    FilterChipItem(label = tx, isSelected = transactionType == tx, onClick = { transactionType = tx })
                }
            }

            OutlinedTextField(
                value = propertyType,
                onValueChange = { propertyType = it },
                label = { Text("Property Type (Office, Showroom, Shop, Apartment, Villa...)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = locationStr,
                onValueChange = { locationStr = it },
                label = { Text("Preferred Locations (comma separated)") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minBudgetStr,
                    onValueChange = { minBudgetStr = it },
                    label = { Text("Min Budget (₹)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = maxBudgetStr,
                    onValueChange = { maxBudgetStr = it },
                    label = { Text("Max Budget (₹)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Text("Area Basis", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RequirementAreaBasis.entries.forEach { basis ->
                    FilterChipItem(
                        label = basis.label,
                        isSelected = areaBasis == basis,
                        onClick = { areaBasis = basis }
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minCarpetStr,
                    onValueChange = { minCarpetStr = it },
                    label = { Text("Min Area (sq.ft)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = maxCarpetStr,
                    onValueChange = { maxCarpetStr = it },
                    label = { Text("Max Area (sq.ft)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Text("Flexibility Tolerance (%)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 5, 10, 15, 20, 25).forEach { pct ->
                    FilterChipItem(
                        label = "±$pct%",
                        isSelected = flexibilityPercent == pct,
                        onClick = { flexibilityPercent = pct }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = includeNearby,
                    onCheckedChange = { includeNearby = it },
                    colors = CheckboxDefaults.colors(checkedColor = PrimaryActionOrange, checkmarkColor = OnPrimaryActionColor)
                )
                Spacer(Modifier.width(6.dp))
                Text("Include nearby micro-market alternatives", fontSize = 12.5.sp, color = TextPrimary)
            }

            validationError?.let { err ->
                Text(err, color = StatusNotAvailable, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Button(
                    onClick = {
                        val minB = minBudgetStr.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
                        val maxB = maxBudgetStr.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
                        val minA = minCarpetStr.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
                        val maxA = maxCarpetStr.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()

                        if ((minB != null && minB <= 0.0) || (maxB != null && maxB <= 0.0)) {
                            validationError = "Budget amounts must be finite positive numbers."
                            return@Button
                        }
                        if (minB != null && maxB != null && minB > maxB) {
                            validationError = "Minimum budget cannot exceed maximum budget."
                            return@Button
                        }
                        if ((minA != null && minA <= 0.0) || (maxA != null && maxA <= 0.0)) {
                            validationError = "Area values must be finite positive numbers."
                            return@Button
                        }
                        if (minA != null && maxA != null && minA > maxA) {
                            validationError = "Minimum area cannot exceed maximum area."
                            return@Button
                        }

                        validationError = null
                        onApply(
                            initial.copy(
                                brand = brand,
                                transactionType = transactionType,
                                propertyType = propertyType.trim(),
                                preferredLocations = locationStr.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                minBudget = minB,
                                maxBudget = maxB,
                                minCarpetSqFt = minA,
                                maxCarpetSqFt = maxA,
                                areaBasis = areaBasis,
                                flexibilityPercent = flexibilityPercent,
                                includeNearbyAlternatives = includeNearby
                            )
                        )
                    },
                    modifier = Modifier.weight(1.5f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Show Matches", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
