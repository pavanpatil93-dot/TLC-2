package com.example.loi

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.model.FurnishingType
import com.example.model.PropertyItem
import com.example.model.escalation
import com.example.model.lockInPeriod
import com.example.ui.screens.CrmDealItem
import com.example.ui.screens.CrmLead
import com.example.ui.screens.displayClientTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

data class LoiDraftData(
    // Meta & Key Dates
    val loiRefCode: String = "",
    val loiDate: String = "",
    val place: String = "Pune",
    val propertyCode: String = "",
    val agreementStartDate: String = "",
    val handoverDate: String = "",
    val monthlyFeeStartDate: String = "",
    val agreementDurationMonths: String = "60",
    val noticePeriodMonths: String = "3",
    val loiValidityDate: String = "",
    val daysToSignAgreement: String = "15",

    // Premises
    val propertyName: String = "",
    val unitNumber: String = "",
    val floor: String = "",
    val wing: String = "",
    val locality: String = "",
    val cityState: String = "Pune, Maharashtra",
    val carpetAreaSqFt: String = "",
    val builtUpAreaSqFt: String = "",
    val furnishing: String = "Warm Shell",
    val parking4Wheeler: String = "",
    val parking2Wheeler: String = "",
    val parkingVisitor: String = "As per Society norms",

    // Parties & Legal/KYC
    val licensorName: String = "",
    val licenseeName: String = "",
    val licenseeRegisteredAddress: String = "",
    val licenseeAuthorizedRep: String = "",
    val licenseeDesignation: String = "",
    val licenseePan: String = "",
    val licenseeGst: String = "",
    val licenseeCin: String = "",
    val boardResolutionDate: String = "",

    // Financials
    val monthlyFeeInr: String = "",
    val securityDepositInr: String = "",
    val lockInMonths: String = "36",
    val escalationPercentYoy: String = "5",
    val maintenanceTerms: String = "Excluded / Paid at actuals by Licensee",

    // Utilities & Charges
    val internetService: String = "No",
    val operatingHours: String = "24/7 Access",
    val electricityTerms: String = "Paid at Actuals by Licensee",
    val maintenanceFeePerSqFtBua: String = "",
    val softServicesAmenities: String = "Society Common Area Maintenance, Security & Housekeeping",
    val additionalParkingFee: String = "As per Society norms / mutual agreement",
    val stampDutyRegistration: String = "50-50 equally by Licensor & Licensee",

    // Editable Standard Clauses
    val terminationDefaultClause: String = DEFAULT_TERMINATION_CLAUSE,
    val forceMajeureClause: String = DEFAULT_FORCE_MAJEURE_CLAUSE,
    val bindingAndTaxClause: String = DEFAULT_BINDING_TAX_CLAUSE,

    // Optional Scope & Annexure
    val ownerScopeOfWork: String = "",
    val tenantScopeOfWork: String = "",
    val furnitureListAnnexureA: String = "",

    // Separate Brokerage Page Toggles
    val includeLicenseeBrokeragePage: Boolean = false,
    val includeLicensorBrokeragePage: Boolean = false
) {
    fun durationMonthsInt(): Int = agreementDurationMonths.filter { it.isDigit() }.toIntOrNull() ?: 60

    fun monthlyFeeDouble(): Double = parseNumeric(monthlyFeeInr)

    fun securityDepositDouble(): Double = parseNumeric(securityDepositInr)

    fun brokerageMonthsSlab(): Int = if (durationMonthsInt() > 36) 2 else 1

    fun brokerageSlabLabel(): String = if (brokerageMonthsSlab() == 2) {
        "2 Months' License Fee (Agreement tenure above 3 years)"
    } else {
        "1 Month's License Fee (Agreement tenure up to 3 years)"
    }

    fun computedBrokerageInr(): Double = monthlyFeeDouble() * brokerageMonthsSlab()

    companion object {
        const val DEFAULT_TERMINATION_CLAUSE =
            "In the event the Licensee defaults in paying the Monthly License Fee or Maintenance/Utility dues for a continuous period of 30 (Thirty) days from the due date, the Licensor shall issue a written cure notice granting 7 (Seven) days to rectify the default. Should the Licensee fail to cure within the said 7-day cure period, the Licensor shall be entitled to terminate the Leave and License Agreement by serving a 23 (Twenty-Three) days' written termination notice and recover liquidated damages equivalent to 60 (Sixty) days' License Fee along with all outstanding arrears."

        const val DEFAULT_FORCE_MAJEURE_CLAUSE =
            "Neither Party shall be held liable for any delay or failure in performing obligations due to Force Majeure events including natural disasters, acts of God, pandemic lockdowns, government orders, or civil unrest. If such Force Majeure event continues beyond 60 (Sixty) consecutive days preventing peaceful occupation or operation of the Premises, either Party may mutually extend the term without rent liability for the affected period or terminate this arrangement with full refund of the unadjusted Security Deposit."

        const val DEFAULT_BINDING_TAX_CLAUSE =
            "This Letter of Intent (LOI) constitutes a binding commercial commitment between the Parties to execute the formal Leave and License Agreement within the stipulated timeline upon completion of due diligence. Applicable TDS under the Income Tax Act shall be deducted by the Licensee on the License Fee and deposited timely with Form 16A certificates furnished quarterly. Municipal Property Tax and structural statutory levies shall be borne exclusively by the Licensor."

        fun parseNumeric(raw: String): Double {
            val cleaned = raw.trim().replace(",", "").replace("₹", "").lowercase(Locale.ENGLISH)
            if (cleaned.isBlank()) return 0.0
            val num = Regex("(\\d+(?:\\.\\d+)?)").find(cleaned)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?: return 0.0
            return when {
                cleaned.contains("cr") -> num * 10_000_000.0
                cleaned.contains("lac") || cleaned.contains("lakh") -> num * 100_000.0
                else -> num
            }
        }
    }
}

