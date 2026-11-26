package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.model.*
import com.example.ui.shortlist.ShortlistMessageFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class AiPitchTone(val label: String, val promptDirective: String) {
    EXECUTIVE(
        label = "Executive",
        promptDirective = "Polished corporate real-estate advisory tone suitable for CXOs, founders, and enterprise decision-makers. Structured, authoritative, concise, and respectful."
    ),
    CRISP_BULLETS(
        label = "Crisp Bullets",
        promptDirective = "Ultra-scannable WhatsApp bullet-point format with zero fluff, bold headings, clean specs, and a 1-line closing CTA."
    ),
    WARM_FOLLOW_UP(
        label = "Warm Follow-up",
        promptDirective = "Warm, conversational, relationship-first tone that feels personal and helpful while clearly presenting key facts and inviting a quick reply or site visit."
    )
}

enum class CrmReplyScenario(val label: String, val promptFocus: String) {
    POST_CALL_FOLLOW_UP(
        label = "Post-Call Follow-up",
        promptFocus = "Thank the client for speaking earlier, recap their requirement briefly, and suggest the next step."
    ),
    SITE_VISIT_CONFIRMATION(
        label = "Site Visit Confirmation",
        promptFocus = "Confirm the upcoming site visit schedule, express readiness with shortlisted options, and ask to confirm timing."
    ),
    POST_VISIT_FEEDBACK(
        label = "Post-Visit Feedback",
        promptFocus = "Thank them for visiting the property, ask for honest feedback on layout/pricing, and offer to share commercials or alternatives."
    ),
    NEW_OPTIONS_CHECKIN(
        label = "Fresh Options",
        promptFocus = "Share that fresh verified inventory matching their micro-market and budget is available and ask for a quick call or visit slot."
    )
}

data class AiTextDraftResult(
    val text: String,
    val usedGemini: Boolean,
    val statusNote: String? = null
)

data class DailyBriefingSnapshot(
    val employeeName: String,
    val overdueFollowUps: Int,
    val dueTodayFollowUps: Int,
    val hotLeadsCount: Int,
    val hotLeadsWithLiveMatches: Int,
    val todaySiteVisits: Int,
    val unloggedCallsCount: Int,
    val pendingAiInboxCount: Int,
    val pendingAiPropertyCount: Int,
    val pendingAiRequirementCount: Int,
    val availableCommercialCount: Int,
    val availableResidentialCount: Int,
    val topOverdueLeadNames: List<String> = emptyList(),
    val topHotLeadSummaries: List<String> = emptyList()
) {
    fun signature(): String = listOf(
        employeeName.trim().lowercase(Locale.ENGLISH),
        overdueFollowUps,
        dueTodayFollowUps,
        hotLeadsCount,
        hotLeadsWithLiveMatches,
        todaySiteVisits,
        unloggedCallsCount,
        pendingAiInboxCount,
        pendingAiPropertyCount,
        pendingAiRequirementCount
    ).joinToString("|")
}

data class DailyBriefingAction(
    val title: String,
    val detail: String,
    val targetKey: String // "CRM", "AI_INBOX", "VISITS", "CALLS", "INVENTORY"
)

