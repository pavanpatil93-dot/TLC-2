package com.example.data

import com.example.model.InventoryTab
import com.example.model.PropertyItem
import com.example.model.escalation
import com.example.model.isCommercial
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

/**
 * Deterministic area, loading, dual-rate, and Pre-Leased Gross ROI calculations
 * mirroring the TLC Master Sheet rules for offline preview and form saves.
 */
data class TlcAreaCalculationResult(
    val groundFloorCarpetSqFt: Double?,
    val mezzanineCarpetSqFt: Double?,
    val totalCarpetSqFt: Double,
    val totalBuiltUpSqFt: Double?,
    val loadingPercent: Double?,
    val isBuiltUpAutoCalculated: Boolean,
    val isCarpetAutoCalculated: Boolean,
    val isManualBuiltUpOrLoadingOverride: Boolean
)

data class TlcDualRateResult(
    val ratePerCarpetSqFt: Double?,
    val ratePerBuiltUpSqFt: Double?
)

object TlcPropertyCalculator {

    const val OFFICE_DEFAULT_LOADING_RATIO = 0.45
    const val SHOP_SHOWROOM_DEFAULT_LOADING_RATIO = 0.50

    fun isShopOrShowroom(propertyType: String, tab: InventoryTab? = null): Boolean {
        val t = propertyType.trim().lowercase(Locale.ENGLISH)
        return t.contains("shop") ||
            t.contains("showroom") ||
            t.contains("retail") ||
            tab == InventoryTab.SHOP_LEASE ||
            tab == InventoryTab.SHOP_OUT
    }

    fun isWarehouseOrIndustrial(propertyType: String): Boolean {
        val t = propertyType.trim().lowercase(Locale.ENGLISH)
        return t.contains("warehouse") ||
            t.contains("industrial") ||
            t.contains("godown") ||
            t.contains("shed") ||
            t.contains("logistics")
    }

    fun isOffice(propertyType: String, tab: InventoryTab? = null): Boolean {
        if (isShopOrShowroom(propertyType, tab) || isWarehouseOrIndustrial(propertyType)) return false
        val t = propertyType.trim().lowercase(Locale.ENGLISH)
        return t.contains("office") ||
            t.contains("it") ||
            t.contains("commercial") ||
            tab == InventoryTab.OFF_LEASE ||
            tab == InventoryTab.OFF_OUT
    }

    fun defaultLoadingRatio(
        propertyType: String,
        isCommercial: Boolean,
        tab: InventoryTab? = null
    ): Double? {
        if (!isCommercial) return null
        return when {
            isShopOrShowroom(propertyType, tab) -> SHOP_SHOWROOM_DEFAULT_LOADING_RATIO
            isWarehouseOrIndustrial(propertyType) -> null
            else -> OFFICE_DEFAULT_LOADING_RATIO
        }
    }

