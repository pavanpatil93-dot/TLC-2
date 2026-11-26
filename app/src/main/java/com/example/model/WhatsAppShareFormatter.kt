package com.example.model

import android.content.Context
import android.net.Uri
import com.example.data.TlcPropertyCalculator
import com.example.data.master.MasterProjectDatabase
import com.example.data.master.ProjectStatus
import com.example.data.projects.ProjectDirectoryStore
import com.example.data.pune.PuneIntelligenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * All 13 transaction templates supported by the unified executive WhatsApp share engine.
 */
enum class UnifiedShareTransactionTemplate(val headline: String) {
    RESIDENTIAL_RENTAL("*RESIDENTIAL PROPERTY FOR RENT*"),
    RESIDENTIAL_RESALE("*RESIDENTIAL PROPERTY FOR RESALE*"),
    NEW_RESIDENTIAL_SALE("*NEW RESIDENTIAL PROJECT FOR SALE*"),
    COMMERCIAL_OFFICE_LEASE("*COMMERCIAL OFFICE SPACE FOR LEASE*"),
    COMMERCIAL_OFFICE_SALE("*COMMERCIAL OFFICE SPACE FOR SALE*"),
    PRE_LEASED_COMMERCIAL_INVESTMENT("*PRE-LEASED COMMERCIAL INVESTMENT*"),
    SHOP_RETAIL_LEASE("*RETAIL / SHOWROOM SPACE FOR LEASE*"),
    SHOP_SHOWROOM_SALE("*RETAIL / SHOWROOM SPACE FOR SALE*"),
    LAND_PLOT_SALE("*LAND / PLOT FOR SALE*"),
    ENTIRE_BUILDING("*ENTIRE COMMERCIAL / INDEPENDENT BUILDING*"),
    WAREHOUSE_INDUSTRIAL("*WAREHOUSE / INDUSTRIAL SPACE*"),
    JV_JD_REDEVELOPMENT("*JV / JD / REDEVELOPMENT OPPORTUNITY*"),
    HOTEL_HOSPITALITY("*HOTEL / HOSPITALITY ASSET*")
}

/**
 * Verified building-level highlights pulled from a linked Project record
 * (by projectId or projectName) to place in the lower portion of the shared message.
 * Note: Developer Name and RERA No. are intentionally excluded.
 */
data class LinkedProjectBuildingHighlights(
    val powerBackupSpec: String? = null,
    val hvacSystem: String? = null,
    val carParkingRatio: String? = null,
    val ocStatus: String? = null,
    val keyOccupantsAnchors: String? = null,
    val amenities: List<String> = emptyList(),
    val googleMapsUrl: String? = null
) {
    fun hasHighlights(): Boolean {
        return !powerBackupSpec.isNullOrBlank() ||
            !hvacSystem.isNullOrBlank() ||
            !carParkingRatio.isNullOrBlank() ||
            !ocStatus.isNullOrBlank() ||
            !keyOccupantsAnchors.isNullOrBlank() ||
            amenities.any { it.isNotBlank() }
    }
}

/**
 * Unified executive-grade WhatsApp message formatter used for BOTH Clients and Brokers.
 *
 * Strict guarantees:
 * 1. Single unified message format across all 13 transaction templates.
 * 2. Strict privacy ALWAYS applied: never includes Owner Name, Owner Mobile, Broker/Source
 *    Name or Contact, internal remarks, or exact Unit / Shop / Office / Flat No.
 * 3. Never includes Developer Name or RERA No.
 * 4. Auto-omits any field whose value is null, blank, or zero; never substitutes Carpet Area
 *    for Built-Up Area and never invents missing values.
 */
object WhatsAppShareFormatter {

    const val FALLBACK_DESK_MOBILE = "9766880099"
    private val englishSymbols = DecimalFormatSymbols(Locale.ENGLISH)
    private val areaFormat = DecimalFormat("#,##0.##", englishSymbols)

    private val PLACEHOLDER_VALUES = setOf(
        "n/a", "na", "nil", "none", "-", "--", "unknown", "tbd",
        "not mentioned", "to be confirmed", "unverified location", "null"
    )

    private val PRIVATE_URL_QUERY_KEYS = setOf(
        "token", "auth", "apikey", "api_key", "key", "secret", "password", "pass",
        "owner", "ownername", "ownercontact", "broker", "brokername", "brokercontact",
        "phone", "mobile", "contact", "email", "uid", "user", "userid", "user_id",
        "internal", "source", "remarks", "session", "sid", "ref", "access", "row"
    )

