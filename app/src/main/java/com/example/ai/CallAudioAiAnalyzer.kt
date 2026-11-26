package com.example.ai

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.crm.CallType
import com.example.crm.CrmCallRecord
import com.example.crm.MatchedCallRecording
import com.example.data.master.CallAnalysisEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class AudioPreFilterSkipReason(val label: String) {
    NONE("Eligible"),
    TOO_SHORT("Under 10 seconds"),
    MISSED_OR_REJECTED("Missed / Rejected call"),
    SPAM_IVR_NUMBER("Spam / Telemarketer / IVR"),
    IGNORED_OR_PERSONAL("Ignored / Personal number"),
    ALREADY_ANALYZED("Already analyzed")
}

data class ExtractedClientLeadData(
    val division: String = "", // Commercial | Residential
    val transactionType: String = "", // Lease | Buy
    val propertyType: String = "",
    val configuration: String = "",
    val location: String = "",
    val microMarket: String = "",
    val carpetAreaSqFt: String = "",
    val budgetMin: String = "",
    val budgetMax: String = "",
    val furnishing: String = "",
    val possession: String = ""
) {
    fun hasAnyField(): Boolean = listOf(
        division, transactionType, propertyType, configuration, location,
        microMarket, carpetAreaSqFt, budgetMin, budgetMax, furnishing, possession
    ).any { it.isNotBlank() }

    fun toJsonString(): String = JSONObject().apply {
        put("division", division)
        put("transactionType", transactionType)
        put("propertyType", propertyType)
        put("configuration", configuration)
        put("location", location)
        put("microMarket", microMarket)
        put("carpetAreaSqFt", carpetAreaSqFt)
        put("budgetMin", budgetMin)
        put("budgetMax", budgetMax)
        put("furnishing", furnishing)
        put("possession", possession)
    }.toString()

    companion object {
        fun fromJsonString(raw: String): ExtractedClientLeadData? {
            if (raw.isBlank()) return null
            return runCatching {
                val obj = JSONObject(raw)
                ExtractedClientLeadData(
                    division = CallAudioAiAnalyzer.cleanScalar(obj.opt("division")),
                    transactionType = CallAudioAiAnalyzer.cleanScalar(obj.opt("transactionType")),
                    propertyType = CallAudioAiAnalyzer.cleanScalar(obj.opt("propertyType")),
                    configuration = CallAudioAiAnalyzer.cleanScalar(obj.opt("configuration")),
                    location = CallAudioAiAnalyzer.cleanScalar(obj.opt("location")),
                    microMarket = CallAudioAiAnalyzer.cleanScalar(obj.opt("microMarket")),
                    carpetAreaSqFt = CallAudioAiAnalyzer.cleanScalar(obj.opt("carpetAreaSqFt")),
                    budgetMin = CallAudioAiAnalyzer.cleanScalar(obj.opt("budgetMin")),
                    budgetMax = CallAudioAiAnalyzer.cleanScalar(obj.opt("budgetMax")),
                    furnishing = CallAudioAiAnalyzer.cleanScalar(obj.opt("furnishing")),
                    possession = CallAudioAiAnalyzer.cleanScalar(obj.opt("possession"))
                ).takeIf { it.hasAnyField() }
            }.getOrNull()
        }
    }
}

data class ExtractedPropertyUpdateData(
    val actionType: String = "", // NEW_PROPERTY | UPDATE_EXISTING | BUILDER_SLAB_UPDATE
    val projectName: String = "",
    val unitNumber: String = "",
    val location: String = "",
    val microMarket: String = "",
    val assetType: String = "",
    val carpetAreaSqFt: String = "",
    val builtUpAreaSqFt: String = "",
    val rentOrPrice: String = "",
    val attachedPantry: String = "",
    val attachedWashroom: String = "",
    val statusChange: String = "", // Available | Under Negotiation | Leased / Sold Out
    val builderOfferOrBrokerageNote: String = ""
) {
    fun hasAnyField(): Boolean = listOf(
        actionType, projectName, unitNumber, location, microMarket, assetType,
        carpetAreaSqFt, builtUpAreaSqFt, rentOrPrice, attachedPantry,
        attachedWashroom, statusChange, builderOfferOrBrokerageNote
    ).any { it.isNotBlank() }

    fun toJsonString(): String = JSONObject().apply {
        put("actionType", actionType)
        put("projectName", projectName)
        put("unitNumber", unitNumber)
        put("location", location)
        put("microMarket", microMarket)
        put("assetType", assetType)
        put("carpetAreaSqFt", carpetAreaSqFt)
        put("builtUpAreaSqFt", builtUpAreaSqFt)
        put("rentOrPrice", rentOrPrice)
        put("attachedPantry", attachedPantry)
        put("attachedWashroom", attachedWashroom)
        put("statusChange", statusChange)
        put("builderOfferOrBrokerageNote", builderOfferOrBrokerageNote)
    }.toString()

    companion object {
        fun fromJsonString(raw: String): ExtractedPropertyUpdateData? {
            if (raw.isBlank()) return null
            return runCatching {
                val obj = JSONObject(raw)
                ExtractedPropertyUpdateData(
                    actionType = CallAudioAiAnalyzer.cleanScalar(obj.opt("actionType")),
                    projectName = CallAudioAiAnalyzer.cleanScalar(obj.opt("projectName")),
                    unitNumber = CallAudioAiAnalyzer.cleanScalar(obj.opt("unitNumber")),
                    location = CallAudioAiAnalyzer.cleanScalar(obj.opt("location")),
                    microMarket = CallAudioAiAnalyzer.cleanScalar(obj.opt("microMarket")),
                    assetType = CallAudioAiAnalyzer.cleanScalar(obj.opt("assetType")),
                    carpetAreaSqFt = CallAudioAiAnalyzer.cleanScalar(obj.opt("carpetAreaSqFt")),
                    builtUpAreaSqFt = CallAudioAiAnalyzer.cleanScalar(obj.opt("builtUpAreaSqFt")),
                    rentOrPrice = CallAudioAiAnalyzer.cleanScalar(obj.opt("rentOrPrice")),
                    attachedPantry = CallAudioAiAnalyzer.cleanScalar(obj.opt("attachedPantry")),
                    attachedWashroom = CallAudioAiAnalyzer.cleanScalar(obj.opt("attachedWashroom")),
                    statusChange = CallAudioAiAnalyzer.cleanScalar(obj.opt("statusChange")),
                    builderOfferOrBrokerageNote = CallAudioAiAnalyzer.cleanScalar(obj.opt("builderOfferOrBrokerageNote"))
                ).takeIf { it.hasAnyField() }
            }.getOrNull()
        }
    }
}

