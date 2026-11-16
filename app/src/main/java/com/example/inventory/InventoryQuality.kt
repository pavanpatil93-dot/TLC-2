package com.example.inventory

import com.example.model.*
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SourceBadgeType(val label: String) {
    OWNER("OWNER"),
    DEVELOPER("DEVELOPER"),
    CP_BROKER("CP / BROKER")
}

data class SourceBadgeInfo(
    val type: SourceBadgeType,
    val authorizedSourceName: String?,
    val displayText: String
) {
    val badgeLabel: String get() = type.label
    val displayName: String get() = authorizedSourceName ?: "Protected"
    val summaryLine: String get() = displayText
}

enum class CompletenessColorTier(val hexColor: Long, val label: String) {
    RED(0xFFEF4444, "Needs Info"),
    AMBER(0xFFF59E0B, "Partial"),
    GREEN(0xFF10B981, "Complete")
}

data class PropertyCompletenessReport(
    val percentage: Int,
    val completedFields: List<String>,
    val missingFields: List<String>,
    val colorTier: CompletenessColorTier
)

enum class MatchTolerance(val fraction: Double, val label: String) {
    EXACT(0.0, "Exact"),
    TOL_15(0.15, "15%"),
    TOL_20(0.20, "20%"),
    TOL_25(0.25, "25%"),
    TOL_50(0.50, "50%");

    fun expandedMin(minVal: Double): Double =
        if (minVal <= 0.0) 0.0 else (minVal * (1.0 - fraction)).coerceAtLeast(0.0)

    fun expandedMax(maxVal: Double, respectHardCeiling: Boolean = false): Double {
        if (maxVal >= Double.MAX_VALUE / 2) return Double.MAX_VALUE
        return if (respectHardCeiling) maxVal else maxVal * (1.0 + fraction)
    }
}

enum class RangeMatchTier {
    EXACT_MATCH,
    APPROXIMATE_MATCH,
    NO_MATCH
}

/** Missing evidence never counts as a duplicate factor. Nothing is automatically merged. */
object InventoryQuality {

    private val PLACEHOLDER_STRINGS = setOf(
        "", "not mentioned", "notmentioned", "n/a", "na", "none", "null", "unknown", "-", "tbd", "0"
    )

    fun isMeaningfulText(value: String?): Boolean {
        val cleaned = value?.trim()?.lowercase() ?: return false
        return cleaned.isNotEmpty() && cleaned !in PLACEHOLDER_STRINGS
    }

    fun isMeaningfulPhone(value: String?): Boolean {
        if (!isMeaningfulText(value)) return false
        val digits = value!!.filter { it.isDigit() }
        return digits.length >= 10 &&
            digits != "0000000000" &&
            digits != "9800000000" &&
            digits.toSet().size > 1
    }

    /**
     * Strict 100% minimum required details required to promote an archived property to Verified & Live.
     */
    fun minimumRequiredMissingFields(property: PropertyItem): List<String> = buildList {
        val stdType = StandardPropertyType.fromString(property.propertyType)
        val isPlot = stdType == StandardPropertyType.COMMERCIAL_LAND || stdType == StandardPropertyType.RESIDENTIAL_PLOT
        if (!isMeaningfulText(property.projectName)) add("Project / Building")
        if (!isMeaningfulText(property.location)) add("Location")
        if (!isMeaningfulText(property.propertyType)) add("Property Type")
        if (!isMeaningfulText(property.transactionType) && property.tab.code.isBlank()) add("Transaction Type")
        if (!isPlot && !isMeaningfulText(property.unitNumber) && !isMeaningfulText(property.floor)) {
            add("Unit / Floor")
        }
        if (property.carpetAreaSqFt <= 0.0) add("Carpet Area")
        if (CalculationUtils.primaryAmountForProperty(property) <= 0.0) {
            val stdTx = StandardTransactionType.fromString(property.transactionType, property.tab)
            add(if (stdTx == StandardTransactionType.LEASE) "Monthly Rent" else "Asking Price")
        }
        if (property.status == com.example.model.AvailabilityStatus.NOT_CONFIRMED) add("Availability Status")
        if (!isMeaningfulText(property.possession)) add("Possession")
        val hasSource = isMeaningfulText(property.ownerName) ||
            isMeaningfulText(property.brokerName) ||
            isMeaningfulText(property.builderName) ||
            isMeaningfulText(property.sourceInfo)
        if (!hasSource) add("Source / Owner / POC")
        val hasPhone = isMeaningfulPhone(property.ownerContact) || isMeaningfulPhone(property.brokerContact)
        if (!hasPhone) add("Contact Number")
    }

