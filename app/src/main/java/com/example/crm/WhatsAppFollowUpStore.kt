package com.example.crm

import android.content.Context
import android.util.AtomicFile
import com.example.ai.GeminiRealEstateService
import com.example.ai.WhatsAppMessageClassification
import com.example.model.CalculationUtils
import com.example.model.PropertyItem
import com.example.ui.screens.CrmLead
import com.example.ui.screens.displayClientTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

data class WhatsAppFollowUpMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderName: String,
    val senderPhone: String = "",
    val packageName: String = "com.whatsapp",
    val messageText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val reviewed: Boolean = false,
    val reviewedAt: Long? = null,
    val matchedKeywords: List<String> = emptyList(),
    // Batch 22: Thread compilation (sender/group) + non-destructive Skip
    val threadKey: String = "",
    val isGroup: Boolean = false,
    val groupName: String = "",
    val senderSubName: String = "",
    val skipped: Boolean = false,
    val skippedAt: Long? = null,
    // Gemini AI Inbox fields (backward-compatible defaults)
    val aiStatus: String = "NONE", // NONE, PENDING, ANALYZED, APPROVED
    val aiIntent: String = "", // PROPERTY_LISTING, CLIENT_REQUIREMENT, EXISTING_PROPERTY_UPDATE, FOLLOW_UP, UNRELATED
    val aiConfidence: Int = 0,
    val aiSummary: String = "",
    val aiLocation: String = "",
    val aiBudgetOrRent: String = "",
    val aiAreaText: String = "",
    val aiReviewFlagCount: Int = 0
) {
    fun effectiveThreadKey(): String {
        if (threadKey.isNotBlank()) return threadKey
        if (isGroup && groupName.isNotBlank()) {
            return "grp:" + groupName.trim().lowercase(Locale.ENGLISH).replace(Regex("\\s+"), " ")
        }
        val digits = ContactUtils.normalizePhoneNumber(senderPhone)
        if (digits.length == 10) return "ph:$digits"
        return "nm:" + senderName.trim().lowercase(Locale.ENGLISH).replace(Regex("\\s+"), " ")
    }

    fun formattedBodyLine(): String =
        if (isGroup && senderSubName.isNotBlank()) "$senderSubName: $messageText" else messageText

    fun isAiInboxEligible(): Boolean =
        !reviewed && !skipped && (
            aiStatus == "PENDING" ||
                aiStatus == "ANALYZED" ||
                aiIntent in setOf("PROPERTY_LISTING", "CLIENT_REQUIREMENT", "EXISTING_PROPERTY_UPDATE") ||
                matchedKeywords.isNotEmpty()
            )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("senderName", senderName)
        put("senderPhone", senderPhone)
        put("packageName", packageName)
        put("messageText", messageText)
        put("timestamp", timestamp)
        put("reviewed", reviewed)
        if (reviewedAt != null) put("reviewedAt", reviewedAt)
        put("matchedKeywords", JSONArray(matchedKeywords))
        put("threadKey", threadKey)
        put("isGroup", isGroup)
        put("groupName", groupName)
        put("senderSubName", senderSubName)
        put("skipped", skipped)
        if (skippedAt != null) put("skippedAt", skippedAt)
        put("aiStatus", aiStatus)
        put("aiIntent", aiIntent)
        put("aiConfidence", aiConfidence)
        put("aiSummary", aiSummary)
        put("aiLocation", aiLocation)
        put("aiBudgetOrRent", aiBudgetOrRent)
        put("aiAreaText", aiAreaText)
        put("aiReviewFlagCount", aiReviewFlagCount)
    }

    companion object {
        fun fromJson(obj: JSONObject): WhatsAppFollowUpMessage {
            val kwArr = obj.optJSONArray("matchedKeywords")
            val keywords = buildList {
                if (kwArr != null) {
                    for (i in 0 until kwArr.length()) {
                        val s = kwArr.optString(i).trim()
                        if (s.isNotBlank()) add(s)
                    }
                }
            }
            val revAt = if (obj.has("reviewedAt") && !obj.isNull("reviewedAt")) {
                obj.optLong("reviewedAt").takeIf { it > 0L }
            } else null
            val skipAt = if (obj.has("skippedAt") && !obj.isNull("skippedAt")) {
                obj.optLong("skippedAt").takeIf { it > 0L }
            } else null
            return WhatsAppFollowUpMessage(
                id = obj.optString("id", UUID.randomUUID().toString()),
                senderName = obj.optString("senderName", ""),
                senderPhone = obj.optString("senderPhone", ""),
                packageName = obj.optString("packageName", "com.whatsapp"),
                messageText = obj.optString("messageText", ""),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                reviewed = obj.optBoolean("reviewed", false),
                reviewedAt = revAt,
                matchedKeywords = keywords,
                threadKey = obj.optString("threadKey", ""),
                isGroup = obj.optBoolean("isGroup", false),
                groupName = obj.optString("groupName", ""),
                senderSubName = obj.optString("senderSubName", ""),
                skipped = obj.optBoolean("skipped", false),
                skippedAt = skipAt,
                aiStatus = obj.optString("aiStatus", "NONE"),
                aiIntent = obj.optString("aiIntent", ""),
                aiConfidence = obj.optInt("aiConfidence", 0),
                aiSummary = obj.optString("aiSummary", ""),
                aiLocation = obj.optString("aiLocation", ""),
                aiBudgetOrRent = obj.optString("aiBudgetOrRent", ""),
                aiAreaText = obj.optString("aiAreaText", ""),
                aiReviewFlagCount = obj.optInt("aiReviewFlagCount", 0)
            )
        }
    }
}

