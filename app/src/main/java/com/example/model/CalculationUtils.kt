package com.example.model

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class AreaRateBasis(val label: String) {
    CARPET("Carpet"),
    BUILT_UP("Built-up")
}

object CalculationUtils {

    private val englishSymbols = DecimalFormatSymbols(Locale.ENGLISH)

    /**
     * Formats Indian currency into compact format: ₹30k, ₹1.25 Lac, ₹4.53 Cr.
     */
    fun formatIndianCurrency(amount: Double, isMonthly: Boolean = false): String {
        val suffix = if (isMonthly) "/mo" else ""
        if (!amount.isFinite() || amount <= 0) return "₹0$suffix"

        val formatted = when {
            amount >= 10_000_000.0 -> { // 1 Crore+
                val cr = amount / 10_000_000.0
                val df = DecimalFormat("#.##", englishSymbols).apply {
                    roundingMode = RoundingMode.HALF_UP
                }
                "₹${df.format(cr)} Cr"
            }
            amount >= 100_000.0 -> { // 1 Lakh+
                val lac = amount / 100_000.0
                val df = DecimalFormat("#.##", englishSymbols).apply {
                    roundingMode = RoundingMode.HALF_UP
                }
                "₹${df.format(lac)} Lac"
            }
            amount >= 1_000.0 -> { // 1 Thousand+
                val k = amount / 1_000.0
                val df = DecimalFormat("#.#", englishSymbols).apply {
                    roundingMode = RoundingMode.HALF_UP
                }
                "₹${df.format(k)}k"
            }
            else -> {
                val df = DecimalFormat("#,##0", englishSymbols).apply {
                    roundingMode = RoundingMode.HALF_UP
                }
                "₹${df.format(amount)}"
            }
        }
        return "$formatted$suffix"
    }

    private fun formatSmartUnitValue(amount: Double, isMonthly: Boolean): String {
        val suffix = if (isMonthly) "/mo" else ""
        if (!amount.isFinite() || amount <= 0.0) return "₹0$suffix"

        val formatted = when {
            amount >= 10_000_000.0 -> {
                val scaled = kotlin.math.round((amount / 10_000_000.0) * 100.0) / 100.0
                val isWhole = kotlin.math.abs(scaled - kotlin.math.round(scaled)) < 0.0001
                val df = DecimalFormat(if (isWhole) "#" else "0.00", englishSymbols).apply {
                    roundingMode = RoundingMode.HALF_UP
                }
                "₹${df.format(scaled)} Cr"
            }
            amount >= 100_000.0 -> {
                val scaled = kotlin.math.round((amount / 100_000.0) * 100.0) / 100.0
                val isWhole = kotlin.math.abs(scaled - kotlin.math.round(scaled)) < 0.0001
                val df = DecimalFormat(if (isWhole) "#" else "0.00", englishSymbols).apply {
                    roundingMode = RoundingMode.HALF_UP
                }
                "₹${df.format(scaled)} Lac"
            }
            amount >= 1_000.0 -> {
                val k = amount / 1_000.0
                val df = DecimalFormat("#.#", englishSymbols).apply {
                    roundingMode = RoundingMode.HALF_UP
                }
                "₹${df.format(k)}k"
            }
            else -> {
                val df = DecimalFormat("#,##0", englishSymbols).apply {
                    roundingMode = RoundingMode.HALF_UP
                }
                "₹${df.format(amount)}"
            }
        }
        return "$formatted$suffix"
    }

    /**
     * Parses any raw numeric string or range into clean short-form Indian currency:
     * - "22500000" -> "₹2.25 Cr"
     * - "11000000-15000000" -> "₹1.10 Cr – ₹1.50 Cr"
     * - "40000" (isMonthly = true) -> "₹40k/mo"
     */
    fun formatSmartIndianAmount(raw: String, isMonthly: Boolean = false): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""