object IndianCurrencyWords {
    private val ones = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen"
    )
    private val tens = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    )

    fun formatIndianNumber(amount: Double): String {
        val rounded = amount.roundToLong()
        if (rounded <= 0L) return "0"
        val s = rounded.toString()
        if (s.length <= 3) return s
        val lastThree = s.takeLast(3)
        val rest = s.dropLast(3)
        val groupedRest = rest.reversed().chunked(2).joinToString(",").reversed()
        return "$groupedRest,$lastThree"
    }

    fun convertLongToWords(value: Long): String {
        if (value <= 0L) return "Zero Only"
        val sb = StringBuilder()
        var n = value
        val crores = n / 10_000_000L
        if (crores > 0) {
            sb.append(convertUnderHundredOrThousand(crores)).append(" Crore ")
            n %= 10_000_000L
        }
        val lakhs = n / 100_000L
        if (lakhs > 0) {
            sb.append(convertUnderHundredOrThousand(lakhs)).append(" Lakh ")
            n %= 100_000L
        }
        val thousands = n / 1_000L
        if (thousands > 0) {
            sb.append(convertUnderHundredOrThousand(thousands)).append(" Thousand ")
            n %= 1_000L
        }
        if (n > 0) {
            sb.append(convertUnderHundredOrThousand(n))
        }
        return "${sb.toString().trim().replace(Regex("\\s+"), " ")} Only"
    }

    private fun convertUnderHundredOrThousand(num: Long): String {
        if (num == 0L) return ""
        val n = num.toInt()
        val sb = StringBuilder()
        val hundreds = n / 100
        val rem = n % 100
        if (hundreds > 0) {
            sb.append(ones[hundreds]).append(" Hundred ")
        }
        if (rem > 0) {
            if (rem < 20) {
                sb.append(ones[rem])
            } else {
                sb.append(tens[rem / 10])
                if (rem % 10 > 0) sb.append(" ").append(ones[rem % 10])
            }
        }
        return sb.toString().trim()
    }

    fun formatRsWithWords(amount: Double, blankFallback: String = "__________________"): String {
        val rounded = amount.roundToLong()
        if (rounded <= 0L) return blankFallback
        return "Rs. ${formatIndianNumber(amount)} (Rupees ${convertLongToWords(rounded)})"
    }
}