data class ExtractedTenantNoteData(
    val tenantAction: String = "", // Renewal | Maintenance | Notice | Rent
    val notes: String = ""
) {
    fun hasAnyField(): Boolean = tenantAction.isNotBlank() || notes.isNotBlank()

    fun toJsonString(): String = JSONObject().apply {
        put("tenantAction", tenantAction)
        put("notes", notes)
    }.toString()

    companion object {
        fun fromJsonString(raw: String): ExtractedTenantNoteData? {
            if (raw.isBlank()) return null
            return runCatching {
                val obj = JSONObject(raw)
                ExtractedTenantNoteData(
                    tenantAction = CallAudioAiAnalyzer.cleanScalar(obj.opt("tenantAction")),
                    notes = CallAudioAiAnalyzer.cleanScalar(obj.opt("notes"))
                ).takeIf { it.hasAnyField() }
            }.getOrNull()
        }
    }
}

data class ExtractedFollowUpData(
    val date: String = "", // dd/MM/yyyy
    val time: String = "", // hh:mm a
    val nextAction: String = "", // Call Back | Send WA Shortlist | Schedule Site Visit | Verify Unit
    val suggestedWhatsAppDraft: String = ""
) {
    fun hasFollowUpSchedule(): Boolean = date.isNotBlank() || nextAction.isNotBlank()
}

data class CallAudioAnalysisResult(
    val callerRole: String, // CLIENT_LEAD | OWNER | BROKER_CP | DEVELOPER_BUILDER | TENANT | VENDOR | PERSONAL_NON_BUSINESS
    val detectedLanguage: String, // Marathi | Hindi | English | Mixed
    val summaryBullets: List<String>,
    val extractedClientLead: ExtractedClientLeadData? = null,
    val extractedPropertyUpdate: ExtractedPropertyUpdateData? = null,
    val extractedTenantNote: ExtractedTenantNoteData? = null,
    val followUp: ExtractedFollowUpData = ExtractedFollowUpData(),
    val confidence: Int = 0,
    val needsReview: Boolean = false,
    val modelUsed: String = CallAudioAiAnalyzer.MODEL_FLASH_LITE,
    val rawTranscript: String = ""
) {
    fun formattedBulletsSummary(): String =
        summaryBullets.filter { it.isNotBlank() }.joinToString("\n") { "• ${it.removePrefix("•").removePrefix("-").trim()}" }

    fun buildSpecPills(): List<String> = buildList {
        extractedClientLead?.let { lead ->
            if (lead.microMarket.isNotBlank()) add(lead.microMarket)
            else if (lead.location.isNotBlank()) add(lead.location)
            if (lead.configuration.isNotBlank()) add(lead.configuration)
            else if (lead.propertyType.isNotBlank()) add(lead.propertyType)
            if (lead.carpetAreaSqFt.isNotBlank()) add("${lead.carpetAreaSqFt} sqft")
            val budgetLabel = listOf(lead.budgetMin, lead.budgetMax).filter { it.isNotBlank() }.joinToString("–")
            if (budgetLabel.isNotBlank()) add(budgetLabel)
            if (lead.transactionType.isNotBlank()) add(lead.transactionType)
        }
        extractedPropertyUpdate?.let { prop ->
            if (prop.projectName.isNotBlank()) add(prop.projectName)
            if (prop.microMarket.isNotBlank()) add(prop.microMarket)
            else if (prop.location.isNotBlank()) add(prop.location)
            if (prop.assetType.isNotBlank()) add(prop.assetType)
            if (prop.carpetAreaSqFt.isNotBlank()) add("${prop.carpetAreaSqFt} sqft")
            if (prop.rentOrPrice.isNotBlank()) add(prop.rentOrPrice)
            if (prop.statusChange.isNotBlank()) add(prop.statusChange)
        }
        extractedTenantNote?.let { tenant ->
            if (tenant.tenantAction.isNotBlank()) add("Tenant: ${tenant.tenantAction}")
        }
        if (followUp.date.isNotBlank()) {
            val fLabel = listOf(followUp.date, followUp.time).filter { it.isNotBlank() }.joinToString(" ")
            add("Follow-up: $fLabel")
        }
    }.distinctBy { it.lowercase(Locale.ENGLISH) }.take(6)

    fun toEntity(
        call: CrmCallRecord,
        deviceName: String,
        recordingFilename: String = call.recordingFilename.orEmpty(),
        reviewStatus: String = "PENDING_REVIEW",
        analyzedAt: Long = System.currentTimeMillis()
    ): CallAnalysisEntity {
        val bulletsArr = JSONArray().apply { summaryBullets.forEach { put(it) } }
        return CallAnalysisEntity(
            callLogId = call.id,
            callerName = call.callerName,
            phoneNumber = call.phoneNumber,
            callTimestamp = call.timestamp,
            durationSeconds = call.durationSeconds,
            callerRole = callerRole,
            detectedLanguage = detectedLanguage,
            summaryBulletsJson = bulletsArr.toString(),
            extractedClientLeadJson = extractedClientLead?.toJsonString().orEmpty(),
            extractedPropertyUpdateJson = extractedPropertyUpdate?.toJsonString().orEmpty(),
            extractedTenantNoteJson = extractedTenantNote?.toJsonString().orEmpty(),
            followUpDate = followUp.date,
            followUpTime = followUp.time,
            followUpNextAction = followUp.nextAction,
            suggestedWhatsAppDraft = followUp.suggestedWhatsAppDraft,
            confidence = confidence.coerceIn(0, 100),
            needsReview = needsReview,
            modelUsed = modelUsed,
            reviewStatus = reviewStatus,
            aiAnalyzedAt = analyzedAt,
            deviceName = deviceName,
            recordingFilename = recordingFilename,
            rawTranscript = rawTranscript,
            syncedToSheet = false
        )
    }
}