        val lower = trimmed.lowercase(Locale.ENGLISH)
        val effectiveMonthly = isMonthly ||
            lower.contains("/mo") ||
            lower.contains("per month") ||
            lower.contains("monthly")

        val strippedMonthly = trimmed
            .replace(Regex("/\\s*mo(?:nth)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bper\\s+month\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bmonthly\\b", RegexOption.IGNORE_CASE), "")
            .trim()

        val pureNumeric = strippedMonthly.replace(",", "").replace("₹", "").trim().toDoubleOrNull()
        if (pureNumeric != null && pureNumeric > 0.0) {
            return formatSmartUnitValue(pureNumeric, isMonthly = effectiveMonthly)
        }

        val rangeParts = strippedMonthly.split(Regex("\\s*(?:-|–|—|\\bto\\b)\\s*", RegexOption.IGNORE_CASE))
        if (rangeParts.size == 2 && rangeParts[0].isNotBlank() && rangeParts[1].isNotBlank()) {
            val rightStrRaw = rangeParts[1].trim()
            val leftStrRaw = rangeParts[0].trim()
            val rightLower = rightStrRaw.lowercase(Locale.ENGLISH)
            val leftLower = leftStrRaw.lowercase(Locale.ENGLISH)
            val rightHasUnit = rightLower.contains("cr") || rightLower.contains("lac") || rightLower.contains("lakh") || rightLower.contains("l") || rightLower.contains("k")
            val leftHasUnit = leftLower.contains("cr") || leftLower.contains("lac") || leftLower.contains("lakh") || leftLower.contains("l") || leftLower.contains("k")
            val effectiveLeftRaw = if (!leftHasUnit && rightHasUnit) {
                val inheritedSuffix = when {
                    rightLower.contains("cr") -> " Cr"
                    rightLower.contains("lac") || rightLower.contains("lakh") || rightLower.contains("l") -> " Lac"
                    rightLower.contains("k") -> " k"
                    else -> ""
                }
                "$leftStrRaw$inheritedSuffix"
            } else {
                leftStrRaw
            }
            val left = parseCurrencyInput(effectiveLeftRaw)
            val right = parseCurrencyInput(rightStrRaw)
            if (left != null && right != null && left > 0.0 && right > 0.0) {
                val leftStr = formatSmartUnitValue(left, isMonthly = false)
                val rightStr = formatSmartUnitValue(right, isMonthly = effectiveMonthly)
                return "$leftStr – $rightStr"
            }
        }

        if (!strippedMonthly.contains("+") && !strippedMonthly.contains("above", ignoreCase = true)) {
            val singleShorthand = parseCurrencyInput(strippedMonthly)
            if (singleShorthand != null && singleShorthand > 0.0) {
                return formatSmartUnitValue(singleShorthand, isMonthly = effectiveMonthly)
            }
        }