object LoiDraftFactory {
    private fun defaultDates(): Triple<String, String, String> {
        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
        val today = Date()
        val calValid = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }
        val calStart = Calendar.getInstance().apply {
            add(Calendar.MONTH, 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        return Triple(
            fmt.format(today),
            fmt.format(calValid.time),
            fmt.format(calStart.time)
        )
    }

    private fun parseDigits(text: String?, fallback: String): String {
        val digits = text.orEmpty().filter { it.isDigit() }
        return digits.ifBlank { fallback }
    }

    fun fromProperty(
        property: PropertyItem,
        lead: CrmLead? = null
    ): LoiDraftData {
        val (todayStr, validStr, nextMonthFirst) = defaultDates()
        val monthly = (property.rent ?: property.priceOrRent).takeIf { it > 0.0 } ?: 0.0
        val deposit = (property.deposit ?: 0.0).let { if (it > 0.0) it else if (monthly > 0.0) monthly * 3.0 else 0.0 }
        val lockInMos = when {
            (property.lockInMonths ?: 0) > 0 -> property.lockInMonths.toString()
            (property.lockInPeriodYears ?: 0) > 0 -> (property.lockInPeriodYears!! * 12).toString()
            else -> "36"
        }
        val escPercent = property.escalationPercent?.takeIf { it > 0.0 }?.toInt()?.toString() ?: "5"
        val wingRaw = property.unmappedColumns["Wing"]
            ?: property.unmappedColumns["Tower / Wing"]
            ?: property.unmappedColumns["Tower"]
            ?: ""
        val park4w = property.parkingCount?.takeIf { it > 0 }?.let { "$it Covered 4-Wheeler" }
            ?: property.parking.takeIf { it.isNotBlank() }.orEmpty()
        val maintText = when {
            property.maintenanceIncluded == true -> "Included in License Fee"
            (property.maintenance ?: 0.0) > 0.0 -> "Rs. ${property.maintenance!!.toInt()} / sq.ft. on BUA"
            else -> "Excluded / Paid at actuals by Licensee"
        }

        return LoiDraftData(
            loiRefCode = "LOI-${property.propertyCode.ifBlank { "TLC" }}-${SimpleDateFormat("yyMMdd", Locale.US).format(Date())}",
            loiDate = todayStr,
            place = "Pune",
            propertyCode = property.propertyCode.ifBlank { property.id },
            agreementStartDate = nextMonthFirst,
            handoverDate = nextMonthFirst,
            monthlyFeeStartDate = nextMonthFirst,
            agreementDurationMonths = "60",
            noticePeriodMonths = "3",
            loiValidityDate = validStr,
            daysToSignAgreement = "15",
            propertyName = property.projectName.ifBlank { "Commercial Premises" },
            unitNumber = property.unitNumber,
            floor = property.floor,
            wing = wingRaw,
            locality = property.location,
            cityState = if (property.city.isNotBlank()) "${property.city}, Maharashtra" else "Pune, Maharashtra",
            carpetAreaSqFt = property.carpetAreaSqFt.takeIf { it > 0.0 }?.toInt()?.toString().orEmpty(),
            builtUpAreaSqFt = property.builtUpAreaSqFt?.takeIf { it > 0.0 }?.toInt()?.toString().orEmpty(),
            furnishing = if (property.furnishing != FurnishingType.UNFURNISHED) property.furnishing.label else "Warm Shell / Unfurnished",
            parking4Wheeler = park4w,
            parking2Wheeler = "",
            parkingVisitor = "As per Society norms",
            licensorName = property.ownerName.orEmpty().ifBlank { property.builderName.orEmpty() },
            licenseeName = lead?.displayClientTitle()?.takeIf { it.isNotBlank() }
                ?: lead?.companyName?.takeIf { it.isNotBlank() }.orEmpty(),
            monthlyFeeInr = if (monthly > 0.0) monthly.roundToLong().toString() else "",
            securityDepositInr = if (deposit > 0.0) deposit.roundToLong().toString() else "",
            lockInMonths = lockInMos,
            escalationPercentYoy = escPercent,
            maintenanceTerms = maintText,
            maintenanceFeePerSqFtBua = property.maintenance?.takeIf { it > 0.0 }?.let { "Rs. ${it} / sq.ft. on BUA" }.orEmpty()
        )
    }

    fun fromDeal(
        deal: CrmDealItem,
        matchedProperty: PropertyItem? = null,
        matchedLead: CrmLead? = null
    ): LoiDraftData {
        val base = matchedProperty?.let { fromProperty(it, matchedLead) } ?: fromProperty(PropertyItem(), matchedLead)
        val (todayStr, validStr, nextMonthFirst) = defaultDates()
        val monthly = deal.monthlyRentOrPriceInr.takeIf { it > 0.0 } ?: base.monthlyFeeDouble()
        val deposit = deal.securityDepositInr.takeIf { it > 0.0 } ?: (monthly * 3.0)
        return base.copy(
            loiRefCode = "LOI-${deal.id}-${deal.propertyCode.ifBlank { "TLC" }}",
            loiDate = todayStr,
            propertyCode = deal.propertyCode.ifBlank { base.propertyCode },
            propertyName = deal.projectName.ifBlank { base.propertyName },
            locality = deal.location.ifBlank { base.locality },
            carpetAreaSqFt = deal.carpetAreaSqFt.takeIf { it > 0.0 }?.toInt()?.toString() ?: base.carpetAreaSqFt,
            builtUpAreaSqFt = deal.builtUpAreaSqFt.takeIf { it > 0.0 }?.toInt()?.toString() ?: base.builtUpAreaSqFt,
            licensorName = deal.ownerName.ifBlank { base.licensorName },
            licenseeName = deal.clientName.ifBlank { base.licenseeName },
            monthlyFeeInr = if (monthly > 0.0) monthly.roundToLong().toString() else base.monthlyFeeInr,
            securityDepositInr = if (deposit > 0.0) deposit.roundToLong().toString() else base.securityDepositInr,
            lockInMonths = parseDigits(deal.lockInPeriod, base.lockInMonths),
            escalationPercentYoy = Regex("(\\d+)%").find(deal.escalationTerms)?.groupValues?.getOrNull(1)
                ?: base.escalationPercentYoy,
            handoverDate = deal.possessionDate.ifBlank { nextMonthFirst },
            loiValidityDate = validStr
        )
    }

    fun fromLead(
        lead: CrmLead,
        matchedProperty: PropertyItem? = null
    ): LoiDraftData {
        return if (matchedProperty != null) {
            fromProperty(matchedProperty, lead)
        } else {
            val (todayStr, validStr, nextMonthFirst) = defaultDates()
            val rentNum = LoiDraftData.parseNumeric(lead.maxRentInr.ifBlank { lead.minRentInr.ifBlank { lead.budget } })
            val depositNum = if (rentNum > 0.0) rentNum * 3.0 else 0.0
            LoiDraftData(
                loiRefCode = "LOI-${lead.id}-${SimpleDateFormat("yyMMdd", Locale.US).format(Date())}",
                loiDate = todayStr,
                place = "Pune",
                agreementStartDate = nextMonthFirst,
                handoverDate = nextMonthFirst,
                monthlyFeeStartDate = nextMonthFirst,
                loiValidityDate = validStr,
                locality = lead.location.ifBlank { "Pune" },
                carpetAreaSqFt = lead.areaSqFt.filter { it.isDigit() },
                furnishing = lead.furnishing.ifBlank { "Warm Shell / Unfurnished" },
                licenseeName = lead.companyName.ifBlank { lead.displayClientTitle() },
                licenseeAuthorizedRep = lead.displayClientTitle(),
                monthlyFeeInr = if (rentNum > 0.0) rentNum.roundToLong().toString() else "",
                securityDepositInr = if (depositNum > 0.0) depositNum.roundToLong().toString() else ""
            )
        }
    }
}

object LoiPdfGenerator {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_H = 36f
    private const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_H * 2)
    private const val FOOTER_Y = PAGE_HEIGHT - 30f
    private const val MAX_Y = PAGE_HEIGHT - 54f

