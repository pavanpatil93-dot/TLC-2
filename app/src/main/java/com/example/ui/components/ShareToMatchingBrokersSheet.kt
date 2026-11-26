package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crm.CrmCallManager
import com.example.data.OfflineDiskCache
import com.example.data.master.BrokerEntity
import com.example.data.master.MasterProjectDatabase
import com.example.model.BrokerVendorRecord
import com.example.model.PropertyItem
import com.example.model.StandardPortfolio
import com.example.model.effectivePortfolio
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class MatchingBrokerCandidate(
    val id: String,
    val name: String,
    val mobileLast10: String,
    val firmName: String,
    val division: String = "",
    val operatingAreas: String,
    val specialization: String = "",
    val confidence: String = "",
    val isAreaMatch: Boolean,
    val isDivisionMatch: Boolean = false,
    val isSpecializationMatch: Boolean = false,
    val matchScore: Int,
    val matchReasons: List<String> = emptyList()
)

/**
 * Loads Brokers / Channel Partners from Room DB (`brokers_master`, synced from
 * `Commercial Brokers` + `Residential Brokers` tabs), plus fallback caches
 * (`OfflineDiskCache` and `CrmCallManager`), deduplicates by 10-digit mobile,
 * and ranks by Division, Location / Micro-Market, Specialization, and Confidence.
 */
object MatchingBrokerResolver {

    private val SPECIALIZATION_KEYWORDS = listOf(
        "office", "it", "shop", "showroom", "retail", "warehouse", "industrial",
        "land", "plot", "pre-leased", "preleased", "pre leased", "lease", "rent",
        "rental", "resale", "sale", "buy", "commercial", "residential", "luxury",
        "villa", "bungalow", "flat", "apartment", "bhk", "coworking", "building"
    )

