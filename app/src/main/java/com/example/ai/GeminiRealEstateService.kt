package com.example.ai

import android.util.Log
import com.example.BuildConfig
import com.example.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ExtractedPropertyDraft(
    val tab: InventoryTab = InventoryTab.OFF_LEASE,
    val propertyType: String = "",
    val subType: String = "",
    val projectName: String = "",
    val location: String = "",
    val microMarket: String = "",
    val unitNumber: String = "",
    val floor: String = "",
    val bhkConfiguration: String = "",
    val carpetAreaSqFt: Double = 0.0,
    val builtUpAreaSqFt: Double? = null,
    val priceOrRent: Double = 0.0,
    val priceSale: Double? = null,
    val rent: Double? = null,
    val deposit: Double? = null,
    val roiPercent: Double? = null,
    val furnishing: FurnishingType = FurnishingType.UNFURNISHED,
    val possession: String = "",
    val parking: String = "",
    val lockInMonths: String = "",
    val escalationTerms: String = "",
    val maintenanceTerms: String = "",
    val workstations: Int? = null,
    val cabins: Int? = null,
    val contactName: String = "",
    val contactPhone: String = "",
    val remarks: String = "",
    val confidenceNotes: String = "",
    val builderName: String = "",
    val rawSnippet: String = "",
    val hasExplicitFurnishing: Boolean = false,
    val missingFields: List<String> = emptyList()
)

data class OnlineProjectFetchResult(
    val projectName: String = "",
    val developerName: String = "",
    val microMarket: String = "",
    val reraNumber: String = "",
    val projectStatus: String = "",
    val possessionDate: String = "",
    val configurations: String = "",
    val carpetAreaRange: String = "",
    val priceRange: String = "",
    val amenities: List<String> = emptyList(),
    val googleMapsUrl: String = "",
    val sourceUrls: List<String> = emptyList(),
    val coverImageUrl: String = "",
    val fetchedFromWeb: Boolean = false,
    val errorMessage: String? = null
)

data class ClientMatchCategory(
    val categoryName: String,
    val results: List<ClientMatchResult>
)

data class ClientMatchResult(
    val property: PropertyItem,
    val matchScorePercent: Int,
    val matchingReasons: List<String>,
    val matchType: String // "Exact Match", "Close Match", "Alternative"
)

data class ExtractedClientRequirement(
    val rawPrompt: String = "",
    val portfolio: Brand? = null, // COMMERCIAL or RESIDENTIAL or null
    val propertyType: String = "",
    val transactionType: String = "", // "Lease" or "Sale"
    val minPriceOrRent: Double? = null,
    val maxPriceOrRent: Double? = null,
    val minCarpetSqFt: Double? = null,
    val maxCarpetSqFt: Double? = null,
    val locations: List<String> = emptyList(),
    val furnishing: FurnishingType? = null,
    val bhkConfig: String? = null,
    val possession: String? = null,
    val isVague: Boolean = false,
    val extractionSource: String = "Gemini 2.5 Flash Model"
) {
    fun toCriteriaSummary(): String {
        val parts = mutableListOf<String>()
        val portfolioText = when (portfolio) {
            Brand.COMMERCIAL -> "Commercial Portfolio"
            Brand.RESIDENTIAL -> "Residential Portfolio"
            null -> "All Portfolios"
        }
        parts.add(portfolioText)
        if (propertyType.isNotBlank()) parts.add(propertyType)
        if (transactionType.isNotBlank()) parts.add(transactionType)
        if (locations.isNotEmpty()) parts.add(locations.joinToString(", "))
        if (minCarpetSqFt != null || maxCarpetSqFt != null) {
            val min = minCarpetSqFt?.toInt() ?: 0
            val max = maxCarpetSqFt?.toInt()
            parts.add(if (max != null && min > 0) "$min–$max sq.ft." else if (max != null) "Up to $max sq.ft." else "Min $min sq.ft.")
        }
        if (maxPriceOrRent != null && maxPriceOrRent > 0) {
            val isRental = transactionType.contains("lease", ignoreCase = true) || transactionType.contains("rent", ignoreCase = true)
            val formattedMax = CalculationUtils.formatIndianCurrency(maxPriceOrRent, isRental)
            parts.add("Max $formattedMax")
        }
        if (furnishing != null) parts.add(furnishing.label)
        if (bhkConfig != null) parts.add(bhkConfig)
        return parts.joinToString(" • ")
    }
}

data class NaturalSearchResult(
    val exactMatches: List<PropertyItem>,
    val alternatives: List<PropertyItem>,
    val isVague: Boolean,
    val extractedRequirement: ExtractedClientRequirement
)

data class ExtractedLeadDraft(
    val clientName: String = "",
    val mobile: String = "",
    val brokerName: String = "",
    val firmName: String = "",
    val isBrokerLead: Boolean = false,
    val division: String = "Commercial",
    val propertyType: String = "Office",
    val transactionType: String = "Lease",
    val subOption: String = "",
    val location: String = "",
    val minCarpetSqFt: Long? = null,
    val maxCarpetSqFt: Long? = null,
    val areaRangeText: String = "",
    val minBudgetInr: Long? = null,
    val maxBudgetInr: Long? = null,
    val budgetText: String = "",
    val requirementSummary: String = "",
    val notes: String = "",
    val sourceLine: String = ""
)

