package com.example.inventory

import com.example.model.*
import com.example.model.AvailabilityStatus
import com.example.model.VerificationStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class OtherMatchTab(val label: String) {
    NEARBY_LOCATION("Nearby Location"),
    PRICE("Price"),
    SIZE("Size"),
    POSSESSION("Possession")
}

data class CriterionScore(
    val name: String,
    val weight: Double,
    val normalizedScore: Double,
    val explanation: String
)

data class RankedPropertyMatch(
    val property: PropertyItem,
    val matchPercentage: Int?,
    val sourcePriorityRank: Int,
    val isVerifiedBrokerageOpportunity: Boolean,
    val hasMandatePriority: Boolean,
    val breakdown: List<CriterionScore>,
    val primaryReason: String
)

data class OtherMatchItem(
    val property: PropertyItem,
    val matchPercentage: Int,
    val reason: String
)

data class PropertyMatchingResult(
    val primaryMatches: List<RankedPropertyMatch>,
    val otherMatchesByTab: Map<OtherMatchTab, List<OtherMatchItem>>,
    val hasActiveCriteria: Boolean
)

/**
 * Deterministic, transparent Smart Property Matching & Ranking Engine.
 * Independent of Android UI / Compose so it is directly unit-testable.
 */
object PropertyMatchingEngine {

    const val WEIGHT_PRICE = 30.0
    const val WEIGHT_SIZE = 25.0
    const val WEIGHT_LOCATION = 25.0
    const val WEIGHT_POSSESSION = 10.0
    const val WEIGHT_FURNISHING = 4.0
    const val WEIGHT_OFFICE_CONFIG = 4.0
    const val WEIGHT_PARKING = 2.0
    private const val WEIGHT_FLOOR = 3.0
    private const val WEIGHT_INVESTMENT = 8.0
    private const val WEIGHT_SEARCH_TEXT = 10.0
    private const val WEIGHT_SPECIFIC_SPECS = 4.0

    fun evaluate(
        properties: List<PropertyItem>,
        criteria: SmartFilterCriteria,
        userRole: UserRole = UserRole.ADMIN,
        allowedBrands: Set<Brand> = Brand.entries.toSet(),
        sortOption: InventorySortOption = InventorySortOption.HIGHEST_MATCH,
        referenceDateMillis: Long = System.currentTimeMillis()
    ): PropertyMatchingResult {
        val deduplicated = deduplicateProperties(properties)
        val hasActive = criteria.hasScoringCriteria()

        // Step 1: Mandatory constraints (Permissions, Brand, Transaction, Property Type, Verification, Availability, Source)
        val baseEligible = deduplicated.filter { prop ->
            passesMandatoryConstraints(
                property = prop,
                criteria = criteria,
                userRole = userRole,
                allowedBrands = allowedBrands,
                enforceLocationFilter = false
            )
        }

        // Step 2: Primary pool respects location filter (if selected) and text search filter (if non-blank)
        val primaryPool = baseEligible.filter { prop ->
            val locationOk = if (criteria.selectedLocations.isEmpty()) {
                true
            } else {
                PuneLocationAdjacency.classifyLocationMatch(prop.location, criteria.selectedLocations) == LocationMatchKind.EXACT
            }
            val searchOk = if (criteria.searchQuery.isBlank()) {
                true
            } else {
                matchesSearchText(prop, criteria.searchQuery)
            }
            locationOk && searchOk
        }

        val rankedPrimary = primaryPool.map { prop ->
            scoreProperty(prop, criteria, hasActive, referenceDateMillis)
        }

        val sortedPrimary = sortRankedMatches(rankedPrimary, sortOption, hasActive)
        val primaryIds = sortedPrimary.map { it.property.id }.toSet()

        // Step 3: Populate "Other Matching Properties" without ever duplicating primaryIds
        val otherTabs = buildOtherMatchingTabs(
            baseEligible = baseEligible,
            primaryIds = primaryIds,
            criteria = criteria,
            referenceDateMillis = referenceDateMillis
        )

        return PropertyMatchingResult(
            primaryMatches = sortedPrimary,
            otherMatchesByTab = otherTabs,
            hasActiveCriteria = hasActive
        )
    }

    fun deduplicateProperties(properties: List<PropertyItem>): List<PropertyItem> {
        val seenIds = HashSet<String>()
        val seenCodes = HashSet<String>()
        val result = ArrayList<PropertyItem>(properties.size)
        for (prop in properties) {
            val cleanId = prop.id.trim()
            if (cleanId.isNotEmpty() && !seenIds.add(cleanId)) continue
            val cleanCode = prop.propertyCode.trim().uppercase(Locale.ENGLISH)
            if (cleanCode.isNotEmpty() && !seenCodes.add(cleanCode)) continue
            result.add(prop)
        }
        return result
    }