enum class FollowUpSenderRole(val label: String) {
    CRM_LEAD("CRM Lead"),
    PROPERTY_OWNER("Property Owner"),
    BROKER("Channel Partner"),
    DEVELOPER_POC("Developer POC"),
    UNMATCHED("Personal / Unmatched")
}

data class WhatsAppSenderMatch(
    val role: FollowUpSenderRole,
    val entityId: String = "",
    val entityLabel: String = "",
    val phone: String = "",
    val matchedLead: CrmLead? = null,
    val matchedProperty: PropertyItem? = null
)

data class WhatsAppThreadCluster(
    val threadKey: String,
    val displayName: String,
    val isGroup: Boolean,
    val packageName: String,
    val messages: List<WhatsAppFollowUpMessage>,
    val latestTimestamp: Long,
    val combinedText: String,
    val allKeywords: List<String>,
    val hasInventorySignal: Boolean,
    val bestMatch: WhatsAppSenderMatch,
    val isReviewed: Boolean,
    val isSkipped: Boolean,
    val aiStatus: String = "NONE",
    val aiIntent: String = "",
    val aiConfidence: Int = 0,
    val aiSummary: String = "",
    val aiReviewFlagCount: Int = 0
) {
    fun messageIds(): List<String> = messages.map { it.id }

    fun headerTitle(): String = if (messages.size > 1) {
        "$displayName · ${messages.size} messages"
    } else {
        displayName
    }
}

/**
 * Strictly ON-DEVICE store in context.filesDir for incoming WhatsApp & WhatsApp Business
 * reply notifications + Gemini AI Inbox queue. Never synced to Google Sheets automatically
 * until the user taps Approve.
 * Auto-expires reviewed/skipped items after 48 hours.
 */
object WhatsAppFollowUpStore {
    private const val FILE_MESSAGES = "wa_followup_messages.json"
    private const val FILE_MUTED_SENDERS = "wa_muted_contacts.json"
    private const val REVIEWED_TTL_MS = 48L * 3600L * 1000L
    private const val MAX_STORED_MESSAGES = 150

    private val lock = Any()
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _aiInboxCount = MutableStateFlow(0)
    val aiInboxCount: StateFlow<Int> = _aiInboxCount.asStateFlow()

    val FOLLOWUP_KEYWORDS = listOf(
        "visit", "site visit", "tomorrow", "call me", "available", "budget",
        "rent", "lease", "sale", "resale", "sqft", "sq ft", "sq.ft", "sft",
        "carpet", "built up", "bua", "bhk", "office", "shop", "showroom",
        "warehouse", "plot", "land", "furnished", "unfurnished", "warm shell",
        "bare shell", "requirement", "looking for", "need ", "pre-leased",
        "preleased", "roi", "possession", "deposit", "share details",
        "interested", "agreement", "udya", "bhetu"
    )

    val PUNE_REAL_ESTATE_LOCALITIES = listOf(
        "kharadi", "baner", "balewadi", "hinjewadi", "wakad", "viman nagar",
        "kalyani nagar", "koregaon park", "magarpatta", "hadapsar", "aundh",
        "bavdhan", "kothrud", "shivajinagar", "sb road", "fc road", "jm road",
        "bund garden", "yerwada", "wagholi", "pimple saudagar", "mundhwa", "keshav nagar"
    )