    fun resolveMatchingBrokers(
        property: PropertyItem,
        roomBrokers: List<BrokerEntity> = emptyList(),
        brokersVendors: List<BrokerVendorRecord> = emptyList(),
        callBrokerNamesByPhone: Map<String, String> = emptyMap()
    ): List<MatchingBrokerCandidate> {
        val isPropResidential = property.effectivePortfolio() == StandardPortfolio.RESIDENTIAL ||
            property.tab.name.contains("RESIDENTIAL", ignoreCase = true)
        val expectedDivision = if (isPropResidential) "Residential" else "Commercial"

        val targetLocationTokens = buildSet {
            property.location.lowercase(Locale.ENGLISH).split(",", " ", "/", "-", "(", ")")
                .map { it.trim() }.filter { it.length >= 3 && it != "pune" }.forEach { add(it) }
            property.effectiveMicroMarket?.lowercase(Locale.ENGLISH)
                ?.split(",", " ", "/", "-", "(", ")")
                ?.map { it.trim() }?.filter { it.length >= 3 && it != "pune" }?.forEach { add(it) }
        }

        val propSpecHaystack = listOf(
            property.propertyType,
            property.tab.name,
            property.transactionType,
            property.bhk.orEmpty()
        ).joinToString(" ").lowercase(Locale.ENGLISH)
        val targetSpecTokens = SPECIALIZATION_KEYWORDS.filter { propSpecHaystack.contains(it) }.toSet()

        val byMobile = LinkedHashMap<String, MatchingBrokerCandidate>()

        fun scoreAndInsert(
            id: String,
            name: String,
            rawPhone: String,
            firmName: String,
            division: String,
            operatingAreas: String,
            specialization: String,
            confidence: String,
            notes: String
        ) {
            val digits = rawPhone.filter { it.isDigit() }
            if (digits.length < 10) return
            val last10 = digits.takeLast(10)

            val divNorm = division.trim()
            val isDivisionMatch = when {
                divNorm.equals(expectedDivision, ignoreCase = true) -> true
                divNorm.contains(expectedDivision, ignoreCase = true) -> true
                divNorm.contains("Both", ignoreCase = true) || divNorm.contains("All", ignoreCase = true) -> true
                else -> false
            }
            val isOppositeDivision = divNorm.isNotBlank() && !isDivisionMatch &&
                (divNorm.contains("Commercial", true) || divNorm.contains("Residential", true))

            val areaHaystack = "$operatingAreas $notes $firmName".lowercase(Locale.ENGLISH)
            val matchedAreaTokens = targetLocationTokens.count { token -> areaHaystack.contains(token) }
            val isAllPune = areaHaystack.contains("all pune") || areaHaystack.contains("pan pune")
            val isAreaMatch = matchedAreaTokens > 0 || isAllPune

            val specHaystack = "$specialization $notes $firmName".lowercase(Locale.ENGLISH)
            val matchedSpecTokens = targetSpecTokens.count { token -> specHaystack.contains(token) }
            val isSpecMatch = matchedSpecTokens > 0

            val confLower = confidence.trim().lowercase(Locale.ENGLISH)
            val confNumeric = confLower.filter { it.isDigit() }.toIntOrNull()
            val confidenceScore = when {
                confLower.contains("high") || confLower.contains("verified") || confLower == "a" || confLower == "a+" ||
                    (confNumeric != null && confNumeric >= 80) -> 35
                confLower.contains("med") || confLower == "b" ||
                    (confNumeric != null && confNumeric in 50..79) -> 18
                confLower.isNotBlank() -> 6
                else -> 0
            }

            var score = 0
            score += when {
                isDivisionMatch -> 80
                isOppositeDivision -> -40
                else -> 15
            }
            score += when {
                matchedAreaTokens > 0 -> 100 + (matchedAreaTokens * 25)
                isAllPune -> 45
                else -> 0
            }
            score += if (isSpecMatch) 55 + ((matchedSpecTokens - 1).coerceAtLeast(0) * 15) else 0
            score += confidenceScore

            val reasons = buildList {
                if (matchedAreaTokens > 0) add("Area Match") else if (isAllPune) add("All Pune")
                if (isSpecMatch) add("Spec Match")
                if (confidenceScore >= 35) add("High Conf")
            }

            val candidate = MatchingBrokerCandidate(
                id = id,
                name = name.ifBlank { "Broker ($last10)" },
                mobileLast10 = last10,
                firmName = firmName.trim(),
                division = divNorm,
                operatingAreas = operatingAreas.trim(),
                specialization = specialization.trim(),
                confidence = confidence.trim(),
                isAreaMatch = isAreaMatch,
                isDivisionMatch = isDivisionMatch,
                isSpecializationMatch = isSpecMatch,
                matchScore = score,
                matchReasons = reasons
            )

            val existing = byMobile[last10]
            if (existing == null || candidate.matchScore > existing.matchScore) {
                byMobile[last10] = candidate
            }
        }

        // 1. Primary source: Room DB `brokers_master` (synced from Commercial Brokers & Residential Brokers)
        roomBrokers.forEach { entity ->
            scoreAndInsert(
                id = entity.brokerId,
                name = entity.name,
                rawPhone = entity.phone.ifBlank { entity.mobileLast10 },
                firmName = entity.firmName,
                division = entity.division,
                operatingAreas = entity.operatingAreas,
                specialization = entity.specialization,
                confidence = entity.confidence,
                notes = entity.notes
            )
        }

        // 2. Fallback / Supplement: Disk cache Brokers & Vendors
        brokersVendors
            .filter { bv ->
                bv.roleCategory.contains("Broker", ignoreCase = true) ||
                    bv.roleCategory.contains("CP", ignoreCase = true) ||
                    bv.subRole.contains("Channel Partner", ignoreCase = true) ||
                    bv.subRole.contains("Broker", ignoreCase = true)
            }
            .forEach { bv ->
                val inferredDivision = when {
                    bv.subRole.contains("Residential", true) || bv.notes.contains("Residential", true) -> "Residential"
                    bv.subRole.contains("Commercial", true) || bv.notes.contains("Commercial", true) -> "Commercial"
                    else -> ""
                }
                scoreAndInsert(
                    id = bv.id,
                    name = bv.name,
                    rawPhone = bv.phone,
                    firmName = bv.firmName,
                    division = inferredDivision,
                    operatingAreas = bv.operatingAreas,
                    specialization = bv.subRole,
                    confidence = "",
                    notes = bv.notes
                )
            }

        // 3. Fallback: Call-tagged broker contacts
        callBrokerNamesByPhone.forEach { (rawPhone, rawName) ->
            val digits = rawPhone.filter { it.isDigit() }
            if (digits.length < 10) return@forEach
            val last10 = digits.takeLast(10)
            if (!byMobile.containsKey(last10)) {
                byMobile[last10] = MatchingBrokerCandidate(
                    id = "CALL-BRK-$last10",
                    name = rawName.ifBlank { "Broker ($last10)" },
                    mobileLast10 = last10,
                    firmName = "",
                    division = "",
                    operatingAreas = "",
                    specialization = "",
                    confidence = "",
                    isAreaMatch = false,
                    isDivisionMatch = false,
                    isSpecializationMatch = false,
                    matchScore = 10,
                    matchReasons = emptyList()
                )
            }
        }

        return byMobile.values.sortedWith(
            compareByDescending<MatchingBrokerCandidate> { it.matchScore }
                .thenBy { it.name.lowercase(Locale.ENGLISH) }
        )
    }
}

