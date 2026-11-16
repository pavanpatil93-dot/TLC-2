package com.example.model

import android.net.Uri
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class WhatsAppShareTemplate(val label: String) {
    SINGLE_PROPERTY("Single Property"), MULTI_PROPERTY_SHORTLIST("Multi-Property Shortlist"),
    OFFICE_LEASE("Office Lease"), OFFICE_SALE("Office Sale"), SHOWROOM_LEASE("Showroom Lease"),
    PRE_LEASED_INVESTMENT("Pre-Leased Investment"), RESIDENTIAL_PROJECT("Residential Project"),
    SITE_VISIT_CONFIRMATION("Site Visit Confirmation"), FOLLOW_UP_MESSAGE("Follow-Up Message")
}

data class SinglePropertySharePreview(
    val message: String,
    val missingEssentialFields: List<String>,
    val requiresRestrictedStatusConfirmation: Boolean,
    val status: AvailabilityStatus,
    val propertyCode: String
)

object WhatsAppShareTemplates {
    const val DESK_CONTACTS = "9766880099 | 9766668099"

    private val englishSymbols = DecimalFormatSymbols(Locale.ENGLISH)
    private val areaDecimalFormat = DecimalFormat("#.##", englishSymbols)

    private val RESTRICTED_UNAVAILABLE_STATUSES = setOf(
        AvailabilityStatus.SOLD,
        AvailabilityStatus.LEASED,
        AvailabilityStatus.WITHDRAWN
    )

    private val UNCONFIRMED_STATUSES = setOf(
        AvailabilityStatus.NOT_CONFIRMED,
        AvailabilityStatus.VERIFICATION_PENDING
    )

    private val PRIVATE_URL_QUERY_KEYS = setOf(
        "token", "auth", "apikey", "api_key", "key", "secret", "password", "pass",
        "owner", "ownername", "ownercontact", "broker", "brokername", "brokercontact",
        "phone", "mobile", "contact", "email", "uid", "user", "userid", "user_id",
        "internal", "source", "remarks", "session", "sid", "ref", "access", "row"
    )