    fun resolveTransactionTemplate(property: PropertyItem): UnifiedShareTransactionTemplate {
        val typeText = listOf(
            property.propertyType,
            property.transactionType,
            property.tab.title,
            property.tab.code,
            property.unmappedColumns["Sub Type"].orEmpty(),
            property.unmappedColumns["Category"].orEmpty()
        ).joinToString(" ").lowercase(Locale.ENGLISH)

        // Specialized asset classes first
        if (typeText.contains("jv") || typeText.contains("joint venture") ||
            typeText.contains("jd") || typeText.contains("joint development") ||
            typeText.contains("redevelopment")
        ) {
            return UnifiedShareTransactionTemplate.JV_JD_REDEVELOPMENT
        }
        if (typeText.contains("hotel") || typeText.contains("hospitality") || typeText.contains("resort")) {
            return UnifiedShareTransactionTemplate.HOTEL_HOSPITALITY
        }
        if (typeText.contains("entire building") || typeText.contains("full building") ||
            typeText.contains("independent building") || typeText.contains("standalone building") ||
            typeText.contains("bts building")
        ) {
            return UnifiedShareTransactionTemplate.ENTIRE_BUILDING
        }
        if (typeText.contains("warehouse") || typeText.contains("industrial") ||
            typeText.contains("godown") || typeText.contains("logistics") || typeText.contains("shed")
        ) {
            return UnifiedShareTransactionTemplate.WAREHOUSE_INDUSTRIAL
        }
        if (typeText.contains("land") || typeText.contains("plot") || typeText.contains("na plot")) {
            return UnifiedShareTransactionTemplate.LAND_PLOT_SALE
        }

        // Pre-Leased Commercial Investment
        if (property.effectiveIsPreLeased || typeText.contains("pre-leased") || typeText.contains("pre leased")) {
            return UnifiedShareTransactionTemplate.PRE_LEASED_COMMERCIAL_INVESTMENT
        }

        val isRental = property.tab.isRental ||
            property.transactionType.contains("lease", ignoreCase = true) ||
            property.transactionType.contains("rent", ignoreCase = true)

        // Residential templates
        if (property.effectivePortfolio() == StandardPortfolio.RESIDENTIAL ||
            property.tab == InventoryTab.RESI_RENT ||
            property.tab == InventoryTab.RESI_RESALE ||
            property.tab == InventoryTab.UND_CONST
        ) {
            return when {
                isRental || property.tab == InventoryTab.RESI_RENT -> UnifiedShareTransactionTemplate.RESIDENTIAL_RENTAL
                property.tab == InventoryTab.UND_CONST ||
                    typeText.contains("under construction") ||
                    typeText.contains("new launch") ||
                    typeText.contains("new residential") -> UnifiedShareTransactionTemplate.NEW_RESIDENTIAL_SALE
                else -> UnifiedShareTransactionTemplate.RESIDENTIAL_RESALE
            }
        }

        // Commercial Retail / Showroom templates
        if (TlcPropertyCalculator.isShopOrShowroom(property.propertyType, property.tab)) {
            return if (isRental) {
                UnifiedShareTransactionTemplate.SHOP_RETAIL_LEASE
            } else {
                UnifiedShareTransactionTemplate.SHOP_SHOWROOM_SALE
            }
        }

        // Commercial Office templates
        return if (isRental) {
            UnifiedShareTransactionTemplate.COMMERCIAL_OFFICE_LEASE
        } else {
            UnifiedShareTransactionTemplate.COMMERCIAL_OFFICE_SALE
        }
    }

