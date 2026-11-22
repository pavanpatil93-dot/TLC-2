package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import com.example.data.EmployeeActivityJournal
import com.example.data.UserSettingsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.speech.RecognizerIntent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.crm.*
import com.example.data.projects.DirectoryProject
import com.example.model.*
import com.example.ui.components.PocTypeChipSelector
import com.example.ui.components.SearchableDropdownField
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private data class CrmCallsComputedState(
    val activeCallsCount: Int = 0,
    val unlinkedCount: Int = 0,
    val unwantedCount: Int = 0,
    val ignoredCount: Int = 0,
    val internalCount: Int = 0,
    val todayCallsCount: Int = 0,
    val convertedCount: Int = 0,
    val missedCount: Int = 0,
    val incomingCount: Int = 0,
    val outgoingCount: Int = 0,
    val filteredCalls: List<CrmCallRecord> = emptyList(),
    val followUpByCallId: Map<String, Pair<FollowUpState, String?>> = emptyMap(),
    val unwantedReasonByCallId: Map<String, UnwantedCallReason> = emptyMap()
)

@Composable
private fun CompactDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowCustom: Boolean = false
) {
    TlcSurfaceDropdownField(
        label = label,
        selectedValue = selectedValue,
        options = options,
        onValueSelected = onValueSelected,
        modifier = modifier,
        allowCustom = allowCustom
    )
}

enum class OpportunityCategory {
    CLIENT, OWNER_DEVELOPER, BROKER_PARTNER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrmCallsScreen(
    inventory: List<PropertyItem> = emptyList(),
    onAddPropertyToInventory: ((PropertyItem) -> Boolean)? = null,
    onNavigateToClients: (() -> Unit)? = null,
    onNewCallReview: (() -> Unit)? = null,
    onOpenSavedReviews: (() -> Unit)? = null,
    onOpenPostCallSheet: ((CrmCallRecord) -> Unit)? = null,
    onOpenPostCallQueue: ((List<CrmCallRecord>, Int) -> Unit)? = null,
    externalRefreshTick: Int = 0
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = remember(context) { UserSettingsManager(context) }
    val userProfile by settingsManager.userProfile.collectAsState()
    val revealMaskedPhones by settingsManager.revealConfidentialPhonesForExec.collectAsState()
    val canViewUnmaskedConfidential = com.example.model.PhoneMaskingUtils.canViewUnmaskedConfidentialPhone(
        userProfile = userProfile,
        revealToggleOn = revealMaskedPhones
    )
    var calls by remember { mutableStateOf<List<CrmCallRecord>>(emptyList()) }
    LaunchedEffect(externalRefreshTick) {
        calls = withContext(Dispatchers.IO) {
            CrmCallManager.retagInternalTeamCalls(context)
            TeamHandoverStore.reconcileWithCallLogs(context)
            CrmCallManager.readCalls(context)
        }
    }
    var selectedFilter by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }

    var callForOpportunity by remember { mutableStateOf<CrmCallRecord?>(null) }
    var showQuickIntakeDialog by remember { mutableStateOf(false) }
    var showActivitiesDialog by remember { mutableStateOf(false) }
    var pendingReview by remember { mutableStateOf<CrmCallRecord?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            coroutineScope.launch {
                val (deviceCalls, updatedCalls, firstNew) = withContext(Dispatchers.IO) {
                    val fetched = CrmCallManager.safeReadDeviceCallLogs(context)
                    if (fetched.isNotEmpty()) {
                        val existingIds = calls.map { it.id }.toSet()
                        val newOnes = fetched.filterNot { it.id in existingIds }
                        val merged = newOnes + calls
                        CrmCallManager.saveCalls(context, merged)
                        Triple(fetched, merged, newOnes.firstOrNull())
                    } else {
                        Triple(emptyList(), calls, null)
                    }
                }
                if (deviceCalls.isNotEmpty()) {
                    val addedCount = updatedCalls.size - calls.size
                    calls = updatedCalls
                    pendingReview = firstNew
                    Toast.makeText(context, "Imported $addedCount calls. Review each business call before classifying.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, CrmCallManager.lastDeviceReadError ?: "No recent calls found in call log", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(
                context,
                "Device call log access denied. Use manual Quick Call intake anytime.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val unlinkedCount: Int
    val todayCallsCount: Int
    val convertedCount: Int
    var debouncedSearchQuery by remember { mutableStateOf(searchQuery) }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            delay(250)
            debouncedSearchQuery = searchQuery
        }
    }

    val today = remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.timeInMillis
    }

    var callsComputed by remember { mutableStateOf(CrmCallsComputedState()) }
    var skippedRefreshTick by remember { mutableIntStateOf(0) }

    // Single-pass batch Samsung/OEM recording match state (scanned once off the UI thread)
    var recordingOutcomesByCallId by remember { mutableStateOf<Map<String, CallRecordingMatchOutcome>>(emptyMap()) }
    var isBatchMatchingRecordings by remember { mutableStateOf(false) }
    var recordingScanTick by remember { mutableIntStateOf(0) }

    // Single shared MediaPlayer controller across the Calls list (prevents overlapping audio & leaks)
    var activePlayingCallId by remember { mutableStateOf<String?>(null) }
    var inlineMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var inlineAudioErrorByCallId by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun stopInlinePlayback() {
        runCatching {
            inlineMediaPlayer?.stop()
            inlineMediaPlayer?.release()
        }
        inlineMediaPlayer = null
        activePlayingCallId = null
    }

    fun toggleInlineRecordingPlay(call: CrmCallRecord, recording: MatchedCallRecording) {
        if (activePlayingCallId == call.id) {
            stopInlinePlayback()
            return
        }
        stopInlinePlayback()
        inlineAudioErrorByCallId = inlineAudioErrorByCallId - call.id
        runCatching {
            if (!CallRecordingMatcher.canOpenAudioRecording(context, recording)) {
                throw IllegalStateException("Audio file cannot be opened")
            }
            val mp = MediaPlayer()
            if (recording.isContentUri || recording.uriOrPath.startsWith("content://")) {
                mp.setDataSource(context, Uri.parse(recording.uriOrPath))
            } else {
                mp.setDataSource(recording.uriOrPath)
            }
            mp.setOnCompletionListener {
                if (activePlayingCallId == call.id) {
                    stopInlinePlayback()
                }
            }
            mp.prepare()
            mp.start()
            inlineMediaPlayer = mp
            activePlayingCallId = call.id
        }.onFailure {
            stopInlinePlayback()
            inlineAudioErrorByCallId = inlineAudioErrorByCallId + (
                call.id to "Could not open \"${recording.fileName}\". Call notes were left intact."
            )
            Toast.makeText(context, "Unable to open ${recording.fileName}", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopInlinePlayback()
        }
    }

    // Batch-match all loaded calls in ONE background folder scan (no per-card folder scan!)
    LaunchedEffect(calls, recordingScanTick) {
        if (calls.isEmpty()) {
            recordingOutcomesByCallId = emptyMap()
            isBatchMatchingRecordings = false
            return@LaunchedEffect
        }
        isBatchMatchingRecordings = true
        val outcomes = CallRecordingMatcher.matchRecordingOutcomesForCallsOnDemand(context, calls)
        recordingOutcomesByCallId = outcomes
        isBatchMatchingRecordings = false
    }

    // Multi-select bulk cleanup state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedCallIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(selectedFilter) {
        selectedCallIds = emptySet()
    }

    LaunchedEffect(calls, selectedFilter, debouncedSearchQuery, externalRefreshTick, skippedRefreshTick) {
        callsComputed = withContext(Dispatchers.Default) {
            var activeCount = 0
            var unlinked = 0
            var unwanted = 0
            var ignored = 0
            var internal = 0
            var todayCount = 0
            var converted = 0
            var missed = 0
            var incoming = 0
            var outgoing = 0
            val q = debouncedSearchQuery.trim()
            val filtered = ArrayList<CrmCallRecord>(calls.size)
            val followUpMap = HashMap<String, Pair<FollowUpState, String?>>(calls.size)
            val unwantedMap = HashMap<String, UnwantedCallReason>(calls.size)

            for (call in calls) {
                val isInternal = CrmCallManager.isInternalTeamCall(context, call)
                val isIgnored = CrmCallManager.isCallSkipped(context, call)
                val unwantedReason = if (!isInternal && !call.isLinked) {
                    CrmCallManager.classifyUnwantedCall(context, call)
                } else {
                    UnwantedCallReason.NONE
                }
                unwantedMap[call.id] = unwantedReason

                if (isIgnored && !call.isLinked) {
                    ignored++
                } else {
                    activeCount++
                    when {
                        isInternal -> internal++
                        !call.isLinked -> {
                            unlinked++
                            if (unwantedReason != UnwantedCallReason.NONE) unwanted++
                        }
                        else -> converted++
                    }
                    if (call.timestamp >= today) todayCount++
                    when (call.callType) {
                        CallType.MISSED -> missed++
                        CallType.INCOMING -> incoming++
                        CallType.OUTGOING -> outgoing++
                    }
                }

                val matchesFilter = when (selectedFilter) {
                    "All" -> !isIgnored
                    "Unlinked" -> !call.isLinked && !isIgnored && !isInternal
                    "Unwanted" -> !call.isLinked && !isIgnored && !isInternal && unwantedReason != UnwantedCallReason.NONE
                    "Ignored" -> isIgnored && !call.isLinked
                    "Internal" -> isInternal && !isIgnored
                    "Incoming" -> !isIgnored && call.callType == CallType.INCOMING
                    "Outgoing" -> !isIgnored && call.callType == CallType.OUTGOING
                    "Missed" -> !isIgnored && call.callType == CallType.MISSED
                    "Converted" -> call.isLinked
                    else -> !isIgnored
                }
                val matchesSearch = q.isBlank() ||
                    call.callerName.contains(q, ignoreCase = true) ||
                    call.phoneNumber.contains(q, ignoreCase = true) ||
                    call.notes.contains(q, ignoreCase = true) ||
                    call.linkedOpportunityTitle.orEmpty().contains(q, ignoreCase = true)

                if (matchesFilter && matchesSearch) {
                    filtered.add(call)
                    val fDate = CallFollowUpStore.get(context, call.id)
                    followUpMap[call.id] = CallFollowUpStore.state(fDate) to fDate
                }
            }

            CrmCallsComputedState(
                activeCallsCount = activeCount,
                unlinkedCount = unlinked,
                unwantedCount = unwanted,
                ignoredCount = ignored,
                internalCount = internal,
                todayCallsCount = todayCount,
                convertedCount = converted,
                missedCount = missed,
                incomingCount = incoming,
                outgoingCount = outgoing,
                filteredCalls = filtered,
                followUpByCallId = followUpMap,
                unwantedReasonByCallId = unwantedMap
            )
        }
    }

    unlinkedCount = callsComputed.unlinkedCount
    todayCallsCount = callsComputed.todayCallsCount
    convertedCount = callsComputed.convertedCount
    val filteredCalls = callsComputed.filteredCalls
    val followUpByCallId = callsComputed.followUpByCallId
    val unwantedReasonByCallId = callsComputed.unwantedReasonByCallId
    val matchedRecordingsCount = remember(recordingOutcomesByCallId, calls, skippedRefreshTick) {
        calls.count { call ->
            !CrmCallManager.isCallSkipped(context, call) &&
                recordingOutcomesByCallId[call.id]?.status == CallRecordingMatchStatus.MATCHED
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("crm_calls_screen")
    ) {
        // Slim 1-line summary bar + Activity Logs button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactCallStatPill(label = "All", value = "${callsComputed.activeCallsCount}")
                CompactCallStatPill(
                    label = "Unlinked",
                    value = "$unlinkedCount",
                    highlight = unlinkedCount > 0
                )
                CompactCallStatPill(
                    label = "Recs",
                    value = if (isBatchMatchingRecordings) "..." else "$matchedRecordingsCount",
                    highlight = matchedRecordingsCount > 0
                )
                CompactCallStatPill(label = "Today", value = "$todayCallsCount")
                CompactCallStatPill(label = "Conv", value = "$convertedCount")
            }

            IconButton(
                onClick = { showActivitiesDialog = true },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("calls_activity_history_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Activity logs",
                    tint = BrandGold
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Compact single-row action bar: + Call | Import | Review | Saved | Demo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { showQuickIntakeDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("calls_add_btn")
            ) {
                Icon(Icons.Default.PhoneCallback, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("+ Call", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_CALL_LOG
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPerm) {
                        coroutineScope.launch {
                            val (newOnesCount, mergedCalls, firstNew) = withContext(Dispatchers.IO) {
                                val deviceCalls = CrmCallManager.safeReadDeviceCallLogs(context)
                                val existingIds = calls.map { it.id }.toSet()
                                val newOnes = deviceCalls.filterNot { it.id in existingIds }
                                val updated = newOnes + calls
                                CrmCallManager.saveCalls(context, updated)
                                Triple(newOnes.size, updated, newOnes.firstOrNull())
                            }
                            calls = mergedCalls
                            pendingReview = firstNew
                            Toast.makeText(context, CrmCallManager.lastDeviceReadError ?: "Imported $newOnesCount calls. Review business calls before classifying.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
                    }
                },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("calls_import_btn")
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = {
                    if (isSelectionMode) {
                        isSelectionMode = false
                        selectedCallIds = emptySet()
                    } else {
                        isSelectionMode = true
                        if (selectedFilter == "All" && callsComputed.unwantedCount > 0) {
                            selectedCallIds = filteredCalls
                                .filter { (unwantedReasonByCallId[it.id] ?: UnwantedCallReason.NONE) != UnwantedCallReason.NONE }
                                .map { it.id }
                                .toSet()
                        }
                    }
                },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    1.dp,
                    if (isSelectionMode || callsComputed.unwantedCount > 0) PrimaryActionOrange.copy(alpha = 0.7f) else BorderGrey
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("calls_cleanup_select_btn")
            ) {
                Icon(
                    imageVector = if (isSelectionMode) Icons.Default.Close else Icons.Default.CleaningServices,
                    contentDescription = "Bulk clean up unwanted calls",
                    tint = if (isSelectionMode || callsComputed.unwantedCount > 0) PrimaryActionOrange else TextPrimaryDark,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isSelectionMode) "Done" else if (callsComputed.unwantedCount > 0) "Clean (${callsComputed.unwantedCount})" else "Select",
                    fontSize = 12.sp
                )
            }

            if (onNewCallReview != null || onOpenPostCallQueue != null || onOpenPostCallSheet != null) {
                OutlinedButton(
                    onClick = {
                        stopInlinePlayback()
                        val unlinkedQueue = calls.filter {
                            !it.isLinked &&
                                !CrmCallManager.isCallSkipped(context, it) &&
                                !CrmCallManager.isInternalTeamCall(context, it)
                        }
                        when {
                            onOpenPostCallQueue != null && unlinkedQueue.isNotEmpty() -> {
                                onOpenPostCallQueue(unlinkedQueue, 0)
                            }
                            onOpenPostCallSheet != null && unlinkedQueue.isNotEmpty() -> {
                                onOpenPostCallSheet(unlinkedQueue.first())
                            }
                            onNewCallReview != null -> onNewCallReview()
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("calls_new_review_btn")
                ) {
                    Icon(Icons.Default.RateReview, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Review", fontSize = 12.sp)
                }
            }

            if (onOpenSavedReviews != null) {
                OutlinedButton(
                    onClick = {
                        stopInlinePlayback()
                        onOpenSavedReviews()
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("calls_saved_reviews_btn")
                ) {
                    Icon(Icons.Default.Bookmarks, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Saved", fontSize = 12.sp)
                }
            }

            if (calls.none { it.id.startsWith("demo_call_") }) {
                TextButton(
                    onClick = {
                        val demo = CrmCallManager.getDemoCalls()
                        val existingIds = calls.map { it.id }.toSet()
                        val updated = demo.filterNot { it.id in existingIds } + calls
                        calls = updated
                        coroutineScope.launch(Dispatchers.IO) {
                            CrmCallManager.saveCalls(context, updated)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("+ Demo", fontSize = 11.sp, color = BrandGold)
                }
            } else {
                TextButton(
                    onClick = {
                        val updated = calls.filterNot { it.id.startsWith("demo_call_") }
                        calls = updated
                        coroutineScope.launch(Dispatchers.IO) {
                            CrmCallManager.saveCalls(context, updated)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("- Demo", fontSize = 11.sp, color = TextSecondaryDark)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Batch 9: Live WhatsApp & WhatsApp Business Call Capture Toggle & Notification Access Bar
        var waCaptureEnabled by remember { mutableStateOf(CrmCallManager.isWhatsAppCallCaptureEnabled(context)) }
        var notifListenerGranted by remember { mutableStateOf(CrmCallManager.isNotificationListenerGranted(context)) }
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, BorderGrey),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("calls_wa_capture_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = null,
                        tint = StatusSuccessGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "WhatsApp Call Capture",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (notifListenerGranted) "Active for WhatsApp & WA Business" else "Optional: Enable notification access to auto-queue WA calls",
                            color = if (notifListenerGranted) StatusSuccessGreen else SecondaryText,
                            fontSize = 10.sp
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!notifListenerGranted) {
                        OutlinedButton(
                            onClick = {
                                CrmCallManager.openNotificationListenerSettings(context)
                                notifListenerGranted = CrmCallManager.isNotificationListenerGranted(context)
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("calls_enable_notif_access_btn")
                        ) {
                            Text("Enable Notification Access", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Switch(
                        checked = waCaptureEnabled,
                        onCheckedChange = { enabled ->
                            waCaptureEnabled = enabled
                            CrmCallManager.setWhatsAppCallCaptureEnabled(context, enabled)
                            notifListenerGranted = CrmCallManager.isNotificationListenerGranted(context)
                        },
                        modifier = Modifier.testTag("calls_wa_capture_switch")
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Compact Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search caller, mobile or notes...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("calls_search_input")
        )

        Spacer(Modifier.height(6.dp))

        // Filter Chips: active calls exclude Ignored; Ignored tab lets user review & restore hidden calls
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val filterOptions = listOf(
                "All" to "${callsComputed.activeCallsCount}",
                "Unlinked" to "$unlinkedCount",
                "Unwanted" to "${callsComputed.unwantedCount}",
                "Missed" to "${callsComputed.missedCount}",
                "Incoming" to "${callsComputed.incomingCount}",
                "Outgoing" to "${callsComputed.outgoingCount}",
                "Converted" to "$convertedCount",
                "Internal" to "${callsComputed.internalCount}",
                "Ignored" to "${callsComputed.ignoredCount}"
            )
            items(filterOptions, key = { it.first }) { (label, count) ->
                FilterChip(
                    selected = selectedFilter == label,
                    onClick = { selectedFilter = label },
                    label = { Text("$label ($count)", fontSize = 11.sp) },
                    modifier = Modifier.testTag("calls_filter_${label.lowercase()}")
                )
            }
        }

        if (isBatchMatchingRecordings) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
                    .testTag("calls_batch_recording_scan_bar")
            ) {
                CircularProgressIndicator(
                    color = PrimaryActionOrange,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "Matching call recordings across linked folder...",
                    fontSize = 11.sp,
                    color = TextSecondaryDark
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Call Records List (Expanded primary content area)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = if (isSelectionMode) 12.dp else 20.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("calls_lazy_list")
        ) {
            items(filteredCalls, key = { it.id }) { call ->
                val followUpEntry = followUpByCallId[call.id]
                val outcome = recordingOutcomesByCallId[call.id]
                val unwantedReason = unwantedReasonByCallId[call.id] ?: UnwantedCallReason.NONE
                CallRecordCard(
                    call = call,
                    onCreateOpportunity = {
                        stopInlinePlayback()
                        val unlinkedQueue = calls.filter { !it.isLinked && !CrmCallManager.isCallSkipped(context, it) }
                        if (!call.isLinked && onOpenPostCallQueue != null && unlinkedQueue.isNotEmpty()) {
                            val idx = unlinkedQueue.indexOfFirst { it.id == call.id }.coerceAtLeast(0)
                            onOpenPostCallQueue(unlinkedQueue, idx)
                        } else if (onOpenPostCallSheet != null) {
                            onOpenPostCallSheet(call)
                        } else {
                            callForOpportunity = call
                        }
                    },
                    onCallUpdated = { updatedCall ->
                        calls = calls.map { if (it.id == updatedCall.id) updatedCall else it }
                        skippedRefreshTick++
                    },
                    loggedInEmployeeName = userProfile.username.ifBlank { "TLC Team" },
                    followUpState = followUpEntry?.first ?: FollowUpState.NONE,
                    followUpDate = followUpEntry?.second,
                    canViewUnmaskedConfidential = canViewUnmaskedConfidential,
                    recordingOutcome = outcome,
                    isPlayingInline = activePlayingCallId == call.id,
                    inlineAudioError = inlineAudioErrorByCallId[call.id],
                    onToggleInlinePlay = { rec -> toggleInlineRecordingPlay(call, rec) },
                    onRecordingOutcomeChanged = { updatedOutcome ->
                        recordingOutcomesByCallId = recordingOutcomesByCallId + (call.id to updatedOutcome)
                    },
                    allCallsForStrictDedup = calls,
                    unwantedReason = unwantedReason,
                    isSelectionMode = isSelectionMode,
                    isSelected = call.id in selectedCallIds,
                    onToggleSelect = {
                        selectedCallIds = if (call.id in selectedCallIds) {
                            selectedCallIds - call.id
                        } else {
                            selectedCallIds + call.id
                        }
                    },
                    isIgnoredTab = selectedFilter == "Ignored",
                    onRestoreIgnored = {
                        CrmCallManager.unskipCall(context, call)
                        skippedRefreshTick++
                        Toast.makeText(context, "Restored ${call.callerName.ifBlank { call.phoneNumber }} to active list", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            if (filteredCalls.isEmpty()) {
                item(key = "calls_empty_state") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (selectedFilter == "Ignored") Icons.Default.CheckCircleOutline else Icons.Default.PhoneMissed,
                                contentDescription = null,
                                tint = TextSecondaryDark,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = when (selectedFilter) {
                                    "Ignored" -> "No ignored calls"
                                    "Unwanted" -> "No unwanted calls detected"
                                    else -> "No calls found for '$selectedFilter'"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = when (selectedFilter) {
                                    "Ignored" -> "Hidden spam, short, or personal calls appear here and can be restored anytime."
                                    "Unwanted" -> "Spam, 140/telemarketer, personal, and <10s calls are auto-tagged here for quick bulk removal."
                                    else -> "Tap 'Import' to load device calls or '+ Call' to log manually."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark
                            )
                        }
                    }
                }
            }
        }

        // Sticky Multi-Select Bulk Cleanup / Restore Bar (sits cleanly above Android gesture bar)
        if (isSelectionMode) {
            val isIgnoredView = selectedFilter == "Ignored"
            Surface(
                color = SurfaceWhite,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.55f)),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 6.dp)
                    .testTag("calls_bulk_action_bar")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedCallIds.size} selected",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!isIgnoredView) {
                                OutlinedButton(
                                    onClick = {
                                        val unwantedIds = filteredCalls
                                            .filter {
                                                !it.isLinked &&
                                                    (unwantedReasonByCallId[it.id] ?: UnwantedCallReason.NONE) != UnwantedCallReason.NONE
                                            }
                                            .map { it.id }
                                            .toSet()
                                        selectedCallIds = unwantedIds
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .heightIn(min = 38.dp)
                                        .testTag("calls_select_all_unwanted_btn")
                                ) {
                                    Text("Select Unwanted", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = PrimaryActionOrange)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        selectedCallIds = filteredCalls.map { it.id }.toSet()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .heightIn(min = 38.dp)
                                        .testTag("calls_select_all_ignored_btn")
                                ) {
                                    Text("Select All", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = PrimaryActionOrange)
                                }
                            }
                            TextButton(
                                onClick = {
                                    isSelectionMode = false
                                    selectedCallIds = emptySet()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 38.dp)
                                    .testTag("calls_bulk_cancel_btn")
                            ) {
                                Text("Cancel", fontSize = 11.sp, color = SecondaryText)
                            }
                        }
                    }

                    Button(
                        enabled = selectedCallIds.isNotEmpty(),
                        onClick = {
                            val targets = calls.filter { it.id in selectedCallIds }
                            if (activePlayingCallId in selectedCallIds) {
                                stopInlinePlayback()
                            }
                            if (isIgnoredView) {
                                CrmCallManager.unskipCalls(context, targets)
                                Toast.makeText(context, "Restored ${targets.size} call(s) to active list", Toast.LENGTH_SHORT).show()
                            } else {
                                val hideable = targets.filterNot { it.isLinked }
                                CrmCallManager.markCallsSkipped(context, hideable)
                                val msg = if (hideable.size < targets.size) {
                                    "Moved ${hideable.size} unlinked call(s) to Ignored tab (linked calls kept)"
                                } else {
                                    "Moved ${hideable.size} call(s) to Ignored tab (reversible)"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            selectedCallIds = emptySet()
                            isSelectionMode = false
                            skippedRefreshTick++
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("calls_bulk_confirm_btn")
                    ) {
                        Icon(
                            imageVector = if (isIgnoredView) Icons.Default.Restore else Icons.Default.VisibilityOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isIgnoredView) {
                                "Restore Selected (${selectedCallIds.size})"
                            } else {
                                "Hide Selected (${selectedCallIds.size}) to Ignored"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    pendingReview?.let { call ->
        val loggedInEmployee = remember {
            UserSettingsManager(context).userProfile.value.username.ifBlank { "TLC-1 Team" }
        }
        val phoneKey = remember(call.phoneNumber) { CrmCallManager.last10Digits(call.phoneNumber) }
        var matchedLead by remember(call.id) { mutableStateOf<CrmLead?>(null) }
        val callerRoles = remember {
            listOf("Client", "CP / Broker", "Owner", "Developer", "Team", "Personal", "Other")
        }
        val outcomeOptions = remember {
            listOf(
                "Call Back",
                "Share Shortlist",
                "Schedule Site Visit",
                "Follow-up After Call",
                "RNR / No Response",
                "Personal / Ignore"
            )
        }
        var callerRole by remember(call.id) { mutableStateOf("Client") }
        var actionOutcome by remember(call.id) { mutableStateOf("Call Back") }
        var discussionNotes by remember(call.id) { mutableStateOf("") }
        var followUpDate by remember(call.id) { mutableStateOf("") }
        var followUpTime by remember(call.id) { mutableStateOf("") }
        var linkToCrmLead by remember(call.id) { mutableStateOf(false) }
        var leadNameInput by remember(call.id) {
            mutableStateOf(
                call.callerName.takeIf {
                    it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
                }.orEmpty()
            )
        }
        LaunchedEffect(call.id) {
            val found = withContext(Dispatchers.IO) {
                readCrmLeads(context).firstOrNull { lead ->
                    (phoneKey.length >= 7 && (
                        CrmCallManager.last10Digits(lead.phone) == phoneKey ||
                            CrmCallManager.last10Digits(lead.alternatePhone) == phoneKey
                        )) || (!call.linkedOpportunityId.isNullOrBlank() && lead.id == call.linkedOpportunityId)
                }
            }
            matchedLead = found
            if (found != null) {
                linkToCrmLead = true
                if (leadNameInput.isBlank()) {
                    leadNameInput = found.displayClientTitle()
                }
            }
        }

        val voiceLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull().orEmpty().trim()
                if (spoken.isNotBlank()) {
                    discussionNotes = if (discussionNotes.isBlank()) spoken else "${discussionNotes.trim()} $spoken"
                }
            }
        }

        fun isoOffsetDays(days: Int): String {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, days)
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }

        fun openDatePicker() {
            val cal = Calendar.getInstance()
            runCatching {
                if (followUpDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(followUpDate)?.let { cal.time = it }
                }
            }
            DatePickerDialog(
                context,
                { _, y, m, d ->
                    followUpDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                    if (followUpTime.isBlank()) followUpTime = "11:00 AM"
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        fun openTimePicker() {
            val cal = Calendar.getInstance()
            TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    val picked = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hourOfDay)
                        set(Calendar.MINUTE, minute)
                    }
                    followUpTime = SimpleDateFormat("hh:mm a", Locale.US).format(picked.time)
                    if (followUpDate.isBlank()) followUpDate = isoOffsetDays(0)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(context)
            ).show()
        }

        val isPersonalSelected = callerRole == "Personal" || actionOutcome == "Personal / Ignore"

        ModalBottomSheet(
            onDismissRequest = { pendingReview = null },
            containerColor = BackgroundSoft,
            dragHandle = { BottomSheetDefaults.DragHandle(color = SecondaryText) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("review_imported_call_sheet"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Review Imported Call",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${call.callerName.ifBlank { call.phoneNumber }} · ${call.phoneNumber}",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${call.callType.label} · ${CrmCallManager.formatDuration(call.durationSeconds)} · ${CrmCallManager.formatTimestamp(call.timestamp)}",
                            fontSize = 11.sp,
                            color = SecondaryText
                        )
                    }
                    IconButton(
                        onClick = { pendingReview = null },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = SecondaryText)
                    }
                }

                // 2-Column Role & Action / Outcome Dropdowns
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactDropdownField(
                        label = "Caller Role",
                        selectedValue = callerRole,
                        options = callerRoles,
                        onValueSelected = { chosen ->
                            callerRole = chosen
                            if (chosen == "Personal") {
                                actionOutcome = "Personal / Ignore"
                                followUpDate = ""
                                followUpTime = ""
                                linkToCrmLead = false
                            } else if (actionOutcome == "Personal / Ignore") {
                                actionOutcome = "Call Back"
                            }
                        },
                        allowCustom = false,
                        modifier = Modifier.weight(1f)
                    )
                    CompactDropdownField(
                        label = "Action / Outcome",
                        selectedValue = actionOutcome,
                        options = outcomeOptions,
                        onValueSelected = { chosen ->
                            actionOutcome = chosen
                            if (chosen == "Personal / Ignore") {
                                callerRole = "Personal"
                                followUpDate = ""
                                followUpTime = ""
                                linkToCrmLead = false
                            }
                        },
                        allowCustom = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Discussion / Next Action with Voice-to-Note Mic
                OutlinedTextField(
                    value = discussionNotes,
                    onValueChange = { discussionNotes = it },
                    label = { Text("Discussion / Next Action", fontSize = 12.sp) },
                    placeholder = { Text("Tap mic to dictate notes or next step...", fontSize = 12.sp) },
                    minLines = 2,
                    maxLines = 3,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak call summary")
                                }
                                runCatching { voiceLauncher.launch(intent) }.onFailure {
                                    Toast.makeText(context, "Voice input unavailable on this device", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("review_import_mic_btn")
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Voice note", tint = PrimaryActionOrange)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite,
                        focusedBorderColor = PrimaryActionOrange,
                        unfocusedBorderColor = BorderGrey
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Quick Action Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val todayStr = remember { isoOffsetDays(0) }
                    val tomorrowStr = remember { isoOffsetDays(1) }
                    val plus3Str = remember { isoOffsetDays(3) }

                    FilterChip(
                        selected = followUpDate == todayStr && !isPersonalSelected,
                        onClick = {
                            if (callerRole == "Personal") callerRole = "Client"
                            if (actionOutcome == "Personal / Ignore") actionOutcome = "Call Back"
                            followUpDate = todayStr
                            if (followUpTime.isBlank()) followUpTime = "04:00 PM"
                        },
                        label = { Text("Today", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = followUpDate == tomorrowStr && !isPersonalSelected,
                        onClick = {
                            if (callerRole == "Personal") callerRole = "Client"
                            if (actionOutcome == "Personal / Ignore") actionOutcome = "Call Back"
                            followUpDate = tomorrowStr
                            if (followUpTime.isBlank()) followUpTime = "11:00 AM"
                        },
                        label = { Text("Tomorrow", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = followUpDate == plus3Str && !isPersonalSelected,
                        onClick = {
                            if (callerRole == "Personal") callerRole = "Client"
                            if (actionOutcome == "Personal / Ignore") actionOutcome = "Call Back"
                            followUpDate = plus3Str
                            if (followUpTime.isBlank()) followUpTime = "11:00 AM"
                        },
                        label = { Text("+3 Days", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = isPersonalSelected,
                        onClick = {
                            callerRole = "Personal"
                            actionOutcome = "Personal / Ignore"
                            followUpDate = ""
                            followUpTime = ""
                            linkToCrmLead = false
                        },
                        label = { Text("Personal (Ignore)", fontSize = 11.sp) }
                    )
                }

                // Side-by-side Date & Time Native Pickers
                if (!isPersonalSelected) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { openDatePicker() },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (followUpDate.isNotBlank()) PrimaryActionOrange else BorderGrey),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("review_import_date_btn")
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp), tint = PrimaryActionOrange)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = followUpDate.ifBlank { "Date" },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1
                            )
                        }
                        OutlinedButton(
                            onClick = { openTimePicker() },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (followUpTime.isNotBlank()) PrimaryActionOrange else BorderGrey),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("review_import_time_btn")
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = PrimaryActionOrange)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = followUpTime.ifBlank { "Time" },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1
                            )
                        }
                    }

                    // Link or Create CRM Lead Toggle
                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (linkToCrmLead) PrimaryActionOrange else BorderGrey),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { linkToCrmLead = !linkToCrmLead }
                            .testTag("review_import_link_lead_toggle")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = linkToCrmLead,
                                    onCheckedChange = { linkToCrmLead = it },
                                    colors = CheckboxDefaults.colors(checkedColor = PrimaryActionOrange, checkmarkColor = OnPrimaryActionColor)
                                )
                                Text(
                                    text = matchedLead?.let { foundLead ->
                                        "Link to existing CRM Lead: ${foundLead.displayClientTitle()} (${foundLead.id})"
                                    } ?: "+ Create / Link CRM Lead",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (linkToCrmLead && matchedLead == null) {
                                OutlinedTextField(
                                    value = leadNameInput,
                                    onValueChange = { leadNameInput = it },
                                    label = { Text("Client / Broker Name", fontSize = 11.5.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // Bottom Pinned Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { pendingReview = null },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(0.75f)
                            .heightIn(min = 48.dp)
                            .testTag("review_import_skip_btn")
                    ) {
                        Text("Skip", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Button(
                        onClick = {
                            if (isPersonalSelected) {
                                coroutineScope.launch {
                                    val updatedList = withContext(Dispatchers.IO) {
                                        IgnoredNumbersManager.markPersonalIgnored(context, call.phoneNumber)
                                        val noteLine = buildString {
                                            append("Personal (Ignored)")
                                            if (discussionNotes.isNotBlank()) append(" — ").append(discussionNotes.trim())
                                        }
                                        CrmCallManager.addOrUpdateCall(context, call.copy(notes = noteLine))
                                        CrmCallManager.readCalls(context)
                                    }
                                    calls = updatedList
                                    pendingReview = null
                                    Toast.makeText(context, "Marked Personal & muted from call pop-ups", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                val todayIso = isoOffsetDays(0)
                                val currentMatched = matchedLead
                                coroutineScope.launch {
                                    val updatedList = withContext(Dispatchers.IO) {
                                        val resolvedLead: CrmLead? = if (linkToCrmLead) {
                                            val baseLead = currentMatched?.copy(
                                                lastContactDate = todayIso,
                                                lastContactMode = "Call",
                                                lastContactOutcome = actionOutcome,
                                                nextFollowUp = followUpDate.ifBlank { currentMatched.nextFollowUp },
                                                nextFollowUpTime = followUpTime.ifBlank { currentMatched.nextFollowUpTime },
                                                followUpTime = followUpTime.ifBlank { currentMatched.followUpTime },
                                                notes = if (discussionNotes.isNotBlank()) {
                                                    "${discussionNotes.trim()}\n${currentMatched.notes}".trim()
                                                } else {
                                                    currentMatched.notes
                                                }
                                            ) ?: CrmLead(
                                                id = "",
                                                name = leadNameInput.trim().ifBlank { call.callerName.ifBlank { "Client ${call.phoneNumber}" } },
                                                phone = ContactUtils.normalizePhoneNumber(call.phoneNumber),
                                                segment = "Commercial",
                                                requirement = discussionNotes.trim().ifBlank { "$callerRole Call Follow-up" },
                                                location = "Pune",
                                                budget = "Negotiable",
                                                status = if (followUpDate.isNotBlank()) "Follow-up Scheduled" else "New Lead",
                                                verificationStatus = "Verified Live",
                                                nextFollowUp = followUpDate,
                                                nextFollowUpTime = followUpTime,
                                                followUpTime = followUpTime,
                                                notes = discussionNotes.trim(),
                                                assignedTo = loggedInEmployee,
                                                clientType = if (callerRole.contains("Broker", true)) "Broker / CP" else "Direct Client",
                                                leadSource = if (callerRole.contains("Broker", true)) "Channel Partner / Broker" else "Inbound Call",
                                                lastContactDate = todayIso,
                                                lastContactMode = "Call",
                                                lastContactOutcome = actionOutcome
                                            )
                                            upsertCrmLeadFromPostCall(
                                                context = context,
                                                lead = baseLead,
                                                activityType = "Call Reviewed • $callerRole",
                                                activityNotes = discussionNotes.trim().ifBlank { actionOutcome },
                                                performer = loggedInEmployee
                                            )
                                        } else {
                                            null
                                        }
                                        CrmCallManager.saveReviewedCallAndSchedule(
                                            context = context,
                                            call = call,
                                            callerRole = callerRole,
                                            actionOutcome = actionOutcome,
                                            discussionNotes = discussionNotes,
                                            followUpDate = followUpDate,
                                            followUpTime = followUpTime,
                                            assignedEmployee = loggedInEmployee,
                                            linkedLeadId = resolvedLead?.id,
                                            linkedLeadName = resolvedLead?.displayClientTitle()
                                        )
                                        CrmCallManager.readCalls(context)
                                    }
                                    calls = updatedList
                                    pendingReview = null
                                    val toastMsg = if (followUpDate.isNotBlank()) {
                                        "Call saved & follow-up scheduled"
                                    } else {
                                        "Call reviewed & saved"
                                    }
                                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1.25f)
                            .heightIn(min = 48.dp)
                            .testTag("review_import_save_btn")
                    ) {
                        Text("Save & Link Call", fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }

    if (showQuickIntakeDialog) {
        QuickCallIntakeDialog(
            onDismiss = { showQuickIntakeDialog = false },
            onSaveCall = { newCall ->
                val updated = listOf(newCall) + calls
                calls = updated
                showQuickIntakeDialog = false
                coroutineScope.launch(Dispatchers.IO) {
                    CrmCallManager.saveCalls(context, updated)
                    CrmCallManager.logActivity(
                        context = context,
                        entityId = newCall.id,
                        type = "Call Logged",
                        summary = "${newCall.callType.label} Call logged with ${newCall.callerName}",
                        details = "Phone: ${newCall.phoneNumber} • ${newCall.notes}"
                    )
                }
            }
        )
    }

    callForOpportunity?.let { call ->
        NewOpportunityDialog(
            call = call,
            allProperties = inventory,
            onDismiss = { callForOpportunity = null },
            onOpportunityCreated = { _, oppTitle, _ ->
                coroutineScope.launch {
                    val refreshed = withContext(Dispatchers.IO) { CrmCallManager.readCalls(context) }
                    calls = refreshed
                    callForOpportunity = null
                    Toast.makeText(context, "Opportunity saved and call linked: $oppTitle", Toast.LENGTH_SHORT).show()
                }
            },
            onAddPropertyToInventory = onAddPropertyToInventory
        )
    }

    if (showActivitiesDialog) {
        ActivityHistoryDialog(
            onDismiss = { showActivitiesDialog = false }
        )
    }
}

@Composable
private fun CompactCallStatPill(
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) Color(0xFFFEF2F2) else BrandCharcoalSurface,
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            if (highlight) Color(0xFFFCA5A5) else BrandCharcoalBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 10.5.sp,
                color = if (highlight) Color(0xFFDC2626) else TextSecondaryDark,
                maxLines = 1
            )
            Text(
                text = value,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (highlight) Color(0xFFB91C1C) else TextPrimaryDark,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CallRecordCard(
    call: CrmCallRecord,
    onCreateOpportunity: () -> Unit,
    onCallUpdated: (CrmCallRecord) -> Unit = {},
    loggedInEmployeeName: String = "TLC Team",
    followUpState: FollowUpState = FollowUpState.NONE,
    followUpDate: String? = null,
    canViewUnmaskedConfidential: Boolean = true,
    recordingOutcome: CallRecordingMatchOutcome? = null,
    isPlayingInline: Boolean = false,
    inlineAudioError: String? = null,
    onToggleInlinePlay: ((MatchedCallRecording) -> Unit)? = null,
    onRecordingOutcomeChanged: ((CallRecordingMatchOutcome) -> Unit)? = null,
    allCallsForStrictDedup: List<CrmCallRecord>? = null,
    unwantedReason: UnwantedCallReason = UnwantedCallReason.NONE,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    isIgnoredTab: Boolean = false,
    onRestoreIgnored: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showRecordingPanel by remember(call.id) { mutableStateOf(false) }
    val isSkipped = remember(call.id, call.isLinked, call.notes, isIgnoredTab) {
        CrmCallManager.isCallSkipped(context, call)
    }
    val (typeIcon, typeColor) = when (call.callType) {
        CallType.INCOMING -> Icons.Default.CallReceived to Color(0xFF10B981)
        CallType.OUTGOING -> Icons.Default.CallMade to Color(0xFF3B82F6)
        CallType.MISSED -> Icons.Default.CallMissed to Color(0xFFEF4444)
    }
    val isConfidentialRole = call.linkedOpportunityType?.contains("Owner", true) == true ||
        call.linkedOpportunityType?.contains("Tenant", true) == true
    val displayPhone = com.example.model.PhoneMaskingUtils.formatPhoneForRole(
        rawPhone = call.phoneNumber,
        canViewFull = !isConfidentialRole || canViewUnmaskedConfidential
    )
    val hasWaLogged = call.waLogged || call.notes.contains("Connected on WhatsApp", ignoreCase = true)
    val isInternalTeam = remember(call.id, call.phoneNumber, call.linkedOpportunityType) {
        CrmCallManager.isInternalTeamCall(context, call)
    }
    val handoverOverdue2h = remember(call.id, call.handoverStatus, call.handedOverAt) {
        call.handoverStatus.equals("Pending Callback", ignoreCase = true) &&
            call.handedOverAt > 0L &&
            (System.currentTimeMillis() - call.handedOverAt) > (2L * 60L * 60L * 1000L)
    }
    val matchedRecording = recordingOutcome?.takeIf { it.status == CallRecordingMatchStatus.MATCHED }?.matched
    val isAmbiguousRecording = recordingOutcome?.status == CallRecordingMatchStatus.AMBIGUOUS &&
        recordingOutcome.ambiguousCandidates.isNotEmpty()
    val cardBorderColor = when {
        isSelected -> PrimaryActionOrange
        isPlayingInline -> PrimaryActionOrange.copy(alpha = 0.85f)
        else -> BrandCharcoalBorder
    }
    val cardBorderWidth = if (isSelected || isPlayingInline) 1.5.dp else 0.8.dp
    val cardModifier = Modifier
        .fillMaxWidth()
        .let { base ->
            if (isSelectionMode && onToggleSelect != null) {
                base.clickable { onToggleSelect() }
            } else {
                base
            }
        }
        .testTag("call_record_card_${call.id}")

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) PrimaryActionOrange.copy(alpha = 0.06f) else BrandCharcoalSurface
        ),
        border = BorderStroke(cardBorderWidth, cardBorderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect?.invoke() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = PrimaryActionOrange,
                                checkmarkColor = Color.White
                            ),
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("call_card_checkbox_${call.id}")
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(typeColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = call.callType.label,
                            tint = typeColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(10.dp))

                    Column {
                        Text(
                            text = call.callerName.ifBlank { "Unknown Caller" },
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimaryDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$displayPhone • ${CrmCallManager.formatTimestamp(call.timestamp)}",
                            fontSize = 11.sp,
                            color = TextSecondaryDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (unwantedReason != UnwantedCallReason.NONE && !call.isLinked) {
                        val (uBg, uFg) = when (unwantedReason) {
                            UnwantedCallReason.SPAM_TELEMARKETER -> Color(0xFFFEF2F2) to Color(0xFFDC2626)
                            UnwantedCallReason.PERSONAL_OR_IGNORED -> Color(0xFFF3E8FF) to Color(0xFF7E22CE)
                            UnwantedCallReason.SHORT_OR_MISSED -> Color(0xFFFFFBEB) to Color(0xFFB45309)
                            UnwantedCallReason.NONE -> Color.Transparent to TextSecondaryDark
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = uBg,
                            modifier = Modifier.testTag("call_card_unwanted_badge_${call.id}")
                        ) {
                            Text(
                                text = unwantedReason.badgeLabel,
                                color = uFg,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (isInternalTeam) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF0EA5E9).copy(alpha = 0.16f),
                            modifier = Modifier.testTag("call_record_internal_badge_${call.id}")
                        ) {
                            Text(
                                text = "Internal Team",
                                color = Color(0xFF0284C7),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (call.handoverStatus.isNotBlank()) {
                        val calledBack = call.handoverStatus.equals("Called Back", ignoreCase = true)
                        val hoBg = when {
                            calledBack -> Color(0xFF10B981).copy(alpha = 0.16f)
                            handoverOverdue2h -> Color(0xFFEF4444).copy(alpha = 0.16f)
                            else -> Color(0xFFF59E0B).copy(alpha = 0.16f)
                        }
                        val hoFg = when {
                            calledBack -> Color(0xFF059669)
                            handoverOverdue2h -> Color(0xFFDC2626)
                            else -> Color(0xFFD97706)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = hoBg,
                            modifier = Modifier.testTag("call_record_handover_badge_${call.id}")
                        ) {
                            Text(
                                text = if (calledBack) "Called Back" else if (handoverOverdue2h) "Callback >2h" else "Pending Callback",
                                color = hoFg,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (hasWaLogged) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF1E7E45).copy(alpha = 0.14f),
                            modifier = Modifier.testTag("call_record_wa_badge_${call.id}")
                        ) {
                            Text(
                                text = "WA",
                                color = Color(0xFF1E7E45),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = typeColor.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = if (call.durationSeconds > 0) CrmCallManager.formatDuration(call.durationSeconds) else call.callType.label,
                            color = typeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (call.notes.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = call.notes,
                    fontSize = 12.sp,
                    color = TextPrimaryDark,
                    lineHeight = 16.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            val followUp = followUpDate
            if (followUpState != FollowUpState.NONE && !followUp.isNullOrBlank()) {
                val badgeColor = when (followUpState) {
                    FollowUpState.OVERDUE -> Color(0xFFDC2626)
                    FollowUpState.DUE -> Color(0xFFD97706)
                    FollowUpState.UPCOMING -> Color(0xFF2563EB)
                    else -> TextSecondaryDark
                }
                Surface(shape = RoundedCornerShape(5.dp), color = badgeColor.copy(alpha = 0.12f)) {
                    Text(
                        text = when (followUpState) {
                            FollowUpState.OVERDUE -> "Overdue • " + followUp
                            FollowUpState.DUE -> "Due today • " + followUp
                            FollowUpState.UPCOMING -> "Follow-up • " + followUp
                            else -> "Invalid follow-up"
                        },
                        color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            // Linked entity / internal / unlinked status on its own dedicated full-width line
            if (isInternalTeam) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Badge,
                        contentDescription = "Internal Team",
                        tint = Color(0xFF0284C7),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = call.linkedOpportunityTitle?.takeIf { it.isNotBlank() }?.let { "Internal Team: $it" }
                            ?: "Internal Team • ${call.callerName.ifBlank { "Colleague" }}",
                        fontSize = 11.5.sp,
                        color = Color(0xFF0284C7),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else if (call.isLinked) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Linked",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "${call.linkedOpportunityType}: ${call.linkedOpportunityTitle.orEmpty()}",
                        fontSize = 11.5.sp,
                        color = Color(0xFF047857),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isIgnoredTab) Color(0xFFF1F5F9) else Color(0xFFFFFBEB),
                        border = BorderStroke(0.6.dp, if (isIgnoredTab) Color(0xFFCBD5E1) else Color(0xFFFDE68A))
                    ) {
                        Text(
                            text = if (isIgnoredTab) "Ignored Call" else "Unlinked Enquiry",
                            color = if (isIgnoredTab) Color(0xFF475569) else Color(0xFFB45309),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Direct Inline Matched Call Recording Strip (single-tap Play/Pause directly on the call card)
            if (matchedRecording != null) {
                Spacer(Modifier.height(8.dp))
                val recDurationSec = (matchedRecording.durationMs / 1000L).toInt()
                val playLabel = when {
                    isPlayingInline -> "Pause"
                    recDurationSec > 0 -> "Play • ${CrmCallManager.formatDuration(recDurationSec)}"
                    else -> "Play"
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isPlayingInline) PrimaryActionOrange.copy(alpha = 0.11f) else Color(0xFFF1F5F9),
                    border = BorderStroke(
                        width = 0.8.dp,
                        color = if (isPlayingInline) PrimaryActionOrange.copy(alpha = 0.55f) else BrandCharcoalBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("call_card_inline_rec_strip_${call.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onToggleInlinePlay?.invoke(matchedRecording) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlayingInline) PrimaryActionOrange else Color(0xFF1E3A5F),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .defaultMinSize(minWidth = 84.dp, minHeight = 48.dp)
                                .testTag("call_card_inline_play_${call.id}")
                        ) {
                            Icon(
                                imageVector = if (isPlayingInline) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlayingInline) "Pause Call Recording" else "Play Call Recording",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = playLabel,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = matchedRecording.fileName,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimaryDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = matchedRecording.matchReason.ifBlank { "Strict timestamp + caller match" },
                                fontSize = 10.sp,
                                color = if (isPlayingInline) PrimaryActionOrange else TextSecondaryDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = {
                                if (isPlayingInline) onToggleInlinePlay?.invoke(matchedRecording)
                                showRecordingPanel = !showRecordingPanel
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("call_card_inline_rec_more_${call.id}")
                        ) {
                            Icon(
                                imageVector = if (showRecordingPanel) Icons.Default.ExpandLess else Icons.Default.AutoAwesome,
                                contentDescription = "Summarize or Change Recording",
                                tint = if (showRecordingPanel) PrimaryActionOrange else Color(0xFF1E3A5F),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else if (isAmbiguousRecording) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = { showRecordingPanel = true },
                    shape = RoundedCornerShape(9.dp),
                    color = Color(0xFFFFFBEB),
                    border = BorderStroke(0.8.dp, Color(0xFFF59E0B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .testTag("call_card_ambiguous_rec_${call.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Multiple Possible Recordings",
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "${recordingOutcome!!.ambiguousCandidates.size} possible recordings — tap to choose exact file",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF92400E),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (!inlineAudioError.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFEF2F2),
                    border = BorderStroke(0.8.dp, Color(0xFFFCA5A5)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("call_card_inline_audio_error_${call.id}")
                ) {
                    Text(
                        text = inlineAudioError,
                        color = Color(0xFFB91C1C),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Clean bottom action row: Call + WhatsApp on left, single-line pill action on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${call.phoneNumber}"))
                            runCatching { context.startActivity(dialIntent) }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("call_record_call_btn_${call.id}")
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Call Caller", tint = BrandGold, modifier = Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = {
                            val clean = ContactUtils.normalizePhoneNumber(call.phoneNumber)
                            val waUrl = "https://wa.me/91$clean"
                            val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                            runCatching { context.startActivity(waIntent) }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("call_record_whatsapp_btn_${call.id}")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                            contentDescription = "Message via WhatsApp",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Batch 20: 1-tap WA Callback on Missed Call Cards
                    if (call.callType == CallType.MISSED && !isIgnoredTab) {
                        Surface(
                            onClick = {
                                val updated = CrmCallManager.triggerMissedCallWaCallback(
                                    context = context,
                                    call = call,
                                    assignedAgent = loggedInEmployeeName,
                                    openBrowserOrApp = true
                                )
                                onCallUpdated(updated)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E7E45),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("call_card_wa_callback_${call.id}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                                    contentDescription = null,
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "WA Callback",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Batch 20: On-Demand Call Recording Play, Picker & Summarize Toggle
                    IconButton(
                        onClick = {
                            if (isPlayingInline && matchedRecording != null) {
                                onToggleInlinePlay?.invoke(matchedRecording)
                            }
                            showRecordingPanel = !showRecordingPanel
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("call_card_recording_toggle_${call.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Play, Pick, or Summarize Call Recording",
                            tint = if (showRecordingPanel || matchedRecording != null) PrimaryActionOrange else TextSecondaryDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (isIgnoredTab && onRestoreIgnored != null) {
                    OutlinedButton(
                        onClick = onRestoreIgnored,
                        border = BorderStroke(1.dp, Color(0xFF059669)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 104.dp, minHeight = 48.dp)
                            .testTag("call_card_restore_${call.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = "Restore Ignored Call",
                            tint = Color(0xFF059669),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Restore",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF059669),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else if (!call.isLinked) {
                    Button(
                        onClick = onCreateOpportunity,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 108.dp, minHeight = 48.dp)
                            .testTag("call_record_create_opp_${call.id}")
                    ) {
                        Text(
                            text = if (isSkipped) "Review" else "Smart Log",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onCreateOpportunity,
                        border = BorderStroke(1.dp, BorderGrey),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 104.dp, minHeight = 48.dp)
                            .testTag("call_record_create_opp_${call.id}")
                    ) {
                        Text(
                            text = if (call.notes.isNotBlank()) "Edit Note" else "Log Note",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Expanded Call Recording Panel (reuses parent's single-pass batch outcome; supports Summarize, Pick, and Clear)
            if (showRecordingPanel) {
                Spacer(Modifier.height(8.dp))
                key(call.id) {
                    val boundCallId = call.id
                    CallRecordingOnDemandSection(
                        call = call,
                        currentNotes = call.notes,
                        onSummaryGenerated = { summary ->
                            if (call.id == boundCallId) {
                                val mergedNotes = if (call.notes.isBlank()) summary else "${call.notes}\n$summary"
                                val updated = call.copy(notes = mergedNotes)
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        CrmCallManager.addOrUpdateCall(context, updated)
                                    }
                                    onCallUpdated(updated)
                                }
                            }
                        },
                        testTagPrefix = "call_card_recording_${call.id}",
                        allCallsForStrictDedup = allCallsForStrictDedup,
                        precomputedOutcome = recordingOutcome,
                        onRecordingOutcomeChanged = onRecordingOutcomeChanged
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickCallIntakeDialog(
    onDismiss: () -> Unit,
    onSaveCall: (CrmCallRecord) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var callType by remember { mutableStateOf(CallType.INCOMING) }
    var notes by remember { mutableStateOf("") }
    var durationMinutes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Call") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CallType.values().forEach { t ->
                        FilterChip(
                            selected = callType == t,
                            onClick = { callType = t },
                            label = { Text(t.label) }
                        )
                    }
                }

                ReusableContactPickerField(
                    name = name,
                    onNameChange = { name = it },
                    phone = phone,
                    onPhoneChange = { phone = it },
                    labelPrefix = "Caller"
                )

                OutlinedTextField(
                    value = durationMinutes,
                    onValueChange = { durationMinutes = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Duration (minutes, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Call Discussion / Enquiry Details *") },
                    placeholder = { Text("e.g. Inquired about 2,000 sq.ft office in Kharadi...") },
                    modifier = Modifier.fillMaxWidth().height(90.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = phone.isNotBlank() && notes.isNotBlank(),
                onClick = {
                    val durSec = (durationMinutes.toIntOrNull() ?: 0) * 60
                    val record = CrmCallRecord(
                        callerName = name.ifBlank { "Caller ${ContactUtils.formatDisplayPhone(phone)}" },
                        phoneNumber = ContactUtils.normalizePhoneNumber(phone),
                        callType = callType,
                        durationSeconds = durSec,
                        notes = notes.trim(),
                        isLinked = false
                    )
                    onSaveCall(record)
                }
            ) {
                Text("Save Call")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewOpportunityDialog(
    call: CrmCallRecord,
    allProperties: List<PropertyItem> = emptyList(),
    onDismiss: () -> Unit,
    onOpportunityCreated: (oppType: String, oppTitle: String, oppId: String) -> Unit,
    onAddPropertyToInventory: ((PropertyItem) -> Boolean)? = null
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf(OpportunityCategory.CLIENT) }

    // Client fields
    var clientName by remember(call) { mutableStateOf(call.callerName) }
    var clientPhone by remember(call) { mutableStateOf(call.phoneNumber) }
    var segment by remember { mutableStateOf("Commercial") }
    var clientRequirement by remember(call) { mutableStateOf(call.notes) }
    var clientLocation by remember { mutableStateOf("Kharadi, Viman Nagar") }
    var clientBudget by remember { mutableStateOf("") }
    var clientStatus by remember { mutableStateOf("Hot") }
    val defaultFollowUp = remember {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }
    var clientFollowUp by remember { mutableStateOf(defaultFollowUp) }

    // Owner fields
    var ownerName by remember(call) { mutableStateOf(call.callerName) }
    var ownerContact by remember(call) { mutableStateOf(call.phoneNumber) }
    var projectName by remember { mutableStateOf("") }
    var propertyType by remember { mutableStateOf("") }
    var propertyLocation by remember { mutableStateOf("Kharadi") }
    var unitNumber by remember { mutableStateOf("") }
    var propertyTab by remember { mutableStateOf(InventoryTab.OFF_LEASE) }
    var carpetArea by remember { mutableStateOf("1200") }
    var priceRent by remember { mutableStateOf("") }
    var ownerRemarks by remember(call) { mutableStateOf(call.notes) }

    // Broker fields
    var brokerName by remember(call) { mutableStateOf(call.callerName) }
    var brokerPhone by remember(call) { mutableStateOf(call.phoneNumber) }
    var brokerFirm by remember { mutableStateOf("") }
    var brokerActionType by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Convert Call to Opportunity", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Caller: ${ContactUtils.formatDisplayPhone(call.phoneNumber)}", fontSize = 11.sp, color = TextSecondaryDark)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
            ) {
                TabRow(
                    selectedTabIndex = selectedCategory.ordinal,
                    containerColor = BrandCharcoalSurface,
                    contentColor = BrandGold
                ) {
                    Tab(
                        selected = selectedCategory == OpportunityCategory.CLIENT,
                        onClick = { selectedCategory = OpportunityCategory.CLIENT },
                        text = { Text("Client", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedCategory == OpportunityCategory.OWNER_DEVELOPER,
                        onClick = { selectedCategory = OpportunityCategory.OWNER_DEVELOPER },
                        text = { Text("Owner", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedCategory == OpportunityCategory.BROKER_PARTNER,
                        onClick = { selectedCategory = OpportunityCategory.BROKER_PARTNER },
                        text = { Text("Broker", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (selectedCategory) {
                        OpportunityCategory.CLIENT -> {
                            item {
                                ReusableContactPickerField(
                                    name = clientName,
                                    onNameChange = { clientName = it },
                                    phone = clientPhone,
                                    onPhoneChange = { clientPhone = it },
                                    labelPrefix = "Client",
                                    allProperties = allProperties
                                )
                            }
                            item {
                                Text("Division", style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("Commercial", "Residential").forEach { seg ->
                                        FilterChip(
                                            selected = segment == seg,
                                            onClick = { segment = seg },
                                            label = { Text(seg) }
                                        )
                                    }
                                }
                            }
                            item {
                                OutlinedTextField(
                                    value = clientRequirement,
                                    onValueChange = { clientRequirement = it },
                                    label = { Text("Requirement Description *") },
                                    placeholder = { Text("e.g. 2,000 sq.ft office for lease") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = clientLocation,
                                    onValueChange = { clientLocation = it },
                                    label = { Text("Preferred Locations") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = clientBudget,
                                    onValueChange = { clientBudget = it },
                                    label = { Text("Budget (e.g. ₹1.2 Lakh/mo or ₹2 Cr)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            item {
                                Text("Status", style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("New", "Warm", "Hot").forEach { st ->
                                        FilterChip(
                                            selected = clientStatus == st,
                                            onClick = { clientStatus = st },
                                            label = { Text(st) }
                                        )
                                    }
                                }
                            }
                            item {
                                OutlinedTextField(
                                    value = clientFollowUp,
                                    onValueChange = { clientFollowUp = it },
                                    label = { Text("Next Follow-up (YYYY-MM-DD)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        OpportunityCategory.OWNER_DEVELOPER -> {
                            item {
                                ReusableContactPickerField(
                                    name = ownerName,
                                    onNameChange = { ownerName = it },
                                    phone = ownerContact,
                                    onPhoneChange = { ownerContact = it },
                                    labelPrefix = "Owner",
                                    allProperties = allProperties
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = projectName,
                                    onValueChange = { projectName = it },
                                    label = { Text("Project / Building Name *") },
                                    placeholder = { Text("e.g. Gera Imperium, World Trade Center") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = propertyLocation,
                                    onValueChange = { propertyLocation = it },
                                    label = { Text("Location (Micro-market) *") },
                                    placeholder = { Text("e.g. Kharadi, Viman Nagar") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = unitNumber,
                                    onValueChange = { unitNumber = it },
                                    label = { Text("Unit / Office Number") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            item {
                                Text("Inventory Category", style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(
                                        InventoryTab.OFF_LEASE,
                                        InventoryTab.SHOP_LEASE,
                                        InventoryTab.OFF_OUT,
                                        InventoryTab.RESI_RESALE
                                    ).forEach { t ->
                                        FilterChip(
                                            selected = propertyTab == t,
                                            onClick = { propertyTab = t },
                                            label = { Text(t.code, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = carpetArea,
                                        onValueChange = { carpetArea = it },
                                        label = { Text("Carpet Sq.Ft") },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = priceRent,
                                        onValueChange = { priceRent = it },
                                        label = { Text("Rent / Price ₹") },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            item {
                                OutlinedTextField(
                                    value = ownerRemarks,
                                    onValueChange = { ownerRemarks = it },
                                    label = { Text("Owner Remarks / Notes") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        OpportunityCategory.BROKER_PARTNER -> {
                            item {
                                ReusableContactPickerField(
                                    name = brokerName,
                                    onNameChange = { brokerName = it },
                                    phone = brokerPhone,
                                    onPhoneChange = { brokerPhone = it },
                                    labelPrefix = "Broker",
                                    allProperties = allProperties
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = brokerFirm,
                                    onValueChange = { brokerFirm = it },
                                    label = { Text("Firm / Real Estate Agency") },
                                    placeholder = { Text("e.g. Pune Prime Properties") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            item {
                                Text("Broker Enquiry Type", style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(
                                        selected = brokerActionType == 0,
                                        onClick = { brokerActionType = 0 },
                                        label = { Text("Listing Property") }
                                    )
                                    FilterChip(
                                        selected = brokerActionType == 1,
                                        onClick = { brokerActionType = 1 },
                                        label = { Text("Client Enquiry") }
                                    )
                                }
                            }
                            if (brokerActionType == 0) {
                                item {
                                    OutlinedTextField(
                                        value = projectName,
                                        onValueChange = { projectName = it },
                                        label = { Text("Property / Project Name *") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                item {
                                    OutlinedTextField(
                                        value = propertyLocation,
                                        onValueChange = { propertyLocation = it },
                                        label = { Text("Location *") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                item {
                                    OutlinedTextField(
                                        value = priceRent,
                                        onValueChange = { priceRent = it },
                                        label = { Text("Rent / Price ₹") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else {
                                item {
                                    OutlinedTextField(
                                        value = clientRequirement,
                                        onValueChange = { clientRequirement = it },
                                        label = { Text("Broker's Client Requirement *") },
                                        placeholder = { Text("e.g. Client needs 3,000 sq.ft office in Viman Nagar") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                item {
                                    OutlinedTextField(
                                        value = clientBudget,
                                        onValueChange = { clientBudget = it },
                                        label = { Text("Client Budget") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val result = when (selectedCategory) {
                    OpportunityCategory.CLIENT -> CallConversionService.convertLead(
                        context, call, clientName, clientPhone, segment, clientRequirement,
                        clientLocation, clientBudget, clientStatus, clientFollowUp
                    )
                    OpportunityCategory.OWNER_DEVELOPER -> {
                        val saver = onAddPropertyToInventory
                        if (saver == null) CallConversionResult(false, "Inventory saving is unavailable")
                        else CallConversionService.convertProperty(
                            context, call,
                            CallPropertyDraft(ownerName, ownerContact, projectName, propertyLocation, unitNumber,
                                propertyTab, carpetArea.toDoubleOrNull() ?: 0.0, priceRent.toDoubleOrNull() ?: 0.0,
                                ownerRemarks, propertyType = propertyType),
                            saver, { tab -> allProperties.filter { it.tab == tab }.map { it.propertyCode }.firstOrNull() ?: (tab.code.take(2).uppercase() + "-NEW") }, { id -> allProperties.find { it.id == id } }
                        )
                    }
                    OpportunityCategory.BROKER_PARTNER -> {
                        if (brokerActionType == 1) CallConversionService.convertLead(
                            context, call, "Client via " + brokerName, brokerPhone, "Commercial",
                            clientRequirement, clientLocation, clientBudget, clientStatus, clientFollowUp
                        ) else {
                            val saver = onAddPropertyToInventory
                            if (saver == null) CallConversionResult(false, "Inventory saving is unavailable")
                            else CallConversionService.convertProperty(
                                context, call,
                                CallPropertyDraft(brokerName, brokerPhone, projectName, propertyLocation, unitNumber,
                                    InventoryTab.OFF_LEASE, carpetArea.toDoubleOrNull() ?: 0.0,
                                    priceRent.toDoubleOrNull() ?: 0.0, ownerRemarks,
                                    brokerName = brokerName, brokerPhone = brokerPhone, brokerFirm = brokerFirm,
                                    propertyType = propertyType),
                                saver, { tab -> allProperties.filter { it.tab == tab }.map { it.propertyCode }.firstOrNull() ?: (tab.code.take(2).uppercase() + "-NEW") }, { id -> allProperties.find { it.id == id } }
                            )
                        }
                    }
                }
                if (result.success) onOpportunityCreated(
                    if (selectedCategory == OpportunityCategory.CLIENT || (selectedCategory == OpportunityCategory.BROKER_PARTNER && brokerActionType == 1)) "Lead" else "Property",
                    result.title, result.id ?: ""
                ) else Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }) { Text("Save and Link Call") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ActivityHistoryDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var activities by remember { mutableStateOf<List<CrmActivityLog>>(emptyList()) }
    LaunchedEffect(Unit) {
        activities = withContext(Dispatchers.IO) { CrmCallManager.readActivityLogs(context) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Activity & Opportunity History (${activities.size})", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                if (activities.isEmpty()) {
                    Text("No activities recorded yet. When calls are logged or opportunities created, timestamped audit logs appear here.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(activities, key = { it.id }) { log ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface),
                                border = androidx.compose.foundation.BorderStroke(0.6.dp, BrandCharcoalBorder)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(log.type, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = BrandGold)
                                        Text(CrmCallManager.formatTimestamp(log.timestamp), fontSize = 10.sp, color = TextSecondaryDark)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(log.summary, fontSize = 12.5.sp, color = TextPrimaryDark, fontWeight = FontWeight.Medium)
                                    if (log.details.isNotBlank()) {
                                        Text(log.details, fontSize = 11.sp, color = TextSecondaryDark)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}



