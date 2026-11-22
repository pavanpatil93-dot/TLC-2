package com.example.ui.screens

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ai.ExtractedPropertyDraft
import com.example.ai.GeminiRealEstateService
import com.example.crm.*
import com.example.inventory.InventoryQuality
import com.example.model.*
import com.example.ui.components.WhatsAppIntentRouter
import com.example.ui.components.WhatsAppShareDialog
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppFollowUpsScreen(
    inventory: List<PropertyItem>,
    employeeName: String,
    onOpenLeadInCrm: ((CrmLead) -> Unit)? = null,
    onOpenPropertyDetail: ((PropertyItem) -> Unit)? = null,
    onPublishLiveProperty: ((PropertyItem) -> Boolean)? = null,
    onSaveCrmLead: ((lead: CrmLead, activityType: String, activityNotes: String) -> Unit)? = null,
    onGeneratePropertyCode: ((InventoryTab) -> String)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var messages by remember { mutableStateOf<List<WhatsAppFollowUpMessage>>(emptyList()) }
    var leads by remember { mutableStateOf<List<CrmLead>>(emptyList()) }
    var brokerContacts by remember { mutableStateOf<Map<String, BrokerContactEntry>>(emptyMap()) }
    var pocLinks by remember { mutableStateOf<Map<String, ProjectPocEntry>>(emptyMap()) }
    var hasNotifAccess by remember { mutableStateOf(CrmCallManager.isNotificationListenerGranted(context)) }
    var selectedFilter by remember { mutableStateOf("AI Inbox") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var isScanningAi by remember { mutableStateOf(false) }

    var activeCrmCaptureCluster by remember { mutableStateOf<WhatsAppThreadCluster?>(null) }
    var activeInventoryCaptureCluster by remember { mutableStateOf<WhatsAppThreadCluster?>(null) }
    var activeSwipeChoiceCluster by remember { mutableStateOf<WhatsAppThreadCluster?>(null) }
    var activeShareMatchesDialog by remember { mutableStateOf<Pair<WhatsAppThreadCluster, List<PropertyItem>>?>(null) }

    LaunchedEffect(refreshTick) {
        hasNotifAccess = CrmCallManager.isNotificationListenerGranted(context)
        val loaded = withContext(Dispatchers.IO) {
            val msgs = WhatsAppFollowUpStore.getMessages(context)
            val crmLeads = runCatching { readCrmLeads(context) }.getOrDefault(emptyList())
            val brokers = runCatching { CrmCallManager.getBrokerContacts(context) }.getOrDefault(emptyMap())
            val pocs = runCatching { CrmCallManager.getProjectPocLinks(context) }.getOrDefault(emptyMap())
            Triple(msgs, crmLeads, brokers to pocs)
        }
        messages = loaded.first
        leads = loaded.second
        brokerContacts = loaded.third.first
        pocLinks = loaded.third.second

        // Auto-analyze any pending real-estate messages in the background for the AI Inbox
        val pendingForAi = loaded.first
            .filter { !it.reviewed && !it.skipped && (it.aiStatus == "PENDING" || (it.aiStatus.isBlank() && WhatsAppFollowUpStore.hasRealEstateKeyword(it.messageText))) }
            .take(8)
        if (pendingForAi.isNotEmpty() && !isScanningAi) {
            isScanningAi = true
            try {
                val aiSvc = GeminiRealEstateService()
                withContext(Dispatchers.IO) {
                    pendingForAi.forEach { msg ->
                        WhatsAppFollowUpStore.analyzeMessageForAiInbox(context, msg, aiSvc)
                    }
                }
                messages = withContext(Dispatchers.IO) { WhatsAppFollowUpStore.getMessages(context) }
            } finally {
                isScanningAi = false
            }
        }
    }

    var aiInboxClusters by remember { mutableStateOf<List<WhatsAppThreadCluster>>(emptyList()) }
    var unreadClusters by remember { mutableStateOf<List<WhatsAppThreadCluster>>(emptyList()) }
    var keywordClusters by remember { mutableStateOf<List<WhatsAppThreadCluster>>(emptyList()) }
    var hideGroupClusters by remember { mutableStateOf<List<WhatsAppThreadCluster>>(emptyList()) }
    var allClusters by remember { mutableStateOf<List<WhatsAppThreadCluster>>(emptyList()) }
    var skippedClusters by remember { mutableStateOf<List<WhatsAppThreadCluster>>(emptyList()) }
    var reviewedClusters by remember { mutableStateOf<List<WhatsAppThreadCluster>>(emptyList()) }
    var autoMatchesByThreadKey by remember { mutableStateOf<Map<String, List<PropertyItem>>>(emptyMap()) }

    LaunchedEffect(messages, leads, inventory, brokerContacts, pocLinks) {
        withContext(Dispatchers.Default) {
            val aiBuilt = WhatsAppFollowUpStore.buildCompiledThreads(messages, "AI Inbox", leads, inventory, brokerContacts, pocLinks)
            aiInboxClusters = aiBuilt
            unreadClusters = WhatsAppFollowUpStore.buildCompiledThreads(messages, "Unread", leads, inventory, brokerContacts, pocLinks)
            keywordClusters = WhatsAppFollowUpStore.buildCompiledThreads(messages, "Keywords", leads, inventory, brokerContacts, pocLinks)
            hideGroupClusters = WhatsAppFollowUpStore.buildCompiledThreads(messages, "Hide Group Chats", leads, inventory, brokerContacts, pocLinks)
            allClusters = WhatsAppFollowUpStore.buildCompiledThreads(messages, "All", leads, inventory, brokerContacts, pocLinks)
            skippedClusters = WhatsAppFollowUpStore.buildCompiledThreads(messages, "Skipped", leads, inventory, brokerContacts, pocLinks)
            reviewedClusters = WhatsAppFollowUpStore.buildCompiledThreads(messages, "Reviewed", leads, inventory, brokerContacts, pocLinks)

            val liveAvail = inventory.filter {
                it.status == AvailabilityStatus.AVAILABLE && it.archiveStatus != ArchiveStatus.ARCHIVED
            }
            val matchMap = LinkedHashMap<String, List<PropertyItem>>()
            if (liveAvail.isNotEmpty()) {
                val candidateThreads = (aiBuilt + unreadClusters).distinctBy { it.threadKey }
                candidateThreads.forEach { cluster ->
                    val isReq = cluster.aiIntent.equals("CLIENT_REQUIREMENT", ignoreCase = true) ||
                        (!cluster.hasInventorySignal && WhatsAppFollowUpStore.hasRealEstateKeyword(cluster.combinedText) &&
                            Regex("\\b(looking|need|required|requirement|client|want|search|budget|site\\s*visit)\\b", RegexOption.IGNORE_CASE)
                                .containsMatchIn(cluster.combinedText))
                    if (isReq) {
                        val matches = matchRequirementThreadToLiveInventory(cluster.combinedText, liveAvail)
                        if (matches.isNotEmpty()) {
                            matchMap[cluster.threadKey] = matches
                        }
                    }
                }
            }
            autoMatchesByThreadKey = matchMap
        }
    }

    var visibleLimit by remember(selectedFilter) { mutableIntStateOf(30) }

    val filteredClusters = remember(
        selectedFilter,
        aiInboxClusters,
        unreadClusters,
        keywordClusters,
        hideGroupClusters,
        allClusters,
        skippedClusters,
        reviewedClusters
    ) {
        when (selectedFilter) {
            "AI Inbox" -> aiInboxClusters
            "Unread" -> unreadClusters
            "Keywords" -> keywordClusters
            "Hide Group Chats" -> hideGroupClusters
            "Skipped" -> skippedClusters
            "Reviewed" -> reviewedClusters
            else -> allClusters
        }
    }

    val displayedClusters = remember(filteredClusters, visibleLimit) {
        filteredClusters.take(visibleLimit)
    }

    fun skipCluster(cluster: WhatsAppThreadCluster) {
        coroutineScope.launch {
            messages = withContext(Dispatchers.IO) {
                WhatsAppFollowUpStore.skipThread(context, cluster.messageIds())
            }
            Toast.makeText(context, "Skipped thread from ${cluster.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    fun markClusterReviewed(cluster: WhatsAppThreadCluster) {
        coroutineScope.launch {
            messages = withContext(Dispatchers.IO) {
                WhatsAppFollowUpStore.markThreadReviewed(context, cluster.messageIds())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("whatsapp_followups_screen")
    ) {
        // Header + Refresh + Clear Reviewed
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WhatsApp AI Inbox & Follow-ups",
                    color = TextPrimaryDark,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Gemini auto-tags listings & client requirements • 1-tap approve",
                    color = TextSecondaryDark,
                    fontSize = 11.5.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isScanningAi) {
                    CircularProgressIndicator(
                        color = BrandGold,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(18.dp)
                    )
                }
                IconButton(
                    onClick = { refreshTick++ },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("wa_refresh_btn")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh and scan AI Inbox", tint = BrandGold)
                }
                if (messages.any { it.reviewed }) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                messages = withContext(Dispatchers.IO) { WhatsAppFollowUpStore.clearReviewed(context) }
                                Toast.makeText(context, "Cleared reviewed follow-ups", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("wa_clear_reviewed_btn")
                    ) {
                        Text("Clear Reviewed", fontSize = 11.5.sp, color = PrimaryActionOrange, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Optional Notification Access banner (graceful degradation, never forced)
        if (!hasNotifAccess) {
            Surface(
                color = SelectedSurfaceContainer,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Notification Access Optional",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Enable listener access to auto-capture & compile incoming WhatsApp & WA Business threads.",
                            fontSize = 11.5.sp,
                            color = SecondaryText
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { CrmCallManager.openNotificationListenerSettings(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("wa_enable_listener_btn")
                    ) {
                        Text("Enable", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "AI Inbox" to aiInboxClusters.size,
                "Unread" to unreadClusters.size,
                "Keywords" to keywordClusters.size,
                "Hide Group Chats" to hideGroupClusters.size,
                "All" to allClusters.size,
                "Skipped" to skippedClusters.size,
                "Reviewed" to reviewedClusters.size
            ).forEach { (tab, count) ->
                val isAiTab = tab == "AI Inbox"
                FilterChip(
                    selected = selectedFilter == tab,
                    onClick = { selectedFilter = tab },
                    leadingIcon = if (isAiTab) {
                        {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = if (selectedFilter == tab) BrandGold else PrimaryActionOrange,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    } else null,
                    label = { Text("$tab ($count)", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.testTag("wa_filter_${tab.lowercase(Locale.ENGLISH).replace(" ", "_")}")
                )
            }
        }

        if (filteredClusters.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = if (selectedFilter == "AI Inbox") Icons.Default.AutoAwesome else Icons.Default.MarkChatRead,
                        contentDescription = null,
                        tint = if (selectedFilter == "AI Inbox") BrandGold else TextSecondaryDark,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = when (selectedFilter) {
                            "AI Inbox" -> "AI Inbox is all caught up"
                            "Unread" -> "All WhatsApp follow-ups reviewed"
                            "Skipped" -> "No skipped WhatsApp threads"
                            "Reviewed" -> "No reviewed threads yet"
                            else -> "No WhatsApp replies captured yet"
                        },
                        color = TextPrimaryDark,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (selectedFilter == "AI Inbox") {
                            "Incoming WhatsApp & WA Business notifications with real-estate keywords are scanned silently by Gemini and queued here for 1-tap approval. Personal chats stay untouched."
                        } else {
                            "Consecutive messages from the same sender or group compile into a single card. Swipe right to Capture to CRM/Inventory or swipe left to Skip."
                        },
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    items = displayedClusters,
                    key = { it.threadKey }
                ) { cluster ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { targetValue ->
                            when (targetValue) {
                                SwipeToDismissBoxValue.EndToStart -> {
                                    skipCluster(cluster)
                                    true
                                }
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    activeSwipeChoiceCluster = cluster
                                    false
                                }
                                SwipeToDismissBoxValue.Settled -> false
                            }
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = true,
                        enableDismissFromEndToStart = !cluster.isSkipped,
                        backgroundContent = {
                            val dir = dismissState.dismissDirection
                            val bgColor = when (dir) {
                                SwipeToDismissBoxValue.StartToEnd -> if (cluster.hasInventorySignal) PrimaryActionOrange else WhatsAppBrandDarkGreen
                                SwipeToDismissBoxValue.EndToStart -> Color(0xFF475569)
                                SwipeToDismissBoxValue.Settled -> Color.Transparent
                            }
                            Surface(
                                color = bgColor,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = if (dir == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                                ) {
                                    if (dir == SwipeToDismissBoxValue.StartToEnd) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.AddTask, contentDescription = "Capture thread", tint = Color.White)
                                            Text(
                                                text = if (cluster.hasInventorySignal) "Capture to Inventory / CRM" else "Capture to CRM Lead",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    } else if (dir == SwipeToDismissBoxValue.EndToStart) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text("Skip Thread", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Icon(Icons.Default.SkipNext, contentDescription = "Skip thread", tint = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    ) {
                        val matchedInventory = autoMatchesByThreadKey[cluster.threadKey].orEmpty()
                        WhatsAppThreadClusterCard(
                            cluster = cluster,
                            matchedProperties = matchedInventory,
                            onShareMatches = if (matchedInventory.isNotEmpty()) {
                                { activeShareMatchesDialog = cluster to matchedInventory }
                            } else null,
                            onOpenEntity = {
                                if (cluster.bestMatch.matchedLead != null) {
                                    onOpenLeadInCrm?.invoke(cluster.bestMatch.matchedLead)
                                } else if (cluster.bestMatch.matchedProperty != null) {
                                    onOpenPropertyDetail?.invoke(cluster.bestMatch.matchedProperty)
                                }
                            },
                            onLogToCrm = { activeCrmCaptureCluster = cluster },
                            onCaptureInventory = { activeInventoryCaptureCluster = cluster },
                            onReplyWhatsApp = {
                                val phone = cluster.bestMatch.phone
                                if (phone.isNotBlank()) {
                                    WhatsAppIntentRouter.launchWhatsAppSafely(context, "", phone) { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "No phone number matched for ${cluster.displayName}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSkip = { skipCluster(cluster) },
                            onMutePersonal = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        WhatsAppFollowUpStore.mutePersonalContact(
                                            context,
                                            cluster.displayName,
                                            cluster.bestMatch.phone
                                        )
                                    }
                                    refreshTick++
                                    Toast.makeText(context, "Muted ${cluster.displayName} from future follow-ups", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onMarkReviewed = { markClusterReviewed(cluster) }
                        )
                    }
                }
            }
        }
    }

    // Swipe-Right Capture Routing Choice Sheet
    activeSwipeChoiceCluster?.let { cluster ->
        ModalBottomSheet(
            onDismissRequest = { activeSwipeChoiceCluster = null },
            containerColor = SurfaceWhite,
            modifier = Modifier.testTag("wa_capture_choice_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Capture Thread: ${cluster.headerTitle()}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = cluster.combinedText,
                    fontSize = 12.5.sp,
                    color = SecondaryText,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )

                Button(
                    onClick = {
                        activeSwipeChoiceCluster = null
                        activeCrmCaptureCluster = cluster
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("wa_choice_log_crm_btn")
                ) {
                    Icon(Icons.Default.PersonAddAlt1, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (cluster.bestMatch.matchedLead != null) "Append to Matched CRM Lead" else "Log as New Live CRM Lead",
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        activeSwipeChoiceCluster = null
                        activeInventoryCaptureCluster = cluster
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E), contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("wa_choice_capture_inventory_btn")
                ) {
                    Icon(Icons.Default.Apartment, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Auto-Parse & Capture to Live Inventory", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // Dual Capture 1: Log to CRM Lead (1-tap append OR pre-filled New Live Lead)
    activeCrmCaptureCluster?.let { cluster ->
        WhatsAppLogToCrmSheet(
            cluster = cluster,
            employeeName = employeeName,
            onDismiss = { activeCrmCaptureCluster = null },
            onSave = { targetLead, activityNotes ->
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        if (onSaveCrmLead != null) {
                            onSaveCrmLead.invoke(targetLead, "WhatsApp Follow-up", activityNotes)
                        } else {
                            upsertCrmLeadFromPostCall(
                                context = context,
                                lead = targetLead,
                                activityType = "WhatsApp Follow-up",
                                activityNotes = activityNotes,
                                performer = employeeName.ifBlank { "TLC-1 Team" }
                            )
                        }
                        if (targetLead.phone.isNotBlank()) {
                            val recordId = "wa_thread_${cluster.threadKey.hashCode()}"
                            val callRec = CrmCallRecord(
                                id = recordId,
                                callerName = targetLead.displayClientTitle(),
                                phoneNumber = targetLead.phone,
                                callType = CallType.INCOMING,
                                timestamp = cluster.latestTimestamp,
                                durationSeconds = 0,
                                notes = activityNotes,
                                isLinked = true,
                                linkedOpportunityType = FollowUpSenderRole.CRM_LEAD.label,
                                linkedOpportunityTitle = targetLead.displayClientTitle(),
                                linkedOpportunityId = targetLead.id
                            )
                            CrmCallManager.addOrUpdateCall(context, callRec)
                            if (targetLead.nextFollowUp.isNotBlank()) {
                                CallFollowUpStore.set(context, recordId, targetLead.nextFollowUp)
                            }
                        }
                        WhatsAppFollowUpStore.markThreadReviewed(context, cluster.messageIds())
                    }
                    activeCrmCaptureCluster = null
                    refreshTick++
                    Toast.makeText(
                        context,
                        "Saved to Live CRM (${targetLead.displayClientTitle()})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    // Dual Capture 2: Auto-Parse Inventory -> Review & Approve Inventory Sheet -> Live
    activeInventoryCaptureCluster?.let { cluster ->
        WhatsAppReviewInventorySheet(
            cluster = cluster,
            employeeName = employeeName,
            onGeneratePropertyCode = onGeneratePropertyCode,
            onDismiss = { activeInventoryCaptureCluster = null },
            onApproveLive = { liveProp ->
                coroutineScope.launch {
                    val published = withContext(Dispatchers.IO) {
                        val ok = onPublishLiveProperty?.invoke(liveProp) ?: false
                        if (ok) {
                            WhatsAppFollowUpStore.markThreadReviewed(context, cluster.messageIds())
                        }
                        ok
                    }
                    if (published) {
                        activeInventoryCaptureCluster = null
                        refreshTick++
                        Toast.makeText(
                            context,
                            "Published ${liveProp.propertyCode} to Live Inventory",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Unable to publish inventory (duplicate code or invalid fields)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    // 1-Tap Share Live Inventory Matches for AI Inbox Client Requirements
    activeShareMatchesDialog?.let { (cluster, matchedProps) ->
        WhatsAppShareDialog(
            properties = matchedProps,
            recipientPhone = cluster.bestMatch.phone.takeIf { it.isNotBlank() },
            senderName = employeeName,
            onDismiss = { activeShareMatchesDialog = null }
        )
    }
}

private fun matchRequirementThreadToLiveInventory(
    rawText: String,
    availableInventory: List<PropertyItem>,
    maxMatches: Int = 3
): List<PropertyItem> {
    val lower = rawText.lowercase(Locale.ENGLISH)
    val wantsCommercial = Regex("\\b(office|shop|showroom|commercial|warehouse|workstation|cabin|retail|pre[\\s-]?leased|it\\s*park|floor\\s*plate)\\b")
        .containsMatchIn(lower)
    val wantsResidential = Regex("\\b(1\\s*bhk|2\\s*bhk|3\\s*bhk|4\\s*bhk|5\\s*bhk|flat|apartment|villa|bungalow|penthouse|residential|duplex)\\b")
        .containsMatchIn(lower)
    val wantsRent = Regex("\\b(rent|lease|leasing|monthly|per\\s*month|/mo)\\b").containsMatchIn(lower)
    val wantsSale = Regex("\\b(buy|sale|purchase|outright|resale|invest|pre[\\s-]?leased)\\b").containsMatchIn(lower)
    val bhkMatch = Regex("\\b([1-5])\\s*bhk\\b").find(lower)?.groupValues?.getOrNull(1)

    val matchedLocalities = WhatsAppFollowUpStore.PUNE_REAL_ESTATE_LOCALITIES.filter { loc ->
        lower.contains(loc)
    }

    val areaMatch = Regex("\\b(\\d{3,5})\\s*(sq\\.?\\s*ft|sqft|sft|carpet|ca)\\b").find(lower)
        ?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    val scored = availableInventory.mapNotNull { prop ->
        var score = 0
        val propLoc = prop.location.lowercase(Locale.ENGLISH)
        val propProject = prop.projectName.lowercase(Locale.ENGLISH)

        if (matchedLocalities.isNotEmpty()) {
            if (matchedLocalities.any { loc -> propLoc.contains(loc) || propProject.contains(loc) }) {
                score += 45
            } else {
                return@mapNotNull null // Keep matches geographically relevant when location is stated
            }
        }

        if (wantsCommercial && !wantsResidential) {
            if (prop.isCommercial()) score += 20 else return@mapNotNull null
        } else if (wantsResidential && !wantsCommercial) {
            if (!prop.isCommercial()) score += 20 else return@mapNotNull null
        }

        if (wantsRent && !wantsSale) {
            if (prop.tab.isRental) score += 20 else return@mapNotNull null
        } else if (wantsSale && !wantsRent) {
            if (!prop.tab.isRental || prop.tab == InventoryTab.PRE_LEASED) score += 20 else return@mapNotNull null
        }

        if (!bhkMatch.isNullOrBlank()) {
            val propBhk = prop.bhk.orEmpty().lowercase(Locale.ENGLISH)
            if (propBhk.contains(bhkMatch)) score += 25 else if (!prop.isCommercial()) score -= 15
        }

        if (areaMatch != null && prop.carpetAreaSqFt > 0.0) {
            val ratio = prop.carpetAreaSqFt / areaMatch
            if (ratio in 0.70..1.35) score += 18 else if (ratio in 0.55..1.60) score += 6
        }

        if (score >= 35) prop to score else null
    }

    return scored
        .sortedByDescending { it.second }
        .take(maxMatches)
        .map { it.first }
}

@Composable
private fun WhatsAppThreadClusterCard(
    cluster: WhatsAppThreadCluster,
    matchedProperties: List<PropertyItem> = emptyList(),
    onShareMatches: (() -> Unit)? = null,
    onOpenEntity: () -> Unit,
    onLogToCrm: () -> Unit,
    onCaptureInventory: () -> Unit,
    onReplyWhatsApp: () -> Unit,
    onSkip: () -> Unit,
    onMutePersonal: () -> Unit,
    onMarkReviewed: () -> Unit
) {
    val timeText = remember(cluster.latestTimestamp) {
        SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH).format(Date(cluster.latestTimestamp))
    }
    val roleColor = when (cluster.bestMatch.role) {
        FollowUpSenderRole.CRM_LEAD -> PrimaryActionOrange
        FollowUpSenderRole.PROPERTY_OWNER -> StatusSuccessGreen
        FollowUpSenderRole.BROKER -> Color(0xFF0284C7)
        FollowUpSenderRole.DEVELOPER_POC -> Color(0xFF7C3AED)
        FollowUpSenderRole.UNMATCHED -> SecondaryText
    }
    val isWaBusiness = cluster.packageName == "com.whatsapp.w4b"

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            when {
                cluster.hasInventorySignal && !cluster.isReviewed -> Color(0xFF0F766E).copy(alpha = 0.7f)
                !cluster.isReviewed && cluster.allKeywords.isNotEmpty() -> PrimaryActionOrange.copy(alpha = 0.6f)
                else -> BorderGrey
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wa_thread_card_${cluster.threadKey}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Row: Compiled Sender/Group Header + Badges + Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = cluster.headerTitle(),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (cluster.isGroup) {
                        Surface(color = Color(0xFFE0F2FE), shape = RoundedCornerShape(4.dp)) {
                            Text(
                                text = "Group",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0369A1),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (isWaBusiness) {
                        Surface(color = Color(0xFFDCFCE7), shape = RoundedCornerShape(4.dp)) {
                            Text(
                                text = "WA Biz",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF166534),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = timeText,
                    fontSize = 10.5.sp,
                    color = SecondaryText,
                    maxLines = 1
                )
            }

            // Role & Entity Pill + Inventory Detected Badge + State label
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        onClick = onOpenEntity,
                        color = roleColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.8.dp, roleColor.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = cluster.bestMatch.role.label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = roleColor
                            )
                            if (cluster.bestMatch.role != FollowUpSenderRole.UNMATCHED && cluster.bestMatch.entityLabel.isNotBlank()) {
                                Text(
                                    text = "• ${cluster.bestMatch.entityLabel}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (cluster.hasInventorySignal) {
                        Surface(
                            color = Color(0xFFCCFBF1),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.8.dp, Color(0xFF0F766E).copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Apartment,
                                    contentDescription = null,
                                    tint = Color(0xFF0F766E),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Inventory Detected",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F766E),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                when {
                    cluster.isReviewed -> Text("Reviewed", fontSize = 10.sp, color = StatusSuccessGreen, fontWeight = FontWeight.Bold)
                    cluster.isSkipped -> Text("Skipped", fontSize = 10.sp, color = SecondaryText, fontWeight = FontWeight.Bold)
                }
            }

            // Gemini AI Intent Banner (when classified or pending AI analysis)
            if (cluster.aiStatus.isNotBlank() && cluster.aiStatus != "NONE") {
                val (aiBadgeLabel, aiBadgeColor, aiBadgeBg) = when {
                    cluster.aiStatus == "PENDING" -> Triple("Gemini Analyzing...", PrimaryNavy, Color(0xFFE0E7FF))
                    cluster.aiIntent == "PROPERTY_LISTING" -> Triple("AI: Property Listing", Color(0xFF0F766E), Color(0xFFCCFBF1))
                    cluster.aiIntent == "CLIENT_REQUIREMENT" -> Triple("AI: Client Requirement", PrimaryActionOrange, Color(0xFFFFEDD5))
                    cluster.aiIntent == "EXISTING_PROPERTY_UPDATE" -> Triple("AI: Listing Update", Color(0xFF7C3AED), Color(0xFFF3E8FF))
                    else -> Triple("AI: Review Needed", Color(0xFF475569), Color(0xFFF1F5F9))
                }
                Surface(
                    color = aiBadgeBg,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.8.dp, aiBadgeColor.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wa_ai_intent_banner_${cluster.threadKey}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = aiBadgeColor,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = aiBadgeLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = aiBadgeColor
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (cluster.aiConfidence > 0) {
                                    Text(
                                        text = "${cluster.aiConfidence}% match",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = aiBadgeColor
                                    )
                                }
                                if (cluster.aiReviewFlagCount > 0) {
                                    Text(
                                        text = "⚠ ${cluster.aiReviewFlagCount} verify",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFB45309)
                                    )
                                }
                            }
                        }
                        if (cluster.aiSummary.isNotBlank()) {
                            Text(
                                text = cluster.aiSummary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Live Inventory Auto-Match Strip for Client Requirements
            if (matchedProperties.isNotEmpty()) {
                Surface(
                    color = Color(0xFFECFDF5),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StatusSuccessGreen.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wa_auto_matches_banner_${cluster.threadKey}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "${matchedProperties.size} Live Inventory ${if (matchedProperties.size == 1) "Match" else "Matches"} Found",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF065F46)
                            )
                            Text(
                                text = matchedProperties.joinToString(" • ") {
                                    it.projectName.ifBlank { it.propertyCode }
                                },
                                fontSize = 11.sp,
                                color = TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (onShareMatches != null) {
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = onShareMatches,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StatusSuccessGreen,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("wa_share_matches_btn")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Share", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Compiled Message Text
            Surface(
                color = SurfaceMuted,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = cluster.combinedText,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(10.dp)
                )
            }

            // Follow-up Keyword Chips
            if (cluster.allKeywords.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(14.dp))
                    cluster.allKeywords.forEach { kw ->
                        Surface(
                            color = PrimaryActionOrange.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.8.dp, PrimaryActionOrange.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = kw.uppercase(Locale.ENGLISH),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryActionOrange,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = BorderSubtle)

            // 1-Tap Actions (48dp touch targets)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isPropertyApprovalPrimary = cluster.aiIntent == "PROPERTY_LISTING" ||
                    cluster.aiIntent == "EXISTING_PROPERTY_UPDATE" ||
                    cluster.hasInventorySignal
                if (isPropertyApprovalPrimary) {
                    Button(
                        onClick = onCaptureInventory,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("wa_capture_inventory_btn")
                    ) {
                        Icon(Icons.Default.Apartment, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Approve to Inventory", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }

                    OutlinedButton(
                        onClick = onLogToCrm,
                        border = BorderStroke(1.dp, PrimaryActionOrange),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("wa_log_crm_btn")
                    ) {
                        Text("Approve to CRM", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange, maxLines = 1, softWrap = false)
                    }
                } else {
                    Button(
                        onClick = onLogToCrm,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("wa_log_crm_btn")
                    ) {
                        Icon(Icons.Default.EventNote, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Approve to CRM Lead", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }

                    OutlinedButton(
                        onClick = onCaptureInventory,
                        border = BorderStroke(1.dp, Color(0xFF0F766E)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("wa_capture_inventory_btn")
                    ) {
                        Text("Parse Inventory", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F766E), maxLines = 1, softWrap = false)
                    }
                }

                OutlinedButton(
                    onClick = onReplyWhatsApp,
                    border = BorderStroke(1.dp, WhatsAppBrandDarkGreen),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("wa_reply_btn")
                ) {
                    Text("Reply WA", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = WhatsAppBrandDarkGreen, maxLines = 1, softWrap = false)
                }

                if (!cluster.isSkipped) {
                    OutlinedButton(
                        onClick = onSkip,
                        border = BorderStroke(1.dp, BorderGrey),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("wa_skip_btn")
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Skip", fontSize = 11.sp, color = SecondaryText, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }

                OutlinedButton(
                    onClick = onMutePersonal,
                    border = BorderStroke(1.dp, BorderGrey),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("wa_mute_btn")
                ) {
                    Text("Mute", fontSize = 11.sp, color = SecondaryText, maxLines = 1, softWrap = false)
                }

                if (!cluster.isReviewed) {
                    IconButton(
                        onClick = onMarkReviewed,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("wa_mark_done_btn")
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = "Mark Reviewed", tint = StatusSuccessGreen)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsAppLogToCrmSheet(
    cluster: WhatsAppThreadCluster,
    employeeName: String,
    onDismiss: () -> Unit,
    onSave: (lead: CrmLead, activityNotes: String) -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val todayDate = remember { dateFormat.format(Date()) }
    val matchedLead = cluster.bestMatch.matchedLead

    var reminderDate by remember {
        mutableStateOf(
            dateFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.time)
        )
    }
    var customNote by remember { mutableStateOf("") }

    // Pre-filled New Live Lead fields when unmatched
    val detectedPhone = remember(cluster) {
        cluster.bestMatch.phone.ifBlank { extractFirstPhoneFromText(cluster.combinedText) }
    }
    var leadName by remember { mutableStateOf(cluster.displayName) }
    var leadPhone by remember { mutableStateOf(detectedPhone) }
    var segment by remember {
        mutableStateOf(
            if (cluster.combinedText.contains("bhk", true) || cluster.combinedText.contains("flat", true) || cluster.combinedText.contains("apartment", true)) {
                "Residential"
            } else "Commercial"
        )
    }
    var reqType by remember {
        mutableStateOf(
            if (cluster.combinedText.contains("buy", true) || cluster.combinedText.contains("sale", true) || cluster.combinedText.contains("purchase", true)) {
                "Buy"
            } else "Lease"
        )
    }
    var location by remember { mutableStateOf(detectPuneLocationHint(cluster.combinedText).ifBlank { "Pune" }) }
    var budget by remember { mutableStateOf("Negotiable") }

    val pickContactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        if (uri != null) {
            extractContactDetails(context, uri)?.let { (pickedName, pickedPhone) ->
                if (pickedName.isNotBlank() && leadName == cluster.displayName) leadName = pickedName
                if (pickedPhone.isNotBlank()) leadPhone = pickedPhone
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pickContactLauncher.launch(null)
        } else {
            Toast.makeText(context, "Contacts permission denied — type number manually", Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("wa_log_crm_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (matchedLead != null) "Append Thread to Matched CRM Lead" else "Capture New Live CRM Lead",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Surface(
                color = SurfaceMuted,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = cluster.combinedText,
                    fontSize = 12.sp,
                    color = SecondaryText,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp)
                )
            }

            if (matchedLead != null) {
                Surface(
                    color = SelectedSurfaceContainer,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${matchedLead.displayClientTitle()} • ${matchedLead.segment}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${matchedLead.status} • ${matchedLead.location.ifBlank { "Pune" }} • ${matchedLead.budget.ifBlank { "Budget TBD" }}",
                            fontSize = 12.sp,
                            color = SecondaryText
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = leadName,
                    onValueChange = { leadName = it },
                    label = { Text("Client Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = leadPhone,
                    onValueChange = { leadPhone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(13) },
                    label = { Text("10-Digit Mobile *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                                    pickContactLauncher.launch(null)
                                } else {
                                    permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Contacts, contentDescription = "Pick from Contacts", tint = PrimaryActionOrange)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Commercial", "Residential").forEach { div ->
                        FilterChip(
                            selected = segment == div,
                            onClick = { segment = div },
                            label = { Text(div, fontSize = 12.sp) }
                        )
                    }
                    listOf("Lease", "Buy").forEach { rt ->
                        FilterChip(
                            selected = reqType == rt,
                            onClick = { reqType = rt },
                            label = { Text(rt, fontSize = 12.sp) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("Location") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = budget,
                        onValueChange = { budget = it },
                        label = { Text("Budget") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text("Follow-up Reminder", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("Today" to 0, "Tomorrow" to 1, "+3 Days" to 3).forEach { (label, offset) ->
                    val target = dateFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }.time)
                    FilterChip(
                        selected = reminderDate == target,
                        onClick = { reminderDate = target },
                        label = { Text(label, fontSize = 11.5.sp) }
                    )
                }
                OutlinedButton(
                    onClick = {
                        val cal = Calendar.getInstance()
                        DatePickerDialog(context, { _, y, m, d ->
                            val c = Calendar.getInstance().apply { set(y, m, d) }
                            reminderDate = dateFormat.format(c.time)
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(reminderDate.ifBlank { "Pick Date" }, fontSize = 11.5.sp)
                }
            }

            OutlinedTextField(
                value = customNote,
                onValueChange = { customNote = it },
                label = { Text("Action Note (Optional)") },
                placeholder = { Text("e.g. Share 3 shortlisted options tomorrow") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val dateStamp = SimpleDateFormat("dd MMM", Locale.ENGLISH).format(Date())
                        val threadEntry = "[WA $dateStamp] ${cluster.combinedText.take(260)}" +
                            if (customNote.isNotBlank()) " • Note: ${customNote.trim()}" else ""
                        val updatedLead = if (matchedLead != null) {
                            val mergedNotes = listOf(matchedLead.notes, threadEntry)
                                .filter { it.isNotBlank() }
                                .joinToString("\n")
                            matchedLead.copy(
                                notes = mergedNotes,
                                clientNotes = mergedNotes,
                                nextFollowUp = reminderDate.ifBlank { matchedLead.nextFollowUp },
                                lastContactDate = todayDate,
                                lastContactMode = "WhatsApp"
                            )
                        } else {
                            CrmLead(
                                id = "CRM-WA-${System.currentTimeMillis().toString().takeLast(6)}",
                                name = leadName.trim().ifBlank { cluster.displayName },
                                phone = ContactUtils.normalizePhoneNumber(leadPhone),
                                clientType = if (cluster.bestMatch.role == FollowUpSenderRole.BROKER) "Broker / CP" else "Direct Client",
                                segment = segment,
                                requirement = cluster.combinedText.take(140),
                                requirementType = reqType,
                                location = location.trim().ifBlank { "Pune" },
                                budget = budget.trim().ifBlank { "Negotiable" },
                                status = "New Lead",
                                verificationStatus = "Verified Live",
                                nextFollowUp = reminderDate,
                                notes = threadEntry,
                                clientNotes = threadEntry,
                                assignedTo = employeeName.ifBlank { "TLC-1 Team" },
                                createdDate = todayDate,
                                lastContactDate = todayDate,
                                lastContactMode = "WhatsApp",
                                leadSource = "WhatsApp Follow-up"
                            )
                        }
                        onSave(updatedLead, threadEntry)
                    },
                    enabled = matchedLead != null || leadName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                    modifier = Modifier
                        .weight(1.5f)
                        .heightIn(min = 48.dp)
                        .testTag("wa_save_crm_lead_confirm_btn")
                ) {
                    Text(
                        text = if (matchedLead != null) "Append & Save Lead" else "Create Live Lead",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsAppReviewInventorySheet(
    cluster: WhatsAppThreadCluster,
    employeeName: String,
    onGeneratePropertyCode: ((InventoryTab) -> String)?,
    onDismiss: () -> Unit,
    onApproveLive: (PropertyItem) -> Unit
) {
    val context = LocalContext.current
    var isParsing by remember { mutableStateOf(true) }

    var selectedTab by remember { mutableStateOf(InventoryTab.OFF_LEASE) }
    var propertyType by remember { mutableStateOf("Office") }
    var projectName by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var unitNumber by remember { mutableStateOf("") }
    var floor by remember { mutableStateOf("") }
    var carpetAreaText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(AvailabilityStatus.AVAILABLE) }
    var possession by remember { mutableStateOf("Immediate") }
    var furnishing by remember { mutableStateOf(FurnishingType.UNFURNISHED) }
    var pocType by remember {
        mutableStateOf(
            when (cluster.bestMatch.role) {
                FollowUpSenderRole.BROKER -> "Broker"
                FollowUpSenderRole.DEVELOPER_POC -> "Developer Sales Team"
                else -> "Property Owner"
            }
        )
    }
    var contactName by remember { mutableStateOf(cluster.displayName) }
    var contactPhone by remember {
        mutableStateOf(cluster.bestMatch.phone.ifBlank { extractFirstPhoneFromText(cluster.combinedText) })
    }
    var remarks by remember { mutableStateOf(cluster.combinedText.take(260)) }

    // AI auto-parse on open
    LaunchedEffect(cluster.threadKey, cluster.latestTimestamp) {
        isParsing = true
        val draft: ExtractedPropertyDraft = withContext(Dispatchers.IO) {
            GeminiRealEstateService().parseWhatsAppInventoryMessage(
                rawText = cluster.combinedText,
                senderName = cluster.displayName,
                senderPhone = cluster.bestMatch.phone
            )
        }
        selectedTab = draft.tab
        propertyType = draft.propertyType.ifBlank {
            when (draft.tab) {
                InventoryTab.SHOP_LEASE, InventoryTab.SHOP_OUT -> "Shop"
                InventoryTab.RESI_RENT, InventoryTab.RESI_RESALE, InventoryTab.UND_CONST -> "Apartment"
                else -> "Office"
            }
        }
        projectName = draft.projectName
        location = draft.location.ifBlank { draft.microMarket }.ifBlank { detectPuneLocationHint(cluster.combinedText) }
        unitNumber = draft.unitNumber
        floor = draft.floor
        if (draft.carpetAreaSqFt > 0.0) {
            carpetAreaText = draft.carpetAreaSqFt.toInt().toString()
        }
        val parsedAmount = when {
            draft.tab.isRental && (draft.rent ?: 0.0) > 0.0 -> draft.rent ?: 0.0
            !draft.tab.isRental && (draft.priceSale ?: 0.0) > 0.0 -> draft.priceSale ?: 0.0
            draft.priceOrRent > 0.0 -> draft.priceOrRent
            else -> 0.0
        }
        if (parsedAmount > 0.0) {
            amountText = parsedAmount.toLong().toString()
        }
        if (draft.possession.isNotBlank()) possession = draft.possession
        if (draft.hasExplicitFurnishing) furnishing = draft.furnishing
        if (draft.contactName.isNotBlank()) contactName = draft.contactName
        val normalizedParsedPhone = ContactUtils.normalizePhoneNumber(draft.contactPhone)
        if (normalizedParsedPhone.length >= 10) contactPhone = normalizedParsedPhone
        if (draft.remarks.isNotBlank()) remarks = draft.remarks
        isParsing = false
    }

    val pickContactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        if (uri != null) {
            extractContactDetails(context, uri)?.let { (pickedName, pickedPhone) ->
                if (pickedName.isNotBlank() && (contactName.isBlank() || contactName == cluster.displayName)) {
                    contactName = pickedName
                }
                if (pickedPhone.isNotBlank()) contactPhone = pickedPhone
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pickContactLauncher.launch(null)
        } else {
            Toast.makeText(context, "Contacts permission denied — type number manually", Toast.LENGTH_SHORT).show()
        }
    }

    // Build candidate PropertyItem for live InventoryQuality validation
    val parsedArea = carpetAreaText.trim().toDoubleOrNull() ?: 0.0
    val parsedAmount = parseSmartIndianAmount(amountText)
    val cleanPhone10 = ContactUtils.normalizePhoneNumber(contactPhone)
    val todayIso = remember { LocalDate.now().toString() }
    val todayReadable = remember { SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date()) }
    val txTypeLabel = StandardTransactionType.fromString(null, selectedTab).label

    val candidateProperty = remember(
        selectedTab, propertyType, projectName, location, unitNumber, floor,
        parsedArea, parsedAmount, status, possession, furnishing, pocType,
        contactName, cleanPhone10, remarks
    ) {
        val generatedCode = onGeneratePropertyCode?.invoke(selectedTab)
            ?: "WA-${System.currentTimeMillis().toString().takeLast(5)}"
        val isBroker = pocType.equals("Broker", ignoreCase = true)
        val isDeveloper = pocType.contains("Developer", ignoreCase = true)
        PropertyItem(
            id = "PROP-WA-${System.currentTimeMillis()}",
            propertyCode = generatedCode,
            date = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).format(Date()),
            location = location.trim(),
            projectName = projectName.trim(),
            unitNumber = unitNumber.trim(),
            floor = floor.trim(),
            propertyType = propertyType.trim(),
            tab = selectedTab,
            carpetAreaSqFt = parsedArea,
            priceOrRent = parsedAmount,
            rent = if (selectedTab.isRental) parsedAmount else null,
            priceSale = if (!selectedTab.isRental) parsedAmount else null,
            possession = possession.trim(),
            furnishing = furnishing,
            status = status,
            ownerName = if (!isBroker) contactName.trim() else null,
            ownerContact = if (!isBroker) cleanPhone10 else null,
            brokerName = if (isBroker) contactName.trim() else null,
            brokerContact = if (isBroker) cleanPhone10 else null,
            builderName = if (isDeveloper) contactName.trim() else null,
            sourceInfo = "$pocType: ${contactName.trim()}",
            remarks = remarks.trim(),
            portfolio = if (selectedTab.isCommercial) "Commercial" else "Residential",
            transactionType = txTypeLabel,
            verificationStatus = VerificationStatus.VERIFIED,
            lastVerifiedDateClean = todayIso,
            lastVerificationDate = todayReadable,
            verifiedBy = employeeName.ifBlank { "TLC-1 Team" },
            recordId = "AST-${UUID.randomUUID().toString().take(8).uppercase(Locale.ENGLISH)}",
            archiveStatus = ArchiveStatus.ACTIVE,
            pocType = pocType,
            isSyncedToSheet = false
        )
    }

    val missingFields = remember(candidateProperty) {
        InventoryQuality.minimumRequiredMissingFields(candidateProperty)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("wa_review_inventory_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Review & Approve Live Inventory",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            if (isParsing) {
                Surface(
                    color = SelectedSurfaceContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = PrimaryActionOrange
                        )
                        Text(
                            text = "Extracting property details from WhatsApp thread...",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }
            }

            if (missingFields.isNotEmpty() && !isParsing) {
                Surface(
                    color = StatusDangerRed.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StatusDangerRed.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wa_inventory_missing_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "Missing Required Fields to Publish Live (${missingFields.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusDangerRed
                        )
                        Text(
                            text = missingFields.joinToString(" • "),
                            fontSize = 11.5.sp,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Tab / Segment Chips
            Text("Inventory Segment", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    InventoryTab.OFF_LEASE,
                    InventoryTab.OFF_OUT,
                    InventoryTab.SHOP_LEASE,
                    InventoryTab.SHOP_OUT,
                    InventoryTab.PRE_LEASED,
                    InventoryTab.RESI_RENT,
                    InventoryTab.RESI_RESALE
                ).forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            propertyType = when (tab) {
                                InventoryTab.SHOP_LEASE, InventoryTab.SHOP_OUT -> "Shop"
                                InventoryTab.RESI_RENT, InventoryTab.RESI_RESALE -> "Apartment"
                                else -> "Office"
                            }
                        },
                        label = { Text(tab.code, fontSize = 11.sp) }
                    )
                }
            }

            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it },
                label = { Text("Project / Building *") },
                isError = "Project / Building" in missingFields,
                supportingText = if ("Project / Building" in missingFields) {
                    { Text("Required to go Live", color = StatusDangerRed, fontSize = 10.5.sp) }
                } else null,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wa_inv_project_input")
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location / Area *") },
                    isError = "Location" in missingFields,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1.2f)
                        .testTag("wa_inv_location_input")
                )
                OutlinedTextField(
                    value = propertyType,
                    onValueChange = { propertyType = it },
                    label = { Text("Type *") },
                    isError = "Property Type" in missingFields,
                    singleLine = true,
                    modifier = Modifier.weight(0.8f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = unitNumber,
                    onValueChange = { unitNumber = it },
                    label = { Text("Unit No.") },
                    isError = "Unit / Floor" in missingFields,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("wa_inv_unit_input")
                )
                OutlinedTextField(
                    value = floor,
                    onValueChange = { floor = it },
                    label = { Text("Floor") },
                    isError = "Unit / Floor" in missingFields,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("wa_inv_floor_input")
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = carpetAreaText,
                    onValueChange = { carpetAreaText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Carpet Area (sq.ft) *") },
                    isError = "Carpet Area" in missingFields,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("wa_inv_area_input")
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(if (selectedTab.isRental) "Monthly Rent *" else "Asking Price *") },
                    placeholder = { Text("e.g. 95000 or 1.5L") },
                    isError = ("Monthly Rent" in missingFields || "Asking Price" in missingFields),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1.1f)
                        .testTag("wa_inv_price_input")
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = possession,
                    onValueChange = { possession = it },
                    label = { Text("Possession *") },
                    isError = "Possession" in missingFields,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .weight(1.2f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        FurnishingType.BARE_SHELL to "Bare",
                        FurnishingType.WARM_SHELL to "Warm",
                        FurnishingType.SEMI_FURNISHED to "Semi",
                        FurnishingType.FULLY_FURNISHED to "Full"
                    ).forEach { (ft, lbl) ->
                        FilterChip(
                            selected = furnishing == ft,
                            onClick = { furnishing = ft },
                            label = { Text(lbl, fontSize = 10.5.sp) }
                        )
                    }
                }
            }

            Text("Point of Contact (POC)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                POC_TYPE_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = pocType == option,
                        onClick = { pocType = option },
                        label = { Text(option, fontSize = 11.sp) }
                    )
                }
            }

            OutlinedTextField(
                value = contactName,
                onValueChange = { contactName = it },
                label = { Text("Source / Owner / Broker Name *") },
                isError = "Source / Owner / POC" in missingFields,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wa_inv_contact_name_input")
            )

            OutlinedTextField(
                value = contactPhone,
                onValueChange = { contactPhone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(13) },
                label = { Text("10-Digit Contact Phone *") },
                isError = "Contact Number" in missingFields,
                supportingText = if ("Contact Number" in missingFields) {
                    { Text("10-digit contact phone required — type or pick from Contacts", color = StatusDangerRed, fontSize = 10.5.sp) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                                pickContactLauncher.launch(null)
                            } else {
                                permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("wa_inv_pick_contact_btn")
                    ) {
                        Icon(Icons.Default.Contacts, contentDescription = "Pick from Contacts", tint = PrimaryActionOrange)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wa_inv_contact_phone_input")
            )

            OutlinedTextField(
                value = remarks,
                onValueChange = { remarks = it },
                label = { Text("Remarks / WhatsApp Thread Snippet") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(0.9f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onApproveLive(candidateProperty) },
                    enabled = missingFields.isEmpty() && !isParsing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (missingFields.isEmpty()) PrimaryActionOrange else BorderGrey,
                        contentColor = OnPrimaryActionColor
                    ),
                    modifier = Modifier
                        .weight(1.6f)
                        .heightIn(min = 48.dp)
                        .testTag("wa_publish_live_inventory_btn")
                ) {
                    Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Approve & Publish Live", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun extractFirstPhoneFromText(text: String): String {
    val match = Regex("(?:\\+?91[-\\s]?)?([6-9]\\d{4}[-\\s]?\\d{5})").find(text)
    val digits = match?.groupValues?.getOrNull(1)?.filter { it.isDigit() }.orEmpty()
    return if (digits.length == 10) digits else ""
}

private fun detectPuneLocationHint(text: String): String {
    val knownLocalities = listOf(
        "Baner", "Balewadi", "Wakad", "Hinjewadi", "Aundh", "Kothrud", "Shivajinagar",
        "Koregaon Park", "Kalyani Nagar", "Viman Nagar", "Kharadi", "Magarpatta",
        "Hadapsar", "SB Road", "JM Road", "FC Road", "Camp", "Swargate", "Bavdhan",
        "Pashan", "Yerwada", "Bund Garden", "Boat Club Road", "Deccan", "Prabhat Road",
        "Pimpri", "Chinchwad", "Bhosari", "Chakan", "NIBM", "Undri", "Wanowrie", "Katraj"
    )
    return knownLocalities.firstOrNull { loc -> text.contains(loc, ignoreCase = true) }.orEmpty()
}

private fun parseSmartIndianAmount(raw: String): Double {
    val cleaned = raw.trim().lowercase(Locale.ENGLISH).replace(",", "").replace("₹", "")
    if (cleaned.isBlank()) return 0.0
    val numPart = Regex("(\\d+(?:\\.\\d+)?)").find(cleaned)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return 0.0
    return when {
        cleaned.contains("cr") -> numPart * 10_000_000.0
        cleaned.contains("lac") || cleaned.contains("lakh") || cleaned.endsWith("l") -> numPart * 100_000.0
        cleaned.endsWith("k") -> numPart * 1_000.0
        else -> numPart
    }
}

private fun extractContactDetails(context: Context, contactUri: Uri): Pair<String, String>? {
    return runCatching {
        var displayName = ""
        var contactId = ""
        var hasPhone = false
        context.contentResolver.query(
            contactUri,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val hasIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                if (idIdx >= 0) contactId = cursor.getString(idIdx).orEmpty()
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx).orEmpty()
                if (hasIdx >= 0) hasPhone = cursor.getInt(hasIdx) > 0
            }
        }
        var phone = ""
        if (hasPhone && contactId.isNotBlank()) {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { pCursor ->
                if (pCursor.moveToFirst()) {
                    val numIdx = pCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numIdx >= 0) {
                        phone = ContactUtils.normalizePhoneNumber(pCursor.getString(numIdx).orEmpty())
                    }
                }
            }
        }
        displayName to phone
    }.getOrNull()
}