        return if (effectiveMonthly && !trimmed.contains("/mo", ignoreCase = true)) {
            "$trimmed/mo"
        } else {
            trimmed
        }
    }

    /**
     * Formats full Indian currency with commas: ₹80,000 or ₹1,25,000
     */
    fun formatIndianCurrencyFull(amount: Double): String {
        if (!amount.isFinite() || amount <= 0) return "₹0"
        val n = kotlin.math.round(amount).toLong()
        val s = n.toString()
        if (s.length <= 3) return "₹$s"
        val lastThree = s.takeLast(3)
        val rest = s.dropLast(3)
        val grouped = rest.reversed().chunked(2).joinToString(",").reversed()
        return "₹$grouped,$lastThree"
    }

    /**
     * Formats currency for display: uses full Indian comma format under 10 Lakh and compact Cr/Lac above.
     */
    fun formatCurrency(amount: Double): String {
        if (!amount.isFinite() || amount <= 0.0) return "₹0"
        return if (amount >= 10_000_000.0) {
            formatIndianCurrency(amount, isMonthly = false)
        } else {
            formatIndianCurrencyFull(amount)
        }
    }

    /**
     * Parses currency input string supporting shorthand suffixes (K, L, Lac, Lakh, Cr, Crore),
     * scientific notation (e.g. 1.4E8, 2.25E7), currency prefixes ("Rs.", "INR", "₹"), and raw digits.
     */
    fun parseCurrencyInput(input: String): Double? {
        if (input.isBlank()) return null
        val cleaned = input.trim()
            .replace(",", "")
            .replace("₹", "")
            .replace(Regex("""\b(?:rs\.?|inr)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""/\s*mo(?:nth)?|\bper\s+month\b|\bmonthly\b""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleaned.isBlank()) return null

        val exactSciMatch = Regex("""^[+-]?\d+(?:\.\d+)?[eE][+-]?\d+$""").matches(cleaned)
        if (exactSciMatch) {
            return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        }

        val match = Regex(
            """([+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\s*(crores?|cr\.?|lakhs?|lacs?|l\.?|thousands?|k)?""",
            RegexOption.IGNORE_CASE
        ).find(cleaned) ?: return null

        val numPart = match.groupValues[1].toDoubleOrNull() ?: return null
        if (!numPart.isFinite()) return null
        val unitPart = match.groupValues[2].lowercase(Locale.ENGLISH).trimEnd('.')
        return when {
            unitPart.startsWith("cr") -> numPart * 10_000_000.0
            unitPart == "l" || unitPart.startsWith("lac") || unitPart.startsWith("lakh") -> numPart * 100_000.0
            unitPart == "k" || unitPart.startsWith("thousand") -> numPart * 1_000.0
            else -> numPart
        }
    }


    /**
     * Formats area or numeric measurements with English commas (e.g. 1,100 or 12.5).
     */
    fun formatNumber(value: Double): String {
        if (!value.isFinite() || value <= 0.0) return "0"
        val df = if (value % 1.0 == 0.0) {
            DecimalFormat("#,##0", englishSymbols)
        } else {
            DecimalFormat("#,##0.##", englishSymbols)
        }
        return df.format(value)
    }

    /**
     * Formats rate per sq.ft with up to 2 decimal places (e.g., ₹72.73 or ₹12,500).
     */
    fun formatRateValue(rate: Double?): String {
        if (rate == null || !rate.isFinite() || rate <= 0.0) return "Not Available"
        val df = if (rate >= 100.0) {
            DecimalFormat("#,##0", englishSymbols).apply {
                roundingMode = RoundingMode.HALF_UP
            }
        } else {
            DecimalFormat("#,##0.##", englishSymbols).apply {
                roundingMode = RoundingMode.HALF_UP
            }
        }
        return "₹${df.format(rate)}"
    }

    /**
     * Normalizes bare Crore figures (e.g., 14.0 or 22.0 from "14 Cr" / "22 Cr") into full INR rupees.
     * In real estate Sale / Pre-Leased listings, no property costs between ₹0.25 and ₹500 total rupees.
     */
    fun normalizeSalePriceInr(raw: Double): Double {
        if (!raw.isFinite() || raw <= 0.0) return 0.0
        return if (raw in 0.25..500.0) raw * 10_000_000.0 else raw
    }

    /**
     * Resolves the effective sale price in full INR rupees for a property.
     */
    fun effectiveSaleAmount(property: PropertyItem): Double {
        val raw = property.priceSale?.takeIf { it > 0.0 }
            ?: if (!property.tab.isRental) property.priceOrRent else 0.0
        return normalizeSalePriceInr(raw)
    }

    /**
     * Resolves the primary financial amount for a property (Monthly Rent for Lease; Asking Price for Sale/Pre-Leased).
     */
    fun primaryAmountForProperty(property: PropertyItem): Double {
        val stdTx = StandardTransactionType.fromString(property.transactionType, property.tab)
        return when (stdTx) {
            StandardTransactionType.LEASE -> {
                val raw = property.rent?.takeIf { it > 0.0 } ?: property.priceOrRent
                if (raw.isFinite() && raw > 0.0) raw else 0.0
            }
            StandardTransactionType.SALE, StandardTransactionType.PRE_LEASED_SALE -> {
                val raw = property.priceSale?.takeIf { it > 0.0 } ?: property.priceOrRent
                normalizeSalePriceInr(raw)
            }
        }
    }

    /**
     * Calculates rate per sq.ft for the selected basis (CARPET or BUILT_UP).
     * Never invents or estimates missing built-up area.
     */
    fun calculateRateForBasis(property: PropertyItem, basis: AreaRateBasis): Double? {
        val amount = primaryAmountForProperty(property)
        if (amount <= 0.0) return null
        val selectedArea = when (basis) {
            AreaRateBasis.CARPET -> property.carpetAreaSqFt
            AreaRateBasis.BUILT_UP -> property.builtUpAreaSqFt ?: 0.0
        }
        if (!selectedArea.isFinite() || selectedArea <= 0.0) return null
        return amount / selectedArea
    }

    /**
     * Formats the rate per sq.ft string for cards and details:
     * e.g., "₹72.73 / sq.ft. (Carpet)" or "Not Available"
     */
    fun formatRateForBasis(
        property: PropertyItem,
        basis: AreaRateBasis,
        includeBasisSuffix: Boolean = true
    ): String {
        val rate = calculateRateForBasis(property, basis) ?: return "Not Available"
        val baseText = "${formatRateValue(rate)} / sq.ft."
        return if (includeBasisSuffix) "$baseText (${basis.label})" else baseText
    }

    /**
     * Sale rate per carpet sq.ft = Price / Carpet Sq.Ft
     */
    fun calculateRatePerCarpet(price: Double, carpetSqFt: Double): Double? {
        if (!price.isFinite() || !carpetSqFt.isFinite() || price <= 0 || carpetSqFt <= 0) return null
        return price / carpetSqFt
    }

    /**
     * Sale rate per built-up sq.ft = Price / Built-up Sq.Ft
     */
    fun calculateRatePerBuiltUp(price: Double, builtUpSqFt: Double?): Double? {
        if (builtUpSqFt == null || !builtUpSqFt.isFinite() || builtUpSqFt <= 0 || !price.isFinite() || price <= 0) return null
        return price / builtUpSqFt
    }

    /**
     * Rent per carpet sq.ft = Monthly Rent / Carpet Sq.Ft
     */
    fun calculateRentPerCarpet(monthlyRent: Double, carpetSqFt: Double): Double? {
        if (!monthlyRent.isFinite() || !carpetSqFt.isFinite() || monthlyRent <= 0 || carpetSqFt <= 0) return null
        return monthlyRent / carpetSqFt
    }

    /**
     * Rent per built-up sq.ft = Monthly Rent / Built-up Sq.Ft
     */
    fun calculateRentPerBuiltUp(monthlyRent: Double, builtUpSqFt: Double?): Double? {
        if (builtUpSqFt == null || !builtUpSqFt.isFinite() || builtUpSqFt <= 0 || !monthlyRent.isFinite() || monthlyRent <= 0) return null
        return monthlyRent / builtUpSqFt
    }

    /**
     * Built-up Area = Carpet Area * (1 + Loading% / 100)
     */
    fun calculateBuiltUpFromCarpet(carpetSqFt: Double, loadingPercent: Double): Double {
        if (carpetSqFt <= 0) return 0.0
        return carpetSqFt * (1.0 + (loadingPercent / 100.0))
    }

    /**
     * Carpet-to-built-up loading percentage = ((BuiltUp - Carpet) / Carpet) * 100
     */
    fun calculateLoadingPercentage(carpetSqFt: Double, builtUpSqFt: Double?): Double? {
        if (builtUpSqFt == null || !builtUpSqFt.isFinite() || !carpetSqFt.isFinite() || builtUpSqFt <= carpetSqFt || carpetSqFt <= 0) return null
        return ((builtUpSqFt - carpetSqFt) / carpetSqFt) * 100.0
    }

    /**
     * Monthly Base Rent = Rent Rate * Applicable Area
     */
    fun calculateMonthlyRentFromRate(ratePerSqFt: Double, applicableAreaSqFt: Double): Double {
        if (ratePerSqFt <= 0 || applicableAreaSqFt <= 0) return 0.0
        return ratePerSqFt * applicableAreaSqFt
    }

    /**
     * Rent Rate per sq.ft = Monthly Rent / Applicable Area
     */
    fun calculateRentRatePerSqFt(monthlyRent: Double, applicableAreaSqFt: Double): Double? {
        if (!monthlyRent.isFinite() || !applicableAreaSqFt.isFinite() || monthlyRent <= 0 || applicableAreaSqFt <= 0) return null
        return monthlyRent / applicableAreaSqFt
    }

    /**
     * Deposit Amount = Confirmed Monthly Rent * Deposit Months
     */
    fun calculateDepositAmount(monthlyRent: Double, depositMonths: Double): Double {
        if (monthlyRent <= 0 || depositMonths <= 0) return 0.0
        return monthlyRent * depositMonths
    }

    /**
     * Annual Rent = Monthly Rent * 12
     */
    fun calculateAnnualRent(monthlyRent: Double): Double {
        return if (monthlyRent > 0 && monthlyRent.isFinite()) monthlyRent * 12.0 else 0.0
    }

    /**
     * Gross Rental Yield (%) = (Annual Rent / Purchase Price) * 100
     */
    fun calculateGrossYield(annualOrMonthlyRent: Double, purchasePrice: Double, isMonthly: Boolean = true): Double? {
        if (!purchasePrice.isFinite() || !annualOrMonthlyRent.isFinite() || purchasePrice <= 0 || annualOrMonthlyRent <= 0) return null
        val annualRent = if (isMonthly) annualOrMonthlyRent * 12.0 else annualOrMonthlyRent
        return (annualRent / purchasePrice) * 100.0
    }

    /**
     * Net ROI (%) = ((Annual Rental Income - Annual Expenses) / Total Investment Cost) * 100
     */
    fun calculateNetRoi(
        monthlyRent: Double,
        monthlyMaintenance: Double = 0.0,
        otherAnnualExpenses: Double = 0.0,
        purchasePrice: Double
    ): Double? {
        if (purchasePrice <= 0 || monthlyRent <= 0) return null
        val netAnnualIncome = (monthlyRent * 12.0) - (monthlyMaintenance * 12.0) - otherAnnualExpenses
        return (netAnnualIncome / purchasePrice) * 100.0
    }

    /**
     * Rent escalation projections (e.g. 5% annual or 15% 3-yearly)
     */
    fun calculateEscalatedRent(currentRent: Double, escalationPercent: Double, periods: Int = 1): Double {
        var rent = currentRent
        repeat(periods) {
            rent += (rent * (escalationPercent / 100.0))
        }
        return rent
    }

    /**
     * Computes data completeness score (0 to 100%) for a property record
     */
    fun computeCompletenessScore(property: PropertyItem): Int = com.example.inventory.InventoryQuality.completeness(property)

    fun computeDetailedCompleteness(property: PropertyItem): CompletenessBreakdown {
        val completed = mutableListOf<String>()
        val missing = mutableListOf<String>()

        fun evaluate(fieldName: String, isPresent: Boolean) {
            if (isPresent) completed.add(fieldName) else missing.add(fieldName)
        }

        evaluate("Project / Building", property.projectName.isNotBlank())
        evaluate("Location", property.location.isNotBlank())
        evaluate("Carpet Area", property.carpetAreaSqFt > 0.0)
        evaluate("Unit Number", property.unitNumber.isNotBlank())
        evaluate("Photos", property.images.isNotEmpty())

        val stdTx = StandardTransactionType.fromString(property.transactionType, property.tab)
        val isLease = stdTx == StandardTransactionType.LEASE
        val isCommercial = property.isCommercial()

        if (isLease) {
            evaluate("Monthly Rent", (property.rent ?: 0.0) > 0.0 || property.priceOrRent > 0.0)
            evaluate("Deposit", (property.deposit ?: 0.0) > 0.0)
        } else {
            evaluate("Asking Price", (property.priceSale ?: 0.0) > 0.0 || property.priceOrRent > 0.0)
        }

        if (property.tab == InventoryTab.PRE_LEASED) {
            evaluate("ROI %", (property.roiPercent ?: 0.0) > 0.0)
            evaluate("Tenant Name", !property.tenantName.isNullOrBlank())
        }

        if (isCommercial) {
            evaluate("Property Type", property.propertyType.isNotBlank())
            evaluate("Furnishing", property.furnishing != FurnishingType.UNFURNISHED)
        } else {
            evaluate("BHK Configuration", !property.bhk.isNullOrBlank())
        }

        val total = completed.size + missing.size
        val percent = if (total > 0) (completed.size * 100) / total else 0
        return CompletenessBreakdown(percent, completed, missing)
    }

    /**
     * Formats an amount in compact Indian currency for CRM cards & details:
     * 22500000 -> "₹2.25 Cr", 120000 (monthly) -> "₹1.20 Lac/mo", 30000 (monthly) -> "₹30k/mo".
     */
    fun formatIndianCurrencyCrm(amount: Double, isMonthly: Boolean = false): String {
        if (!amount.isFinite() || amount <= 0.0) return ""
        return formatSmartUnitValue(amount, isMonthly = isMonthly)
    }

    /**
     * Formats raw CRM budget / rent text (e.g. "22500000", "120000", "80000 - 120000")
     * using short-form Indian numbering (₹30k/mo, ₹1.20 Lac/mo, ₹2.25 Cr).
     */
    fun formatCrmBudgetDisplay(
        rawBudget: String,
        transactionOrRequirementHint: String = "",
        minInr: String = "",
        maxInr: String = ""
    ): String {
        val hintLower = transactionOrRequirementHint.trim().lowercase(Locale.ENGLISH)
        val isPreLeased = hintLower.contains("pre-leased") || hintLower.contains("pre leased")
        val rawLower = rawBudget.trim().lowercase(Locale.ENGLISH)
        val isMonthly = !isPreLeased && (
            hintLower.contains("lease") ||
                hintLower.contains("rent") ||
                rawLower.contains("/mo") ||
                rawLower.contains("per month") ||
                rawLower.contains("month")
            )

        val effectiveRaw = rawBudget.trim().ifBlank {
            val cleanMin = minInr.trim()
            val cleanMax = maxInr.trim()
            when {
                cleanMin.isNotBlank() && cleanMax.isNotBlank() && cleanMin != cleanMax -> "$cleanMin - $cleanMax"
                cleanMax.isNotBlank() -> cleanMax
                cleanMin.isNotBlank() -> cleanMin
                else -> ""
            }
        }
        if (effectiveRaw.isBlank()) return ""

        val smartFormatted = formatSmartIndianAmount(effectiveRaw, isMonthly = isMonthly)
        if (smartFormatted.isNotBlank()) return smartFormatted
        return effectiveRaw.trim()
    }

    /**
     * Returns live formatted preview text while editing Budget / Rent in AddEditLeadScreen.
     */
    fun formatCrmBudgetPreview(rawInput: String, isMonthly: Boolean = false): String {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return ""
        return formatSmartIndianAmount(trimmed, isMonthly = isMonthly)
    }
}

data class CompletenessBreakdown(
    val percent: Int,
    val completedFields: List<String>,
    val missingFields: List<String>
)