fun CallAnalysisEntity.toAnalysisResult(): CallAudioAnalysisResult {
    val bullets = runCatching {
        val arr = JSONArray(summaryBulletsJson)
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim().takeIf { it.isNotBlank() } }
    }.getOrDefault(emptyList())
    return CallAudioAnalysisResult(
        callerRole = callerRole,
        detectedLanguage = detectedLanguage,
        summaryBullets = bullets,
        extractedClientLead = ExtractedClientLeadData.fromJsonString(extractedClientLeadJson),
        extractedPropertyUpdate = ExtractedPropertyUpdateData.fromJsonString(extractedPropertyUpdateJson),
        extractedTenantNote = ExtractedTenantNoteData.fromJsonString(extractedTenantNoteJson),
        followUp = ExtractedFollowUpData(
            date = followUpDate,
            time = followUpTime,
            nextAction = followUpNextAction,
            suggestedWhatsAppDraft = suggestedWhatsAppDraft
        ),
        confidence = confidence,
        needsReview = needsReview,
        modelUsed = modelUsed,
        rawTranscript = rawTranscript
    )
}

/**
 * Pure-Kotlin Audio Pre-Filter Gate — skips noise before spending any Gemini tokens:
 * - Skips duration < 10s
 * - Skips Missed / Rejected / 0-second calls
 * - Skips Spam / Telemarketer / IVR (140 / 160 / 1800)
 * - Skips Ignored / Personal numbers
 * - Skips already analyzed calls (aiAnalyzedAt != null)
 */
object CallAudioPreFilter {
    const val MIN_AUDIO_DURATION_SECONDS = 10

    private val SPAM_NAME_TOKENS = listOf(
        "spam", "telemarketer", "ivr", "customer care", "robocall",
        "scam", "fraud", "loan offer", "credit card", "suspected spam"
    )

    fun evaluateEligibility(
        durationSeconds: Int,
        callType: CallType,
        phoneNumber: String,
        callerName: String = "",
        leadStatus: String = "",
        isIgnoredOrPersonal: Boolean = false,
        aiAnalyzedAt: Long? = null
    ): AudioPreFilterSkipReason {
        if (aiAnalyzedAt != null && aiAnalyzedAt > 0L) {
            return AudioPreFilterSkipReason.ALREADY_ANALYZED
        }
        if (isIgnoredOrPersonal) {
            return AudioPreFilterSkipReason.IGNORED_OR_PERSONAL
        }
        if (callType == CallType.MISSED || leadStatus.equals("Rejected", ignoreCase = true)) {
            return AudioPreFilterSkipReason.MISSED_OR_REJECTED
        }
        if (isSpamOrIvrNumber(phoneNumber, callerName, leadStatus)) {
            return AudioPreFilterSkipReason.SPAM_IVR_NUMBER
        }
        if (durationSeconds < MIN_AUDIO_DURATION_SECONDS) {
            return AudioPreFilterSkipReason.TOO_SHORT
        }
        return AudioPreFilterSkipReason.NONE
    }

    fun isSpamOrIvrNumber(
        phoneNumber: String,
        callerName: String = "",
        leadStatus: String = ""
    ): Boolean {
        if (leadStatus.equals("Spam", ignoreCase = true)) return true
        val lowerName = callerName.trim().lowercase(Locale.ENGLISH)
        if (lowerName.isNotBlank() && SPAM_NAME_TOKENS.any { lowerName.contains(it) }) {
            return true
        }
        val digits = phoneNumber.filter { it.isDigit() }
        if (digits.isBlank()) return false
        val stripped = when {
            digits.length > 10 && digits.startsWith("91") -> digits.removePrefix("91")
            digits.length > 10 && digits.startsWith("0") -> digits.removePrefix("0")
            else -> digits
        }
        // Indian TRAI telemarketer / transactional / toll-free IVR prefixes (140, 160, 1800) + short codes
        return stripped.startsWith("140") ||
            stripped.startsWith("160") ||
            stripped.startsWith("1800") ||
            digits.startsWith("140") ||
            digits.startsWith("160") ||
            digits.startsWith("1800") ||
            digits.length in 3..6
    }
}