    fun buildSinglePropertyPreview(
        property: PropertyItem,
        senderName: String? = null
    ): SinglePropertySharePreview {
        val code = property.propertyCode.trim().takeIf { it.isNotBlank() }
        val project = property.projectName.trim().takeIf { it.isNotBlank() }
        val location = property.location.trim().takeIf { it.isNotBlank() }

        val transactionType = resolveTransactionType(property)
        val isRental = transactionType == StandardTransactionType.LEASE
        val isPreLeased = transactionType == StandardTransactionType.PRE_LEASED_SALE

        val propertyTypeLabel = resolvePublicPropertyType(property)
        val transactionLabel = when (transactionType) {
            StandardTransactionType.LEASE -> "Lease"
            StandardTransactionType.SALE -> "Sale"
            StandardTransactionType.PRE_LEASED_SALE -> "Pre-Leased Sale"
        }

        val carpetArea = property.carpetAreaSqFt.takeIf { it.isFinite() && it > 0.0 }
        val builtUpArea = property.builtUpAreaSqFt?.takeIf { it.isFinite() && it > 0.0 }

        val askingAmount = resolveAskingAmount(property, isRental)
        val carpetRate = if (askingAmount != null && carpetArea != null) askingAmount / carpetArea else null
        val builtUpRate = if (askingAmount != null && builtUpArea != null) askingAmount / builtUpArea else null

        val monthlyIncome = if (isPreLeased) {
            property.monthlyRentalIncome?.takeIf { it.isFinite() && it > 0.0 }
                ?: property.rent?.takeIf { it.isFinite() && it > 0.0 }
        } else null

        val indicativeGrossYield = if (isPreLeased && monthlyIncome != null && askingAmount != null) {
            CalculationUtils.calculateGrossYield(monthlyIncome, askingAmount, isMonthly = true)
                ?.takeIf { it.isFinite() && it > 0.0 }
        } else null

        val configuration = buildConfigurationSummary(property)
        val floor = property.floor.ifBlank { property.floorLevel.orEmpty() }.trim().takeIf { it.isNotBlank() }
        val parking = property.parking.trim().takeIf { it.isNotBlank() }
        val possession = property.possession.trim()
            .takeIf { it.isNotBlank() && !it.equals("To be confirmed", true) && !it.equals("Not Mentioned", true) }

        val deposit = property.deposit?.takeIf { it.isFinite() && it > 0.0 }
        val maintenanceDisplay = formatMaintenanceDisplay(property)
        val lockInAndLease = formatLockInAndLease(property)
        val escalation = formatEscalation(property)
        val publicListingLink = sanitizePublicListingUrl(property.listingLink)

        val missingEssential = buildList {
            if (code == null) add("Property Code")
            if (project == null) add("Project / Building")
            if (location == null) add("Location")
            if (carpetArea == null) add("Carpet Area")
            if (askingAmount == null) add(if (isRental) "Monthly Rent" else "Sale Price")
            if (isPreLeased && monthlyIncome == null) add("Monthly Rental Income")
            if (possession == null) add("Possession")
        }

        val lines = mutableListOf<String>()
        lines.add("🏢 *$propertyTypeLabel · $transactionLabel*")
        if (code != null) lines.add("🧾 *Property Code:* $code")
        if (project != null) lines.add("🏛 *Project / Building:* $project")
        if (location != null) lines.add("📍 *Location:* $location")

        if (carpetArea != null) {
            lines.add("📐 *Carpet Area:* ${formatNumber(carpetArea)} sq.ft")
        }
        if (builtUpArea != null) {
            lines.add("📐 *Built-up Area:* ${formatNumber(builtUpArea)} sq.ft")
        }

        lines.add("🛋 *Furnishing:* ${property.furnishing.label}")
        if (configuration != null) lines.add("🏷 *Configuration:* $configuration")
        if (floor != null) lines.add("🪜 *Floor:* $floor")
        if (parking != null) lines.add("🚗 *Parking:* $parking")
        if (possession != null) lines.add("🔑 *Possession:* $possession")

        if (askingAmount != null) {
            val priceLabel = if (isRental) "Asking Monthly Rent" else "Asking Sale Price"
            val formattedAmount = CalculationUtils.formatIndianCurrency(askingAmount, isMonthly = false) +
                if (isRental) " / month" else ""
            lines.add("💰 *$priceLabel:* $formattedAmount")
        }
        if (carpetRate != null) {
            lines.add("📊 *Carpet Rate:* ${formatRate(carpetRate, isRental)}")
        }
        if (builtUpRate != null) {
            lines.add("📊 *Built-up Rate:* ${formatRate(builtUpRate, isRental)}")
        }

        if (deposit != null) {
            lines.add("🛡 *Deposit:* ${CalculationUtils.formatIndianCurrency(deposit, isMonthly = false)}")
        }
        if (maintenanceDisplay != null) {
            lines.add("🧾 *Maintenance:* $maintenanceDisplay")
        }
        if (lockInAndLease != null) {
            lines.add("🔒 *Lease / Lock-in:* $lockInAndLease")
        }
        if (escalation != null) {
            lines.add("📈 *Escalation:* $escalation")
        }

        if (isPreLeased && monthlyIncome != null) {
            lines.add("💵 *Monthly Rental Income:* ${CalculationUtils.formatIndianCurrency(monthlyIncome, isMonthly = false)} / month")
        }
        if (isPreLeased && indicativeGrossYield != null) {
            lines.add("📈 *Indicative gross yield:* ${String.format(Locale.ENGLISH, "%.2f", indicativeGrossYield)}% p.a.")
        }

        lines.add("📌 *Status:* ${property.status.label}")
        if (property.status in UNCONFIRMED_STATUSES) {
            lines.add("⚠️ Availability subject to confirmation.")
        }

        if (publicListingLink != null) {
            lines.add("🔗 *Listing:* $publicListingLink")
        }

        val cleanSender = senderName?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("Unregistered", true) &&
                    !it.equals("Read-Only User", true) &&
                    !it.equals("User", true)
            }

        lines.add("")
        if (cleanSender != null) {
            lines.add("Shared by: $cleanSender")
        }
        lines.add("For details & site visits: 📞 $DESK_CONTACTS")