    /**
     * Generates ONE unified, executive-grade WhatsApp message for a single property.
     */
    fun formatProperty(
        property: PropertyItem,
        employeeName: String,
        employeeMobile: String,
        linkedProjectHighlights: LinkedProjectBuildingHighlights? = null
    ): String {
        val template = resolveTransactionTemplate(property)
        val isCommercial = property.effectivePortfolio() == StandardPortfolio.COMMERCIAL ||
            template in setOf(
                UnifiedShareTransactionTemplate.COMMERCIAL_OFFICE_LEASE,
                UnifiedShareTransactionTemplate.COMMERCIAL_OFFICE_SALE,
                UnifiedShareTransactionTemplate.PRE_LEASED_COMMERCIAL_INVESTMENT,
                UnifiedShareTransactionTemplate.SHOP_RETAIL_LEASE,
                UnifiedShareTransactionTemplate.SHOP_SHOWROOM_SALE,
                UnifiedShareTransactionTemplate.ENTIRE_BUILDING,
                UnifiedShareTransactionTemplate.WAREHOUSE_INDUSTRIAL,
                UnifiedShareTransactionTemplate.HOTEL_HOSPITALITY
            )
        val isShopOrShowroom = template == UnifiedShareTransactionTemplate.SHOP_RETAIL_LEASE ||
            template == UnifiedShareTransactionTemplate.SHOP_SHOWROOM_SALE ||
            TlcPropertyCalculator.isShopOrShowroom(property.propertyType, property.tab)
        val isResidential = template in setOf(
            UnifiedShareTransactionTemplate.RESIDENTIAL_RENTAL,
            UnifiedShareTransactionTemplate.RESIDENTIAL_RESALE,
            UnifiedShareTransactionTemplate.NEW_RESIDENTIAL_SALE
        )
        val isRentalPrimary = template in setOf(
            UnifiedShareTransactionTemplate.RESIDENTIAL_RENTAL,
            UnifiedShareTransactionTemplate.COMMERCIAL_OFFICE_LEASE,
            UnifiedShareTransactionTemplate.SHOP_RETAIL_LEASE
        ) || (property.tab.isRental && !property.effectiveIsPreLeased &&
            template != UnifiedShareTransactionTemplate.PRE_LEASED_COMMERCIAL_INVESTMENT)

        // ── SECTION 1: HEADER ──────────────────────────────────────────────────
        val headerLines = mutableListOf<String>()
        headerLines += template.headline

        val cleanLocation = cleanText(property.location)
        val cleanMicroMarket = cleanText(property.effectiveMicroMarket)
            ?.takeIf { !it.equals(cleanLocation, ignoreCase = true) }
        if (cleanLocation != null) {
            val locationWithoutCity = cleanLocation
                .replace(Regex(""",\s*pune$""", RegexOption.IGNORE_CASE), "")
                .trim()
            val locationLine = if (cleanMicroMarket != null) {
                "📍 *Location:* $locationWithoutCity ($cleanMicroMarket), Pune"
            } else {
                "📍 *Location:* $locationWithoutCity, Pune"
            }
            headerLines += locationLine
        } else if (cleanMicroMarket != null) {
            headerLines += "📍 *Location:* $cleanMicroMarket, Pune"
        }

        cleanText(property.projectName)?.let { projName ->
            headerLines += "🏢 *Project / Building:* $projName"
        }

        val cleanFloor = cleanText(property.floor) ?: cleanText(property.floorLevel)
        if (cleanFloor != null) {
            headerLines += "🏙️ *Floor:* $cleanFloor"
        }

        // ── SECTION 2: SPACE & SPECIFICATIONS ─────────────────────────────────
        val specLines = mutableListOf<String>()

        val carpet = property.carpetAreaSqFt.takeIf { it.isFinite() && it > 0.0 }
        val groundCarpet = property.groundFloorCarpetSqFt?.takeIf { it.isFinite() && it > 0.0 }
        val mezzanineCarpet = if (isShopOrShowroom) {
            property.mezzanineCarpetSqFt?.takeIf { it.isFinite() && it > 0.0 }
        } else null

        if (isShopOrShowroom && mezzanineCarpet != null) {
            val resolvedGround = groundCarpet ?: carpet?.minus(mezzanineCarpet)?.takeIf { it > 0.0 }
            val resolvedTotal = carpet ?: ((resolvedGround ?: 0.0) + mezzanineCarpet)
            if (resolvedGround != null && resolvedGround > 0.0) {
                specLines += "📐 *Carpet Area:* ${formatArea(resolvedGround)} Sq.Ft. (Ground) + " +
                    "${formatArea(mezzanineCarpet)} Sq.Ft. (Mezzanine) = ${formatArea(resolvedTotal)} Sq.Ft. Total"
            } else if (resolvedTotal > 0.0) {
                specLines += "📐 *Carpet Area:* ${formatArea(resolvedTotal)} Sq.Ft. (incl. ${formatArea(mezzanineCarpet)} Sq.Ft. Mezzanine)"
            }
        } else if (carpet != null) {
            specLines += "📐 *Carpet Area:* ${formatArea(carpet)} Sq.Ft."
        }

        // Strictly never substitute Carpet Area for Built-Up Area
        val builtUp = property.builtUpAreaSqFt?.takeIf { it.isFinite() && it > 0.0 }
        if (builtUp != null) {
            specLines += "📐 *Built-Up Area:* ${formatArea(builtUp)} Sq.Ft."
        }

        val plotArea = cleanText(property.unmappedColumns["Plot SqFt"])
            ?: cleanText(property.unmappedColumns["Plot Area"])
        if (plotArea != null && (template == UnifiedShareTransactionTemplate.LAND_PLOT_SALE ||
                template == UnifiedShareTransactionTemplate.ENTIRE_BUILDING ||
                template == UnifiedShareTransactionTemplate.JV_JD_REDEVELOPMENT)
        ) {
            val formattedPlot = if (plotArea.contains("sq", ignoreCase = true) || plotArea.contains("acre", ignoreCase = true)) {
                plotArea
            } else {
                "$plotArea Sq.Ft."
            }
            specLines += "🗺️ *Plot Area:* $formattedPlot"
        }

        val conditionText = resolveConditionFitOut(property)
        if (conditionText != null) {
            specLines += "🛋️ *Condition / Fit-out:* $conditionText"
        }

        // Office layout (when present)
        val officeLayoutParts = buildList {
            property.workstations?.takeIf { it > 0 }?.let { add("Workstations: $it") }
            property.cabins?.takeIf { it > 0 }?.let { add("Cabins: $it") }
            property.conferenceRooms?.takeIf { it > 0 }?.let { add("Conference Rooms: $it") }
        }
        if (officeLayoutParts.isNotEmpty()) {
            specLines += "🧩 *Office Layout:* ${officeLayoutParts.joinToString(" | ")}"
        }

        // Commercial utilities
        val pantry = TlcPropertyCalculator.normalizeYesNo(property.attachedPantry)
        if (pantry != null) {
            specLines += "☕ *Attached Pantry:* $pantry"
        }
        val washroom = TlcPropertyCalculator.normalizeYesNo(property.attachedWashroom)
        if (washroom != null) {
            specLines += "🚻 *Attached Washroom:* $washroom"
        }
        val parkingText = resolveParkingText(property)
        if (parkingText != null) {
            specLines += "🚗 *Car Parking:* $parkingText"
        }

        // Retail / Warehouse specs
        property.effectiveFrontageFt?.takeIf { it.isFinite() && it > 0.0 }?.let {
            specLines += "📏 *Frontage:* ${formatArea(it)} Ft."
        }
        property.effectiveClearHeightFt?.takeIf { it.isFinite() && it > 0.0 }?.let {
            specLines += "⬆️ *Clear Height:* ${formatArea(it)} Ft."
        }
        cleanText(property.truckAccess)?.let {
            specLines += "🚛 *Truck Access:* $it"
        }
        cleanText(property.powerLoad)?.let {
            specLines += "⚡ *Power Load:* $it"
        }

        // Residential specs
        if (isResidential) {
            cleanText(property.bhk)?.let {
                specLines += "🏠 *Configuration:* $it"
            }
            val bedrooms = firstCleanUnmapped(property, "Bedrooms", "Bedroom")
            if (bedrooms != null && bedrooms != "0") {
                specLines += "🛏️ *Bedrooms:* $bedrooms"
            }
            val bathrooms = firstCleanUnmapped(property, "Bathrooms", "Bathroom", "Washrooms")
            if (bathrooms != null && bathrooms != "0") {
                specLines += "🚿 *Bathrooms:* $bathrooms"
            }
            property.balconies?.takeIf { it > 0 }?.let {
                specLines += "🌇 *Balconies:* $it"
            }
            val preferredTenant = firstCleanUnmapped(property, "Preferred Tenant", "Tenant Preference", "Preferred Tenants")
            if (preferredTenant != null) {
                specLines += "👥 *Preferred Tenant:* $preferredTenant"
            }
            val pets = firstCleanUnmapped(property, "Pets", "Pets Allowed", "Pet Policy")
            if (pets != null) {
                specLines += "🐾 *Pets:* $pets"
            }
        }

        // ── SECTION 3: FINANCIALS & COMMERCIAL TERMS ──────────────────────────
        val financialLines = mutableListOf<String>()

        val primaryAmount = resolvePrimaryAmount(property, isRentalPrimary)
        if (primaryAmount != null) {
            val formattedAmount = formatAmountForShare(primaryAmount)
            if (isRentalPrimary) {
                financialLines += "💰 *Monthly Rent:* $formattedAmount"
            } else {
                financialLines += "💰 *Asking Price:* $formattedAmount"
            }

            // Dual rates — auto-calculated ONLY for available positive areas
            val dualRates = TlcPropertyCalculator.calculateDualRates(
                primaryAmount = primaryAmount,
                totalCarpetSqFt = carpet,
                totalBuiltUpSqFt = builtUp
            )
            val rateParts = buildList {
                dualRates.ratePerCarpetSqFt?.takeIf { it.isFinite() && it > 0.0 }?.let {
                    add("${formatRateNumber(it)} (Carpet)")
                }
                dualRates.ratePerBuiltUpSqFt?.takeIf { it.isFinite() && it > 0.0 }?.let {
                    add("${formatRateNumber(it)} (Built-Up)")
                }
            }
            if (rateParts.isNotEmpty()) {
                financialLines += "📊 *Rate / Sq.Ft:* ${rateParts.joinToString(" | ")}"
            }
        }

        val depositText = resolveDepositText(property)
        if (depositText != null) {
            financialLines += "🔐 *Deposit:* $depositText"
        }

        val maintenanceText = resolveMaintenanceText(property)
        if (maintenanceText != null) {
            financialLines += "🧾 *Maintenance:* $maintenanceText"
        }

        val lockInText = resolveLockInText(property)
        val tenureText = resolveTenureText(property)
        val escalationText = resolveEscalationText(property)

        val preLeasedTenant = cleanText(property.tenantName)
        val preLeasedMonthlyRent = (property.monthlyRentalIncome?.takeIf { it.isFinite() && it > 0.0 }
            ?: property.rent?.takeIf { it.isFinite() && it > 0.0 })
        val showPreLeasedBlock = property.effectiveIsPreLeased &&
            preLeasedTenant != null &&
            preLeasedMonthlyRent != null

        if (!showPreLeasedBlock && lockInText != null) {
            financialLines += "🔒 *Lock-in:* $lockInText"
        }
        if (tenureText != null) {
            financialLines += "📄 *Tenure:* $tenureText"
        }
        if (escalationText != null) {
            financialLines += "📈 *Escalation:* $escalationText"
        }

        val possessionText = resolvePossessionText(property)
        if (possessionText != null) {
            financialLines += "📅 *Possession:* $possessionText"
        }

        // Pre-Leased Block (ONLY when Pre-Leased = Yes AND Tenant + Monthly Rent exist)
        if (showPreLeasedBlock && preLeasedTenant != null && preLeasedMonthlyRent != null) {
            val annualRent = preLeasedMonthlyRent * 12.0
            val saleValueForRoi = (property.priceSale?.takeIf { it.isFinite() && it > 0.0 }
                ?: property.priceOrRent.takeIf { it.isFinite() && it > 0.0 })
            val grossRoi = property.roiPercent?.takeIf { it.isFinite() && it > 0.0 }
                ?: TlcPropertyCalculator.calculateGrossRoiPercent(preLeasedMonthlyRent, saleValueForRoi)

            financialLines += "🏢 *Tenant:* $preLeasedTenant"
            financialLines += "💰 *Monthly Rent:* ${formatAmountForShare(preLeasedMonthlyRent)}"
            financialLines += "💰 *Annual Rent:* ${formatAmountForShare(annualRent)}"
            if (grossRoi != null) {
                financialLines += "📈 *Gross ROI:* ${String.format(Locale.US, "%.2f", grossRoi)}%"
            }
            if (lockInText != null) {
                financialLines += "🔒 *Remaining Lock-in:* $lockInText"
            }
            val leaseExpiry = cleanText(property.leaseExpiryDate)
                ?.let { TlcPropertyCalculator.formatDateDdMmYyyy(it) }
                ?.takeIf { it.isNotBlank() }
            if (leaseExpiry != null) {
                financialLines += "📅 *Lease Expiry:* $leaseExpiry"
            }
            val maintTaxPaidBy = firstCleanUnmapped(
                property,
                "Maint / Tax Paid By",
                "Maintenance / Tax Paid By",
                "Property Tax Paid By",
                "Maintenance Paid By"
            ) ?: property.maintenanceBasis
                .takeIf { it.isNotBlank() && !it.equals("UNKNOWN", ignoreCase = true) }
                ?.replace('_', ' ')
            if (maintTaxPaidBy != null) {
                financialLines += "🧾 *Maint / Tax Paid By:* $maintTaxPaidBy"
            }
        }

        // ── SECTION 4: HIGHLIGHTS & LOCATION (LOWER PORTION) ──────────────────
        val effectiveHighlights = mergePropertyAndLinkedHighlights(property, linkedProjectHighlights)
        val highlightLines = mutableListOf<String>()

        val bulletItems = buildList {
            cleanText(effectiveHighlights.powerBackupSpec)?.let { add("• Power Backup: $it") }
            cleanText(effectiveHighlights.hvacSystem)?.let { add("• HVAC: $it") }
            cleanText(effectiveHighlights.carParkingRatio)?.let { add("• Car Parking Ratio: $it") }
            cleanText(effectiveHighlights.ocStatus)?.let { add("• OC Status: $it") }
            cleanText(effectiveHighlights.keyOccupantsAnchors)?.let { add("• Key Occupants / Anchors: $it") }
            val cleanAmenities = effectiveHighlights.amenities
                .mapNotNull { cleanText(it) }
                .distinctBy { it.lowercase(Locale.ENGLISH) }
            if (cleanAmenities.isNotEmpty()) {
                add("• Amenities: ${cleanAmenities.joinToString(", ")}")
            }
        }
        if (bulletItems.isNotEmpty()) {
            highlightLines += "✨ *Building Highlights:*"
            highlightLines.addAll(bulletItems)
        }

        val mapsUrl = resolveGoogleMapsLink(property, effectiveHighlights)
        if (mapsUrl != null) {
            highlightLines += "📍 *Google Maps:* $mapsUrl"
        }

        // ── SECTION 5: STANDARD FOOTER ────────────────────────────────────────
        val cleanEmployeeName = employeeName.trim().takeIf {
            it.isNotBlank() && !it.equals("Unregistered", true) && !it.equals("User", true)
        } ?: "TLC Advisory Desk"
        val cleanEmployeeMobile = employeeMobile.trim().ifBlank { FALLBACK_DESK_MOBILE }
        val cleanCode = cleanText(property.propertyCode)

        val footerLines = buildList {
            add("📞 *Contact for Details & Site Visit*")
            add("$cleanEmployeeName | $cleanEmployeeMobile")
            add("*The Leasing Company*")
            if (cleanCode != null) {
                add("*Property Code:* $cleanCode • *Availability subject to confirmation.*")
            } else {
                add("*Availability subject to confirmation.*")
            }
        }

        // Assemble non-empty sections cleanly with blank line separators
        val sections = listOf(headerLines, specLines, financialLines, highlightLines, footerLines)
            .filter { it.isNotEmpty() }

        return sections.joinToString("\n\n") { it.joinToString("\n") }
    }