    private val INVENTORY_SIGNAL_TERMS = listOf(
        "sq.ft", "sqft", "sq ft", "sft", "carpet", "rent", "sale", "lease",
        "furnished", "unfurnished", "semi-furnished", "warm shell", "bare shell",
        "floor", "unit", "office", "shop", "showroom", "bhk", "lac", "lakh", "cr", "crore",
        "pre-leased", "preleased", "workstation", "cabin"
    )

    /**
     * Real-estate notification keyword pre-gate: returns true only when an incoming WhatsApp
     * notification contains genuine property/brokerage intent so personal chats stay untouched.
     */
    fun hasRealEstateKeyword(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 8) return false
        val lower = clean.lowercase(Locale.ENGLISH)
        val strongTerms = listOf(
            "sq.ft", "sqft", "sq ft", "sft", "carpet", "bhk", "office space", "commercial office",
            "shop for", "showroom", "warehouse", "pre-leased", "preleased", "warm shell", "bare shell",
            "site visit", "looking for", "requirement", "client req", "urgent req", "for lease",
            "for rent", "for sale", "available for rent", "available for lease", "available for sale",
            "resale", "workstation", "semi furnished", "fully furnished", "unfurnished"
        )
        if (strongTerms.any { lower.contains(it) }) return true

        val localityHit = PUNE_REAL_ESTATE_LOCALITIES.any { lower.contains(it) }
        val genericPropHit = listOf("office", "shop", "flat", "apartment", "villa", "plot", "land", "rent", "lease", "sale", "budget", "deposit", "possession", "brokerage", "roi", "lac", "lakh", "cr", "crore").count { lower.contains(it) }
        val hasNumber = Regex("\\d").containsMatchIn(lower)
        return (localityHit && (genericPropHit >= 1 || hasNumber)) || (genericPropHit >= 2) || (genericPropHit >= 1 && hasNumber && lower.contains("k "))
    }

    private val IGNORED_SYSTEM_TITLES = setOf(
        "whatsapp", "whatsapp business", "you", "checking for new messages",
        "checking for messages", "backup in progress", "finished backup"
    )

    private val IGNORED_SYSTEM_TEXTS = listOf(
        "checking for new messages",
        "new messages",
        "messages from",
        "missed voice call",
        "missed video call",
        "incoming voice call",
        "incoming video call",
        "ongoing call",
        "whatsapp web is currently active",
        "backing up messages"
    )

    fun extractMatchingKeywords(text: String): List<String> {
        val lower = text.lowercase(Locale.ENGLISH)
        return FOLLOWUP_KEYWORDS.filter { kw -> lower.contains(kw) }.distinct()
    }

    fun hasInventoryListingSignal(text: String): Boolean {
        val lower = text.lowercase(Locale.ENGLISH)
        val hits = INVENTORY_SIGNAL_TERMS.count { term -> lower.contains(term) }
        val hasNumber = Regex("\\d").containsMatchIn(lower)
        return hits >= 2 || (hits >= 1 && hasNumber && (lower.contains("sq") || lower.contains("bhk") || lower.contains("lac") || lower.contains("cr") || lower.contains("rent") || lower.contains("carpet")))
    }

    private fun normalizeSenderKey(raw: String): String =
        raw.trim().lowercase(Locale.ENGLISH).replace(Regex("\\s+"), " ")

    private fun readAtomic(context: Context, fileName: String): String? {
        val file = File(context.filesDir, fileName)
        if (!file.exists()) return null
        return runCatching {
            String(AtomicFile(file).readFully(), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun writeAtomic(context: Context, fileName: String, content: String) {
        val file = File(context.filesDir, fileName)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(StandardCharsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
        }
    }

    fun getMutedSenders(context: Context): Set<String> = synchronized(lock) {
        val raw = readAtomic(context, FILE_MUTED_SENDERS) ?: return@synchronized emptySet()
        runCatching {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val v = normalizeSenderKey(arr.optString(i))
                    if (v.isNotBlank()) add(v)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun isMutedSender(context: Context, senderName: String, senderPhone: String = ""): Boolean {
        val muted = getMutedSenders(context)
        val nameKey = normalizeSenderKey(senderName)
        if (nameKey.isNotBlank() && nameKey in muted) return true
        val digits = ContactUtils.normalizePhoneNumber(senderPhone)
        if (digits.length >= 10 && digits in muted) return true
        val titleDigits = ContactUtils.normalizePhoneNumber(senderName)
        if (titleDigits.length >= 10 && titleDigits in muted) return true
        return false
    }

    fun mutePersonalContact(context: Context, senderName: String, senderPhone: String = "") {
        synchronized(lock) {
            val current = getMutedSenders(context).toMutableSet()
            val nameKey = normalizeSenderKey(senderName)
            if (nameKey.isNotBlank()) current.add(nameKey)
            val phoneKey = ContactUtils.normalizePhoneNumber(senderPhone)
            if (phoneKey.length >= 10) current.add(phoneKey)
            val titleDigits = ContactUtils.normalizePhoneNumber(senderName)
            if (titleDigits.length >= 10) current.add(titleDigits)
            writeAtomic(context, FILE_MUTED_SENDERS, JSONArray(current.toList()).toString())

            val kept = loadMessagesInternal(context).filterNot { msg ->
                normalizeSenderKey(msg.senderName) == nameKey ||
                    (phoneKey.length >= 10 && ContactUtils.normalizePhoneNumber(msg.senderPhone) == phoneKey)
            }
            saveMessagesInternal(context, kept)
        }
    }

    private fun loadMessagesInternal(context: Context): List<WhatsAppFollowUpMessage> {
        val raw = readAtomic(context, FILE_MESSAGES) ?: return emptyList()
        val now = System.currentTimeMillis()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { WhatsAppFollowUpMessage.fromJson(it) }
            }
        }.getOrDefault(emptyList())

        val pruned = parsed.filter { msg ->
            if (!msg.reviewed && !msg.skipped) true
            else {
                val closedAt = msg.reviewedAt ?: msg.skippedAt ?: msg.timestamp
                (now - closedAt) <= REVIEWED_TTL_MS
            }
        }.sortedByDescending { it.timestamp }

        // Auto-clean bloated cache: deduplicate by (threadKey + normalizedText) and keep latest 8 per thread
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<WhatsAppFollowUpMessage>()
        for (msg in pruned) {
            val key = "${msg.effectiveThreadKey()}::${msg.messageText.trim().lowercase(Locale.ENGLISH)}"
            if (seen.add(key)) {
                deduped.add(msg)
            }
        }

        val perThreadCounts = mutableMapOf<String, Int>()
        val threadCapped = deduped.filter { msg ->
            val tKey = msg.effectiveThreadKey()
            val count = perThreadCounts.getOrDefault(tKey, 0)
            if (count < 8) {
                perThreadCounts[tKey] = count + 1
                true
            } else false
        }

        val finalCapped = threadCapped.take(100)

        if (finalCapped.size != parsed.size) {
            saveMessagesInternal(context, finalCapped)
        }
        _unreadCount.value = finalCapped.count { !it.reviewed && !it.skipped }
        _aiInboxCount.value = finalCapped.count { it.isAiInboxEligible() }
        return finalCapped
    }

    private fun saveMessagesInternal(context: Context, messages: List<WhatsAppFollowUpMessage>) {
        val trimmed = messages
            .sortedByDescending { it.timestamp }
            .take(MAX_STORED_MESSAGES)
        val arr = JSONArray().apply {
            trimmed.forEach { put(it.toJson()) }
        }
        writeAtomic(context, FILE_MESSAGES, arr.toString())
        _unreadCount.value = trimmed.count { !it.reviewed && !it.skipped }
        _aiInboxCount.value = trimmed.count { it.isAiInboxEligible() }
    }

    fun refreshUnreadCount(context: Context): Int = synchronized(lock) {
        val list = loadMessagesInternal(context)
        list.count { !it.reviewed && !it.skipped }
    }

    fun getMessages(context: Context): List<WhatsAppFollowUpMessage> = synchronized(lock) {
        loadMessagesInternal(context)
    }

    fun markReviewed(context: Context, messageId: String): List<WhatsAppFollowUpMessage> =
        markThreadReviewed(context, listOf(messageId))

    fun markThreadReviewed(context: Context, messageIds: List<String>): List<WhatsAppFollowUpMessage> = synchronized(lock) {
        val idSet = messageIds.toSet()
        val now = System.currentTimeMillis()
        val updated = loadMessagesInternal(context).map { msg ->
            if (msg.id in idSet) {
                msg.copy(
                    reviewed = true,
                    reviewedAt = now,
                    skipped = false,
                    aiStatus = if (msg.aiStatus != "NONE") "APPROVED" else msg.aiStatus
                )
            } else msg
        }
        saveMessagesInternal(context, updated)
        updated
    }

    fun skipThread(context: Context, messageIds: List<String>): List<WhatsAppFollowUpMessage> = synchronized(lock) {
        val idSet = messageIds.toSet()
        val now = System.currentTimeMillis()
        val updated = loadMessagesInternal(context).map { msg ->
            if (msg.id in idSet) msg.copy(skipped = true, skippedAt = now) else msg
        }
        saveMessagesInternal(context, updated)
        updated
    }

    /**
     * Persists Gemini AI Inbox classification & extracted spec preview for a captured message.
     */
    fun updateMessageAiAnalysis(
        context: Context,
        messageId: String,
        aiIntent: String,
        aiConfidence: Int,
        aiSummary: String,
        aiLocation: String = "",
        aiBudgetOrRent: String = "",
        aiAreaText: String = "",
        aiReviewFlagCount: Int = 0
    ): List<WhatsAppFollowUpMessage> = synchronized(lock) {
        val updated = loadMessagesInternal(context).map { msg ->
            if (msg.id == messageId) {
                msg.copy(
                    aiStatus = if (aiIntent == "UNRELATED") "NONE" else "ANALYZED",
                    aiIntent = aiIntent,
                    aiConfidence = aiConfidence.coerceIn(0, 100),
                    aiSummary = aiSummary.trim(),
                    aiLocation = aiLocation.trim(),
                    aiBudgetOrRent = aiBudgetOrRent.trim(),
                    aiAreaText = aiAreaText.trim(),
                    aiReviewFlagCount = aiReviewFlagCount.coerceAtLeast(0)
                )
            } else msg
        }
        saveMessagesInternal(context, updated)
        updated
    }

    /**
     * Runs Gemini (with automatic safe offline fallback) to classify a captured WhatsApp message
     * into PROPERTY_LISTING, CLIENT_REQUIREMENT, EXISTING_PROPERTY_UPDATE, or UNRELATED,
     * and saves a compact spec preview to the on-device AI Inbox queue for 1-tap approval.
     */
    suspend fun analyzeMessageForAiInbox(
        context: Context,
        message: WhatsAppFollowUpMessage,
        aiService: GeminiRealEstateService = GeminiRealEstateService()
    ): List<WhatsAppFollowUpMessage> {
        val senderLabel = if (message.isGroup && message.senderSubName.isNotBlank()) message.senderSubName else message.senderName
        val batch = runCatching {
            aiService.extractWhatsAppSafely(
                rawText = message.messageText,
                senderName = senderLabel,
                senderPhone = message.senderPhone
            )
        }.getOrNull() ?: return getMessages(context)

        var summary = ""
        var loc = ""
        var budgetOrRent = ""
        var areaText = ""
        var flagCount = 0

        when (batch.classification) {
            WhatsAppMessageClassification.PROPERTY_LISTING,
            WhatsAppMessageClassification.EXISTING_PROPERTY_UPDATE,
            WhatsAppMessageClassification.AMBIGUOUS_NEEDS_REVIEW -> {
                val draft = batch.drafts.firstOrNull()
                if (draft != null) {
                    loc = draft.location
                    if (draft.carpetAreaSqFt > 0.0) {
                        areaText = "${CalculationUtils.formatNumber(draft.carpetAreaSqFt)} sq.ft CA"
                    } else if ((draft.builtUpAreaSqFt ?: 0.0) > 0.0) {
                        areaText = "${CalculationUtils.formatNumber(draft.builtUpAreaSqFt!!)} sq.ft BUA"
                    }
                    val amount = draft.rent ?: draft.priceSale ?: draft.priceOrRent
                    if (amount > 0.0) {
                        budgetOrRent = CalculationUtils.formatIndianCurrency(amount, isMonthly = draft.tab.isRental)
                    }
                    flagCount = draft.reviewWarnings.size + draft.extractedFields.values.count { it.needsReview }
                    val multiTag = if (batch.drafts.size > 1) "+${batch.drafts.size - 1} more" else null
                    summary = listOfNotNull(
                        draft.projectName.takeIf { it.isNotBlank() } ?: draft.propertyType.takeIf { it.isNotBlank() },
                        loc.takeIf { it.isNotBlank() },
                        areaText.takeIf { it.isNotBlank() },
                        budgetOrRent.takeIf { it.isNotBlank() },
                        multiTag
                    ).joinToString(" • ")
                }
            }
            WhatsAppMessageClassification.CLIENT_REQUIREMENT -> {
                val leadDraft = runCatching {
                    aiService.parseBrokerOrClientLeadsBatch(message.messageText).firstOrNull()
                }.getOrNull()
                if (leadDraft != null) {
                    loc = leadDraft.location
                    areaText = leadDraft.areaRangeText
                    budgetOrRent = leadDraft.budgetText
                    summary = listOfNotNull(
                        leadDraft.requirementSummary.takeIf { it.isNotBlank() },
                        loc.takeIf { it.isNotBlank() },
                        budgetOrRent.takeIf { it.isNotBlank() }
                    ).joinToString(" • ")
                }
            }
            WhatsAppMessageClassification.UNRELATED -> {
                summary = ""
            }
        }

        val resolvedIntent = when (batch.classification) {
            WhatsAppMessageClassification.PROPERTY_LISTING -> "PROPERTY_LISTING"
            WhatsAppMessageClassification.CLIENT_REQUIREMENT -> "CLIENT_REQUIREMENT"
            WhatsAppMessageClassification.EXISTING_PROPERTY_UPDATE -> "EXISTING_PROPERTY_UPDATE"
            WhatsAppMessageClassification.AMBIGUOUS_NEEDS_REVIEW -> {
                if (batch.drafts.isNotEmpty()) "PROPERTY_LISTING" else "FOLLOW_UP"
            }
            WhatsAppMessageClassification.UNRELATED -> "UNRELATED"
        }

        return updateMessageAiAnalysis(
            context = context,
            messageId = message.id,
            aiIntent = resolvedIntent,
            aiConfidence = batch.classificationConfidence,
            aiSummary = summary.ifBlank { batch.classificationReason.take(120) },
            aiLocation = loc,
            aiBudgetOrRent = budgetOrRent,
            aiAreaText = areaText,
            aiReviewFlagCount = flagCount
        )
    }

    fun clearReviewed(context: Context): List<WhatsAppFollowUpMessage> = synchronized(lock) {
        val remaining = loadMessagesInternal(context).filter { !it.reviewed }
        saveMessagesInternal(context, remaining)
        remaining
    }

    fun captureIncomingMessageNotification(
        context: Context,
        packageName: String,
        rawTitle: String,
        rawText: String,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val trimmedTitle = rawTitle.trim()
        val cleanText = rawText.trim()
        if (trimmedTitle.isBlank() || cleanText.isBlank()) return

        // Detect group notification pattern: "Group Name: Sender Name" or "Group Name (4 messages): Sender Name"
        val colonIdx = trimmedTitle.indexOf(':')
        val isGroupChat = colonIdx > 0 && colonIdx < trimmedTitle.length - 1
        val cleanTitle = if (isGroupChat) {
            trimmedTitle.substring(0, colonIdx).substringBefore(" (").trim()
        } else {
            trimmedTitle.substringBefore(" (").trim()
        }
        val groupMemberName = if (isGroupChat) {
            trimmedTitle.substring(colonIdx + 1).trim()
        } else ""

        if (cleanTitle.isBlank()) return

        val lowerTitle = cleanTitle.lowercase(Locale.ENGLISH)
        val lowerText = cleanText.lowercase(Locale.ENGLISH)
        if (lowerTitle in IGNORED_SYSTEM_TITLES) return
        if (IGNORED_SYSTEM_TEXTS.any { lowerText.contains(it) }) return
        if (Regex("^\\d+\\s+new\\s+messages?$", RegexOption.IGNORE_CASE).matches(cleanText)) return

        val possibleDigits = ContactUtils.normalizePhoneNumber(cleanTitle)
        val senderPhone = if (!isGroupChat && possibleDigits.length == 10) possibleDigits else ""
        if (isMutedSender(context, cleanTitle, senderPhone)) return

        val matchedKeywords = extractMatchingKeywords(cleanText)
        val hasRealEstateSignal = hasRealEstateKeyword(cleanText)
        val computedThreadKey = if (isGroupChat) {
            "grp:${normalizeSenderKey(cleanTitle)}"
        } else if (senderPhone.length == 10) {
            "ph:$senderPhone"
        } else {
            "nm:${normalizeSenderKey(cleanTitle)}"
        }

        var newlyCaptured: WhatsAppFollowUpMessage? = null
        synchronized(lock) {
            val existing = loadMessagesInternal(context).toMutableList()
            val normBody = cleanText.trim().lowercase(Locale.ENGLISH)
            val duplicate = existing.any { prev ->
                prev.effectiveThreadKey() == computedThreadKey &&
                    prev.messageText.trim().lowercase(Locale.ENGLISH) == normBody
            }
            if (duplicate) return

            val entry = WhatsAppFollowUpMessage(
                id = UUID.randomUUID().toString(),
                senderName = cleanTitle,
                senderPhone = senderPhone,
                packageName = packageName,
                messageText = cleanText,
                timestamp = timestampMs,
                reviewed = false,
                reviewedAt = null,
                matchedKeywords = matchedKeywords,
                threadKey = computedThreadKey,
                isGroup = isGroupChat,
                groupName = if (isGroupChat) cleanTitle else "",
                senderSubName = groupMemberName,
                skipped = false,
                skippedAt = null,
                aiStatus = if (hasRealEstateSignal) "PENDING" else "NONE"
            )
            newlyCaptured = entry
            existing.add(0, entry)
            saveMessagesInternal(context, existing)
        }

        // Non-blocking background Gemini classification into AI Inbox when real-estate keywords matched
        val targetMsg = newlyCaptured
        if (hasRealEstateSignal && targetMsg != null) {
            val appCtx = context.applicationContext ?: context
            aiScope.launch {
                runCatching {
                    analyzeMessageForAiInbox(appCtx, targetMsg)
                }
            }
        }
    }

    /**
     * Compiles messages by Sender or Group threadKey.
     * Unread messages from the same sender/group are merged into a single Thread Cluster card
     * (e.g. "Rohan More · 4 messages") until reviewed, replied, or skipped.
     */
    fun buildCompiledThreads(
        messages: List<WhatsAppFollowUpMessage>,
        filter: String,
        leads: List<CrmLead>,
        inventory: List<PropertyItem>,
        brokerContacts: Map<String, BrokerContactEntry>,
        pocLinks: Map<String, ProjectPocEntry>
    ): List<WhatsAppThreadCluster> {
        val baseScoped = when (filter) {
            "AI Inbox" -> messages.filter { it.isAiInboxEligible() }
            "Unread" -> messages.filter { !it.reviewed && !it.skipped }
            "Keywords" -> messages.filter { !it.skipped && it.matchedKeywords.isNotEmpty() }
            "Skipped" -> messages.filter { it.skipped }
            "Reviewed" -> messages.filter { it.reviewed }
            else -> messages.filter { !it.skipped }
        }
        val scoped = if (filter == "Hide Group Chats") {
            baseScoped.filter { !it.isGroup }
        } else {
            baseScoped
        }
        val grouped = scoped.groupBy { msg ->
            val stateBucket = when {
                msg.reviewed -> "rev"
                msg.skipped -> "skip"
                else -> "open"
            }
            "${stateBucket}::${msg.effectiveThreadKey()}"
        }
        return grouped.entries.map { (_, clusterMsgs) ->
            val chronological = clusterMsgs.sortedBy { it.timestamp }.takeLast(10)
            val latest = chronological.last()
            val combined = chronological.joinToString("\n") { it.formattedBodyLine() }
            val keywords = chronological.flatMap { it.matchedKeywords }.distinct()
            val bestMatch = clusterMsgs.firstNotNullOfOrNull { m ->
                val candidate = matchSender(m, leads, inventory, brokerContacts, pocLinks)
                candidate.takeIf { it.role != FollowUpSenderRole.UNMATCHED }
            } ?: matchSender(latest, leads, inventory, brokerContacts, pocLinks)

            val bestAiMsg = chronological.lastOrNull { it.aiIntent.isNotBlank() && it.aiIntent != "UNRELATED" }
                ?: chronological.lastOrNull { it.aiStatus == "PENDING" }
                ?: latest
            val effectiveAiStatus = when {
                chronological.any { it.aiStatus == "ANALYZED" } -> "ANALYZED"
                chronological.any { it.aiStatus == "PENDING" } -> "PENDING"
                chronological.any { it.aiStatus == "APPROVED" } -> "APPROVED"
                else -> "NONE"
            }

            WhatsAppThreadCluster(
                threadKey = latest.effectiveThreadKey(),
                displayName = latest.groupName.ifBlank { latest.senderName },
                isGroup = chronological.any { it.isGroup },
                packageName = latest.packageName,
                messages = chronological,
                latestTimestamp = latest.timestamp,
                combinedText = combined,
                allKeywords = keywords,
                hasInventorySignal = hasInventoryListingSignal(combined) ||
                    bestAiMsg.aiIntent == "PROPERTY_LISTING" ||
                    bestAiMsg.aiIntent == "EXISTING_PROPERTY_UPDATE",
                bestMatch = bestMatch,
                isReviewed = clusterMsgs.all { it.reviewed },
                isSkipped = clusterMsgs.all { it.skipped },
                aiStatus = effectiveAiStatus,
                aiIntent = bestAiMsg.aiIntent,
                aiConfidence = bestAiMsg.aiConfidence,
                aiSummary = bestAiMsg.aiSummary,
                aiReviewFlagCount = bestAiMsg.aiReviewFlagCount
            )
        }.sortedByDescending { it.latestTimestamp }
    }

    fun matchSender(
        msg: WhatsAppFollowUpMessage,
        leads: List<CrmLead>,
        inventory: List<PropertyItem>,
        brokerContacts: Map<String, BrokerContactEntry>,
        pocLinks: Map<String, ProjectPocEntry>
    ): WhatsAppSenderMatch {
        val targetName = if (msg.isGroup && msg.senderSubName.isNotBlank()) msg.senderSubName else msg.senderName
        val nameKey = normalizeSenderKey(targetName)
        val phoneKey = ContactUtils.normalizePhoneNumber(
            msg.senderPhone.ifBlank { targetName }
        )

        fun nameMatches(candidate: String?): Boolean {
            val c = candidate?.trim()?.lowercase(Locale.ENGLISH)?.replace(Regex("\\s+"), " ") ?: return false
            if (c.length < 3 || nameKey.length < 3) return false
            return c == nameKey || (c.length >= 4 && nameKey.contains(c)) || (nameKey.length >= 4 && c.contains(nameKey))
        }

        fun phoneMatches(candidate: String?): Boolean {
            if (phoneKey.length < 10 || candidate.isNullOrBlank()) return false
            return ContactUtils.normalizePhoneNumber(candidate) == phoneKey
        }

        // 1. CRM Leads
        val leadMatch = leads.firstOrNull { lead ->
            phoneMatches(lead.phone) || phoneMatches(lead.alternatePhone) ||
                nameMatches(lead.name) || nameMatches(lead.displayClientTitle())
        }
        if (leadMatch != null) {
            return WhatsAppSenderMatch(
                role = FollowUpSenderRole.CRM_LEAD,
                entityId = leadMatch.id,
                entityLabel = "${leadMatch.displayClientTitle()} • ${leadMatch.segment}",
                phone = ContactUtils.normalizePhoneNumber(leadMatch.phone),
                matchedLead = leadMatch
            )
        }

        // 2. Property Owners
        val ownerProp = inventory.firstOrNull { prop ->
            phoneMatches(prop.ownerContact) || nameMatches(prop.ownerName)
        }
        if (ownerProp != null) {
            return WhatsAppSenderMatch(
                role = FollowUpSenderRole.PROPERTY_OWNER,
                entityId = ownerProp.id,
                entityLabel = "${ownerProp.ownerName?.ifBlank { "Owner" }} · ${ownerProp.projectName.ifBlank { ownerProp.propertyCode }}",
                phone = ContactUtils.normalizePhoneNumber(ownerProp.ownerContact),
                matchedProperty = ownerProp
            )
        }

        // 3. Brokers / Channel Partners
        val brokerEntry = brokerContacts.values.firstOrNull { b ->
            phoneMatches(b.phoneKey) || nameMatches(b.name)
        }
        val brokerProp = inventory.firstOrNull { prop ->
            phoneMatches(prop.brokerContact) || nameMatches(prop.brokerName)
        }
        if (brokerEntry != null || brokerProp != null) {
            val bName = brokerEntry?.name?.ifBlank { brokerProp?.brokerName.orEmpty() }
                ?: brokerProp?.brokerName.orEmpty()
            val bPhone = brokerEntry?.phoneKey?.ifBlank { ContactUtils.normalizePhoneNumber(brokerProp?.brokerContact) }
                ?: ContactUtils.normalizePhoneNumber(brokerProp?.brokerContact)
            return WhatsAppSenderMatch(
                role = FollowUpSenderRole.BROKER,
                entityId = brokerProp?.id ?: bPhone,
                entityLabel = "$bName${brokerEntry?.firm?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}",
                phone = bPhone,
                matchedProperty = brokerProp
            )
        }

        // 4. Developer POCs
        val pocEntry = pocLinks.values.firstOrNull { p ->
            phoneMatches(p.phoneKey) || nameMatches(p.contactName)
        }
        if (pocEntry != null) {
            return WhatsAppSenderMatch(
                role = FollowUpSenderRole.DEVELOPER_POC,
                entityId = pocEntry.projectId,
                entityLabel = "${pocEntry.contactName} · ${pocEntry.projectName}",
                phone = pocEntry.phoneKey
            )
        }

        return WhatsAppSenderMatch(
            role = FollowUpSenderRole.UNMATCHED,
            entityLabel = targetName,
            phone = phoneKey.takeIf { it.length == 10 }.orEmpty()
        )
    }
}
