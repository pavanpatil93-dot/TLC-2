package com.example.data

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import java.util.UUID

/**
 * Strict Row-1 Header-Name mapper and IST date normalizer for Commercial CRM and Residential CRM
 * Google Sheet synchronization.
 *
 * Guarantees:
 * - Maps every CRM field strictly by exact Row 1 header name (NEVER by positional fallback index),
 *   preventing column-shift corruption (e.g., Preferred Locations -> Furnishing, Possession -> Carpet Area,
 *   Requirement -> Monthly Rent / Budget, Mobile -> Client Type).
 * - Converts ISO-8601 UTC timestamps (`2026-11-17T18:30:00.000Z`) and JS Date strings
 *   (`Fri Dec 01 2028 00:00:00 GMT+0530 ...`) into clean `dd/MM/yyyy` in Asia/Kolkata (IST) timezone,
 *   while leaving non-date textual timelines (e.g., `"Anytime"`, `"Immediate"`) untouched.
 */
object CrmSheetSyncMapper {

    private val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val DD_MM_YYYY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH)

    private val TEXT_DATE_FORMATTERS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d-M-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM yyyy").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM yyyy").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM dd, yyyy").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("EEE MMM d yyyy HH:mm:ss 'GMT'Z").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("EEE MMM dd yyyy").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("EEE MMM d yyyy").toFormatter(Locale.ENGLISH)
    )

    private val CRM_ID_REGEX = Regex("^CRM[-_]?\\d+$", RegexOption.IGNORE_CASE)
    private val PHONE_LIKE_REGEX = Regex("^\\+?\\d[\\d\\s\\-()]{6,}$")
    private val NON_AREA_TEXT_REGEX = Regex(
        "^(anytime|immediate|ready\\s*to\\s*move|direct|walk[-\\s]*in|direct\\s*/\\s*walk[-\\s]*in|reference|portal|broker|website|cold\\s*call|whatsapp|not\\s*mentioned|tbd|n/a|na)$",
        RegexOption.IGNORE_CASE
    )
    private val JS_DATE_PARENTHESIZED_TZ = Regex("\\s*\\([^)]*\\)\\s*$")

    /**
     * Parses ISO-8601 timestamps (`2026-11-17T18:30:00.000Z`), JS Date strings
     * (`Fri Dec 01 2028 00:00:00 GMT+0530 (India Standard Time)`), or standard date strings
     * and formats them as `dd/MM/yyyy` in local IST (`Asia/Kolkata`) timezone.
     *
     * Textual timelines like `"Anytime"` or `"Immediate"` are preserved as-is.
     */
    fun formatCrmDateIst(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) return ""

        // 1. ISO-8601 instant with Z or explicit offset (e.g., 2026-11-17T18:30:00.000Z -> 18/11/2026 IST)
        if (trimmed.contains('T')) {
            runCatching {
                val instant = Instant.parse(trimmed)
                return instant.atZone(IST_ZONE).toLocalDate().format(DD_MM_YYYY)
            }
            runCatching {
                val odt = OffsetDateTime.parse(trimmed)
                return odt.atZoneSameInstant(IST_ZONE).toLocalDate().format(DD_MM_YYYY)
            }
            runCatching {
                val zdt = ZonedDateTime.parse(trimmed)
                return zdt.withZoneSameInstant(IST_ZONE).toLocalDate().format(DD_MM_YYYY)
            }
            // ISO local datetime without zone (e.g., 2026-11-18T10:30:00)
            val datePrefix = trimmed.substringBefore('T')
            runCatching {
                return LocalDate.parse(datePrefix).format(DD_MM_YYYY)
            }
        }

        // 2. JavaScript Date.toString() (e.g., "Fri Dec 01 2028 00:00:00 GMT+0530 (India Standard Time)")
        val strippedJsTz = JS_DATE_PARENTHESIZED_TZ.replace(trimmed, "").trim()
        if (strippedJsTz.contains("GMT", ignoreCase = true)) {
            val normalizedGmt = strippedJsTz.replace(Regex("GMT\\s*([+-]\\d{4})", RegexOption.IGNORE_CASE), "GMT$1")
            for (fmt in TEXT_DATE_FORMATTERS) {
                runCatching {
                    val zdt = ZonedDateTime.parse(normalizedGmt, fmt)
                    return zdt.withZoneSameInstant(IST_ZONE).toLocalDate().format(DD_MM_YYYY)
                }
            }
            // Fallback: extract "Dec 01 2028" from "Fri Dec 01 2028 ..."
            val match = Regex("""\b([A-Za-z]{3})\s+(\d{1,2})\s+(\d{4})\b""").find(strippedJsTz)
            if (match != null) {
                val mon = match.groupValues[1]
                val day = match.groupValues[2].padStart(2, '0')
                val year = match.groupValues[3]
                val candidate = "$day $mon $year"
                runCatching {
                    val f = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM yyyy").toFormatter(Locale.ENGLISH)
                    return LocalDate.parse(candidate, f).format(DD_MM_YYYY)
                }
            }
        }

        // 3. Standard local date formats (yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy, dd MMM yyyy, etc.)
        for (fmt in TEXT_DATE_FORMATTERS) {
            runCatching {
                return LocalDate.parse(trimmed, fmt).format(DD_MM_YYYY)
            }
        }

        // 4. Preserve non-date textual values like "Anytime", "Immediate", "Within 30 Days"
        return trimmed
    }

    private fun normalizeHeader(header: String): String =
        header
            .replace('\u00A0', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ENGLISH)

    private fun buildHeaderMap(headers: List<String>): Map<String, Int> {
        val map = LinkedHashMap<String, Int>(headers.size)
        headers.forEachIndexed { idx, raw ->
            val key = normalizeHeader(raw)
            if (key.isNotBlank() && !map.containsKey(key)) {
                map[key] = idx
            }
        }
        return map
    }

    /**
     * Resolves a cell value STRICTLY by exact Row 1 header names.
     * NEVER falls back to arbitrary column indices.
     */
    private fun exactCell(
        headerMap: Map<String, Int>,
        row: List<String>,
        vararg exactHeaders: String
    ): String {
        for (h in exactHeaders) {
            val idx = headerMap[normalizeHeader(h)] ?: continue
            if (idx in row.indices) {
                val v = row[idx].trim()
                if (v.isNotBlank() && !v.equals("null", ignoreCase = true) && !v.equals("undefined", ignoreCase = true)) {
                    return v
                }
            }
        }
        return ""
    }

    private fun isPhoneLike(value: String): Boolean {
        val clean = value.trim()
        if (clean.isBlank()) return false
        if (PHONE_LIKE_REGEX.matches(clean)) return true
        val digits = clean.count { it.isDigit() }
        val letters = clean.count { it.isLetter() }
        return digits >= 8 && letters == 0
    }

    private fun sanitizeClientType(raw: String): String {
        val clean = raw.trim()
        if (clean.isBlank() || isPhoneLike(clean)) return ""
        return clean
    }

    private fun sanitizeCarpetArea(raw: String): String {
        val clean = raw.trim()
        if (clean.isBlank()) return ""
        if (NON_AREA_TEXT_REGEX.matches(clean)) return ""
        // Carpet area must contain at least one digit (e.g., "1500", "1200-1800 Sq.Ft", "2000+")
        if (clean.none { it.isDigit() }) return ""
        return clean
    }

    private fun sanitizeBudget(raw: String): String {
        val clean = raw.trim()
        if (clean.isBlank()) return ""
        // Must contain a digit (e.g., "1.5 Lac", "₹2.5 Cr", "85000", "50k-75k") — rejects "Lease - Office Space"
        if (clean.none { it.isDigit() }) return ""
        return clean
    }

    /**
     * Maps a single sheet row using exact Row 1 header names into a normalized CRM lead [JSONObject]
     * compatible with `crm_development_v1` and `CrmLead`.
     */
    fun mapSheetRowToLeadJson(
        headers: List<String>,
        row: List<String>,
        defaultSegment: String = "Commercial"
    ): JSONObject? {
        if (row.all { it.isBlank() }) return null
        val hMap = buildHeaderMap(headers)

        val leadId = exactCell(hMap, row, "Lead ID", "lead_id", "CRM ID", "ID")
            .ifBlank { "CRM-${UUID.randomUUID().toString().take(8)}" }

        val rawClientName = exactCell(hMap, row, "Client Name", "Customer Name", "Lead Name", "Name")
        val cleanClientName = if (
            rawClientName.isBlank() ||
            rawClientName.equals(leadId, ignoreCase = true) ||
            CRM_ID_REGEX.matches(rawClientName)
        ) "" else rawClientName

        val mobile = exactCell(
            hMap, row,
            "Mobile", "Mobile Number", "Phone", "Phone Number",
            "Contact Number", "WhatsApp Number", "Primary Mobile"
        )
        val altMobile = exactCell(
            hMap, row,
            "Alternate Mobile", "Alternate Phone", "Alt Mobile", "Secondary Mobile"
        )

        val clientType = sanitizeClientType(
            exactCell(hMap, row, "Client Type", "Customer Type", "Lead Type", "Entity Type", "POC Type")
        )
        val companyName = exactCell(
            hMap, row,
            "Company Name", "Company", "Firm Name", "Broker Firm", "Organization"
        )

        val rawRequirementCol = exactCell(
            hMap, row,
            "Requirement", "Client Requirement", "Requirement Summary", "Enquiry"
        )
        val reqType = exactCell(
            hMap, row,
            "Requirement Type", "Transaction Type", "Transaction", "Deal Type", "Purpose"
        )
        val segment = exactCell(
            hMap, row,
            "Property Category", "Segment", "Vertical", "Division", "Portfolio"
        ).ifBlank {
            if (defaultSegment.contains("res", ignoreCase = true)) "Residential" else "Commercial"
        }
        val propType = exactCell(
            hMap, row,
            "Property Type", "Asset Type", "Configuration", "Sub Type", "BHK"
        )

        val preferredLocations = exactCell(
            hMap, row,
            "Preferred Locations", "Preferred Location", "Location", "Locations",
            "Preferred Areas", "Preferred Area", "Zone", "Pune Zone", "Micro-Market"
        )

        val minCarpet = sanitizeCarpetArea(
            exactCell(hMap, row, "Min Carpet SqFt", "Min Carpet (Sq Ft)", "Min Carpet Area", "Min Area", "Min Area (Sq Ft)")
        )
        val maxCarpet = sanitizeCarpetArea(
            exactCell(hMap, row, "Max Carpet SqFt", "Max Carpet (Sq Ft)", "Max Carpet Area", "Max Area", "Max Area (Sq Ft)")
        )
        val combinedCarpet = sanitizeCarpetArea(
            exactCell(
                hMap, row,
                "Carpet Area", "Carpet Area (Sq Ft)", "Carpet Area SqFt",
                "Area / Size (Sq Ft)", "Area (Sq Ft)", "Area SqFt", "Required Area", "Size"
            )
        )
        val carpetRange = when {
            minCarpet.isNotBlank() && maxCarpet.isNotBlank() && minCarpet != maxCarpet -> "$minCarpet–$maxCarpet"
            maxCarpet.isNotBlank() -> maxCarpet
            minCarpet.isNotBlank() -> minCarpet
            else -> combinedCarpet
        }

        val minBudget = sanitizeBudget(
            exactCell(hMap, row, "Min Budget INR", "Min Budget", "Min Budget (₹)")
        )
        val maxBudget = sanitizeBudget(
            exactCell(hMap, row, "Max Budget INR", "Max Budget", "Max Budget (₹)")
        )
        val minRent = sanitizeBudget(
            exactCell(hMap, row, "Min Rent INR/Month", "Min Rent", "Min Rent (₹)", "Min Monthly Rent")
        )
        val maxRent = sanitizeBudget(
            exactCell(hMap, row, "Max Rent INR/Month", "Max Rent", "Max Rent (₹)", "Max Monthly Rent")
        )
        val combinedBudget = sanitizeBudget(
            exactCell(
                hMap, row,
                "Budget", "Budget (₹)", "Budget INR", "Monthly Rent / Budget",
                "Monthly Budget", "Rent / Budget", "Monthly Rent", "Monthly Rent (₹)",
                "Rent", "Price / Budget", "Asking Budget"
            )
        )

        val isLease = reqType.contains("Lease", ignoreCase = true) ||
            reqType.contains("Rent", ignoreCase = true) ||
            rawRequirementCol.contains("Lease", ignoreCase = true) ||
            rawRequirementCol.contains("Rent", ignoreCase = true)

        val budgetStr = when {
            isLease && (minRent.isNotBlank() || maxRent.isNotBlank()) -> {
                when {
                    minRent.isNotBlank() && maxRent.isNotBlank() && minRent != maxRent -> "$minRent - $maxRent"
                    maxRent.isNotBlank() -> maxRent
                    else -> minRent
                }
            }
            minBudget.isNotBlank() || maxBudget.isNotBlank() -> {
                when {
                    minBudget.isNotBlank() && maxBudget.isNotBlank() && minBudget != maxBudget -> "$minBudget - $maxBudget"
                    maxBudget.isNotBlank() -> maxBudget
                    else -> minBudget
                }
            }
            minRent.isNotBlank() || maxRent.isNotBlank() -> maxRent.ifBlank { minRent }
            else -> combinedBudget
        }

        val furnishing = normalizeFurnishingPreference(
            exactCell(
                hMap, row,
                "Furnishing Preference", "Furnishing", "Condition / Fit-out", "Fit-out", "Fitout"
            )
        )

        val possession = formatCrmDateIst(
            exactCell(
                hMap, row,
                "Possession", "Possession Deadline", "Possession Timeline",
                "Target Possession", "Target Possession Date", "Move-in Date", "Move-In Timeline"
            )
        )

        val leadSource = exactCell(hMap, row, "Lead Source", "Source", "Channel")
        val subSource = exactCell(
            hMap, row,
            "Sub-Source / Broker", "Sub-Source", "Sub Source / Broker", "Sub Source", "Broker / Sub-Source"
        )
        val assignedTo = exactCell(hMap, row, "Assigned To", "Assigned RM", "RM", "Owner RM", "Executive")
        val stage = normalizePipelineStage(
            exactCell(
                hMap, row,
                "Pipeline Stage", "Lead Stage", "Stage", "Status", "Lead Status"
            )
        )
        val archiveStatus = normalizeArchiveStatus(
            exactCell(hMap, row, "Archive Status", "Record Status")
        )
        val priority = exactCell(hMap, row, "Priority", "Temperature", "Lead Temperature")

        val nextFollowUp = formatCrmDateIst(
            exactCell(
                hMap, row,
                "Next Follow-up Date", "Next Follow-up", "Next Follow Up Date",
                "Next Follow Up", "Follow-up Date", "Follow Up Date", "Follow-Up"
            )
        )
        val createdDate = formatCrmDateIst(
            exactCell(hMap, row, "Created Date", "Date Added", "Lead Date", "Date")
        )
        val lastContactDate = formatCrmDateIst(
            exactCell(hMap, row, "Last Contact Date", "Last Contacted", "Last Call Date")
        )
        val lastContactMode = exactCell(hMap, row, "Last Contact Mode", "Contact Mode")
        val lastContactOutcome = exactCell(hMap, row, "Last Contact Outcome", "Last Call Outcome")
        val matchedCodes = exactCell(hMap, row, "Matched Property Codes", "Matched Properties", "Shortlisted Properties")
        val siteVisitDate = formatCrmDateIst(
            exactCell(hMap, row, "Site Visit Date", "Visit Date")
        )
        val siteVisitStatus = exactCell(hMap, row, "Site Visit Status", "Visit Status")
        val actionNotes = exactCell(hMap, row, "Action Notes", "Remarks", "Notes", "Follow-up Notes")
        val clientNotes = exactCell(hMap, row, "Client Notes", "Description", "Specific Requirements")
        val consentStatus = exactCell(hMap, row, "Consent Status")
        val createdBy = exactCell(hMap, row, "Created By", "Added By")
        val updatedAt = formatCrmDateIst(exactCell(hMap, row, "Updated At", "Last Updated"))
        val legacyLeadId = exactCell(hMap, row, "Legacy Lead ID")
        val verificationStatus = exactCell(hMap, row, "Verification Status")
        val duplicatePhoneCheck = exactCell(hMap, row, "Duplicate Phone Check")

        val requirementSummary = rawRequirementCol.ifBlank {
            listOf(reqType, propType, furnishing)
                .filter { it.isNotBlank() && !it.equals("Not Mentioned", ignoreCase = true) }
                .joinToString(" ")
        }.ifBlank { clientNotes }

        val cleanNotes = actionNotes.ifBlank { clientNotes }

        return JSONObject().apply {
            put("id", leadId)
            put("name", cleanClientName)
            put("clientName", cleanClientName)
            put("phone", mobile)
            put("alternatePhone", altMobile)
            put("createdDate", createdDate)
            put("clientType", clientType)
            put("companyName", companyName.ifBlank { if (!isPhoneLike(clientType)) clientType else "" })
            put("requirementType", reqType)
            put("segment", segment)
            put("propertyType", propType)
            put("requirement", requirementSummary)
            put("location", preferredLocations)
            put("carpetAreaRange", carpetRange)
            put("areaSqFt", carpetRange)
            put("minCarpetSqFt", minCarpet)
            put("maxCarpetSqFt", maxCarpet)
            put("minBudgetInr", minBudget)
            put("maxBudgetInr", maxBudget)
            put("minRentInr", minRent)
            put("maxRentInr", maxRent)
            put("budget", budgetStr)
            put("furnishing", furnishing)
            put("possessionTimeline", possession)
            put("leadSource", leadSource)
            put("subSource", subSource)
            put("assignedTo", assignedTo)
            put("status", stage)
            put("archiveStatus", archiveStatus)
            put("priority", priority)
            put("nextFollowUp", nextFollowUp)
            put("lastContactDate", lastContactDate)
            put("lastContactMode", lastContactMode)
            put("lastContactOutcome", lastContactOutcome)
            put("matchedPropertyCodes", matchedCodes)
            put("siteVisitDate", siteVisitDate)
            put("siteVisitStatus", siteVisitStatus)
            put("notes", cleanNotes)
            put("clientNotes", clientNotes)
            put("consentStatus", consentStatus)
            put("createdBy", createdBy)
            put("updatedAt", updatedAt)
            put("legacyLeadId", legacyLeadId)
            put("verificationStatus", verificationStatus)
            put("duplicatePhoneCheck", duplicatePhoneCheck)
        }
    }

    fun normalizePipelineStage(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank()) return "New"
        val lower = clean.lowercase(Locale.ENGLISH)
        return when {
            lower == "new" || lower == "new lead" || lower == "enquiry" -> "New"
            lower == "contacted" || lower == "in touch" || lower == "follow up" || lower == "follow-up" -> "Contacted"
            lower == "site visit scheduled" || lower == "visit scheduled" -> "Site Visit Scheduled"
            lower == "site visit done" || lower == "visit done" || lower == "site visited" -> "Site Visit Done"
            lower == "negotiation" || lower == "under negotiation" || lower == "offer" -> "Negotiation"
            lower == "token / booking" || lower == "token/booking" || lower == "token" || lower == "booking" || lower == "loi" -> "Token / Booking"
            lower == "closed won" || lower == "won" || lower == "deal closed" || lower == "closed - won" -> "Closed Won"
            lower == "closed lost" || lower == "lost" || lower == "dropped" || lower == "closed - lost" -> "Closed Lost"
            else -> clean
        }
    }

    fun normalizeFurnishingPreference(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank()) return ""
        val lower = clean.lowercase(Locale.ENGLISH)
        return when {
            lower == "unfurnished" || lower == "un-furnished" -> "Unfurnished"
            lower == "semi-furnished" || lower == "semi furnished" || lower == "semifurnished" -> "Semi-Furnished"
            lower == "fully furnished" || lower == "fully-furnished" || lower == "furnished" -> "Fully Furnished"
            lower == "bare shell" || lower == "bareshell" || lower == "bare-shell" -> "Bare Shell"
            lower == "warm shell" || lower == "warmshell" || lower == "warm-shell" -> "Warm Shell"
            lower == "plug & play" || lower == "plug and play" || lower == "plug-and-play" || lower == "plug n play" -> "Plug & Play"
            else -> clean
        }
    }

    fun normalizeArchiveStatus(raw: String?): String {
        val clean = raw?.trim().orEmpty()
        return if (clean.equals("Archived", ignoreCase = true) || clean.equals("Inactive", ignoreCase = true)) {
            "Archived"
        } else {
            "Active"
        }
    }
}
