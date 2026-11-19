package com.example.inventory

import com.example.model.*
import com.example.model.AvailabilityStatus
import com.example.model.VerificationStatus
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe precomputed cache for 6,000+ PropertyItem rows.
 * Populated on background dispatchers during disk load, Sheet sync, and edits
 * so UI composition and LazyColumn/LazyVerticalGrid scrolling never run INR formatting,
 * completeness scoring, or string normalization on the Main thread.
 */
data class PropertyPrecomputedData(
    val recordVersion: Int,
    val updatedAt: String,
    val status: AvailabilityStatus,
    val verificationStatus: VerificationStatus,
    val archiveStatus: ArchiveStatus,
    val priceOrRent: Double,
    val priceSale: Double?,
    val rent: Double?,
    val carpetAreaSqFt: Double,
    val imagesSize: Int,
    val lastVerifiedDateClean: String,
    val lastVerificationDate: String,
    val pocType: String,
    val projectName: String,
    val location: String,
    val unitNumber: String,
    val ownerContact: String?,
    val brokerContact: String?,
    val computedDayEpoch: Long,
    val stdTx: StandardTransactionType,
    val primaryAmount: Double,
    val priceDisplay: String,
    val rateDisplay: String,
    val isStrictlyLive: Boolean,
    val requiredMissingFields: List<String>,
    val detailedCompleteness: CompletenessBreakdown,
    val verificationAgeState: VerificationAgeState,
    val isBuilderStock: Boolean,
    val searchBlobLower: String,
    val shortSubtitle: String
)

object PropertyPrecomputedCache {

    private val cache = ConcurrentHashMap<String, PropertyPrecomputedData>(8192)

    private fun matches(
        p: PropertyItem,
        e: PropertyPrecomputedData,
        todayEpoch: Long
    ): Boolean =
        e.computedDayEpoch == todayEpoch &&
            e.recordVersion == p.recordVersion &&
            e.updatedAt == p.updatedAt &&
            e.status == p.status &&
            e.verificationStatus == p.verificationStatus &&
            e.archiveStatus == p.archiveStatus &&
            e.priceOrRent == p.priceOrRent &&
            e.priceSale == p.priceSale &&
            e.rent == p.rent &&
            e.carpetAreaSqFt == p.carpetAreaSqFt &&
            e.imagesSize == p.images.size &&
            e.lastVerifiedDateClean == p.lastVerifiedDateClean &&
            e.lastVerificationDate == p.lastVerificationDate &&
            e.pocType == p.pocType &&
            e.projectName == p.projectName &&
            e.location == p.location &&
            e.unitNumber == p.unitNumber &&
            e.ownerContact == p.ownerContact &&
            e.brokerContact == p.brokerContact