class GeminiRealEstateService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun parsePropertyMessage(rawText: String, defaultCompanyPrefix: String = "ACS"): ExtractedPropertyDraft = withContext(Dispatchers.IO) {
        val list = parsePropertyMessagesBatch(rawText, defaultCompanyPrefix)
        list.firstOrNull() ?: ExtractedPropertyDraft()
    }

    suspend fun parsePropertyMessagesBatch(rawText: String, defaultCompanyPrefix: String = "ACS"): List<ExtractedPropertyDraft> = withContext(Dispatchers.IO) {
        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Exception) { "" }

        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val prompt = rawText.replace("\"", "'")
                val systemPrompt = """
You are a senior real estate data ingestion specialist for PROPERTY OS in Pune.
Extract structured inventory fields from the pasted broker/client message or WhatsApp forward (which may contain 1 or multiple properties).
STRICT RULES:
- NEVER invent fake values. If a field is not present in the text, leave it blank ("") or null / 0.0. Never output placeholder names like "Prime Center" or "Available Unit".
- Output ONLY a JSON array of objects (even if there is only 1 property) with keys:
  "tab": one of ["Off Lease", "Off Out", "Shop Lease", "Shop Out", "Pre - Leased", "Resi Rent", "Resi Resale", "Und Const"]
  "propertyType": string
  "subType": string
  "projectName": string (leave blank if not mentioned)
  "location": string (Pune micro-market or locality)
  "microMarket": string
  "unitNumber": string (leave blank if not mentioned)
  "floor": string (leave blank if not mentioned)
  "bhkConfiguration": string
  "carpetAreaSqFt": number (0.0 if not mentioned)
  "builtUpAreaSqFt": number or null
  "priceSale": number or null
  "rent": number or null
  "deposit": number or null
  "roiPercent": number or null
  "furnishing": one of ["Bare Shell", "Warm Shell", "Semi-Furnished", "Fully Furnished", "Unfurnished"]
  "possession": string
  "parking": string
  "lockInMonths": string
  "escalationTerms": string
  "maintenanceTerms": string
  "workstations": integer or null
  "cabins": integer or null
  "contactName": string
  "contactPhone": string
  "remarks": string
Text to parse:
"$prompt"
                """.trimIndent()

                val jsonPayload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", systemPrompt) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                    })
                }

                val models = listOf("gemini-2.5-flash", "gemini-2.0-flash")
                var responseBody = ""
                var success = false

                for (modelName in models) {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                    val request = Request.Builder()
                        .url(url)
                        .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = client.newCall(request).execute()
                    responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        success = true
                        break
                    }
                }

                if (success) {
                    val root = JSONObject(responseBody)
                    val text = root.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    val cleanedJson = stripJsonFences(text)
                    val jsonArray = if (cleanedJson.trim().startsWith("[")) {
                        JSONArray(cleanedJson)
                    } else {
                        JSONArray().put(JSONObject(cleanedJson))
                    }

                    val results = mutableListOf<ExtractedPropertyDraft>()
                    for (i in 0 until jsonArray.length()) {
                        val parsed = jsonArray.getJSONObject(i)
                        val tabStr = parsed.optString("tab", "Off Lease").trim()
                        val matchedTab = InventoryTab.entries.find { it.code.equals(tabStr, ignoreCase = true) } ?: InventoryTab.OFF_LEASE
                        val furnStr = parsed.optString("furnishing", "").trim()
                        val hasExplicitFurn = furnStr.isNotBlank() && !furnStr.equals("null", true)
                        val matchedFurn = if (hasExplicitFurn) {
                            FurnishingType.entries.find { it.label.equals(furnStr, ignoreCase = true) } ?: FurnishingType.UNFURNISHED
                        } else {
                            FurnishingType.UNFURNISHED
                        }

                        val rentVal = if (parsed.has("rent") && !parsed.isNull("rent")) parsed.optDouble("rent").takeIf { it > 0.0 } else null
                        val saleVal = if (parsed.has("priceSale") && !parsed.isNull("priceSale")) parsed.optDouble("priceSale").takeIf { it > 0.0 } else null
                        val depositVal = if (parsed.has("deposit") && !parsed.isNull("deposit")) parsed.optDouble("deposit").takeIf { it > 0.0 } else null
                        val carpetVal = parsed.optDouble("carpetAreaSqFt", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                        val buaVal = if (parsed.has("builtUpAreaSqFt") && !parsed.isNull("builtUpAreaSqFt")) parsed.optDouble("builtUpAreaSqFt").takeIf { it.isFinite() && it > 0.0 } else null
                        val roiVal = if (parsed.has("roiPercent") && !parsed.isNull("roiPercent")) parsed.optDouble("roiPercent").takeIf { it.isFinite() && it > 0.0 } else null
                        val primaryPrice = if (matchedTab.isRental) (rentVal ?: 0.0) else (saleVal ?: 0.0)

                        val cleanProj = sanitizeExtractedString(parsed.optString("projectName", ""))
                        val cleanLoc = sanitizeExtractedString(parsed.optString("location", ""))
                        val cleanMicro = sanitizeExtractedString(parsed.optString("microMarket", ""))
                        val cleanUnit = sanitizeExtractedString(parsed.optString("unitNumber", ""))
                        val cleanFloor = sanitizeExtractedString(parsed.optString("floor", ""))
                        val cleanType = sanitizeExtractedString(parsed.optString("propertyType", ""))
                        val cleanPhone = normalizeIndianPhone(parsed.optString("contactPhone", ""))
                        val cleanContactName = sanitizeExtractedString(parsed.optString("contactName", ""))
                        val cleanPoss = sanitizeExtractedString(parsed.optString("possession", ""))

                        val draft = ExtractedPropertyDraft(
                            tab = matchedTab,
                            propertyType = cleanType,
                            subType = sanitizeExtractedString(parsed.optString("subType", "")),
                            projectName = cleanProj,
                            location = cleanLoc.ifBlank { cleanMicro },
                            microMarket = cleanMicro,
                            unitNumber = cleanUnit,
                            floor = cleanFloor,
                            bhkConfiguration = sanitizeExtractedString(parsed.optString("bhkConfiguration", "")),
                            carpetAreaSqFt = carpetVal,
                            builtUpAreaSqFt = buaVal,
                            priceOrRent = primaryPrice,
                            priceSale = saleVal,
                            rent = rentVal,
                            deposit = depositVal,
                            roiPercent = roiVal,
                            furnishing = matchedFurn,
                            possession = cleanPoss,
                            parking = sanitizeExtractedString(parsed.optString("parking", "")),
                            lockInMonths = sanitizeExtractedString(parsed.optString("lockInMonths", "")),
                            escalationTerms = sanitizeExtractedString(parsed.optString("escalationTerms", "")),
                            maintenanceTerms = sanitizeExtractedString(parsed.optString("maintenanceTerms", "")),
                            workstations = if (parsed.has("workstations") && !parsed.isNull("workstations")) parsed.optInt("workstations").takeIf { it > 0 } else null,
                            cabins = if (parsed.has("cabins") && !parsed.isNull("cabins")) parsed.optInt("cabins").takeIf { it > 0 } else null,
                            contactName = cleanContactName,
                            contactPhone = cleanPhone,
                            remarks = sanitizeExtractedString(parsed.optString("remarks", "")),
                            confidenceNotes = "Extracted via Gemini 2.5 Flash Model",
                            rawSnippet = rawText.trim(),
                            hasExplicitFurnishing = hasExplicitFurn
                        )
                        results.add(draft.copy(missingFields = computeDraftMissingFields(draft)))
                    }
                    if (results.isNotEmpty()) return@withContext results
                }
            } catch (e: Exception) {
                Log.w("GeminiRealEstate", "Gemini batch error: ${e.message}")
            }
        }

        return@withContext fallbackParsePropertiesBatch(rawText)
    }

    private fun stripJsonFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun sanitizeExtractedString(value: String?): String {
        val cleaned = value?.trim().orEmpty()
        if (cleaned.equals("null", true) || cleaned.equals("n/a", true) || cleaned.equals("none", true)) return ""
        val forbiddenFake = setOf("prime center", "available unit", "mid floor", "not specified", "not mentioned")
        return if (cleaned.lowercase() in forbiddenFake) "" else cleaned
    }

    private fun normalizeIndianPhone(raw: String?): String {
        val digits = raw.orEmpty().filter { it.isDigit() }
        return when {
            digits.length == 10 && digits.first() in '6'..'9' -> digits
            digits.length > 10 -> digits.takeLast(10).takeIf { it.first() in '6'..'9' }.orEmpty()
            else -> ""
        }
    }

    private fun computeDraftMissingFields(draft: ExtractedPropertyDraft): List<String> = buildList {
        if (draft.projectName.isBlank()) add("Project / Building")
        if (draft.location.isBlank()) add("Locality / Micro-Market")
        if (draft.carpetAreaSqFt <= 0.0) add("Carpet Area")
        if (draft.tab.isRental && (draft.rent ?: 0.0) <= 0.0) add("Monthly Rent")
        if (!draft.tab.isRental && (draft.priceSale ?: 0.0) <= 0.0) add("Sale Price")
        if (draft.unitNumber.isBlank()) add("Unit / Shop No")
        if (draft.contactPhone.isBlank()) add("Contact Mobile")
    }

    fun fallbackParseProperty(rawText: String): ExtractedPropertyDraft = parseSingleTextSegment(rawText)

    private fun fallbackParsePropertiesBatch(rawText: String): List<ExtractedPropertyDraft> {
        val normalized = rawText.replace("\r\n", "\n").trim()
        if (normalized.isBlank()) return emptyList()

        // Split numbered lists, bullet dividers, or blank-line separated property blocks cleanly
        val splitByDivider = normalized.split(Regex("""(?m)(?:\n\s*[-=*_]{3,}\s*\n|\n\s*\n+|^\s*(?:option|property|unit)\s*\d+\s*[:\.\-]\s*|(?=^\s*\d{1,2}[\.\)]\s+[A-Za-z]))"""))
            .map { it.trim() }
            .filter { it.length >= 12 }

        val textBlocks = when {
            splitByDivider.size > 1 -> splitByDivider
            else -> {
                // Also try splitting when repeated "Project:" / "Office for" / "Shop for" lines occur
                val lineStarts = normalized.split(Regex("""(?im)(?=\n(?:project|building|office\s+(?:for|at|in)|shop\s+(?:for|at|in)|[1-5]\s*bhk\s+(?:for|at|in))\b)"""))
                    .map { it.trim() }
                    .filter { it.length >= 12 }
                if (lineStarts.size > 1) lineStarts else listOf(normalized)
            }
        }
        return textBlocks.map { parseSingleTextSegment(it) }
    }

    private fun parseSingleTextSegment(text: String): ExtractedPropertyDraft {
        val lower = text.lowercase()

        val isRentalHint = lower.contains("rent") || lower.contains("lease") || lower.contains("p.m") || lower.contains("/month") || lower.contains("per month")
        val isSaleHint = lower.contains("sale") || lower.contains("outright") || lower.contains("resale") || lower.contains("sell") || lower.contains("buy")

        val tab = when {
            lower.contains("pre-leased") || lower.contains("pre leased") || lower.contains("preleased") || lower.contains("roi") || lower.contains("yield") -> InventoryTab.PRE_LEASED
            (lower.contains("shop") || lower.contains("showroom") || lower.contains("retail")) && isSaleHint && !isRentalHint -> InventoryTab.SHOP_OUT
            lower.contains("shop") || lower.contains("showroom") || lower.contains("retail") -> InventoryTab.SHOP_LEASE
            (lower.contains("office") || lower.contains("commercial") || lower.contains("it park") || lower.contains("workstation")) && isSaleHint && !isRentalHint -> InventoryTab.OFF_OUT
            lower.contains("office") || lower.contains("commercial") || lower.contains("it park") || lower.contains("workstation") -> InventoryTab.OFF_LEASE
            lower.contains("under construction") || lower.contains("rera") -> InventoryTab.UND_CONST
            (lower.contains("bhk") || lower.contains("flat") || lower.contains("apartment") || lower.contains("villa")) && isRentalHint -> InventoryTab.RESI_RENT
            lower.contains("bhk") || lower.contains("flat") || lower.contains("apartment") || lower.contains("villa") || lower.contains("resale") -> InventoryTab.RESI_RESALE
            else -> InventoryTab.OFF_LEASE
        }

        val puneProjects = listOf(
            "Gera Imperium Alpha", "Gera Imperium Rise", "Gera Imperium Oasis", "Gera Imperium Star",
            "Gera Commerzone", "World Trade Center", "WTC", "EON Free Zone", "EON IT Park",
            "Global Business Hub", "Finswell", "Nyati Enthral", "Nyati Tech Park", "Nyati Unitree",
            "Zensar", "Panchshil Towers", "Panchshil Business Park", "Panchshil Tech Park",
            "Yoo Pune", "Trump Towers", "Godrej Boulevard", "Godrej Greens", "Godrej Rejuve",
            "Riverdale Grand", "Riverdale Grove", "Riverdale Heights", "Gagan Avencia",
            "VTP Pegasus", "VTP Leonara", "VTP Blue Waters", "Lodha Belmondo",
            "Kolte Patil Life Republic", "Amanora Park Town", "Magarpatta Cybercity",
            "Phoenix Marketcity", "Amar Business Zone", "Amar Tech Park", "Supreme HQ",
            "Balewadi High Street", "Kapil Zenith", "Solitaire Business Hub", "Cerebrum IT Park"
        ).sortedByDescending { it.length }

        val projectName = puneProjects.firstOrNull { candidate ->
            Regex("""\b${Regex.escape(candidate)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
            ?: Regex("""(?im)(?:project|building|society|tower|complex)\s*[:\-]\s*([^\n,|]{2,60})""").find(text)?.groupValues?.get(1)?.trim().orEmpty()

        val puneLocalities = listOf(
            "Kharadi", "Viman Nagar", "Kalyani Nagar", "Koregaon Park", "Mundhwa", "Keshav Nagar",
            "Magarpatta", "Hadapsar", "Amanora", "Wagholi", "Yerwada", "Baner", "Balewadi", "Wakad",
            "Hinjawadi", "Hinjewadi", "Aundh", "Shivaji Nagar", "Shivajinagar", "Camp", "Pimpri",
            "Chinchwad", "NIBM", "Undri", "Kondhwa", "Pashan", "Bavdhan", "Kothrud", "Karve Nagar",
            "Senapati Bapat Road", "SB Road", "FC Road", "JM Road", "Boat Club Road", "Bund Garden",
            "Ravet", "Tathawade", "Punawale", "Pimple Saudagar", "Pimple Nilakh", "Dhanori", "Lohegaon"
        ).sortedByDescending { it.length }

        val location = puneLocalities.firstOrNull { loc ->
            Regex("""\b${Regex.escape(loc)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
            ?: Regex("""(?im)(?:location|locality|area|micro[\-\s]?market)\s*[:\-]\s*([^\n|]{2,45})""").find(text)?.groupValues?.get(1)?.trim().orEmpty()

        val unitNumber = Regex("""(?im)\b(?:unit|office|shop|flat|suite)(?:\s*no\.?|\s*#)?\s*[:\-#]?\s*([A-Z]?\d{1,5}[A-Z]?)\b""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val floor = Regex("""(?im)(?:\b(\d+(?:st|nd|rd|th)?|ground|gf|lower ground|lgf|mezzanine)\s+floor\b|\bfloor\s*[:\-]\s*([^\n,|]{1,24}))""").find(text)?.let { match ->
            match.groupValues[2].takeIf { it.isNotBlank() }?.trim()
                ?: match.groupValues[1].takeIf { it.isNotBlank() }?.let { "$it Floor" }
        }.orEmpty()

        val carpetRegex = Regex("""(?:(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)?\s*(?:carpet|ca\b)|carpet(?:\s*area)?\s*[:\-]?\s*(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)?)""", RegexOption.IGNORE_CASE)
        val builtUpRegex = Regex("""(?:(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)?\s*(?:built[\- ]?up|bua|super\s*built[\- ]?up|chargeable)|(?:built[\- ]?up|bua|chargeable)(?:\s*area)?\s*[:\-]?\s*(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)?)""", RegexOption.IGNORE_CASE)
        val genericAreaRegex = Regex("""(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)\b""", RegexOption.IGNORE_CASE)

        val builtUp = builtUpRegex.find(text)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(",", "").toDoubleOrNull()
        }
        val carpetExplicit = carpetRegex.find(text)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(",", "").toDoubleOrNull()
        }
        val carpet = carpetExplicit ?: genericAreaRegex.findAll(text).firstNotNullOfOrNull { m ->
            val v = m.groupValues[1].replace(",", "").toDoubleOrNull()
            if (v != null && v != builtUp) v else null
        } ?: 0.0

        var rentVal: Double? = null
        var saleVal: Double? = null
        var depositVal: Double? = null

        fun parseScaledRupees(numStr: String, unitStr: String): Double? {
            val base = numStr.replace(",", "").toDoubleOrNull() ?: return null
            if (base <= 0.0) return null
            return when (unitStr.lowercase().trim()) {
                "cr", "crore", "crores" -> base * 10_000_000.0
                "lac", "lakh", "lakhs", "lacs", "l" -> base * 100_000.0
                "k", "thousand" -> base * 1_000.0
                else -> base
            }
        }

        // Tagged rent extraction first
        Regex("""(?im)\b(?:rent|lease)\s*[:\-@]?\s*(?:₹|rs\.?|inr)?\s*(\d[\d,.]*)\s*(cr|crore|crores|lac|lakh|lakhs|lacs|k|thousand)?\b""").find(text)?.let { m ->
            rentVal = parseScaledRupees(m.groupValues[1], m.groupValues[2])
        }
        // Tagged sale price extraction
        Regex("""(?im)\b(?:sale|price|ask|asking|quote|budget|outright)\s*[:\-@]?\s*(?:₹|rs\.?|inr)?\s*(\d[\d,.]*)\s*(cr|crore|crores|lac|lakh|lakhs|lacs|k|thousand)?\b""").find(text)?.let { m ->
            val parsed = parseScaledRupees(m.groupValues[1], m.groupValues[2])
            if (parsed != null && parsed > 0.0) {
                if (tab.isRental && parsed < 1_000_000.0 && rentVal == null) rentVal = parsed
                else saleVal = parsed
            }
        }
        // Untyped Cr/Lac/k fallback
        if (rentVal == null && saleVal == null) {
            Regex("""(?:₹|rs\.?|inr)?\s*(\d+(?:\.\d+)?)\s*(cr|crore|crores)\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                val v = parseScaledRupees(m.groupValues[1], m.groupValues[2])
                if (tab.isRental) rentVal = v else saleVal = v
            } ?: Regex("""(?:₹|rs\.?|inr)?\s*(\d+(?:\.\d+)?)\s*(lac|lakh|lakhs|lacs)\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                val v = parseScaledRupees(m.groupValues[1], m.groupValues[2])
                if (tab.isRental) rentVal = v else saleVal = v
            } ?: Regex("""(?:₹|rs\.?|inr)?\s*(\d+(?:\.\d+)?)\s*(k|thousand)\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                val v = parseScaledRupees(m.groupValues[1], m.groupValues[2])
                if (tab.isRental) rentVal = v else saleVal = v
            }
        }

        // Security deposit extraction (INR amount or N months)
        Regex("""(?im)\b(?:sec(?:urity)?\.?\s*dep(?:osit)?|deposit)\s*[:\-]?\s*(?:₹|rs\.?|inr)?\s*(\d[\d,.]*)\s*(cr|crore|lac|lakh|lacs|k|months?)?\b""").find(text)?.let { m ->
            val unit = m.groupValues[2].lowercase()
            if (unit.startsWith("month")) {
                val months = m.groupValues[1].toDoubleOrNull() ?: 0.0
                if (months > 0.0 && (rentVal ?: 0.0) > 0.0) {
                    depositVal = months * (rentVal ?: 0.0)
                }
            } else {
                depositVal = parseScaledRupees(m.groupValues[1], unit)
            }
        }

        val primaryPrice = if (tab.isRental) (rentVal ?: 0.0) else (saleVal ?: 0.0)

        val phone = Regex("""(?:\+91[\-\s]?)?([6-9]\d{9})\b""").find(text)?.groupValues?.get(1).orEmpty()
        val contactName = Regex("""(?im)\b(?:contact|owner|poc|call)\s*[:\-]\s*([A-Za-z][A-Za-z\s\.]{1,30})""").find(text)?.groupValues?.get(1)?.trim()
            ?.takeIf { !it.all { c -> c.isDigit() } }.orEmpty()

        val workstations = Regex("""(\d+)\s*(?:workstations?|seats?|ws\b)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        val cabins = Regex("""(\d+)\s*cabins?""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        val bhkConfig = Regex("""(\d(?:\.5)?\s*BHK)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.uppercase()?.replace(Regex("\\s+"), " ").orEmpty()
        val parking = Regex("""(?im)(\d+\s*(?:covered|open|car)?\s*park(?:ing)?s?)""").find(text)?.groupValues?.get(1)?.trim().orEmpty()

        val hasExplicitFurn = lower.contains("fully furnished") || lower.contains("semi furnished") ||
            lower.contains("semi-furnished") || lower.contains("warm shell") ||
            lower.contains("bare shell") || lower.contains("unfurnished")
        val furnishing = when {
            lower.contains("fully furnished") -> FurnishingType.FULLY_FURNISHED
            lower.contains("semi furnished") || lower.contains("semi-furnished") -> FurnishingType.SEMI_FURNISHED
            lower.contains("warm shell") -> FurnishingType.WARM_SHELL
            lower.contains("bare shell") -> FurnishingType.BARE_SHELL
            else -> FurnishingType.UNFURNISHED
        }

        val propType = when {
            tab == InventoryTab.SHOP_LEASE || tab == InventoryTab.SHOP_OUT -> if (lower.contains("showroom")) "Showroom" else "Retail Shop"
            tab.isCommercial -> "Office"
            bhkConfig.isNotBlank() -> "Apartment"
            lower.contains("villa") -> "Villa"
            lower.contains("bungalow") -> "Bungalow"
            else -> if (tab.isCommercial) "Office" else "Apartment"
        }

        val draft = ExtractedPropertyDraft(
            tab = tab,
            propertyType = propType,
            projectName = projectName,
            location = location,
            microMarket = location,
            unitNumber = unitNumber,
            floor = floor,
            bhkConfiguration = bhkConfig,
            carpetAreaSqFt = carpet,
            builtUpAreaSqFt = builtUp,
            priceOrRent = primaryPrice,
            priceSale = saleVal,
            rent = rentVal,
            deposit = depositVal,
            furnishing = furnishing,
            possession = when {
                lower.contains("immediate") || lower.contains("ready to move") -> "Immediate"
                else -> Regex("""(?im)possession\s*[:\-]\s*([^\n,|]{2,25})""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
            },
            parking = parking,
            workstations = workstations,
            cabins = cabins,
            contactName = contactName,
            contactPhone = phone,
            remarks = text.trim(),
            confidenceNotes = "Parsed via Pune Smart Fallback Engine",
            rawSnippet = text.trim(),
            hasExplicitFurnishing = hasExplicitFurn
        )
        return draft.copy(missingFields = computeDraftMissingFields(draft))
    }

    /**
     * Online Project Details & Cover Image Auto-Fetch with Google Search Grounding.
     * Never auto-saves; caller MUST present a mandatory manual verification screen before saving.
     */
    suspend fun fetchOnlineProjectDetails(
        projectName: String,
        location: String
    ): OnlineProjectFetchResult = withContext(Dispatchers.IO) {
        val cleanName = projectName.trim()
        val cleanLoc = location.trim()
        if (cleanName.isBlank()) {
            return@withContext OnlineProjectFetchResult(
                errorMessage = "Enter a Project Name before fetching online details."
            )
        }
        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (_: Exception) { "" }
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext OnlineProjectFetchResult(
                projectName = cleanName,
                microMarket = cleanLoc,
                fetchedFromWeb = false,
                errorMessage = "Gemini API key not configured. Verify & fill project details manually."
            )
        }

        try {
            val queryPrompt = """
Search official developer portals, MahaRERA, and real estate portals for the project "$cleanName" in "${cleanLoc.ifBlank { "Pune" }}, Pune, India".
Return ONLY a raw JSON object (no markdown fences) with these exact keys. Leave any unverified field as an empty string "" or []:
{
  "developerName": "string",
  "microMarket": "string",
  "reraNumber": "string",
  "projectStatus": "string (e.g. Ready to Move, Under Construction, New Launch)",
  "possessionDate": "string (e.g. Dec 2027 or Immediate)",
  "configurations": "string (e.g. 2 BHK, 3 BHK or Office, Retail)",
  "carpetAreaRange": "string (e.g. 850 - 1,450 sq.ft.)",
  "priceRange": "string (e.g. ₹1.15 Cr - ₹2.40 Cr)",
  "amenities": ["amenity 1", "amenity 2"],
  "googleMapsUrl": "https://maps.google.com/...",
  "sourceUrls": ["https://..."],
  "coverImageUrl": "https://... direct high-res building elevation image URL if verified"
}
Rules: NEVER invent fake RERA numbers, fake prices, or fake image URLs. If unknown, return "".
            """.trimIndent()

            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", queryPrompt) })
                        })
                    })
                })
                put("tools", JSONArray().apply {
                    put(JSONObject().apply { put("google_search", JSONObject()) })
                })
            }

            val models = listOf("gemini-2.5-flash", "gemini-2.0-flash")
            var responseBody = ""
            var responseSuccess = false
            var lastErrorCode = 0

            for (model in models) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = client.newCall(request).execute()
                responseBody = response.body?.string().orEmpty()
                lastErrorCode = response.code
                if (response.isSuccessful) {
                    responseSuccess = true
                    break
                }
            }

            if (!responseSuccess) {
                return@withContext OnlineProjectFetchResult(
                    projectName = cleanName,
                    microMarket = cleanLoc,
                    fetchedFromWeb = false,
                    errorMessage = "Online search failed (HTTP $lastErrorCode). Please review manually."
                )
            }

            val root = JSONObject(responseBody)
            val candidate0 = root.optJSONArray("candidates")?.optJSONObject(0)
            val parts = candidate0?.optJSONObject("content")?.optJSONArray("parts")
            val rawText = buildString {
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }
            }

            val groundingUrls = mutableListOf<String>()
            val chunks = candidate0?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
            if (chunks != null) {
                for (i in 0 until chunks.length()) {
                    val webUri = chunks.optJSONObject(i)?.optJSONObject("web")?.optString("uri").orEmpty().trim()
                    if (webUri.startsWith("http")) groundingUrls.add(webUri)
                }
            }

            val jsonObj = extractJsonObjectFromText(rawText) ?: JSONObject()
            val jsonSources = mutableListOf<String>()
            jsonObj.optJSONArray("sourceUrls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i).trim()
                    if (u.startsWith("http")) jsonSources.add(u)
                }
            }
            val combinedSources = (jsonSources + groundingUrls).distinct().take(6)

            val amenitiesList = mutableListOf<String>()
            jsonObj.optJSONArray("amenities")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optString(i).trim()
                    if (a.isNotBlank()) amenitiesList.add(a)
                }
            }

            var resolvedCover = sanitizeImageUrl(jsonObj.optString("coverImageUrl", ""))
            if (resolvedCover.isBlank()) {
                for (srcUrl in combinedSources.take(3)) {
                    val og = fetchOgImageFromWebPage(srcUrl)
                    if (og.isNotBlank()) {
                        resolvedCover = og
                        break
                    }
                }
            }

            return@withContext OnlineProjectFetchResult(
                projectName = cleanName,
                developerName = sanitizeExtractedString(jsonObj.optString("developerName", "")),
                microMarket = sanitizeExtractedString(jsonObj.optString("microMarket", "")).ifBlank { cleanLoc },
                reraNumber = sanitizeExtractedString(jsonObj.optString("reraNumber", "")),
                projectStatus = sanitizeExtractedString(jsonObj.optString("projectStatus", "")),
                possessionDate = sanitizeExtractedString(jsonObj.optString("possessionDate", "")),
                configurations = sanitizeExtractedString(jsonObj.optString("configurations", "")),
                carpetAreaRange = sanitizeExtractedString(jsonObj.optString("carpetAreaRange", "")),
                priceRange = sanitizeExtractedString(jsonObj.optString("priceRange", "")),
                amenities = amenitiesList,
                googleMapsUrl = sanitizeExtractedString(jsonObj.optString("googleMapsUrl", "")),
                sourceUrls = combinedSources,
                coverImageUrl = resolvedCover,
                fetchedFromWeb = true
            )
        } catch (e: Exception) {
            Log.w("GeminiRealEstate", "fetchOnlineProjectDetails failed: ${e.message}")
            return@withContext OnlineProjectFetchResult(
                projectName = cleanName,
                microMarket = cleanLoc,
                fetchedFromWeb = false,
                errorMessage = "Online fetch failed: ${e.message ?: "Network error"}"
            )
        }
    }

    private fun extractJsonObjectFromText(raw: String): JSONObject? {
        val cleaned = stripJsonFences(raw)
        val direct = runCatching { JSONObject(cleaned) }.getOrNull()
        if (direct != null) return direct
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    private fun sanitizeImageUrl(raw: String?): String {
        val u = raw?.trim().orEmpty()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return ""
        val lower = u.lowercase()
        if (lower.endsWith(".svg") || lower.contains("favicon") || lower.contains("spacer.gif")) return ""
        return u
    }

    private fun fetchOgImageFromWebPage(pageUrl: String): String {
        return runCatching {
            val shortClient = client.newBuilder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .build()
            shortClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching ""
                val html = resp.body?.string()?.take(180_000).orEmpty()
                val patterns = listOf(
                    Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""", RegexOption.IGNORE_CASE),
                    Regex("""<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+name=["']twitter:image["']""", RegexOption.IGNORE_CASE)
                )
                for (rx in patterns) {
                    val found = rx.find(html)?.groupValues?.get(1)?.trim()
                    if (!found.isNullOrBlank()) {
                        val abs = runCatching { java.net.URL(java.net.URL(pageUrl), found).toString() }.getOrDefault(found)
                        val clean = sanitizeImageUrl(abs)
                        if (clean.isNotBlank()) return@runCatching clean
                    }
                }
                ""
            }
        }.getOrDefault("")
    }

    /**
     * STEP 7: AI NATURAL LANGUAGE PROPERTY SEARCH EXTRACTION
     * Uses Gemini 2.5 Flash to extract structured search criteria or falls back to rule-based pattern engine.
     */
    suspend fun extractRequirementsWithGemini(query: String): ExtractedClientRequirement = withContext(Dispatchers.IO) {
        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Exception) { "" }

        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val prompt = """
You are a real estate AI assistant for Commercial & Residential property inventory in Pune, India.
Analyze this client requirement query/message:
"${query.replace("\"", "'")}"

Extract structured search criteria into a JSON object with these exact keys:
- "portfolio": "COMMERCIAL" or "RESIDENTIAL" or "AUTO"
- "propertyType": string (e.g. "Office", "Shop", "Showroom", "Warehouse", "Pre-Leased", "1 BHK", "2 BHK", "3 BHK", "4 BHK", "Villa", "Apartment", "Plot")
- "transactionType": "Lease" or "Sale" or ""
- "minPriceOrRent": number or null (monthly rent in ₹ or sale price in ₹)
- "maxPriceOrRent": number or null (monthly rent in ₹ or sale price in ₹, e.g. 2 lakh = 200000, 1.5 Cr = 15000000)
- "minCarpetSqFt": number or null
- "maxCarpetSqFt": number or null
- "locations": array of strings (e.g. ["Kharadi", "Baner"])
- "furnishing": "Bare Shell" or "Warm Shell" or "Semi-Furnished" or "Fully Furnished" or "Unfurnished" or null
- "bhkConfig": string or null (e.g. "2 BHK", "3 BHK")
- "isVague": boolean (true if query is broad like 'properties in Pune' without specific budget, type, or location)

Rules:
- 1 lakh / 1 lac = 100,000
- 1 crore / 1 cr = 10,000,000
- Auto-detect portfolio if ambiguous: Commercial for Office/Shop/Showroom/Warehouse/Pre-Leased; Residential for BHK/Flat/Villa/Apartment.
- Return ONLY valid JSON without markdown code fences.
                """.trimIndent()

                val jsonPayload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                    })
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val root = JSONObject(responseBody)
                    val text = root.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    val parsed = JSONObject(stripJsonFences(text))
                    val portStr = parsed.optString("portfolio", "AUTO")
                    val portBrand = when {
                        portStr.contains("COMMERCIAL", ignoreCase = true) -> Brand.COMMERCIAL
                        portStr.contains("RESIDENTIAL", ignoreCase = true) -> Brand.RESIDENTIAL
                        else -> detectPortfolioFromQuery(query)
                    }

                    val furnStr = parsed.optString("furnishing", "")
                    val furnType = FurnishingType.values().find { it.label.equals(furnStr, ignoreCase = true) }

                    val locList = mutableListOf<String>()
                    val locArray = parsed.optJSONArray("locations")
                    if (locArray != null) {
                        for (i in 0 until locArray.length()) {
                            locList.add(locArray.getString(i))
                        }
                    }

                    return@withContext ExtractedClientRequirement(
                        rawPrompt = query,
                        portfolio = portBrand,
                        propertyType = parsed.optString("propertyType", ""),
                        transactionType = parsed.optString("transactionType", ""),
                        minPriceOrRent = if (parsed.has("minPriceOrRent") && !parsed.isNull("minPriceOrRent")) parsed.optDouble("minPriceOrRent") else null,
                        maxPriceOrRent = if (parsed.has("maxPriceOrRent") && !parsed.isNull("maxPriceOrRent")) parsed.optDouble("maxPriceOrRent") else null,
                        minCarpetSqFt = if (parsed.has("minCarpetSqFt") && !parsed.isNull("minCarpetSqFt")) parsed.optDouble("minCarpetSqFt") else null,
                        maxCarpetSqFt = if (parsed.has("maxCarpetSqFt") && !parsed.isNull("maxCarpetSqFt")) parsed.optDouble("maxCarpetSqFt") else null,
                        locations = locList,
                        furnishing = furnType,
                        bhkConfig = if (parsed.has("bhkConfig") && !parsed.isNull("bhkConfig")) parsed.optString("bhkConfig") else null,
                        isVague = parsed.optBoolean("isVague", false),
                        extractionSource = "Gemini 2.5 Flash Model"
                    )
                }
            } catch (e: Exception) {
                Log.w("GeminiRealEstate", "Gemini search extraction error: ${e.message}")
            }
        }

        return@withContext fallbackExtractRequirements(query)
    }

    fun fallbackExtractRequirements(query: String): ExtractedClientRequirement {
        val lower = query.lowercase()

        val portfolio = detectPortfolioFromQuery(query)

        val knownLocations = listOf(
            "Kharadi", "Baner", "Viman Nagar", "Balewadi", "Hinjawadi", "Koregaon Park",
            "Mundhwa", "Keshav Nagar", "Yerwada", "Wagholi", "Kesnand", "NIBM", "BT Kawade", "Pimpri", "Shivaji Nagar", "Magarpatta", "Hadapsar", "Aundh", "Kalyani Nagar", "Wakad",
            "Pimple Nilakh", "Ravet", "Bhavani Peth", "FC Road", "JM Road", "Senapati Bapat Road"
        )
        val foundLocations = knownLocations.filter { lower.contains(it.lowercase()) }

        val propertyType = when {
            lower.contains("office") -> "Office"
            lower.contains("shop") -> "Shop"
            lower.contains("showroom") -> "Showroom"
            lower.contains("warehouse") || lower.contains("godown") -> "Warehouse"
            lower.contains("pre-leased") || lower.contains("preleased") -> "Pre-Leased"
            lower.contains("1 bhk") || lower.contains("1bhk") -> "1 BHK"
            lower.contains("2 bhk") || lower.contains("2bhk") -> "2 BHK"
            lower.contains("3.5 bhk") || lower.contains("3.5bhk") -> "3.5 BHK"
            lower.contains("3 bhk") || lower.contains("3bhk") -> "3 BHK"
            lower.contains("4 bhk") || lower.contains("4bhk") -> "4 BHK"
            lower.contains("villa") || lower.contains("bungalow") -> "Villa / Bungalow"
            lower.contains("plot") -> "Plot"
            lower.contains("flat") || lower.contains("apartment") -> "Apartment"
            else -> ""
        }

        val transactionType = when {
            lower.contains("pre-leased") || lower.contains("preleased") || lower.contains("purchase") -> "Sale"
            lower.contains("rent") || lower.contains("lease") -> "Lease"
            lower.contains("sale") || lower.contains("buy") || lower.contains("outright") || lower.contains("resale") -> "Sale"
            else -> ""
        }

        var maxPrice: Double? = null
        var minPrice: Double? = null

        val crRegex = Regex("""(\d+[\d,.]*)\s*(cr|crore)""", RegexOption.IGNORE_CASE)
        val lacRegex = Regex("""(\d+[\d,.]*)\s*(lac|lakh|lacs|l)\b""", RegexOption.IGNORE_CASE)

        val lacMatches = lacRegex.findAll(query).toList()
        if (lacMatches.isNotEmpty()) {
            val vals = lacMatches.mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull()?.times(100_000.0) }
            if (vals.size >= 2) {
                minPrice = vals.minOrNull()
                maxPrice = vals.maxOrNull()
            } else if (vals.isNotEmpty()) {
                maxPrice = vals[0]
            }
        } else {
            val crMatches = crRegex.findAll(query).toList()
            if (crMatches.isNotEmpty()) {
                val vals = crMatches.mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull()?.times(10_000_000.0) }
                if (vals.size >= 2) {
                    minPrice = vals.minOrNull()
                    maxPrice = vals.maxOrNull()
                } else if (vals.isNotEmpty()) {
                    maxPrice = vals[0]
                }
            }
        }

        if (maxPrice == null) {
            val budget = Regex("""(?:budget|rent|under|below|up to|max)\s*(?:of\s*)?(?:₹|rs\.?|inr)?\s*([0-9][0-9,.]*)\s*(k)?\b""", RegexOption.IGNORE_CASE).find(query)
            if (budget != null) maxPrice = budget.groupValues[1].replace(",", "").toDoubleOrNull()?.times(if (budget.groupValues[2].isNotBlank()) 1000.0 else 1.0)
        }
        var minCarpet: Double? = null
        var maxCarpet: Double? = null
        val sqftRangeRegex = Regex("""(\d+[\d,.]*)\s*[-–to\s]+\s*(\d+[\d,.]*)\s*(sq\.?ft|sqft|sft|carpet)""", RegexOption.IGNORE_CASE)
        val singleSqftRegex = Regex("""(\d+[\d,.]*)\s*(sq\.?ft|sqft|sft|carpet)""", RegexOption.IGNORE_CASE)

        sqftRangeRegex.find(query)?.let {
            minCarpet = it.groupValues[1].replace(",", "").toDoubleOrNull()
            maxCarpet = it.groupValues[2].replace(",", "").toDoubleOrNull()
        } ?: singleSqftRegex.find(query)?.let {
            val v = it.groupValues[1].replace(",", "").toDoubleOrNull()
            if (lower.contains("under") || lower.contains("below") || lower.contains("up to")) {
                maxCarpet = v
            } else {
                minCarpet = v
            }
        }

        val furnishing = when {
            lower.contains("unfurnished") -> FurnishingType.UNFURNISHED
            lower.contains("semi furnished") || lower.contains("semi-furnished") -> FurnishingType.SEMI_FURNISHED
            lower.contains("fully furnished") || lower.contains("fully-furnished") || lower.contains("furnished") -> FurnishingType.FULLY_FURNISHED
            lower.contains("warm shell") -> FurnishingType.WARM_SHELL
            lower.contains("bare shell") -> FurnishingType.BARE_SHELL
            else -> null
        }

        val isVague = foundLocations.isEmpty() && propertyType.isBlank() && maxPrice == null && minCarpet == null && maxCarpet == null

        return ExtractedClientRequirement(
            rawPrompt = query,
            portfolio = portfolio,
            propertyType = propertyType,
            transactionType = transactionType,
            minPriceOrRent = minPrice,
            maxPriceOrRent = maxPrice,
            minCarpetSqFt = minCarpet,
            maxCarpetSqFt = maxCarpet,
            locations = foundLocations,
            furnishing = furnishing,
            bhkConfig = if (propertyType.contains("BHK")) propertyType else null,
            isVague = isVague,
            extractionSource = "Smart Pattern Recognition Engine"
        )
    }

    fun detectPortfolioFromQuery(query: String): Brand? {
        val lower = query.lowercase()
        val commScore = listOf("office", "shop", "showroom", "warehouse", "pre-leased", "commercial", "lease", "workstation").count { lower.contains(it) }
        val resiScore = listOf("bhk", "flat", "villa", "apartment", "bungalow", "residential", "resi").count { lower.contains(it) }
        return when {
            commScore > resiScore -> Brand.COMMERCIAL
            resiScore > commScore -> Brand.RESIDENTIAL
            else -> null
        }
    }

    /**
     * Executes property filtering using the extracted client requirement rules against actual Master Inventory.
     */
    fun executeNaturalSearch(
        req: ExtractedClientRequirement,
        inventory: List<PropertyItem>
    ): NaturalSearchResult {
        val portfolioFiltered = when (req.portfolio) {
            Brand.COMMERCIAL -> inventory.filter { it.isCommercial() }
            Brand.RESIDENTIAL -> inventory.filter { it.isResidential() }
            null -> inventory
        }

        val scored = portfolioFiltered.map { prop ->
            var score = 50
            val reasons = mutableListOf<String>()

            if (req.locations.isNotEmpty()) {
                val propLoc = prop.location.lowercase()
                if (req.locations.any { propLoc.contains(it.lowercase()) }) {
                    score += 25
                    reasons.add("Micro-market match: ${prop.location}")
                }
            }

            if (req.propertyType.isNotBlank()) {
                val pType = prop.propertyType.lowercase()
                val reqType = req.propertyType.lowercase()
                if (pType.contains(reqType) || (reqType.contains("office") && prop.isCommercial())) {
                    score += 20
                    reasons.add("Property type matches ${req.propertyType}")
                }
            }

            if (req.transactionType.isNotBlank()) {
                val isReqRental = req.transactionType.contains("lease", ignoreCase = true) || req.transactionType.contains("rent", ignoreCase = true)
                if (prop.tab.isRental == isReqRental) {
                    score += 15
                    reasons.add(if (isReqRental) "Rental structure match" else "Outright sale match")
                }
            }

            if (req.maxPriceOrRent != null && req.maxPriceOrRent > 0) {
                if (prop.priceOrRent <= req.maxPriceOrRent) {
                    score += 20
                    reasons.add("Price within budget (${CalculationUtils.formatIndianCurrency(prop.priceOrRent, prop.tab.isRental)})")
                } else if (prop.priceOrRent <= req.maxPriceOrRent * 1.15) {
                    score += 5
                    reasons.add("Slightly above budget (${CalculationUtils.formatIndianCurrency(prop.priceOrRent, prop.tab.isRental)})")
                }
            }

            if (req.minCarpetSqFt != null && req.maxCarpetSqFt != null) {
                if (prop.carpetAreaSqFt >= req.minCarpetSqFt && prop.carpetAreaSqFt <= req.maxCarpetSqFt * 1.1) {
                    score += 15
                    reasons.add("Carpet area in range (${prop.carpetAreaSqFt.toInt()} sq.ft)")
                }
            } else if (req.maxCarpetSqFt != null) {
                if (prop.carpetAreaSqFt <= req.maxCarpetSqFt * 1.1) {
                    score += 15
                    reasons.add("Carpet area up to ${req.maxCarpetSqFt.toInt()} sq.ft")
                }
            } else if (req.minCarpetSqFt != null) {
                if (prop.carpetAreaSqFt >= req.minCarpetSqFt * 0.9) {
                    score += 15
                    reasons.add("Carpet area above ${req.minCarpetSqFt.toInt()} sq.ft")
                }
            }

            if (req.furnishing != null && prop.furnishing == req.furnishing) {
                score += 10
                reasons.add("Furnishing match: ${prop.furnishing.label}")
            }

            val finalScore = score.coerceIn(20, 98)
            ClientMatchResult(
                property = prop,
                matchScorePercent = finalScore,
                matchingReasons = reasons.ifEmpty { listOf("Active property in portfolio") },
                matchType = if (finalScore >= 75) "Exact Match" else if (finalScore >= 55) "Close Match" else "Alternative"
            )
        }.sortedByDescending { it.matchScorePercent }

        val matches = scored.filter { it.matchScorePercent >= 60 }.map { it.property }

        val alternatives = if (matches.isEmpty()) {
            portfolioFiltered.take(3)
        } else emptyList()

        return NaturalSearchResult(
            exactMatches = matches,
            alternatives = alternatives,
            isVague = req.isVague || (req.locations.isEmpty() && req.maxPriceOrRent == null),
            extractedRequirement = req
        )
    }

    /**
     * FIND PROPERTY FOR CLIENT
     * Strictly searches ONLY the current tenant's private inventory.
     * Groups results into: Exact Matches, Close Matches, Alternative Properties.
     */
    fun matchClientRequirementsCategorized(requirementText: String, tenantInventory: List<PropertyItem>): List<ClientMatchCategory> {
        if (requirementText.isBlank()) return emptyList()
        val req = fallbackExtractRequirements(requirementText)
        val results = tenantInventory.mapNotNull { prop ->
            if (prop.archiveStatus == ArchiveStatus.ARCHIVED || prop.status == AvailabilityStatus.NOT_AVAILABLE) return@mapNotNull null
            if (req.portfolio == Brand.COMMERCIAL && !prop.isCommercial()) return@mapNotNull null
            if (req.portfolio == Brand.RESIDENTIAL && !prop.isResidential()) return@mapNotNull null
            val reasons = mutableListOf<String>()
            val unknowns = mutableListOf<String>()
            if (prop.status != AvailabilityStatus.AVAILABLE) unknowns += "Availability requires confirmation"
            if (req.transactionType.isNotBlank()) {
                val rental = req.transactionType.equals("Lease", true)
                if (prop.tab.isRental != rental) return@mapNotNull null
                reasons += "Transaction: ${req.transactionType}"
            } else unknowns += "Buy/rent requirement not specified"
            if (req.propertyType.isNotBlank()) {
                val typeMatch = when {
                    req.propertyType.contains("BHK") -> prop.bhk?.replace(" ", "").equals(req.propertyType.replace(" ", ""), true)
                    req.propertyType == "Pre-Leased" -> prop.tab == InventoryTab.PRE_LEASED
                    else -> prop.propertyType.contains(req.propertyType, true)
                }
                if (prop.propertyType.isBlank() && prop.bhk.isNullOrBlank()) unknowns += "Property type unknown"
                else if (!typeMatch) return@mapNotNull null
                else reasons += "Property type: ${req.propertyType}"
            } else unknowns += "Property type requirement not specified"
            if (req.locations.isNotEmpty()) {
                if (prop.location.isBlank()) unknowns += "Location unknown"
                else if (req.locations.none { prop.location.contains(it, true) }) return@mapNotNull null
                else reasons += "Location: ${prop.location}"
            } else unknowns += "Preferred location not recognised; please verify"
            if (req.minPriceOrRent != null || req.maxPriceOrRent != null) {
                if (prop.priceOrRent <= 0) unknowns += "Price/rent unknown"
                else if ((req.minPriceOrRent != null && prop.priceOrRent < req.minPriceOrRent) ||
                    (req.maxPriceOrRent != null && prop.priceOrRent > req.maxPriceOrRent)) return@mapNotNull null
                else reasons += "Within stated budget"
            } else unknowns += "Budget not specified or recognised"
            if (req.minCarpetSqFt != null || req.maxCarpetSqFt != null) {
                if (prop.carpetAreaSqFt <= 0) unknowns += "Carpet area unknown"
                else if ((req.minCarpetSqFt != null && prop.carpetAreaSqFt < req.minCarpetSqFt) ||
                    (req.maxCarpetSqFt != null && prop.carpetAreaSqFt > req.maxCarpetSqFt)) return@mapNotNull null
                else reasons += "Carpet area within stated range"
            } else unknowns += "Carpet requirement not specified or recognised"
            if (req.furnishing != null) {
                if (prop.furnishing != req.furnishing) return@mapNotNull null
                reasons += "Furnishing: ${prop.furnishing.label}"
            }
            // Dates and other free-text constraints must not become a claimed exact match.
            val lower = requirementText.lowercase()
            if (listOf("possession", "immediate", "ready to move", "workstation", "parking", "roi").any { lower.contains(it) } || Regex("20[2-9][0-9]").containsMatchIn(lower)) {
                unknowns += "Possession, configuration and investment terms need manual verification"
            }
            val exact = unknowns.isEmpty()
            ClientMatchResult(prop, if (exact) 100 else (reasons.size * 15).coerceAtMost(85),
                reasons + unknowns.map { "Unknown / verify: $it" }, if (exact) "Exact Match" else "Needs Verification")
        }.sortedByDescending { it.matchScorePercent }
        return listOf(
            ClientMatchCategory("Exact Matches", results.filter { it.matchType == "Exact Match" }),
            ClientMatchCategory("Needs Verification", results.filter { it.matchType != "Exact Match" })
        ).filter { it.results.isNotEmpty() }
    }

    /**
     * CUSTOM WHATSAPP WRITE-UP GENERATOR
     * Supports presets and tone adjustments. Never leaks confidential contacts.
     */
    fun generateCustomWhatsAppPitch(
        properties: List<PropertyItem>,
        promptStyle: String,
        companyName: String,
        companyContact: String
    ): String {
        val isShort = promptStyle.contains("short", ignoreCase = true)
        val isInvestor = promptStyle.contains("investor", ignoreCase = true) || promptStyle.contains("pre-leased", ignoreCase = true)
        val isBroker = promptStyle.contains("broker", ignoreCase = true)

        val sb = StringBuilder()
        sb.append("✨ *EXCLUSIVE MANDATE | $companyName*\n\n")

        properties.forEachIndexed { i, prop ->
            if (properties.size > 1) {
                sb.append("*OPTION ${i + 1}: ${prop.projectName.uppercase()}*\n")
            } else {
                sb.append("🏢 *${prop.projectName.uppercase()}*\n")
            }

            sb.append("📍 Location: ${prop.location}\n")
            sb.append("📐 Carpet Area: ${prop.carpetAreaSqFt.toInt()} sq.ft\n")
            sb.append("🛋 Furnishing: ${prop.furnishing.label}\n")

            if (isInvestor && prop.roiPercent != null) {
                sb.append("📈 ROI Yield: ${prop.roiPercent}%\n")
                sb.append("💼 Tenant: ${prop.tenantName ?: "MNC Corporate"}\n")
            }

            sb.append("💰 Commercials: ${CalculationUtils.formatIndianCurrency(prop.priceOrRent, prop.tab.isRental)}\n")

            if (!isShort) {
                sb.append("🚗 Parking: ${prop.parking}\n")
                sb.append("🔑 Possession: ${prop.possession}\n")
                if (prop.amenities.isNotEmpty()) {
                    sb.append("🌟 Amenities: ${prop.amenities.take(3).joinToString(", ")}\n")
                }
            }
            sb.append("\n")
        }

        if (isBroker) {
            sb.append("🤝 *Broker Welcome* — Side-by-side inspection can be arranged today.\n")
        } else {
            sb.append("Direct verified mandate through $companyName.\n")
        }

        sb.append("📞 *Direct Connect:* $companyContact")
        return sb.toString().trim()
    }

    fun matchClientRequirements(requirementText: String, inventory: List<PropertyItem>): List<ClientMatchResult> {
        return matchClientRequirementsCategorized(requirementText, inventory).flatMap { it.results }
    }

    fun generatePropertyPitch(property: PropertyItem): String {
        return generateCustomWhatsAppPitch(
            properties = listOf(property),
            promptStyle = "Make a premium short WhatsApp message",
            companyName = if (property.tab.brand == Brand.COMMERCIAL) "COMMERCIAL PORTFOLIO" else "RESIDENTIAL PORTFOLIO",
            companyContact = "9766880099 | 9766668099"
        )
    }

    /**
     * AI Paste Multi-Broker Requirement Splitter:
     * Extracts sender/broker details (Broker Name, Firm Name, Mobile) and splits multi-brand
     * or multi-requirement WhatsApp forwards into individual selectable ExtractedLeadDraft cards.
     */
    suspend fun parseBrokerOrClientLeadsBatch(rawText: String): List<ExtractedLeadDraft> = withContext(Dispatchers.IO) {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (_: Exception) { "" }
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val promptSafe = trimmed.replace("\"", "'")
                val systemPrompt = """
You are a senior real estate CRM intake assistant in Pune, India.
Extract one or MULTIPLE client/brand requirements from the pasted WhatsApp forward, call note, or broker message.
RULES:
1. If the message is from a Broker / Channel Partner with multiple brands or multiple requirements (e.g., numbered bullets or separate lines), split EACH requirement into its own JSON object in the JSON array and copy the sender's brokerName, firmName, and 10-digit mobile onto every item.
2. NEVER invent fake phone numbers, names, budgets, or areas. Leave empty string "" or null if omitted.
3. For Commercial Lease or Residential Rent, express monthly rent in full INR (e.g. "1.25 Lac" -> 125000, "45k" -> 45000). For Purchase / Pre-Leased, express total budget in full INR (e.g. "2.5 Cr" -> 25000000).
4. Output ONLY a valid JSON array of objects with exact keys:
   "clientName": string (brand name, company, or client name; if broker forward without brand name, use e.g. "Client via <BrokerName>")
   "mobile": string (10-digit Indian mobile if present, else "")
   "brokerName": string
   "firmName": string
   "isBrokerLead": boolean
   "division": "Commercial" or "Residential"
   "propertyType": one of ["Office", "Shop", "Showroom", "Restaurant / F&B", "Warehouse", "Commercial Land", "Apartment / Flat", "Villa / Bungalow", "Plot / Land", "Penthouse"]
   "transactionType": one of ["Lease", "Purchase", "Pre-Leased", "Rent", "Under Construction"]
   "subOption": string (e.g. "Fully Furnished", "Warm Shell", "Ground / High-Street", "7%–8%", "3 BHK", or "")
   "location": string (comma-separated Pune localities if mentioned)
   "minCarpetSqFt": integer or null
   "maxCarpetSqFt": integer or null
   "areaRangeText": string (e.g. "1,101–1,500 sq.ft" or "1,200 sq.ft" or "")
   "minBudgetInr": integer or null
   "maxBudgetInr": integer or null
   "budgetText": string (short readable budget/rent text if mentioned, e.g. "₹1.25 Lac/mo" or "₹2.50 Cr")
   "requirementSummary": string (clean 1-line summary)
   "notes": string (any extra floor, seating, parking, possession, brand notes)

Pasted text:
"$promptSafe"
                """.trimIndent()

                val jsonPayload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", systemPrompt) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                    })
                }

                val models = listOf("gemini-2.5-flash", "gemini-2.0-flash")
                var responseBody = ""
                var success = false
                for (modelName in models) {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                    val request = Request.Builder()
                        .url(url)
                        .post(jsonPayload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val response = client.newCall(request).execute()
                    responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        success = true
                        break
                    }
                }

                if (success) {
                    val root = JSONObject(responseBody)
                    val text = root.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                    val cleanedJson = stripJsonFences(text)
                    val jsonArray = if (cleanedJson.trim().startsWith("[")) {
                        JSONArray(cleanedJson)
                    } else {
                        JSONArray().put(JSONObject(cleanedJson))
                    }
                    val parsedList = mutableListOf<ExtractedLeadDraft>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val minSq = if (obj.has("minCarpetSqFt") && !obj.isNull("minCarpetSqFt")) obj.optLong("minCarpetSqFt").takeIf { it > 0L } else null
                        val maxSq = if (obj.has("maxCarpetSqFt") && !obj.isNull("maxCarpetSqFt")) obj.optLong("maxCarpetSqFt").takeIf { it > 0L } else null
                        val minBud = if (obj.has("minBudgetInr") && !obj.isNull("minBudgetInr")) obj.optLong("minBudgetInr").takeIf { it > 0L } else null
                        val maxBud = if (obj.has("maxBudgetInr") && !obj.isNull("maxBudgetInr")) obj.optLong("maxBudgetInr").takeIf { it > 0L } else null
                        val division = obj.optString("division", "Commercial").let {
                            if (it.equals("Residential", true)) "Residential" else "Commercial"
                        }
                        val propType = sanitizeExtractedString(obj.optString("propertyType", "")).ifBlank {
                            if (division == "Residential") "Apartment / Flat" else "Office"
                        }
                        val txType = sanitizeExtractedString(obj.optString("transactionType", "")).ifBlank {
                            if (division == "Residential") "Rent" else "Lease"
                        }
                        val bName = sanitizeExtractedString(obj.optString("brokerName", ""))
                        val fName = sanitizeExtractedString(obj.optString("firmName", ""))
                        val isBroker = obj.optBoolean("isBrokerLead", bName.isNotBlank() || fName.isNotBlank())
                        val rawBudgetText = sanitizeExtractedString(obj.optString("budgetText", ""))
                        val isMonthly = txType.equals("Lease", true) || txType.equals("Rent", true)
                        val resolvedBudget = rawBudgetText.ifBlank {
                            val refAmt = maxBud ?: minBud
                            if (refAmt != null && refAmt > 0L) CalculationUtils.formatIndianCurrency(refAmt.toDouble(), isMonthly) else ""
                        }
                        val resolvedArea = sanitizeExtractedString(obj.optString("areaRangeText", "")).ifBlank {
                            when {
                                minSq != null && maxSq != null && minSq != maxSq -> "$minSq–$maxSq sq.ft"
                                maxSq != null -> "$maxSq sq.ft"
                                minSq != null -> "$minSq+ sq.ft"
                                else -> ""
                            }
                        }
                        val cName = sanitizeExtractedString(obj.optString("clientName", "")).ifBlank {
                            if (isBroker && bName.isNotBlank()) "Client via $bName" else "Lead ${i + 1}"
                        }
                        parsedList.add(
                            ExtractedLeadDraft(
                                clientName = cName,
                                mobile = normalizeIndianPhone(obj.optString("mobile", "")),
                                brokerName = bName,
                                firmName = fName,
                                isBrokerLead = isBroker,
                                division = division,
                                propertyType = propType,
                                transactionType = txType,
                                subOption = sanitizeExtractedString(obj.optString("subOption", "")),
                                location = sanitizeExtractedString(obj.optString("location", "")),
                                minCarpetSqFt = minSq,
                                maxCarpetSqFt = maxSq,
                                areaRangeText = resolvedArea,
                                minBudgetInr = minBud,
                                maxBudgetInr = maxBud,
                                budgetText = resolvedBudget,
                                requirementSummary = sanitizeExtractedString(obj.optString("requirementSummary", "")),
                                notes = sanitizeExtractedString(obj.optString("notes", "")),
                                sourceLine = trimmed
                            )
                        )
                    }
                    if (parsedList.isNotEmpty()) return@withContext parsedList
                }
            } catch (e: Exception) {
                Log.w("GeminiRealEstate", "AI lead batch parse error: ${e.message}")
            }
        }

        return@withContext fallbackParseLeadsBatch(trimmed)
    }

    internal fun fallbackParseLeadsBatch(rawText: String): List<ExtractedLeadDraft> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        // 1. Detect global sender / broker header or footer metadata
        val globalPhone = Regex("""(?:\+?91[\s-]?)?([6-9]\d{9})\b""")
            .find(rawText.replace(Regex("[\\s-]"), " "))
            ?.groupValues?.getOrNull(1)
            ?.takeIf { it.length == 10 }
            .orEmpty()
        val rawDigitsPhone = if (globalPhone.isBlank()) {
            Regex("""(?:\+?91[\s-]?)?([6-9][\d\s-]{8,12}\d)""").find(rawText)
                ?.groupValues?.getOrNull(1)
                ?.filter { it.isDigit() }
                ?.takeLast(10)
                ?.takeIf { it.length == 10 }
                .orEmpty()
        } else globalPhone

        var detectedBrokerName = ""
        var detectedFirmName = ""
        val firmKeywords = listOf("realty", "properties", "associates", "consultants", "estates", "advisors", "infra", "agency", "brokers", "solutions")
        val brokerLineSignal = Regex("""^(?:from|broker|cp|partner|regards|name|contact)\s*[:\-–]\s*(.+)$""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val lower = line.lowercase()
            brokerLineSignal.find(line)?.let { m ->
                val valPart = m.groupValues[1].trim().replace(Regex("""(?:\+?91[\s-]?)?[6-9]\d{9}"""), "").trim(' ', '-', ',', '|', '•')
                if (valPart.isNotBlank()) {
                    if (firmKeywords.any { valPart.lowercase().contains(it) }) {
                        if (detectedFirmName.isBlank()) detectedFirmName = valPart
                    } else if (detectedBrokerName.isBlank()) {
                        detectedBrokerName = valPart
                    }
                }
            }
            if (detectedFirmName.isBlank() && firmKeywords.any { lower.contains(it) } &&
                !lower.contains("sq") && !lower.contains("bhk") && !lower.contains("rent") && !lower.contains("lac")
            ) {
                detectedFirmName = line.replace(Regex("""(?:\+?91[\s-]?)?[6-9]\d{9}"""), "").trim(' ', '-', ',', '|', '•')
            }
        }
        val mentionsBrokerHint = rawText.contains("broker", true) ||
            rawText.contains("cp ", true) ||
            rawText.contains("channel partner", true) ||
            detectedBrokerName.isNotBlank() ||
            detectedFirmName.isNotBlank()

        // 2. Split into candidate requirement chunks (numbered items, bullets, or requirement lines)
        val reqKeywordRegex = Regex("""(sq\.?\s*ft|sqft|bhk|office|shop|showroom|warehouse|land|plot|flat|apartment|villa|bungalow|penthouse|rent|lease|buy|purchase|pre-leased|lac|lakh|cr|crore|kharadi|viman|baner|hinjewadi|kalyani|koregaon|wakad|aundh|fc road|sb road|balewadi|magarpatta|hadapsar)""", RegexOption.IGNORE_CASE)
        val bulletPrefixRegex = Regex("""^(?:\d+[\.\)]\s*|[-•*▪➤]+\s*|(?:option|req|requirement|brand)\s*\d*\s*[:\-]\s*)""", RegexOption.IGNORE_CASE)

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()
        for (line in lines) {
            val isMetaOnly = (brokerLineSignal.matches(line) || line.replace(Regex("""[\d\s+\-()]"""), "").isBlank()) &&
                !reqKeywordRegex.containsMatchIn(line)
            if (isMetaOnly) continue

            val startsBullet = bulletPrefixRegex.containsMatchIn(line)
            if (startsBullet && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk.clear()
            } else if (currentChunk.isNotEmpty() && reqKeywordRegex.containsMatchIn(line) && reqKeywordRegex.containsMatchIn(currentChunk.toString())) {
                // Separate standalone requirement lines when each line has its own area/type/location
                chunks.add(currentChunk.toString().trim())
                currentChunk.clear()
            }
            if (currentChunk.isNotEmpty()) currentChunk.append(" ")
            currentChunk.append(line.replace(bulletPrefixRegex, "").trim())
        }
        if (currentChunk.isNotEmpty()) chunks.add(currentChunk.toString().trim())

        val finalChunks = if (chunks.isEmpty()) listOf(rawText) else chunks
        val isMultiItem = finalChunks.size > 1 || mentionsBrokerHint

        return finalChunks.mapIndexed { idx, chunk ->
            val extracted = fallbackExtractRequirements(chunk)
            val chunkLower = chunk.lowercase()
            val chunkPhone = Regex("""(?:\+?91[\s-]?)?([6-9]\d{9})\b""")
                .find(chunk.replace(Regex("[\\s-]"), " "))
                ?.groupValues?.getOrNull(1)
                ?.takeIf { it.length == 10 }
                ?: rawDigitsPhone

            val division = when {
                extracted.portfolio == Brand.RESIDENTIAL ||
                    chunkLower.contains("bhk") || chunkLower.contains("flat") ||
                    chunkLower.contains("villa") || chunkLower.contains("bungalow") ||
                    chunkLower.contains("apartment") || chunkLower.contains("penthouse") -> "Residential"
                else -> "Commercial"
            }

            val propType = when {
                chunkLower.contains("showroom") -> "Showroom"
                chunkLower.contains("restaurant") || chunkLower.contains("cafe") || chunkLower.contains("f&b") || chunkLower.contains("kitchen") -> "Restaurant / F&B"
                chunkLower.contains("shop") || chunkLower.contains("retail") -> "Shop"
                chunkLower.contains("warehouse") || chunkLower.contains("godown") -> "Warehouse"
                chunkLower.contains("commercial land") || (division == "Commercial" && chunkLower.contains("land")) -> "Commercial Land"
                chunkLower.contains("office") || division == "Commercial" && extracted.propertyType.isBlank() -> "Office"
                chunkLower.contains("villa") || chunkLower.contains("bungalow") -> "Villa / Bungalow"
                chunkLower.contains("penthouse") -> "Penthouse"
                chunkLower.contains("plot") || chunkLower.contains("land") -> "Plot / Land"
                division == "Residential" -> "Apartment / Flat"
                else -> extracted.propertyType.ifBlank { "Office" }
            }

            val txType = when {
                chunkLower.contains("pre-leased") || chunkLower.contains("pre leased") || chunkLower.contains("roi") || chunkLower.contains("yield") -> "Pre-Leased"
                chunkLower.contains("under construction") -> "Under Construction"
                chunkLower.contains("buy") || chunkLower.contains("purchase") || chunkLower.contains("sale") || chunkLower.contains("resale") || chunkLower.contains("out") -> "Purchase"
                division == "Residential" -> "Rent"
                else -> "Lease"
            }

            val subOption = when {
                txType == "Pre-Leased" -> {
                    when {
                        chunkLower.contains("8%") -> "8%+"
                        chunkLower.contains("6%") -> "6%–7%"
                        else -> "7%–8%"
                    }
                }
                division == "Residential" && (propType.contains("Apartment") || propType.contains("Villa") || propType.contains("Penthouse")) -> {
                    extracted.bhkConfig ?: Regex("""(\d+(?:\.\d+)?)\s*bhk""", RegexOption.IGNORE_CASE).find(chunk)?.groupValues?.getOrNull(1)?.let { "$it BHK" }.orEmpty()
                }
                propType in listOf("Shop", "Showroom", "Restaurant / F&B") -> {
                    when {
                        chunkLower.contains("hard cooking") || chunkLower.contains("exhaust") -> "Hard Cooking Provision"
                        chunkLower.contains("mall") -> "Mall Retail"
                        chunkLower.contains("lower") || chunkLower.contains("upper ground") -> "Lower / Upper Ground"
                        chunkLower.contains("ground") || chunkLower.contains("high street") || chunkLower.contains("high-street") -> "Ground / High-Street"
                        else -> ""
                    }
                }
                else -> extracted.furnishing?.label ?: ""
            }

            // Brand / Client Name prefix before dash, colon, or "looking for" / "needs" / "wants"
            val candidateName = Regex("""^([A-Za-z0-9&\.\s]{2,34}?)(?:\s*[-–—:]\s*|\s+(?:needs?|wants?|looking|req|requirement)\b)""", RegexOption.IGNORE_CASE)
                .find(chunk)
                ?.groupValues?.getOrNull(1)
                ?.trim()
                ?.takeIf { cand ->
                    val cl = cand.lowercase()
                    !reqKeywordRegex.containsMatchIn(cand) && !cl.startsWith("urgent") && !cl.startsWith("client")
                }
                ?: if (isMultiItem && detectedBrokerName.isNotBlank()) "Client ${idx + 1} via $detectedBrokerName"
                else if (isMultiItem) "Requirement ${idx + 1}"
                else "New Client"

            val minSq = extracted.minCarpetSqFt?.toLong()
            val maxSq = extracted.maxCarpetSqFt?.toLong()
            val areaText = when {
                minSq != null && maxSq != null && minSq != maxSq -> "$minSq–$maxSq sq.ft"
                maxSq != null -> "$maxSq sq.ft"
                minSq != null -> "$minSq+ sq.ft"
                else -> ""
            }
            val minBud = extracted.minPriceOrRent?.toLong()
            val maxBud = extracted.maxPriceOrRent?.toLong()
            val isMonthly = txType == "Lease" || txType == "Rent"
            val budText = (maxBud ?: minBud)?.let {
                CalculationUtils.formatIndianCurrency(it.toDouble(), isMonthly = isMonthly)
            }.orEmpty()
            val locText = extracted.locations.joinToString(", ")
            val summary = listOfNotNull(
                txType,
                subOption.takeIf { it.isNotBlank() && division == "Residential" },
                propType,
                subOption.takeIf { it.isNotBlank() && division == "Commercial" }?.let { "($it)" },
                areaText.takeIf { it.isNotBlank() }?.let { "• $it" }
            ).joinToString(" ")

            ExtractedLeadDraft(
                clientName = candidateName,
                mobile = chunkPhone,
                brokerName = detectedBrokerName,
                firmName = detectedFirmName,
                isBrokerLead = isMultiItem && (mentionsBrokerHint || detectedBrokerName.isNotBlank()),
                division = division,
                propertyType = propType,
                transactionType = txType,
                subOption = subOption,
                location = locText,
                minCarpetSqFt = minSq,
                maxCarpetSqFt = maxSq,
                areaRangeText = areaText,
                minBudgetInr = minBud,
                maxBudgetInr = maxBud,
                budgetText = budText,
                requirementSummary = summary,
                notes = chunk,
                sourceLine = chunk
            )
        }
    }
}