    private fun valOrBlank(raw: String): String = raw.trim().ifBlank { "__________________" }

    suspend fun generatePdfFile(context: Context, draft: LoiDraftData): File = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val writer = PdfPageWriter(doc, draft)

        // ── PAGE 1+: MAIN LOI ──
        writer.startFreshPage(fullLetterhead = true)

        // Title Banner
        writer.drawDocumentBanner(
            title = "LETTER OF INTENT (LOI)",
            subtitle = "Ref: ${draft.loiRefCode.ifBlank { "TLC-LOI" }}  |  Date: ${valOrBlank(draft.loiDate)}  |  Place: ${valOrBlank(draft.place)}"
        )

        // 1. PARTIES DETAILS
        writer.drawSectionHeader("1. PARTIES & LICENSEE KYC DETAILS")
        writer.drawKeyValueTable(
            listOf(
                "Licensor (Owner / Landlord)" to valOrBlank(draft.licensorName),
                "Licensee (Client / Tenant)" to valOrBlank(draft.licenseeName),
                "Registered Address" to valOrBlank(draft.licenseeRegisteredAddress),
                "Authorized Representative" to valOrBlank(draft.licenseeAuthorizedRep),
                "Designation" to valOrBlank(draft.licenseeDesignation),
                "PAN Number" to valOrBlank(draft.licenseePan),
                "GSTIN" to valOrBlank(draft.licenseeGst),
                "CIN" to valOrBlank(draft.licenseeCin),
                "Board Resolution Date" to valOrBlank(draft.boardResolutionDate)
            )
        )

        // 2. PREMISES & PARKING
        writer.drawSectionHeader("2. LICENSED PREMISES & PARKING")
        val unitWingFloor = listOfNotNull(
            draft.unitNumber.takeIf { it.isNotBlank() }?.let { "Unit $it" },
            draft.wing.takeIf { it.isNotBlank() }?.let { "Wing $it" },
            draft.floor.takeIf { it.isNotBlank() }?.let { "Floor $it" }
        ).joinToString("  |  ").ifBlank { "__________________" }
        val areaText = buildString {
            val c = draft.carpetAreaSqFt.trim()
            val b = draft.builtUpAreaSqFt.trim()
            append(if (c.isNotBlank()) "$c sq.ft. Carpet" else "______ sq.ft. Carpet")
            append("  |  ")
            append(if (b.isNotBlank()) "$b sq.ft. Built-up (BUA)" else "______ sq.ft. Built-up")
        }
        val parkingSummary = "4W: ${valOrBlank(draft.parking4Wheeler)}   |   2W: ${valOrBlank(draft.parking2Wheeler)}   |   Visitor: ${valOrBlank(draft.parkingVisitor)}"
        writer.drawKeyValueTable(
            listOf(
                "Property / Building Name" to "${valOrBlank(draft.propertyName)}${draft.propertyCode.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}",
                "Unit / Wing / Floor" to unitWingFloor,
                "Locality & City / State" to "${valOrBlank(draft.locality)}, ${valOrBlank(draft.cityState)}",
                "Area (Carpet / Built-up)" to areaText,
                "Furnishing Condition" to valOrBlank(draft.furnishing),
                "Allocated Parking" to parkingSummary
            )
        )