/**
 * Batch 44 Gemini Native Audio Transcription & Multilingual Pune Real Estate Extractor.
 * - Call recordings: sends matched .m4a / .mp3 / .aac audio inline to Gemini
 *   (`gemini-2.5-flash-lite` with automatic escalation to `gemini-2.5-flash` for calls >3 mins
 *   or complex Marathi/Hindi conversations).
 * - Microphone voice dictation: transcribes live recorded microphone audio via `gemini-3.5-transcribe`.
 */
object CallAudioAiAnalyzer {
    private const val TAG = "CallAudioAiAnalyzer"

    const val MODEL_FLASH_LITE = "gemini-2.5-flash-lite"
    const val MODEL_FLASH = "gemini-2.5-flash"
    const val MODEL_TRANSCRIBE = "gemini-3.5-transcribe"
    const val ESCALATION_DURATION_SECONDS = 180 // > 3 minutes escalates to gemini-2.5-flash
    private const val MAX_INLINE_AUDIO_BYTES = 18 * 1024 * 1024 // < 20 MB inline limit

    private val PLACEHOLDER_VALUES = setOf(
        "null", "none", "n/a", "na", "nil", "-", "--", "unknown",
        "not mentioned", "not stated", "not specified", "unspecified", "tbd"
    )

    private val VALID_ROLES = setOf(
        "CLIENT_LEAD", "OWNER", "BROKER_CP", "DEVELOPER_BUILDER",
        "TENANT", "VENDOR", "PERSONAL_NON_BUSINESS"
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun resolveApiKey(): String {
        val raw = runCatching { BuildConfig.GEMINI_API_KEY }.getOrDefault("").trim()
        return if (raw.isBlank() || raw == "MY_GEMINI_API_KEY") "" else raw
    }

    /**
     * Selects `gemini-2.5-flash-lite` for fast short calls and automatically escalates to
     * `gemini-2.5-flash` for calls > 3 minutes (`> 180s`) or complex Marathi/Hindi hints.
     */
    fun selectExtractionModel(
        durationSeconds: Int,
        languageOrContextHint: String = "",
        forceFlashEscalation: Boolean = false
    ): String {
        if (forceFlashEscalation || durationSeconds > ESCALATION_DURATION_SECONDS) {
            return MODEL_FLASH
        }
        val hintLower = languageOrContextHint.lowercase(Locale.ENGLISH)
        val hasComplexMultilingualHint = hintLower.contains("marathi") ||
            hintLower.contains("hindi") ||
            hintLower.contains("mixed") ||
            hintLower.contains("madhe") ||
            hintLower.contains("guntha") ||
            hintLower.any { ch -> ch in '\u0900'..'\u097F' } // Devanagari script
        return if (hasComplexMultilingualHint) MODEL_FLASH else MODEL_FLASH_LITE
    }

    /**
     * Transcribes live microphone audio using `gemini-3.5-transcribe`.
     */
    suspend fun transcribeMicrophoneAudio(
        audioFile: File,
        mimeType: String = "audio/mp4"
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("GEMINI_API_KEY is not configured in AI Studio Secrets.")
            )
        }
        if (!audioFile.exists() || audioFile.length() <= 0L) {
            return@withContext Result.failure(IllegalArgumentException("Recorded audio file is empty."))
        }
        if (audioFile.length() > MAX_INLINE_AUDIO_BYTES) {
            return@withContext Result.failure(IllegalArgumentException("Audio clip exceeds 18 MB limit."))
        }

        val audioBytes = runCatching { audioFile.readBytes() }.getOrElse {
            return@withContext Result.failure(it)
        }
        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val prompt = """
            Transcribe this Pune real estate voice note accurately.
            Language priority: Marathi (1st), Hindi (2nd), English (3rd), including Hinglish and Marathi-English code-switching
            (terms like Kharadi madhe, carpet, deposit, lock-in, guntha, BHK, warm shell, bare shell, pre-leased).
            Output clean, readable English (or English with brief Marathi/Hindi phrases only if essential for meaning).
            Return ONLY the plain transcription text without markdown fences or invented numbers.
        """.trimIndent()