/**
 * Ranked Matching Brokers Sheet with Multi-Select Batch WhatsApp Share.
 * Loads Room-synced Commercial & Residential Brokers, ranks by Division,
 * Location/Micro-Market, Specialization, and Confidence, and provides:
 * - Multi-select checkboxes + Select Top 5 / Top 10 batch selection
 * - Guided sequential Batch WhatsApp Dispatch (`Send Next via WhatsApp (X/N)`)
 * - 1-tap Call, Direct WA, and Photo+WA per broker row
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareToMatchingBrokersSheet(
    property: PropertyItem,
    unifiedMessage: String,
    photoSources: List<String>,
    onDismiss: () -> Unit,
    onTriggerSyncBrokers: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isPropResidential = remember(property) {
        property.effectivePortfolio() == StandardPortfolio.RESIDENTIAL ||
            property.tab.name.contains("RESIDENTIAL", ignoreCase = true)
    }
    val propDivisionLabel = if (isPropResidential) "Residential" else "Commercial"

    var searchQuery by remember { mutableStateOf("") }
    var onlyAreaMatches by remember { mutableStateOf(false) }
    var onlyDivisionMatches by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var selectedBrokerMobiles by remember(property.id) { mutableStateOf(setOf<String>()) }
    var sentBrokerMobiles by remember(property.id) { mutableStateOf(setOf<String>()) }
    var includePhotosInBatch by remember(property.id, photoSources) { mutableStateOf(false) }
    var preparingPhotoForBroker by remember { mutableStateOf<String?>(null) }
    var isBatchPreparingPhoto by remember { mutableStateOf(false) }
    var feedbackError by remember { mutableStateOf<String?>(null) }

    var allBrokers by remember(property.id) { mutableStateOf<List<MatchingBrokerCandidate>>(emptyList()) }
    var isLoadingBrokers by remember(property.id) { mutableStateOf(true) }

    LaunchedEffect(property.id) {
        isLoadingBrokers = true
        val resolved = withContext(Dispatchers.IO) {
            val roomBrokers = runCatching {
                MasterProjectDatabase.getDatabase(context).masterProjectDao().getAllBrokersSync()
            }.getOrDefault(emptyList())
            val diskBrokers = runCatching { OfflineDiskCache.loadBrokersVendors(context) }.getOrDefault(emptyList())
            val callBrokers = runCatching {
                CrmCallManager.getBrokerContacts(context).mapValues { it.value.name }
            }.getOrDefault(emptyMap())
            MatchingBrokerResolver.resolveMatchingBrokers(
                property = property,
                roomBrokers = roomBrokers,
                brokersVendors = diskBrokers,
                callBrokerNamesByPhone = callBrokers
            )
        }
        allBrokers = resolved
        isLoadingBrokers = false
    }

    val areaMatchCount = remember(allBrokers) { allBrokers.count { it.isAreaMatch } }
    val divisionMatchCount = remember(allBrokers) { allBrokers.count { it.isDivisionMatch } }

    val visibleBrokers = remember(allBrokers, searchQuery, onlyAreaMatches, onlyDivisionMatches) {
        allBrokers.filter { broker ->
            val matchesAreaFilter = !onlyAreaMatches || broker.isAreaMatch
            val matchesDivisionFilter = !onlyDivisionMatches || broker.isDivisionMatch
            val q = searchQuery.trim()
            val matchesQuery = q.isBlank() ||
                broker.name.contains(q, ignoreCase = true) ||
                broker.firmName.contains(q, ignoreCase = true) ||
                broker.operatingAreas.contains(q, ignoreCase = true) ||
                broker.specialization.contains(q, ignoreCase = true) ||
                broker.division.contains(q, ignoreCase = true) ||
                broker.mobileLast10.contains(q)
            matchesAreaFilter && matchesDivisionFilter && matchesQuery
        }
    }

    // Ordered queue of selected brokers (respecting ranked order)
    val selectedOrderedBrokers = remember(allBrokers, selectedBrokerMobiles) {
        allBrokers.filter { it.mobileLast10 in selectedBrokerMobiles }
    }
    val selectedSentCount = remember(selectedOrderedBrokers, sentBrokerMobiles) {
        selectedOrderedBrokers.count { it.mobileLast10 in sentBrokerMobiles }
    }
    val nextUnsentSelectedBroker = remember(selectedOrderedBrokers, sentBrokerMobiles) {
        selectedOrderedBrokers.firstOrNull { it.mobileLast10 !in sentBrokerMobiles }
    }

    fun dispatchToBroker(broker: MatchingBrokerCandidate, withPhotos: Boolean, isFromBatch: Boolean = false) {
        feedbackError = null
        if (withPhotos && photoSources.isNotEmpty()) {
            if (isFromBatch) {
                if (isBatchPreparingPhoto) return
                isBatchPreparingPhoto = true
            } else {
                if (preparingPhotoForBroker == broker.mobileLast10) return
                preparingPhotoForBroker = broker.mobileLast10
            }
            scope.launch {
                val uris = WhatsAppShareHelper.prepareShareablePhotoUris(context, photoSources)
                if (isFromBatch) isBatchPreparingPhoto = false else preparingPhotoForBroker = null
                val ok = WhatsAppShareHelper.shareToWhatsApp(
                    context = context,
                    message = unifiedMessage,
                    photoUris = uris,
                    recipientPhone = broker.mobileLast10
                ) { err -> feedbackError = err }
                if (ok) {
                    sentBrokerMobiles = sentBrokerMobiles + broker.mobileLast10
                }
            }
        } else {
            val ok = WhatsAppShareHelper.launchDirectBrokerWaChat(
                context = context,
                mobileNumber = broker.mobileLast10,
                message = unifiedMessage
            ) { err -> feedbackError = err }
            if (ok) {
                sentBrokerMobiles = sentBrokerMobiles + broker.mobileLast10
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundSoft,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextPrimary) },
        modifier = Modifier.testTag("share_matching_brokers_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Matching Brokers • $propDivisionLabel",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val locationSummary = property.location.ifBlank { property.effectiveMicroMarket ?: "Pune" }
                        Text(
                            text = "${allBrokers.size} Synced ($areaMatchCount Area Match) • $locationSummary",
                            color = SecondaryText,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("brokers_sheet_close_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Collapsible Unified Message Preview
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (photoSources.isNotEmpty()) {
                                    "Unified Share Message • ${photoSources.size} Photo(s) Ready"
                                } else {
                                    "Unified Share Message"
                                },
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = { showPreview = !showPreview },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = if (showPreview) "Hide" else "Preview",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryActionOrange
                                )
                                Icon(
                                    imageVector = if (showPreview) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = PrimaryActionOrange,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (showPreview) {
                            HorizontalDivider(color = BorderGrey.copy(alpha = 0.6f))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = unifiedMessage,
                                color = TextPrimary,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search broker, firm, area, specialization...", fontSize = 12.5.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("brokers_sheet_search_input"),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite,
                        focusedBorderColor = FocusOutlineColor,
                        unfocusedBorderColor = BorderGrey
                    )
                )

                Spacer(Modifier.height(8.dp))

                // Quick filter + multi-select chips row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (areaMatchCount > 0) {
                        FilterChip(
                            selected = onlyAreaMatches,
                            onClick = { onlyAreaMatches = !onlyAreaMatches },
                            label = {
                                Text(
                                    text = "Area Match ($areaMatchCount)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            modifier = Modifier.testTag("brokers_filter_area_chip")
                        )
                    }
                    if (divisionMatchCount > 0) {
                        FilterChip(
                            selected = onlyDivisionMatches,
                            onClick = { onlyDivisionMatches = !onlyDivisionMatches },
                            label = {
                                Text(
                                    text = "$propDivisionLabel ($divisionMatchCount)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            modifier = Modifier.testTag("brokers_filter_division_chip")
                        )
                    }
                    if (visibleBrokers.isNotEmpty()) {
                        AssistChip(
                            onClick = {
                                val top5 = visibleBrokers.take(5).map { it.mobileLast10 }.toSet()
                                selectedBrokerMobiles = selectedBrokerMobiles + top5
                            },
                            label = { Text("Select Top 5", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.testTag("brokers_select_top5_chip")
                        )
                        if (visibleBrokers.size > 5) {
                            AssistChip(
                                onClick = {
                                    val top10 = visibleBrokers.take(10).map { it.mobileLast10 }.toSet()
                                    selectedBrokerMobiles = selectedBrokerMobiles + top10
                                },
                                label = { Text("Select Top 10", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.testTag("brokers_select_top10_chip")
                            )
                        }
                    }
                    if (selectedBrokerMobiles.isNotEmpty()) {
                        AssistChip(
                            onClick = { selectedBrokerMobiles = emptySet() },
                            label = { Text("Clear (${selectedBrokerMobiles.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatusDangerRed) },
                            modifier = Modifier.testTag("brokers_clear_selection_chip")
                        )
                    }
                }

                if (!feedbackError.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = feedbackError.orEmpty(),
                        color = StatusDangerRed,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(8.dp))

                when {
                    isLoadingBrokers -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = PrimaryActionOrange)
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "Ranking matching brokers...",
                                    color = SecondaryText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    visibleBrokers.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(horizontal = 20.dp)
                            ) {
                                Text(
                                    text = if (allBrokers.isEmpty()) {
                                        "No brokers synced yet from Commercial Brokers & Residential Brokers tabs."
                                    } else {
                                        "No brokers match your current filter."
                                    },
                                    color = SecondaryText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (allBrokers.isEmpty() && onTriggerSyncBrokers != null) {
                                    Button(
                                        onClick = {
                                            onTriggerSyncBrokers()
                                            onDismiss()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = PrimaryActionOrange,
                                            contentColor = OnPrimaryActionColor
                                        ),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("brokers_empty_sync_now_btn")
                                    ) {
                                        Text("Sync Brokers Now", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("brokers_ranked_list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            items(visibleBrokers, key = { it.mobileLast10 }) { broker ->
                                val isSelected = broker.mobileLast10 in selectedBrokerMobiles
                                val isSent = broker.mobileLast10 in sentBrokerMobiles
                                val isPreparingPhoto = preparingPhotoForBroker == broker.mobileLast10
                                BrokerShareRowCard(
                                    broker = broker,
                                    isSelected = isSelected,
                                    isSent = isSent,
                                    hasPhotos = photoSources.isNotEmpty(),
                                    isPreparingPhoto = isPreparingPhoto,
                                    onToggleSelect = {
                                        selectedBrokerMobiles = if (isSelected) {
                                            selectedBrokerMobiles - broker.mobileLast10
                                        } else {
                                            selectedBrokerMobiles + broker.mobileLast10
                                        }
                                    },
                                    onCallBroker = {
                                        runCatching {
                                            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+91${broker.mobileLast10}"))
                                            context.startActivity(dialIntent)
                                        }.onFailure { feedbackError = "Unable to open phone dialer" }
                                    },
                                    onSendDirectWa = { dispatchToBroker(broker, withPhotos = false) },
                                    onShareWithPhoto = { dispatchToBroker(broker, withPhotos = true) }
                                )
                            }
                        }
                    }
                }
            }

            // Sticky Bottom Batch Share Bar (shown when 1+ brokers are selected)
            AnimatedVisibility(
                visible = selectedBrokerMobiles.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = SurfaceWhite,
                    tonalElevation = 4.dp,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("brokers_batch_share_bar")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${selectedOrderedBrokers.size} Broker${if (selectedOrderedBrokers.size == 1) "" else "s"} Selected • $selectedSentCount/${selectedOrderedBrokers.size} Sent",
                                    color = TextPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                val nextLabel = nextUnsentSelectedBroker?.let { "Next: ${it.name}" } ?: "All selected brokers dispatched"
                                Text(
                                    text = nextLabel,
                                    color = SecondaryText,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (photoSources.isNotEmpty()) {
                                FilterChip(
                                    selected = includePhotosInBatch,
                                    onClick = { includePhotosInBatch = !includePhotosInBatch },
                                    label = { Text("+Photos", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.PhotoLibrary,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    modifier = Modifier.testTag("brokers_batch_photos_toggle")
                                )
                            }
                        }

                        Button(
                            onClick = {
                                val next = nextUnsentSelectedBroker
                                if (next != null) {
                                    dispatchToBroker(
                                        broker = next,
                                        withPhotos = includePhotosInBatch,
                                        isFromBatch = true
                                    )
                                }
                            },
                            enabled = nextUnsentSelectedBroker != null && !isBatchPreparingPhoto,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (nextUnsentSelectedBroker == null) StatusSuccessGreen else PrimaryActionOrange,
                                contentColor = Color.White,
                                disabledContainerColor = StatusSuccessGreen.copy(alpha = 0.85f),
                                disabledContentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("brokers_batch_send_next_btn")
                        ) {
                            if (isBatchPreparingPhoto) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Preparing Photos...", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            } else if (nextUnsentSelectedBroker == null) {
                                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "All ${selectedOrderedBrokers.size} Sent ✓",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.5.sp
                                )
                            } else {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Send Next via WhatsApp (${selectedSentCount + 1}/${selectedOrderedBrokers.size})",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.5.sp
                                )
                            }
                        }
                    }
                }
            }

            if (selectedBrokerMobiles.isEmpty()) {
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun BrokerShareRowCard(
    broker: MatchingBrokerCandidate,
    isSelected: Boolean,
    isSent: Boolean,
    hasPhotos: Boolean,
    isPreparingPhoto: Boolean,
    onToggleSelect: () -> Unit,
    onCallBroker: () -> Unit,
    onSendDirectWa: () -> Unit,
    onShareWithPhoto: () -> Unit
) {
    Surface(
        color = if (isSelected) SelectedSurfaceContainer else SurfaceWhite,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = when {
                isSent -> StatusSuccessGreen.copy(alpha = 0.65f)
                isSelected -> PrimaryActionOrange
                else -> BorderGrey
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelect)
            .testTag("broker_share_row_${broker.mobileLast10}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = PrimaryActionOrange,
                        checkmarkColor = Color.White
                    ),
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("broker_select_checkbox_${broker.mobileLast10}")
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = broker.name,
                            color = TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (broker.division.isNotBlank()) {
                            val shortDiv = if (broker.division.contains("Res", true)) "Resi" else "Comm"
                            Surface(
                                color = if (broker.isDivisionMatch) SelectedSurfaceContainer else BackgroundSoft,
                                shape = RoundedCornerShape(5.dp),
                                border = BorderStroke(0.8.dp, if (broker.isDivisionMatch) PrimaryActionOrange else BorderGrey)
                            ) {
                                Text(
                                    text = shortDiv,
                                    color = if (broker.isDivisionMatch) PrimaryActionOrange else SecondaryText,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (broker.isAreaMatch) {
                            Surface(
                                color = PrimaryActionOrange.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(5.dp)
                            ) {
                                Text(
                                    text = "Area Match",
                                    color = PrimaryActionOrange,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (isSent) {
                            Surface(
                                color = StatusSuccessGreen.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(5.dp),
                                modifier = Modifier.testTag("broker_sent_badge_${broker.mobileLast10}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusSuccessGreen,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        text = "Sent ✓",
                                        color = StatusSuccessGreen,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }

                    val primarySub = listOfNotNull(
                        broker.firmName.takeIf { it.isNotBlank() },
                        broker.operatingAreas.takeIf { it.isNotBlank() },
                        "+91 ${broker.mobileLast10}"
                    ).joinToString(" • ")
                    Text(
                        text = primarySub,
                        color = SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val metaSub = listOfNotNull(
                        broker.specialization.takeIf { it.isNotBlank() },
                        broker.confidence.takeIf { it.isNotBlank() }?.let { "Conf: $it" }
                    ).joinToString(" • ")
                    if (metaSub.isNotBlank()) {
                        Text(
                            text = metaSub,
                            color = SecondaryText.copy(alpha = 0.85f),
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Action strip: Call + optional Photo WA + Send WA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCallBroker,
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .testTag("broker_call_btn_${broker.mobileLast10}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Call broker",
                        tint = TextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Call", color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }

                if (hasPhotos) {
                    FilledTonalIconButton(
                        onClick = onShareWithPhoto,
                        enabled = !isPreparingPhoto,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("broker_share_photo_btn_${broker.mobileLast10}"),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = PrimaryActionOrange.copy(alpha = 0.14f),
                            contentColor = PrimaryActionOrange
                        )
                    ) {
                        if (isPreparingPhoto) {
                            CircularProgressIndicator(
                                color = PrimaryActionOrange,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Share with Photo",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = onSendDirectWa,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .testTag("broker_send_wa_btn_${broker.mobileLast10}"),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSent) StatusSuccessGreen else PrimaryActionOrange,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (isSent) "Resend WA" else "Send WA",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