    fun classifySource(property: PropertyItem): PropertySourceFilter {
        val src = property.sourceInfo?.lowercase().orEmpty()
        val hasBroker = !property.brokerName.isNullOrBlank() || !property.brokerContact.isNullOrBlank()
        val hasBuilder = !property.builderName.isNullOrBlank() || property.tab == InventoryTab.UND_CONST
        return when {
            src.contains("broker") || src.contains("cp") || src.contains("channel partner") || hasBroker ->
                PropertySourceFilter.BROKER_CP
            src.contains("developer") || src.contains("builder") || hasBuilder ->
                PropertySourceFilter.DEVELOPER
            else -> PropertySourceFilter.DIRECT_OWNER
        }
    }

    fun hasMandatePriority(property: PropertyItem): Boolean {
        val combined = "${property.sourceInfo.orEmpty()} ${property.internalRemarks.orEmpty()} ${property.remarks.orEmpty()}".lowercase()
        return combined.contains("mandate") || combined.contains("exclusive")
    }

    fun isVerifiedBrokerageOpportunity(property: PropertyItem): Boolean {
        if (property.verificationStatus != VerificationStatus.VERIFIED) return false
        val src = classifySource(property)
        return (src == PropertySourceFilter.DIRECT_OWNER || src == PropertySourceFilter.DEVELOPER) &&
            property.status == AvailabilityStatus.AVAILABLE &&
            property.priceOrRent > 0.0
    }

    fun sourceTieBreakRank(property: PropertyItem): Int {
        val src = classifySource(property)
        val mandate = hasMandatePriority(property)
        val verifiedBrokerage = isVerifiedBrokerageOpportunity(property)
        return when {
            verifiedBrokerage && mandate -> 0
            verifiedBrokerage -> 1
            (src == PropertySourceFilter.DIRECT_OWNER || src == PropertySourceFilter.DEVELOPER) && mandate -> 2
            src == PropertySourceFilter.DIRECT_OWNER || src == PropertySourceFilter.DEVELOPER -> 3
            else -> 4 // Broker / CP
        }
    }

    fun isUpcomingVacancy(property: PropertyItem): Boolean {
        return property.upcomingVacancyDate.isNotBlank() ||
            property.leaseExpiryDate.isNotBlank() ||
            property.status == AvailabilityStatus.ON_HOLD ||
            property.status == AvailabilityStatus.UNDER_NEGOTIATION ||
            property.status == AvailabilityStatus.TOKEN_RECEIVED
    }

    fun passesMandatoryConstraints(
        property: PropertyItem,
        criteria: SmartFilterCriteria,
        userRole: UserRole,
        allowedBrands: Set<Brand>,
        enforceLocationFilter: Boolean = true
    ): Boolean {
        if (property.archiveStatus == ArchiveStatus.ARCHIVED) return false

        // Role & Brand access
        val effectivePortfolio = property.effectivePortfolio()
        val propBrand = when (effectivePortfolio) {
            StandardPortfolio.COMMERCIAL -> Brand.COMMERCIAL
            StandardPortfolio.RESIDENTIAL -> Brand.RESIDENTIAL
            null -> property.tab.brand
        }
        val roleAllowedBrands = when (userRole) {
            UserRole.COMMERCIAL_MANAGER -> setOf(Brand.COMMERCIAL)
            UserRole.RESIDENTIAL_MANAGER -> setOf(Brand.RESIDENTIAL)
            else -> allowedBrands
        }
        if (propBrand !in roleAllowedBrands) return false
        if (criteria.brand != null && propBrand != criteria.brand) return false

        // Property source filter
        if (criteria.selectedSources.isNotEmpty() && criteria.selectedSources.size < PropertySourceFilter.entries.size) {
            val source = classifySource(property)
            if (source !in criteria.selectedSources) return false
        }

        // Transaction / Inventory Section (preserves development stage separately from transaction)
        if (criteria.transactionSections.isNotEmpty()) {
            val propSection = classifyTransactionSection(property)
            if (propSection !in criteria.transactionSections) return false
        }

        // Property Type
        if (criteria.selectedPropertyTypes.isNotEmpty()) {
            if (!matchesPropertyType(property, criteria.selectedPropertyTypes)) return false
        }

        // Availability & Verification Gate
        when (criteria.availabilityFilter) {
            AvailabilityFilterOption.AVAILABLE -> {
                if (property.verificationStatus != VerificationStatus.VERIFIED) return false
                if (property.status != AvailabilityStatus.AVAILABLE) return false
            }
            AvailabilityFilterOption.UPCOMING -> {
                if (property.verificationStatus != VerificationStatus.VERIFIED) return false
                if (!isUpcomingVacancy(property)) return false
            }
            AvailabilityFilterOption.LEASED -> {
                if (property.verificationStatus != VerificationStatus.VERIFIED) return false
                if (property.status != AvailabilityStatus.LEASED) return false
            }
            AvailabilityFilterOption.SOLD -> {
                if (property.verificationStatus != VerificationStatus.VERIFIED) return false
                if (property.status != AvailabilityStatus.SOLD) return false
            }
            AvailabilityFilterOption.UNAVAILABLE -> {
                val isUnavailable = property.status == AvailabilityStatus.NOT_AVAILABLE ||
                    property.status == AvailabilityStatus.WITHDRAWN ||
                    property.status == AvailabilityStatus.SOLD ||
                    property.status == AvailabilityStatus.LEASED
                if (!isUnavailable) return false
            }
            AvailabilityFilterOption.ALL -> {
                // Still require verified status unless explicitly reviewing on Dashboard
                if (property.verificationStatus != VerificationStatus.VERIFIED) return false
            }
        }

        // Optional Location check
        if (enforceLocationFilter && criteria.selectedLocations.isNotEmpty()) {
            if (PuneLocationAdjacency.classifyLocationMatch(property.location, criteria.selectedLocations) != LocationMatchKind.EXACT) {
                return false
            }
        }

        return true
    }