        return SinglePropertySharePreview(
            message = lines.joinToString("\n"),
            missingEssentialFields = missingEssential,
            requiresRestrictedStatusConfirmation = property.status in RESTRICTED_UNAVAILABLE_STATUSES,
            status = property.status,
            propertyCode = code ?: ""
        )
    }

    fun property(
        property: PropertyItem,
        template: WhatsAppShareTemplate = WhatsAppShareTemplate.SINGLE_PROPERTY,
        senderName: String? = null
    ): String {
        return when (template) {
            WhatsAppShareTemplate.SINGLE_PROPERTY -> buildSinglePropertyPreview(property, senderName).message
            WhatsAppShareTemplate.MULTI_PROPERTY_SHORTLIST -> shortlist(listOf(property), senderName)
            WhatsAppShareTemplate.OFFICE_LEASE -> buildOfficeLeasePreview(property, senderName)
            WhatsAppShareTemplate.OFFICE_SALE -> buildOfficeSalePreview(property, senderName)
            WhatsAppShareTemplate.SHOWROOM_LEASE -> buildShowroomLeasePreview(property, senderName)
            WhatsAppShareTemplate.PRE_LEASED_INVESTMENT -> buildPreLeasedPreview(property, senderName)
            WhatsAppShareTemplate.RESIDENTIAL_PROJECT -> buildResidentialProjectPreview(property, senderName)
            WhatsAppShareTemplate.SITE_VISIT_CONFIRMATION -> buildSiteVisitConfirmation(property, senderName)
            WhatsAppShareTemplate.FOLLOW_UP_MESSAGE -> buildFollowUpMessage(property, senderName)
        }
    }

    fun shortlist(properties: List<PropertyItem>, senderName: String? = null): String {
        val body = properties.distinctBy { it.id }
            .joinToString("\n\n────────────\n\n") { buildSinglePropertyPreview(it, senderName).message }
        return if (body.isBlank()) {
            "For details & site visits: 📞 $DESK_CONTACTS"
        } else {
            "*PROPERTY SHORTLIST*\n\n$body"
        }
    }

    private fun buildOfficeLeasePreview(property: PropertyItem, senderName: String?): String {
        val lines = mutableListOf<String>()
        val code = property.propertyCode.trim().ifBlank { property.tab.code }
        lines.add("🏢 *Commercial Office for Lease*")
        lines.add("🧾 *Code:* $code · *Project:* ${property.projectName.ifBlank { "Pune Commercial Hub" }}")
        lines.add("📍 *Location:* ${property.location.ifBlank { "East Pune" }}")
        if (property.carpetAreaSqFt > 0) lines.add("📐 *Carpet Area:* ${formatNumber(property.carpetAreaSqFt)} sq.ft")
        lines.add("🛋 *Furnishing:* ${property.furnishing.label}")
        val cfg = buildConfigurationSummary(property)
        if (cfg != null) lines.add("🏷 *Fit-out:* $cfg")
        val rent = resolveAskingAmount(property, isRental = true)
        if (rent != null) {
            lines.add("💰 *Asking Rent:* ${CalculationUtils.formatIndianCurrency(rent, isMonthly = false)} / month")
            if (property.carpetAreaSqFt > 0) {
                val rate = rent / property.carpetAreaSqFt
                lines.add("📊 *Carpet Rate:* ${formatRate(rate, true)}")
            }
        }
        val lock = formatLockInAndLease(property)
        if (lock != null) lines.add("🔒 *Lease Terms:* $lock")
        lines.add("📌 *Status:* ${property.status.label}")
        appendFooter(lines, senderName)
        return lines.joinToString("\n")
    }

    private fun buildOfficeSalePreview(property: PropertyItem, senderName: String?): String {
        val lines = mutableListOf<String>()
        val code = property.propertyCode.trim().ifBlank { property.tab.code }
        lines.add("🏛 *Commercial Office for Outright Sale*")
        lines.add("🧾 *Code:* $code · *Project:* ${property.projectName.ifBlank { "Pune IT / Commercial" }}")
        lines.add("📍 *Location:* ${property.location.ifBlank { "East Pune" }}")
        if (property.carpetAreaSqFt > 0) lines.add("📐 *Carpet Area:* ${formatNumber(property.carpetAreaSqFt)} sq.ft")
        val sale = resolveAskingAmount(property, isRental = false)
        if (sale != null) {
            lines.add("💰 *Sale Price:* ${CalculationUtils.formatIndianCurrency(sale, isMonthly = false)}")
            if (property.carpetAreaSqFt > 0) {
                val rate = sale / property.carpetAreaSqFt
                lines.add("📊 *Rate per sq.ft:* ${formatRate(rate, false)}")
            }
        }
        lines.add("🛋 *Furnishing:* ${property.furnishing.label}")
        if (property.possession.isNotBlank()) lines.add("🔑 *Possession:* ${property.possession}")
        lines.add("📌 *Status:* ${property.status.label}")
        appendFooter(lines, senderName)
        return lines.joinToString("\n")
    }

    private fun buildShowroomLeasePreview(property: PropertyItem, senderName: String?): String {
        val lines = mutableListOf<String>()
        val code = property.propertyCode.trim().ifBlank { property.tab.code }
        lines.add("🛍 *Retail Showroom / Shop for Lease*")
        lines.add("🧾 *Code:* $code · *Project / Building:* ${property.projectName.ifBlank { "Prime Retail" }}")
        lines.add("📍 *Location:* ${property.location.ifBlank { "High Street, Pune" }}")
        if (property.carpetAreaSqFt > 0) lines.add("📐 *Carpet Area:* ${formatNumber(property.carpetAreaSqFt)} sq.ft")
        if (property.frontageFeet != null && property.frontageFeet > 0) lines.add("📏 *Frontage:* ${formatNumber(property.frontageFeet)} ft")
        if (property.ceilingHeightFeet != null && property.ceilingHeightFeet > 0) lines.add("↕️ *Ceiling Height:* ${formatNumber(property.ceilingHeightFeet)} ft")
        val rent = resolveAskingAmount(property, isRental = true)
        if (rent != null) lines.add("💰 *Asking Rent:* ${CalculationUtils.formatIndianCurrency(rent, isMonthly = false)} / month")
        lines.add("📌 *Status:* ${property.status.label}")
        appendFooter(lines, senderName)
        return lines.joinToString("\n")
    }

    private fun buildPreLeasedPreview(property: PropertyItem, senderName: String?): String {
        val lines = mutableListOf<String>()
        val code = property.propertyCode.trim().ifBlank { property.tab.code }
        lines.add("💎 *Pre-Leased Commercial Investment*")
        lines.add("🧾 *Code:* $code · *Project:* ${property.projectName.ifBlank { "Pune IT Park / Commercial" }}")
        lines.add("📍 *Location:* ${property.location.ifBlank { "East Pune" }}")
        if (property.carpetAreaSqFt > 0) lines.add("📐 *Carpet Area:* ${formatNumber(property.carpetAreaSqFt)} sq.ft")
        val monthlyRent = property.monthlyRentalIncome?.takeIf { it > 0 } ?: property.rent?.takeIf { it > 0 }
        if (monthlyRent != null) lines.add("💵 *Monthly Rental Income:* ${CalculationUtils.formatIndianCurrency(monthlyRent, isMonthly = false)} / month")
        val sale = resolveAskingAmount(property, isRental = false)
        if (sale != null) lines.add("💰 *Asking Price:* ${CalculationUtils.formatIndianCurrency(sale, isMonthly = false)}")
        if (monthlyRent != null && sale != null && sale > 0) {
            val grossYield = (monthlyRent * 12.0 / sale) * 100.0
            lines.add("📈 *Indicative Gross Yield:* ${String.format(Locale.ENGLISH, "%.2f", grossYield)}% p.a.")
        }
        val lock = formatLockInAndLease(property)
        if (lock != null) lines.add("🔒 *Lease Term & Lock-in:* $lock")
        val esc = formatEscalation(property)
        if (esc != null) lines.add("📈 *Escalation:* $esc")
        lines.add("📌 *Status:* ${property.status.label}")
        appendFooter(lines, senderName)
        return lines.joinToString("\n")
    }

    private fun buildResidentialProjectPreview(property: PropertyItem, senderName: String?): String {
        val lines = mutableListOf<String>()
        val code = property.propertyCode.trim().ifBlank { property.tab.code }
        lines.add("🏡 *Residential Property*")
        lines.add("🧾 *Code:* $code · *Project:* ${property.projectName.ifBlank { "Residential Residences" }}")
        lines.add("📍 *Location:* ${property.location.ifBlank { "Pune" }}")
        if (!property.bhk.isNullOrBlank()) lines.add("🛏 *Configuration:* ${property.bhk}")
        if (property.carpetAreaSqFt > 0) lines.add("📐 *Carpet Area:* ${formatNumber(property.carpetAreaSqFt)} sq.ft")
        val isRental = property.tab.isRental
        val amount = resolveAskingAmount(property, isRental)
        if (amount != null) {
            val label = if (isRental) "Monthly Rent" else "Asking Price"
            val suffix = if (isRental) " / month" else ""
            lines.add("💰 *$label:* ${CalculationUtils.formatIndianCurrency(amount, isMonthly = false)}$suffix")
        }
        if (property.possession.isNotBlank()) lines.add("🔑 *Possession:* ${property.possession}")
        lines.add("📌 *Status:* ${property.status.label}")
        appendFooter(lines, senderName)
        return lines.joinToString("\n")
    }

    private fun buildSiteVisitConfirmation(property: PropertyItem, senderName: String?): String {
        val lines = mutableListOf<String>()
        val code = property.propertyCode.trim().ifBlank { property.tab.code }
        lines.add("🗓 *Site Visit Confirmation*")
        lines.add("Dear Client, your site visit has been scheduled:")
        lines.add("🏛 *Project:* ${property.projectName.ifBlank { "Commercial Hub" }} (Code: $code)")
        lines.add("📍 *Location / Meeting Point:* ${property.location.ifBlank { "Pune" }}")
        if (property.unitNumber.isNotBlank()) lines.add("🚪 *Unit / Tower:* ${property.unitNumber}")
        lines.add("Our executive will coordinate access at the site upon your arrival.")
        appendFooter(lines, senderName)
        return lines.joinToString("\n")
    }

    private fun buildFollowUpMessage(property: PropertyItem, senderName: String?): String {
        val lines = mutableListOf<String>()
        val code = property.propertyCode.trim().ifBlank { property.tab.code }
        lines.add("👋 *Property Follow-Up*")
        lines.add("Following up on your interest in *${property.projectName.ifBlank { "our property" }}* (Code: $code) in ${property.location.ifBlank { "Pune" }}.")
        lines.add("Current status: *${property.status.label}*.")
        lines.add("Please let us know if you would like to schedule a visit or explore updated commercials.")
        appendFooter(lines, senderName)
        return lines.joinToString("\n")
    }

    private fun appendFooter(lines: MutableList<String>, senderName: String?) {
        val cleanSender = senderName?.trim()?.takeIf {
            it.isNotBlank() &&
                !it.equals("Unregistered", true) &&
                !it.equals("Read-Only User", true) &&
                !it.equals("User", true)
        }
        lines.add("")
        if (cleanSender != null) lines.add("Shared by: $cleanSender")
        lines.add("For details & site visits: 📞 $DESK_CONTACTS")
    }


    private fun resolveTransactionType(property: PropertyItem): StandardTransactionType {
        val raw = property.transactionType.trim().lowercase(Locale.ENGLISH)
        if (property.tab == InventoryTab.PRE_LEASED || raw.contains("pre-lease") || raw.contains("pre leased")) {
            return StandardTransactionType.PRE_LEASED_SALE
        }
        return if (property.tab.isRental) {
            StandardTransactionType.LEASE
        } else {
            StandardTransactionType.SALE
        }
    }

    private fun resolvePublicPropertyType(property: PropertyItem): String {
        val raw = property.propertyType.trim()
        val generic = raw.equals("Commercial", true) || raw.equals("Residential", true) || raw.isBlank()
        return if (!generic) {
            raw
        } else {
            when (property.tab) {
                InventoryTab.OFF_LEASE, InventoryTab.OFF_OUT -> "Commercial Office"
                InventoryTab.SHOP_LEASE, InventoryTab.SHOP_OUT -> "Retail Shop / Showroom"
                InventoryTab.PRE_LEASED -> "Pre-Leased Commercial"
                InventoryTab.RESI_RENT, InventoryTab.RESI_RESALE -> "Residential Property"
                InventoryTab.UND_CONST -> "Under-Construction Residential"
            }
        }
    }

    private fun resolveAskingAmount(property: PropertyItem, isRental: Boolean): Double? {
        val primary = if (isRental) {
            property.rent?.takeIf { it.isFinite() && it > 0.0 }
        } else {
            property.priceSale?.takeIf { it.isFinite() && it > 0.0 }
        }
        return primary ?: property.priceOrRent.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun buildConfigurationSummary(property: PropertyItem): String? {
        val parts = mutableListOf<String>()
        property.bhk?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        property.workstations?.takeIf { it > 0 }?.let { parts.add("$it Workstations") }
        property.cabins?.takeIf { it > 0 }?.let { parts.add("$it Cabins") }
        property.conferenceRooms?.takeIf { it > 0 }?.let { parts.add("$it Conference Room(s)") }
        property.balconies?.takeIf { it > 0 }?.let { parts.add("$it Balconies") }
        property.frontageFeet?.takeIf { it.isFinite() && it > 0.0 }?.let {
            parts.add("${formatNumber(it)} ft Frontage")
        }
        property.ceilingHeightFeet?.takeIf { it.isFinite() && it > 0.0 }?.let {
            parts.add("${formatNumber(it)} ft Ceiling")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }

    private fun formatMaintenanceDisplay(property: PropertyItem): String? {
        val publicNoteLower = property.remarks?.lowercase(Locale.ENGLISH).orEmpty()
        val explicitIncluded = Regex("""maintenance\s*(is\s*)?(included|inclusive)""").containsMatchIn(publicNoteLower)
        val explicitExtra = Regex("""maintenance\s*(is\s*)?(extra|excluded|additional|paid by licensee|paid by tenant)""")
            .containsMatchIn(publicNoteLower) ||
            publicNoteLower.contains("maintenance") && (publicNoteLower.contains("paid by licensee") || publicNoteLower.contains("paid by tenant"))
        val explicitPerSqFt = publicNoteLower.contains("maintenance") &&
            (publicNoteLower.contains("/sq.ft") || publicNoteLower.contains("per sq.ft") || publicNoteLower.contains("psf"))

        val amount = property.maintenance?.takeIf { it.isFinite() && it >= 0.0 }
        return when {
            amount != null && amount > 0.0 -> {
                val base = CalculationUtils.formatIndianCurrency(amount, isMonthly = false)
                val basisSuffix = buildList {
                    if (explicitPerSqFt) add("per sq.ft")
                    if (explicitIncluded) add("included")
                    else if (explicitExtra) add("extra")
                }.joinToString(", ")
                if (basisSuffix.isNotBlank()) "$base ($basisSuffix)" else base
            }
            amount == 0.0 && explicitIncluded -> "Included"
            explicitIncluded -> "Included"
            explicitExtra -> "Extra"
            else -> null
        }
    }

    private fun formatLockInAndLease(property: PropertyItem): String? {
        val leaseYears = property.leaseDurationYears?.takeIf { it > 0 }
        val lockInYears = property.lockInPeriodYears?.takeIf { it > 0 }
        return when {
            leaseYears != null && lockInYears != null -> "$leaseYears yr(s) lease, $lockInYears yr(s) lock-in"
            lockInYears != null -> "$lockInYears yr(s) lock-in"
            leaseYears != null -> "$leaseYears yr(s) lease term"
            else -> null
        }
    }

    private fun formatEscalation(property: PropertyItem): String? {
        val pct = property.escalationPercent?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val interval = property.escalationIntervalYears?.takeIf { it > 0 }
        val pctText = "${formatNumber(pct)}%"
        return if (interval != null) "$pctText every $interval yr(s)" else pctText
    }

    internal fun sanitizePublicListingUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.ENGLISH) ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = parsed.host?.lowercase(Locale.ENGLISH)?.takeIf { it.isNotBlank() } ?: return null
        if (host == "localhost" || host == "127.0.0.1" || host == "docs.google.com") return null

        val builder = parsed.buildUpon().clearQuery()
        val queryNames = runCatching { parsed.queryParameterNames }.getOrNull().orEmpty()
        for (name in queryNames) {
            val lower = name.lowercase(Locale.ENGLISH)
            val isPrivateOrTracking = lower.startsWith("utm_") ||
                lower in PRIVATE_URL_QUERY_KEYS ||
                lower.contains("owner") ||
                lower.contains("broker") ||
                lower.contains("token") ||
                lower.contains("phone") ||
                lower.contains("contact") ||
                lower.contains("internal")
            if (!isPrivateOrTracking) {
                parsed.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
        }
        return builder.build().toString()
    }

    private fun formatNumber(value: Double): String = areaDecimalFormat.format(value)

    private fun formatRate(rate: Double, isRental: Boolean): String {
        val formatted = CalculationUtils.formatIndianCurrencyFull(rate)
        return if (isRental) "$formatted/sq.ft/month" else "$formatted/sq.ft"
    }
}