    private fun computeEntry(p: PropertyItem, todayEpoch: Long): PropertyPrecomputedData {
        val stdTx = StandardTransactionType.fromString(p.transactionType, p.tab)
        val primaryAmount = CalculationUtils.primaryAmountForProperty(p)
        val priceDisplay = if (primaryAmount > 0.0) {
            CalculationUtils.formatIndianCurrency(
                amount = primaryAmount,
                isMonthly = stdTx == StandardTransactionType.LEASE
            )
        } else {
            "Price Pending"
        }
        val rateDisplay = CalculationUtils.formatRateForBasis(
            p,
            AreaRateBasis.CARPET,
            includeBasisSuffix = true
        )
        val requiredMissingFields = InventoryQuality.minimumRequiredMissingFields(p)
        val isStrictlyLive = InventoryQuality.isStrictlyLive(p)
        val detailedCompleteness = CalculationUtils.computeDetailedCompleteness(p)
        val verificationAgeState = getVerificationAgeState(p, validityDays = 60)
        val isBuilderStock = InventoryQuality.isBuilderProjectStock(p)

        val searchBlobLower = buildString(160) {
            if (p.projectName.isNotBlank()) append(p.projectName.lowercase(Locale.ENGLISH)).append('\n')
            if (p.location.isNotBlank()) append(p.location.lowercase(Locale.ENGLISH)).append('\n')
            if (p.propertyCode.isNotBlank()) append(p.propertyCode.lowercase(Locale.ENGLISH)).append('\n')
            if (p.unitNumber.isNotBlank()) append(p.unitNumber.lowercase(Locale.ENGLISH)).append('\n')
            if (p.propertyType.isNotBlank()) append(p.propertyType.lowercase(Locale.ENGLISH)).append('\n')
            if (!p.bhk.isNullOrBlank()) append(p.bhk.lowercase(Locale.ENGLISH)).append('\n')
            if (p.furnishing.label.isNotBlank()) append(p.furnishing.label.lowercase(Locale.ENGLISH)).append('\n')
            if (!p.ownerName.isNullOrBlank()) append(p.ownerName.lowercase(Locale.ENGLISH)).append('\n')
            if (!p.remarks.isNullOrBlank()) append(p.remarks.lowercase(Locale.ENGLISH))
        }

        val shortType = StandardPropertyType.fromString(p.propertyType)
            .label
            .ifBlank { p.propertyType.ifBlank { "Property" } }
        val shortTx = when (stdTx) {
            StandardTransactionType.LEASE -> "Lease"
            StandardTransactionType.PRE_LEASED_SALE -> "Pre-Leased"
            StandardTransactionType.SALE -> "Sale"
        }
        val shortLocation = p.location.substringBefore(",").trim()
        val shortSubtitle = listOf(shortType, shortTx, shortLocation)
            .filter { it.isNotBlank() }
            .joinToString(" • ")

        return PropertyPrecomputedData(
            recordVersion = p.recordVersion,
            updatedAt = p.updatedAt,
            status = p.status,
            verificationStatus = p.verificationStatus,
            archiveStatus = p.archiveStatus,
            priceOrRent = p.priceOrRent,
            priceSale = p.priceSale,
            rent = p.rent,
            carpetAreaSqFt = p.carpetAreaSqFt,
            imagesSize = p.images.size,
            lastVerifiedDateClean = p.lastVerifiedDateClean,
            lastVerificationDate = p.lastVerificationDate,
            pocType = p.pocType,
            projectName = p.projectName,
            location = p.location,
            unitNumber = p.unitNumber,
            ownerContact = p.ownerContact,
            brokerContact = p.brokerContact,
            computedDayEpoch = todayEpoch,
            stdTx = stdTx,
            primaryAmount = primaryAmount,
            priceDisplay = priceDisplay,
            rateDisplay = rateDisplay,
            isStrictlyLive = isStrictlyLive,
            requiredMissingFields = requiredMissingFields,
            detailedCompleteness = detailedCompleteness,
            verificationAgeState = verificationAgeState,
            isBuilderStock = isBuilderStock,
            searchBlobLower = searchBlobLower,
            shortSubtitle = shortSubtitle
        )
    }

    fun get(property: PropertyItem): PropertyPrecomputedData {
        val todayEpoch = LocalDate.now().toEpochDay()
        val existing = cache[property.id]
        if (existing != null && matches(property, existing, todayEpoch)) {
            return existing
        }
        val computed = computeEntry(property, todayEpoch)
        cache[property.id] = computed
        return computed
    }

    fun warmUp(properties: List<PropertyItem>) {
        if (properties.isEmpty()) return
        val todayEpoch = LocalDate.now().toEpochDay()
        for (prop in properties) {
            val existing = cache[prop.id]
            if (existing == null || !matches(prop, existing, todayEpoch)) {
                cache[prop.id] = computeEntry(prop, todayEpoch)
            }
        }
    }

    fun warmItem(property: PropertyItem) {
        val todayEpoch = LocalDate.now().toEpochDay()
        cache[property.id] = computeEntry(property, todayEpoch)
    }

    fun invalidate(propertyId: String) {
        cache.remove(propertyId)
    }

    fun clear() {
        cache.clear()
    }
}