        // 3. FINANCIALS & TENURE SCHEDULE
        writer.drawSectionHeader("3. FINANCIAL TERMS & TENURE SCHEDULE")
        val monthlyText = IndianCurrencyWords.formatRsWithWords(draft.monthlyFeeDouble())
        val depositText = IndianCurrencyWords.formatRsWithWords(draft.securityDepositDouble())
        writer.drawKeyValueTable(
            listOf(
                "Monthly License Fee" to monthlyText,
                "Refundable Security Deposit" to depositText,
                "Agreement Duration" to "${valOrBlank(draft.agreementDurationMonths)} Months",
                "Lock-in Period" to "${valOrBlank(draft.lockInMonths)} Months",
                "Escalation Terms" to "${valOrBlank(draft.escalationPercentYoy)}% Year-on-Year (YOY)",
                "Notice Period (Post Lock-in)" to "${valOrBlank(draft.noticePeriodMonths)} Months",
                "Agreement Start Date" to valOrBlank(draft.agreementStartDate),
                "Possession / Handover Date" to valOrBlank(draft.handoverDate),
                "Monthly Fee Start Date" to valOrBlank(draft.monthlyFeeStartDate),
                "LOI Validity & Signing" to "Valid till ${valOrBlank(draft.loiValidityDate)} (${valOrBlank(draft.daysToSignAgreement)} days to sign Final Agreement)"
            )
        )

        // 4. UTILITIES, MAINTENANCE & STATUTORY CHARGES
        writer.drawSectionHeader("4. UTILITIES, MAINTENANCE & STATUTORY CHARGES")
        writer.drawKeyValueTable(
            listOf(
                "Maintenance Terms" to valOrBlank(draft.maintenanceTerms),
                "Maintenance Fee (on BUA)" to valOrBlank(draft.maintenanceFeePerSqFtBua),
                "Electricity Charges" to valOrBlank(draft.electricityTerms),
                "Internet / Telecom Service" to valOrBlank(draft.internetService),
                "Operating Hours" to valOrBlank(draft.operatingHours),
                "Soft Services / Amenities" to valOrBlank(draft.softServicesAmenities),
                "Additional Parking Fee" to valOrBlank(draft.additionalParkingFee),
                "Stamp Duty & Registration" to valOrBlank(draft.stampDutyRegistration)
            )
        )

        // 5. STANDARD COMMERCIAL CLAUSES
        writer.drawSectionHeader("5. STANDARD LEGAL & COMMERCIAL CLAUSES")
        writer.drawClauseBlock("5.1 Termination & Default", draft.terminationDefaultClause)
        writer.drawClauseBlock("5.2 Force Majeure", draft.forceMajeureClause)
        writer.drawClauseBlock("5.3 Binding LOI, TDS & Property Tax", draft.bindingAndTaxClause)

        // 6. OPTIONAL SCOPE OF WORK & FURNITURE ANNEXURE
        if (draft.ownerScopeOfWork.isNotBlank()) {
            writer.drawSectionHeader("6. OWNER / LICENSOR SCOPE OF WORK")
            writer.drawParagraph(draft.ownerScopeOfWork.trim())
        }
        if (draft.tenantScopeOfWork.isNotBlank()) {
            writer.drawSectionHeader("7. TENANT / LICENSEE SCOPE OF WORK")
            writer.drawParagraph(draft.tenantScopeOfWork.trim())
        }
        if (draft.furnitureListAnnexureA.isNotBlank()) {
            writer.drawSectionHeader("ANNEXURE A — FURNITURE & FIXTURES INVENTORY")
            writer.drawParagraph(draft.furnitureListAnnexureA.trim())
        }

        // EXECUTION SIGNATURE BLOCK
        writer.drawDualSignatureBlock(
            leftHeading = "FOR LICENSOR (OWNER / LANDLORD)",
            leftParty = draft.licensorName,
            rightHeading = "FOR LICENSEE (CLIENT / TENANT)",
            rightParty = draft.licenseeName,
            extraSignatory = draft.licenseeAuthorizedRep
        )

        // ── OPTIONAL SEPARATE LICENSEE BROKERAGE PAGE ──
        if (draft.includeLicenseeBrokeragePage) {
            writer.startFreshPage(fullLetterhead = true)
            writer.drawBrokerageConfirmationPage(
                sideLabel = "LICENSEE (TENANT / CLIENT)",
                partyName = draft.licenseeName,
                authorizedRep = draft.licenseeAuthorizedRep,
                designation = draft.licenseeDesignation,
                draft = draft
            )
        }

        // ── OPTIONAL SEPARATE LICENSOR BROKERAGE PAGE ──
        if (draft.includeLicensorBrokeragePage) {
            writer.startFreshPage(fullLetterhead = true)
            writer.drawBrokerageConfirmationPage(
                sideLabel = "LICENSOR (OWNER / LANDLORD)",
                partyName = draft.licensorName,
                authorizedRep = "",
                designation = "Owner / Authorized Signatory",
                draft = draft
            )
        }

        writer.finishAll()

