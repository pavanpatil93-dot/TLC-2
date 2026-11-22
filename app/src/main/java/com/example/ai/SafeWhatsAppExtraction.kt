package com.example.ai

import com.example.model.FurnishingType
import com.example.model.InventoryTab
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class WhatsAppMessageClassification(val label: String) {
    PROPERTY_LISTING("Property Listing"),
    CLIENT_REQUIREMENT("Client Requirement"),
    EXISTING_PROPERTY_UPDATE("Existing Property Update"),
    UNRELATED("Unrelated Message"),
    AMBIGUOUS_NEEDS_REVIEW("Ambiguous — Needs Review");

    companion object {
        fun fromString(raw: String?): WhatsAppMessageClassification {
            val normalized = raw?.trim()?.uppercase(Locale.US)?.replace(" ", "_").orEmpty()
            return entries.firstOrNull { it.name == normalized } ?: AMBIGUOUS_NEEDS_REVIEW
        }
    }
}

enum class ConfidenceTier(val label: String) {
    HIGH("High"),
    REVIEW("Review"),
    CONFIRM_REQUIRED("Confirm Required")
}

data class ExtractedField<T>(
    val value: T? = null,
    val evidence: String? = null,
    val confidence: Int = 0,
    val needsReview: Boolean = false
) {
    val isPresent: Boolean
        get() = value != null && (value !is String || value.isNotBlank())

    val tier: ConfidenceTier
        get() = when {
            needsReview || confidence < 70 -> ConfidenceTier.CONFIRM_REQUIRED
            confidence >= 90 -> ConfidenceTier.HIGH
            else -> ConfidenceTier.REVIEW
        }
}

data class WhatsAppExtractionBatchResult(
    val classification: WhatsAppMessageClassification,
    val classificationConfidence: Int,
    val classificationReason: String,
    val drafts: List<ExtractedPropertyDraft>,
    val rawMessage: String,
    val usedFallbackParser: Boolean = true,
    val errorMessage: String? = null
) {
    val blocksAddPropertyForm: Boolean
        get() = classification == WhatsAppMessageClassification.CLIENT_REQUIREMENT ||
            classification == WhatsAppMessageClassification.UNRELATED
}

/**
 * Deterministic Build 01 Safe WhatsApp Extraction Engine.
 * - Strictly ZERO fabricated defaults (never invents area, price, deposit, project, locality, floor, or possession).
 * - Never substitutes Carpet Area for Built-up Area or Saleable Area.
 * - Two-step pipeline: Step A classifies message intent; Step B splits & extracts isolated multi-property drafts.
 * - Field-level evidence, confidence (0-100), and review flags for Pune shorthands, Indian number scales,
 *   Owner vs Source/Broker separation, and Marathi/Hinglish real estate messages.
 */
object SafeWhatsAppExtractor {

    private val FORBIDDEN_PLACEHOLDERS = setOf(
        "prime center", "available unit", "mid floor", "not specified",
        "not mentioned", "unknown", "tbd", "n/a", "none", "null", "na"
    )

    private val PUNE_PROJECTS = listOf(
        "Gera Imperium Alpha", "Gera Imperium Rise", "Gera Imperium Oasis", "Gera Imperium Star",
        "Gera Commerzone", "World Trade Center", "WTC", "EON Free Zone", "EON IT Park",
        "Global Business Hub", "Finswell", "Nyati Enthral", "Nyati Tech Park", "Nyati Unitree",
        "Zensar", "Panchshil Towers", "Panchshil Business Park", "Panchshil Tech Park",
        "Yoo Pune", "Trump Towers", "Godrej Boulevard", "Godrej Greens", "Godrej Rejuve",
        "Riverdale Grand", "Riverdale Grove", "Riverdale Heights", "Gagan Avencia",
        "VTP Pegasus", "VTP Leonara", "VTP Blue Waters", "Lodha Belmondo",
        "Kolte Patil Life Republic", "Amanora Park Town", "Magarpatta Cybercity",
        "Phoenix Marketcity", "Amar Business Zone", "Amar Tech Park", "Supreme HQ",
        "Balewadi High Street", "Kapil Zenith", "Solitaire Business Hub", "Cerebrum IT Park",
        "K Raheja Commerzone", "Marvel Fuego", "Pride Icon", "Kohinoor World Towers",
        "Aurelia", "Blue Ridge", "Rohan Abhilasha", "Duville Riverdale"
    ).sortedByDescending { it.length }

    private val PUNE_LOCALITIES = listOf(
        "Kharadi", "Viman Nagar", "Kalyani Nagar", "Koregaon Park", "Mundhwa", "Keshav Nagar",
        "Magarpatta", "Hadapsar", "Amanora", "Wagholi", "Yerwada", "Baner", "Balewadi", "Wakad",
        "Hinjawadi", "Hinjewadi", "Aundh", "Shivaji Nagar", "Shivajinagar", "Camp", "Pimpri",
        "Chinchwad", "Chakan", "Talegaon", "Ranjangaon", "Shikrapur", "NIBM", "Undri", "Kondhwa",
        "Pashan", "Bavdhan", "Kothrud", "Karve Nagar", "Warje", "Sinhagad Road", "Katraj",
        "Senapati Bapat Road", "SB Road", "FC Road", "JM Road", "Boat Club Road", "Bund Garden",
        "Ravet", "Tathawade", "Punawale", "Pimple Saudagar", "Pimple Nilakh", "Dhanori", "Lohegaon",
        "Mulshi", "Pirangut", "Sus", "Mahalunge", "Nagar Road", "Solapur Road", "Saswad"
    ).sortedByDescending { it.length }

    // ─────────────────────────────────────────────────────────────────────────────
    // STEP A: MESSAGE CLASSIFICATION
    // ─────────────────────────────────────────────────────────────────────────────