    /**
     * Formats 1 or more properties using the unified executive format.
     * Single property uses the exact 5-section format; multiple properties combine each
     * property's body cleanly with ONE standard footer at the bottom.
     */
    fun formatMultipleProperties(
        properties: List<PropertyItem>,
        employeeName: String,
        employeeMobile: String,
        highlightsByPropertyId: Map<String, LinkedProjectBuildingHighlights> = emptyMap()
    ): String {
        val unique = properties.distinctBy { it.id }
        if (unique.isEmpty()) return ""
        if (unique.size == 1) {
            val single = unique.first()
            return formatProperty(
                property = single,
                employeeName = employeeName,
                employeeMobile = employeeMobile,
                linkedProjectHighlights = highlightsByPropertyId[single.id]
            )
        }

        val blocks = unique.mapIndexed { idx, prop ->
            val full = formatProperty(
                property = prop,
                employeeName = employeeName,
                employeeMobile = employeeMobile,
                linkedProjectHighlights = highlightsByPropertyId[prop.id]
            )
            val withoutFooter = full.substringBefore("\n\n📞 *Contact for Details & Site Visit*").trim()
            val codeTag = cleanText(prop.propertyCode)?.let { " ($it)" }.orEmpty()
            "*OPTION ${idx + 1}$codeTag*\n$withoutFooter"
        }

        val cleanEmployeeName = employeeName.trim().takeIf {
            it.isNotBlank() && !it.equals("Unregistered", true) && !it.equals("User", true)
        } ?: "TLC Advisory Desk"
        val cleanEmployeeMobile = employeeMobile.trim().ifBlank { FALLBACK_DESK_MOBILE }

        return buildString {
            append("*CURATED PROPERTY OPTIONS (${unique.size})*\n\n")
            append(blocks.joinToString("\n\n────────────\n\n"))
            append("\n\n📞 *Contact for Details & Site Visit*\n")
            append("$cleanEmployeeName | $cleanEmployeeMobile\n")
            append("*The Leasing Company*\n")
            append("*Availability subject to confirmation.*")
        }
    }