        val dir = File(context.cacheDir, "shared_pdfs").apply { mkdirs() }
        val safeCode = draft.propertyCode.ifBlank { draft.propertyName }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(28)
            .ifBlank { "Premises" }
        val outFile = File(dir, "TLC_Official_LOI_${safeCode}.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    fun buildShareIntent(context: Context, pdfFile: File, draft: LoiDraftData): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )
        val premises = listOf(draft.propertyName, draft.unitNumber, draft.locality)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { draft.propertyCode.ifBlank { "Commercial Premises" } }
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Letter of Intent (LOI) — $premises | The Leasing Company")
            putExtra(
                Intent.EXTRA_TEXT,
                "Dear Sir/Madam,\n\nPlease find attached the Official Letter of Intent (LOI) prepared by The Leasing Company for $premises.\n\nWarm Regards,\nThe Leasing Company\nWe Make Leasing Simple!"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private class PdfPageWriter(
        private val doc: PdfDocument,
        private val draft: LoiDraftData
    ) {
        private var currentPage: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = 0f

        private val charcoalColor = Color.parseColor("#121619")
        private val orangeColor = Color.parseColor("#E96932")
        private val mutedBgColor = Color.parseColor("#F8F7F4")
        private val rowAltColor = Color.parseColor("#F3F4F6")
        private val borderColor = Color.parseColor("#D1D5DB")
        private val labelColor = Color.parseColor("#4B5563")
        private val textColor = Color.parseColor("#111827")

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val charcoalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = charcoalColor }
        private val orangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orangeColor }
        private val softCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mutedBgColor }
        private val rowAltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rowAltColor }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
        }

        private val headerCompanyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = charcoalColor
            textSize = 15.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.05f
        }
        private val taglinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = orangeColor
            textSize = 9.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val addressPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = 7.8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        private val bannerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val bannerSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E5E7EB")
            textSize = 8.8f
        }
        private val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = charcoalColor
            textSize = 10.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = 8.8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        private val clauseTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = charcoalColor
            textSize = 9.2f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 8.8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = 7.5f
        }

        private val canvas: Canvas
            get() = currentPage!!.canvas

        fun startFreshPage(fullLetterhead: Boolean) {
            currentPage?.let {
                drawPageFooter(it.canvas)
                doc.finishPage(it)
            }
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            currentPage = doc.startPage(info)
            canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), bgPaint)
            drawTlcLetterhead(compact = !fullLetterhead)
        }

        fun finishAll() {
            currentPage?.let {
                drawPageFooter(it.canvas)
                doc.finishPage(it)
            }
            currentPage = null
        }

        private fun ensureSpace(requiredHeight: Float) {
            if (y + requiredHeight > MAX_Y) {
                startFreshPage(fullLetterhead = false)
            }
        }

        private fun drawTlcLetterhead(compact: Boolean) {
            // Double-Wing Vector Logo from ic_tlc_logo.xml (1000x250 viewport)
            val logoTargetWidth = if (compact) 76f else 108f
            val logoTargetHeight = logoTargetWidth * 0.25f
            drawDoubleWingLogo(canvas, MARGIN_H, 20f, logoTargetWidth, logoTargetHeight)

            val textStartX = MARGIN_H + logoTargetWidth + 14f
            canvas.drawText("THE LEASING COMPANY", textStartX, 32f, headerCompanyPaint)
            canvas.drawText("We Make Leasing Simple!", textStartX, 45f, taglinePaint)

            if (!compact) {
                canvas.drawText(
                    "001, Gera Imperium Alpha, Grant Road, Kharadi, Pune 411014",
                    MARGIN_H,
                    63f,
                    addressPaint
                )
                canvas.drawText(
                    "www.theleasingcompany.in  |  info@theleasingcompany.in  |  9766880099  |  9766080099  |  9766668099",
                    MARGIN_H,
                    74f,
                    addressPaint
                )
                canvas.drawRect(MARGIN_H, 81f, PAGE_WIDTH - MARGIN_H, 83.5f, orangePaint)
                y = 95f
            } else {
                canvas.drawText(
                    "Ref: ${draft.loiRefCode.ifBlank { "TLC-LOI" }}  |  ${draft.propertyName.take(40)}",
                    PAGE_WIDTH - MARGIN_H - 190f,
                    32f,
                    addressPaint
                )
                canvas.drawRect(MARGIN_H, 52f, PAGE_WIDTH - MARGIN_H, 53.5f, orangePaint)
                y = 66f
            }
        }

        private fun drawDoubleWingLogo(c: Canvas, left: Float, top: Float, width: Float, height: Float) {
            val sx = width / 1000f
            val sy = height / 250f
            val m = Matrix().apply {
                setScale(sx, sy)
                postTranslate(left, top)
            }
            val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = charcoalColor
                style = Paint.Style.FILL
            }
            val wingPaths = listOf(
                // Left Wing
                floatArrayOf(0f, 0f, 92.3f, 61.0f, 336.7f, 80.4f, 497.5f, 250.0f, 382.8f, 22.3f),
                floatArrayOf(117.2f, 78.9f, 179.6f, 122.0f, 371.6f, 135.4f, 335.4f, 96.7f),
                floatArrayOf(213.2f, 139.9f, 276.8f, 184.5f, 425.2f, 192.0f, 389.0f, 153.3f),
                // Right Wing
                floatArrayOf(1000.0f, 0f, 617.2f, 22.3f, 502.5f, 250.0f, 663.3f, 80.4f, 907.7f, 61.0f),
                floatArrayOf(882.8f, 78.9f, 664.6f, 96.7f, 628.4f, 135.4f, 820.4f, 122.0f),
                floatArrayOf(786.8f, 139.9f, 611.0f, 153.3f, 574.8f, 192.0f, 723.2f, 184.5f)
            )
            wingPaths.forEach { pts ->
                val path = Path()
                path.moveTo(pts[0], pts[1])
                var i = 2
                while (i < pts.size) {
                    path.lineTo(pts[i], pts[i + 1])
                    i += 2
                }
                path.close()
                path.transform(m)
                c.drawPath(path, logoPaint)
            }
        }

        private fun drawPageFooter(c: Canvas) {
            c.drawLine(MARGIN_H, FOOTER_Y - 10f, PAGE_WIDTH - MARGIN_H, FOOTER_Y - 10f, borderPaint)
            c.drawText(
                "The Leasing Company  |  We Make Leasing Simple!  |  www.theleasingcompany.in",
                MARGIN_H,
                FOOTER_Y,
                footerPaint
            )
            c.drawText(
                "Page $pageNumber",
                PAGE_WIDTH - MARGIN_H - 36f,
                FOOTER_Y,
                footerPaint
            )
        }

        fun drawDocumentBanner(title: String, subtitle: String) {
            ensureSpace(44f)
            val rect = RectF(MARGIN_H, y, PAGE_WIDTH - MARGIN_H, y + 36f)
            canvas.drawRoundRect(rect, 5f, 5f, charcoalPaint)
            canvas.drawRect(MARGIN_H, y, MARGIN_H + 5f, y + 36f, orangePaint)
            canvas.drawText(title, MARGIN_H + 14f, y + 16f, bannerTitlePaint)
            canvas.drawText(subtitle, MARGIN_H + 14f, y + 29f, bannerSubPaint)
            y += 46f
        }

        fun drawSectionHeader(title: String) {
            ensureSpace(26f)
            canvas.drawRect(MARGIN_H, y, MARGIN_H + 3.5f, y + 13f, orangePaint)
            canvas.drawText(title, MARGIN_H + 9f, y + 10.5f, sectionPaint)
            y += 18f
        }

        fun drawKeyValueTable(rows: List<Pair<String, String>>) {
            val labelColWidth = 172f
            val valueColWidth = CONTENT_WIDTH - labelColWidth - 20f
            rows.forEachIndexed { index, (k, v) ->
                val layout = StaticLayout.Builder
                    .obtain(v, 0, v.length, valuePaint, valueColWidth.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(1.5f, 1.0f)
                    .build()
                val rowHeight = maxOf(19f, layout.height + 9f)
                ensureSpace(rowHeight + 2f)

                val rect = RectF(MARGIN_H, y, PAGE_WIDTH - MARGIN_H, y + rowHeight)
                if (index % 2 == 0) {
                    canvas.drawRect(rect, rowAltPaint)
                }
                canvas.drawRect(rect, borderPaint)
                canvas.drawLine(MARGIN_H + labelColWidth, y, MARGIN_H + labelColWidth, y + rowHeight, borderPaint)
                canvas.drawText(k, MARGIN_H + 7f, y + 13f, labelPaint)

                canvas.save()
                canvas.translate(MARGIN_H + labelColWidth + 8f, y + 4.5f)
                layout.draw(canvas)
                canvas.restore()

                y += rowHeight
            }
            y += 10f
        }

        fun drawClauseBlock(title: String, body: String) {
            if (body.isBlank()) return
            val layout = StaticLayout.Builder
                .obtain(body, 0, body.length, bodyPaint, (CONTENT_WIDTH - 12f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.05f)
                .build()
            val needed = 16f + layout.height + 10f
            ensureSpace(needed.coerceAtMost(220f))

            canvas.drawText(title, MARGIN_H, y + 10f, clauseTitlePaint)
            y += 14f

            val boxRect = RectF(MARGIN_H, y, PAGE_WIDTH - MARGIN_H, y + layout.height + 10f)
            if (boxRect.bottom <= MAX_Y) {
                canvas.drawRoundRect(boxRect, 4f, 4f, softCardPaint)
                canvas.drawRoundRect(boxRect, 4f, 4f, borderPaint)
                canvas.save()
                canvas.translate(MARGIN_H + 6f, y + 5f)
                layout.draw(canvas)
                canvas.restore()
                y = boxRect.bottom + 8f
            } else {
                // Draw line-by-line across page breaks if clause is very long
                drawParagraph(body)
            }
        }

        fun drawParagraph(text: String) {
            val lines = text.split("\n")
            lines.forEach { para ->
                val clean = para.trim()
                if (clean.isEmpty()) {
                    y += 6f
                    return@forEach
                }
                val layout = StaticLayout.Builder
                    .obtain(clean, 0, clean.length, bodyPaint, (CONTENT_WIDTH - 8f).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.05f)
                    .build()
                ensureSpace(layout.height + 6f)
                canvas.save()
                canvas.translate(MARGIN_H + 4f, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + 5f
            }
            y += 6f
        }

        fun drawDualSignatureBlock(
            leftHeading: String,
            leftParty: String,
            rightHeading: String,
            rightParty: String,
            extraSignatory: String
        ) {
            ensureSpace(112f)
            y += 8f
            val boxW = (CONTENT_WIDTH - 18f) / 2f
            val boxH = 92f
            val leftRect = RectF(MARGIN_H, y, MARGIN_H + boxW, y + boxH)
            val rightRect = RectF(MARGIN_H + boxW + 18f, y, PAGE_WIDTH - MARGIN_H, y + boxH)

            listOf(leftRect, rightRect).forEach { r ->
                canvas.drawRoundRect(r, 4f, 4f, softCardPaint)
                canvas.drawRoundRect(r, 4f, 4f, borderPaint)
            }

            canvas.drawText(leftHeading, leftRect.left + 10f, leftRect.top + 15f, clauseTitlePaint)
            canvas.drawText("Name: ${valOrBlank(leftParty).take(32)}", leftRect.left + 10f, leftRect.top + 30f, valuePaint)
            canvas.drawLine(leftRect.left + 10f, leftRect.bottom - 26f, leftRect.right - 12f, leftRect.bottom - 26f, borderPaint)
            canvas.drawText("Authorized Signatory & Date / Stamp", leftRect.left + 10f, leftRect.bottom - 12f, addressPaint)

            canvas.drawText(rightHeading, rightRect.left + 10f, rightRect.top + 15f, clauseTitlePaint)
            canvas.drawText("Name: ${valOrBlank(rightParty).take(32)}", rightRect.left + 10f, rightRect.top + 30f, valuePaint)
            if (extraSignatory.isNotBlank()) {
                canvas.drawText("Rep: ${extraSignatory.take(30)}", rightRect.left + 10f, rightRect.top + 43f, addressPaint)
            }
            canvas.drawLine(rightRect.left + 10f, rightRect.bottom - 26f, rightRect.right - 12f, rightRect.bottom - 26f, borderPaint)
            canvas.drawText("Authorized Signatory & Date / Stamp", rightRect.left + 10f, rightRect.bottom - 12f, addressPaint)

            y += boxH + 12f
        }

        fun drawBrokerageConfirmationPage(
            sideLabel: String,
            partyName: String,
            authorizedRep: String,
            designation: String,
            draft: LoiDraftData
        ) {
            drawDocumentBanner(
                title = "BROKERAGE & ADVISORY FEE CONFIRMATION — $sideLabel",
                subtitle = "Ref: ${draft.loiRefCode.ifBlank { "TLC-BRK" }}  |  Date: ${valOrBlank(draft.loiDate)}  |  Place: ${valOrBlank(draft.place)}"
            )

            val premisesDesc = listOfNotNull(
                draft.propertyName.takeIf { it.isNotBlank() },
                draft.unitNumber.takeIf { it.isNotBlank() }?.let { "Unit $it" },
                draft.wing.takeIf { it.isNotBlank() }?.let { "Wing $it" },
                draft.locality.takeIf { it.isNotBlank() },
                draft.cityState.takeIf { it.isNotBlank() }
            ).joinToString(", ").ifBlank { "__________________" }

            val slabDesc = draft.brokerageSlabLabel()
            val brokAmtText = IndianCurrencyWords.formatRsWithWords(draft.computedBrokerageInr())
            val monthlyText = IndianCurrencyWords.formatRsWithWords(draft.monthlyFeeDouble())

            drawSectionHeader("1. TRANSACTION & BROKERAGE SCHEDULE")
            drawKeyValueTable(
                listOf(
                    "Confirming Party ($sideLabel)" to valOrBlank(partyName),
                    "Authorized Representative" to valOrBlank(authorizedRep),
                    "Designation" to valOrBlank(designation),
                    "Licensed Premises" to premisesDesc,
                    "Agreement Tenure" to "${valOrBlank(draft.agreementDurationMonths)} Months",
                    "Monthly License Fee" to monthlyText,
                    "Applicable Brokerage Slab" to slabDesc,
                    "Total Professional Fee Payable" to "$brokAmtText + applicable GST (18%)",
                    "Payment Due Milestone" to "100% payable upon execution / registration of Leave & License Agreement",
                    "Payable In Favour Of" to "THE LEASING COMPANY (Kharadi, Pune 411014)"
                )
            )

            drawSectionHeader("2. MANDATE & PAYMENT CONFIRMATION")
            val mandateText =
                "We, ${valOrBlank(partyName)}, hereby confirm and agree to pay The Leasing Company the professional real estate advisory and brokerage fee specified above towards facilitating the Leave & License transaction for the Premises described herein. Standard brokerage slab is 1 (One) Month's License Fee for lease terms up to 3 years (36 months) and 2 (Two) Months' License Fee for lease terms exceeding 3 years, plus GST as applicable under Indian law. Payment shall be cleared promptly against Tax Invoice upon execution/registration of the formal Leave & License Agreement."
            drawClauseBlock("Confirmation Terms", mandateText)

            y += 16f
            drawDualSignatureBlock(
                leftHeading = "CONFIRMED BY $sideLabel",
                leftParty = partyName,
                rightHeading = "ACCEPTED BY THE LEASING COMPANY",
                rightParty = "The Leasing Company",
                extraSignatory = authorizedRep
            )
        }
    }
}
