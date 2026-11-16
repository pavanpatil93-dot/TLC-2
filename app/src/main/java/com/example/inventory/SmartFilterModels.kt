package com.example.inventory

import com.example.model.Brand
import com.example.model.FurnishingType
import kotlin.math.max

enum class PropertySourceFilter(val label: String) {
    DIRECT_OWNER("Direct Owner"),
    BROKER_CP("Broker / CP"),
    DEVELOPER("Developer")
}

enum class TransactionFilterSection(val label: String) {
    LEASE("Lease"),
    SALE("Sale"),
    UNDER_CONSTRUCTION("Under Construction"),
    PRE_LEASED("Pre-Leased")
}

enum class AreaBasis(val label: String) {
    CARPET("Carpet Area"),
    BUILT_UP("Built-up Area")
}

enum class FlexibilityOption(val label: String, val fraction: Double) {
    STANDARD("Standard", 0.0),
    PERCENT_5("5%", 0.05),
    PERCENT_10("10%", 0.10),
    PERCENT_15("15%", 0.15),
    PERCENT_25("25%", 0.25),
    PERCENT_50("50%", 0.50),
    PERCENT_100("100%", 1.00)
}

enum class PossessionPreset(val label: String, val maxDays: Int?) {
    ANY("Any", null),
    IMMEDIATE("Immediate", 0),
    WITHIN_30_DAYS("Within 30 Days", 30),
    ONE_TO_THREE_MONTHS("1–3 Months", 90),
    THREE_TO_SIX_MONTHS("3–6 Months", 180),
    SIX_TO_TWELVE_MONTHS("6–12 Months", 365),
    CUSTOM_DATE("Custom Date", null)
}

enum class FloorOption(val label: String) {
    ANY("Any"),
    BASEMENT("Basement"),
    LOWER_GROUND("Lower Ground"),
    GROUND("Ground"),
    UPPER_GROUND("Upper Ground"),
    FIRST("1st"),
    SECOND("2nd"),
    THIRD("3rd"),
    FOURTH_PLUS("4th+")
}

enum class AvailabilityFilterOption(val label: String) {
    AVAILABLE("Available"),
    UPCOMING("Upcoming / Future Available"),
    LEASED("Leased"),
    SOLD("Sold"),
    UNAVAILABLE("Unavailable"),
    ALL("All")
}

enum class InventorySortOption(val label: String) {
    HIGHEST_MATCH("Highest Match %"),
    PRICE_ASC("Price: Low to High"),
    PRICE_DESC("Price: High to Low"),
    AREA_DESC("Area: Largest"),
    RECENTLY_VERIFIED("Recently Verified"),
    CODE_ASC("Property Code")
}

data class DiscreteRangePreset(
    val index: Int,
    val label: String,
    val minValue: Double,
    val maxValue: Double?
) {
    val isAny: Boolean get() = index == 0
    val isBelowOnly: Boolean get() = minValue == 0.0 && maxValue != null
    val isOpenEndedTop: Boolean get() = minValue > 0.0 && maxValue == null
}

/**
 * Configurable 10-position discrete preset tables for Price (Lease vs Sale/Pre-Leased) and Size.
 */
object SmartFilterPresets {
    var leasePricePresets: List<DiscreteRangePreset> = listOf(
        DiscreteRangePreset(0, "Any Price", 0.0, null),
        DiscreteRangePreset(1, "Below ₹50,000 / mo", 0.0, 50_000.0),
        DiscreteRangePreset(2, "₹50,000 – ₹75,000 / mo", 50_000.0, 75_000.0),
        DiscreteRangePreset(3, "₹75,000 – ₹1,00,000 / mo", 75_000.0, 100_000.0),
        DiscreteRangePreset(4, "₹1,00,000 – ₹1,50,000 / mo", 100_000.0, 150_000.0),
        DiscreteRangePreset(5, "₹1,50,000 – ₹2,00,000 / mo", 150_000.0, 200_000.0),
        DiscreteRangePreset(6, "₹2,00,000 – ₹3,00,000 / mo", 200_000.0, 300_000.0),
        DiscreteRangePreset(7, "₹3,00,000 – ₹4,00,000 / mo", 300_000.0, 400_000.0),
        DiscreteRangePreset(8, "₹4,00,000 – ₹5,00,000 / mo", 400_000.0, 500_000.0),
        DiscreteRangePreset(9, "₹5,00,000+ / mo", 500_000.0, null)
    )