    /**
     * Resolves verified building-level highlights from the linked Project record
     * (DirectoryProject store + Room MasterProjectDatabase + Room PuneIntelligenceDatabase)
     * matching by projectId first, then exact normalized projectName.
     */
    suspend fun resolveLinkedProjectHighlights(
        context: Context,
        property: PropertyItem
    ): LinkedProjectBuildingHighlights? = withContext(Dispatchers.IO) {
        val targetId = property.projectId.trim()
        val targetName = property.projectName.trim()
        if (targetId.isBlank() && targetName.isBlank()) return@withContext null

        var powerBackup: String? = null
        var hvac: String? = null
        var parkingRatio: String? = null
        var ocStatus: String? = null
        var keyOccupants: String? = null
        val amenities = mutableListOf<String>()
        var mapsUrl: String? = null

        // 1. ProjectDirectoryStore (synced Commercial Projects & Residential Projects tabs)
        runCatching { ProjectDirectoryStore.read(context) }.getOrDefault(emptyList())
            .firstOrNull { dp -> dp.matchesProperty(property) }
            ?.let { dp ->
                val rawOuter = dp.raw
                val rawInner = rawOuter.optJSONObject("raw")
                powerBackup = pickJsonField(
                    rawOuter, rawInner,
                    "Power Backup Spec", "Power Backup", "DG Backup", "powerBackup", "powerBackupSpec"
                )
                hvac = pickJsonField(
                    rawOuter, rawInner,
                    "HVAC System", "HVAC", "Air Conditioning", "hvac", "hvacSystem"
                )
                parkingRatio = pickJsonField(
                    rawOuter, rawInner,
                    "Car Parking Ratio", "Parking Ratio", "carParkingRatio", "parkingRatio"
                )
                ocStatus = pickJsonField(
                    rawOuter, rawInner,
                    "OC Status", "Occupancy Certificate", "ocStatus"
                ) ?: cleanText(dp.possession)?.takeIf {
                    it.contains("OC", ignoreCase = true) || it.contains("Ready", ignoreCase = true)
                }
                keyOccupants = pickJsonField(
                    rawOuter, rawInner,
                    "Key Occupants / Anchors", "Key Occupants", "Anchor Tenants", "Major Tenants", "keyOccupants"
                )
                pickJsonField(rawOuter, rawInner, "Amenities", "amenities", "amenitiesSummary")?.let { rawAm ->
                    rawAm.split(",", "|", "•", ";").mapNotNull { cleanText(it) }.forEach { amenities += it }
                }
                mapsUrl = pickJsonField(
                    rawOuter, rawInner,
                    "Google Maps Link", "Google Maps URL", "Google Maps", "Maps Link", "googleMapsUrl", "mapLink"
                )
            }

        // 2. Room MasterProjectDatabase (`projects_master`)
        runCatching {
            val allProjects = MasterProjectDatabase.getDatabase(context).masterProjectDao().getAllProjectsSync()
            allProjects.firstOrNull { p ->
                (targetId.isNotBlank() && p.projectId.equals(targetId, ignoreCase = true)) ||
                    (targetName.isNotBlank() && p.projectName.equals(targetName, ignoreCase = true))
            }
        }.getOrNull()?.let { proj ->
            if (ocStatus.isNullOrBlank()) {
                ocStatus = when (proj.status) {
                    ProjectStatus.READY, ProjectStatus.COMPLETED -> "OC Received"
                    ProjectStatus.UNDER_CONSTRUCTION -> "Under Construction"
                    ProjectStatus.PRE_LAUNCH -> null
                }
            }
        }

        // 3. Room PuneIntelligenceDatabase (`commercial_projects` for coordinates fallback)
        if (mapsUrl.isNullOrBlank()) {
            runCatching {
                val repo = PuneIntelligenceRepository.getInstance(context)
                val cp = repo.getAllCommercialProjects().firstOrNull { p ->
                    (targetId.isNotBlank() && p.id.equals(targetId, ignoreCase = true)) ||
                        (targetName.isNotBlank() && p.name.equals(targetName, ignoreCase = true))
                }
                cp?.coordinates?.trim()?.takeIf { it.contains(",") }?.let { coords ->
                    val parts = coords.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                    if (parts.size == 2) {
                        mapsUrl = "https://maps.google.com/?q=${parts[0]},${parts[1]}"
                    }
                }
            }
        }

        val resolved = LinkedProjectBuildingHighlights(
            powerBackupSpec = cleanText(powerBackup),
            hvacSystem = cleanText(hvac),
            carParkingRatio = cleanText(parkingRatio),
            ocStatus = cleanText(ocStatus),
            keyOccupantsAnchors = cleanText(keyOccupants),
            amenities = amenities.distinctBy { it.lowercase(Locale.ENGLISH) },
            googleMapsUrl = sanitizeGoogleMapsUrl(mapsUrl)
        )
        if (resolved.hasHighlights() || !resolved.googleMapsUrl.isNullOrBlank()) resolved else null
    }