data class DailyAiBriefingResult(
    val dateIso: String,
    val generatedAtMillis: Long,
    val headline: String,
    val priorityBullets: List<DailyBriefingAction>,
    val usedGemini: Boolean,
    val dataSignature: String,
    val statusNote: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("dateIso", dateIso)
        put("generatedAtMillis", generatedAtMillis)
        put("headline", headline)
        put("usedGemini", usedGemini)
        put("dataSignature", dataSignature)
        if (!statusNote.isNullOrBlank()) put("statusNote", statusNote)
        put(
            "priorityBullets",
            JSONArray().apply {
                priorityBullets.forEach { item ->
                    put(
                        JSONObject()
                            .put("title", item.title)
                            .put("detail", item.detail)
                            .put("targetKey", item.targetKey)
                    )
                }
            }
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): DailyAiBriefingResult? {
            val dateIso = obj.optString("dateIso").trim()
            val headline = obj.optString("headline").trim()
            if (dateIso.isBlank() || headline.isBlank()) return null
            val arr = obj.optJSONArray("priorityBullets") ?: JSONArray()
            val bullets = buildList {
                for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    val title = b.optString("title").trim()
                    val detail = b.optString("detail").trim()
                    val targetKey = b.optString("targetKey", "CRM").trim().uppercase(Locale.ENGLISH)
                    if (title.isNotBlank() && detail.isNotBlank()) {
                        add(DailyBriefingAction(title, detail, targetKey))
                    }
                }
            }
            return DailyAiBriefingResult(
                dateIso = dateIso,
                generatedAtMillis = obj.optLong("generatedAtMillis", System.currentTimeMillis()),
                headline = headline,
                priorityBullets = bullets,
                usedGemini = obj.optBoolean("usedGemini", false),
                dataSignature = obj.optString("dataSignature", ""),
                statusNote = obj.optString("statusNote").takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * Unified Gemini AI Productivity Engine for TLC-1:
 * - Brochure / Screenshot Multimodal Vision-to-Property Extraction (zero fabricated defaults)
 * - Tone-switchable Privacy-Safe WhatsApp Property & Shortlist Pitch Composer
 * - Personalized CRM Follow-Up & Site Visit Reply Drafter
 * - Daily AI Priority Briefing Generator with IST on-device daily caching
 *
 * Preserves existing BuildConfig.GEMINI_API_KEY configuration while failing gracefully to
 * deterministic privacy-safe fallbacks when offline or unconfigured.
 */
object GeminiProductivityEngine {
    private const val TAG = "GeminiProductivity"
    private const val BRIEFING_CACHE_FILE = "daily_ai_briefing_cache_v1.json"
    private const val CALL_TIMEOUT_MS = 45_000L

    private val MODELS = listOf("gemini-2.5-flash", "gemini-2.0-flash")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun configuredApiKey(): String {
        val raw = runCatching { BuildConfig.GEMINI_API_KEY }.getOrDefault("").trim()
        return if (raw.isBlank() || raw == "MY_GEMINI_API_KEY") "" else raw
    }

    private fun stripCodeFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private suspend fun executeGeminiRequest(
        parts: JSONArray,
        expectJson: Boolean = false,
        temperature: Double = 0.25,
        maxOutputTokens: Int = 1024
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = configuredApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key not configured"))
        }

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", temperature)
                    put("maxOutputTokens", maxOutputTokens)
                    if (expectJson) put("responseMimeType", "application/json")
                }
            )
        }

        try {
            withTimeout(CALL_TIMEOUT_MS) {
                var lastError = "Gemini request failed"
                for (modelName in MODELS) {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                    val request = Request.Builder()
                        .url(url)
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (response.isSuccessful) {
                            val text = runCatching {
                                JSONObject(body)
                                    .optJSONArray("candidates")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("content")
                                    ?.optJSONArray("parts")
                                    ?.optJSONObject(0)
                                    ?.optString("text")
                                    ?.trim()
                            }.getOrNull()
                            if (!text.isNullOrBlank()) {
                                return@withTimeout Result.success(text)
                            }
                            lastError = "Empty response from $modelName"
                        } else {
                            lastError = "HTTP ${response.code} from $modelName"
                        }
                    }
                }
                Result.failure(IllegalStateException(lastError))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini call timed out after ${CALL_TIMEOUT_MS}ms", e)
            Result.failure(IllegalStateException("AI request timed out. Switched to smart offline fallback."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call failed", e)
            Result.failure(e)
        }
    }

    /**
     * Safely decodes and compresses a user-selected Photo Picker URI into a JPEG under ~1.4MB
     * on Dispatchers.IO so multimodal vision calls stay fast and well within payload limits.
     */
    suspend fun compressUriToJpegBytes(
        context: Context,
        uri: Uri,
        maxEdgePx: Int = 1600,
        maxBytes: Int = 1_400_000
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val origW = bounds.outWidth
            val origH = bounds.outHeight
            if (origW <= 0 || origH <= 0) return@runCatching null

            var sampleSize = 1
            while ((origW / sampleSize) > maxEdgePx * 2 || (origH / sampleSize) > maxEdgePx * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return@runCatching null

            val maxDim = maxOf(decoded.width, decoded.height)
            val scaled: Bitmap = if (maxDim > maxEdgePx) {
                val ratio = maxEdgePx.toFloat() / maxDim.toFloat()
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                decoded
            }

            var quality = 85
            var bytes: ByteArray
            do {
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                bytes = baos.toByteArray()
                quality -= 12
            } while (bytes.size > maxBytes && quality >= 35)

            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
            bytes
        }.getOrNull()
    }

    /**
     * Multimodal Brochure / WhatsApp Screenshot Photo-to-Property AI Scanner.
     * Strictly enforces Build 01 zero-fabrication extraction rules.
     */
    suspend fun extractPropertyFromImageBytes(
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg",
        extraCaption: String = "",
        senderName: String = "",
        senderPhone: String = ""
    ): WhatsAppExtractionBatchResult = withContext(Dispatchers.IO) {
        if (imageBytes.isEmpty()) {
            return@withContext WhatsAppExtractionBatchResult(
                classification = WhatsAppMessageClassification.AMBIGUOUS_NEEDS_REVIEW,
                classificationConfidence = 0,
                classificationReason = "Selected image could not be read.",
                drafts = emptyList(),
                rawMessage = extraCaption,
                usedFallbackParser = true,
                errorMessage = "Could not decode image bytes. Please choose another screenshot or brochure."
            )
        }

        val apiKey = configuredApiKey()
        if (apiKey.isBlank()) {
            return@withContext WhatsAppExtractionBatchResult(
                classification = WhatsAppMessageClassification.AMBIGUOUS_NEEDS_REVIEW,
                classificationConfidence = 0,
                classificationReason = "Vision scan requires GEMINI_API_KEY configured in Secrets.",
                drafts = emptyList(),
                rawMessage = extraCaption,
                usedFallbackParser = true,
                errorMessage = "Configure GEMINI_API_KEY in AI Studio Secrets to scan brochures & screenshots with Gemini Vision."
            )
        }

        val prompt = """
You are a strict Pune real estate data extraction specialist for TLC-1.
Read ALL visible text, rate cards, specs, contact details, and floor/unit tables from this property brochure, flyer, or WhatsApp screenshot${if (extraCaption.isNotBlank()) " (caption: $extraCaption)" else ""}.
Return ONLY raw JSON (no markdown fences) matching this exact schema:

STEP A — Classify the image content into exactly ONE of:
"PROPERTY_LISTING", "CLIENT_REQUIREMENT", "EXISTING_PROPERTY_UPDATE", "UNRELATED", "AMBIGUOUS_NEEDS_REVIEW".

STEP B — Extract an isolated array "properties" (1 object per distinct property/configuration clearly listed in the image).
STRICT ZERO-FABRICATION RULES:
1. NEVER invent missing values (NO default 1000 sqft, ₹85000, ₹1.25 Cr, Kharadi, Mid Floor, Immediate). Unmentioned fields MUST be null or "".
2. NEVER substitute Carpet Area for Built-up Area (BUA) or Saleable Area (SBA). Keep them strictly separate.
3. Preserve Land units ("Guntha", "Acre", "Sq.Ft") in landAreaValue + landAreaUnit.
4. Normalize Indian currency deterministically (85k -> 85000, 1.5L -> 150000, 1.8Cr -> 18000000). Flag unclear figures with confidence < 70 and needsReview=true.
5. Wrap extracted fields as {"value": ..., "evidence": "exact short quote from image", "confidence": 0-100, "needsReview": boolean}.

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
      "rawSnippet": "transcribed summary of visible specs from image",
      "remarks": "",
      "reviewWarnings": []
    }
  ]
}
        """.trimIndent()

        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val parts = JSONArray().apply {
            put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", mimeType)
                        .put("data", base64Image)
                )
            )
            put(JSONObject().put("text", prompt))
        }

        val res = executeGeminiRequest(parts = parts, expectJson = true, temperature = 0.1, maxOutputTokens = 2048)
        res.fold(
            onSuccess = { rawJson ->
                val cleanedJson = stripCodeFences(rawJson)
                val parsed = SafeWhatsAppExtractor.parseGeminiBatchJson(
                    cleanedJson = cleanedJson,
                    rawMessage = extraCaption.ifBlank { "[Scanned Brochure / Screenshot]" },
                    senderName = senderName,
                    senderPhone = senderPhone
                )
                parsed ?: WhatsAppExtractionBatchResult(
                    classification = WhatsAppMessageClassification.AMBIGUOUS_NEEDS_REVIEW,
                    classificationConfidence = 30,
                    classificationReason = "Gemini scanned the image but could not structure clear property specs.",
                    drafts = emptyList(),
                    rawMessage = extraCaption,
                    usedFallbackParser = true,
                    errorMessage = "Could not parse clear property details from this image. Try a sharper screenshot."
                )
            },
            onFailure = { err ->
                WhatsAppExtractionBatchResult(
                    classification = WhatsAppMessageClassification.AMBIGUOUS_NEEDS_REVIEW,
                    classificationConfidence = 0,
                    classificationReason = "Image scan could not reach Gemini.",
                    drafts = emptyList(),
                    rawMessage = extraCaption,
                    usedFallbackParser = true,
                    errorMessage = err.localizedMessage ?: "Unable to scan image right now. Check connection and try again."
                )
            }
        )
    }

    /**
     * Strict Privacy Firewall: builds a client-safe fact sheet using ONLY allowlisted public fields.
     * Owner names, owner numbers, broker names/contacts, source strings, and internal remarks
     * are NEVER included in prompts sent to Gemini.
     */
    fun buildPrivacySafePropertyContext(
        properties: List<PropertyItem>,
        employeeName: String,
        employeeMobile: String
    ): String = buildString {
        properties.distinctBy { it.id }.forEachIndexed { idx, prop ->
            val rates = ShortlistMessageFormatter.calculateAreaRates(prop)
            val priceStr = rates.primaryAmount?.let {
                CalculationUtils.formatIndianCurrency(it, isMonthly = rates.isRentalPrimary)
            } ?: "On Request"
            append("Option ${idx + 1}: ")
            append("Code=${prop.propertyCode.ifBlank { prop.tab.code }}")
            if (prop.projectId.isNotBlank()) append(" | ProjectID=${prop.projectId}")
            append(" | Project=${prop.projectName.ifBlank { prop.propertyType }}")
            if (prop.location.isNotBlank() && !prop.location.equals("Unverified Location", true)) {
                append(" | Location=${prop.location}")
            }
            prop.effectiveMicroMarket?.takeIf { !it.equals(prop.location, true) }?.let {
                append(" | MicroMarket=$it")
            }
            append(" | Type=${prop.propertyType.ifBlank { prop.tab.title }} (${prop.transactionType.ifBlank { prop.tab.title }})")
            if (!prop.bhk.isNullOrBlank()) append(" | Config=${prop.bhk}")
            if (prop.carpetAreaSqFt > 0.0) append(" | Carpet=${CalculationUtils.formatNumber(prop.carpetAreaSqFt)} sq.ft")
            if ((prop.builtUpAreaSqFt ?: 0.0) > 0.0) append(" | BuiltUp=${CalculationUtils.formatNumber(prop.builtUpAreaSqFt!!)} sq.ft")
            if (prop.floor.isNotBlank()) append(" | Floor=${prop.floor}")
            append(" | Furnishing=${prop.furnishing.label}")
            append(" | Commercials=$priceStr")
            val rateUnit = if (rates.isRentalPrimary) "/sq.ft/mo" else "/sq.ft"
            rates.ratePerCarpetSqFt?.let { r ->
                append(" | CarpetRate=${CalculationUtils.formatIndianCurrencyFull(r)}$rateUnit")
            }
            rates.ratePerBuiltUpSqFt?.let { r ->
                append(" | BuiltUpRate=${CalculationUtils.formatIndianCurrencyFull(r)}$rateUnit")
            }
            if ((prop.deposit ?: 0.0) > 0.0 && rates.isRentalPrimary) {
                append(" | Deposit=${CalculationUtils.formatIndianCurrency(prop.deposit!!)}")
            }
            val grossYield = prop.roiPercent?.takeIf { it > 0.0 }
                ?: com.example.data.TlcPropertyCalculator.calculateGrossRoiPercent(
                    prop.rent ?: prop.monthlyRentalIncome,
                    prop.priceSale ?: prop.priceOrRent.takeIf { !rates.isRentalPrimary }
                )
            if (prop.effectiveIsPreLeased && grossYield != null) {
                append(" | GrossROI=${String.format(Locale.US, "%.2f", grossYield)}%")
            }
            if (prop.parking.isNotBlank()) append(" | Parking=${prop.parking}")
            prop.attachedPantry?.takeIf { it.isNotBlank() }?.let { append(" | AttachedPantry=$it") }
            prop.attachedWashroom?.takeIf { it.isNotBlank() }?.let { append(" | AttachedWashroom=$it") }
            prop.effectiveFrontageFt?.takeIf { it > 0.0 }?.let { append(" | Frontage=${CalculationUtils.formatNumber(it)} ft") }
            prop.effectiveClearHeightFt?.takeIf { it > 0.0 }?.let { append(" | ClearHeight=${CalculationUtils.formatNumber(it)} ft") }
            prop.truckAccess?.takeIf { it.isNotBlank() }?.let { append(" | TruckAccess=$it") }
            prop.powerLoad?.takeIf { it.isNotBlank() }?.let { append(" | PowerLoad=$it") }
            if (prop.possession.isNotBlank()) append(" | Possession=${prop.possession}")
            prop.targetPossessionDate?.takeIf { it.isNotBlank() }?.let { append(" | TargetPossession=$it") }
            if ((prop.workstations ?: 0) > 0) append(" | Workstations=${prop.workstations}")
            if ((prop.cabins ?: 0) > 0) append(" | Cabins=${prop.cabins}")
            append("\n")
        }
        val cleanEmp = employeeName.trim().ifBlank { "TLC Real Estate Advisory" }
        val cleanPhone = employeeMobile.trim().ifBlank { ShortlistMessageFormatter.DESK_NUMBERS }
        append("Advisory Contact: $cleanEmp | $cleanPhone | The Leasing Company (Pune)")
    }

    /**
     * Extra post-generation guard ensuring no confidential owner or broker contact ever slips into shared copy.
     */
    fun sanitizeOutgoingPitch(rawText: String, properties: List<PropertyItem>): String {
        var safe = rawText
        properties.forEach { prop ->
            listOfNotNull(prop.ownerContact, prop.brokerContact).forEach { phone ->
                val clean = phone.filter { it.isDigit() }
                if (clean.length >= 10) {
                    safe = safe.replace(phone.trim(), "[Redacted]")
                        .replace(clean.takeLast(10), "[Redacted]")
                }
            }
        }
        return safe.trim()
    }

    /**
     * Generates a tone-switched, client-safe WhatsApp property or multi-property shortlist pitch.
     */
    suspend fun draftPropertySharePitch(
        properties: List<PropertyItem>,
        tone: AiPitchTone,
        audienceLabel: String = "Client",
        employeeName: String = "TLC Executive",
        employeeMobile: String = "",
        clientRequirementHint: String = ""
    ): AiTextDraftResult = withContext(Dispatchers.IO) {
        if (properties.isEmpty()) {
            return@withContext AiTextDraftResult(
                text = "Select at least one property to draft a WhatsApp pitch.",
                usedGemini = false,
                statusNote = "No property selected"
            )
        }

        val factSheet = buildPrivacySafePropertyContext(properties, employeeName, employeeMobile)
        val prompt = """
You are a senior Pune commercial & residential real-estate advisor at The Leasing Company (TLC).
Write a WhatsApp-ready message for an audience of: $audienceLabel.
Tone requirement (${tone.label}): ${tone.promptDirective}
${if (clientRequirementHint.isNotBlank()) "Client context: $clientRequirementHint" else ""}

STRICT RULES:
1. Use ONLY the verified numbers and facts provided below. NEVER invent carpet areas, rents, prices, deposits, or amenities.
2. Format cleanly for WhatsApp (*bold* sparingly, clean line breaks, easy to scan on mobile).
3. Include every option's project, location, area, commercials, and the TLC contact signature at the end.
4. Keep under 1,200 characters for 1-2 options (or concise structured list for 3+ options). Return ONLY the final WhatsApp message text.

Verified Property Facts:
$factSheet
        """.trimIndent()

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        val res = executeGeminiRequest(parts = parts, expectJson = false, temperature = 0.35, maxOutputTokens = 900)

        res.fold(
            onSuccess = { aiText ->
                AiTextDraftResult(
                    text = sanitizeOutgoingPitch(aiText, properties),
                    usedGemini = true,
                    statusNote = "Drafted with Gemini (${tone.label})"
                )
            },
            onFailure = {
                val fallback = buildDeterministicTonePitch(properties, tone, employeeName, employeeMobile)
                AiTextDraftResult(
                    text = sanitizeOutgoingPitch(fallback, properties),
                    usedGemini = false,
                    statusNote = "Smart offline ${tone.label} template (configure GEMINI_API_KEY for live AI polish)"
                )
            }
        )
    }

    fun buildDeterministicTonePitch(
        properties: List<PropertyItem>,
        tone: AiPitchTone,
        employeeName: String,
        employeeMobile: String
    ): String {
        val cleanEmp = employeeName.trim().ifBlank { "TLC Advisory" }
        val cleanMob = employeeMobile.trim().ifBlank { "9766880099" }
        val unique = properties.distinctBy { it.id }

        val opening = when (tone) {
            AiPitchTone.EXECUTIVE -> if (unique.size == 1) {
                "Dear Sir/Ma'am,\n\nPlease find below a curated verified option from *The Leasing Company* aligned with your Pune real estate criteria:"
            } else {
                "Dear Sir/Ma'am,\n\nPlease find below *${unique.size} curated verified options* from *The Leasing Company* for your review:"
            }
            AiPitchTone.CRISP_BULLETS -> "*TLC Verified Options (${unique.size})*"
            AiPitchTone.WARM_FOLLOW_UP -> if (unique.size == 1) {
                "Hi! Hope you're doing well 🙂\nSharing a great verified option we shortlisted for you:"
            } else {
                "Hi! Hope you're doing well 🙂\nI've shortlisted *${unique.size} verified options* that look like a strong match for you:"
            }
        }

        val body = unique.mapIndexed { idx, prop ->
            val rates = ShortlistMessageFormatter.calculateAreaRates(prop)
            val priceText = rates.primaryAmount?.let {
                CalculationUtils.formatIndianCurrency(it, isMonthly = rates.isRentalPrimary)
            } ?: "On Request"
            val title = prop.projectName.ifBlank { prop.propertyType }
            val loc = prop.location.takeIf { it.isNotBlank() && !it.equals("Unverified Location", true) }
            val area = prop.carpetAreaSqFt.takeIf { it > 0.0 }?.let { "${CalculationUtils.formatNumber(it)} sq.ft Carpet" }
            val rateLine = rates.ratePerCarpetSqFt?.let { r ->
                val unit = if (rates.isRentalPrimary) "/sq.ft/mo" else "/sq.ft"
                "${CalculationUtils.formatIndianCurrencyFull(r)}$unit"
            }

            when (tone) {
                AiPitchTone.CRISP_BULLETS -> buildString {
                    append("${idx + 1}. *$title*")
                    if (!loc.isNullOrBlank()) append(" — $loc")
                    append("\n• ${prop.propertyType.ifBlank { prop.tab.title }} | ${prop.furnishing.label}")
                    if (!area.isNullOrBlank()) append(" | $area")
                    append("\n• *$priceText*")
                    if (!rateLine.isNullOrBlank()) append(" ($rateLine)")
                    if (prop.possession.isNotBlank()) append(" | ${prop.possession}")
                }
                AiPitchTone.EXECUTIVE -> buildString {
                    append("${idx + 1}. *$title* (${prop.propertyCode.ifBlank { prop.tab.code }})")
                    if (!loc.isNullOrBlank()) append("\n   Location: $loc")
                    val specs = listOfNotNull(
                        prop.propertyType.takeIf { it.isNotBlank() },
                        area,
                        prop.furnishing.label
                    ).joinToString(" • ")
                    if (specs.isNotBlank()) append("\n   Specifications: $specs")
                    append("\n   Commercials: *$priceText*${rateLine?.let { " ($it)" }.orEmpty()}")
                    if (prop.parking.isNotBlank() || prop.possession.isNotBlank()) {
                        val extras = listOf(prop.possession, prop.parking).filter { it.isNotBlank() }.joinToString(" • ")
                        append("\n   Highlights: $extras")
                    }
                }
                AiPitchTone.WARM_FOLLOW_UP -> buildString {
                    append("${idx + 1}. *$title*${loc?.let { " in $it" }.orEmpty()}")
                    val quickSpec = listOfNotNull(area, prop.furnishing.label, priceText).joinToString(" • ")
                    append("\n   $quickSpec")
                    if (prop.possession.isNotBlank()) append(" • ${prop.possession}")
                }
            }
        }.joinToString("\n\n")

        val closing = when (tone) {
            AiPitchTone.EXECUTIVE -> "We would be pleased to arrange a private inspection at your convenience.\n\nWarm regards,\n*$cleanEmp* | The Leasing Company\n📞 $cleanMob"
            AiPitchTone.CRISP_BULLETS -> "Reply to schedule a site visit.\n*$cleanEmp* • 📞 $cleanMob (TLC)"
            AiPitchTone.WARM_FOLLOW_UP -> "Let me know which option catches your eye — happy to line up a quick site visit whenever suits you!\n\nBest,\n*$cleanEmp* (TLC) • 📞 $cleanMob"
        }

        return "$opening\n\n$body\n\n$closing"
    }

    /**
     * Personalized CRM Follow-Up & Site Visit Reply Drafter.
     */
    suspend fun draftCrmFollowUpMessage(
        leadName: String,
        requirement: String,
        location: String,
        budget: String,
        status: String,
        lastNote: String,
        nextFollowUp: String,
        siteVisitDate: String,
        scenario: CrmReplyScenario,
        tone: AiPitchTone,
        employeeName: String = "TLC Advisory"
    ): AiTextDraftResult = withContext(Dispatchers.IO) {
        val cleanName = leadName.trim().ifBlank { "Sir/Ma'am" }
        val cleanEmp = employeeName.trim().ifBlank { "The Leasing Company" }
        val prompt = """
You are a Pune real-estate relationship manager at The Leasing Company (TLC).
Write a short, high-conversion WhatsApp message for client "$cleanName".
Scenario (${scenario.label}): ${scenario.promptFocus}
Tone (${tone.label}): ${tone.promptDirective}

Client CRM Context:
- Requirement: ${requirement.ifBlank { "Property search in Pune" }}
- Micro-Market: ${location.ifBlank { "Pune" }}
- Budget: ${budget.ifBlank { "As discussed" }}
- Current Stage: ${status.ifBlank { "Active" }}
- Next Follow-up: ${nextFollowUp.ifBlank { "Upcoming" }}
- Site Visit Date: ${siteVisitDate.ifBlank { "To be scheduled" }}
- Latest Note: ${lastNote.take(220).ifBlank { "None" }}
- Sender: $cleanEmp, The Leasing Company

RULES:
1. Keep it natural, WhatsApp-ready, 3–5 concise lines max.
2. Reference only real details above — never invent property names or fake prices.
3. End with a clear easy-to-answer question and sender sign-off.
4. Return ONLY the message text.
        """.trimIndent()

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        val res = executeGeminiRequest(parts = parts, expectJson = false, temperature = 0.4, maxOutputTokens = 320)

        res.fold(
            onSuccess = { aiText ->
                AiTextDraftResult(
                    text = aiText.trim(),
                    usedGemini = true,
                    statusNote = "Drafted with Gemini (${scenario.label} • ${tone.label})"
                )
            },
            onFailure = {
                val fallback = buildDeterministicCrmReply(
                    cleanName = cleanName,
                    requirement = requirement.ifBlank { "property requirement" },
                    location = location.ifBlank { "Pune" },
                    siteVisitDate = siteVisitDate,
                    scenario = scenario,
                    tone = tone,
                    employeeName = cleanEmp
                )
                AiTextDraftResult(
                    text = fallback,
                    usedGemini = false,
                    statusNote = "Smart offline draft (${tone.label})"
                )
            }
        )
    }

    fun buildDeterministicCrmReply(
        cleanName: String,
        requirement: String,
        location: String,
        siteVisitDate: String,
        scenario: CrmReplyScenario,
        tone: AiPitchTone,
        employeeName: String
    ): String {
        val greeting = when (tone) {
            AiPitchTone.EXECUTIVE -> "Dear $cleanName,"
            AiPitchTone.CRISP_BULLETS -> "Hi $cleanName — quick update:"
            AiPitchTone.WARM_FOLLOW_UP -> "Hi $cleanName! Hope your week is going well 🙂"
        }
        val visitHint = siteVisitDate.takeIf { it.isNotBlank() }?.let { " on $it" }.orEmpty()
        val core = when (scenario) {
            CrmReplyScenario.POST_CALL_FOLLOW_UP -> when (tone) {
                AiPitchTone.CRISP_BULLETS -> "• Requirement: $requirement ($location)\n• Next Step: Shortlisting verified options\n• Reply with your preferred time for a quick review."
                AiPitchTone.EXECUTIVE -> "Thank you for speaking with us regarding your $requirement in $location. We are curating verified options aligned to your specifications and will share the shortlist shortly. Please let us know a convenient time to review."
                AiPitchTone.WARM_FOLLOW_UP -> "Great speaking with you! I'm putting together the best verified options for your $requirement in $location. Would tomorrow work for a quick look?"
            }
            CrmReplyScenario.SITE_VISIT_CONFIRMATION -> when (tone) {
                AiPitchTone.CRISP_BULLETS -> "• Site Visit Confirmation$visitHint\n• Focus: $requirement in $location\n• Please confirm if the timing works for you."
                AiPitchTone.EXECUTIVE -> "We are writing to confirm your scheduled site visit$visitHint for shortlisted $requirement options in $location. Kindly confirm your availability so we can coordinate access smoothly."
                AiPitchTone.WARM_FOLLOW_UP -> "Just checking in to confirm our site visit$visitHint for $location! All shortlisted spaces are lined up — let me know if the timing still works great for you."
            }
            CrmReplyScenario.POST_VISIT_FEEDBACK -> when (tone) {
                AiPitchTone.CRISP_BULLETS -> "• Thanks for visiting $location options today\n• Please share your top preference or feedback\n• Happy to negotiate commercials or line up alternatives."
                AiPitchTone.EXECUTIVE -> "Thank you for taking time to inspect the options in $location. We would value your feedback on layout and commercials so we can either progress discussions or curate sharper alternatives."
                AiPitchTone.WARM_FOLLOW_UP -> "Thanks for joining the site visit in $location! Which option felt closest to what you have in mind? Happy to share commercials or explore a couple more choices."
            }
            CrmReplyScenario.NEW_OPTIONS_CHECKIN -> when (tone) {
                AiPitchTone.CRISP_BULLETS -> "• Fresh verified inventory added in $location\n• Matches: $requirement\n• Reply YES and I'll share the top 3 options now."
                AiPitchTone.EXECUTIVE -> "We have newly verified $requirement availability in $location matching your brief. Would you like us to share the updated shortlist and arrange a viewing this week?"
                AiPitchTone.WARM_FOLLOW_UP -> "Quick heads up — a couple of fresh, verified options just opened up in $location for your $requirement! Want me to send the details across?"
            }
        }
        val signOff = when (tone) {
            AiPitchTone.EXECUTIVE -> "\n\nWarm regards,\n$employeeName | The Leasing Company"
            AiPitchTone.CRISP_BULLETS -> "\n\n— $employeeName (TLC)"
            AiPitchTone.WARM_FOLLOW_UP -> "\n\nBest,\n$employeeName • TLC"
        }
        return "$greeting\n$core$signOff"
    }

    /**
     * Reads cached Daily AI Briefing for today (Asia/Kolkata) if present.
     */
    fun loadCachedDailyBriefing(context: Context): DailyAiBriefingResult? {
        val todayIso = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
        val file = File(context.filesDir, BRIEFING_CACHE_FILE)
        if (!file.exists()) return null
        return runCatching {
            val raw = String(AtomicFile(file).readFully(), StandardCharsets.UTF_8)
            val parsed = DailyAiBriefingResult.fromJson(JSONObject(raw))
            parsed?.takeIf { it.dateIso == todayIso }
        }.getOrNull()
    }

    private fun saveCachedDailyBriefing(context: Context, briefing: DailyAiBriefingResult) {
        runCatching {
            val file = File(context.filesDir, BRIEFING_CACHE_FILE)
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(briefing.toJson().toString().toByteArray(StandardCharsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (_: Exception) {
                atomic.failWrite(stream)
            }
        }
    }

    /**
     * Generates a prioritized Daily AI Executive Briefing for the Dashboard and caches it for today (IST).
     */
    suspend fun generateDailyPriorityBriefing(
        context: Context,
        snapshot: DailyBriefingSnapshot,
        forceRefresh: Boolean = false
    ): DailyAiBriefingResult = withContext(Dispatchers.IO) {
        val todayIso = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
        val sig = snapshot.signature()
        if (!forceRefresh) {
            val cached = loadCachedDailyBriefing(context)
            if (cached != null && cached.dataSignature == sig) {
                return@withContext cached
            }
        }

        val prompt = """
You are an executive real-estate operations chief of staff for TLC-1 (Pune Commercial & Residential Brokerage).
Analyze today's live workload for ${snapshot.employeeName.ifBlank { "TLC Team" }} on $todayIso and return ONLY raw JSON (no markdown fences):
{
  "headline": "1 crisp executive summary sentence (max 110 chars)",
  "priorityBullets": [
    {
      "title": "Short 3-5 word action heading",
      "detail": "Specific 1-line action citing the exact counts/names below",
      "targetKey": "CRM|AI_INBOX|VISITS|CALLS|INVENTORY"
    }
  ]
}
Provide 3 to 4 highest-impact items ordered by deal urgency. NEVER invent numbers or names not present below.

Live Workload Snapshot:
- Overdue CRM follow-ups: ${snapshot.overdueFollowUps} ${if (snapshot.topOverdueLeadNames.isNotEmpty()) "(Top: ${snapshot.topOverdueLeadNames.joinToString(", ")})" else ""}
- Follow-ups due today: ${snapshot.dueTodayFollowUps}
- Hot/Active leads: ${snapshot.hotLeadsCount} (${snapshot.hotLeadsWithLiveMatches} have matching live inventory)
${if (snapshot.topHotLeadSummaries.isNotEmpty()) "- Notable hot leads: ${snapshot.topHotLeadSummaries.joinToString("; ")}" else ""}
- Site visits scheduled today: ${snapshot.todaySiteVisits}
- Unlogged calls waiting for review: ${snapshot.unloggedCallsCount}
- WhatsApp AI Inbox pending approvals: ${snapshot.pendingAiInboxCount} (${snapshot.pendingAiPropertyCount} listings, ${snapshot.pendingAiRequirementCount} client requirements)
- Live available inventory: ${snapshot.availableCommercialCount} Commercial, ${snapshot.availableResidentialCount} Residential
        """.trimIndent()

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        val res = executeGeminiRequest(parts = parts, expectJson = true, temperature = 0.2, maxOutputTokens = 650)

        val result = res.fold(
            onSuccess = { raw ->
                parseDailyBriefingJson(raw, todayIso, sig)
                    ?: buildDeterministicDailyBriefing(snapshot, todayIso, sig, "Smart offline priority briefing")
            },
            onFailure = {
                buildDeterministicDailyBriefing(
                    snapshot = snapshot,
                    todayIso = todayIso,
                    signature = sig,
                    note = "Local priority briefing (configure GEMINI_API_KEY for live Gemini synthesis)"
                )
            }
        )
        saveCachedDailyBriefing(context, result)
        result
    }

    private fun parseDailyBriefingJson(
        rawJson: String,
        todayIso: String,
        signature: String
    ): DailyAiBriefingResult? = runCatching {
        val obj = JSONObject(stripCodeFences(rawJson))
        val headline = obj.optString("headline").trim()
        if (headline.isBlank()) return null
        val arr = obj.optJSONArray("priorityBullets") ?: return null
        val allowedTargets = setOf("CRM", "AI_INBOX", "VISITS", "CALLS", "INVENTORY")
        val bullets = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val detail = item.optString("detail").trim()
                val rawTarget = item.optString("targetKey", "CRM").trim().uppercase(Locale.ENGLISH)
                val target = if (rawTarget in allowedTargets) rawTarget else "CRM"
                if (title.isNotBlank() && detail.isNotBlank()) {
                    add(DailyBriefingAction(title, detail, target))
                }
            }
        }.take(4)
        if (bullets.isEmpty()) return null
        DailyAiBriefingResult(
            dateIso = todayIso,
            generatedAtMillis = System.currentTimeMillis(),
            headline = headline,
            priorityBullets = bullets,
            usedGemini = true,
            dataSignature = signature,
            statusNote = "Synthesized by Gemini AI"
        )
    }.getOrNull()

    fun buildDeterministicDailyBriefing(
        snapshot: DailyBriefingSnapshot,
        todayIso: String = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString(),
        signature: String = snapshot.signature(),
        note: String? = null
    ): DailyAiBriefingResult {
        val actions = mutableListOf<DailyBriefingAction>()
        if (snapshot.overdueFollowUps > 0 || snapshot.dueTodayFollowUps > 0) {
            val leadHint = snapshot.topOverdueLeadNames.take(2).joinToString(", ")
            actions.add(
                DailyBriefingAction(
                    title = "Clear Urgent Follow-Ups",
                    detail = "${snapshot.overdueFollowUps} overdue & ${snapshot.dueTodayFollowUps} due today" +
                        if (leadHint.isNotBlank()) " — start with $leadHint." else ".",
                    targetKey = "CRM"
                )
            )
        }
        if (snapshot.pendingAiInboxCount > 0) {
            actions.add(
                DailyBriefingAction(
                    title = "Review WhatsApp AI Inbox",
                    detail = "${snapshot.pendingAiInboxCount} real-estate messages ready for 1-tap approval (${snapshot.pendingAiPropertyCount} listings, ${snapshot.pendingAiRequirementCount} requirements).",
                    targetKey = "AI_INBOX"
                )
            )
        }
        if (snapshot.todaySiteVisits > 0) {
            actions.add(
                DailyBriefingAction(
                    title = "Confirm Today's Site Visits",
                    detail = "${snapshot.todaySiteVisits} site visit${if (snapshot.todaySiteVisits == 1) "" else "s"} scheduled today — send 1-tap confirmation replies.",
                    targetKey = "VISITS"
                )
            )
        }
        if (snapshot.unloggedCallsCount > 0) {
            actions.add(
                DailyBriefingAction(
                    title = "Log Pending Calls",
                    detail = "${snapshot.unloggedCallsCount} unlogged calls waiting in your call queue with matched recordings.",
                    targetKey = "CALLS"
                )
            )
        }
        if (actions.size < 3 && snapshot.hotLeadsWithLiveMatches > 0) {
            actions.add(
                DailyBriefingAction(
                    title = "Share Live Inventory Matches",
                    detail = "${snapshot.hotLeadsWithLiveMatches} active leads already match available Pune units — send shortlists now.",
                    targetKey = "CRM"
                )
            )
        }
        if (actions.isEmpty()) {
            actions.add(
                DailyBriefingAction(
                    title = "Pipeline Clear & Ready",
                    detail = "${snapshot.availableCommercialCount} Commercial & ${snapshot.availableResidentialCount} Residential units live — explore inventory or add fresh stock.",
                    targetKey = "INVENTORY"
                )
            )
        }

        val urgentCount = snapshot.overdueFollowUps + snapshot.pendingAiInboxCount + snapshot.todaySiteVisits
        val headline = if (urgentCount > 0) {
            "$urgentCount priority items need attention today across CRM, AI Inbox & Site Visits."
        } else {
            "All urgent loops are clear — focus on proactive pitches from ${snapshot.availableCommercialCount + snapshot.availableResidentialCount} live units."
        }

        return DailyAiBriefingResult(
            dateIso = todayIso,
            generatedAtMillis = System.currentTimeMillis(),
            headline = headline,
            priorityBullets = actions.take(4),
            usedGemini = false,
            dataSignature = signature,
            statusNote = note
        )
    }
}
