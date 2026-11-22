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
    val saleableAreaSqFt: Double? = null,
    val landAreaValue: Double? = null,
    val landAreaUnit: String = "",
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
    val conferenceRooms: Int? = null,
    val tenantName: String = "",
    val negotiable: Boolean? = null,
    val isSevenTwelveClear: Boolean? = null,
    val ownerName: String = "",
    val ownerPhone: String = "",
    val sourceBrokerName: String = "",
    val sourceBrokerPhone: String = "",
    val additionalPhones: List<String> = emptyList(),
    val contactName: String = "",
    val contactPhone: String = "",
    val remarks: String = "",
    val confidenceNotes: String = "",
    val builderName: String = "",
    val rawSnippet: String = "",
    val hasExplicitFurnishing: Boolean = false,
    val isPriceConflictOrUpdate: Boolean = false,
    val reviewWarnings: List<String> = emptyList(),
    val extractedFields: Map<String, ExtractedField<String>> = emptyMap(),
    val missingFields: List<String> = emptyList()
) {
    val hasFlaggedFields: Boolean
        get() = reviewWarnings.isNotEmpty() || extractedFields.values.any { it.tier == ConfidenceTier.CONFIRM_REQUIRED }
}

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
            Brand.COMMERCIAL -> "Commercial Division"
            Brand.RESIDENTIAL -> "Residential Division"
            null -> "All Divisions"
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

    /**
     * Build 01: Two-Step Safe WhatsApp Classification & Multi-Property Extraction.
     * - Step A classifies message intent (PROPERTY_LISTING, CLIENT_REQUIREMENT, EXISTING_PROPERTY_UPDATE,
     *   UNRELATED, AMBIGUOUS_NEEDS_REVIEW). Blocks CLIENT_REQUIREMENT & UNRELATED from Add Property.
     * - Step B extracts isolated multi-property drafts with field-level evidence, confidence (0-100),
     *   and needsReview flags. Zero fabricated defaults; graceful offline fallback.
     */
    suspend fun extractWhatsAppSafely(
        rawText: String,
        senderName: String = "",
        senderPhone: String = ""
    ): WhatsAppExtractionBatchResult = withContext(Dispatchers.IO) {
        val cleaned = rawText.replace("\r\n", "\n").trim()
        if (cleaned.isBlank()) {
            return@withContext WhatsAppExtractionBatchResult(
                classification = WhatsAppMessageClassification.UNRELATED,
                classificationConfidence = 100,
                classificationReason = "Message is empty.",
                drafts = emptyList(),
                rawMessage = "",
                usedFallbackParser = true
            )
        }

        // Fast deterministic pre-guard for obvious client requirements / unrelated chats so AI never misroutes them
        val (localClass, localConf, localReason) = SafeWhatsAppExtractor.classifyMessage(cleaned)
        if ((localClass == WhatsAppMessageClassification.CLIENT_REQUIREMENT && localConf >= 90) ||
            (localClass == WhatsAppMessageClassification.UNRELATED && localConf >= 92)
        ) {
            return@withContext SafeWhatsAppExtractor.extractOffline(cleaned, senderName, senderPhone)
        }

        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (_: Exception) { "" }
        var networkError: String? = null

        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val escapedInput = cleaned.replace("\"\"\"", "'")
                val systemPrompt = """
You are a strict Pune real estate data extraction specialist for TLC-1.
Analyze the WhatsApp message in TWO STEPS and return ONLY raw JSON (no markdown fences):

STEP A — Classify the message into exactly ONE of:
"PROPERTY_LISTING", "CLIENT_REQUIREMENT", "EXISTING_PROPERTY_UPDATE", "UNRELATED", "AMBIGUOUS_NEEDS_REVIEW".
- Use "CLIENT_REQUIREMENT" if someone is looking for / needs / wants a property or asks to share options (even if formatted like specs).
- Use "EXISTING_PROPERTY_UPDATE" if it revises/drops/updates price, rent, or sold/leased status of a unit.
- Use "UNRELATED" if it is non-real-estate chat.

STEP B — If PROPERTY_LISTING, EXISTING_PROPERTY_UPDATE, or AMBIGUOUS_NEEDS_REVIEW, extract an isolated array "properties" (1 object per distinct property).
STRICT ZERO-FABRICATION RULES:
1. NEVER invent defaults (NO fake 1000 sqft, ₹85000, ₹1.25 Cr, 6 months deposit, Kharadi, Prime Center, Mid Floor, Immediate). Unmentioned fields MUST be null or "".
2. NEVER substitute Carpet Area for Built-up Area (BU/BUA) or Saleable Area (SBA). Keep them strictly separate.
3. Preserve Land units ("Guntha", "Acre", "Sq.Ft") in landAreaValue + landAreaUnit instead of carpetAreaSqFt.
4. Resolve Pune shorthands from context: FF (First Floor vs Fully Furnished), WS (Workstations vs Warm Shell), BS (Bare Shell), SF (Semi-Furnished), GF/UG, Conf, Cab, LI, Esc, CAM, PL, Avl, Nego, Poss, Imd, Dep/SD, 7/12.
5. Normalize numbers deterministically (35k -> 35000, 1.2L -> 120000, 1.50Cr -> 15000000). Flag ambiguous amounts like "2.55k rent" with confidence < 70 and needsReview=true.
6. Separate Owner (only if explicitly labeled Owner/Landlord/Malak) from Source/Broker. NEVER assume the WhatsApp sender is the Owner.
7. Wrap extracted fields as {"value": ..., "evidence": "exact short quote", "confidence": 0-100, "needsReview": boolean}.

JSON schema:
{
  "classification": "PROPERTY_LISTING|CLIENT_REQUIREMENT|EXISTING_PROPERTY_UPDATE|UNRELATED|AMBIGUOUS_NEEDS_REVIEW",
  "classificationConfidence": 0-100,
  "classificationReason": "short reason",
  "properties": [
    {
      "tab": {"value": "Off Lease|Off Out|Shop Lease|Shop Out|Pre - Leased|Resi Rent|Resi Resale|Und Const", "evidence": "", "confidence": 0, "needsReview": false},
      "propertyType": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "projectName": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "location": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "unitNumber": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "floor": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "bhkConfiguration": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "carpetAreaSqFt": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "builtUpAreaSqFt": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "saleableAreaSqFt": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "landAreaValue": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "landAreaUnit": "",
      "rent": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "priceSale": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "deposit": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "roiPercent": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "furnishing": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "possession": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "parking": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "lockInMonths": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "escalationTerms": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "maintenanceTerms": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "workstations": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "cabins": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "conferenceRooms": {"value": null, "evidence": "", "confidence": 0, "needsReview": false},
      "tenantName": {"value": "", "evidence": "", "confidence": 0, "needsReview": false},
      "ownerName": "",
      "ownerPhone": "",
      "sourceBrokerName": "",
      "sourceBrokerPhone": "",
      "builderName": "",
      "rawSnippet": "original text for this property only",
      "remarks": "",
      "reviewWarnings": []
    }
  ]
}

Message:
"$escapedInput"
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
                    } else {
                        networkError = "AI service returned HTTP ${response.code}; switched to offline parser."
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
                    val parsedResult = SafeWhatsAppExtractor.parseGeminiBatchJson(
                        cleanedJson = cleanedJson,
                        rawMessage = cleaned,
                        senderName = senderName,
                        senderPhone = senderPhone
                    )
                    if (parsedResult != null) {
                        return@withContext parsedResult
                    }
                }
            } catch (e: Exception) {
                Log.w("GeminiRealEstate", "Safe WhatsApp extraction AI error: ${e.message}")
                networkError = "Offline or AI unavailable (${e.localizedMessage ?: "network error"}) — using safe offline parser."
            }
        }

        return@withContext SafeWhatsAppExtractor.extractOffline(
            rawText = cleaned,
            senderName = senderName,
            senderPhone = senderPhone,
            errorMessage = networkError
        )
    }

    suspend fun parsePropertyMessage(rawText: String, defaultCompanyPrefix: String = "ACS"): ExtractedPropertyDraft = withContext(Dispatchers.IO) {
        val result = extractWhatsAppSafely(rawText)
        result.drafts.firstOrNull() ?: ExtractedPropertyDraft(rawSnippet = rawText.trim())
    }

    suspend fun parsePropertyMessagesBatch(rawText: String, defaultCompanyPrefix: String = "ACS"): List<ExtractedPropertyDraft> = withContext(Dispatchers.IO) {
        extractWhatsAppSafely(rawText).drafts
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

    fun fallbackParseProperty(rawText: String): ExtractedPropertyDraft {
        val res = SafeWhatsAppExtractor.extractOffline(rawText)
        return res.drafts.firstOrNull()
            ?: SafeWhatsAppExtractor.parseIsolatedSegment(rawText)
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
                matchingReasons = reasons.ifEmpty { listOf("Active property in inventory") },
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

    /**
     * Batch 20: Strictly ON-DEMAND Call Recording Summarizer (runs Gemini Flash ONLY when
     * the user taps "✨ Summarize"). Produces a crisp 2-line summary + Budget / Area / Location
     * ready to write straight into the Call Note field.
     */
    suspend fun summarizeCallRecordingOnDemand(
        callerName: String = "",
        callerPhone: String = "",
        phoneNumber: String = callerPhone,
        durationSeconds: Int = 0,
        existingNotes: String = "",
        recordingFileName: String = "",
        audioFileName: String = recordingFileName,
        audioBytes: ByteArray? = null,
        audioMimeType: String = "audio/mp4"
    ): String = withContext(Dispatchers.IO) {
        val resolvedPhone = callerPhone.ifBlank { phoneNumber }
        val resolvedFileName = recordingFileName.ifBlank { audioFileName }
        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (_: Exception) { "" }
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            val geminiResult: String? = runCatching {
                val partsArray = JSONArray()
                if (audioBytes != null && audioBytes.isNotEmpty() && audioBytes.size <= 14 * 1024 * 1024) {
                    val base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
                    partsArray.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", audioMimeType)
                                .put("data", base64Audio)
                        )
                    )
                }
                val prompt = """
                    You are a real estate CRM assistant for The Leasing Company (Pune).
                    Summarize this call recording ($resolvedFileName, caller: $callerName $resolvedPhone, duration: ${durationSeconds}s, context: $existingNotes) into:
                    Line 1-2: A concise 2-line summary of the discussion and action items.
                    Line 3: Extracted parameters in the exact format: "Budget: <value or TBD> | Area: <sq.ft or TBD> | Location: <micro-market or Pune>"
                    Keep it strictly factual, compact, and max 3 lines total.
                """.trimIndent()
                partsArray.put(JSONObject().put("text", prompt))

                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
                    put(
                        "generationConfig",
                        JSONObject()
                            .put("temperature", 0.2)
                            .put("maxOutputTokens", 220)
                    )
                }
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
                val req = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bodyStr = resp.body?.string().orEmpty()
                    val root = JSONObject(bodyStr)
                    root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()

            if (!geminiResult.isNullOrBlank()) {
                return@withContext geminiResult
            }
        }

        // Smart local fallback parser when offline or if audio stream is unavailable
        val parsed = fallbackParseProperty("$existingNotes $resolvedFileName")
        val loc = parsed.location.ifBlank { "Pune (confirm micro-market)" }
        val areaStr = if (parsed.carpetAreaSqFt > 0.0) "${parsed.carpetAreaSqFt.toInt()} sq.ft." else "Area TBD"
        val budgetVal = parsed.rent ?: parsed.priceSale ?: parsed.priceOrRent
        val budgetStr = if (budgetVal > 0.0) {
            CalculationUtils.formatIndianCurrency(budgetVal, isMonthly = parsed.tab.isRental)
        } else {
            "Budget TBD"
        }
        val cleanCaller = callerName.takeIf { it.isNotBlank() && !it.equals("Unknown", true) } ?: resolvedPhone.ifBlank { "Caller" }
        val line1 = "Call recording ($resolvedFileName • ${durationSeconds}s) with $cleanCaller reviewed."
        val line2 = existingNotes.trim()
            .takeUnless { it.isBlank() || it.equals("Imported from device call log", true) }
            ?: "Discussed ${parsed.propertyType.ifBlank { "property" }} requirement & availability follow-up."
        "$line1\n$line2\nBudget: $budgetStr | Area: $areaStr | Location: $loc"
    }

    /**
     * Batch 22 + Build 01: On-demand parser for WhatsApp Follow-up messages detected with inventory signals.
     * Preserves sender details strictly as Source/Broker (NEVER assumes sender is Property Owner).
     */
    suspend fun parseWhatsAppInventoryMessage(
        rawText: String,
        senderName: String,
        senderPhone: String
    ): ExtractedPropertyDraft = withContext(Dispatchers.IO) {
        val result = extractWhatsAppSafely(
            rawText = rawText,
            senderName = senderName,
            senderPhone = senderPhone
        )
        result.drafts.firstOrNull() ?: ExtractedPropertyDraft(
            sourceBrokerName = senderName.trim(),
            sourceBrokerPhone = SafeWhatsAppExtractor.normalizeIndianPhone(senderPhone),
            contactName = senderName.trim(),
            contactPhone = SafeWhatsAppExtractor.normalizeIndianPhone(senderPhone),
            remarks = rawText.trim().take(240),
            rawSnippet = rawText.trim()
        )
    }

    private fun sanitizeExtractedString(value: String?): String =
        SafeWhatsAppExtractor.sanitizeExtractedString(value)

    private fun normalizeIndianPhone(raw: String?): String =
        SafeWhatsAppExtractor.normalizeIndianPhone(raw)
}