    private fun mergePropertyAndLinkedHighlights(
        property: PropertyItem,
        linked: LinkedProjectBuildingHighlights?
    ): LinkedProjectBuildingHighlights {
        val mergedAmenities = buildList {
            linked?.amenities?.forEach { add(it) }
            property.amenities.forEach { add(it) }
        }.mapNotNull { cleanText(it) }.distinctBy { it.lowercase(Locale.ENGLISH) }

        return LinkedProjectBuildingHighlights(
            powerBackupSpec = cleanText(linked?.powerBackupSpec)
                ?: firstCleanUnmapped(property, "Power Backup Spec", "Power Backup", "DG Backup"),
            hvacSystem = cleanText(linked?.hvacSystem)
                ?: firstCleanUnmapped(property, "HVAC System", "HVAC"),
            carParkingRatio = cleanText(linked?.carParkingRatio)
                ?: firstCleanUnmapped(property, "Car Parking Ratio", "Parking Ratio"),
            ocStatus = cleanText(linked?.ocStatus)
                ?: cleanText(property.ocStatus)
                ?: firstCleanUnmapped(property, "OC Status"),
            keyOccupantsAnchors = cleanText(linked?.keyOccupantsAnchors)
                ?: firstCleanUnmapped(property, "Key Occupants / Anchors", "Key Occupants", "Anchor Tenants"),
            amenities = mergedAmenities,
            googleMapsUrl = sanitizeGoogleMapsUrl(linked?.googleMapsUrl)
        )
    }