    /**
     * Computes Total Carpet, Total Built-Up, and Loading % according to TLC Master Sheet rules:
     * - Shop/Showroom: Total Carpet = Ground Floor Carpet + Mezzanine Carpet (when provided).
     *   For Office / Warehouse / Residential, Mezzanine is forced to 0 / null.
     * - Only Carpet entered -> auto-calculates Built-Up with 45% (Office) or 50% (Shop/Showroom).
     * - Only Built-Up entered -> auto-calculates Carpet using the same ratio.
     * - Both explicitly entered -> computes actual Loading % = ((BuiltUp - Carpet) / Carpet) * 100
     *   and marks isManualBuiltUpOrLoadingOverride = true.
     */
    fun calculateAreaMetrics(
        groundFloorCarpet: Double?,
        mezzanineCarpet: Double?,
        directCarpet: Double?,
        builtUpInput: Double?,
        propertyType: String,
        isCommercial: Boolean,
        tab: InventoryTab? = null,
        manualLoadingPercentOverride: Double? = null
    ): TlcAreaCalculationResult {
        val shopOrShowroom = isCommercial && isShopOrShowroom(propertyType, tab)
        val cleanGround = groundFloorCarpet?.takeIf { it.isFinite() && it > 0.0 }
        val cleanMezz = if (shopOrShowroom) mezzanineCarpet?.takeIf { it.isFinite() && it > 0.0 } else null
        val cleanDirectCarpet = directCarpet?.takeIf { it.isFinite() && it > 0.0 }
        val cleanBuiltUp = builtUpInput?.takeIf { it.isFinite() && it > 0.0 }
        val cleanManualLoading = manualLoadingPercentOverride?.takeIf { it.isFinite() && it >= 0.0 }

        val enteredCarpet = if (shopOrShowroom && (cleanGround != null || cleanMezz != null)) {
            (cleanGround ?: 0.0) + (cleanMezz ?: 0.0)
        } else {
            cleanDirectCarpet ?: cleanGround ?: 0.0
        }

        val defaultRatio = defaultLoadingRatio(propertyType, isCommercial, tab)
        val effectiveRatio = cleanManualLoading?.div(100.0) ?: defaultRatio

        return when {
            enteredCarpet > 0.0 && cleanBuiltUp != null -> {
                val actualLoading = if (cleanBuiltUp >= enteredCarpet) {
                    round(((cleanBuiltUp - enteredCarpet) / enteredCarpet) * 1000.0) / 10.0
                } else 0.0
                TlcAreaCalculationResult(
                    groundFloorCarpetSqFt = if (shopOrShowroom) (cleanGround ?: enteredCarpet) else null,
                    mezzanineCarpetSqFt = if (shopOrShowroom) (cleanMezz ?: 0.0) else null,
                    totalCarpetSqFt = enteredCarpet,
                    totalBuiltUpSqFt = cleanBuiltUp,
                    loadingPercent = actualLoading,
                    isBuiltUpAutoCalculated = false,
                    isCarpetAutoCalculated = false,
                    isManualBuiltUpOrLoadingOverride = true
                )
            }
            enteredCarpet > 0.0 && cleanBuiltUp == null -> {
                val autoBuiltUp = effectiveRatio?.let { round(enteredCarpet * (1.0 + it)) }
                val loadingPct = effectiveRatio?.let { round(it * 1000.0) / 10.0 }
                TlcAreaCalculationResult(
                    groundFloorCarpetSqFt = if (shopOrShowroom) (cleanGround ?: enteredCarpet) else null,
                    mezzanineCarpetSqFt = if (shopOrShowroom) (cleanMezz ?: 0.0) else null,
                    totalCarpetSqFt = enteredCarpet,
                    totalBuiltUpSqFt = autoBuiltUp,
                    loadingPercent = loadingPct,
                    isBuiltUpAutoCalculated = autoBuiltUp != null,
                    isCarpetAutoCalculated = false,
                    isManualBuiltUpOrLoadingOverride = cleanManualLoading != null
                )
            }
            enteredCarpet <= 0.0 && cleanBuiltUp != null -> {
                val autoCarpet = effectiveRatio?.let { round(cleanBuiltUp / (1.0 + it)) } ?: 0.0
                val loadingPct = effectiveRatio?.let { round(it * 1000.0) / 10.0 }
                TlcAreaCalculationResult(
                    groundFloorCarpetSqFt = if (shopOrShowroom && autoCarpet > 0.0) autoCarpet else null,
                    mezzanineCarpetSqFt = if (shopOrShowroom) 0.0 else null,
                    totalCarpetSqFt = autoCarpet,
                    totalBuiltUpSqFt = cleanBuiltUp,
                    loadingPercent = loadingPct,
                    isBuiltUpAutoCalculated = false,
                    isCarpetAutoCalculated = autoCarpet > 0.0,
                    isManualBuiltUpOrLoadingOverride = cleanManualLoading != null
                )
            }
            else -> {
                TlcAreaCalculationResult(
                    groundFloorCarpetSqFt = if (shopOrShowroom) cleanGround else null,
                    mezzanineCarpetSqFt = if (shopOrShowroom) cleanMezz else null,
                    totalCarpetSqFt = 0.0,
                    totalBuiltUpSqFt = null,
                    loadingPercent = effectiveRatio?.let { round(it * 1000.0) / 10.0 },
                    isBuiltUpAutoCalculated = false,
                    isCarpetAutoCalculated = false,
                    isManualBuiltUpOrLoadingOverride = cleanManualLoading != null
                )
            }
        }
    }

