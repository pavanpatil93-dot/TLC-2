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
import com.example.ui.components.CompactDropdownField
import com.example.ui.components.SearchableDropdownField
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

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
    externalRefreshTick: Int = 0
) {
    val context = LocalContext.current
    var calls by remember { mutableStateOf(CrmCallManager.readCalls(context)) }
    LaunchedEffect(externalRefreshTick) {
        if (externalRefreshTick > 0) {
            calls = CrmCallManager.readCalls(context)
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
            val deviceCalls = CrmCallManager.safeReadDeviceCallLogs(context)
            if (deviceCalls.isNotEmpty()) {
                val existingIds = calls.map { it.id }.toSet()
                val newOnes = deviceCalls.filterNot { it.id in existingIds }
                calls = newOnes + calls
                CrmCallManager.saveCalls(context, calls)
                pendingReview = newOnes.firstOrNull()
                Toast.makeText(context, "Imported ${newOnes.size} calls. Review each business call before classifying.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, CrmCallManager.lastDeviceReadError ?: "No recent calls found in call log", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(
                context,
                "Device call log access denied. Use manual Quick Call intake anytime.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val unlinkedCount = remember(calls) { calls.count { !it.isLinked } }
    val today = remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.timeInMillis
    }
    val todayCallsCount = remember(calls) { calls.count { it.timestamp >= today } }
    val convertedCount = remember(calls) { calls.count { it.isLinked } }

    val filteredCalls = remember(calls, selectedFilter, searchQuery) {
        calls.filter { call ->
            val matchesFilter = when (selectedFilter) {
                "Unlinked" -> !call.isLinked
                "Incoming" -> call.callType == CallType.INCOMING
                "Outgoing" -> call.callType == CallType.OUTGOING
                "Missed" -> call.callType == CallType.MISSED
                "Converted" -> call.isLinked
                else -> true
            }
            val matchesSearch = searchQuery.isBlank() ||
                call.callerName.contains(searchQuery, ignoreCase = true) ||
                call.phoneNumber.contains(searchQuery, ignoreCase = true) ||
                call.notes.contains(searchQuery, ignoreCase = true)

            matchesFilter && matchesSearch
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
                CompactCallStatPill(label = "All", value = "${calls.size}")
                CompactCallStatPill(
                    label = "Unlinked",
                    value = "$unlinkedCount",
                    highlight = unlinkedCount > 0
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
                        val deviceCalls = CrmCallManager.safeReadDeviceCallLogs(context)
                        val existingIds = calls.map { it.id }.toSet()
                        val newOnes = deviceCalls.filterNot { it.id in existingIds }
                        calls = newOnes + calls
                        CrmCallManager.saveCalls(context, calls)
                        pendingReview = newOnes.firstOrNull()
                        Toast.makeText(context, CrmCallManager.lastDeviceReadError ?: "Imported ${newOnes.size} calls. Review business calls before classifying.", Toast.LENGTH_LONG).show()
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

            if (onNewCallReview != null) {
                OutlinedButton(
                    onClick = onNewCallReview,
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
                    onClick = onOpenSavedReviews,
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
                        calls = demo.filterNot { it.id in existingIds } + calls
                        CrmCallManager.saveCalls(context, calls)
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("+ Demo", fontSize = 11.sp, color = BrandGold)
                }
            } else {
                TextButton(
                    onClick = {
                        calls = calls.filterNot { it.id.startsWith("demo_call_") }
                        CrmCallManager.saveCalls(context, calls)
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

        // Filter Chips
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val filterOptions = listOf(
                "All" to "${calls.size}",
                "Unlinked" to "$unlinkedCount",
                "Missed" to "${calls.count { it.callType == CallType.MISSED }}",
                "Incoming" to "${calls.count { it.callType == CallType.INCOMING }}",
                "Outgoing" to "${calls.count { it.callType == CallType.OUTGOING }}",
                "Converted" to "$convertedCount"
            )
            items(filterOptions) { (label, count) ->
                FilterChip(
                    selected = selectedFilter == label,
                    onClick = { selectedFilter = label },
                    label = { Text("$label ($count)", fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Call Records List (Expanded primary content area)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("calls_lazy_list")
        ) {
            items(filteredCalls, key = { it.id }) { call ->
                CallRecordCard(
                    call = call,
                    onCreateOpportunity = {
                        if (onOpenPostCallSheet != null) {
                            onOpenPostCallSheet(call)
                        } else {
                            callForOpportunity = call
                        }
                    }
                )
            }

            if (filteredCalls.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.PhoneMissed,
                                contentDescription = null,
                                tint = TextSecondaryDark,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "No calls found for '$selectedFilter'",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = "Tap 'Log Call' or '+ Demo' to add enquiry records.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark
                            )
                        }
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
        val matchedLead = remember(call.id) {
            readCrmLeads(context).firstOrNull { lead ->
                (phoneKey.length >= 7 && (
                    CrmCallManager.last10Digits(lead.phone) == phoneKey ||
                        CrmCallManager.last10Digits(lead.alternatePhone) == phoneKey
                    )) || (!call.linkedOpportunityId.isNullOrBlank() && lead.id == call.linkedOpportunityId)
            }
        }
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
        var linkToCrmLead by remember(call.id) { mutableStateOf(matchedLead != null) }
        var leadNameInput by remember(call.id) {
            mutableStateOf(
                matchedLead?.displayClientTitle()
                    ?: call.callerName.takeIf {
                        it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
                    }.orEmpty()
            )
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
                                    text = if (matchedLead != null) {
                                        "Link to existing CRM Lead: ${matchedLead.displayClientTitle()} (${matchedLead.id})"
                                    } else {
                                        "+ Create / Link CRM Lead"
                                    },
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
                                IgnoredNumbersManager.markPersonalIgnored(context, call.phoneNumber)
                                val noteLine = buildString {
                                    append("Personal (Ignored)")
                                    if (discussionNotes.isNotBlank()) append(" — ").append(discussionNotes.trim())
                                }
                                CrmCallManager.addOrUpdateCall(context, call.copy(notes = noteLine))
                                calls = CrmCallManager.readCalls(context)
                                pendingReview = null
                                Toast.makeText(context, "Marked Personal & muted from call pop-ups", Toast.LENGTH_SHORT).show()
                            } else {
                                val todayIso = isoOffsetDays(0)
                                val resolvedLead: CrmLead? = if (linkToCrmLead) {
                                    val baseLead = matchedLead?.copy(
                                        lastContactDate = todayIso,
                                        lastContactMode = "Call",
                                        lastContactOutcome = actionOutcome,
                                        nextFollowUp = followUpDate.ifBlank { matchedLead.nextFollowUp },
                                        nextFollowUpTime = followUpTime.ifBlank { matchedLead.nextFollowUpTime },
                                        followUpTime = followUpTime.ifBlank { matchedLead.followUpTime },
                                        notes = if (discussionNotes.isNotBlank()) {
                                            "${discussionNotes.trim()}\n${matchedLead.notes}".trim()
                                        } else {
                                            matchedLead.notes
                                        }
                                    ) ?: CrmLead(
                                        id = "",
                                        name = leadNameInput.trim().ifBlank { call.callerName.ifBlank { "Client ${call.phoneNumber}" } },
                                        phone = ContactUtils.normalizePhoneNumber(call.phoneNumber),
                                        segment = "Commercial",
                                        requirement = discussionNotes.trim().ifBlank { "$callerRole Call Follow-up" },
                                        location = "Pune",
                                        budget = "Negotiable",
                                        status = if (followUpDate.isNotBlank()) "Follow-up Scheduled" else "New",
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
                                calls = CrmCallManager.readCalls(context)
                                pendingReview = null
                                val toastMsg = if (followUpDate.isNotBlank()) {
                                    "Call saved & follow-up scheduled"
                                } else {
                                    "Call reviewed & saved"
                                }
                                Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
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
                calls = listOf(newCall) + calls
                CrmCallManager.saveCalls(context, calls)
                CrmCallManager.logActivity(
                    context = context,
                    entityId = newCall.id,
                    type = "Call Logged",
                    summary = "${newCall.callType.label} Call logged with ${newCall.callerName}",
                    details = "Phone: ${newCall.phoneNumber} • ${newCall.notes}"
                )
                showQuickIntakeDialog = false
            }
        )
    }

    callForOpportunity?.let { call ->
        NewOpportunityDialog(
            call = call,
            allProperties = inventory,
            onDismiss = { callForOpportunity = null },
            onOpportunityCreated = { _, oppTitle, _ ->
                calls = CrmCallManager.readCalls(context)
                callForOpportunity = null
                Toast.makeText(context, "Opportunity saved and call linked: $oppTitle", Toast.LENGTH_SHORT).show()
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
    onCreateOpportunity: () -> Unit
) {
    val context = LocalContext.current
    val (typeIcon, typeColor) = when (call.callType) {
        CallType.INCOMING -> Icons.Default.CallReceived to Color(0xFF10B981)
        CallType.OUTGOING -> Icons.Default.CallMade to Color(0xFF3B82F6)
        CallType.MISSED -> Icons.Default.CallMissed to Color(0xFFEF4444)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, BrandCharcoalBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "${ContactUtils.formatDisplayPhone(call.phoneNumber)} • ${CrmCallManager.formatTimestamp(call.timestamp)}",
                            fontSize = 11.sp,
                            color = TextSecondaryDark
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

            val followUp = CallFollowUpStore.get(context, call.id)
            val followUpState = CallFollowUpStore.state(followUp)
            if (followUpState != FollowUpState.NONE) {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (call.isLinked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Linked",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${call.linkedOpportunityType}: ${call.linkedOpportunityTitle.orEmpty()}",
                            fontSize = 11.sp,
                            color = Color(0xFF047857),
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFFFFBEB),
                        border = androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFFFDE68A))
                    ) {
                        Text(
                            text = "Unlinked Enquiry",
                            color = Color(0xFFB45309),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
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

                    if (!call.isLinked) {
                        Button(
                            onClick = onCreateOpportunity,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("call_record_create_opp_${call.id}")
                        ) {
                            Text("Smart Log", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onCreateOpportunity,
                            border = BorderStroke(1.dp, BorderGrey),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("call_record_create_opp_${call.id}")
                        ) {
                            Text("Log Note", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                    }
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
                                Text("Portfolio Segment", style = MaterialTheme.typography.labelMedium)
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
    val activities = remember { CrmCallManager.readActivityLogs(context) }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostCallSmartSheet(
    call: CrmCallRecord,
    leads: List<CrmLead>,
    inventory: List<PropertyItem>,
    directoryProjects: List<DirectoryProject> = emptyList(),
    underConstProjects: List<UnderConstProject> = emptyList(),
    employeeName: String,
    employeeMobile: String,
    onDismissLater: (CrmCallRecord) -> Unit,
    onMarkPersonalIgnore: (CrmCallRecord) -> Unit,
    onSaveLeadAndSync: (lead: CrmLead, activityType: String, activityNotes: String) -> Unit,
    onShareMatchingProps: (CrmLead) -> Unit,
    onScheduleSiteVisit: (CrmLead) -> Unit,
    onUpdateOwnerPropertyStatus: (property: PropertyItem, newStatus: AvailabilityStatus, callNote: String, updatedPriceOrRent: Double?) -> Boolean,
    onLinkOwnerExistingProperty: (property: PropertyItem, call: CrmCallRecord, ownerNameInput: String) -> Boolean,
    onAddNewProperty: (PropertyItem) -> Boolean,
    onOpenFullAddProperty: (ownerPhone: String) -> Unit,
    onCallResolved: (CrmCallRecord) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val match = remember(call, leads, inventory, directoryProjects, underConstProjects) {
        CrmCallManager.matchCallToDatabase(
            context = context,
            call = call,
            leads = leads,
            inventory = inventory,
            directoryProjects = directoryProjects,
            underConstProjects = underConstProjects
        )
    }

    val safeEmployeeName = employeeName.trim().ifBlank { "TLC-1 Team" }
    val safeEmployeeMobile = employeeMobile.trim().ifBlank { "9766880099" }
    val cleanDisplayPhone = remember(call.phoneNumber) {
        ContactUtils.formatDisplayPhone(call.phoneNumber).ifBlank { call.phoneNumber }
    }

    var notes by remember(call.id) { mutableStateOf("") }

    // Voice-to-Note Mic launcher
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrBlank()) {
                notes = if (notes.isBlank()) spoken else "$notes\n$spoken"
            }
        }
    }
    val launchVoiceToNote: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak call summary...")
        }
        runCatching { voiceLauncher.launch(intent) }.onFailure {
            Toast.makeText(context, "Voice dictation unavailable on this device. Please type note.", Toast.LENGTH_SHORT).show()
        }
    }

    // 1-Tap follow-up helper states
    val todayDateStr = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    fun computeFollowUpPreset(offsetDays: Int, timeStr: String): Pair<String, String> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time) to timeStr
    }
    fun computeTodayPlus3Hours(): Pair<String, String> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.HOUR_OF_DAY, 3)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time) to
            SimpleDateFormat("hh:mm a", Locale.US).format(cal.time)
    }
    fun computeWeekendPreset(): Pair<String, String> {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val daysUntilSaturday = ((Calendar.SATURDAY - dow + 7) % 7).let { if (it == 0) 7 else it }
        cal.add(Calendar.DAY_OF_YEAR, daysUntilSaturday)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time) to "11:00 AM"
    }

    val openDatePicker: ((String) -> Unit) -> Unit = { onPicked ->
        val cal = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val picked = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
                onPicked(picked)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    ModalBottomSheet(
        onDismissRequest = { onDismissLater(call) },
        sheetState = sheetState,
        containerColor = BackgroundSoft,
        contentColor = TextPrimary,
        modifier = Modifier.testTag("post_call_smart_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ================= HEADER =================
            val (badgeIcon, badgeColor) = when (call.callType) {
                CallType.INCOMING -> Icons.Default.CallReceived to SuccessGreen
                CallType.OUTGOING -> Icons.Default.CallMade to Color(0xFF1565C0)
                CallType.MISSED -> Icons.Default.CallMissed to ErrorRed
            }

            Surface(
                color = SurfaceWhite,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderGrey),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(badgeIcon, contentDescription = call.callType.label, tint = badgeColor, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = match.displayTitle,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "$cleanDisplayPhone • ${call.callType.label} • ${CrmCallManager.formatDuration(call.durationSeconds)}",
                                    fontSize = 11.5.sp,
                                    color = SecondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Surface(
                            color = SelectedSurfaceContainer,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.8.dp, PrimaryActionOrange)
                        ) {
                            Text(
                                text = match.role.label,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryActionOrange,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = match.subtitle,
                        fontSize = 12.sp,
                        color = SecondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onDismissLater(call) },
                            border = BorderStroke(1.dp, BorderGrey),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("post_call_later_btn")
                        ) {
                            Text("Later", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                        OutlinedButton(
                            onClick = { onMarkPersonalIgnore(call) },
                            border = BorderStroke(1.dp, BorderGrey),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("post_call_personal_ignore_btn")
                        ) {
                            Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Personal — Don't Ask Again",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SecondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ================= MISSED CALL 1-TAP WHATSAPP RESCUE =================
            if (call.callType == CallType.MISSED || call.durationSeconds == 0) {
                Button(
                    onClick = {
                        val rescueMsg = "Hi, sorry I missed your call. This is $safeEmployeeName from The Leasing Company ($safeEmployeeMobile). How can I assist you?"
                        CrmCallManager.openWhatsAppChat(context, call.phoneNumber, rescueMsg)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E7E45),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("post_call_missed_wa_rescue_btn")
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Send \"Sorry I Missed Your Call\" on WhatsApp",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ================= VOICE-TO-NOTE CALL SUMMARY INPUT =================
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Call Discussion / Quick Note", fontSize = 12.sp) },
                placeholder = { Text("Tap mic or type summary...", fontSize = 12.sp) },
                trailingIcon = {
                    IconButton(
                        onClick = launchVoiceToNote,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("post_call_voice_note_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice to Note",
                            tint = PrimaryActionOrange
                        )
                    }
                },
                minLines = 2,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryActionOrange,
                    unfocusedBorderColor = BorderGrey,
                    focusedContainerColor = SurfaceWhite,
                    unfocusedContainerColor = SurfaceWhite
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("post_call_notes_input")
            )

            // ================= ROLE-SPECIFIC BODY =================
            when (match.role) {
                PostCallRole.CLIENT_LEAD -> {
                    val lead = match.matchedLead!!
                    val outcomeOptions = remember {
                        listOf(
                            "Spoken — Interested",
                            "Send Options on WhatsApp",
                            "Site Visit Confirmed",
                            "Call Back Later",
                            "RNR / Busy"
                        )
                    }
                    var selectedOutcome by remember(call.id) { mutableStateOf(outcomeOptions.first()) }
                    var nextDate by remember(call.id) { mutableStateOf(computeFollowUpPreset(1, "11:00 AM").first) }
                    var nextTime by remember(call.id) { mutableStateOf("11:00 AM") }
                    var selectedPreset by remember(call.id) { mutableStateOf("Tomorrow 11 AM") }

                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Client Requirement • ${lead.segment.ifBlank { "Commercial" }}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryActionOrange
                            )
                            Text(
                                text = "${lead.displayClientTitle()} • Stage: ${lead.status.ifBlank { "New" }}\n${lead.requirement.ifBlank { "Requirement on file" }}",
                                fontSize = 12.5.sp,
                                color = TextPrimary
                            )

                            CompactDropdownField(
                                label = "Call Outcome",
                                selectedValue = selectedOutcome,
                                options = outcomeOptions,
                                onValueSelected = { selectedOutcome = it },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text("Next Follow-up", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("Today +3h", "Tomorrow 11 AM", "This Weekend", "Pick Date").forEach { preset ->
                                    val isSel = selectedPreset == preset
                                    FilterChip(
                                        selected = isSel,
                                        onClick = {
                                            selectedPreset = preset
                                            when (preset) {
                                                "Today +3h" -> {
                                                    val (d, t) = computeTodayPlus3Hours()
                                                    nextDate = d; nextTime = t
                                                }
                                                "Tomorrow 11 AM" -> {
                                                    val (d, t) = computeFollowUpPreset(1, "11:00 AM")
                                                    nextDate = d; nextTime = t
                                                }
                                                "This Weekend" -> {
                                                    val (d, t) = computeWeekendPreset()
                                                    nextDate = d; nextTime = t
                                                }
                                                "Pick Date" -> {
                                                    openDatePicker { picked ->
                                                        nextDate = picked
                                                        nextTime = "11:00 AM"
                                                    }
                                                }
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = if (preset == "Pick Date" && isSel) nextDate else preset,
                                                fontSize = 11.sp
                                            )
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onShareMatchingProps(lead) },
                                    border = BorderStroke(1.dp, BorderGrey),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("post_call_share_matches_btn")
                                ) {
                                    Text("Share Options", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { onScheduleSiteVisit(lead) },
                                    border = BorderStroke(1.dp, BorderGrey),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("post_call_schedule_visit_btn")
                                ) {
                                    Text("Schedule Visit", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
                                }
                            }

                            Button(
                                onClick = {
                                    val combinedNotes = buildString {
                                        append("Outcome: ").append(selectedOutcome)
                                        if (notes.isNotBlank()) append(" — ").append(notes.trim())
                                    }
                                    val newStage = when (selectedOutcome) {
                                        "Site Visit Confirmed" -> "Site Visit Scheduled"
                                        else -> lead.status.ifBlank { "Contacted" }
                                    }
                                    val updatedLead = lead.copy(
                                        status = newStage,
                                        nextFollowUp = nextDate,
                                        nextFollowUpTime = nextTime,
                                        followUpTime = nextTime,
                                        followUpCompleted = false,
                                        lastContactDate = todayDateStr,
                                        lastContactMode = "Call",
                                        lastContactOutcome = selectedOutcome,
                                        notes = if (notes.isNotBlank()) "${notes.trim()}\n${lead.notes}".trim() else lead.notes
                                    )
                                    onSaveLeadAndSync(updatedLead, "Call: $selectedOutcome", combinedNotes)
                                    val savedCall = call.copy(
                                        callerName = lead.displayClientTitle(),
                                        notes = combinedNotes,
                                        isLinked = true,
                                        linkedOpportunityType = "Client",
                                        linkedOpportunityTitle = lead.displayClientTitle(),
                                        linkedOpportunityId = lead.id
                                    )
                                    CrmCallManager.addOrUpdateCall(context, savedCall)
                                    CrmCallManager.logActivity(
                                        context = context,
                                        entityId = lead.id,
                                        type = "Client Call • $selectedOutcome",
                                        summary = "${lead.displayClientTitle()} ($cleanDisplayPhone)",
                                        details = "$combinedNotes • Next follow-up: $nextDate $nextTime",
                                        author = safeEmployeeName
                                    )
                                    onCallResolved(savedCall)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("post_call_save_client_btn")
                            ) {
                                Text("Save Client Call Log", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                PostCallRole.PROPERTY_OWNER -> {
                    val matchedProps = match.matchedProperties
                    var selectedPropIdx by remember(call.id) { mutableIntStateOf(0) }
                    val targetProp = matchedProps.getOrNull(selectedPropIdx) ?: matchedProps.first()
                    var showPriceEditor by remember(call.id) { mutableStateOf(false) }
                    var updatedPriceText by remember(call.id, targetProp.id) {
                        val currentVal = targetProp.rent ?: targetProp.priceSale ?: targetProp.priceOrRent
                        mutableStateOf(if (currentVal > 0.0) currentVal.toLong().toString() else "")
                    }

                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Linked Property Owner",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryActionOrange
                            )
                            if (matchedProps.size > 1) {
                                val propLabels = matchedProps.map { "${it.propertyCode} • ${it.projectName} ${it.unitNumber}".trim() }
                                CompactDropdownField(
                                    label = "Select Property",
                                    selectedValue = propLabels.getOrElse(selectedPropIdx) { propLabels.first() },
                                    options = propLabels,
                                    onValueSelected = { picked ->
                                        val idx = propLabels.indexOf(picked)
                                        if (idx >= 0) selectedPropIdx = idx
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    text = "${targetProp.propertyCode} • ${targetProp.projectName} ${targetProp.unitNumber}".trim(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }

                            if (showPriceEditor) {
                                OutlinedTextField(
                                    value = updatedPriceText,
                                    onValueChange = { updatedPriceText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                    label = { Text("Updated Monthly Rent / Sale Price (₹)", fontSize = 11.5.sp) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            fun commitOwnerAction(newStatus: AvailabilityStatus, actionLabel: String) {
                                val priceOverride = if (showPriceEditor) updatedPriceText.toDoubleOrNull() else null
                                val noteText = buildString {
                                    append(actionLabel)
                                    if (notes.isNotBlank()) append(" — ").append(notes.trim())
                                }
                                onUpdateOwnerPropertyStatus(targetProp, newStatus, noteText, priceOverride)
                                val savedCall = call.copy(
                                    callerName = targetProp.ownerName?.takeIf { it.isNotBlank() } ?: match.displayTitle,
                                    notes = noteText,
                                    isLinked = true,
                                    linkedOpportunityType = "Owner",
                                    linkedOpportunityTitle = "${targetProp.propertyCode} • ${targetProp.projectName}",
                                    linkedOpportunityId = targetProp.id
                                )
                                CrmCallManager.addOrUpdateCall(context, savedCall)
                                CrmCallManager.logActivity(
                                    context = context,
                                    entityId = targetProp.id,
                                    type = "Owner Call • $actionLabel",
                                    summary = "${targetProp.propertyCode} • ${targetProp.projectName}",
                                    details = noteText,
                                    author = safeEmployeeName
                                )
                                onCallResolved(savedCall)
                            }

                            Button(
                                onClick = { commitOwnerAction(AvailabilityStatus.AVAILABLE, "Confirmed Available (60-Day Timer Reset)") },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("post_call_owner_available_btn")
                            ) {
                                Text("Confirm Available (Reset 60-Day Timer)", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { commitOwnerAction(AvailabilityStatus.ON_HOLD, "Marked On Hold") },
                                    border = BorderStroke(1.dp, BorderGrey),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("post_call_owner_hold_btn")
                                ) {
                                    Text("On Hold", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = WarningAmber)
                                }
                                val soldOrLeasedStatus = if (targetProp.transactionType.contains("Sale", true)) AvailabilityStatus.SOLD else AvailabilityStatus.LEASED
                                val soldOrLeasedLabel = if (soldOrLeasedStatus == AvailabilityStatus.SOLD) "Sold" else "Leased"
                                OutlinedButton(
                                    onClick = { commitOwnerAction(soldOrLeasedStatus, "Marked $soldOrLeasedLabel") },
                                    border = BorderStroke(1.dp, BorderGrey),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("post_call_owner_closed_btn")
                                ) {
                                    Text("Leased / Sold", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ErrorRed)
                                }
                            }

                            OutlinedButton(
                                onClick = { showPriceEditor = !showPriceEditor },
                                border = BorderStroke(1.dp, BorderGrey),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Text(
                                    text = if (showPriceEditor) "Hide Rent / Price Update" else "Update Rent / Price",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }

                PostCallRole.BROKER -> {
                    val broker = match.matchedBroker
                    var brokerMode by remember(call.id) { mutableStateOf("NOTE") } // NOTE, CLIENT_REQ, PROPERTY_LISTING
                    var reqDivision by remember(call.id) { mutableStateOf("Commercial") }
                    var reqLocation by remember(call.id) { mutableStateOf("Kharadi") }
                    var reqText by remember(call.id) { mutableStateOf("") }
                    var reqBudget by remember(call.id) { mutableStateOf("") }
                    var propProject by remember(call.id) { mutableStateOf("") }
                    var propLocation by remember(call.id) { mutableStateOf("Kharadi") }
                    var propUnit by remember(call.id) { mutableStateOf("") }
                    var propArea by remember(call.id) { mutableStateOf("") }
                    var propPrice by remember(call.id) { mutableStateOf("") }

                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Channel Partner / Broker Co-Broking",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryActionOrange
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = brokerMode == "CLIENT_REQ",
                                    onClick = { brokerMode = if (brokerMode == "CLIENT_REQ") "NOTE" else "CLIENT_REQ" },
                                    label = { Text("+ Broker Client Req", fontSize = 11.sp) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                                FilterChip(
                                    selected = brokerMode == "PROPERTY_LISTING",
                                    onClick = { brokerMode = if (brokerMode == "PROPERTY_LISTING") "NOTE" else "PROPERTY_LISTING" },
                                    label = { Text("+ Broker Listing", fontSize = 11.sp) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }

                            when (brokerMode) {
                                "CLIENT_REQ" -> {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CompactDropdownField(
                                            label = "Division",
                                            selectedValue = reqDivision,
                                            options = listOf("Commercial", "Residential"),
                                            onValueSelected = { reqDivision = it },
                                            modifier = Modifier.weight(1f)
                                        )
                                        SearchableDropdownField(
                                            label = "Location",
                                            selectedValue = reqLocation,
                                            options = TlcStandardOptions.puneLocations,
                                            onValueSelected = { reqLocation = it },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    OutlinedTextField(
                                        value = reqText,
                                        onValueChange = { reqText = it },
                                        label = { Text("Client Requirement via Broker *") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = reqBudget,
                                        onValueChange = { reqBudget = it },
                                        label = { Text("Budget (₹)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                "PROPERTY_LISTING" -> {
                                    OutlinedTextField(
                                        value = propProject,
                                        onValueChange = { propProject = it },
                                        label = { Text("Project / Building Name *") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SearchableDropdownField(
                                            label = "Location",
                                            selectedValue = propLocation,
                                            options = TlcStandardOptions.puneLocations,
                                            onValueSelected = { propLocation = it },
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = propUnit,
                                            onValueChange = { propUnit = it },
                                            label = { Text("Unit No") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = propArea,
                                            onValueChange = { propArea = it.filter { c -> c.isDigit() || c == '.' } },
                                            label = { Text("Carpet Sq.Ft") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = propPrice,
                                            onValueChange = { propPrice = it.filter { c -> c.isDigit() || c == '.' } },
                                            label = { Text("Rent / Price (₹)") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    val brokerTitle = broker?.name?.takeIf { it.isNotBlank() } ?: match.displayTitle
                                    val noteSummary = notes.trim().ifBlank { "Co-broking discussion with $brokerTitle" }
                                    CrmCallManager.saveBrokerContact(context, call.phoneNumber, brokerTitle, broker?.firm.orEmpty(), noteSummary)
                                    if (brokerMode == "CLIENT_REQ" && reqText.isNotBlank()) {
                                        val tomorrow = computeFollowUpPreset(1, "11:00 AM").first
                                        val newLead = CrmLead(
                                            id = "",
                                            name = "Client via $brokerTitle",
                                            phone = ContactUtils.normalizePhoneNumber(call.phoneNumber),
                                            segment = reqDivision,
                                            requirement = reqText.trim(),
                                            location = reqLocation,
                                            budget = reqBudget.trim(),
                                            status = "New",
                                            nextFollowUp = tomorrow,
                                            nextFollowUpTime = "11:00 AM",
                                            notes = "Via Broker $brokerTitle ($cleanDisplayPhone). $noteSummary",
                                            assignedTo = safeEmployeeName,
                                            leadSource = "Channel Partner / Broker"
                                        )
                                        onSaveLeadAndSync(newLead, "Broker Client Req", noteSummary)
                                    } else if (brokerMode == "PROPERTY_LISTING" && propProject.isNotBlank()) {
                                        val priceVal = propPrice.toDoubleOrNull() ?: 0.0
                                        val newProp = PropertyItem(
                                            id = "prop_brk_${System.currentTimeMillis()}",
                                            propertyCode = "OL-BRK-${(System.currentTimeMillis() % 1000)}",
                                            projectName = propProject.trim(),
                                            location = propLocation,
                                            unitNumber = propUnit.trim(),
                                            tab = InventoryTab.OFF_LEASE,
                                            portfolio = "Commercial",
                                            transactionType = "Lease",
                                            carpetAreaSqFt = propArea.toDoubleOrNull() ?: 0.0,
                                            priceOrRent = priceVal,
                                            rent = if (priceVal > 0) priceVal else null,
                                            brokerName = brokerTitle,
                                            brokerContact = ContactUtils.normalizePhoneNumber(call.phoneNumber),
                                            sourceInfo = "Channel Partner / Broker",
                                            remarks = noteSummary,
                                            status = AvailabilityStatus.AVAILABLE,
                                            updatedBy = safeEmployeeName
                                        )
                                        onAddNewProperty(newProp)
                                    }
                                    val savedCall = call.copy(
                                        callerName = brokerTitle,
                                        notes = noteSummary,
                                        isLinked = true,
                                        linkedOpportunityType = "Broker",
                                        linkedOpportunityTitle = brokerTitle
                                    )
                                    CrmCallManager.addOrUpdateCall(context, savedCall)
                                    CrmCallManager.logActivity(
                                        context = context,
                                        entityId = savedCall.id,
                                        type = "Broker Co-Broking Call",
                                        summary = "$brokerTitle ($cleanDisplayPhone)",
                                        details = noteSummary,
                                        author = safeEmployeeName
                                    )
                                    onCallResolved(savedCall)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("post_call_save_broker_btn")
                            ) {
                                Text(
                                    text = when (brokerMode) {
                                        "CLIENT_REQ" -> "Save Broker Client Requirement"
                                        "PROPERTY_LISTING" -> "Save Broker Property Listing"
                                        else -> "Save Co-Broking Note"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                    }
                }

                PostCallRole.DEVELOPER_POC -> {
                    val projName = match.matchedDirectoryProject?.name
                        ?: match.matchedUnderConstProject?.projectName
                        ?: match.matchedDeveloperPoc?.projectName.orEmpty()
                    val projId = match.matchedDirectoryProject?.id
                        ?: match.matchedUnderConstProject?.projectCode
                        ?: match.matchedDeveloperPoc?.projectId.orEmpty()

                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Developer Sales POC • $projId",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryActionOrange
                            )
                            Text(
                                text = "${match.displayTitle} • $projName",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )

                            fun logDeveloperAction(actionTitle: String) {
                                val summaryText = if (notes.isNotBlank()) "$actionTitle — ${notes.trim()}" else "$actionTitle ($projName)"
                                val savedCall = call.copy(
                                    callerName = match.displayTitle,
                                    notes = summaryText,
                                    isLinked = true,
                                    linkedOpportunityType = "Developer POC",
                                    linkedOpportunityTitle = "$projId • $projName",
                                    linkedOpportunityId = projId
                                )
                                CrmCallManager.addOrUpdateCall(context, savedCall)
                                CrmCallManager.logActivity(
                                    context = context,
                                    entityId = projId.ifBlank { savedCall.id },
                                    type = actionTitle,
                                    summary = "${match.displayTitle} ($projName)",
                                    details = summaryText,
                                    author = safeEmployeeName
                                )
                                onCallResolved(savedCall)
                            }

                            Button(
                                onClick = { logDeveloperAction("Builder Meeting Logged") },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Text("Log Builder Meeting", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { logDeveloperAction("Client Site Visit Tagged") },
                                    border = BorderStroke(1.dp, BorderGrey),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text("Tag Client Visit", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                                OutlinedButton(
                                    onClick = { logDeveloperAction("Developer Follow-up Scheduled") },
                                    border = BorderStroke(1.dp, BorderGrey),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text("Schedule Follow-up", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                            }
                        }
                    }
                }

                PostCallRole.FRESH_UNKNOWN -> {
                    // Fresh Number Quick Classification (5 buttons + expandable mini-forms)
                    var freshSelection by remember(call.id) { mutableStateOf<PostCallRole?>(null) }

                    // Client Lead mini-form state
                    var freshName by remember(call.id) {
                        mutableStateOf(
                            call.callerName.takeIf { !it.equals("Unknown", true) && !it.equals("Unknown Caller", true) }.orEmpty()
                        )
                    }
                    var freshDivision by remember(call.id) { mutableStateOf("Commercial") }
                    var freshReqType by remember(call.id) { mutableStateOf("Lease") }
                    var freshLocation by remember(call.id) { mutableStateOf("Kharadi") }
                    var freshBudget by remember(call.id) { mutableStateOf("") }
                    var freshFollowUp by remember(call.id) { mutableStateOf(computeFollowUpPreset(1, "11:00 AM").first) }

                    // Property Owner linking state
                    val allUnitOptions = remember(inventory) {
                        inventory.take(150).map { prop ->
                            val unit = prop.unitNumber.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                            "${prop.propertyCode} • ${prop.projectName}$unit (${prop.location})"
                        }
                    }
                    var selectedUnitLabel by remember(call.id) { mutableStateOf("") }
                    var ownerNameInput by remember(call.id) { mutableStateOf(freshName) }

                    // Broker state
                    var brokerNameInput by remember(call.id) { mutableStateOf(freshName) }
                    var brokerFirmInput by remember(call.id) { mutableStateOf("") }

                    // Developer POC state
                    val projectOptions = remember(directoryProjects, underConstProjects) {
                        val dirOpts = directoryProjects.map { "${it.id} • ${it.name}" }
                        val ucOpts = underConstProjects.map { "${it.projectCode} • ${it.projectName}" }
                        (dirOpts + ucOpts).distinct()
                    }
                    var selectedProjectOption by remember(call.id) { mutableStateOf(projectOptions.firstOrNull().orEmpty()) }
                    var devPocName by remember(call.id) { mutableStateOf(freshName) }

                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { freshSelection = PostCallRole.CLIENT_LEAD },
                                    border = BorderStroke(1.dp, if (freshSelection == PostCallRole.CLIENT_LEAD) PrimaryActionOrange else BorderGrey),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (freshSelection == PostCallRole.CLIENT_LEAD) SelectedSurfaceContainer else SurfaceWhite
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("post_call_fresh_client_btn")
                                ) {
                                    Text("Client Lead", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { freshSelection = PostCallRole.PROPERTY_OWNER },
                                    border = BorderStroke(1.dp, if (freshSelection == PostCallRole.PROPERTY_OWNER) PrimaryActionOrange else BorderGrey),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (freshSelection == PostCallRole.PROPERTY_OWNER) SelectedSurfaceContainer else SurfaceWhite
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("post_call_fresh_owner_btn")
                                ) {
                                    Text("Property Owner", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { freshSelection = PostCallRole.BROKER },
                                    border = BorderStroke(1.dp, if (freshSelection == PostCallRole.BROKER) PrimaryActionOrange else BorderGrey),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (freshSelection == PostCallRole.BROKER) SelectedSurfaceContainer else SurfaceWhite
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("post_call_fresh_broker_btn")
                                ) {
                                    Text("Broker / CP", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { freshSelection = PostCallRole.DEVELOPER_POC },
                                    border = BorderStroke(1.dp, if (freshSelection == PostCallRole.DEVELOPER_POC) PrimaryActionOrange else BorderGrey),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (freshSelection == PostCallRole.DEVELOPER_POC) SelectedSurfaceContainer else SurfaceWhite
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("post_call_fresh_dev_btn")
                                ) {
                                    Text("Developer POC", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
                                }
                            }

                            // Expandable Mini-Forms for selected fresh role
                            when (freshSelection) {
                                PostCallRole.CLIENT_LEAD -> {
                                    HorizontalDivider(color = BorderGrey)
                                    OutlinedTextField(
                                        value = freshName,
                                        onValueChange = { freshName = it },
                                        label = { Text("Client Name *") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CompactDropdownField(
                                            label = "Division",
                                            selectedValue = freshDivision,
                                            options = listOf("Commercial", "Residential"),
                                            onValueSelected = { freshDivision = it },
                                            modifier = Modifier.weight(1f)
                                        )
                                        CompactDropdownField(
                                            label = "Type",
                                            selectedValue = freshReqType,
                                            options = listOf("Lease", "Buy", "Pre-Leased"),
                                            onValueSelected = { freshReqType = it },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SearchableDropdownField(
                                            label = "Location",
                                            selectedValue = freshLocation,
                                            options = TlcStandardOptions.puneLocations,
                                            onValueSelected = { freshLocation = it },
                                            modifier = Modifier.weight(1f)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            OutlinedTextField(
                                                value = freshBudget,
                                                onValueChange = { freshBudget = it },
                                                label = { Text("Budget (₹)") },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            val preview = CalculationUtils.formatCrmBudgetPreview(freshBudget, isMonthly = freshReqType == "Lease")
                                            if (preview.isNotBlank()) {
                                                Text(preview, fontSize = 10.sp, color = PrimaryActionOrange, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Follow-up: $freshFollowUp", fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                                        TextButton(
                                            onClick = { openDatePicker { picked -> freshFollowUp = picked } },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text("Pick Date", color = PrimaryActionOrange, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            val resolvedName = freshName.trim().ifBlank { "Client $cleanDisplayPhone" }
                                            val summaryReq = notes.trim().ifBlank { "$freshDivision $freshReqType in $freshLocation" }
                                            val newLead = CrmLead(
                                                id = "",
                                                name = resolvedName,
                                                phone = ContactUtils.normalizePhoneNumber(call.phoneNumber),
                                                segment = freshDivision,
                                                requirement = summaryReq,
                                                requirementType = freshReqType,
                                                location = freshLocation,
                                                budget = freshBudget.trim().ifBlank { "Negotiable" },
                                                status = "New",
                                                nextFollowUp = freshFollowUp,
                                                nextFollowUpTime = "11:00 AM",
                                                followUpTime = "11:00 AM",
                                                notes = summaryReq,
                                                assignedTo = safeEmployeeName,
                                                lastContactDate = todayDateStr,
                                                lastContactMode = "Call",
                                                lastContactOutcome = "Connected - Interested",
                                                leadSource = "Inbound Call"
                                            )
                                            onSaveLeadAndSync(newLead, "New Client Call", summaryReq)
                                            val savedCall = call.copy(
                                                callerName = resolvedName,
                                                notes = summaryReq,
                                                isLinked = true,
                                                linkedOpportunityType = "Client",
                                                linkedOpportunityTitle = resolvedName
                                            )
                                            CrmCallManager.addOrUpdateCall(context, savedCall)
                                            CrmCallManager.logActivity(
                                                context = context,
                                                entityId = savedCall.id,
                                                type = "Fresh Call -> Client Lead",
                                                summary = "$resolvedName ($cleanDisplayPhone)",
                                                details = summaryReq,
                                                author = safeEmployeeName
                                            )
                                            onCallResolved(savedCall)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("post_call_save_fresh_client_btn")
                                    ) {
                                        Text("Save Lead to CRM & Sync", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }

                                PostCallRole.PROPERTY_OWNER -> {
                                    HorizontalDivider(color = BorderGrey)
                                    OutlinedTextField(
                                        value = ownerNameInput,
                                        onValueChange = { ownerNameInput = it },
                                        label = { Text("Owner Name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    SearchableDropdownField(
                                        label = "Link to Existing Unit (Optional)",
                                        selectedValue = selectedUnitLabel,
                                        options = allUnitOptions,
                                        onValueSelected = { selectedUnitLabel = it },
                                        allowAddNew = false,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            enabled = selectedUnitLabel.isNotBlank(),
                                            onClick = {
                                                val idx = allUnitOptions.indexOf(selectedUnitLabel)
                                                val target = inventory.getOrNull(idx)
                                                if (target != null) {
                                                    val cleanPhone = ContactUtils.normalizePhoneNumber(call.phoneNumber)
                                                    val updatedCall = call.copy(
                                                        callerName = ownerNameInput.trim().ifBlank { target.ownerName ?: "Owner" },
                                                        notes = notes.trim().ifBlank { "Owner phone linked to ${target.propertyCode}" },
                                                        isLinked = true,
                                                        linkedOpportunityType = "Owner",
                                                        linkedOpportunityTitle = "${target.propertyCode} • ${target.projectName}",
                                                        linkedOpportunityId = target.id
                                                    )
                                                    onLinkOwnerExistingProperty(target, updatedCall, ownerNameInput.trim())
                                                    CrmCallManager.addOrUpdateCall(context, updatedCall)
                                                    onCallResolved(updatedCall)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Text("Link Unit", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = { onOpenFullAddProperty(ContactUtils.normalizePhoneNumber(call.phoneNumber)) },
                                            border = BorderStroke(1.dp, BorderGrey),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Text("+ New Property", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        }
                                    }
                                }

                                PostCallRole.BROKER -> {
                                    HorizontalDivider(color = BorderGrey)
                                    OutlinedTextField(
                                        value = brokerNameInput,
                                        onValueChange = { brokerNameInput = it },
                                        label = { Text("Broker / Partner Name *") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = brokerFirmInput,
                                        onValueChange = { brokerFirmInput = it },
                                        label = { Text("Agency / Firm (Optional)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            val bName = brokerNameInput.trim().ifBlank { "Channel Partner ($cleanDisplayPhone)" }
                                            val noteText = notes.trim().ifBlank { "Tagged Channel Partner from call" }
                                            CrmCallManager.saveBrokerContact(context, call.phoneNumber, bName, brokerFirmInput.trim(), noteText)
                                            val savedCall = call.copy(
                                                callerName = bName,
                                                notes = noteText,
                                                isLinked = true,
                                                linkedOpportunityType = "Broker",
                                                linkedOpportunityTitle = bName
                                            )
                                            CrmCallManager.addOrUpdateCall(context, savedCall)
                                            CrmCallManager.logActivity(
                                                context = context,
                                                entityId = savedCall.id,
                                                type = "Tagged Channel Partner",
                                                summary = "$bName ($cleanDisplayPhone)",
                                                details = noteText,
                                                author = safeEmployeeName
                                            )
                                            onCallResolved(savedCall)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Text("Save as Broker / Channel Partner", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }

                                PostCallRole.DEVELOPER_POC -> {
                                    HorizontalDivider(color = BorderGrey)
                                    OutlinedTextField(
                                        value = devPocName,
                                        onValueChange = { devPocName = it },
                                        label = { Text("Sales POC Name *") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    SearchableDropdownField(
                                        label = "Link to Project",
                                        selectedValue = selectedProjectOption,
                                        options = projectOptions,
                                        onValueSelected = { selectedProjectOption = it },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        enabled = selectedProjectOption.isNotBlank(),
                                        onClick = {
                                            val parts = selectedProjectOption.split(" • ", limit = 2)
                                            val pId = parts.firstOrNull().orEmpty().trim()
                                            val pName = parts.getOrNull(1).orEmpty().ifBlank { selectedProjectOption }
                                            val contactTitle = devPocName.trim().ifBlank { "Sales POC" }
                                            val div = directoryProjects.firstOrNull { it.id == pId }?.division ?: "Residential"
                                            CrmCallManager.saveProjectPocLink(context, call.phoneNumber, pId, pName, div, contactTitle)
                                            val noteText = notes.trim().ifBlank { "Linked Developer POC $contactTitle to $pName" }
                                            val savedCall = call.copy(
                                                callerName = contactTitle,
                                                notes = noteText,
                                                isLinked = true,
                                                linkedOpportunityType = "Developer POC",
                                                linkedOpportunityTitle = "$pId • $pName",
                                                linkedOpportunityId = pId
                                            )
                                            CrmCallManager.addOrUpdateCall(context, savedCall)
                                            CrmCallManager.logActivity(
                                                context = context,
                                                entityId = pId.ifBlank { savedCall.id },
                                                type = "Linked Developer POC",
                                                summary = "$contactTitle • $pName ($cleanDisplayPhone)",
                                                details = noteText,
                                                author = safeEmployeeName
                                            )
                                            onCallResolved(savedCall)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Text("Link Developer Sales POC", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }

                                else -> Unit
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}