    private fun resolveGoogleMapsLink(
        property: PropertyItem,
        highlights: LinkedProjectBuildingHighlights
    ): String? {
        sanitizeGoogleMapsUrl(property.googleMapsUrl)?.let { return it }
        sanitizeGoogleMapsUrl(firstCleanUnmapped(property, "Google Maps Link", "Google Maps URL", "Google Maps"))?.let { return it }
        val lat = property.latitude ?: property.lat
        val lng = property.longitude ?: property.lng
        if (lat != null && lng != null && lat.isFinite() && lng.isFinite() && (lat != 0.0 || lng != 0.0)) {
            return "https://maps.google.com/?q=$lat,$lng"
        }
        return sanitizeGoogleMapsUrl(highlights.googleMapsUrl)
    }

    private fun sanitizeGoogleMapsUrl(raw: String?): String? {
        val candidate = cleanText(raw) ?: return null
        if (!candidate.startsWith("http://", ignoreCase = true) && !candidate.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return runCatching {
            val uri = Uri.parse(candidate)
            val host = uri.host?.lowercase(Locale.ENGLISH) ?: return null
            if (host.contains("docs.google.com") || host.contains("script.google.com")) return null
            if (uri.queryParameterNames.any { it.lowercase(Locale.ENGLISH) in PRIVATE_URL_QUERY_KEYS }) {
                return null
            }
            candidate
        }.getOrNull()
    }

    private fun resolveConditionFitOut(property: PropertyItem): String? {
        val explicit = firstCleanUnmapped(property, "Condition / Fit-out", "Condition", "Fit-out", "Fitout")
        if (explicit != null) return explicit
        // Avoid emitting default UNFURNISHED if property is Land/Plot/JV/Building with no furnishing context
        val tabIsLandOrBare = property.propertyType.contains("land", true) ||
            property.propertyType.contains("plot", true)
        if (tabIsLandOrBare && property.furnishing == FurnishingType.UNFURNISHED) return null
        return cleanText(property.furnishing.label)
    }

    private fun resolveParkingText(property: PropertyItem): String? {
        val raw = cleanText(property.parking)
        if (raw != null && raw != "0") return raw
        val count = property.parkingCount?.takeIf { it > 0 }
        return count?.let { if (it == 1) "1 Car Park" else "$it Car Parks" }
    }

    private fun resolvePrimaryAmount(property: PropertyItem, isRentalPrimary: Boolean): Double? {
        return if (isRentalPrimary) {
            property.rent?.takeIf { it.isFinite() && it > 0.0 }
                ?: property.priceOrRent.takeIf { it.isFinite() && it > 0.0 }
        } else {
            property.priceSale?.takeIf { it.isFinite() && it > 0.0 }
                ?: property.priceOrRent.takeIf { it.isFinite() && it > 0.0 }
        }
    }

    private fun resolveDepositText(property: PropertyItem): String? {
        val num = property.deposit?.takeIf { it.isFinite() && it > 0.0 }
        if (num != null) {
            return if (num <= 24.0) {
                val months = if (num % 1.0 == 0.0) num.toInt().toString() else formatArea(num)
                "$months Months"
            } else {
                formatAmountForShare(num)
            }
        }
        return firstCleanUnmapped(property, "Deposit", "Security Deposit", "Deposit Months")
    }

    private fun resolveMaintenanceText(property: PropertyItem): String? {
        val num = property.maintenance?.takeIf { it.isFinite() && it > 0.0 }
        if (num != null) {
            return "${formatAmountForShare(num)} / month"
        }
        return firstCleanUnmapped(property, "Maintenance Terms", "Maintenance", "CAM")
    }

    private fun resolveLockInText(property: PropertyItem): String? {
        property.lockInMonths?.takeIf { it > 0 }?.let { months ->
            return if (months % 12 == 0) {
                val yrs = months / 12
                "$yrs ${if (yrs == 1) "Year" else "Years"}"
            } else {
                "$months Months"
            }
        }
        property.lockInPeriodYears?.takeIf { it > 0 }?.let { yrs ->
            return "$yrs ${if (yrs == 1) "Year" else "Years"}"
        }
        return firstCleanUnmapped(property, "Lock-in", "Lock-in Months", "Remaining Lock-in")
    }

    private fun resolveTenureText(property: PropertyItem): String? {
        property.leaseDurationYears?.takeIf { it > 0 }?.let { yrs ->
            return "$yrs ${if (yrs == 1) "Year" else "Years"}"
        }
        return firstCleanUnmapped(property, "Tenure", "Lease Tenure", "Lease Duration")
    }

    private fun resolveEscalationText(property: PropertyItem): String? {
        val pct = property.escalationPercent?.takeIf { it.isFinite() && it > 0.0 }
        if (pct != null) {
            val pctStr = if (pct % 1.0 == 0.0) "${pct.toInt()}%" else "${formatArea(pct)}%"
            val intervalYrs = property.escalationIntervalYears?.takeIf { it > 0 }
                ?: property.escalationIntervalMonths?.takeIf { it > 0 && it % 12 == 0 }?.div(12)
            return if (intervalYrs != null) {
                "$pctStr every $intervalYrs ${if (intervalYrs == 1) "Yr" else "Yrs"}"
            } else {
                "$pctStr p.a."
            }
        }
        return firstCleanUnmapped(property, "Escalation Terms", "Escalation")
    }

    private fun resolvePossessionText(property: PropertyItem): String? {
        val base = cleanText(property.possession)
        val targetDate = cleanText(property.targetPossessionDate)
            ?.let { TlcPropertyCalculator.formatDateDdMmYyyy(it) }
            ?.takeIf { it.isNotBlank() }
        return when {
            base != null && targetDate != null && !base.contains(targetDate, ignoreCase = true) ->
                "$base (Target: $targetDate)"
            base != null -> base
            targetDate != null -> "Target: $targetDate"
            else -> null
        }
    }

    private fun pickJsonField(outer: JSONObject?, inner: JSONObject?, vararg keys: String): String? {
        for (key in keys) {
            cleanText(outer?.optString(key))?.let { return it }
            cleanText(inner?.optString(key))?.let { return it }
        }
        return null
    }

    private fun firstCleanUnmapped(property: PropertyItem, vararg keys: String): String? {
        for (key in keys) {
            cleanText(property.unmappedColumns[key])?.let { return it }
        }
        return null
    }

    private fun cleanText(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (trimmed.lowercase(Locale.ENGLISH) in PLACEHOLDER_VALUES) return null
        return trimmed
    }

    private fun formatArea(value: Double): String = areaFormat.format(value)

    private fun formatAmountForShare(amount: Double): String {
        val raw = CalculationUtils.formatIndianCurrency(amount, isMonthly = false).trim()
        return if (raw.startsWith("₹")) raw else "₹$raw"
    }

    private fun formatRateNumber(rate: Double): String {
        val rounded = if (rate >= 100.0 || rate % 1.0 == 0.0) {
            areaFormat.format(kotlin.math.round(rate))
        } else {
            String.format(Locale.ENGLISH, "%.2f", rate).trimEnd('0').trimEnd('.')
        }
        return "₹$rounded"
    }
}