    fun classifyTransactionSection(property: PropertyItem): TransactionFilterSection {
        return when (property.tab) {
            InventoryTab.UND_CONST -> TransactionFilterSection.UNDER_CONSTRUCTION
            InventoryTab.PRE_LEASED -> TransactionFilterSection.PRE_LEASED
            InventoryTab.OFF_LEASE, InventoryTab.SHOP_LEASE, InventoryTab.RESI_RENT -> TransactionFilterSection.LEASE
            InventoryTab.OFF_OUT, InventoryTab.SHOP_OUT, InventoryTab.RESI_RESALE -> TransactionFilterSection.SALE
        }
    }

    private fun matchesPropertyType(property: PropertyItem, selectedTypes: Set<String>): Boolean {
        val raw = "${property.propertyType} ${property.tab.code} ${property.bhk.orEmpty()}".lowercase()
        return selectedTypes.any { target ->
            val t = target.lowercase()
            when {
                t == "office" -> raw.contains("office") || property.tab == InventoryTab.OFF_LEASE || property.tab == InventoryTab.OFF_OUT
                t == "shop" -> raw.contains("shop") || property.tab == InventoryTab.SHOP_LEASE || property.tab == InventoryTab.SHOP_OUT
                t == "showroom" -> raw.contains("showroom")
                t == "retail" -> raw.contains("retail") || raw.contains("shop") || raw.contains("showroom")
                t == "restaurant" -> raw.contains("restaurant") || raw.contains("f&b") || raw.contains("cafe")
                t == "warehouse" -> raw.contains("warehouse") || raw.contains("godown")
                t.contains("commercial building") -> raw.contains("building") || raw.contains("it park") || raw.contains("commercial building")
                t.contains("land") || t.contains("plot") -> raw.contains("land") || raw.contains("plot")
                t == "apartment" -> raw.contains("apartment") || raw.contains("flat") || property.tab == InventoryTab.RESI_RENT || property.tab == InventoryTab.RESI_RESALE
                t.contains("villa") || t.contains("bungalow") -> raw.contains("villa") || raw.contains("bungalow") || raw.contains("row house")
                else -> raw.contains(t)
            }
        }
    }

    private fun matchesSearchText(property: PropertyItem, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val haystack = listOfNotNull(
            property.projectName,
            property.location,
            property.propertyCode,
            property.unitNumber,
            property.propertyType,
            property.bhk,
            property.furnishing.label,
            property.remarks
        ).joinToString(" ").lowercase()
        val tokens = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.all { haystack.contains(it) }
    }

    fun getAreaForBasis(property: PropertyItem, basis: AreaBasis): Double? {
        return when (basis) {
            AreaBasis.CARPET -> property.carpetAreaSqFt.takeIf { it > 0.0 }
            // Never auto-substitute estimated/calculated built-up area for real stored built-up
            AreaBasis.BUILT_UP -> property.builtUpAreaSqFt?.takeIf { it > 0.0 && !property.isAreaEstimated }
        }
    }

    fun getPriceForEvaluation(property: PropertyItem): Double? {
        val p = when {
            property.tab.isRental -> property.rent?.takeIf { it > 0.0 } ?: property.priceOrRent
            property.tab == InventoryTab.PRE_LEASED -> property.priceSale?.takeIf { it > 0.0 } ?: property.priceOrRent
            else -> property.priceSale?.takeIf { it > 0.0 } ?: property.priceOrRent
        }
        return p.takeIf { it > 0.0 }
    }