    var salePricePresets: List<DiscreteRangePreset> = listOf(
        DiscreteRangePreset(0, "Any Price", 0.0, null),
        DiscreteRangePreset(1, "Below ₹50 Lakh", 0.0, 5_000_000.0),
        DiscreteRangePreset(2, "₹50 Lakh – ₹1 Crore", 5_000_000.0, 10_000_000.0),
        DiscreteRangePreset(3, "₹1 Crore – ₹1.5 Crore", 10_000_000.0, 15_000_000.0),
        DiscreteRangePreset(4, "₹1.5 Crore – ₹2.5 Crore", 15_000_000.0, 25_000_000.0),
        DiscreteRangePreset(5, "₹2.5 Crore – ₹4 Crore", 25_000_000.0, 40_000_000.0),
        DiscreteRangePreset(6, "₹4 Crore – ₹6 Crore", 40_000_000.0, 60_000_000.0),
        DiscreteRangePreset(7, "₹6 Crore – ₹8 Crore", 60_000_000.0, 80_000_000.0),
        DiscreteRangePreset(8, "₹8 Crore – ₹10 Crore", 80_000_000.0, 100_000_000.0),
        DiscreteRangePreset(9, "₹10 Crore+", 100_000_000.0, null)
    )

    var sizePresets: List<DiscreteRangePreset> = listOf(
        DiscreteRangePreset(0, "Any Size", 0.0, null),
        DiscreteRangePreset(1, "Below 500 sq.ft", 0.0, 500.0),
        DiscreteRangePreset(2, "500 – 800 sq.ft", 500.0, 800.0),
        DiscreteRangePreset(3, "800 – 1,200 sq.ft", 800.0, 1_200.0),
        DiscreteRangePreset(4, "1,200 – 1,800 sq.ft", 1_200.0, 1_800.0),
        DiscreteRangePreset(5, "1,800 – 2,500 sq.ft", 1_800.0, 2_500.0),
        DiscreteRangePreset(6, "2,500 – 3,500 sq.ft", 2_500.0, 3_500.0),
        DiscreteRangePreset(7, "3,500 – 5,000 sq.ft", 3_500.0, 5_000.0),
        DiscreteRangePreset(8, "5,000 – 10,000 sq.ft", 5_000.0, 10_000.0),
        DiscreteRangePreset(9, "10,000+ sq.ft", 10_000.0, null)
    )

    fun pricePresetsFor(sections: Set<TransactionFilterSection>): List<DiscreteRangePreset> {
        val isPureLease = sections == setOf(TransactionFilterSection.LEASE)
        val includesLeaseOnly = sections.contains(TransactionFilterSection.LEASE) &&
            !sections.contains(TransactionFilterSection.SALE) &&
            !sections.contains(TransactionFilterSection.PRE_LEASED) &&
            !sections.contains(TransactionFilterSection.UNDER_CONSTRUCTION)
        return if (isPureLease || includesLeaseOnly) leasePricePresets else salePricePresets
    }

    fun getPricePreset(index: Int, sections: Set<TransactionFilterSection>): DiscreteRangePreset {
        val list = pricePresetsFor(sections)
        val clamped = index.coerceIn(0, list.lastIndex)
        return list[clamped]
    }

    fun getSizePreset(index: Int): DiscreteRangePreset {
        val clamped = index.coerceIn(0, sizePresets.lastIndex)
        return sizePresets[clamped]
    }

    fun expandBounds(
        preset: DiscreteRangePreset,
        flexibility: FlexibilityOption
    ): Pair<Double, Double?> {
        if (preset.isAny) return 0.0 to null
        val p = flexibility.fraction.coerceAtLeast(0.0)
        if (p == 0.0) return max(0.0, preset.minValue) to preset.maxValue

        val expandedMin = when {
            preset.isBelowOnly -> 0.0
            else -> max(0.0, preset.minValue * (1.0 - p))
        }
        val expandedMax = preset.maxValue?.let { max(0.0, it * (1.0 + p)) }
        return expandedMin to expandedMax
    }

    fun expandApproximateTarget(
        target: Double,
        flexibility: FlexibilityOption
    ): Pair<Double, Double> {
        val cleanTarget = max(0.0, target)
        val p = flexibility.fraction.coerceAtLeast(0.0)
        val minBound = max(0.0, cleanTarget * (1.0 - p))
        val maxBound = max(0.0, cleanTarget * (1.0 + p))
        return minBound to maxBound
    }
}

data class OfficeConfigurationFilter(
    val minWorkstations: Int? = null,
    val minCabins: Int? = null,
    val conferenceRoomRequired: Boolean = false,
    val minConferenceCapacity: Int? = null,
    val receptionRequired: Boolean = false,
    val pantryRequired: Boolean = false,
    val serverRoomRequired: Boolean = false,
    val washroomRequired: Boolean = false,
    val minParkingCount: Int? = null
) {
    fun isActive(): Boolean =
        (minWorkstations ?: 0) > 0 ||
            (minCabins ?: 0) > 0 ||
            conferenceRoomRequired ||
            (minConferenceCapacity ?: 0) > 0 ||
            receptionRequired ||
            pantryRequired ||
            serverRoomRequired ||
            washroomRequired ||
            (minParkingCount ?: 0) > 0
}