        // Primary required transcription model: gemini-3.5-transcribe (with graceful fallback if endpoint rejects preview tag)
        val modelsToTry = listOf(MODEL_TRANSCRIBE, MODEL_FLASH)
        var lastError: Throwable? = null
        for (modelName in modelsToTry) {
            val res = callGeminiRest(
                apiKey = apiKey,
                modelName = modelName,
                prompt = prompt,
                audioBase64 = base64Audio,
                audioMimeType = mimeType,
                expectJson = false
            )
            if (res.isSuccess) {
                val cleaned = res.getOrThrow().trim()
                if (cleaned.isNotBlank()) return@withContext Result.success(cleaned)
            } else {
                lastError = res.exceptionOrNull()
                Log.w(TAG, "Transcription attempt on $modelName failed: ${lastError?.message}")
            }
        }
        Result.failure(lastError ?: IllegalStateException("Audio transcription failed."))
    }

    /**
     * Analyzes a matched call recording (.m4a / .mp3 / .aac) directly via Gemini native multimodal audio.
     */
    suspend fun analyzeAudioRecording(
        context: Context,
        call: CrmCallRecord,
        recording: MatchedCallRecording
    ): Result<CallAudioAnalysisResult> = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Configure GEMINI_API_KEY in AI Studio Secrets to analyze call recordings.")
            )
        }

        val audioBytes = readRecordingBytes(context, recording)
            ?: return@withContext Result.failure(
                IllegalStateException("Could not read recording file ${recording.fileName}.")
            )
        if (audioBytes.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Recording file is empty."))
        }
        if (audioBytes.size > MAX_INLINE_AUDIO_BYTES) {
            return@withContext Result.failure(IllegalStateException("Recording exceeds 18 MB inline audio limit."))
        }

        val resolvedMime = normalizeAudioMimeType(recording.mimeType, recording.fileName)
        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val prompt = buildMultilingualExtractionPrompt(call = call, transcriptOverride = null)

        val initialModel = selectExtractionModel(
            durationSeconds = call.durationSeconds,
            languageOrContextHint = "${call.callerName} ${call.notes}"
        )

        val firstRes = callGeminiRest(
            apiKey = apiKey,
            modelName = initialModel,
            prompt = prompt,
            audioBase64 = base64Audio,
            audioMimeType = resolvedMime,
            expectJson = true
        )

        if (firstRes.isSuccess) {
            val parsed = parseAnalysisJson(
                rawJson = firstRes.getOrThrow(),
                callTimestampMs = call.timestamp,
                modelUsed = initialModel
            )
            // Automatic escalation to gemini-2.5-flash when initial lite pass flags Marathi/Hindi complexity or ambiguity
            val shouldEscalate = initialModel == MODEL_FLASH_LITE && (
                parsed.needsReview ||
                    parsed.confidence < 65 ||
                    (parsed.detectedLanguage in setOf("Marathi", "Hindi", "Mixed") && call.durationSeconds >= 90)
                )
            if (shouldEscalate) {
                val escalatedRes = callGeminiRest(
                    apiKey = apiKey,
                    modelName = MODEL_FLASH,
                    prompt = prompt,
                    audioBase64 = base64Audio,
                    audioMimeType = resolvedMime,
                    expectJson = true
                )
                if (escalatedRes.isSuccess) {
                    return@withContext Result.success(
                        parseAnalysisJson(
                            rawJson = escalatedRes.getOrThrow(),
                            callTimestampMs = call.timestamp,
                            modelUsed = MODEL_FLASH
                        )
                    )
                }
            }
            return@withContext Result.success(parsed)
        }

        // Fallback to gemini-2.5-flash if flash-lite call failed
        if (initialModel != MODEL_FLASH) {
            val fallbackRes = callGeminiRest(
                apiKey = apiKey,
                modelName = MODEL_FLASH,
                prompt = prompt,
                audioBase64 = base64Audio,
                audioMimeType = resolvedMime,
                expectJson = true
            )
            if (fallbackRes.isSuccess) {
                return@withContext Result.success(
                    parseAnalysisJson(
                        rawJson = fallbackRes.getOrThrow(),
                        callTimestampMs = call.timestamp,
                        modelUsed = MODEL_FLASH
                    )
                )
            }
            return@withContext Result.failure(fallbackRes.exceptionOrNull() ?: firstRes.exceptionOrNull()!!)
        }

        Result.failure(firstRes.exceptionOrNull() ?: IllegalStateException("Audio analysis failed."))
    }

    /**
     * Extracts the exact Batch 44 structured JSON schema from a voice-dictated transcript
     * (e.g., produced by `gemini-3.5-transcribe` from the microphone).
     */
    suspend fun analyzeTranscriptText(
        call: CrmCallRecord,
        transcript: String
    ): Result<CallAudioAnalysisResult> = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Configure GEMINI_API_KEY in AI Studio Secrets.")
            )
        }
        val cleanTranscript = transcript.trim()
        if (cleanTranscript.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Transcript is blank."))
        }
        val modelName = selectExtractionModel(
            durationSeconds = call.durationSeconds,
            languageOrContextHint = cleanTranscript
        )
        val prompt = buildMultilingualExtractionPrompt(call = call, transcriptOverride = cleanTranscript)
        val res = callGeminiRest(
            apiKey = apiKey,
            modelName = modelName,
            prompt = prompt,
            audioBase64 = null,
            audioMimeType = null,
            expectJson = true
        )
        res.map { rawJson ->
            parseAnalysisJson(
                rawJson = rawJson,
                callTimestampMs = call.timestamp,
                modelUsed = modelName,
                fallbackTranscript = cleanTranscript
            )
        }
    }

    fun buildMultilingualExtractionPrompt(
        call: CrmCallRecord,
        transcriptOverride: String? = null
    ): String {
        val callDateFormatted = SimpleDateFormat("dd/MM/yyyy (EEEE)", Locale.ENGLISH)
            .format(Date(if (call.timestamp > 0L) call.timestamp else System.currentTimeMillis()))
        val sourceBlock = if (!transcriptOverride.isNullOrBlank()) {
            "TRANSCRIPT TO ANALYZE:\n\"\"\"\n$transcriptOverride\n\"\"\""
        } else {
            "Analyze the attached call recording audio directly."
        }
        return """
            You are a senior Pune commercial & residential real estate call intelligence analyst for "The Leasing Company".
            CALL METADATA:
            - Call Date: $callDateFormatted
            - Caller Name: ${call.callerName.ifBlank { "Unknown" }}
            - Direction: ${call.callType.label}
            - Duration: ${call.durationSeconds} seconds
            $sourceBlock

            LANGUAGE PRIORITY & CODE-SWITCHING:
            - Prioritize Marathi (1st), Hindi (2nd), English (3rd), and Pune Hinglish/Marathi-English real estate code-switching
              (examples: "Kharadi madhe", "carpet", "deposit", "lock-in", "guntha", "bhk", "warm shell", "bare shell", "udya", "kal").
            - Output ALL structured CRM/Inventory fields and the 2-3 bullet summary in CLEAN ENGLISH
              (preserve short verbatim Marathi/Hindi terms in parentheses only if strictly needed for clarity).

            STRICT ZERO-FABRICATION RULE:
            - Leave ANY unmentioned field as null or empty string "".
            - NEVER invent prices, rents, carpet areas, unit numbers, locations, or follow-up dates.
            - If the caller is a Broker (BROKER_CP) and did not state a unit number, leave unitNumber "".
            - In suggestedWhatsAppDraft, write a polite, ready-to-send follow-up message in English branded "The Leasing Company" with ZERO owner name, owner phone, or internal source leaks. If no follow-up is appropriate (e.g., PERSONAL_NON_BUSINESS), leave suggestedWhatsAppDraft "".
            - Resolve relative follow-up dates (e.g., "tomorrow", "udya", "kal", "Saturday") into exact dd/MM/yyyy relative to Call Date ($callDateFormatted). If no follow-up date was discussed, set followUp.date to "".

            Return ONLY a valid JSON object matching this exact schema:
            {
              "callerRole": "CLIENT_LEAD | OWNER | BROKER_CP | DEVELOPER_BUILDER | TENANT | VENDOR | PERSONAL_NON_BUSINESS",
              "detectedLanguage": "Marathi | Hindi | English | Mixed",
              "summaryBullets": ["bullet 1", "bullet 2"],
              "extractedClientLead": {
                "division": "Commercial | Residential | ",
                "transactionType": "Lease | Buy | ",
                "propertyType": "",
                "configuration": "",
                "location": "",
                "microMarket": "",
                "carpetAreaSqFt": "",
                "budgetMin": "",
                "budgetMax": "",
                "furnishing": "",
                "possession": ""
              },
              "extractedPropertyUpdate": {
                "actionType": "NEW_PROPERTY | UPDATE_EXISTING | BUILDER_SLAB_UPDATE | ",
                "projectName": "",
                "unitNumber": "",
                "location": "",
                "microMarket": "",
                "assetType": "",
                "carpetAreaSqFt": "",
                "builtUpAreaSqFt": "",
                "rentOrPrice": "",
                "attachedPantry": "",
                "attachedWashroom": "",
                "statusChange": "Available | Under Negotiation | Leased / Sold Out | ",
                "builderOfferOrBrokerageNote": ""
              },
              "extractedTenantNote": {
                "tenantAction": "Renewal | Maintenance | Notice | Rent | ",
                "notes": ""
              },
              "followUp": {
                "date": "dd/MM/yyyy or empty",
                "time": "hh:mm a or empty",
                "nextAction": "Call Back | Send WA Shortlist | Schedule Site Visit | Verify Unit | ",
                "suggestedWhatsAppDraft": ""
              },
              "confidence": 0,
              "needsReview": false,
              "transcript": "brief key transcript excerpt if helpful, else empty"
            }
        """.trimIndent()
    }

    /**
     * Parses Gemini's structured JSON output into [CallAudioAnalysisResult], stripping any
     * accidental markdown fences, enforcing zero-fabricated placeholder scrubbing, and
     * normalizing relative follow-up dates into `dd/MM/yyyy`.
     */
    fun parseAnalysisJson(
        rawJson: String,
        callTimestampMs: Long,
        modelUsed: String = MODEL_FLASH_LITE,
        fallbackTranscript: String = ""
    ): CallAudioAnalysisResult {
        val cleanedJson = extractJsonObjectText(rawJson)
        val root = JSONObject(cleanedJson)

        val rawRole = cleanScalar(root.opt("callerRole")).uppercase(Locale.ENGLISH)
            .replace(" ", "_").replace("/", "_")
        val callerRole = when {
            rawRole in VALID_ROLES -> rawRole
            rawRole.contains("BROKER") || rawRole.contains("CP") -> "BROKER_CP"
            rawRole.contains("DEVELOPER") || rawRole.contains("BUILDER") -> "DEVELOPER_BUILDER"
            rawRole.contains("OWNER") || rawRole.contains("LANDLORD") -> "OWNER"
            rawRole.contains("TENANT") -> "TENANT"
            rawRole.contains("VENDOR") || rawRole.contains("OPS") -> "VENDOR"
            rawRole.contains("CLIENT") || rawRole.contains("LEAD") || rawRole.contains("BUYER") -> "CLIENT_LEAD"
            else -> "PERSONAL_NON_BUSINESS"
        }

        val rawLang = cleanScalar(root.opt("detectedLanguage"))
        val detectedLanguage = when {
            rawLang.contains("marathi", true) && (rawLang.contains("english", true) || rawLang.contains("hindi", true) || rawLang.contains("mix", true)) -> "Mixed"
            rawLang.contains("marathi", true) -> "Marathi"
            rawLang.contains("hindi", true) && rawLang.contains("english", true) -> "Mixed"
            rawLang.contains("hindi", true) -> "Hindi"
            rawLang.contains("mix", true) || rawLang.contains("hinglish", true) -> "Mixed"
            rawLang.contains("english", true) -> "English"
            else -> "English"
        }

        val bullets = mutableListOf<String>()
        val bulletsArr = root.optJSONArray("summaryBullets")
        if (bulletsArr != null) {
            for (i in 0 until bulletsArr.length()) {
                val b = cleanScalar(bulletsArr.opt(i))
                if (b.isNotBlank()) bullets.add(b)
            }
        }
        if (bullets.isEmpty()) {
            val fallbackSummary = cleanScalar(root.opt("summary"))
            if (fallbackSummary.isNotBlank()) bullets.add(fallbackSummary)
        }

        val leadObj = root.optJSONObject("extractedClientLead")
        val extractedLead = leadObj?.let { obj ->
            ExtractedClientLeadData(
                division = cleanScalar(obj.opt("division")),
                transactionType = cleanScalar(obj.opt("transactionType")),
                propertyType = cleanScalar(obj.opt("propertyType")),
                configuration = cleanScalar(obj.opt("configuration")),
                location = cleanScalar(obj.opt("location")),
                microMarket = cleanScalar(obj.opt("microMarket")),
                carpetAreaSqFt = cleanScalar(obj.opt("carpetAreaSqFt")),
                budgetMin = cleanScalar(obj.opt("budgetMin")),
                budgetMax = cleanScalar(obj.opt("budgetMax")),
                furnishing = cleanScalar(obj.opt("furnishing")),
                possession = cleanScalar(obj.opt("possession"))
            ).takeIf { it.hasAnyField() }
        }

        val propObj = root.optJSONObject("extractedPropertyUpdate")
        val extractedProp = propObj?.let { obj ->
            val rawUnit = cleanScalar(obj.opt("unitNumber"))
            ExtractedPropertyUpdateData(
                actionType = cleanScalar(obj.opt("actionType")),
                projectName = cleanScalar(obj.opt("projectName")),
                unitNumber = rawUnit,
                location = cleanScalar(obj.opt("location")),
                microMarket = cleanScalar(obj.opt("microMarket")),
                assetType = cleanScalar(obj.opt("assetType")),
                carpetAreaSqFt = cleanScalar(obj.opt("carpetAreaSqFt")),
                builtUpAreaSqFt = cleanScalar(obj.opt("builtUpAreaSqFt")),
                rentOrPrice = cleanScalar(obj.opt("rentOrPrice")),
                attachedPantry = cleanScalar(obj.opt("attachedPantry")),
                attachedWashroom = cleanScalar(obj.opt("attachedWashroom")),
                statusChange = cleanScalar(obj.opt("statusChange")),
                builderOfferOrBrokerageNote = cleanScalar(obj.opt("builderOfferOrBrokerageNote"))
            ).takeIf { it.hasAnyField() }
        }

        val tenantObj = root.optJSONObject("extractedTenantNote")
        val extractedTenant = tenantObj?.let { obj ->
            ExtractedTenantNoteData(
                tenantAction = cleanScalar(obj.opt("tenantAction")),
                notes = cleanScalar(obj.opt("notes"))
            ).takeIf { it.hasAnyField() }
        }

        val followUpObj = root.optJSONObject("followUp")
        val rawFollowUpDate = cleanScalar(followUpObj?.opt("date"))
        val resolvedDate = resolveFollowUpDateDdMmYyyy(rawFollowUpDate, callTimestampMs)
        val followUp = ExtractedFollowUpData(
            date = resolvedDate,
            time = cleanScalar(followUpObj?.opt("time")),
            nextAction = cleanScalar(followUpObj?.opt("nextAction")),
            suggestedWhatsAppDraft = cleanScalar(followUpObj?.opt("suggestedWhatsAppDraft"))
        )

        val confidence = root.optInt("confidence", 75).coerceIn(0, 100)
        val needsReview = root.optBoolean("needsReview", confidence < 70)
        val transcript = cleanScalar(root.opt("transcript")).ifBlank { fallbackTranscript }

        return CallAudioAnalysisResult(
            callerRole = callerRole,
            detectedLanguage = detectedLanguage,
            summaryBullets = bullets.take(4),
            extractedClientLead = extractedLead,
            extractedPropertyUpdate = extractedProp,
            extractedTenantNote = extractedTenant,
            followUp = followUp,
            confidence = confidence,
            needsReview = needsReview,
            modelUsed = modelUsed,
            rawTranscript = transcript
        )
    }

    /**
     * Resolves explicit or relative follow-up date text into strict `dd/MM/yyyy` relative to [callTimestampMs].
     * Never fabricates a date when input is blank or unrecognized.
     */
    fun resolveFollowUpDateDdMmYyyy(rawDate: String?, callTimestampMs: Long): String {
        val cleaned = cleanScalar(rawDate)
        if (cleaned.isBlank()) return ""

        val outFmt = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).apply { isLenient = false }

        // 1. Exact dd/MM/yyyy or d/M/yyyy
        Regex("""^(\d{1,2})[/-](\d{1,2})[/-](\d{4})$""").matchEntire(cleaned)?.let { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return@let
            val month = m.groupValues[2].toIntOrNull() ?: return@let
            val year = m.groupValues[3].toIntOrNull() ?: return@let
            if (day in 1..31 && month in 1..12 && year in 2020..2099) {
                return String.format(Locale.ENGLISH, "%02d/%02d/%04d", day, month, year)
            }
        }

        // 2. ISO yyyy-MM-dd
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?:T.*)?$""").matchEntire(cleaned)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return@let
            val month = m.groupValues[2].toIntOrNull() ?: return@let
            val day = m.groupValues[3].toIntOrNull() ?: return@let
            if (day in 1..31 && month in 1..12 && year in 2020..2099) {
                return String.format(Locale.ENGLISH, "%02d/%02d/%04d", day, month, year)
            }
        }

        val cal = Calendar.getInstance(Locale.ENGLISH).apply {
            timeInMillis = if (callTimestampMs > 0L) callTimestampMs else System.currentTimeMillis()
        }
        val lower = cleaned.lowercase(Locale.ENGLISH)

        // 3. Relative Marathi / Hindi / English phrases
        return when {
            lower == "today" || lower == "aaj" || lower == "आज" -> outFmt.format(cal.time)
            lower == "tomorrow" || lower == "tmrw" || lower == "udya" || lower == "kal" || lower == "उद्या" || lower == "कल" -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                outFmt.format(cal.time)
            }
            lower.contains("day after tomorrow") || lower == "parwa" || lower == "parso" || lower == "परवा" -> {
                cal.add(Calendar.DAY_OF_YEAR, 2)
                outFmt.format(cal.time)
            }
            lower.contains("next week") -> {
                cal.add(Calendar.DAY_OF_YEAR, 7)
                outFmt.format(cal.time)
            }
            else -> {
                Regex("""\bin\s+(\d{1,2})\s+days?\b""").find(lower)?.let { m ->
                    val addDays = m.groupValues[1].toIntOrNull() ?: 0
                    if (addDays in 1..60) {
                        cal.add(Calendar.DAY_OF_YEAR, addDays)
                        return outFmt.format(cal.time)
                    }
                }
                val targetDow = when {
                    lower.contains("monday") || lower.contains("somvar") -> Calendar.MONDAY
                    lower.contains("tuesday") || lower.contains("mangalvar") -> Calendar.TUESDAY
                    lower.contains("wednesday") || lower.contains("budhvar") -> Calendar.WEDNESDAY
                    lower.contains("thursday") || lower.contains("guruvar") -> Calendar.THURSDAY
                    lower.contains("friday") || lower.contains("shukravar") -> Calendar.FRIDAY
                    lower.contains("saturday") || lower.contains("shanivar") -> Calendar.SATURDAY
                    lower.contains("sunday") || lower.contains("ravivar") -> Calendar.SUNDAY
                    else -> null
                }
                if (targetDow != null) {
                    val currentDow = cal.get(Calendar.DAY_OF_WEEK)
                    var delta = (targetDow - currentDow + 7) % 7
                    if (delta == 0) delta = 7
                    cal.add(Calendar.DAY_OF_YEAR, delta)
                    outFmt.format(cal.time)
                } else {
                    ""
                }
            }
        }
    }

    internal fun cleanScalar(value: Any?): String {
        if (value == null || value === JSONObject.NULL) return ""
        val str = value.toString().trim()
        if (str.isBlank()) return ""
        return if (str.lowercase(Locale.ENGLISH) in PLACEHOLDER_VALUES) "" else str
    }

    private fun extractJsonObjectText(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    private fun normalizeAudioMimeType(mimeType: String, fileName: String): String {
        val lowerMime = mimeType.lowercase(Locale.ENGLISH).trim()
        if (lowerMime.startsWith("audio/")) return lowerMime
        val lowerName = fileName.lowercase(Locale.ENGLISH)
        return when {
            lowerName.endsWith(".m4a") || lowerName.endsWith(".mp4") -> "audio/mp4"
            lowerName.endsWith(".mp3") -> "audio/mpeg"
            lowerName.endsWith(".aac") -> "audio/aac"
            lowerName.endsWith(".wav") -> "audio/wav"
            lowerName.endsWith(".ogg") -> "audio/ogg"
            lowerName.endsWith(".3gp") || lowerName.endsWith(".amr") -> "audio/3gpp"
            else -> "audio/mp4"
        }
    }

    private fun readRecordingBytes(context: Context, recording: MatchedCallRecording): ByteArray? {
        return runCatching {
            if (recording.isContentUri || recording.uriOrPath.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(recording.uriOrPath))?.use { it.readBytes() }
            } else {
                val f = File(recording.uriOrPath)
                if (f.exists() && f.canRead()) f.readBytes() else null
            }
        }.getOrNull()
    }

    private fun callGeminiRest(
        apiKey: String,
        modelName: String,
        prompt: String,
        audioBase64: String?,
        audioMimeType: String?,
        expectJson: Boolean
    ): Result<String> {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        val parts = JSONArray().apply {
            if (!audioBase64.isNullOrBlank() && !audioMimeType.isNullOrBlank()) {
                put(
                    JSONObject().apply {
                        put(
                            "inline_data",
                            JSONObject().apply {
                                put("mime_type", audioMimeType)
                                put("data", audioBase64)
                            }
                        )
                    }
                )
            }
            put(JSONObject().apply { put("text", prompt) })
        }

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", parts)
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.15)
                    if (expectJson) {
                        put("responseMimeType", "application/json")
                    }
                }
            )
        }

        val reqBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(reqBody).build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errMsg = runCatching {
                        JSONObject(bodyText).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    throw IllegalStateException(
                        "Gemini $modelName HTTP ${response.code}: ${errMsg.ifBlank { bodyText.take(180) }}"
                    )
                }
                val root = JSONObject(bodyText)
                val candidates = root.optJSONArray("candidates")
                    ?: throw IllegalStateException("Gemini $modelName returned no candidates.")
                val first = candidates.optJSONObject(0)
                    ?: throw IllegalStateException("Gemini $modelName returned empty candidate.")
                val outParts = first.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IllegalStateException("Gemini $modelName returned no content parts.")
                val sb = StringBuilder()
                for (i in 0 until outParts.length()) {
                    val t = outParts.optJSONObject(i)?.optString("text").orEmpty()
                    if (t.isNotBlank()) sb.append(t)
                }
                val text = sb.toString().trim()
                if (text.isBlank()) {
                    throw IllegalStateException("Gemini $modelName returned blank text.")
                }
                text
            }
        }
    }
}