    fun scoreProperty(
        property: PropertyItem,
        criteria: SmartFilterCriteria,
        hasActiveCriteria: Boolean,
        referenceDateMillis: Long
    ): RankedPropertyMatch {
        val sourceRank = sourceTieBreakRank(property)
        val verifiedBrokerage = isVerifiedBrokerageOpportunity(property)
        val mandatePriority = hasMandatePriority(property)

        if (!hasActiveCriteria) {
            return RankedPropertyMatch(
                property = property,
                matchPercentage = null,
                sourcePriorityRank = sourceRank,
                isVerifiedBrokerageOpportunity = verifiedBrokerage,
                hasMandatePriority = mandatePriority,
                breakdown = emptyList(),
                primaryReason = "Verified active inventory"
            )
        }

        val scores = mutableListOf<CriterionScore>()

        // 1. Location score
        if (criteria.selectedLocations.isNotEmpty()) {
            val locKind = PuneLocationAdjacency.classifyLocationMatch(property.location, criteria.selectedLocations)
            val (s, reason) = when (locKind) {
                LocationMatchKind.EXACT -> 1.0 to "Exact location match (${property.location})"
                LocationMatchKind.NEARBY -> 0.62 to "Nearby East Pune micro-market (${property.location})"
                LocationMatchKind.OTHER -> 0.20 to "Outside selected micro-market (${property.location})"
                LocationMatchKind.MISSING -> 0.0 to "Location missing"
                LocationMatchKind.NONE_SELECTED -> 1.0 to ""
            }
            scores.add(CriterionScore("Location", WEIGHT_LOCATION, s, reason))
        }

        // 2. Price score
        if (criteria.pricePresetIndex > 0) {
            val preset = SmartFilterPresets.getPricePreset(criteria.pricePresetIndex, criteria.transactionSections)
            val propPrice = getPriceForEvaluation(property)
            val (s, reason) = scoreNumericRange(
                value = propPrice,
                preset = preset,
                flexibility = criteria.flexibility,
                unitLabel = "Price"
            )
            scores.add(CriterionScore("Price", WEIGHT_PRICE, s, reason))
        }

        // 3. Size score (exclusive Carpet vs Built-up basis; missing basis never gets 100%)
        if (criteria.sizePresetIndex > 0) {
            val preset = SmartFilterPresets.getSizePreset(criteria.sizePresetIndex)
            val propArea = getAreaForBasis(property, criteria.areaBasis)
            val (s, reason) = scoreNumericRange(
                value = propArea,
                preset = preset,
                flexibility = criteria.flexibility,
                unitLabel = criteria.areaBasis.label
            )
            scores.add(CriterionScore(criteria.areaBasis.label, WEIGHT_SIZE, s, reason))
        }

        // 4. Possession timeline score
        if (criteria.possessionPreset != PossessionPreset.ANY) {
            val (s, reason) = scorePossession(property, criteria, referenceDateMillis)
            scores.add(CriterionScore("Possession", WEIGHT_POSSESSION, s, reason))
        }

        // 5. Furnishing score
        if (criteria.selectedFurnishings.isNotEmpty() || criteria.plugAndPlayOnly) {
            val (s, reason) = scoreFurnishing(property, criteria)
            scores.add(CriterionScore("Furnishing", WEIGHT_FURNISHING, s, reason))
        }

        // 6. Floor score
        if (criteria.selectedFloor != FloorOption.ANY) {
            val (s, reason) = scoreFloor(property, criteria.selectedFloor)
            scores.add(CriterionScore("Floor", WEIGHT_FLOOR, s, reason))
        }

        // 7. Furnished Office Configuration (only applies to Commercial Office)
        if (criteria.officeConfig.isActive() && isOfficeProperty(property)) {
            val (s, reason) = scoreOfficeConfig(property, criteria.officeConfig)
            scores.add(CriterionScore("Office Config", WEIGHT_OFFICE_CONFIG, s, reason))
        }

        // 8. Investment criteria (only applies to Pre-Leased / investment listings)
        if (criteria.investmentFilter.isActive() && (property.tab == InventoryTab.PRE_LEASED || property.roiPercent != null)) {
            val (s, reason) = scoreInvestment(property, criteria.investmentFilter)
            scores.add(CriterionScore("Investment", WEIGHT_INVESTMENT, s, reason))
        }

        // 9. Property-specific specs (Main road, frontage, ceiling height, BHK)
        if (criteria.onlyMainRoadFacing || criteria.minFrontageFeet != null || criteria.minCeilingHeightFeet != null || !criteria.bhkFilter.isNullOrBlank()) {
            val (s, reason) = scoreSpecificSpecs(property, criteria)
            scores.add(CriterionScore("Specs", WEIGHT_SPECIFIC_SPECS, s, reason))
        }

        // 10. Search text relevance
        if (criteria.searchQuery.isNotBlank()) {
            val q = criteria.searchQuery.trim().lowercase()
            val s = when {
                property.propertyCode.equals(q, ignoreCase = true) ||
                    property.projectName.lowercase().contains(q) -> 1.0
                property.location.lowercase().contains(q) ||
                    property.unitNumber.lowercase().contains(q) -> 0.85
                matchesSearchText(property, q) -> 0.70
                else -> 0.0
            }
            scores.add(CriterionScore("Search", WEIGHT_SEARCH_TEXT, s, "Keyword match"))
        }

        // Zero-division guard & weight normalization across applicable criteria
        val totalWeight = scores.sumOf { it.weight }
        val pct = if (totalWeight <= 0.0) {
            null
        } else {
            val weightedSum = scores.sumOf { it.weight * it.normalizedScore.coerceIn(0.0, 1.0) }
            kotlin.math.floor((weightedSum / totalWeight) * 100.0).toInt().coerceIn(0, 100)
        }

        val topReason = scores.maxByOrNull { it.weight * it.normalizedScore }?.explanation
            ?.ifBlank { "Evaluated across ${scores.size} criteria" }
            ?: "Evaluated"

        return RankedPropertyMatch(
            property = property,
            matchPercentage = pct,
            sourcePriorityRank = sourceRank,
            isVerifiedBrokerageOpportunity = verifiedBrokerage,
            hasMandatePriority = mandatePriority,
            breakdown = scores,
            primaryReason = topReason
        )
    }