data class InvestmentFilter(
    val minRoiPercent: Double? = null,
    val minMonthlyRent: Double? = null,
    val maxAskingPrice: Double? = null,
    val tenantStatus: String? = null,
    val minLockInYears: Int? = null,
    val minLeaseTenureYears: Int? = null,
    val minEscalationPercent: Double? = null,
    val maintenanceIncludedOnly: Boolean = false,
    val rentStartedOnly: Boolean = false
) {
    fun isActive(): Boolean =
        minRoiPercent != null ||
            minMonthlyRent != null ||
            maxAskingPrice != null ||
            !tenantStatus.isNullOrBlank() ||
            minLockInYears != null ||
            minLeaseTenureYears != null ||
            minEscalationPercent != null ||
            maintenanceIncludedOnly ||
            rentStartedOnly
}

val COMMERCIAL_PROPERTY_TYPE_OPTIONS = listOf(
    "Office", "Shop", "Showroom", "Retail", "Restaurant",
    "Warehouse", "Commercial Building", "Land / Plot"
)

val RESIDENTIAL_PROPERTY_TYPE_OPTIONS = listOf(
    "Apartment", "Villa / Bungalow", "Residential Plot"
)

data class SmartFilterCriteria(
    val brand: Brand? = null,
    val selectedSources: Set<PropertySourceFilter> = PropertySourceFilter.entries.toSet(),
    val transactionSections: Set<TransactionFilterSection> = emptySet(),
    val selectedLocations: Set<String> = emptySet(),
    val selectedPropertyTypes: Set<String> = emptySet(),
    val pricePresetIndex: Int = 0,
    val sizePresetIndex: Int = 0,
    val areaBasis: AreaBasis = AreaBasis.CARPET,
    val flexibility: FlexibilityOption = FlexibilityOption.STANDARD,
    val possessionPreset: PossessionPreset = PossessionPreset.ANY,
    val customPossessionDate: String = "",
    val selectedFurnishings: Set<FurnishingType> = emptySet(),
    val plugAndPlayOnly: Boolean = false,
    val selectedFloor: FloorOption = FloorOption.ANY,
    val officeConfig: OfficeConfigurationFilter = OfficeConfigurationFilter(),
    val availabilityFilter: AvailabilityFilterOption = AvailabilityFilterOption.AVAILABLE,
    val investmentFilter: InvestmentFilter = InvestmentFilter(),
    val onlyMainRoadFacing: Boolean = false,
    val minFrontageFeet: Double? = null,
    val minCeilingHeightFeet: Double? = null,
    val bhkFilter: String? = null,
    val searchQuery: String = ""
) {
    fun isOfficeFurnishedContext(): Boolean {
        val officeSelected = selectedPropertyTypes.isEmpty() ||
            selectedPropertyTypes.any { it.equals("Office", ignoreCase = true) }
        val furnishedSelected = plugAndPlayOnly || selectedFurnishings.any {
            it == FurnishingType.SEMI_FURNISHED || it == FurnishingType.FULLY_FURNISHED
        }
        return (brand == null || brand == Brand.COMMERCIAL) && officeSelected && furnishedSelected
    }

    fun isPreLeasedContext(): Boolean =
        transactionSections.contains(TransactionFilterSection.PRE_LEASED)

    fun hasScoringCriteria(): Boolean =
        searchQuery.isNotBlank() ||
            selectedLocations.isNotEmpty() ||
            pricePresetIndex > 0 ||
            sizePresetIndex > 0 ||
            possessionPreset != PossessionPreset.ANY ||
            selectedFurnishings.isNotEmpty() ||
            plugAndPlayOnly ||
            selectedFloor != FloorOption.ANY ||
            officeConfig.isActive() ||
            investmentFilter.isActive() ||
            onlyMainRoadFacing ||
            minFrontageFeet != null ||
            minCeilingHeightFeet != null ||
            !bhkFilter.isNullOrBlank()

    fun activeFilterCount(): Int {
        var count = 0
        if (selectedSources != PropertySourceFilter.entries.toSet()) count++
        if (transactionSections.isNotEmpty()) count++
        if (selectedLocations.isNotEmpty()) count++
        if (selectedPropertyTypes.isNotEmpty()) count++
        if (pricePresetIndex > 0) count++
        if (sizePresetIndex > 0) count++
        if (flexibility != FlexibilityOption.STANDARD) count++
        if (possessionPreset != PossessionPreset.ANY) count++
        if (selectedFurnishings.isNotEmpty() || plugAndPlayOnly) count++
        if (selectedFloor != FloorOption.ANY) count++
        if (officeConfig.isActive()) count++
        if (availabilityFilter != AvailabilityFilterOption.AVAILABLE) count++
        if (investmentFilter.isActive() || onlyMainRoadFacing || minFrontageFeet != null || minCeilingHeightFeet != null || !bhkFilter.isNullOrBlank()) count++
        return count
    }
}