    fun classifyMessage(rawText: String): Triple<WhatsAppMessageClassification, Int, String> {
        val text = rawText.trim()
        if (text.length < 6) {
            return Triple(WhatsAppMessageClassification.UNRELATED, 95, "Message too short to contain a property listing.")
        }
        val lower = text.lowercase(Locale.US)

        // Exclude casual everyday phrases ("coffee shop", "tea shop", "sweet shop", etc.) from triggering asset cue
        val textForAssetCheck = text.replace(
            Regex("""\b(?:coffee|tea|cake|sweet|flower|barber|book|gift|print|repair|pet|ice\s*cream|paan|chai|medical|grocery|kirana)\s+shop\b""", RegexOption.IGNORE_CASE),
            " "
        )

        // Strong real-estate asset / domain signals
        val hasAssetCue = Regex(
            """\b(office|shop|showroom|retail|flat|apartment|bhk|villa|bungalow|penthouse|warehouse|godown|shed|plot|land|guntha|gunthe|acre|acres|7/12|satbara|pre[\-\s]?leased|commercial|residential|it\s*park|tower|project|dukan|jaga|jamin|sqft|sq\.?\s*ft|sft|carpet|bua|sba|rera)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(textForAssetCheck) ||
            text.contains("दुकान") || text.contains("जमीन") || text.contains("गुंठा") ||
            text.contains("सातबारा") || text.contains("फ्लॅट") || text.contains("ऑफिस")

        val hasTransactionCue = Regex(
            """\b(rent|lease|sale|sell|resale|outright|buy|purchase|deposit|sd\b|dep\b|roi|yield|pre[\-\s]?leased|avl\b|avail|available|bhade|bhadya|bhadyane|kiraya|kiraye|vikri|vikne|vikaycha|bechna|upalabdh|khali\s+ahe)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text) ||
            text.contains("भाडे") || text.contains("भाड्याने") || text.contains("विक्री") || text.contains("उपलब्ध")

        val hasLocationCue = PUNE_LOCALITIES.any { loc ->
            Regex("""\b${Regex.escape(loc)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        } || PUNE_PROJECTS.any { proj ->
            Regex("""\b${Regex.escape(proj)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

        val hasNumericSpecCue = Regex(
            """(\d+(?:\.\d+)?\s*(?:sq\.?\s*ft|sqft|sft|guntha|gunthe|acre|acres|cr|crore|lac|lakh|l\b|k\b|bhk))""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)

        val hasUnambiguousRealEstateSpec = Regex(
            """\b(bhk|sqft|sq\.?\s*ft|sft|carpet|bua|sba|guntha|gunthe|acre|acres|7/12|satbara|rera|pre[\-\s]?leased|showroom|godown|warehouse|penthouse|dukan|jaga|jamin)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text) ||
            text.contains("दुकान") || text.contains("जमीन") || text.contains("गुंठा") ||
            text.contains("सातबारा") || text.contains("फ्लॅट") || text.contains("ऑफिस")

        // 1. Check Unrelated (no real estate domain cues at all)
        if (!hasAssetCue && !hasTransactionCue && !hasLocationCue && !hasNumericSpecCue) {
            return Triple(
                WhatsAppMessageClassification.UNRELATED,
                95,
                "No property, area, pricing, or real estate transaction details detected."
            )
        }
        // Only a greeting / meeting mentioning a locality or generic word ("office"/"flat") without any property spec/price/transaction cue
        if (!hasTransactionCue && !hasNumericSpecCue && !hasUnambiguousRealEstateSpec && (!hasAssetCue || !hasLocationCue)) {
            return Triple(
                WhatsAppMessageClassification.UNRELATED,
                88,
                "Message appears to be general chat without property specs or transaction details."
            )
        }

        // 2. Check Client Requirement (including disguised listings!)
        val strongRequirementPatterns = listOf(
            Regex("""\b(client\s+requirement|urgent\s+req(?:uirement)?|requirement\s*[:\-]|req\s*[:\-]|looking\s+for|searching\s+for|in\s+search\s+of)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(wanted|needed|need\s+(?:a|an|1|2|3|4|5|office|shop|flat|showroom|warehouse|plot|land|commercial|residential)|require\s+(?:a|an|office|shop|flat|showroom|warehouse|plot|land))\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(please\s+share\s+(?:options|units|matching|direct)|share\s+(?:options|matching|if\s+available|any\s+options)|send\s+options|any\s+options\??|dm\s+(?:me\s+)?options)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(client\s+(?:needs|wants|looking|budget|shifting|requires)|for\s+(?:my|our)\s+client|direct\s+client\s+(?:req|requirement|looking)|buyer\s+(?:looking|wants|needs)|tenant\s+(?:looking|wants|needs))\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(want\s+to\s+(?:buy|rent|lease)|looking\s+to\s+(?:buy|rent|lease|shift)|planning\s+to\s+(?:buy|rent|shift))\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(chahiye|chahie|pahije|hava\s+ahe|havi\s+ahe|have\s+ahe|lagte\s+ahe|lagat\s+ahe)\b""", RegexOption.IGNORE_CASE)
        )
        val requirementMatches = strongRequirementPatterns.mapNotNull { it.find(text)?.value }
        val hasBudgetCue = Regex("""\b(?:client\s+)?budget\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            text.contains("पाहिजे") || text.contains("हवे") || text.contains("चाहिए")

        val strongListingSupplyCue = Regex(
            """\b(for\s+(?:lease|rent|sale|resale|outright)|available\s+for\s+(?:lease|rent|sale)|avl\s+for\s+(?:lease|rent|sale)|direct\s+owner\s+(?:property|flat|office|shop)|unit\s*(?:no\.?|#)?\s*[:\-]?\s*[A-Z]?\d{2,5}|bhadyane\s+upalabdh|vikrisathi\s+upalabdh|भाड्याने\s+उपलब्ध|विक्रीसाठी)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)

        if (requirementMatches.isNotEmpty() || (hasBudgetCue && !strongListingSupplyCue)) {
            // Even if message starts with "Available requirement:" or describes specs, requirement wins
            val reasonCue = requirementMatches.firstOrNull() ?: "budget / requirement cue"
            return Triple(
                WhatsAppMessageClassification.CLIENT_REQUIREMENT,
                if (requirementMatches.isNotEmpty()) 94 else 84,
                "Detected client requirement cue (\"$reasonCue\"). Route to CRM instead of Property Inventory."
            )
        }

        // 3. Check Existing Property Update / Price Revision / Conflict
        val updatePatterns = listOf(
            Regex("""\b(price\s+(?:revised|reduced|dropped|updated|changed|cut)|rent\s+(?:revised|reduced|dropped|updated|changed|cut)|rate\s+(?:revised|reduced|updated))\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(revised\s+(?:to|from|rent|price)|reduced\s+(?:to|from)|dropped\s+(?:to|from)|updated\s+(?:rent|price|status)|status\s+update|update\s+on\s+(?:unit|flat|office|shop)|correction\s*[:\-])\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(earlier\s+(?:rent|price|was)|previously\s+(?:rent|price|was)|instead\s+of\s+(?:₹|rs\.?)?\s*\d|now\s+(?:reduced|revised)\s+to)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(sold\s+out|leased\s+out|rented\s+out|deal\s+closed|no\s+longer\s+available|off\s+market)\b""", RegexOption.IGNORE_CASE)
        )
        val updateMatch = updatePatterns.firstNotNullOfOrNull { it.find(text)?.value }
        if (updateMatch != null) {
            return Triple(
                WhatsAppMessageClassification.EXISTING_PROPERTY_UPDATE,
                90,
                "Detected existing property update / revision cue (\"$updateMatch\"). Verify before updating inventory."
            )
        }

        // 4. Property Listing vs Ambiguous
        if (hasAssetCue && (hasTransactionCue || hasNumericSpecCue || hasLocationCue)) {
            return Triple(
                WhatsAppMessageClassification.PROPERTY_LISTING,
                92,
                "Detected property listing details."
            )
        }

        return Triple(
            WhatsAppMessageClassification.AMBIGUOUS_NEEDS_REVIEW,
            60,
            "Partial real estate details found without a clear listing or requirement intent."
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // STEP B: MULTI-PROPERTY SPLITTER & FULL OFFLINE EXTRACTOR
    // ─────────────────────────────────────────────────────────────────────────────

    fun extractOffline(
        rawText: String,
        senderName: String = "",
        senderPhone: String = "",
        errorMessage: String? = null
    ): WhatsAppExtractionBatchResult {
        val cleaned = rawText.replace("\r\n", "\n").trim()
        val (classification, classConfidence, classReason) = classifyMessage(cleaned)

        if (classification == WhatsAppMessageClassification.UNRELATED) {
            return WhatsAppExtractionBatchResult(
                classification = classification,
                classificationConfidence = classConfidence,
                classificationReason = classReason,
                drafts = emptyList(),
                rawMessage = cleaned,
                usedFallbackParser = true,
                errorMessage = errorMessage
            )
        }

        if (classification == WhatsAppMessageClassification.CLIENT_REQUIREMENT) {
            return WhatsAppExtractionBatchResult(
                classification = classification,
                classificationConfidence = classConfidence,
                classificationReason = classReason,
                drafts = emptyList(),
                rawMessage = cleaned,
                usedFallbackParser = true,
                errorMessage = errorMessage
            )
        }

        val (sharedFooterContact, segments) = splitIntoIsolatedSegments(cleaned)
        val drafts = segments.map { segment ->
            parseIsolatedSegment(
                segmentText = segment,
                sharedContactSnippet = sharedFooterContact,
                senderName = senderName,
                senderPhone = senderPhone,
                parentClassification = classification
            )
        }

        // Upgrade classification to EXISTING_PROPERTY_UPDATE if single draft shows explicit price conflict
        val finalClassification = if (classification == WhatsAppMessageClassification.PROPERTY_LISTING &&
            drafts.size == 1 && drafts.first().isPriceConflictOrUpdate
        ) {
            WhatsAppMessageClassification.EXISTING_PROPERTY_UPDATE
        } else {
            classification
        }

        val finalReason = if (finalClassification != classification) {
            "Conflicting / revised price figures detected for the same property — verify update."
        } else {
            classReason
        }

        return WhatsAppExtractionBatchResult(
            classification = finalClassification,
            classificationConfidence = classConfidence,
            classificationReason = finalReason,
            drafts = drafts,
            rawMessage = cleaned,
            usedFallbackParser = true,
            errorMessage = errorMessage
        )
    }

    /**
     * Splits a multi-property WhatsApp forward into strictly isolated property blocks.
     * Returns Pair(sharedFooterContactSnippet, listOfPropertySegments).
     */
    internal fun splitIntoIsolatedSegments(normalized: String): Pair<String, List<String>> {
        if (normalized.isBlank()) return "" to emptyList()

        // Extract trailing standalone contact footer (e.g. "Call Rajesh 9822011223" / "Regards, ...")
        // so it can be attached as Source/Broker without becoming a bogus property segment.
        val lines = normalized.lines()
        val footerLines = mutableListOf<String>()
        var bodyEndExclusive = lines.size
        while (bodyEndExclusive > 1) {
            val candidate = lines[bodyEndExclusive - 1].trim()
            if (candidate.isBlank()) {
                bodyEndExclusive--
                continue
            }
            val isContactOnlyLine = (
                Regex("""(?:\+91[\-\s]?)?[6-9]\d{9}""").containsMatchIn(candidate) ||
                    Regex("""(?i)^(?:regards|thanks|contact|call|broker|cp|channel\s*partner|from|shared\s+by)\b""").containsMatchIn(candidate)
                ) && !hasIndependentPropertySignal(candidate)
            if (isContactOnlyLine) {
                footerLines.add(0, candidate)
                bodyEndExclusive--
            } else {
                break
            }
        }

        val sharedFooter = footerLines.joinToString("\n").trim()
        val body = if (footerLines.isNotEmpty()) {
            lines.subList(0, bodyEndExclusive).joinToString("\n").trim()
        } else {
            normalized
        }

        // 1. Explicit numbered items / option headers / horizontal dividers
        val explicitMarkerRegex = Regex(
            """(?m)(?:\n\s*[-=*_]{3,}\s*\n|(?=(?:^|\n)\s*(?:option|property|unit|listing)\s*#?\s*\d+\s*[:.\-)])|(?=(?:^|\n)\s*(?:\*?\d{1,2}[.)]\*?|[1-9]\uFE0F?\u20E3)\s+\S))""",
            RegexOption.IGNORE_CASE
        )
        val explicitChunks = body.split(explicitMarkerRegex)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Check if chunks are real independent property blocks (filter out intro header like "Kharadi Options:")
        val propertyChunks = explicitChunks.filter { hasIndependentPropertySignal(it) }
        if (propertyChunks.size > 1) {
            val introHeader = explicitChunks.firstOrNull()?.takeIf { !hasIndependentPropertySignal(it) }.orEmpty()
            val headerLoc = extractLocalityHintOnly(introHeader)
            val enriched = propertyChunks.map { chunk ->
                if (headerLoc.isNotBlank() && extractLocalityHintOnly(chunk).isBlank()) {
                    "$chunk\nLocation: $headerLoc"
                } else {
                    chunk
                }
            }
            return sharedFooter to enriched
        }

        // 2. Blank-line separated blocks ONLY when >= 2 blocks each carry independent property signals
        val blankLineBlocks = body.split(Regex("""\n\s*\n+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val blankPropBlocks = blankLineBlocks.filter { hasIndependentPropertySignal(it) }
        if (blankPropBlocks.size > 1) {
            val introHeader = blankLineBlocks.firstOrNull()?.takeIf { !hasIndependentPropertySignal(it) }.orEmpty()
            val headerLoc = extractLocalityHintOnly(introHeader)
            val enriched = blankPropBlocks.map { chunk ->
                if (headerLoc.isNotBlank() && extractLocalityHintOnly(chunk).isBlank()) {
                    "$chunk\nLocation: $headerLoc"
                } else {
                    chunk
                }
            }
            return sharedFooter to enriched
        }

        // 3. Repeated asset-header lines where each resulting block has area or price
        val repeatedAssetBlocks = body.split(
            Regex("""(?im)(?=\n\s*(?:office|shop|showroom|warehouse|godown|[1-5](?:\.5)?\s*bhk|pre[\-\s]?leased|land|plot)\b)""")
        ).map { it.trim() }.filter { hasIndependentPropertySignal(it) }
        if (repeatedAssetBlocks.size > 1) {
            return sharedFooter to repeatedAssetBlocks
        }

        return sharedFooter to listOf(normalized)
    }

    private fun hasIndependentPropertySignal(chunk: String): Boolean {
        if (chunk.length < 10) return false
        val hasArea = Regex("""\d[\d,.]*\s*(?:sq\.?\s*ft|sqft|sft|guntha|gunthe|acre|acres|ca\b|bua\b|sba\b)""", RegexOption.IGNORE_CASE).containsMatchIn(chunk)
        val hasPrice = Regex("""(?:₹|rs\.?|inr|rent|price|sale|ask|quote|outright)?\s*\d+(?:\.\d+)?\s*(?:cr|crore|lac|lakh|l\b|k\b|hajar|koti)""", RegexOption.IGNORE_CASE).containsMatchIn(chunk)
        val hasAsset = Regex("""\b(office|shop|showroom|flat|apartment|bhk|villa|warehouse|godown|plot|land|pre[\-\s]?leased|dukan|jamin)\b""", RegexOption.IGNORE_CASE).containsMatchIn(chunk)
        return (hasArea && (hasPrice || hasAsset)) || (hasPrice && hasAsset)
    }

    private fun extractLocalityHintOnly(text: String): String {
        if (text.isBlank()) return ""
        return PUNE_LOCALITIES.firstOrNull { loc ->
            Regex("""\b${Regex.escape(loc)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }.orEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // STEP C: SINGLE ISOLATED SEGMENT EXTRACTION (ZERO FABRICATED DEFAULTS)
    // ─────────────────────────────────────────────────────────────────────────────

    internal fun parseIsolatedSegment(
        segmentText: String,
        sharedContactSnippet: String = "",
        senderName: String = "",
        senderPhone: String = "",
        parentClassification: WhatsAppMessageClassification = WhatsAppMessageClassification.PROPERTY_LISTING
    ): ExtractedPropertyDraft {
        val text = segmentText.trim()
        val lower = text.lowercase(Locale.US)
        val warnings = mutableListOf<String>()
        val fieldMap = LinkedHashMap<String, ExtractedField<String>>()

        // ── 1. Transaction & Asset Type Detection ──
        val isPreLeased = Regex("""\b(pre[\-\s]?leased|preleased|pl\b|leased\s+to\s+[a-z]|assured\s+roi|rental\s+yield)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val isUnderConstruction = Regex("""\b(under[\-\s]?construction|new\s+launch|uc\s+project|maharera|rera\s*(?:no|id|reg)?\s*[:#\-]?\s*P\d+|possession\s*(?:in|by|:)?\s*(?:dec|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov)?\s*202[7-9]|203\d)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val isWarehouse = Regex("""\b(warehouse|godown|industrial\s+shed|logistics\s+shed|\bshed\b)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val isLandOrPlot = Regex("""\b(guntha|gunthe|acre|acres|7/12|satbara|agricultural\s+land|na\s+plot|residential\s+plot|commercial\s+plot|open\s+plot|\bplot\b|\bland\b|jamin)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            text.contains("गुंठा") || text.contains("जमीन") || text.contains("सातबारा")
        val isShowroom = Regex("""\bshowroom\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val isShop = isShowroom || Regex("""\b(shop|retail|dukan)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) || text.contains("दुकान")
        val isOffice = Regex("""\b(office|it\s*park|commercial\s+space|business\s+(?:hub|park|center|centre)|workstations?|cabins?|\bws\b|\bcab\b|\bconf\b)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val bhkMatch = Regex("""(\d(?:\.5)?\s*BHK)""", RegexOption.IGNORE_CASE).find(text)
        val isResidentialAsset = bhkMatch != null ||
            Regex("""\b(flat|apartment|villa|bungalow|row\s*house|penthouse|residential)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            text.contains("फ्लॅट")

        val isRentalHint = Regex(
            """\b(for\s+(?:rent|lease)|on\s+(?:rent|lease)|avl\s+for\s+(?:rent|lease)|rent\b|lease\b|p\.?m\.?\b|/month|per\s+month|monthly|bhade|bhadya|bhadyane|kiraya|kiraye|mahina|mahinyala)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text) || text.contains("भाडे") || text.contains("भाड्याने")

        val isSaleHint = Regex(
            """\b(for\s+(?:sale|resale|outright)|on\s+sale|sale\b|resale\b|outright\b|sell\b|selling|vikri|vikne|vikaycha|bechna|asking\s+price|sale\s+price|per\s+guntha|per\s+acre)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text) || text.contains("विक्री")

        // Determine InventoryTab strictly from detected cues
        val tab: InventoryTab = when {
            isPreLeased -> InventoryTab.PRE_LEASED
            isUnderConstruction && (isResidentialAsset || !isOffice && !isShop && !isWarehouse) -> InventoryTab.UND_CONST
            isLandOrPlot && !isResidentialAsset -> if (isRentalHint && !isSaleHint) InventoryTab.OFF_LEASE else InventoryTab.OFF_OUT
            isLandOrPlot && isResidentialAsset -> if (isRentalHint && !isSaleHint) InventoryTab.RESI_RENT else InventoryTab.RESI_RESALE
            isWarehouse -> if (isSaleHint && !isRentalHint) InventoryTab.OFF_OUT else InventoryTab.OFF_LEASE
            isShop -> if (isSaleHint && !isRentalHint) InventoryTab.SHOP_OUT else InventoryTab.SHOP_LEASE
            isResidentialAsset && !isOffice -> when {
                isRentalHint && !isSaleHint -> InventoryTab.RESI_RENT
                else -> InventoryTab.RESI_RESALE
            }
            isOffice -> if (isSaleHint && !isRentalHint) InventoryTab.OFF_OUT else InventoryTab.OFF_LEASE
            isSaleHint && !isRentalHint -> InventoryTab.OFF_OUT
            else -> InventoryTab.OFF_LEASE
        }

        val propertyType: String = when {
            isWarehouse -> "Warehouse"
            isLandOrPlot -> if (isResidentialAsset) "Residential Plot" else "Commercial Land"
            isShowroom -> "Showroom"
            isShop -> "Shop"
            bhkMatch != null || lower.contains("flat") || lower.contains("apartment") -> "Apartment"
            lower.contains("villa") -> "Villa"
            lower.contains("bungalow") -> "Bungalow"
            isOffice -> "Office"
            isPreLeased -> "Pre-Leased Commercial"
            else -> "" // Never fabricate a property type if genuinely unspecified
        }
        val propertyTypeEvidence = Regex(
            """\b(warehouse|godown|shed|showroom|shop|dukan|office|apartment|flat|villa|bungalow|plot|land|jamin|pre[\-\s]?leased|\d(?:\.5)?\s*bhk)\b""",
            RegexOption.IGNORE_CASE
        ).find(text)?.value
        if (propertyType.isNotBlank()) {
            fieldMap["Property Type"] = ExtractedField(propertyType, propertyTypeEvidence ?: propertyType, 94, false)
        }

        // ── 2. Project / Building & Locality (Zero Fabricated Defaults) ──
        var projectEvidence: String? = null
        val matchedKnownProject = PUNE_PROJECTS.firstOrNull { candidate ->
            Regex("""\b${Regex.escape(candidate)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
        val labeledProjectMatch = Regex(
            """(?im)\b(?:project|building|bldg|society|tower|complex|park)\s*[:\-]\s*([^\n,|]{2,60})"""
        ).find(text)
        val atProjectMatch = if (matchedKnownProject == null && labeledProjectMatch == null) {
            Regex("""(?i)\b(?:at|in)\s+([A-Z][A-Za-z0-9&\s]{2,36}?(?:Towers?|Heights?|Residency|Park|Plaza|Hub|Center|Centre|Square|Arcade|Avenue|Greens?|Enclave|Point|Imperium|Business|Tech|Court|Palms?|Crest|Vista))\b""")
                .find(text)
        } else null

        val rawProject = matchedKnownProject
            ?: labeledProjectMatch?.groupValues?.get(1)?.trim()
            ?: atProjectMatch?.groupValues?.get(1)?.trim()
            ?: ""
        val projectName = sanitizeExtractedString(rawProject)
        projectEvidence = matchedKnownProject ?: labeledProjectMatch?.value?.trim() ?: atProjectMatch?.value?.trim()
        if (projectName.isNotBlank()) {
            val conf = if (matchedKnownProject != null || labeledProjectMatch != null) 95 else 82
            fieldMap["Project / Building"] = ExtractedField(projectName, projectEvidence, conf, conf < 70)
        }

        val matchedKnownLoc = PUNE_LOCALITIES.firstOrNull { loc ->
            Regex("""\b${Regex.escape(loc)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
        val labeledLocMatch = Regex(
            """(?im)\b(?:location|locality|micro[\-\s]?market|place)\s*[:\-]\s*([^\n,|]{2,45})"""
        ).find(text)
        val location = sanitizeExtractedString(matchedKnownLoc ?: labeledLocMatch?.groupValues?.get(1).orEmpty())
        if (location.isNotBlank()) {
            val locEv = matchedKnownLoc ?: labeledLocMatch?.value?.trim()
            fieldMap["Locality"] = ExtractedField(location, locEv, if (matchedKnownLoc != null) 96 else 85, false)
        }

        // ── 3. Unit Number & BHK ──
        val unitMatch = Regex(
            """(?im)\b(?:unit|office|shop|flat|suite|gala)\s*(?:no\.?|number|#)?\s*[:\-#]?\s*([A-Z]?\d{1,5}[A-Z]?)(?!\s*(?:sq|sft|bhk|ws|cab|conf|lac|lakh|cr|k|month|yr|year|floor|majla|guntha|acre))\b"""
        ).find(text)
        val unitNumber = sanitizeExtractedString(unitMatch?.groupValues?.get(1))
        if (unitNumber.isNotBlank()) {
            fieldMap["Unit / Shop No"] = ExtractedField(unitNumber, unitMatch?.value?.trim(), 92, false)
        }

        val bhkConfig = bhkMatch?.groupValues?.get(1)?.uppercase(Locale.US)?.replace(Regex("\\s+"), " ").orEmpty()
        if (bhkConfig.isNotBlank()) {
            fieldMap["Configuration"] = ExtractedField(bhkConfig, bhkMatch?.value?.trim(), 96, false)
        }

        // ── 4. Context-Aware Pune Shorthands: Floor vs Furnishing (FF) & Workstations vs Warm Shell (WS) ──
        val explicitFurnishingMatches = mutableListOf<Pair<FurnishingType, String>>()
        Regex("""\bfully[\-\s]+furnished\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            explicitFurnishingMatches.add(FurnishingType.FULLY_FURNISHED to it.value)
        }
        Regex("""\b(?:semi[\-\s]+furnished|\bsf\b)\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            // Avoid matching SF inside "1200 sf" (sq.ft)
            val beforeIdx = (it.range.first - 6).coerceAtLeast(0)
            val prefixWindow = text.substring(beforeIdx, it.range.first)
            if (!Regex("""\d\s*$""").containsMatchIn(prefixWindow)) {
                explicitFurnishingMatches.add(FurnishingType.SEMI_FURNISHED to it.value)
            }
        }
        Regex("""\b(?:bare[\-\s]+shell|\bbs\b)\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            explicitFurnishingMatches.add(FurnishingType.BARE_SHELL to it.value)
        }
        Regex("""\bwarm[\-\s]+shell\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            explicitFurnishingMatches.add(FurnishingType.WARM_SHELL to it.value)
        }
        Regex("""\bunfurnished\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            explicitFurnishingMatches.add(FurnishingType.UNFURNISHED to it.value)
        }

        // Workstations (numeric WS) vs Warm Shell (non-numeric WS)
        val numericWsMatch = Regex(
            """(?i)(?:(\d+)\+?\s*(?:workstations?|seats?|w/s|ws\b)|\b(?:workstations?|ws)\s*[:\-]\s*(\d+))"""
        ).find(text)
        val workstations = numericWsMatch?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()?.takeIf { it > 0 }
        }
        if (workstations != null) {
            fieldMap["Workstations"] = ExtractedField(workstations.toString(), numericWsMatch.value.trim(), 95, false)
        }

        // Standalone WS (NOT preceded/followed by a workstation count) -> Warm Shell
        val standaloneWsMatches = Regex("""\bws\b""", RegexOption.IGNORE_CASE).findAll(text).filter { m ->
            val before = text.substring((m.range.first - 10).coerceAtLeast(0), m.range.first)
            val after = text.substring(m.range.last + 1, (m.range.last + 10).coerceAtMost(text.length))
            val precededByCount = Regex("""\d+\+?\s*$""").containsMatchIn(before)
            val followedByCount = Regex("""^\s*[:\-]\s*\d+""").containsMatchIn(after)
            !precededByCount && !followedByCount
        }.toList()
        if (standaloneWsMatches.isNotEmpty() && explicitFurnishingMatches.none { it.first == FurnishingType.WARM_SHELL }) {
            explicitFurnishingMatches.add(FurnishingType.WARM_SHELL to standaloneWsMatches.first().value)
        }

        // Cabins & Conference Rooms
        val cabinsMatch = Regex("""(?i)(?:(\d+)\s*(?:cabins?|cab\b)|\b(?:cabins?|cab)\s*[:\-]\s*(\d+))""").find(text)
        val cabins = cabinsMatch?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()?.takeIf { it > 0 } }
        if (cabins != null) {
            fieldMap["Cabins"] = ExtractedField(cabins.toString(), cabinsMatch.value.trim(), 94, false)
        }

        val confMatch = Regex("""(?i)(?:(\d+)\s*(?:conference\s*rooms?|conf\.?\s*rooms?|conf\b)|\b(?:conf\.?\s*rooms?|conference)\s*[:\-]\s*(\d+))""").find(text)
        val confRooms = confMatch?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()?.takeIf { it > 0 } }
            ?: if (Regex("""\b(?:conference\s*room|conf\.?\s*room)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) 1 else null
        if (confRooms != null) {
            fieldMap["Conference Rooms"] = ExtractedField(confRooms.toString(), confMatch?.value?.trim() ?: "Conference Room", 90, false)
        }

        // Explicit Floor extraction (excluding FF first so we can disambiguate FF accurately)
        var floor = ""
        var floorEvidence: String? = null
        var floorConfidence = 0
        var floorNeedsReview = false

        val gfPlusFfMatch = Regex("""\b(gf\s*\+\s*ff|ground\s*\+\s*first\s*(?:floor)?)\b""", RegexOption.IGNORE_CASE).find(text)
        val explicitFloorMatch = Regex(
            """(?im)(?:\b(\d+(?:st|nd|rd|th)?|ground|upper\s+ground|lower\s+ground|mezzanine|mezz|top|higher|lower|pahila|dusra|tisra)\s+(?:floor|flr|majla)\b|\bfloor\s*[:\-]\s*([^\n,|]{1,24})|\b(ugf|ug|lgf|gf)\b)"""
        ).find(text)

        if (gfPlusFfMatch != null) {
            floor = "Ground + First Floor"
            floorEvidence = gfPlusFfMatch.value
            floorConfidence = 95
        } else if (explicitFloorMatch != null) {
            val g2 = explicitFloorMatch.groupValues[2].trim()
            val g1 = explicitFloorMatch.groupValues[1].trim()
            val g3 = explicitFloorMatch.groupValues[3].trim()
            floorEvidence = explicitFloorMatch.value.trim()
            floor = when {
                g2.isNotBlank() -> normalizeFloorLabel(g2)
                g1.isNotBlank() -> normalizeFloorLabel(g1)
                g3.isNotBlank() -> normalizeFloorLabel(g3)
                else -> ""
            }
            floorConfidence = 93
        }

        // Disambiguate `FF` (First Floor vs Fully Furnished)
        val ffMatches = Regex("""\bff\b""", RegexOption.IGNORE_CASE).findAll(text).toList()
        var furnishingConfidence = if (explicitFurnishingMatches.isNotEmpty()) 94 else 0
        var furnishingNeedsReview = false
        var furnishingEvidence = explicitFurnishingMatches.firstOrNull()?.second
        var resolvedFurnishing: FurnishingType? = explicitFurnishingMatches.firstOrNull()?.first

        if (ffMatches.isNotEmpty() && gfPlusFfMatch == null) {
            val ffHit = ffMatches.first()
            val windowStart = (ffHit.range.first - 20).coerceAtLeast(0)
            val windowEnd = (ffHit.range.last + 24).coerceAtMost(text.length)
            val contextWindow = text.substring(windowStart, windowEnd)

            val strongFloorContext = Regex(
                """(?i)(?:\b(?:on|at|floor\s*[:\-]?|level|gf\s*[/+&]|ug\s*[/+&])\s*ff\b|\bff\s*(?:shop|showroom|facing|frontage|level|floor|[/+&]\s*(?:gf|ug|sf))\b)"""
            ).containsMatchIn(contextWindow) ||
                (resolvedFurnishing != null && resolvedFurnishing != FurnishingType.FULLY_FURNISHED && floor.isBlank())

            val strongFurnContext = Regex(
                """(?i)(?:\b(?:furnishing|status|condition)\s*[:\-]?\s*ff\b|\bff\s*(?:with|office|flat|apartment|ready|ac|pantry|workstation|ws|cab|seat)|\b(?:bhk|office|flat)\s+ff\b)"""
            ).containsMatchIn(contextWindow) ||
                floor.isNotBlank() || workstations != null || cabins != null || confRooms != null

            when {
                strongFloorContext && !strongFurnContext -> {
                    floor = "First Floor"
                    floorEvidence = contextWindow.trim()
                    floorConfidence = 92
                }
                strongFurnContext && !strongFloorContext -> {
                    if (resolvedFurnishing == null) {
                        resolvedFurnishing = FurnishingType.FULLY_FURNISHED
                        furnishingEvidence = contextWindow.trim()
                        furnishingConfidence = 92
                    }
                }
                else -> {
                    // Genuine ambiguity — flag clearly for human confirmation
                    if (isShop && floor.isBlank()) {
                        floor = "First Floor"
                        floorEvidence = contextWindow.trim()
                        floorConfidence = 60
                        floorNeedsReview = true
                    } else if (resolvedFurnishing == null) {
                        resolvedFurnishing = FurnishingType.FULLY_FURNISHED
                        furnishingEvidence = contextWindow.trim()
                        furnishingConfidence = 60
                        furnishingNeedsReview = true
                    }
                    warnings.add("Shorthand 'FF' can mean First Floor or Fully Furnished — please confirm.")
                }
            }
        }

        if (floor.isNotBlank()) {
            fieldMap["Floor"] = ExtractedField(floor, floorEvidence, floorConfidence, floorNeedsReview)
        }
        val hasExplicitFurnishing = resolvedFurnishing != null
        if (resolvedFurnishing != null) {
            fieldMap["Furnishing"] = ExtractedField(resolvedFurnishing.label, furnishingEvidence, furnishingConfidence, furnishingNeedsReview)
        }

        // ── 5. Area Extraction (STRICT: Never substitute Carpet for Built-up or Saleable; Preserve Land Units) ──
        var landAreaValue: Double? = null
        var landAreaUnit = ""
        val landMatch = Regex(
            """(\d[\d,.]*)\s*(gunthas?|gunthe|acres?|\bR\b|hectares?)\b""",
            RegexOption.IGNORE_CASE
        ).find(text) ?: Regex("""(\d[\d,.]*)\s*(गुंठा|गुंठे|एकर)""").find(text)
        if (landMatch != null) {
            val v = landMatch.groupValues[1].replace(",", "").toDoubleOrNull()
            if (v != null && v > 0.0) {
                landAreaValue = v
                val rawUnit = landMatch.groupValues[2].lowercase(Locale.US)
                landAreaUnit = when {
                    rawUnit.startsWith("gunth") || rawUnit == "r" || rawUnit.contains("गुंठ") -> "Guntha"
                    rawUnit.startsWith("acre") || rawUnit.contains("एकर") -> "Acre"
                    rawUnit.startsWith("hect") -> "Hectare"
                    else -> landMatch.groupValues[2]
                }
                fieldMap["Land Area"] = ExtractedField(
                    value = "${formatCleanNumber(v)} $landAreaUnit",
                    evidence = landMatch.value.trim(),
                    confidence = 95,
                    needsReview = false
                )
            }
        }

        // Explicit Saleable / Super Built-up Area (SBA)
        val sbaMatch = Regex(
            """(?i)(?:(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)?\s*(?:super\s*built[\-\s]?up|sba\b|saleable(?:\s*area)?|chargeable(?:\s*area)?)|(?:super\s*built[\-\s]?up|sba|saleable|chargeable)(?:\s*area)?\s*[:\-]?\s*(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)?)"""
        ).find(text)
        val saleableArea = sbaMatch?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 }
        }
        if (saleableArea != null) {
            fieldMap["Saleable / SBA"] = ExtractedField("${formatCleanNumber(saleableArea)} sq.ft", sbaMatch.value.trim(), 94, false)
        }

        // Explicit Built-up Area (BU / BUA) — excludes "Super Built-up"
        val buaMatch = Regex(
            """(?i)(?:(?<!super\s)(?:(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)?\s*(?:built[\-\s]?up|bua\b|bu\b)|(?<!super\s)(?:built[\-\s]?up|bua|\bbu)(?:\s*area)?\s*[:\-]?\s*(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)?))"""
        ).find(text)
        val builtUpArea = buaMatch?.let { m ->
            val v = (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 }
            if (v != null && v != saleableArea) v else null
        }
        if (builtUpArea != null) {
            fieldMap["Built-up Area"] = ExtractedField("${formatCleanNumber(builtUpArea)} sq.ft", buaMatch.value.trim(), 94, false)
        }

        // Explicit Carpet Area (CA / Carpet)
        val carpetMatch = Regex(
            """(?i)(?:(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)?\s*(?:carpet(?:\s*area)?|ca\b)|(?:carpet(?:\s*area)?|\bca)\s*[:\-]?\s*(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)?)"""
        ).find(text)
        val carpetExplicit = carpetMatch?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 }
        }

        var carpetArea = 0.0
        if (carpetExplicit != null) {
            carpetArea = carpetExplicit
            fieldMap["Carpet Area"] = ExtractedField("${formatCleanNumber(carpetExplicit)} sq.ft", carpetMatch.value.trim(), 96, false)
        } else if (isLandOrPlot && landAreaValue == null) {
            val plotSqftMatch = Regex("""(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)\b""", RegexOption.IGNORE_CASE).find(text)
            val plotSqft = plotSqftMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0.0 }
            if (plotSqft != null) {
                landAreaValue = plotSqft
                landAreaUnit = "Sq.Ft"
                fieldMap["Land Area"] = ExtractedField("${formatCleanNumber(plotSqft)} Sq.Ft", plotSqftMatch.value.trim(), 90, false)
            }
        } else if (builtUpArea == null && saleableArea == null && landAreaValue == null) {
            // Unqualified sq.ft when NO BU/SBA was given: keep with needsReview = true so user confirms Carpet vs BUA
            val genericSqftMatch = Regex("""(\d[\d,.]*)\s*(?:sq\.?\s*ft|sqft|sft)\b""", RegexOption.IGNORE_CASE).find(text)
            val genericVal = genericSqftMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0.0 }
            if (genericVal != null) {
                carpetArea = genericVal
                fieldMap["Carpet Area"] = ExtractedField(
                    value = "${formatCleanNumber(genericVal)} sq.ft",
                    evidence = genericSqftMatch.value.trim(),
                    confidence = 68,
                    needsReview = true
                )
                warnings.add("Area '${genericSqftMatch.value.trim()}' did not specify Carpet vs Built-up — please confirm.")
            }
        }
        // NOTE: If ONLY Built-up or Saleable area was mentioned, carpetArea stays 0.0 (NEVER substituted!)

        // ── 6. Financial Extraction (Normalized Indian Numbers + Ambiguity & Conflict Detection) ──
        var rentVal: Double? = null
        var rentEvidence: String? = null
        var rentConfidence = 0
        var rentNeedsReview = false

        var saleVal: Double? = null
        var saleEvidence: String? = null
        var saleConfidence = 0
        var saleNeedsReview = false

        var depositVal: Double? = null
        var depositEvidence: String? = null
        var depositConfidence = 0
        var depositNeedsReview = false

        var roiVal: Double? = null
        var isPriceConflictOrUpdate = parentClassification == WhatsAppMessageClassification.EXISTING_PROPERTY_UPDATE

        // Detect all tagged rent occurrences (to catch conflicting/revised rents!)
        val taggedRentMatches = Regex(
            """(?i)\b(?:revised\s+rent|new\s+rent|updated\s+rent|monthly\s+rent|rent|lease|bhade|mahina|kiraya)\s*(?:is|was|now|revised\s+to|reduced\s+to)?\s*[:\-@]?\s*(?:₹|rs\.?|inr)?\s*(\d[\d,.]*)\s*(cr|crore|crores|koti|lac|lakh|lakhs|lacs|l\b|k\b|thousand|hajar)?(?:\s*/\s*(?:sqft|sq\.?\s*ft|sft))?"""
        ).findAll(text).toList()

        // Also catch "<amount> rent" pattern like "2.55k rent" or "1.2L rent"
        val postfixRentMatches = Regex(
            """(?i)(?:₹|rs\.?|inr)?\s*(\d[\d,.]*)\s*(cr|crore|crores|koti|lac|lakh|lakhs|lacs|l|k|thousand|hajar)\s*(?:p\.?m\.?\s*)?(?:rent|monthly\s+rent|lease|bhade|kiraya)\b"""
        ).findAll(text).toList()

        val parsedRentCandidates = mutableListOf<Triple<Double, String, Boolean>>() // (amount, evidence, isPerSqFt)
        for (m in taggedRentMatches) {
            val rawFull = m.value.trim()
            val isPerSqFt = Regex("""/\s*(?:sqft|sq\.?\s*ft|sft)""", RegexOption.IGNORE_CASE).containsMatchIn(rawFull)
            val parsed = parseScaledRupees(m.groupValues[1], m.groupValues[2])
            if (parsed != null && parsed > 0.0) {
                parsedRentCandidates.add(Triple(parsed, rawFull, isPerSqFt))
            }
        }
        for (m in postfixRentMatches) {
            val rawFull = m.value.trim()
            val parsed = parseScaledRupees(m.groupValues[1], m.groupValues[2])
            if (parsed != null && parsed > 0.0 && parsedRentCandidates.none { it.first == parsed }) {
                parsedRentCandidates.add(Triple(parsed, rawFull, false))
            }
        }

        if (parsedRentCandidates.isNotEmpty()) {
            // Check for conflicting/multiple distinct rents
            val distinctRents = parsedRentCandidates.filter { !it.third }.map { it.first }.distinct()
            val chosen = parsedRentCandidates.last() // latest figure if revised (e.g. "was 1.2L, now 1.05L")
            if (chosen.third) {
                // Per sq.ft rent
                val areaBase = carpetArea.takeIf { it > 0.0 } ?: builtUpArea ?: saleableArea
                if (areaBase != null && areaBase > 0.0) {
                    rentVal = chosen.first * areaBase
                    rentEvidence = "${chosen.second} × ${formatCleanNumber(areaBase)} sq.ft"
                    rentConfidence = 78
                    rentNeedsReview = true
                    warnings.add("Monthly rent computed from per-sq.ft rate (${chosen.second}) — verify total.")
                } else {
                    rentEvidence = chosen.second
                    rentConfidence = 55
                    rentNeedsReview = true
                    warnings.add("Rent given as per-sq.ft rate (${chosen.second}) without area — enter total rent manually.")
                }
            } else {
                rentVal = chosen.first
                rentEvidence = chosen.second
                rentConfidence = 94
                if (distinctRents.size > 1) {
                    isPriceConflictOrUpdate = true
                    rentConfidence = 55
                    rentNeedsReview = true
                    warnings.add(
                        "Conflicting/revised rent values found: ${
                            distinctRents.joinToString(" vs ") { "₹${formatCleanNumber(it)}" }
                        } — confirm active rent."
                    )
                }
            }
        }

        // Detect all tagged sale price occurrences
        val taggedSaleMatches = Regex(
            """(?i)\b(?:revised\s+price|new\s+price|updated\s+price|sale\s+price|asking\s+price|expected\s+price|price|ask|asking|quote|outright|sale|vikri|cost|rate)\s*(?:is|was|now|revised\s+to|reduced\s+to)?\s*[:\-@]?\s*(?:₹|rs\.?|inr)?\s*(\d[\d,.]*)\s*(cr|crore|crores|koti|lac|lakh|lakhs|lacs|l\b|k\b|thousand|hajar)?(?:\s*/\s*(?:guntha|acre|sqft|sq\.?\s*ft))?"""
        ).findAll(text).toList()

        val parsedSaleCandidates = mutableListOf<Triple<Double, String, String>>() // (amount, evidence, perUnit)
        for (m in taggedSaleMatches) {
            val rawFull = m.value.trim()
            val perUnit = Regex("""/\s*(guntha|acre|sqft|sq\.?\s*ft)""", RegexOption.IGNORE_CASE).find(rawFull)?.groupValues?.get(1).orEmpty()
            val parsed = parseScaledRupees(m.groupValues[1], m.groupValues[2])
            if (parsed != null && parsed > 0.0) {
                // Skip tiny numbers without scale unless per-unit rate (e.g. "price 1" typo)
                if (parsed >= 1_000.0 || perUnit.isNotBlank()) {
                    parsedSaleCandidates.add(Triple(parsed, rawFull, perUnit))
                }
            }
        }

        if (parsedSaleCandidates.isNotEmpty()) {
            val distinctSales = parsedSaleCandidates.filter { it.third.isBlank() }.map { it.first }.distinct()
            val chosen = parsedSaleCandidates.last()
            if (chosen.third.isNotBlank()) {
                val perLower = chosen.third.lowercase(Locale.US)
                val multiplier = when {
                    perLower == "guntha" && landAreaUnit.equals("Guntha", true) -> landAreaValue
                    perLower == "acre" && landAreaUnit.equals("Acre", true) -> landAreaValue
                    perLower.startsWith("sq") -> carpetArea.takeIf { it > 0.0 } ?: landAreaValue?.takeIf { landAreaUnit.equals("Sq.Ft", true) }
                    else -> null
                }
                if (multiplier != null && multiplier > 0.0) {
                    saleVal = chosen.first * multiplier
                    saleEvidence = "${chosen.second} × ${formatCleanNumber(multiplier)}"
                    saleConfidence = 82
                    saleNeedsReview = true
                } else {
                    saleVal = chosen.first
                    saleEvidence = chosen.second
                    saleConfidence = 60
                    saleNeedsReview = true
                    warnings.add("Price stated per ${chosen.third} (${chosen.second}) — confirm total price.")
                }
            } else {
                // Guard: if this is a rental listing and "Ask: 85k" was captured, route to rent when rent is null
                if (tab.isRental && !isPreLeased && chosen.first < 1_000_000.0 && rentVal == null) {
                    rentVal = chosen.first
                    rentEvidence = chosen.second
                    rentConfidence = 88
                } else {
                    saleVal = chosen.first
                    saleEvidence = chosen.second
                    saleConfidence = 94
                    if (distinctSales.size > 1) {
                        isPriceConflictOrUpdate = true
                        saleConfidence = 55
                        saleNeedsReview = true
                        warnings.add(
                            "Conflicting/revised sale prices found: ${
                                distinctSales.joinToString(" vs ") { "₹${formatCleanNumber(it)}" }
                            } — confirm active price."
                        )
                    }
                }
            }
        }

        // Untagged fallback amounts (only if neither tagged rent nor tagged sale matched for the primary transaction)
        if ((tab.isRental || isPreLeased) && rentVal == null) {
            val untaggedRent = Regex(
                """(?i)(?:₹|rs\.?|inr)\s*(\d[\d,.]*)\s*(lac|lakh|lakhs|lacs|l|k|thousand|hajar)?\b|\b(\d+(?:\.\d+)?)\s*(k|thousand|hajar|lac|lakh|l)\s*(?:/m|pm|p\.m\.|per\s*month|monthly)\b"""
            ).find(text)
            if (untaggedRent != null) {
                val num = untaggedRent.groupValues[1].ifBlank { untaggedRent.groupValues[3] }
                val unit = untaggedRent.groupValues[2].ifBlank { untaggedRent.groupValues[4] }
                val parsed = parseScaledRupees(num, unit)
                if (parsed != null && parsed > 0.0 && (!isPreLeased || parsed < 5_000_000.0)) {
                    rentVal = parsed
                    rentEvidence = untaggedRent.value.trim()
                    rentConfidence = 80
                }
            }
        }
        if ((!tab.isRental || isPreLeased) && saleVal == null) {
            val untaggedCr = Regex("""(?i)(?:₹|rs\.?|inr)?\s*(\d+(?:\.\d+)?)\s*(cr|crore|crores|koti)\b""").find(text)
            val untaggedLac = if (untaggedCr == null && !tab.isRental) {
                Regex("""(?i)(?:₹|rs\.?|inr)?\s*(\d+(?:\.\d+)?)\s*(lac|lakh|lakhs|lacs|l)\b""").find(text)
            } else null
            val hit = untaggedCr ?: untaggedLac
            if (hit != null) {
                val parsed = parseScaledRupees(hit.groupValues[1], hit.groupValues[2])
                if (parsed != null && parsed > 0.0) {
                    saleVal = parsed
                    saleEvidence = hit.value.trim()
                    saleConfidence = if (untaggedCr != null) 88 else 78
                }
            }
        }

        // Ambiguous decimal-k rent check (e.g., "2.55k rent" -> ₹2,550 likely typo for 2.55L or 25.5k!)
        val ambiguousDecimalK = Regex("""(?i)\b(\d+\.\d+)\s*k\b""").find(text)
        if (ambiguousDecimalK != null && rentVal != null && rentVal!! < 10_000.0) {
            rentConfidence = 45
            rentNeedsReview = true
            warnings.add(
                "Ambiguous amount '${ambiguousDecimalK.value}' (parsed as ₹${formatCleanNumber(rentVal!!)}) — possible typo for Lac or higher rent; confirm before saving."
            )
        } else if (rentVal != null && rentVal!! < 5_000.0) {
            rentConfidence = 50
            rentNeedsReview = true
            warnings.add("Unusually low monthly rent (₹${formatCleanNumber(rentVal!!)}) — please confirm.")
        }

        if (rentVal != null) {
            fieldMap["Monthly Rent"] = ExtractedField("₹${formatCleanNumber(rentVal!!)}", rentEvidence, rentConfidence, rentNeedsReview)
        }
        if (saleVal != null) {
            fieldMap["Sale Price"] = ExtractedField("₹${formatCleanNumber(saleVal!!)}", saleEvidence, saleConfidence, saleNeedsReview)
        }

        // Security Deposit (Dep / SD / Security Deposit — NEVER fabricate 6 months!)
        val depositMatch = Regex(
            """(?i)(?:\b(?:sec(?:urity)?\.?\s*dep(?:osit)?|deposit|dep|sd)\s*[:\-]?\s*(?:₹|rs\.?|inr)?\s*(\d[\d,.]*)\s*(cr|crore|lac|lakh|lacs|l\b|k\b|months?|mahine|m\b)?|(\d+(?:\.\d+)?)\s*(?:months?|mahine|m)\s*(?:sec(?:urity)?\.?\s*dep(?:osit)?|deposit|dep|sd)\b)"""
        ).find(text)
        if (depositMatch != null) {
            val numStr = depositMatch.groupValues[1].ifBlank { depositMatch.groupValues[3] }
            val unitStr = if (depositMatch.groupValues[3].isNotBlank()) "months" else depositMatch.groupValues[2].lowercase(Locale.US)
            depositEvidence = depositMatch.value.trim()
            if (unitStr.startsWith("month") || unitStr == "mahine" || unitStr == "m") {
                val months = numStr.toDoubleOrNull() ?: 0.0
                if (months > 0.0 && rentVal != null && rentVal!! > 0.0) {
                    depositVal = months * rentVal!!
                    depositConfidence = if (rentNeedsReview) 60 else 92
                    depositNeedsReview = rentNeedsReview
                } else if (months > 0.0) {
                    depositConfidence = 75
                    depositNeedsReview = true
                    fieldMap["Deposit"] = ExtractedField("${formatCleanNumber(months)} months", depositEvidence, 75, true)
                }
            } else {
                depositVal = parseScaledRupees(numStr, unitStr)
                depositConfidence = 92
            }
            if (depositVal != null) {
                fieldMap["Deposit"] = ExtractedField("₹${formatCleanNumber(depositVal!!)}", depositEvidence, depositConfidence, depositNeedsReview)
            }
        }

        // ROI / Yield %
        val roiMatch = Regex("""(?i)(?:(\d+(?:\.\d+)?)\s*%\s*(?:roi|yield|return)|(?:roi|yield)\s*[:\-]?\s*(\d+(?:\.\d+)?)\s*%?)""").find(text)
        roiVal = roiMatch?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).toDoubleOrNull()?.takeIf { it in 0.5..35.0 } }
        if (roiVal != null) {
            fieldMap["ROI %"] = ExtractedField("${roiVal}%", roiMatch?.value?.trim(), 94, false)
        }

        // ── 7. Possession, Parking, Lock-in, Escalation, CAM, Negotiable, Tenant, 7/12 ──
        val imdMatch = Regex("""(?i)\b(?:poss(?:ession)?\s*[:\-]?\s*)?(?:immediate|imd\b|imm\b|ready\s+to\s+move|rtm\b|ready\s+possession|turant)\b""").find(text)
            ?: Regex("""तत्काळ""").find(text)
        val datedPossMatch = Regex("""(?im)\b(?:poss(?:ession)?)\s*[:\-]?\s*([^\n,|]{2,28})""").find(text)
        val possession = when {
            imdMatch != null -> "Immediate"
            datedPossMatch != null -> sanitizeExtractedString(datedPossMatch.groupValues[1])
            else -> "" // NEVER invent "Immediate" if unmentioned!
        }
        if (possession.isNotBlank()) {
            fieldMap["Possession"] = ExtractedField(possession, (imdMatch ?: datedPossMatch)?.value?.trim(), 92, false)
        }

        val parkingMatch = Regex("""(?i)(\d+\s*(?:covered|open|car|2w|4w|reserved)?\s*(?:car\s*)?park(?:ing)?s?)""").find(text)
        val parking = parkingMatch?.groupValues?.get(1)?.trim().orEmpty()
        if (parking.isNotBlank()) {
            fieldMap["Parking"] = ExtractedField(parking, parkingMatch?.value?.trim(), 92, false)
        }

        val lockInMatch = Regex("""(?i)\b(?:lock[\-\s]?in|li)\s*[:\-]?\s*(\d+\s*(?:months?|mos?|m\b|years?|yrs?|y\b))""").find(text)
        val lockInMonths = lockInMatch?.groupValues?.get(1)?.trim().orEmpty()
        if (lockInMonths.isNotBlank()) {
            fieldMap["Lock-in"] = ExtractedField(lockInMonths, lockInMatch?.value?.trim(), 92, false)
        }

        val escMatch = Regex("""(?i)\b(?:escalation|esc)\s*[:\-]?\s*(\d+(?:\.\d+)?%\s*(?:yearly|p\.?a\.?|per\s*annum|every\s*\d+\s*yrs?)?)""").find(text)
        val escalationTerms = escMatch?.groupValues?.get(1)?.trim().orEmpty()
        if (escalationTerms.isNotBlank()) {
            fieldMap["Escalation"] = ExtractedField(escalationTerms, escMatch?.value?.trim(), 92, false)
        }

        val camMatch = Regex("""(?i)\b(?:cam|maint(?:enance)?)\s*[:\-]?\s*([^\n,|]{2,26})""").find(text)
        val maintenanceTerms = sanitizeExtractedString(camMatch?.groupValues?.get(1))
        if (maintenanceTerms.isNotBlank()) {
            fieldMap["CAM / Maintenance"] = ExtractedField(maintenanceTerms, camMatch?.value?.trim(), 90, false)
        }

        val negoMatch = Regex("""\b(negotiable|nego|neg)\b""", RegexOption.IGNORE_CASE).find(text)
        val negotiable = if (negoMatch != null) true else null
        if (negotiable == true) {
            fieldMap["Negotiable"] = ExtractedField("Yes", negoMatch?.value, 94, false)
        }

        val sevenTwelveMatch = Regex("""(?i)(?:7/12|satbara|सातबारा)""").find(text)
        val isSevenTwelveClear = if (sevenTwelveMatch != null) true else null
        if (isSevenTwelveClear == true) {
            fieldMap["Land Title"] = ExtractedField("7/12 Extract Mentioned", sevenTwelveMatch?.value, 92, false)
        }

        val tenantMatch = Regex("""(?im)\b(?:tenant|leased\s+to|mnc\s+tenant|brand)\s*[:\-]?\s*([A-Za-z0-9&\s\.]{2,36})""").find(text)
        val tenantName = sanitizeExtractedString(tenantMatch?.groupValues?.get(1)?.substringBefore(",")?.trim())
        if (tenantName.isNotBlank()) {
            fieldMap["Tenant"] = ExtractedField(tenantName, tenantMatch?.value?.trim(), 88, false)
        }

        val builderMatch = Regex("""(?im)\b(?:builder|developer|by)\s*[:\-]?\s*([A-Za-z0-9&\s\.]{2,36})""").find(text)
        val builderName = sanitizeExtractedString(builderMatch?.groupValues?.get(1)?.substringBefore(",")?.trim())

        // ── 8. Contact Extraction: Separate Owner vs Source / Broker (NEVER assume sender is Owner) ──
        val combinedForContact = if (sharedContactSnippet.isNotBlank()) "$text\n$sharedContactSnippet" else text
        val allPhones = Regex("""(?:\+?91[\-\s]?)?([6-9]\d{9})\b""")
            .findAll(combinedForContact)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        val ownerLabelLine = Regex("""(?im)^.*?\b(?:direct\s+owner|owner|landlord|malak|मालक)\b.*$""").find(combinedForContact)?.value
        val brokerLabelLine = Regex("""(?im)^.*?\b(?:broker|agent|cp\b|channel\s*partner|realtor|associates|properties|realty|s2s|side\s*by\s*side|shared\s+by|regards)\b.*$""").find(combinedForContact)?.value

        var ownerName = ""
        var ownerPhone = ""
        var sourceBrokerName = ""
        var sourceBrokerPhone = ""

        if (ownerLabelLine != null) {
            ownerPhone = Regex("""(?:\+?91[\-\s]?)?([6-9]\d{9})\b""").find(ownerLabelLine)?.groupValues?.get(1).orEmpty()
            ownerName = Regex("""(?i)\b(?:direct\s+owner|owner|landlord|malak)\s*[:\-]?\s*([A-Za-z][A-Za-z\s\.]{1,28})""")
                .find(ownerLabelLine)?.groupValues?.get(1)?.trim()
                ?.takeIf { !it.equals("property", true) && !it.equals("direct", true) }.orEmpty()
            // If owner line has no phone on that line, check if there's only 1 phone and no broker signal
            if (ownerPhone.isBlank() && allPhones.size == 1 && brokerLabelLine == null) {
                ownerPhone = allPhones.first()
            }
        }

        if (brokerLabelLine != null) {
            sourceBrokerPhone = Regex("""(?:\+?91[\-\s]?)?([6-9]\d{9})\b""").find(brokerLabelLine)?.groupValues?.get(1).orEmpty()
            sourceBrokerName = Regex("""(?i)\b(?:broker|agent|cp|channel\s*partner|shared\s+by|regards|contact|call)\s*[:\-,]?\s*([A-Za-z][A-Za-z\s\.]{1,28})""")
                .find(brokerLabelLine)?.groupValues?.get(1)?.trim().orEmpty()
        }

        // Any unlabeled phone defaults strictly to Source/Broker (NEVER Owner!)
        val remainingPhones = allPhones.filter { it != ownerPhone && it != sourceBrokerPhone }
        if (sourceBrokerPhone.isBlank() && remainingPhones.isNotEmpty()) {
            sourceBrokerPhone = remainingPhones.first()
            val unlabeledName = Regex("""(?im)\b(?:contact|call|poc|name)\s*[:\-]?\s*([A-Za-z][A-Za-z\s\.]{1,28})""")
                .find(combinedForContact)?.groupValues?.get(1)?.trim().orEmpty()
            if (sourceBrokerName.isBlank()) sourceBrokerName = unlabeledName
        }

        // If WhatsApp sender metadata was passed, keep it ONLY as Source/Broker fallback — NEVER Owner!
        val cleanSenderPhone = normalizeIndianPhone(senderPhone)
        if (sourceBrokerPhone.isBlank() && cleanSenderPhone.isNotBlank() && cleanSenderPhone != ownerPhone) {
            sourceBrokerPhone = cleanSenderPhone
        }
        if (sourceBrokerName.isBlank() && senderName.isNotBlank() && senderName != ownerName) {
            sourceBrokerName = senderName.trim()
        }

        val additionalPhones = allPhones.filter { it != ownerPhone && it != sourceBrokerPhone }
        if (allPhones.size > 1) {
            warnings.add("Multiple phone numbers detected (${allPhones.joinToString(", ")}) — verify Owner vs Broker contact.")
        }

        if (ownerPhone.isNotBlank() || ownerName.isNotBlank()) {
            val disp = listOf(ownerName, ownerPhone).filter { it.isNotBlank() }.joinToString(" • ")
            fieldMap["Owner Contact"] = ExtractedField(disp, ownerLabelLine?.trim() ?: disp, 90, false)
        }
        if (sourceBrokerPhone.isNotBlank() || sourceBrokerName.isNotBlank()) {
            val disp = listOf(sourceBrokerName, sourceBrokerPhone).filter { it.isNotBlank() }.joinToString(" • ")
            val isExplicitBroker = brokerLabelLine != null
            fieldMap["Source / Broker"] = ExtractedField(
                value = disp,
                evidence = brokerLabelLine?.trim() ?: disp,
                confidence = if (isExplicitBroker) 92 else 75,
                needsReview = !isExplicitBroker
            )
        }

        val primaryPrice = when {
            isPreLeased -> saleVal ?: rentVal ?: 0.0
            tab.isRental -> rentVal ?: 0.0
            else -> saleVal ?: 0.0
        }

        val legacyContactName = ownerName.ifBlank { sourceBrokerName }
        val legacyContactPhone = ownerPhone.ifBlank { sourceBrokerPhone }

        val baseDraft = ExtractedPropertyDraft(
            tab = tab,
            propertyType = propertyType,
            subType = tab.code,
            projectName = projectName,
            location = location,
            microMarket = location,
            unitNumber = unitNumber,
            floor = floor,
            bhkConfiguration = bhkConfig,
            carpetAreaSqFt = carpetArea,
            builtUpAreaSqFt = builtUpArea,
            saleableAreaSqFt = saleableArea,
            landAreaValue = landAreaValue,
            landAreaUnit = landAreaUnit,
            priceOrRent = primaryPrice,
            priceSale = saleVal,
            rent = rentVal,
            deposit = depositVal,
            roiPercent = roiVal,
            furnishing = resolvedFurnishing ?: FurnishingType.UNFURNISHED,
            possession = possession,
            parking = parking,
            lockInMonths = lockInMonths,
            escalationTerms = escalationTerms,
            maintenanceTerms = maintenanceTerms,
            workstations = workstations,
            cabins = cabins,
            conferenceRooms = confRooms,
            tenantName = tenantName,
            negotiable = negotiable,
            isSevenTwelveClear = isSevenTwelveClear,
            ownerName = ownerName,
            ownerPhone = ownerPhone,
            sourceBrokerName = sourceBrokerName,
            sourceBrokerPhone = sourceBrokerPhone,
            additionalPhones = additionalPhones,
            contactName = legacyContactName,
            contactPhone = legacyContactPhone,
            remarks = text,
            confidenceNotes = "Parsed safely via Build 01 Extraction Engine",
            builderName = builderName,
            rawSnippet = text,
            hasExplicitFurnishing = hasExplicitFurnishing,
            isPriceConflictOrUpdate = isPriceConflictOrUpdate,
            reviewWarnings = warnings,
            extractedFields = fieldMap
        )

        return baseDraft.copy(missingFields = computeMissingFields(baseDraft))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // GEMINI JSON PARSER (WITH ZERO-FABRICATION SANITIZATION)
    // ─────────────────────────────────────────────────────────────────────────────

    fun parseGeminiBatchJson(
        cleanedJson: String,
        rawMessage: String,
        senderName: String = "",
        senderPhone: String = ""
    ): WhatsAppExtractionBatchResult? {
        return runCatching {
            val root = JSONObject(cleanedJson)
            val classification = WhatsAppMessageClassification.fromString(root.optString("classification", ""))
            val classConf = root.optInt("classificationConfidence", 80).coerceIn(0, 100)
            val classReason = root.optString("classificationReason", "").trim()

            if (classification == WhatsAppMessageClassification.CLIENT_REQUIREMENT ||
                classification == WhatsAppMessageClassification.UNRELATED
            ) {
                return WhatsAppExtractionBatchResult(
                    classification = classification,
                    classificationConfidence = classConf,
                    classificationReason = classReason.ifBlank { classification.label },
                    drafts = emptyList(),
                    rawMessage = rawMessage.trim(),
                    usedFallbackParser = false
                )
            }

            val propsArray = root.optJSONArray("properties") ?: JSONArray()
            val drafts = mutableListOf<ExtractedPropertyDraft>()
            for (i in 0 until propsArray.length()) {
                val obj = propsArray.optJSONObject(i) ?: continue
                drafts.add(parseGeminiPropertyObject(obj, rawMessage, senderName, senderPhone, classification))
            }

            if (drafts.isEmpty()) return null

            WhatsAppExtractionBatchResult(
                classification = classification,
                classificationConfidence = classConf,
                classificationReason = classReason.ifBlank { "Extracted via Gemini 2.5 Flash" },
                drafts = drafts,
                rawMessage = rawMessage.trim(),
                usedFallbackParser = false
            )
        }.getOrNull()
    }

    private fun parseGeminiPropertyObject(
        obj: JSONObject,
        rawMessage: String,
        senderName: String,
        senderPhone: String,
        classification: WhatsAppMessageClassification
    ): ExtractedPropertyDraft {
        val fieldMap = LinkedHashMap<String, ExtractedField<String>>()
        val warnings = mutableListOf<String>()

        fun readStringField(key: String, label: String): String {
            val raw = obj.opt(key)
            return when (raw) {
                is JSONObject -> {
                    val v = sanitizeExtractedString(raw.optString("value", ""))
                    val ev = sanitizeExtractedString(raw.optString("evidence", ""))
                    val conf = raw.optInt("confidence", if (v.isNotBlank()) 85 else 0).coerceIn(0, 100)
                    val review = raw.optBoolean("needsReview", conf < 70)
                    if (v.isNotBlank()) {
                        fieldMap[label] = ExtractedField(v, ev.ifBlank { null }, conf, review)
                    }
                    v
                }
                else -> {
                    val v = sanitizeExtractedString(obj.optString(key, ""))
                    if (v.isNotBlank()) fieldMap[label] = ExtractedField(v, null, 80, false)
                    v
                }
            }
        }

        fun readDoubleField(key: String, label: String, prefix: String = "", suffix: String = ""): Double? {
            val raw = obj.opt(key)
            return when (raw) {
                is JSONObject -> {
                    val num = if (raw.has("value") && !raw.isNull("value")) raw.optDouble("value", Double.NaN) else Double.NaN
                    val valid = num.takeIf { it.isFinite() && it > 0.0 }
                    if (valid != null) {
                        val ev = sanitizeExtractedString(raw.optString("evidence", ""))
                        val conf = raw.optInt("confidence", 85).coerceIn(0, 100)
                        val review = raw.optBoolean("needsReview", conf < 70)
                        fieldMap[label] = ExtractedField("$prefix${formatCleanNumber(valid)}$suffix", ev.ifBlank { null }, conf, review)
                    }
                    valid
                }
                else -> {
                    val num = if (obj.has(key) && !obj.isNull(key)) obj.optDouble(key, Double.NaN) else Double.NaN
                    val valid = num.takeIf { it.isFinite() && it > 0.0 }
                    if (valid != null) {
                        fieldMap[label] = ExtractedField("$prefix${formatCleanNumber(valid)}$suffix", null, 80, false)
                    }
                    valid
                }
            }
        }

        fun readIntField(key: String, label: String): Int? = readDoubleField(key, label)?.toInt()?.takeIf { it > 0 }

        val tabStr = readStringField("tab", "Category")
        val matchedTab = InventoryTab.entries.find { it.code.equals(tabStr, ignoreCase = true) } ?: InventoryTab.OFF_LEASE
        val propType = readStringField("propertyType", "Property Type")
        val projName = readStringField("projectName", "Project / Building")
        val loc = readStringField("location", "Locality")
        val micro = sanitizeExtractedString(obj.optString("microMarket", ""))
        val unit = readStringField("unitNumber", "Unit / Shop No")
        val floor = readStringField("floor", "Floor")
        val bhk = readStringField("bhkConfiguration", "Configuration")
        val carpet = readDoubleField("carpetAreaSqFt", "Carpet Area", suffix = " sq.ft") ?: 0.0
        val bua = readDoubleField("builtUpAreaSqFt", "Built-up Area", suffix = " sq.ft")
        val sba = readDoubleField("saleableAreaSqFt", "Saleable / SBA", suffix = " sq.ft")
        val landVal = readDoubleField("landAreaValue", "Land Area")
        val landUnit = sanitizeExtractedString(obj.optString("landAreaUnit", ""))
        if (landVal != null && landUnit.isNotBlank()) {
            val prev = fieldMap["Land Area"]
            fieldMap["Land Area"] = ExtractedField(
                value = "${formatCleanNumber(landVal)} $landUnit",
                evidence = prev?.evidence,
                confidence = prev?.confidence ?: 90,
                needsReview = prev?.needsReview ?: false
            )
        }

        val rent = readDoubleField("rent", "Monthly Rent", prefix = "₹")
        val sale = readDoubleField("priceSale", "Sale Price", prefix = "₹")
        val deposit = readDoubleField("deposit", "Deposit", prefix = "₹")
        val roi = readDoubleField("roiPercent", "ROI %", suffix = "%")
        val furnStr = readStringField("furnishing", "Furnishing")
        val hasExplicitFurn = furnStr.isNotBlank()
        val matchedFurn = FurnishingType.entries.find { it.label.equals(furnStr, ignoreCase = true) } ?: FurnishingType.UNFURNISHED
        val poss = readStringField("possession", "Possession")
        val parking = readStringField("parking", "Parking")
        val lockIn = readStringField("lockInMonths", "Lock-in")
        val esc = readStringField("escalationTerms", "Escalation")
        val cam = readStringField("maintenanceTerms", "CAM / Maintenance")
        val ws = readIntField("workstations", "Workstations")
        val cab = readIntField("cabins", "Cabins")
        val conf = readIntField("conferenceRooms", "Conference Rooms")
        val tenant = readStringField("tenantName", "Tenant")

        val ownerName = sanitizeExtractedString(obj.optString("ownerName", ""))
        val ownerPhone = normalizeIndianPhone(obj.optString("ownerPhone", ""))
        var brokerName = sanitizeExtractedString(obj.optString("sourceBrokerName", ""))
        var brokerPhone = normalizeIndianPhone(obj.optString("sourceBrokerPhone", ""))
        if (brokerPhone.isBlank()) {
            val sp = normalizeIndianPhone(senderPhone)
            if (sp.isNotBlank() && sp != ownerPhone) brokerPhone = sp
        }
        if (brokerName.isBlank() && senderName.isNotBlank() && senderName != ownerName) {
            brokerName = senderName.trim()
        }

        if (ownerPhone.isNotBlank() || ownerName.isNotBlank()) {
            fieldMap["Owner Contact"] = ExtractedField(
                listOf(ownerName, ownerPhone).filter { it.isNotBlank() }.joinToString(" • "),
                null, 90, false
            )
        }
        if (brokerPhone.isNotBlank() || brokerName.isNotBlank()) {
            fieldMap["Source / Broker"] = ExtractedField(
                listOf(brokerName, brokerPhone).filter { it.isNotBlank() }.joinToString(" • "),
                null, 80, false
            )
        }

        val warnArr = obj.optJSONArray("reviewWarnings")
        if (warnArr != null) {
            for (w in 0 until warnArr.length()) {
                val msg = warnArr.optString(w, "").trim()
                if (msg.isNotBlank()) warnings.add(msg)
            }
        }

        // Extra sanity check: flag low rent < 5000 even if Gemini didn't flag it
        if (rent != null && rent < 5_000.0) {
            fieldMap["Monthly Rent"] = fieldMap["Monthly Rent"]?.copy(confidence = 45, needsReview = true)
                ?: ExtractedField("₹${formatCleanNumber(rent)}", null, 45, true)
            warnings.add("Unusually low monthly rent (₹${formatCleanNumber(rent)}) — confirm before saving.")
        }

        val primaryPrice = if (matchedTab == InventoryTab.PRE_LEASED) (sale ?: rent ?: 0.0)
        else if (matchedTab.isRental) (rent ?: 0.0) else (sale ?: 0.0)

        val snippet = sanitizeExtractedString(obj.optString("rawSnippet", "")).ifBlank { rawMessage.trim() }

        val draft = ExtractedPropertyDraft(
            tab = matchedTab,
            propertyType = propType,
            subType = matchedTab.code,
            projectName = projName,
            location = loc.ifBlank { micro },
            microMarket = micro.ifBlank { loc },
            unitNumber = unit,
            floor = floor,
            bhkConfiguration = bhk,
            carpetAreaSqFt = carpet,
            builtUpAreaSqFt = bua,
            saleableAreaSqFt = sba,
            landAreaValue = landVal,
            landAreaUnit = landUnit,
            priceOrRent = primaryPrice,
            priceSale = sale,
            rent = rent,
            deposit = deposit,
            roiPercent = roi,
            furnishing = matchedFurn,
            possession = poss,
            parking = parking,
            lockInMonths = lockIn,
            escalationTerms = esc,
            maintenanceTerms = cam,
            workstations = ws,
            cabins = cab,
            conferenceRooms = conf,
            tenantName = tenant,
            ownerName = ownerName,
            ownerPhone = ownerPhone,
            sourceBrokerName = brokerName,
            sourceBrokerPhone = brokerPhone,
            contactName = ownerName.ifBlank { brokerName },
            contactPhone = ownerPhone.ifBlank { brokerPhone },
            remarks = sanitizeExtractedString(obj.optString("remarks", "")).ifBlank { snippet },
            confidenceNotes = "Extracted via Gemini 2.5 Flash (Build 01 Safe Schema)",
            builderName = sanitizeExtractedString(obj.optString("builderName", "")),
            rawSnippet = snippet,
            hasExplicitFurnishing = hasExplicitFurn,
            isPriceConflictOrUpdate = classification == WhatsAppMessageClassification.EXISTING_PROPERTY_UPDATE,
            reviewWarnings = warnings,
            extractedFields = fieldMap
        )
        return draft.copy(missingFields = computeMissingFields(draft))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────────

    fun computeMissingFields(draft: ExtractedPropertyDraft): List<String> = buildList {
        val isLand = draft.propertyType.contains("Land", true) || draft.propertyType.contains("Plot", true) || draft.landAreaUnit.isNotBlank()
        if (!isLand && draft.projectName.isBlank()) add("Project / Building")
        if (draft.location.isBlank()) add("Locality / Micro-Market")
        if (!isLand && draft.carpetAreaSqFt <= 0.0) add("Carpet Area")
        if (isLand && (draft.landAreaValue ?: 0.0) <= 0.0) add("Land Area")
        if (draft.tab == InventoryTab.PRE_LEASED) {
            if ((draft.priceSale ?: 0.0) <= 0.0) add("Sale Price")
            if ((draft.rent ?: 0.0) <= 0.0) add("Monthly Rent")
        } else if (draft.tab.isRental) {
            if ((draft.rent ?: 0.0) <= 0.0) add("Monthly Rent")
        } else {
            if ((draft.priceSale ?: 0.0) <= 0.0) add("Sale Price")
        }
        if (draft.ownerPhone.isBlank() && draft.sourceBrokerPhone.isBlank()) add("Contact Mobile")
    }

    fun sanitizeExtractedString(value: String?): String {
        val cleaned = value?.trim().orEmpty()
        if (cleaned.isBlank()) return ""
        return if (cleaned.lowercase(Locale.US) in FORBIDDEN_PLACEHOLDERS) "" else cleaned
    }

    fun normalizeIndianPhone(raw: String?): String {
        val digits = raw.orEmpty().filter { it.isDigit() }
        return when {
            digits.length == 10 && digits.first() in '6'..'9' -> digits
            digits.length > 10 -> digits.takeLast(10).takeIf { it.first() in '6'..'9' }.orEmpty()
            else -> ""
        }
    }

    fun parseScaledRupees(numStr: String, unitStr: String): Double? {
        val base = numStr.replace(",", "").toDoubleOrNull() ?: return null
        if (!base.isFinite() || base <= 0.0) return null
        return when (unitStr.lowercase(Locale.US).trim()) {
            "cr", "crore", "crores", "koti" -> base * 10_000_000.0
            "lac", "lakh", "lakhs", "lacs", "l" -> base * 100_000.0
            "k", "thousand", "hajar" -> base * 1_000.0
            else -> base
        }
    }

    private fun normalizeFloorLabel(raw: String): String {
        val clean = raw.trim()
        val lower = clean.lowercase(Locale.US)
        return when {
            lower == "gf" || lower == "ground" || lower == "ground floor" -> "Ground Floor"
            lower == "ug" || lower == "ugf" || lower == "upper ground" -> "Upper Ground Floor"
            lower == "lgf" || lower == "lower ground" -> "Lower Ground Floor"
            lower == "ff" || lower == "first" || lower == "1st" || lower == "pahila" -> "First Floor"
            lower == "dusra" -> "2nd Floor"
            lower == "tisra" -> "3rd Floor"
            lower.startsWith("mezz") -> "Mezzanine"
            lower.endsWith("floor") -> clean.replaceFirstChar { it.uppercase() }
            else -> "$clean Floor"
        }
    }

    private fun formatCleanNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}