    private fun scoreNumericRange(
        value: Double?,
        preset: DiscreteRangePreset,
        flexibility: FlexibilityOption,
        unitLabel: String
    ): Pair<Double, String> {
        if (value == null || value <= 0.0) {
            return 0.0 to "$unitLabel not specified"
        }
        val baseMin = preset.minValue
        val baseMax = preset.maxValue

        // Within exact preset interval -> 1.0
        val inBase = value >= baseMin && (baseMax == null || value <= baseMax)
        if (inBase) return 1.0 to "$unitLabel within target preset"

        val (flexMin, flexMax) = SmartFilterPresets.expandBounds(preset, flexibility)
        val inFlex = value >= flexMin && (flexMax == null || value <= flexMax)

        // Compute normalized distance from base preset bounds
        val distanceRatio = when {
            value < baseMin && baseMin > 0.0 -> (baseMin - value) / baseMin
            baseMax != null && value > baseMax && baseMax > 0.0 -> (value - baseMax) / baseMax
            else -> 0.0
        }

        return if (inFlex && flexibility.fraction > 0.0) {
            // Smooth degradation inside flexibility tolerance: 0.90 -> 0.68
            val flexRatio = (distanceRatio / flexibility.fraction).coerceIn(0.0, 1.0)
            val score = (0.90 - 0.22 * flexRatio).coerceIn(0.65, 0.95)
            score to "$unitLabel within ${flexibility.label} flexibility"
        } else {
            // Smooth progressive decay outside tolerance: 0.60 down to 0.05
            val penaltyFactor = (distanceRatio / 1.5).coerceIn(0.0, 1.0)
            val score = max(0.0, 0.60 * (1.0 - penaltyFactor))
            score to "$unitLabel outside target range"
        }
    }

    private fun scorePossession(
        property: PropertyItem,
        criteria: SmartFilterCriteria,
        referenceDateMillis: Long
    ): Pair<Double, String> {
        val raw = property.possession.trim()
        if (raw.isBlank()) return 0.0 to "Possession date missing"

        val propDays = parsePossessionDays(raw, referenceDateMillis)
            ?: return 0.25 to "Possession: $raw"

        val targetMaxDays = when (criteria.possessionPreset) {
            PossessionPreset.IMMEDIATE -> 7
            PossessionPreset.WITHIN_30_DAYS -> 30
            PossessionPreset.ONE_TO_THREE_MONTHS -> 90
            PossessionPreset.THREE_TO_SIX_MONTHS -> 180
            PossessionPreset.SIX_TO_TWELVE_MONTHS -> 365
            PossessionPreset.CUSTOM_DATE -> parsePossessionDays(criteria.customPossessionDate, referenceDateMillis) ?: 90
            PossessionPreset.ANY -> return 1.0 to ""
        }

        if (propDays <= targetMaxDays) {
            return 1.0 to "Possession meets timeline ($raw)"
        }
        val excessDays = propDays - targetMaxDays
        return when {
            excessDays <= 30 -> 0.78 to "Possession ~1 month after target ($raw)"
            excessDays <= 90 -> 0.55 to "Possession ~3 months after target ($raw)"
            excessDays <= 180 -> 0.35 to "Possession later timeline ($raw)"
            else -> 0.15 to "Possession far from target ($raw)"
        }
    }

