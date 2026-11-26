package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.ai.BatchCallAnalysisCoordinator
import com.example.ai.CallAudioAiAnalyzer
import com.example.ai.MicAudioRecorderController
import com.example.ai.toAnalysisResult
import com.example.crm.*
import com.example.data.OfflineDiskCache
import com.example.data.projects.DirectoryProject
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WhatsAppGreen = Color(0xFF16A34A)
private val ClientLeadAccent = Color(0xFF1D4ED8)
private val OwnerAccent = Color(0xFF0F766E)
private val BrokerAccent = Color(0xFF7C3AED)
private val TenantAccent = Color(0xFFB45309)
private val DevVendorAccent = Color(0xFF475569)

/**
 * Dedicated full-screen Call Action Router (`CallLogActionScreen`).
 *
 * Replaces the old swipeable bottom-sheet popup (`PostCallSmartSheet`) to eliminate accidental
 * swipe-down dismissals and cramped mini-forms:
 * 1. Top Bar: `<- Back` + Caller Name/Number + green `WA` (`wa.me/91...`) + quick `Ignore / Spam`.
 * 2. Auto-Detect Known Caller card pinned at the top for 1-tap jump to existing Lead / Owner / Tenant / Broker / Project.
 * 3. Audio & AI Strip: exact matched `CallRecordingOnDemandSection` (Play/Pause + `AI Summarize` + detected specs).
 * 4. Caller Role & Direct Route Cards (nothing below the roles): routes straight into full destination screens
 *    carrying `ActiveCallActionContext` (Auto-Link on Save + Pinned Mini Audio Player Bar).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallLogActionScreen(
    call: CrmCallRecord,
    leads: List<CrmLead>,
    inventory: List<PropertyItem>,
    directoryProjects: List<DirectoryProject> = emptyList(),
    underConstProjects: List<UnderConstProject> = emptyList(),
    tenants: List<OfflineDiskCache.TenantCacheRecord> = emptyList(),
    brokersVendors: List<BrokerVendorRecord> = emptyList(),
    queuePosition: Int = 1,
    queueTotal: Int = 1,
    onBack: () -> Unit,
    onSkipLater: (CrmCallRecord) -> Unit,
    onMarkPersonalIgnore: (CrmCallRecord) -> Unit,
    onOpenExistingRecord: (PostCallMatchResult, ActiveCallActionContext) -> Unit,
    onRouteClientLead: (segment: String, session: ActiveCallActionContext) -> Unit,
    onRouteOwnerAddProperty: (initialTab: InventoryTab, session: ActiveCallActionContext) -> Unit,
    onUpdateOwnerUnitStatus: (property: PropertyItem, newStatus: AvailabilityStatus, callNote: String, updatedPriceOrRent: Double?) -> Boolean,
    onRouteBrokerHasClient: (session: ActiveCallActionContext) -> Unit,
    onRouteBrokerHasProperty: (session: ActiveCallActionContext) -> Unit,
    onRouteTenant: (existingTenant: OfflineDiskCache.TenantCacheRecord?, session: ActiveCallActionContext) -> Unit,
    onRouteDeveloperPoc: (matchedProject: DirectoryProject?, session: ActiveCallActionContext) -> Unit,
    onRouteVendor: (existingVendor: BrokerVendorRecord?, session: ActiveCallActionContext) -> Unit,
    onCallHandled: (CrmCallRecord) -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val effectiveTenants = remember(tenants) {
        tenants.ifEmpty { OfflineDiskCache.loadTenants(context) }
    }
    val effectiveBrokersVendors = remember(brokersVendors) {
        brokersVendors.ifEmpty { OfflineDiskCache.loadBrokersVendors(context) }
    }

    var workingCall by remember(call.id) { mutableStateOf(call) }
    var aiSummaryText by remember(call.id) {
        mutableStateOf(
            call.notes.takeUnless { it.equals("Imported from device call log", ignoreCase = true) }.orEmpty()
        )
    }
    var matchedRecording by remember(call.id) { mutableStateOf<MatchedCallRecording?>(null) }
    var showIgnoreMenu by remember { mutableStateOf(false) }
    var showUpdateUnitDialog by remember { mutableStateOf(false) }

    // Batch 44: Microphone audio transcription (gemini-3.5-transcribe) & Deep Native Audio Extract
    val micController = remember(context) { MicAudioRecorderController(context) }
    var isRecordingMic by remember(call.id) { mutableStateOf(false) }
    var micElapsedSec by remember(call.id) { mutableIntStateOf(0) }
    var isTranscribingMic by remember(call.id) { mutableStateOf(false) }
    var isDeepExtractingAudio by remember(call.id) { mutableStateOf(false) }
    var aiAudioStatusHint by remember(call.id) { mutableStateOf<String?>(null) }

    DisposableEffect(call.id) {
        onDispose {
            micController.cancelAndDelete()
        }
    }

    LaunchedEffect(isRecordingMic) {
        if (!isRecordingMic) {
            micElapsedSec = 0
        } else {
            micElapsedSec = 0
            while (isRecordingMic) {
                delay(1000L)
                micElapsedSec++
            }
        }
    }

    fun startActionMicRecording() {
        val started = micController.startRecording()
        if (started.isSuccess) {
            isRecordingMic = true
            aiAudioStatusHint = "Recording voice note... Speak in Marathi, Hindi, or English"
        } else {
            Toast.makeText(
                context,
                started.exceptionOrNull()?.message ?: "Unable to start microphone",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun stopAndTranscribeActionMic() {
        val file = micController.stopRecording()
        isRecordingMic = false
        if (file == null || !file.exists() || file.length() == 0L) {
            aiAudioStatusHint = "No voice audio captured. Tap Dictate Note to retry."
            return
        }
        isTranscribingMic = true
        aiAudioStatusHint = "Transcribing voice note with ${CallAudioAiAnalyzer.MODEL_TRANSCRIBE}..."
        coroutineScope.launch {
            try {
                val transcriptRes = CallAudioAiAnalyzer.transcribeMicrophoneAudio(file, "audio/mp4")
                val transcript = transcriptRes.getOrElse { err ->
                    aiAudioStatusHint = "Transcription failed: ${err.message}"
                    Toast.makeText(context, err.message ?: "Transcription failed", Toast.LENGTH_LONG).show()
                    return@launch
                }
                aiAudioStatusHint = "Structuring call summary & syncing to Call Logs..."
                val analysisRes = BatchCallAnalysisCoordinator.analyzeDictatedTranscriptAndSync(
                    context = context,
                    call = workingCall,
                    transcript = transcript
                )
                val finalSummary = analysisRes.getOrNull()?.toAnalysisResult()?.formattedBulletsSummary()
                    ?.ifBlank { transcript }
                    ?: transcript
                aiSummaryText = finalSummary
                val updatedCall = workingCall.copy(
                    notes = finalSummary,
                    syncStatus = "PENDING",
                    updatedAt = System.currentTimeMillis()
                )
                workingCall = updatedCall
                CrmCallManager.addOrUpdateCall(context, updatedCall)
                CallActionSessionManager.updateAiSummary(updatedCall.id, finalSummary)
                aiAudioStatusHint = "Voice note transcribed & saved to AI Review queue."
            } finally {
                runCatching { file.delete() }
                isTranscribingMic = false
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startActionMicRecording()
        } else {
            Toast.makeText(context, "Microphone permission is required for voice transcription.", Toast.LENGTH_LONG).show()
        }
    }

    var matchResult by remember(call.id) {
        mutableStateOf(
            PostCallMatchResult(
                call = call,
                role = PostCallRole.FRESH_UNKNOWN,
                displayTitle = call.callerName.takeIf {
                    it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
                } ?: ContactUtils.formatDisplayPhone(call.phoneNumber).ifBlank { call.phoneNumber },
                subtitle = "Checking directory..."
            )
        )
    }

    LaunchedEffect(
        call.id,
        leads.size,
        inventory.size,
        directoryProjects.size,
        underConstProjects.size,
        effectiveTenants.size,
        effectiveBrokersVendors.size
    ) {
        matchResult = withContext(Dispatchers.IO) {
            CrmCallManager.matchCallToDatabase(
                context = context,
                call = workingCall,
                leads = leads,
                inventory = inventory,
                directoryProjects = directoryProjects,
                underConstProjects = underConstProjects,
                tenants = effectiveTenants,
                brokersVendors = effectiveBrokersVendors
            )
        }
    }

    val cleanDisplayPhone = remember(workingCall.phoneNumber) {
        ContactUtils.formatDisplayPhone(workingCall.phoneNumber).ifBlank { workingCall.phoneNumber }
    }
    val cleanCallerName = remember(workingCall.callerName, matchResult.displayTitle, cleanDisplayPhone) {
        val fromMatch = matchResult.displayTitle.takeIf {
            it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
        }
        val fromCall = workingCall.callerName.takeIf {
            it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
        }
        fromMatch ?: fromCall ?: cleanDisplayPhone
    }
    val formattedTimestamp = remember(workingCall.timestamp) {
        runCatching {
            SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH).format(Date(workingCall.timestamp))
        }.getOrDefault("")
    }
    val detectedSpecs = remember(aiSummaryText) { extractDetectedCallSpecChips(aiSummaryText) }

    fun buildSession(roleLabel: String): ActiveCallActionContext {
        val currentCallWithNotes = workingCall.copy(
            notes = aiSummaryText.trim().ifBlank { workingCall.notes },
            recordingFilename = matchedRecording?.fileName ?: workingCall.recordingFilename
        )
        return ActiveCallActionContext(
            callId = currentCallWithNotes.id,
            callRecord = currentCallWithNotes,
            callerName = cleanCallerName,
            callerPhone = currentCallWithNotes.phoneNumber,
            durationSeconds = currentCallWithNotes.durationSeconds,
            aiSummaryNote = aiSummaryText.trim(),
            matchedRecording = matchedRecording,
            targetRoleLabel = roleLabel
        )
    }

    fun openWhatsAppDirect() {
        val digits10 = CrmCallManager.last10Digits(workingCall.phoneNumber)
        if (digits10.length < 10) {
            Toast.makeText(context, "Valid 10-digit mobile number required for WhatsApp", Toast.LENGTH_SHORT).show()
            return
        }
        val updated = CrmCallManager.logWhatsAppTouchOnCall(context, workingCall)
        workingCall = updated
        val uri = Uri.parse("https://wa.me/91$digits10")
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            Toast.makeText(context, "WhatsApp is not installed on this device", Toast.LENGTH_SHORT).show()
        }
    }

    val isSpamFlagged = remember(workingCall.leadStatus, workingCall.notes) {
        workingCall.leadStatus.equals("Spam", ignoreCase = true) ||
            workingCall.notes.contains("Marked Spam", ignoreCase = true)
    }

    val hasKnownExistingMatch = remember(matchResult) {
        matchResult.matchedLead != null ||
            matchResult.matchedProperties.isNotEmpty() ||
            matchResult.matchedTenant != null ||
            matchResult.matchedBrokerVendor != null ||
            matchResult.matchedBroker != null ||
            matchResult.matchedDirectoryProject != null ||
            matchResult.matchedDeveloperPoc != null
    }

    Surface(
        color = BackgroundSoft,
        modifier = Modifier
            .fillMaxSize()
            .testTag("call_log_action_screen")
    ) {
        Scaffold(
            containerColor = BackgroundSoft,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(
                    color = SurfaceWhite,
                    shadowElevation = 3.dp,
                    border = BorderStroke(0.5.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("call_action_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = cleanCallerName,
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isSpamFlagged) {
                                    Surface(
                                        color = ErrorRed.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.testTag("call_action_spam_badge")
                                    ) {
                                        Text(
                                            text = "SPAM",
                                            color = ErrorRed,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (queueTotal > 1) {
                                    Surface(
                                        color = PrimaryActionOrange.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "$queuePosition/$queueTotal",
                                            color = PrimaryActionOrange,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = listOfNotNull(
                                    cleanDisplayPhone.takeIf { it.isNotBlank() && it != cleanCallerName },
                                    workingCall.callType.label,
                                    CrmCallManager.formatDuration(workingCall.durationSeconds),
                                    formattedTimestamp.takeIf { it.isNotBlank() }
                                ).joinToString(" • "),
                                color = SecondaryText,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Compact green WhatsApp direct link button (wa.me/91..., 48dp accessible height)
                        Surface(
                            color = WhatsAppGreen,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { openWhatsAppDirect() }
                                .testTag("call_action_wa_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Chat,
                                    contentDescription = "Open WhatsApp",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "WA",
                                    color = Color.White,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        // Quick Spam / Personal / Skip menu button
                        Box {
                            IconButton(
                                onClick = { showIgnoreMenu = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSpamFlagged) ErrorRed.copy(alpha = 0.14f) else Color.Transparent)
                                    .testTag("call_action_ignore_btn")
                            ) {
                                Icon(
                                    imageVector = if (isSpamFlagged) Icons.Default.Report else Icons.Default.Block,
                                    contentDescription = "Spam, Personal, or Skip options",
                                    tint = ErrorRed
                                )
                            }
                            DropdownMenu(
                                expanded = showIgnoreMenu,
                                onDismissRequest = { showIgnoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Mark as Spam & Mute", fontWeight = FontWeight.Bold, color = ErrorRed) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Report, contentDescription = null, tint = ErrorRed)
                                    },
                                    modifier = Modifier.testTag("call_action_menu_spam"),
                                    onClick = {
                                        showIgnoreMenu = false
                                        val spamCall = workingCall.copy(
                                            notes = if (workingCall.notes.contains("Spam", true)) workingCall.notes else "Marked Spam",
                                            leadStatus = "Spam"
                                        )
                                        workingCall = spamCall
                                        onMarkPersonalIgnore(spamCall)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Ignore / Mute Personal Number", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = ErrorRed)
                                    },
                                    modifier = Modifier.testTag("call_action_menu_personal"),
                                    onClick = {
                                        showIgnoreMenu = false
                                        onMarkPersonalIgnore(workingCall)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Skip for Later") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Schedule, contentDescription = null, tint = SecondaryText)
                                    },
                                    modifier = Modifier.testTag("call_action_menu_skip"),
                                    onClick = {
                                        showIgnoreMenu = false
                                        onSkipLater(workingCall)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── 1. AUTO-DETECT KNOWN CALLER FIRST (1-TAP OPEN EXISTING RECORD) ──
                AnimatedVisibility(visible = hasKnownExistingMatch) {
                    KnownCallerHighlightCard(
                        match = matchResult,
                        onOpenExisting = {
                            val roleLabel = when (matchResult.role) {
                                PostCallRole.CLIENT_LEAD -> "Client"
                                PostCallRole.PROPERTY_OWNER -> "Owner"
                                PostCallRole.TENANT -> "Tenant"
                                PostCallRole.BROKER -> "Broker"
                                PostCallRole.DEVELOPER_POC -> "Developer POC"
                                PostCallRole.OPS_VENDOR -> "Ops / Vendor"
                                PostCallRole.FRESH_UNKNOWN -> "Client"
                            }
                            val session = buildSession(roleLabel)
                            CallActionSessionManager.beginSession(session)
                            onOpenExistingRecord(matchResult, session)
                        }
                    )
                }

                // ── 2. AUDIO & AI STRIP (EXACT MATCHED PLAYER + AI SUMMARIZE) ──
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("call_action_audio_ai_strip")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CallRecordingOnDemandSection(
                            call = workingCall,
                            currentNotes = aiSummaryText,
                            testTagPrefix = "call_action_recording",
                            onRecordingOutcomeChanged = { outcome ->
                                matchedRecording = outcome.matched
                                val recName = outcome.matched?.fileName
                                if (!recName.isNullOrBlank() && workingCall.recordingFilename != recName) {
                                    workingCall = workingCall.copy(recordingFilename = recName)
                                }
                            },
                            onSummaryGenerated = { summary ->
                                val cleanSummary = summary.trim()
                                aiSummaryText = cleanSummary
                                val updatedCall = workingCall.copy(
                                    notes = cleanSummary,
                                    recordingFilename = matchedRecording?.fileName ?: workingCall.recordingFilename,
                                    syncStatus = "PENDING",
                                    updatedAt = System.currentTimeMillis()
                                )
                                workingCall = updatedCall
                                CrmCallManager.addOrUpdateCall(context, updatedCall)
                                CallActionSessionManager.updateAiSummary(updatedCall.id, cleanSummary)
                            }
                        )

                        // Batch 44: Live Microphone Dictation (gemini-3.5-transcribe) & Structured Audio Extract
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (isRecordingMic) {
                                        stopAndTranscribeActionMic()
                                    } else {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            startActionMicRecording()
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                },
                                enabled = !isTranscribingMic && !isDeepExtractingAudio,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isRecordingMic) ErrorRed.copy(alpha = 0.12f) else Color.Transparent,
                                    contentColor = if (isRecordingMic) ErrorRed else ClientLeadAccent
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isRecordingMic) ErrorRed else ClientLeadAccent.copy(alpha = 0.45f)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("call_action_mic_dictate_btn")
                            ) {
                                if (isTranscribingMic) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        color = ClientLeadAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isRecordingMic) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = if (isRecordingMic) "Stop and transcribe voice note" else "Dictate voice note",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = when {
                                        isTranscribingMic -> "Transcribing..."
                                        isRecordingMic -> "Stop & Transcribe (${micElapsedSec}s)"
                                        else -> "Dictate Voice Note"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (matchedRecording != null) {
                                Button(
                                    onClick = {
                                        val rec = matchedRecording ?: return@Button
                                        isDeepExtractingAudio = true
                                        aiAudioStatusHint = "Analyzing Marathi/Hindi/English audio & syncing..."
                                        coroutineScope.launch {
                                            val res = BatchCallAnalysisCoordinator.analyzeSingleCallAndSync(
                                                context = context,
                                                call = workingCall,
                                                recording = rec
                                            )
                                            res.onSuccess { entity ->
                                                val parsed = entity.toAnalysisResult()
                                                val bullets = parsed.formattedBulletsSummary()
                                                if (bullets.isNotBlank()) {
                                                    aiSummaryText = bullets
                                                    workingCall = workingCall.copy(notes = bullets)
                                                    CallActionSessionManager.updateAiSummary(workingCall.id, bullets)
                                                }
                                                aiAudioStatusHint = "Extracted (${entity.detectedLanguage} • ${entity.callerRole}) & synced to Call Logs"
                                            }.onFailure { err ->
                                                aiAudioStatusHint = err.message ?: "Audio extraction failed"
                                                Toast.makeText(context, aiAudioStatusHint, Toast.LENGTH_LONG).show()
                                            }
                                            isDeepExtractingAudio = false
                                        }
                                    },
                                    enabled = !isDeepExtractingAudio && !isRecordingMic && !isTranscribingMic,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("call_action_deep_extract_btn")
                                ) {
                                    if (isDeepExtractingAudio) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            color = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = if (isDeepExtractingAudio) "Extracting..." else "AI Audio Extract",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = !aiAudioStatusHint.isNullOrBlank()) {
                            Text(
                                text = aiAudioStatusHint.orEmpty(),
                                fontSize = 11.5.sp,
                                color = if (isRecordingMic) ErrorRed else OwnerAccent,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        AnimatedVisibility(visible = aiSummaryText.isNotBlank()) {
                            Surface(
                                color = SelectedSurfaceContainer,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.28f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = PrimaryActionOrange,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "AI Summary & Detected Specs (carries forward)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryActionOrange
                                        )
                                    }
                                    Text(
                                        text = aiSummaryText,
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        color = TextPrimary
                                    )
                                    if (detectedSpecs.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            detectedSpecs.forEach { spec ->
                                                Surface(
                                                    color = SurfaceWhite,
                                                    shape = RoundedCornerShape(999.dp),
                                                    border = BorderStroke(1.dp, BorderGrey)
                                                ) {
                                                    Text(
                                                        text = spec,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = TextPrimary,
                                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 3. CALLER ROLE & DIRECT ROUTE CARDS (NOTHING BELOW ROLES) ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ROUTE THIS CALL",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.9.sp,
                        color = SecondaryText
                    )
                    Text(
                        text = "Auto-links call on Save",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OwnerAccent
                    )
                }

                // Role Card 1: Client Lead
                RoleRouteCard(
                    icon = Icons.Default.PersonSearch,
                    accentColor = ClientLeadAccent,
                    title = "Client Lead",
                    subtitle = "Opens full Add / Edit Client form with caller & AI summary pre-filled",
                    cardTestTag = "role_card_client"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RouteActionButton(
                            label = "Residential Lead",
                            accentColor = ClientLeadAccent,
                            filled = false,
                            testTag = "route_client_resi",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val session = buildSession("Client")
                                CallActionSessionManager.beginSession(session)
                                onRouteClientLead("Residential", session)
                            }
                        )
                        RouteActionButton(
                            label = "Commercial Lead",
                            accentColor = ClientLeadAccent,
                            filled = true,
                            testTag = "route_client_comm",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val session = buildSession("Client")
                                CallActionSessionManager.beginSession(session)
                                onRouteClientLead("Commercial", session)
                            }
                        )
                    }
                }

                // Role Card 2: Property Owner
                RoleRouteCard(
                    icon = Icons.Default.DomainAdd,
                    accentColor = OwnerAccent,
                    title = "Property Owner",
                    subtitle = "Add a new unit (Unit No. required) or update an existing inventory unit",
                    cardTestTag = "role_card_owner"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RouteActionButton(
                                label = "Add Resi Unit",
                                accentColor = OwnerAccent,
                                filled = false,
                                testTag = "route_owner_add_resi",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val session = buildSession("Owner")
                                    CallActionSessionManager.beginSession(session)
                                    onRouteOwnerAddProperty(InventoryTab.RESI_RENT, session)
                                }
                            )
                            RouteActionButton(
                                label = "Add Comm Unit",
                                accentColor = OwnerAccent,
                                filled = true,
                                testTag = "route_owner_add_comm",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val session = buildSession("Owner")
                                    CallActionSessionManager.beginSession(session)
                                    onRouteOwnerAddProperty(InventoryTab.OFF_LEASE, session)
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = { showUpdateUnitDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, OwnerAccent.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("route_owner_update_unit")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SyncAlt,
                                contentDescription = null,
                                tint = OwnerAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (matchResult.matchedProperties.isNotEmpty()) {
                                    "Update Existing Unit (${matchResult.matchedProperties.size} matched)"
                                } else {
                                    "Update Existing Unit (Available / Negotiation / Leased-Sold)"
                                },
                                color = OwnerAccent,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Role Card 3: Broker / CP
                RoleRouteCard(
                    icon = Icons.Default.Handshake,
                    accentColor = BrokerAccent,
                    title = "Broker / Channel Partner",
                    subtitle = "Tag to Broker — Client requirement or Broker property (Unit No. optional)",
                    cardTestTag = "role_card_broker"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RouteActionButton(
                            label = "Broker Has Client",
                            accentColor = BrokerAccent,
                            filled = false,
                            testTag = "route_broker_client",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val session = buildSession("Broker")
                                CallActionSessionManager.beginSession(session)
                                onRouteBrokerHasClient(session)
                            }
                        )
                        RouteActionButton(
                            label = "Broker Has Property",
                            accentColor = BrokerAccent,
                            filled = true,
                            testTag = "route_broker_property",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val session = buildSession("Broker")
                                CallActionSessionManager.beginSession(session)
                                onRouteBrokerHasProperty(session)
                            }
                        )
                    }
                }

                // Role Card 4: Tenant
                RoleRouteCard(
                    icon = Icons.Default.Badge,
                    accentColor = TenantAccent,
                    title = "Tenant",
                    subtitle = "Create or update a tenant lease & renewal record",
                    cardTestTag = "role_card_tenant"
                ) {
                    RouteActionButton(
                        label = if (matchResult.matchedTenant != null) {
                            "Update Tenant: ${matchResult.matchedTenant!!.tenantName}"
                        } else {
                            "Open Tenants Window"
                        },
                        accentColor = TenantAccent,
                        filled = true,
                        testTag = "route_tenant",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val session = buildSession("Tenant")
                            CallActionSessionManager.beginSession(session)
                            onRouteTenant(matchResult.matchedTenant, session)
                        }
                    )
                }

                // Role Card 5: Vendor / Developer
                RoleRouteCard(
                    icon = Icons.Default.Business,
                    accentColor = DevVendorAccent,
                    title = "Vendor / Developer",
                    subtitle = "Open Ops / Vendor partner record or Developer Project POC directory",
                    cardTestTag = "role_card_vendor"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RouteActionButton(
                            label = "Vendors",
                            accentColor = DevVendorAccent,
                            filled = true,
                            testTag = "route_vendor",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val session = buildSession("Ops / Vendor")
                                CallActionSessionManager.beginSession(session)
                                onRouteVendor(matchResult.matchedBrokerVendor, session)
                            }
                        )
                        RouteActionButton(
                            label = "Project POC",
                            accentColor = DevVendorAccent,
                            filled = false,
                            testTag = "route_developer_poc",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val session = buildSession("Developer POC")
                                CallActionSessionManager.beginSession(session)
                                onRouteDeveloperPoc(matchResult.matchedDirectoryProject, session)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Focused Quick Unit Picker + Status Update dialog for Owner -> "Update Existing Unit"
    if (showUpdateUnitDialog) {
        QuickOwnerUnitStatusDialog(
            matchedProperties = matchResult.matchedProperties,
            allInventory = inventory,
            defaultNote = aiSummaryText.ifBlank { "Confirmed via call with $cleanCallerName" },
            onDismiss = { showUpdateUnitDialog = false },
            onConfirmUpdate = { selectedProp, newStatus, noteText, revisedPrice ->
                val ok = onUpdateOwnerUnitStatus(selectedProp, newStatus, noteText, revisedPrice)
                if (ok) {
                    CrmCallManager.markCallHandledAndLinked(
                        context = context,
                        callId = workingCall.id,
                        fallbackCall = workingCall,
                        oppType = "Owner",
                        oppTitle = "${selectedProp.projectName.ifBlank { selectedProp.propertyCode }} (${newStatus.label})",
                        oppId = selectedProp.id,
                        notesOverride = noteText
                    )
                    showUpdateUnitDialog = false
                    onCallHandled(workingCall)
                }
            }
        )
    }
}

@Composable
private fun KnownCallerHighlightCard(
    match: PostCallMatchResult,
    onOpenExisting: () -> Unit
) {
    val badgeLabel = when {
        match.matchedLead != null -> "KNOWN CRM LEAD"
        match.matchedProperties.isNotEmpty() -> "KNOWN OWNER • ${match.matchedProperties.size} UNIT(S)"
        match.matchedTenant != null -> "KNOWN TENANT"
        match.matchedBrokerVendor != null -> "KNOWN ${match.matchedBrokerVendor.roleCategory.uppercase(Locale.ENGLISH)}"
        match.matchedBroker != null -> "KNOWN BROKER"
        match.matchedDirectoryProject != null -> "KNOWN DEVELOPER POC"
        else -> "KNOWN CONTACT"
    }
    Surface(
        color = OwnerAccent.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, OwnerAccent.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("call_action_known_caller_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(OwnerAccent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = OwnerAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = badgeLabel,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.7.sp,
                        color = OwnerAccent
                    )
                    Text(
                        text = match.displayTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (match.subtitle.isNotBlank()) {
                        Text(
                            text = match.subtitle,
                            fontSize = 12.sp,
                            color = SecondaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Button(
                onClick = onOpenExisting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = OwnerAccent,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("call_action_open_existing_btn")
            ) {
                Text(
                    text = "Open Existing Record",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun RoleRouteCard(
    icon: ImageVector,
    accentColor: Color,
    title: String,
    subtitle: String,
    cardTestTag: String,
    content: @Composable () -> Unit
) {
    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderGrey),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(cardTestTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = subtitle,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        color = SecondaryText
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun RouteActionButton(
    label: String,
    accentColor: Color,
    filled: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (filled) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = modifier
                .heightIn(min = 48.dp)
                .testTag(testTag)
        ) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(15.dp)
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            border = BorderStroke(1.2.dp, accentColor.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = modifier
                .heightIn(min = 48.dp)
                .testTag(testTag)
        ) {
            Text(
                text = label,
                color = accentColor,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun QuickOwnerUnitStatusDialog(
    matchedProperties: List<PropertyItem>,
    allInventory: List<PropertyItem>,
    defaultNote: String,
    onDismiss: () -> Unit,
    onConfirmUpdate: (PropertyItem, AvailabilityStatus, String, Double?) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedProperty by remember { mutableStateOf(matchedProperties.firstOrNull()) }
    var selectedStatus by remember(selectedProperty?.id) {
        mutableStateOf(selectedProperty?.status ?: AvailabilityStatus.AVAILABLE)
    }
    var updatedPriceInput by remember(selectedProperty?.id) { mutableStateOf("") }
    var noteInput by remember { mutableStateOf(defaultNote) }

    val candidateUnits = remember(matchedProperties, allInventory, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ENGLISH)
        val matchedIds = matchedProperties.map { it.id }.toSet()
        val combined = matchedProperties + allInventory.filterNot { it.id in matchedIds }
        if (q.isBlank()) {
            combined.take(25)
        } else {
            combined.filter { prop ->
                prop.projectName.contains(q, true) ||
                    prop.unitNumber.contains(q, true) ||
                    prop.propertyCode.contains(q, true) ||
                    prop.location.contains(q, true) ||
                    prop.ownerName.orEmpty().contains(q, true)
            }.take(30)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, BorderGrey),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.84f)
                .navigationBarsPadding()
                .imePadding()
                .testTag("owner_update_unit_dialog")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Update Existing Unit",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Pick unit and confirm Available / Negotiation / Leased-Sold",
                            fontSize = 11.5.sp,
                            color = SecondaryText
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }
                HorizontalDivider(color = BorderGrey)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search project, unit no, code, or location") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(candidateUnits, key = { it.id }) { prop ->
                            val isSelected = selectedProperty?.id == prop.id
                            val isOwnerMatch = matchedProperties.any { it.id == prop.id }
                            Surface(
                                color = if (isSelected) OwnerAccent.copy(alpha = 0.1f) else BackgroundSoft,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) OwnerAccent else BorderGrey
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedProperty = prop
                                        selectedStatus = prop.status
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            selectedProperty = prop
                                            selectedStatus = prop.status
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = listOfNotNull(
                                                prop.projectName.ifBlank { prop.propertyCode },
                                                prop.unitNumber.takeIf { it.isNotBlank() }?.let { "Unit $it" }
                                            ).joinToString(" • "),
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = listOfNotNull(
                                                prop.location.takeIf { it.isNotBlank() },
                                                prop.status.label,
                                                if (isOwnerMatch) "Phone Matched" else null
                                            ).joinToString(" • "),
                                            fontSize = 11.5.sp,
                                            color = if (isOwnerMatch) OwnerAccent else SecondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (selectedProperty != null) {
                        val isSaleUnit = selectedProperty!!.transactionType.contains("Sale", true) ||
                            selectedProperty!!.transactionType.contains("Outright", true)
                        val closedStatus = if (isSaleUnit) AvailabilityStatus.SOLD else AvailabilityStatus.LEASED
                        val statusOptions = listOf(
                            AvailabilityStatus.AVAILABLE to "Available",
                            AvailabilityStatus.UNDER_NEGOTIATION to "Negotiation",
                            closedStatus to if (isSaleUnit) "Sold" else "Leased"
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            statusOptions.forEach { (status, label) ->
                                val active = selectedStatus == status
                                FilterChip(
                                    selected = active,
                                    onClick = { selectedStatus = status },
                                    label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = updatedPriceInput,
                            onValueChange = { updatedPriceInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("Revised Price / Monthly Rent (Optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            label = { Text("Call Confirmation Note") },
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                HorizontalDivider(color = BorderGrey)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
                        enabled = selectedProperty != null,
                        onClick = {
                            val prop = selectedProperty ?: return@Button
                            onConfirmUpdate(
                                prop,
                                selectedStatus,
                                noteInput.trim(),
                                updatedPriceInput.toDoubleOrNull()?.takeIf { it > 0.0 }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OwnerAccent,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .heightIn(min = 48.dp)
                            .testTag("owner_update_unit_confirm_btn")
                    ) {
                        Text("Update & Link Call", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun extractDetectedCallSpecChips(summary: String): List<String> {
    if (summary.isBlank()) return emptyList()
    val chips = mutableListOf<String>()
    Regex("""\b\d+(?:\.\d+)?\s*BHK\b""", RegexOption.IGNORE_CASE)
        .find(summary)?.value?.uppercase(Locale.ENGLISH)?.let { chips.add(it) }
    Regex("""\b\d{3,6}(?:\s*[-–to]+\s*\d{3,6})?\s*(?:sq\.?\s*ft\.?|sqft|sft)\b""", RegexOption.IGNORE_CASE)
        .find(summary)?.value?.trim()?.let { chips.add(it) }
    Regex("""(?:₹|Rs\.?\s*)?\d+(?:\.\d+)?\s*(?:Cr|Crore|L|Lac|Lakh|K)\b""", RegexOption.IGNORE_CASE)
        .find(summary)?.value?.trim()?.let { chips.add(it) }
    val types = listOf("Office", "Showroom", "Shop", "Warehouse", "Pre-Leased", "Flat", "Villa", "Plot")
    types.firstOrNull { summary.contains(it, ignoreCase = true) }?.let { chips.add(it) }
    return chips.distinctBy { it.lowercase(Locale.ENGLISH) }.take(5)
}