    fun isReadyForLiveVerification(property: PropertyItem): Boolean =
        minimumRequiredMissingFields(property).isEmpty()

    fun date(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    fun validDates(p: PropertyItem): Boolean = listOf(
        p.leaseExpiryDate,
        p.upcomingVacancyDate,
        p.lastPriceConfirmationDate
    ).all { it.isBlank() || date(it) != null }

    fun upcoming(p: PropertyItem, today: LocalDate = LocalDate.now(), horizonDays: Long = 90): Boolean {
        if (!p.tab.isCommercial || p.status != com.example.model.AvailabilityStatus.LEASED || p.archiveStatus == ArchiveStatus.ARCHIVED) return false
        return listOf(p.upcomingVacancyDate, p.leaseExpiryDate).mapNotNull(::date)
            .any { !it.isBefore(today) && !it.isAfter(today.plusDays(horizonDays)) }
    }

    fun missing(p: PropertyItem): List<String> = buildList {
        if (p.projectName.isBlank()) add("Project")
        if (p.location.isBlank()) add("Location")
        if (p.unitNumber.isBlank()) add("Unit")
        if (p.carpetAreaSqFt <= 0) add("Carpet area")
        if (p.builtUpAreaSqFt == null || p.builtUpAreaSqFt <= 0) add("Built-up area")
        if (p.priceOrRent <= 0) add("Price / rent")
        if (p.ownerContact.isNullOrBlank()) add("Owner contact")
        if (p.images.isEmpty()) add("Photos")
        if (p.possession.isBlank()) add("Possession")
        if (p.lastVerifiedDateClean.isBlank()) add("Verification date")
    }

    fun completeness(p: PropertyItem): Int = 100 - missing(p).size * 10

    fun shown(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "Not Mentioned"

    /**
     * Resolves Source Type (OWNER, DEVELOPER, CP / BROKER) and actual recorded name (if authorized).
     * Never invents a source name and hides confidential names when [isAuthorized] is false.
     */
    fun resolveSourceInfo(property: PropertyItem, isAuthorized: Boolean): SourceBadgeInfo {
        val rawSource = property.sourceInfo?.trim().orEmpty()
        val srcLower = rawSource.lowercase()
        val hasBroker = isMeaningfulText(property.brokerName)
        val hasBuilder = isMeaningfulText(property.builderName)
        val hasOwner = isMeaningfulText(property.ownerName)

        val badgeType = when {
            srcLower.contains("developer") || srcLower.contains("builder") ||
                (hasBuilder && !hasOwner && !hasBroker) -> SourceBadgeType.DEVELOPER
            srcLower.contains("broker") || srcLower.contains("cp") ||
                srcLower.contains("channel") || srcLower.contains("agent") || hasBroker -> SourceBadgeType.CP_BROKER
            else -> SourceBadgeType.OWNER
        }

        val actualName: String? = when (badgeType) {
            SourceBadgeType.DEVELOPER -> property.builderName?.trim()?.takeIf { isMeaningfulText(it) }
                ?: property.ownerName?.trim()?.takeIf { isMeaningfulText(it) }
            SourceBadgeType.CP_BROKER -> property.brokerName?.trim()?.takeIf { isMeaningfulText(it) }
                ?: property.ownerName?.trim()?.takeIf { isMeaningfulText(it) }
            SourceBadgeType.OWNER -> property.ownerName?.trim()?.takeIf { isMeaningfulText(it) }
        }

        val safeName = if (isAuthorized) actualName else null
        val display = if (safeName != null) {
            "Source: ${badgeType.label.lowercase().replaceFirstChar { it.uppercase() }} – $safeName"
        } else {
            "Source: ${badgeType.label}"
        }
        return SourceBadgeInfo(
            type = badgeType,
            authorizedSourceName = safeName,
            displayText = display
        )
    }

    /**
     * Type-aware, verification-independent property data completeness evaluation (Group 02 Point 2.D).
     * Evaluates only fields applicable to the specific property type and transaction type.
     */
    fun completenessReport(property: PropertyItem): PropertyCompletenessReport {
        val stdType = StandardPropertyType.fromString(property.propertyType)
        val stdTx = StandardTransactionType.fromString(property.transactionType, property.tab)
        val isPlot = stdType == StandardPropertyType.COMMERCIAL_LAND || stdType == StandardPropertyType.RESIDENTIAL_PLOT
        val isOffice = stdType == StandardPropertyType.OFFICE
        val isShopOrShowroom = stdType == StandardPropertyType.SHOP || stdType == StandardPropertyType.SHOWROOM
        val isResidentialUnit = stdType == StandardPropertyType.APARTMENT ||
            stdType == StandardPropertyType.VILLA ||
            stdType == StandardPropertyType.BUNGALOW

        val completed = mutableListOf<String>()
        val missing = mutableListOf<String>()

        fun checkField(label: String, isPresent: Boolean) {
            if (isPresent) completed.add(label) else missing.add(label)
        }

        // 1. Core Identification & Location (Applicable to all property types)
        checkField("Project / Building", isMeaningfulText(property.projectName))
        checkField("Property Type", isMeaningfulText(property.propertyType))
        checkField("Transaction Type", isMeaningfulText(property.transactionType) || property.tab.code.isNotBlank())
        checkField("Location", isMeaningfulText(property.location))

        // 2. Unit / Floor (Not required for raw land/plots)
        if (!isPlot) {
            checkField(
                "Unit / Floor",
                isMeaningfulText(property.unitNumber) || isMeaningfulText(property.floor)
            )
        }

        // 3. Area Measurements
        if (isPlot) {
            checkField(
                "Plot Area",
                property.carpetAreaSqFt > 0.0 || (property.builtUpAreaSqFt ?: 0.0) > 0.0
            )
        } else {
            checkField("Carpet Area", property.carpetAreaSqFt > 0.0)
            checkField("Built-up Area", (property.builtUpAreaSqFt ?: 0.0) > 0.0)
        }

        // 4. Primary Financials & Transaction-Specific Financials
        val primaryAmount = CalculationUtils.primaryAmountForProperty(property)
        checkField(
            if (stdTx == StandardTransactionType.LEASE) "Monthly Rent" else "Asking Price",
            primaryAmount > 0.0
        )
        when (stdTx) {
            StandardTransactionType.LEASE -> {
                checkField("Security Deposit", (property.deposit ?: 0.0) > 0.0)
            }
            StandardTransactionType.PRE_LEASED_SALE -> {
                checkField(
                    "Pre-Leased Rental / ROI",
                    (property.monthlyRentalIncome ?: property.rent ?: 0.0) > 0.0 || (property.roiPercent ?: 0.0) > 0.0
                )
            }
            StandardTransactionType.SALE -> {
                // Do not penalize a sale-only property for missing rental deposit
            }
        }

        // 5. Availability & Possession
        checkField("Availability Status", property.status != com.example.model.AvailabilityStatus.NOT_CONFIRMED)
        checkField("Possession", isMeaningfulText(property.possession))

        // 6. Property-Type Specific Specifications (Never penalize plots for workstations or apartments for frontage)
        if (!isPlot) {
            checkField("Furnishing", isMeaningfulText(property.furnishing.label))
        }
        if (isShopOrShowroom) {
            checkField(
                "Frontage / Ceiling Height",
                (property.frontageFeet ?: 0.0) > 0.0 || (property.ceilingHeightFeet ?: 0.0) > 0.0 || property.isMainRoadFacing
            )
        } else if (isResidentialUnit && !property.propertyType.contains("BHK", ignoreCase = true)) {
            checkField("Configuration (BHK)", isMeaningfulText(property.bhk))
        }

        // 7. Source, Owner/POC & Contact
        checkField(
            "Source / Owner / POC",
            isMeaningfulText(property.ownerName) ||
                isMeaningfulText(property.brokerName) ||
                isMeaningfulText(property.builderName) ||
                isMeaningfulText(property.sourceInfo)
        )
        checkField(
            "Contact Number",
            isMeaningfulPhone(property.ownerContact) || isMeaningfulPhone(property.brokerContact)
        )

        // 8. Media & Google Maps Location
        checkField("Photographs", property.images.any { isMeaningfulText(it) })
        val hasValidCoords = property.lat != null && property.lng != null &&
            property.lat in -90.0..90.0 && property.lng in -180.0..180.0 &&
            !(property.lat == 0.0 && property.lng == 0.0)
        val hasValidLatLonFields = property.latitude != null && property.longitude != null &&
            property.latitude in -90.0..90.0 && property.longitude in -180.0..180.0 &&
            !(property.latitude == 0.0 && property.longitude == 0.0)
        checkField(
            "Google Maps Location",
            isMeaningfulText(property.googleMapsUrl) || isMeaningfulText(property.listingLink) || hasValidCoords || hasValidLatLonFields
        )

        val totalApplicable = completed.size + missing.size
        val pct = if (totalApplicable == 0) 0 else ((completed.size * 100.0) / totalApplicable).roundToInt().coerceIn(0, 100)
        val tier = when {
            pct < 50 -> CompletenessColorTier.RED
            pct < 80 -> CompletenessColorTier.AMBER
            else -> CompletenessColorTier.GREEN
        }
        return PropertyCompletenessReport(
            percentage = pct,
            completedFields = completed,
            missingFields = missing,
            colorTier = tier
        )
    }

    /**
     * Evaluates whether a numeric property value (price, rent, carpet area, built-up area)
     * matches a target range [minTarget, maxTarget] exactly or within [tolerance].
     */
    fun evaluateRangeMatch(
        value: Double,
        minTarget: Double,
        maxTarget: Double,
        tolerance: MatchTolerance,
        respectHardCeiling: Boolean = false
    ): RangeMatchTier {
        val hasMin = minTarget > 0.0
        val hasMax = maxTarget < Double.MAX_VALUE / 2 && maxTarget > 0.0
        if (!hasMin && !hasMax) return RangeMatchTier.EXACT_MATCH
        if (!value.isFinite() || value <= 0.0) return RangeMatchTier.NO_MATCH

        val exactMinOk = !hasMin || value >= minTarget
        val exactMaxOk = !hasMax || value <= maxTarget
        if (exactMinOk && exactMaxOk) return RangeMatchTier.EXACT_MATCH

        if (tolerance == MatchTolerance.EXACT) return RangeMatchTier.NO_MATCH

        val tolMin = if (hasMin) tolerance.expandedMin(minTarget) else 0.0
        val tolMax = if (hasMax) tolerance.expandedMax(maxTarget, respectHardCeiling) else Double.MAX_VALUE
        return if (value >= tolMin && value <= tolMax) {
            RangeMatchTier.APPROXIMATE_MATCH
        } else {
            RangeMatchTier.NO_MATCH
        }
    }

    /**
     * Finds genuinely relevant related properties from the existing inventory:
     * - Strictly matches propertyCategory (never suggests Residential when viewing Commercial or vice versa).
     * - Prioritizes same transaction type, same locality, same property type, and similar area/budget.
     */
    fun findRelatedProperties(
        property: PropertyItem,
        allProperties: List<PropertyItem>,
        maxCount: Int = 4
    ): List<PropertyItem> {
        val targetCat = property.propertyCategory
        val targetTx = StandardTransactionType.fromString(property.transactionType, property.tab)
        val targetType = StandardPropertyType.fromString(property.propertyType)
        val targetLoc = property.location.trim().lowercase()
        val targetArea = property.carpetAreaSqFt
        val targetPrice = CalculationUtils.primaryAmountForProperty(property)

        return allProperties
            .asSequence()
            .filter { candidate ->
                candidate.id != property.id &&
                    candidate.archiveStatus == ArchiveStatus.ACTIVE &&
                    candidate.propertyCategory == targetCat
            }
            .mapNotNull { candidate ->
                var score = 0
                val candTx = StandardTransactionType.fromString(candidate.transactionType, candidate.tab)
                val candType = StandardPropertyType.fromString(candidate.propertyType)
                val candLoc = candidate.location.trim().lowercase()
                val candArea = candidate.carpetAreaSqFt
                val candPrice = CalculationUtils.primaryAmountForProperty(candidate)

                if (candTx == targetTx) score += 4
                if (candType == targetType) score += 3
                if (targetLoc.isNotBlank() && candLoc.isNotBlank() &&
                    (candLoc.contains(targetLoc) || targetLoc.contains(candLoc))) {
                    score += 3
                }
                if (targetArea > 0.0 && candArea > 0.0) {
                    val ratio = candArea / targetArea
                    if (ratio in 0.6..1.5) score += 2
                }
                if (targetPrice > 0.0 && candPrice > 0.0) {
                    val ratio = candPrice / targetPrice
                    if (ratio in 0.6..1.5) score += 2
                }
                if (score >= 4) candidate to score else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(maxCount)
            .toList()
    }

    private fun norm(s: String?) = s.orEmpty().lowercase().replace(Regex("[^a-z0-9]"), "")
    private fun same(a: String?, b: String?) =
        norm(a).isNotEmpty() && norm(a) !in setOf("notmentioned", "unknown", "na", "none") && norm(a) == norm(b)

    fun factors(a: PropertyItem, b: PropertyItem): Map<String, Boolean> = linkedMapOf(
        "Property code" to same(a.propertyCode, b.propertyCode),
        "Project" to same(a.projectName, b.projectName),
        "Unit" to same(a.unitNumber, b.unitNumber),
        "Location" to same(a.location, b.location),
        "Floor" to same(a.floor, b.floor),
        "Carpet area" to (a.carpetAreaSqFt > 0 && b.carpetAreaSqFt > 0 && abs(a.carpetAreaSqFt - b.carpetAreaSqFt) <= 1),
        "Built-up area" to (a.builtUpAreaSqFt != null && b.builtUpAreaSqFt != null && a.builtUpAreaSqFt > 0 && b.builtUpAreaSqFt > 0 && abs(a.builtUpAreaSqFt - b.builtUpAreaSqFt) <= 1),
        "Owner contact" to same(a.ownerContact?.filter(Char::isDigit)?.takeLast(10), b.ownerContact?.filter(Char::isDigit)?.takeLast(10)),
        "Category" to (a.tab == b.tab)
    )

    fun suspected(a: PropertyItem, b: PropertyItem): Boolean {
        if (a.id == b.id) return false
        val f = factors(a, b)
        return f.getValue("Property code") ||
            (f.getValue("Project") && f.getValue("Unit")) ||
            (f.getValue("Project") && f.values.count { it } >= 6)
    }
}