    internal fun parsePossessionDays(raw: String, referenceDateMillis: Long): Int? {
        val lower = raw.lowercase(Locale.ENGLISH).trim()
        if (lower.contains("immediate") || lower.contains("ready")) return 0

        val dayMatch = Regex("(\\d+)\\s*day").find(lower)
        if (dayMatch != null) return dayMatch.groupValues[1].toIntOrNull()

        val monthMatch = Regex("(\\d+)\\s*month").find(lower)
        if (monthMatch != null) return (monthMatch.groupValues[1].toIntOrNull() ?: 0) * 30

        val patterns = listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd MMM yyyy", "MMM yyyy", "MMMM yyyy")
        for (pattern in patterns) {
            val parsed: Date? = runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = false }.parse(raw.trim())
            }.getOrNull()
            if (parsed != null) {
                val diffMillis = parsed.time - referenceDateMillis
                val days = TimeUnit.MILLISECONDS.toDays(diffMillis).toInt()
                return max(0, days)
            }
        }

        val yearOnly = Regex("(20\\d{2})").find(lower)?.groupValues?.get(1)?.toIntOrNull()
        if (yearOnly != null) {
            val refYearStr = SimpleDateFormat("yyyy", Locale.ENGLISH).format(Date(referenceDateMillis))
            val refYear = refYearStr.toIntOrNull() ?: 2026
            val yearDiff = max(0, yearOnly - refYear)
            return yearDiff * 365
        }
        return null
    }

    private fun scoreFurnishing(property: PropertyItem, criteria: SmartFilterCriteria): Pair<Double, String> {
        val remarksLower = "${property.remarks.orEmpty()} ${property.internalRemarks.orEmpty()}".lowercase()
        val isPlugAndPlay = remarksLower.contains("plug") && remarksLower.contains("play")

        if (criteria.plugAndPlayOnly) {
            return when {
                isPlugAndPlay -> 1.0 to "Plug-and-Play ready"
                property.furnishing == FurnishingType.FULLY_FURNISHED -> 0.85 to "Fully furnished"
                property.furnishing == FurnishingType.SEMI_FURNISHED -> 0.45 to "Semi-furnished"
                else -> 0.15 to property.furnishing.label
            }
        }

        if (property.furnishing in criteria.selectedFurnishings) {
            return 1.0 to "Exact furnishing (${property.furnishing.label})"
        }
        val compatible = criteria.selectedFurnishings.any { sel ->
            (sel == FurnishingType.SEMI_FURNISHED && property.furnishing == FurnishingType.FULLY_FURNISHED) ||
                (sel == FurnishingType.WARM_SHELL && property.furnishing == FurnishingType.BARE_SHELL)
        }
        return if (compatible) {
            0.55 to "Adjacent furnishing (${property.furnishing.label})"
        } else {
            0.15 to "Different furnishing (${property.furnishing.label})"
        }
    }

    private fun scoreFloor(property: PropertyItem, target: FloorOption): Pair<Double, String> {
        val raw = "${property.floorLevel.orEmpty()} ${property.floor}".trim().lowercase()
        if (raw.isBlank()) return 0.0 to "Floor not specified"

        val matched = when (target) {
            FloorOption.ANY -> true
            FloorOption.BASEMENT -> raw.contains("basement") || raw == "b1" || raw == "b2"
            FloorOption.LOWER_GROUND -> raw.contains("lower ground") || raw == "lg"
            FloorOption.GROUND -> (raw.contains("ground") && !raw.contains("lower") && !raw.contains("upper")) || raw == "g" || raw == "0"
            FloorOption.UPPER_GROUND -> raw.contains("upper ground") || raw == "ug"
            FloorOption.FIRST -> raw.contains("1st") || Regex("\\b1\\b").containsMatchIn(raw)
            FloorOption.SECOND -> raw.contains("2nd") || Regex("\\b2\\b").containsMatchIn(raw)
            FloorOption.THIRD -> raw.contains("3rd") || Regex("\\b3\\b").containsMatchIn(raw)
            FloorOption.FOURTH_PLUS -> {
                val num = Regex("(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                (num != null && num >= 4) || raw.contains("high floor") || raw.contains("mid floor")
            }
        }
        return if (matched) 1.0 to "Floor match ($raw)" else 0.15 to "Floor: ${property.floor}"
    }

    private fun isOfficeProperty(property: PropertyItem): Boolean {
        return property.tab == InventoryTab.OFF_LEASE ||
            property.tab == InventoryTab.OFF_OUT ||
            property.tab == InventoryTab.PRE_LEASED ||
            property.propertyType.contains("office", ignoreCase = true)
    }

    private fun scoreOfficeConfig(property: PropertyItem, cfg: OfficeConfigurationFilter): Pair<Double, String> {
        val subScores = mutableListOf<Double>()
        val remarks = "${property.remarks.orEmpty()} ${property.amenities.joinToString(" ")}".lowercase()

        cfg.minWorkstations?.takeIf { it > 0 }?.let { minWs ->
            val actual = property.workstations
            subScores.add(if (actual == null || actual <= 0) 0.0 else (actual.toDouble() / minWs).coerceIn(0.0, 1.0))
        }
        cfg.minCabins?.takeIf { it > 0 }?.let { minCab ->
            val actual = property.cabins
            subScores.add(if (actual == null || actual <= 0) 0.0 else (actual.toDouble() / minCab).coerceIn(0.0, 1.0))
        }
        if (cfg.conferenceRoomRequired) {
            val count = property.conferenceRooms ?: 0
            subScores.add(if (count > 0 || remarks.contains("conference") || remarks.contains("boardroom")) 1.0 else 0.0)
        }
        if (cfg.receptionRequired) {
            subScores.add(if (remarks.contains("reception")) 1.0 else 0.0)
        }
        if (cfg.pantryRequired) {
            subScores.add(if (remarks.contains("pantry") || remarks.contains("cafeteria")) 1.0 else 0.0)
        }
        if (cfg.serverRoomRequired) {
            subScores.add(if (remarks.contains("server")) 1.0 else 0.0)
        }
        if (cfg.washroomRequired) {
            subScores.add(if (remarks.contains("washroom") || remarks.contains("toilet")) 1.0 else 0.0)
        }
        cfg.minParkingCount?.takeIf { it > 0 }?.let { minPark ->
            val num = Regex("(\\d+)").find(property.parking)?.groupValues?.get(1)?.toIntOrNull()
            subScores.add(if (num == null) 0.0 else (num.toDouble() / minPark).coerceIn(0.0, 1.0))
        }

        if (subScores.isEmpty()) return 1.0 to "Office specs matched"
        val avg = subScores.average()
        return avg to "Office configuration (${(avg * 100).roundToInt()}%)"
    }

    private fun scoreInvestment(property: PropertyItem, inv: InvestmentFilter): Pair<Double, String> {
        val subScores = mutableListOf<Double>()
        inv.minRoiPercent?.let { minRoi ->
            val actual = property.roiPercent
            subScores.add(if (actual == null || actual <= 0.0) 0.0 else (actual / minRoi).coerceIn(0.0, 1.0))
        }
        inv.minMonthlyRent?.let { minRent ->
            val actual = property.monthlyRentalIncome ?: property.rent
            subScores.add(if (actual == null || actual <= 0.0) 0.0 else (actual / minRent).coerceIn(0.0, 1.0))
        }
        inv.maxAskingPrice?.let { maxAsk ->
            val actual = getPriceForEvaluation(property)
            subScores.add(
                when {
                    actual == null -> 0.0
                    actual <= maxAsk -> 1.0
                    else -> max(0.0, 1.0 - ((actual - maxAsk) / maxAsk)).coerceIn(0.0, 1.0)
                }
            )
        }
        inv.tenantStatus?.takeIf { it.isNotBlank() }?.let { expectedTenant ->
            val tenant = property.tenantName.orEmpty().lowercase()
            val remarks = property.remarks.orEmpty().lowercase()
            subScores.add(
                when {
                    tenant.isBlank() && !remarks.contains("tenant") -> 0.0
                    tenant.contains(expectedTenant.lowercase()) || remarks.contains(expectedTenant.lowercase()) -> 1.0
                    else -> 0.3
                }
            )
        }
        inv.minLockInYears?.let { minLock ->
            val actual = property.lockInPeriodYears
            subScores.add(if (actual == null) 0.0 else (actual.toDouble() / minLock).coerceIn(0.0, 1.0))
        }
        inv.minLeaseTenureYears?.let { minTenure ->
            val actual = property.leaseDurationYears
            subScores.add(if (actual == null) 0.0 else (actual.toDouble() / minTenure).coerceIn(0.0, 1.0))
        }
        inv.minEscalationPercent?.let { minEsc ->
            val actual = property.escalationPercent
            subScores.add(if (actual == null) 0.0 else (actual / minEsc).coerceIn(0.0, 1.0))
        }
        if (inv.maintenanceIncludedOnly) {
            val remarks = property.remarks.orEmpty().lowercase()
            subScores.add(if (remarks.contains("maintenance included") || property.maintenance == 0.0) 1.0 else 0.0)
        }
        if (inv.rentStartedOnly) {
            val started = (property.monthlyRentalIncome ?: property.rent ?: 0.0) > 0.0 && !property.tenantName.isNullOrBlank()
            subScores.add(if (started) 1.0 else 0.0)
        }
        if (subScores.isEmpty()) return 1.0 to "Investment criteria checked"
        val avg = subScores.average()
        return avg to "Investment metrics (${(avg * 100).roundToInt()}%)"
    }

    private fun scoreSpecificSpecs(property: PropertyItem, criteria: SmartFilterCriteria): Pair<Double, String> {
        val subScores = mutableListOf<Double>()
        if (criteria.onlyMainRoadFacing) {
            subScores.add(if (property.isMainRoadFacing) 1.0 else 0.0)
        }
        criteria.minFrontageFeet?.let { minF ->
            val actual = property.frontageFeet
            subScores.add(if (actual == null || actual <= 0.0) 0.0 else (actual / minF).coerceIn(0.0, 1.0))
        }
        criteria.minCeilingHeightFeet?.let { minH ->
            val actual = property.ceilingHeightFeet
            subScores.add(if (actual == null || actual <= 0.0) 0.0 else (actual / minH).coerceIn(0.0, 1.0))
        }
        criteria.bhkFilter?.takeIf { it.isNotBlank() }?.let { bhk ->
            val actual = property.bhk.orEmpty()
            subScores.add(if (actual.isBlank()) 0.0 else if (actual.contains(bhk, ignoreCase = true)) 1.0 else 0.2)
        }
        if (subScores.isEmpty()) return 1.0 to ""
        return subScores.average() to "Property specification match"
    }

    private fun sortRankedMatches(
        matches: List<RankedPropertyMatch>,
        sortOption: InventorySortOption,
        hasActiveCriteria: Boolean
    ): List<RankedPropertyMatch> {
        val dateComparator = compareByDescending<RankedPropertyMatch> { parseVerificationMillis(it.property) }
        val tieBreakComparator = compareBy<RankedPropertyMatch>(
            { it.sourcePriorityRank },
            { -parseVerificationMillis(it.property) },
            { it.property.propertyCode }
        )

        return when (sortOption) {
            InventorySortOption.HIGHEST_MATCH -> {
                if (hasActiveCriteria) {
                    matches.sortedWith(
                        compareByDescending<RankedPropertyMatch> { it.matchPercentage ?: 0 }
                            .then(tieBreakComparator)
                    )
                } else {
                    matches.sortedWith(tieBreakComparator)
                }
            }
            InventorySortOption.PRICE_ASC -> matches.sortedWith(
                compareBy<RankedPropertyMatch> { getPriceForEvaluation(it.property) ?: Double.MAX_VALUE }
                    .then(tieBreakComparator)
            )
            InventorySortOption.PRICE_DESC -> matches.sortedWith(
                compareByDescending<RankedPropertyMatch> { getPriceForEvaluation(it.property) ?: 0.0 }
                    .then(tieBreakComparator)
            )
            InventorySortOption.AREA_DESC -> matches.sortedWith(
                compareByDescending<RankedPropertyMatch> { it.property.carpetAreaSqFt }
                    .then(tieBreakComparator)
            )
            InventorySortOption.RECENTLY_VERIFIED -> matches.sortedWith(
                dateComparator.then(compareBy { it.sourcePriorityRank })
            )
            InventorySortOption.CODE_ASC -> matches.sortedBy { it.property.propertyCode }
        }
    }

    internal fun parseVerificationMillis(property: PropertyItem): Long {
        val raw = property.lastVerifiedDateClean.ifBlank { property.lastVerificationDate }.trim()
        if (raw.isBlank()) return 0L
        val formats = listOf("dd MMM yyyy", "yyyy-MM-dd", "dd/MM/yyyy")
        for (fmt in formats) {
            val parsed = runCatching {
                SimpleDateFormat(fmt, Locale.ENGLISH).apply { isLenient = false }.parse(raw)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    private fun buildOtherMatchingTabs(
        baseEligible: List<PropertyItem>,
        primaryIds: Set<String>,
        criteria: SmartFilterCriteria,
        referenceDateMillis: Long
    ): Map<OtherMatchTab, List<OtherMatchItem>> {
        val nonPrimary = baseEligible.filter { it.id !in primaryIds }
        val scoredNonPrimary = nonPrimary.map { prop ->
            scoreProperty(prop, criteria, hasActiveCriteria = true, referenceDateMillis = referenceDateMillis)
        }

        // Tab 1: Nearby Location (verified adjacent micro-markets not in primary results)
        val nearbyItems = if (criteria.selectedLocations.isEmpty()) {
            emptyList()
        } else {
            scoredNonPrimary
                .filter { ranked ->
                    PuneLocationAdjacency.classifyLocationMatch(ranked.property.location, criteria.selectedLocations) == LocationMatchKind.NEARBY
                }
                .sortedWith(compareByDescending<RankedPropertyMatch> { it.matchPercentage ?: 0 }.thenBy { it.sourcePriorityRank })
                .map { ranked ->
                    val targetLocs = criteria.selectedLocations.joinToString(", ")
                    OtherMatchItem(
                        property = ranked.property,
                        matchPercentage = ranked.matchPercentage ?: 0,
                        reason = "Adjacent micro-market near $targetLocs (${ranked.property.location})"
                    )
                }
        }

        // Tab 2: Price (non-primary verified options matching target price budget)
        val priceItems = if (criteria.pricePresetIndex <= 0) {
            emptyList()
        } else {
            scoredNonPrimary
                .filter { ranked ->
                    val priceScore = ranked.breakdown.firstOrNull { it.name == "Price" }?.normalizedScore ?: 0.0
                    priceScore >= 0.65
                }
                .sortedWith(compareByDescending<RankedPropertyMatch> { it.matchPercentage ?: 0 }.thenBy { it.sourcePriorityRank })
                .map { ranked ->
                    OtherMatchItem(
                        property = ranked.property,
                        matchPercentage = ranked.matchPercentage ?: 0,
                        reason = "Matches target price in ${ranked.property.location}"
                    )
                }
        }

        // Tab 3: Size (non-primary verified options matching target area)
        val sizeItems = if (criteria.sizePresetIndex <= 0) {
            emptyList()
        } else {
            scoredNonPrimary
                .filter { ranked ->
                    val sizeScore = ranked.breakdown.firstOrNull { it.name == criteria.areaBasis.label }?.normalizedScore ?: 0.0
                    sizeScore >= 0.65
                }
                .sortedWith(compareByDescending<RankedPropertyMatch> { it.matchPercentage ?: 0 }.thenBy { it.sourcePriorityRank })
                .map { ranked ->
                    OtherMatchItem(
                        property = ranked.property,
                        matchPercentage = ranked.matchPercentage ?: 0,
                        reason = "Matches ${criteria.areaBasis.label.lowercase()} in ${ranked.property.location}"
                    )
                }
        }

        // Tab 4: Possession (non-primary verified options matching possession timeline)
        val possessionItems = if (criteria.possessionPreset == PossessionPreset.ANY) {
            emptyList()
        } else {
            scoredNonPrimary
                .filter { ranked ->
                    val posScore = ranked.breakdown.firstOrNull { it.name == "Possession" }?.normalizedScore ?: 0.0
                    posScore >= 0.70
                }
                .sortedWith(compareByDescending<RankedPropertyMatch> { it.matchPercentage ?: 0 }.thenBy { it.sourcePriorityRank })
                .map { ranked ->
                    OtherMatchItem(
                        property = ranked.property,
                        matchPercentage = ranked.matchPercentage ?: 0,
                        reason = "Matches possession (${ranked.property.possession}) in ${ranked.property.location}"
                    )
                }
        }

        return mapOf(
            OtherMatchTab.NEARBY_LOCATION to nearbyItems,
            OtherMatchTab.PRICE to priceItems,
            OtherMatchTab.SIZE to sizeItems,
            OtherMatchTab.POSSESSION to possessionItems
        )
    }
}