    fun calculateDualRates(
        primaryAmount: Double?,
        totalCarpetSqFt: Double?,
        totalBuiltUpSqFt: Double?
    ): TlcDualRateResult {
        val amount = primaryAmount?.takeIf { it.isFinite() && it > 0.0 } ?: return TlcDualRateResult(null, null)
        val carpet = totalCarpetSqFt?.takeIf { it.isFinite() && it > 0.0 }
        val builtUp = totalBuiltUpSqFt?.takeIf { it.isFinite() && it > 0.0 }
        return TlcDualRateResult(
            ratePerCarpetSqFt = carpet?.let { amount / it },
            ratePerBuiltUpSqFt = builtUp?.let { amount / it }
        )
    }

    /**
     * Gross ROI % = (Current Monthly Rent * 12 / Base Sale Value) * 100
     */
    fun calculateGrossRoiPercent(monthlyRent: Double?, baseValue: Double?): Double? {
        val rent = monthlyRent?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val value = baseValue?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val roi = ((rent * 12.0) / value) * 100.0
        return if (roi.isFinite() && roi > 0.0) round(roi * 100.0) / 100.0 else null
    }

    /**
     * Unit Number Validation:
     * - Required when Seller/Contact Type is Direct Owner ("Property Owner" / "Direct Owner")
     *   or Developer ("Developer Sales Team" / "Developer").
     * - Optional / allowed blank when Seller/Contact Type is Broker ("Broker" / "Channel Partner").
     */
    fun isUnitNumberRequired(pocType: String): Boolean {
        val normalized = pocType.trim().lowercase(Locale.ENGLISH)
        if (normalized.contains("broker") || normalized.contains("channel partner") || normalized.contains("agent")) {
            return false
        }
        return normalized.contains("owner") ||
            normalized.contains("developer") ||
            normalized.contains("builder") ||
            normalized.isBlank()
    }

    /**
     * Formats any recognized date string (ISO-8601 UTC `2026-11-17T18:30:00.000Z`,
     * JS Date strings, yyyy-MM-dd, dd MMM yyyy, dd-MM-yyyy, dd/MM/yyyy, etc.)
     * strictly into `dd/MM/yyyy` in Asia/Kolkata (IST) timezone for UI and Google Sheets sync.
     */
    fun formatDateDdMmYyyy(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank()) return ""
        val istFormatted = CrmSheetSyncMapper.formatCrmDateIst(clean)
        if (Regex("""^\d{2}/\d{2}/\d{4}$""").matches(istFormatted)) {
            return istFormatted
        }

        val istZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        val targetFmt = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).apply {
            timeZone = istZone
        }
        val inputPatterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "dd.MM.yyyy",
            "dd MMM yyyy, hh:mm a",
            "dd MMM yyyy hh:mm a",
            "dd MMMM yyyy"
        )
        for (pattern in inputPatterns) {
            val parsed: Date? = runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    isLenient = false
                    timeZone = istZone
                }.parse(clean)
            }.getOrNull()
            if (parsed != null) {
                return targetFmt.format(parsed)
            }
        }
        return istFormatted
    }

    fun normalizeYesNo(raw: String?): String? {
        val clean = raw?.trim()?.lowercase(Locale.ENGLISH).orEmpty()
        return when {
            clean == "yes" || clean == "y" || clean == "true" || clean == "1" || clean.startsWith("attached") -> "Yes"
            clean == "no" || clean == "n" || clean == "false" || clean == "0" || clean == "none" -> "No"
            else -> null
        }
    }
}

/**
 * Exact Row 1 Google Sheets header mapper & Formula-Protection guard for TLC Master Sheet UPSERTs.
 */
object TlcMasterSheetMapper {

    /**
     * Formula-driven columns in the TLC Master Sheet that MUST NEVER be overwritten on UPSERT.
     */
    val ALWAYS_FORMULA_PROTECTED_HEADERS: Set<String> = setOf(
        "Project ID",
        "Total Carpet Area (Sq Ft)",
        "Loading %",
        "Total Built-Up Area (Sq Ft)",
        "Rent / Sq Ft",
        "Rate / Sq Ft",
        "Rent / Sq Ft (₹)",
        "Rate / Sq Ft (₹)",
        "Rent / Sq Ft Carpet (₹)",
        "Rent / Sq Ft Built-Up (₹)",
        "Rate / Sq Ft Carpet (₹)",
        "Rate / Sq Ft Built-Up (₹)",
        "Current ROI / Yield (%)",
        "Total Security Deposit (₹)",
        "Total Monthly Outflow (₹)"
    )

    val CONDITIONAL_FORMULA_PROTECTED_HEADERS: Set<String> = emptySet()

    val ALL_FORMULA_PROTECTED_HEADERS: Set<String> =
        ALWAYS_FORMULA_PROTECTED_HEADERS + CONDITIONAL_FORMULA_PROTECTED_HEADERS

    fun buildUpsertRecord(property: PropertyItem): JSONObject {
        val tabName = TlcInventoryTabV2.resolveTabName(property)
        val isCommercial = property.isCommercial()
        val isShopOrShowroom = isCommercial && TlcPropertyCalculator.isShopOrShowroom(property.propertyType, property.tab)
        val isWarehouse = isCommercial && TlcPropertyCalculator.isWarehouseOrIndustrial(property.propertyType)

        val permId = property.recordId.ifBlank { property.id }
        val saleVal = property.priceSale ?: if (!property.tab.isRental && property.priceOrRent > 0.0) property.priceOrRent else null
        val rentVal = property.rent ?: if (property.tab.isRental && property.priceOrRent > 0.0) property.priceOrRent else null
        val effectiveListingUrl = property.listingLink?.trim()?.takeIf { it.isNotBlank() }
            ?: property.images.firstOrNull { it.startsWith("http") }.orEmpty()
        val ownerDisplay = property.ownerName?.takeIf { it.isNotBlank() } ?: property.brokerName.orEmpty()
        val contactDisplay = property.ownerContact?.takeIf { it.isNotBlank() } ?: property.brokerContact.orEmpty()

        val formattedDateAdded = TlcPropertyCalculator.formatDateDdMmYyyy(property.date)
        val formattedVerifiedDate = TlcPropertyCalculator.formatDateDdMmYyyy(
            property.lastVerifiedDateClean.ifBlank { property.lastVerificationDate }
        )
        val formattedUpdatedDate = TlcPropertyCalculator.formatDateDdMmYyyy(
            property.updatedAt.ifBlank { property.syncUpdatedAt }
        )
        val formattedLeaseStart = TlcPropertyCalculator.formatDateDdMmYyyy(property.unmappedColumns["Lease Start"])
        val formattedLeaseEnd = TlcPropertyCalculator.formatDateDdMmYyyy(property.leaseExpiryDate)
        val formattedTargetPossession = TlcPropertyCalculator.formatDateDdMmYyyy(property.targetPossessionDate)

        val record = JSONObject().apply {
            put("Unit ID", property.propertyCode.ifBlank { permId })
            put("Property ID", permId)
            put("Legacy Property Code", property.propertyCode)
            put("Date Added", formattedDateAdded)
            put("Location", property.location)
            put("Micro-Market", property.effectiveMicroMarket.orEmpty())
            put("Project / Building", property.projectName)
            put("Unit No", property.unitNumber)
            put("Floor", property.floor)
            put("Property Type", property.propertyType)
            put("Sub Type", property.tab.code)
            put("Transaction Type", property.transactionType)
            if (!property.puneZone.isNullOrBlank()) put("Pune Zone", property.puneZone)
            if (!property.jurisdiction.isNullOrBlank()) put("Jurisdiction", property.jurisdiction)
            if (!property.ocStatus.isNullOrBlank()) put("OC Status", property.ocStatus)

            // Area inputs (protect Total Carpet Area (Sq Ft), Loading %, Total Built-Up Area (Sq Ft) formulas)
            val groundCarpet = if (isShopOrShowroom) {
                property.groundFloorCarpetSqFt ?: property.carpetAreaSqFt.takeIf { it > 0.0 }
            } else {
                property.carpetAreaSqFt.takeIf { it > 0.0 }
            }
            if (isCommercial) {
                put("Ground Floor Carpet (Sq Ft)", groundCarpet ?: "")
                put("Mezzanine Carpet (Sq Ft)", if (isShopOrShowroom) (property.mezzanineCarpetSqFt ?: 0.0) else "")
            } else {
                put("Carpet Area (Sq Ft)", property.carpetAreaSqFt.takeIf { it > 0.0 } ?: "")
            }

            // Formula Protection: Loading % and Total Built-Up Area (Sq Ft) are sheet formula columns and are never overwritten on UPSERT.

            // Pricing & Lease / Resale inputs (never write calculated rates, ROI, Total Security Deposit, Total Monthly Outflow)
            when (tabName) {
                TlcInventoryTabV2.COMMERCIAL_LEASE, TlcInventoryTabV2.RESIDENTIAL_RENTALS -> {
                    put("Monthly Rent (₹)", rentVal ?: "")
                    put("Rent INR/Month", rentVal ?: "")
                }
                TlcInventoryTabV2.COMMERCIAL_RESALE -> {
                    put("Sale Price (₹)", saleVal ?: "")
                    put("Sale Price INR", saleVal ?: "")
                    put("Pre-Leased Status", if (property.effectiveIsPreLeased) "Yes" else "No")
                    if (property.effectiveIsPreLeased) {
                        put("Tenant Name", property.tenantName.orEmpty())
                        put("Current Monthly Rent", rentVal ?: property.monthlyRentalIncome ?: "")
                    }
                    if (formattedTargetPossession.isNotBlank()) {
                        put("Target Possession Date", formattedTargetPossession)
                    }
                }
                else -> {
                    put("Sale Price (₹)", saleVal ?: "")
                    put("Sale Price INR", saleVal ?: "")
                    if (formattedTargetPossession.isNotBlank()) {
                        put("Target Possession Date", formattedTargetPossession)
                    }
                }
            }

            put("Possession", property.possession)
            put("Furnishing", property.furnishing.label)
            put("Availability Status", property.status.label)
            put("Car Parking", property.parking)

            if (isCommercial) {
                put("Attached Pantry", property.attachedPantry.orEmpty())
                put("Attached Washroom", property.attachedWashroom.orEmpty())
                if (isShopOrShowroom) {
                    put("Frontage (Ft)", property.effectiveFrontageFt ?: "")
                    put("Clear Height (Ft)", property.effectiveClearHeightFt ?: "")
                } else if (isWarehouse) {
                    put("Clear Height (Ft)", property.effectiveClearHeightFt ?: "")
                    put("Truck Access", property.truckAccess.orEmpty())
                    put("Power Load", property.powerLoad.orEmpty())
                } else {
                    property.effectiveFrontageFt?.let { put("Frontage (Ft)", it) }
                    property.effectiveClearHeightFt?.let { put("Clear Height (Ft)", it) }
                    if (!property.truckAccess.isNullOrBlank()) put("Truck Access", property.truckAccess)
                    if (!property.powerLoad.isNullOrBlank()) put("Power Load", property.powerLoad)
                }
                if (property.workstations != null) put("Workstations", property.workstations)
                if (property.cabins != null) put("Cabins", property.cabins)
                put("Lease Start", formattedLeaseStart)
                put("Lease End", formattedLeaseEnd)
                put("Lock-in Months", property.lockInMonths ?: property.lockInPeriodYears?.let { it * 12 } ?: "")
                put("Escalation Terms", property.escalation)
                put("Maintenance Terms", property.maintenance?.toString() ?: property.unmappedColumns["Maintenance Terms"].orEmpty())
            } else {
                put("Developer", property.builderName.orEmpty())
                put("Tower / Wing", property.unmappedColumns["Tower / Wing"].orEmpty())
                put("Configuration", property.bhk.orEmpty())
                put("RERA Number", property.unmappedColumns["RERA Number"].orEmpty())
                put("Plot SqFt", property.unmappedColumns["Plot SqFt"].orEmpty())
                put("Amenities", property.amenities.joinToString(", "))
            }

            put("Type of POC", property.pocType)
            put("Owner / POC", ownerDisplay)
            put("Owner Contact", contactDisplay)
            put("Source", property.sourceInfo.orEmpty())
            put("Source Reference", property.unmappedColumns["Source Reference"].orEmpty())
            put("Remarks", property.remarks.orEmpty())
            put("Listing URL", effectiveListingUrl)
            put("Latitude", property.latitude ?: property.lat ?: "")
            put("Longitude", property.longitude ?: property.lng ?: "")
            put("Last Verified", formattedVerifiedDate)
            put("Last Updated", formattedUpdatedDate)
            put("Verification Status", property.verificationStatus.label)
            put("Record Status", property.archiveStatus.label)
        }

        // Hard Formula Protection Enforcement — strip every formula-calculated column unconditionally
        ALL_FORMULA_PROTECTED_HEADERS.forEach { protectedCol ->
            record.remove(protectedCol)
        }

        return record
    }
}
