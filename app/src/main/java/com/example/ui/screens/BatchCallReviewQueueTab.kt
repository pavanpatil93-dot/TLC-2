package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ai.AudioPreFilterSkipReason
import com.example.ai.BatchCallAnalysisCoordinator
import com.example.ai.CallAudioAiAnalyzer
import com.example.ai.CallAudioAnalysisResult
import com.example.ai.CallAudioPreFilter
import com.example.ai.MicAudioRecorderController
import com.example.ai.toAnalysisResult
import com.example.crm.CallRecordingMatchOutcome
import com.example.crm.CallRecordingMatchStatus
import com.example.crm.CallType
import com.example.crm.ContactUtils
import com.example.crm.CrmCallManager
import com.example.crm.CrmCallRecord
import com.example.crm.IgnoredNumbersManager
import com.example.crm.MatchedCallRecording
import com.example.data.DeviceIdentityManager
import com.example.data.OfflineDiskCache
import com.example.data.SheetsRepository
import com.example.data.UserSettingsManager
import com.example.data.master.CallAnalysisEntity
import com.example.data.master.MasterProjectDatabase
import com.example.model.AvailabilityStatus
import com.example.model.FurnishingType
import com.example.model.InventoryTab
import com.example.model.PropertyItem
import com.example.model.VerificationStatus
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val ReviewClientColor = Color(0xFF1D4ED8)
private val ReviewOwnerColor = Color(0xFF0F766E)
private val ReviewBrokerColor = Color(0xFF7C3AED)
private val ReviewTenantColor = Color(0xFFB45309)
private val ReviewNonBizColor = Color(0xFF64748B)
private val ReviewWaGreen = Color(0xFF16A34A)
private val ReviewAmberWarning = Color(0xFFD97706)

enum class AiReviewFilterTab(val key: String, val label: String) {
    ALL("all", "All Analyzed"),
    NEW_LEADS("new_leads", "New Leads"),
    OWNER_BUILDER("owner_builder", "Owner & Builder"),
    BROKERS("brokers", "Brokers"),
    FOLLOW_UPS("follow_ups", "Follow-Ups"),
    NON_BUSINESS("non_business", "Non-Business")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchCallReviewQueueTab(
    calls: List<CrmCallRecord>,
    recordingOutcomesByCallId: Map<String, CallRecordingMatchOutcome>,
    activePlayingCallId: String?,
    onTogglePlayRecording: (CrmCallRecord, MatchedCallRecording) -> Unit,
    onAddPropertyToInventory: ((PropertyItem) -> Boolean)? = null,
    onEditExtractedClient: ((CrmLead, CrmCallRecord?) -> Unit)? = null,
    onEditExtractedProperty: ((PropertyItem, CrmCallRecord?) -> Unit)? = null,
    onPushCrmLeadToSheet: (suspend (CrmLead) -> Result<Unit>)? = null,
    onOpenCallActionScreen: ((CrmCallRecord) -> Unit)? = null,
    onCallsMutated: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember(context) { MasterProjectDatabase.getDatabase(context).masterProjectDao() }
    val analyses by dao.observeAllCallAnalyses().collectAsState(initial = emptyList())
    val batchProgress by BatchCallAnalysisCoordinator.progress.collectAsState()
    val currentDeviceName = remember(context) { DeviceIdentityManager.getFriendlyDeviceName(context) }

    var selectedTab by remember { mutableStateOf(AiReviewFilterTab.ALL) }
    var approvingIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Live Microphone Dictation (gemini-3.5-transcribe)
    val micController = remember(context) { MicAudioRecorderController(context) }
    var isRecordingMic by remember { mutableStateOf(false) }
    var micElapsedSec by remember { mutableIntStateOf(0) }
    var isTranscribingMic by remember { mutableStateOf(false) }
    var micStatusMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
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

    fun startMicRecording() {
        val started = micController.startRecording()
        if (started.isSuccess) {
            isRecordingMic = true
            micStatusMessage = "Recording voice note... Speak in Marathi, Hindi, or English"
        } else {
            Toast.makeText(
                context,
                started.exceptionOrNull()?.message ?: "Unable to start microphone recording",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun stopAndTranscribeMic() {
        val recordedFile = micController.stopRecording()
        isRecordingMic = false
        if (recordedFile == null || !recordedFile.exists() || recordedFile.length() == 0L) {
            micStatusMessage = "Recording was empty. Tap Dictate to try again."
            return
        }
        isTranscribingMic = true
        micStatusMessage = "Transcribing with ${CallAudioAiAnalyzer.MODEL_TRANSCRIBE}..."
        scope.launch {
            try {
                val transcriptRes = CallAudioAiAnalyzer.transcribeMicrophoneAudio(recordedFile, "audio/mp4")
                val transcript = transcriptRes.getOrElse { err ->
                    micStatusMessage = "Transcription failed: ${err.message}"
                    Toast.makeText(context, err.message ?: "Transcription failed", Toast.LENGTH_LONG).show()
                    return@launch
                }
                micStatusMessage = "Extracting structured CRM/Inventory fields..."
                val now = System.currentTimeMillis()
                val voiceCall = CrmCallRecord(
                    id = "voice_note_${UUID.randomUUID().toString().take(8)}",
                    callerName = "Voice Note (${SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date(now))})",
                    phoneNumber = "",
                    callType = CallType.OUTGOING,
                    durationSeconds = micElapsedSec.coerceAtLeast(10),
                    timestamp = now,
                    notes = transcript,
                    deviceName = currentDeviceName
                )
                val analyzeRes = BatchCallAnalysisCoordinator.analyzeDictatedTranscriptAndSync(
                    context = context,
                    call = voiceCall,
                    transcript = transcript
                )
                if (analyzeRes.isSuccess) {
                    micStatusMessage = "Voice note transcribed & added to AI Review queue."
                    onCallsMutated()
                    Toast.makeText(context, "Voice note ready for review!", Toast.LENGTH_SHORT).show()
                } else {
                    val errMsg = analyzeRes.exceptionOrNull()?.message ?: "Failed to structure transcript"
                    micStatusMessage = errMsg
                    Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                }
            } finally {
                runCatching { recordedFile.delete() }
                isTranscribingMic = false
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMicRecording()
        } else {
            Toast.makeText(context, "Microphone permission is required to dictate call notes.", Toast.LENGTH_LONG).show()
        }
    }

    val callsById = remember(calls) { calls.associateBy { it.id } }
    val analyzedAtByCallId = remember(analyses) { analyses.associate { it.callLogId to it.aiAnalyzedAt } }

    // Pre-filter eligible unreviewed calls (skips <10s, missed, 140/160/1800 spam, personal, already analyzed)
    val eligibilitySummary = remember(calls, analyzedAtByCallId, recordingOutcomesByCallId) {
        val eligibleIds = mutableListOf<String>()
        var withMatchedAudio = 0
        var skippedNoise = 0
        calls.forEach { call ->
            val reason = CallAudioPreFilter.evaluateEligibility(
                durationSeconds = call.durationSeconds,
                callType = call.callType,
                phoneNumber = call.phoneNumber,
                callerName = call.callerName,
                leadStatus = call.leadStatus,
                isIgnoredOrPersonal = IgnoredNumbersManager.isIgnoredOrInternal(context, call.phoneNumber),
                aiAnalyzedAt = analyzedAtByCallId[call.id]
            )
            if (reason == AudioPreFilterSkipReason.NONE) {
                eligibleIds += call.id
                if (recordingOutcomesByCallId[call.id]?.status == CallRecordingMatchStatus.MATCHED) {
                    withMatchedAudio++
                }
            } else if (reason != AudioPreFilterSkipReason.ALREADY_ANALYZED) {
                skippedNoise++
            }
        }
        Triple(eligibleIds, withMatchedAudio, skippedNoise)
    }
    val eligibleCallIds = eligibilitySummary.first
    val eligibleWithAudioCount = eligibilitySummary.second
    val skippedNoiseCount = eligibilitySummary.third

    val tabCounts = remember(analyses) {
        mapOf(
            AiReviewFilterTab.ALL to analyses.size,
            AiReviewFilterTab.NEW_LEADS to analyses.count { it.callerRole == "CLIENT_LEAD" },
            AiReviewFilterTab.OWNER_BUILDER to analyses.count {
                it.callerRole == "OWNER" || it.callerRole == "DEVELOPER_BUILDER"
            },
            AiReviewFilterTab.BROKERS to analyses.count { it.callerRole == "BROKER_CP" },
            AiReviewFilterTab.FOLLOW_UPS to analyses.count {
                it.followUpDate.isNotBlank() || it.followUpNextAction.isNotBlank()
            },
            AiReviewFilterTab.NON_BUSINESS to analyses.count {
                it.callerRole == "PERSONAL_NON_BUSINESS" || it.callerRole == "VENDOR"
            }
        )
    }

    val filteredAnalyses = remember(analyses, selectedTab) {
        val base = when (selectedTab) {
            AiReviewFilterTab.ALL -> analyses
            AiReviewFilterTab.NEW_LEADS -> analyses.filter { it.callerRole == "CLIENT_LEAD" }
            AiReviewFilterTab.OWNER_BUILDER -> analyses.filter {
                it.callerRole == "OWNER" || it.callerRole == "DEVELOPER_BUILDER"
            }
            AiReviewFilterTab.BROKERS -> analyses.filter { it.callerRole == "BROKER_CP" }
            AiReviewFilterTab.FOLLOW_UPS -> analyses.filter {
                it.followUpDate.isNotBlank() || it.followUpNextAction.isNotBlank()
            }
            AiReviewFilterTab.NON_BUSINESS -> analyses.filter {
                it.callerRole == "PERSONAL_NON_BUSINESS" || it.callerRole == "VENDOR"
            }
        }
        // Keep PENDING_REVIEW items above APPROVED items while preserving recency
        base.sortedWith(
            compareBy<CallAnalysisEntity> { it.reviewStatus == "APPROVED" }
                .thenByDescending { it.callTimestamp }
        )
    }

    fun approveAndSaveAnalysis(entity: CallAnalysisEntity, parsed: CallAudioAnalysisResult) {
        if (entity.callLogId in approvingIds) return
        approvingIds = approvingIds + entity.callLogId
        scope.launch {
            val savedSummary = withContext(Dispatchers.IO) {
                val matchedCall = callsById[entity.callLogId] ?: CrmCallRecord(
                    id = entity.callLogId,
                    callerName = entity.callerName,
                    phoneNumber = entity.phoneNumber,
                    callType = CallType.INCOMING,
                    durationSeconds = entity.durationSeconds,
                    timestamp = entity.callTimestamp,
                    notes = parsed.formattedBulletsSummary(),
                    recordingFilename = entity.recordingFilename.ifBlank { null },
                    deviceName = entity.deviceName
                )
                val employeeName = UserSettingsManager(context).userProfile.value.username.ifBlank { "TLC Team" }
                var linkedType = humanRoleLabel(entity.callerRole)
                var linkedTitle = entity.callerName.ifBlank { entity.phoneNumber.ifBlank { "Call Record" } }
                var linkedId = entity.callLogId
                var feedback = "Call marked handled"

                when {
                    entity.callerRole == "CLIENT_LEAD" ||
                        (entity.callerRole == "BROKER_CP" && parsed.extractedClientLead?.hasAnyField() == true) -> {
                        val draftLead = buildDraftCrmLeadFromAnalysis(entity, parsed, employeeName)
                        val existing = readCrmLeads(context)
                        val phoneKey = CrmCallManager.last10Digits(draftLead.phone)
                        val matchIdx = if (phoneKey.length >= 10) {
                            existing.indexOfFirst { CrmCallManager.last10Digits(it.phone) == phoneKey }
                        } else -1
                        val savedLead = if (matchIdx >= 0) {
                            val prev = existing[matchIdx]
                            prev.copy(
                                requirement = draftLead.requirement.ifBlank { prev.requirement },
                                location = draftLead.location.ifBlank { prev.location },
                                budget = draftLead.budget.ifBlank { prev.budget },
                                nextFollowUp = draftLead.nextFollowUp.ifBlank { prev.nextFollowUp },
                                nextFollowUpTime = draftLead.nextFollowUpTime.ifBlank { prev.nextFollowUpTime },
                                nextFollowUpAction = draftLead.nextFollowUpAction.ifBlank { prev.nextFollowUpAction },
                                notes = listOf(prev.notes, draftLead.notes).filter { it.isNotBlank() }.joinToString("\n---\n"),
                                updatedBy = employeeName,
                                updatedAt = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.ENGLISH).format(Date())
                            )
                        } else {
                            draftLead
                        }
                        val updatedList = if (matchIdx >= 0) {
                            existing.toMutableList().apply { set(matchIdx, savedLead) }
                        } else {
                            listOf(savedLead) + existing
                        }
                        saveCrmLeads(context, updatedList)
                        runCatching { onPushCrmLeadToSheet?.invoke(savedLead) }
                        linkedType = if (entity.callerRole == "BROKER_CP") "Broker Client" else "Client"
                        linkedTitle = savedLead.name
                        linkedId = savedLead.id
                        feedback = if (matchIdx >= 0) "Updated CRM Lead: ${savedLead.name}" else "Saved CRM Lead: ${savedLead.name}"
                    }

                    entity.callerRole == "OWNER" ||
                        entity.callerRole == "DEVELOPER_BUILDER" ||
                        (entity.callerRole == "BROKER_CP" && parsed.extractedPropertyUpdate?.hasAnyField() == true) -> {
                        val draftProp = buildDraftPropertyFromAnalysis(entity, parsed, employeeName)
                        val added = withContext(Dispatchers.Main) {
                            onAddPropertyToInventory?.invoke(draftProp) ?: false
                        }
                        linkedType = if (entity.callerRole == "BROKER_CP") "Broker Property" else "Owner"
                        linkedTitle = draftProp.projectName.ifBlank { draftProp.location.ifBlank { "Property Draft" } }
                        linkedId = draftProp.id
                        feedback = if (added) "Saved Property Draft: $linkedTitle" else "Approved Property Update: $linkedTitle"
                    }

                    entity.callerRole == "TENANT" -> {
                        val existingTenants = OfflineDiskCache.loadTenants(context)
                        val phoneKey = CrmCallManager.last10Digits(entity.phoneNumber)
                        val idx = if (phoneKey.length >= 10) {
                            existingTenants.indexOfFirst { CrmCallManager.last10Digits(it.contactNumber) == phoneKey }
                        } else -1
                        val noteLine = buildString {
                            val action = parsed.extractedTenantNote?.tenantAction.orEmpty()
                            if (action.isNotBlank()) append("[$action] ")
                            append(parsed.extractedTenantNote?.notes.orEmpty().ifBlank { parsed.formattedBulletsSummary() })
                        }.trim()
                        val updatedTenant = if (idx >= 0) {
                            val prev = existingTenants[idx]
                            prev.copy(
                                notes = listOf(prev.notes, noteLine).filter { it.isNotBlank() }.joinToString("\n"),
                                updatedAt = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.ENGLISH).format(Date())
                            )
                        } else {
                            OfflineDiskCache.TenantCacheRecord(
                                id = "TEN-${UUID.randomUUID().toString().take(8).uppercase(Locale.ENGLISH)}",
                                tenantName = entity.callerName.ifBlank { "Tenant ${entity.phoneNumber}" },
                                contactNumber = entity.phoneNumber,
                                notes = noteLine,
                                updatedAt = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.ENGLISH).format(Date()),
                                assignedTo = employeeName
                            )
                        }
                        val nextTenants = if (idx >= 0) {
                            existingTenants.toMutableList().apply { set(idx, updatedTenant) }
                        } else {
                            listOf(updatedTenant) + existingTenants
                        }
                        OfflineDiskCache.saveTenants(context, nextTenants)
                        linkedType = "Tenant"
                        linkedTitle = updatedTenant.tenantName
                        linkedId = updatedTenant.id
                        feedback = "Saved Tenant Note: ${updatedTenant.tenantName}"
                    }

                    else -> {
                        feedback = "Marked ${humanRoleLabel(entity.callerRole)} call reviewed"
                    }
                }

                // Mark call handled & linked locally
                CrmCallManager.markCallHandledAndLinked(
                    context = context,
                    callId = entity.callLogId,
                    fallbackCall = matchedCall,
                    oppType = linkedType,
                    oppTitle = linkedTitle,
                    oppId = linkedId,
                    notesOverride = parsed.formattedBulletsSummary().ifBlank { matchedCall.notes }
                )
                val updatedCallForSync = CrmCallManager.readCalls(context)
                    .firstOrNull { it.id == entity.callLogId }
                    ?: matchedCall

                // Mark Room analysis APPROVED and sync to Call Logs tab
                dao.updateCallAnalysisReviewStatus(entity.callLogId, "APPROVED")
                val approvedEntity = entity.copy(reviewStatus = "APPROVED")
                runCatching {
                    val repo = SheetsRepository(context.applicationContext, startForegroundSync = false)
                    if (repo.pushCallLogToMasterSheet(updatedCallForSync, analysisOverride = approvedEntity).isSuccess) {
                        dao.markCallAnalysisSynced(entity.callLogId)
                    }
                }
                feedback
            }

            approvingIds = approvingIds - entity.callLogId
            onCallsMutated()
            Toast.makeText(context, "$savedSummary • Stayed in AI Review queue", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("batch_call_review_queue_tab")
    ) {
        // ── 1. TOP CONTROL CARD: BATCH ANALYZE + MICROPHONE VOICE DICTATION + LIVE PROGRESS ──
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, BorderGrey),
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI Call Audio Review Queue",
                            color = TextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = buildString {
                                append("$eligibleWithAudioCount recording(s) ready")
                                if (eligibleCallIds.size > eligibleWithAudioCount) {
                                    append(" • ${eligibleCallIds.size} eligible")
                                }
                                if (skippedNoiseCount > 0) {
                                    append(" • $skippedNoiseCount noise/short skipped")
                                }
                            },
                            color = SecondaryText,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Surface(
                        color = BrandGold.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = "MR • HI • EN",
                            color = Color(0xFF92400E),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (eligibleCallIds.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    "No unreviewed calls (>=10s, non-spam) waiting for analysis.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                BatchCallAnalysisCoordinator.enqueueBatchAnalysis(context, eligibleCallIds)
                            }
                        },
                        enabled = !batchProgress.isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1.35f)
                            .heightIn(min = 48.dp)
                            .testTag("batch_analyze_unreviewed_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (batchProgress.isRunning) {
                                "Analyzing..."
                            } else {
                                "Analyze Unreviewed (${eligibleWithAudioCount.takeIf { it > 0 } ?: eligibleCallIds.size})"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (isRecordingMic) {
                                stopAndTranscribeMic()
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    startMicRecording()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        enabled = !isTranscribingMic,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isRecordingMic) ErrorRed.copy(alpha = 0.12f) else Color.Transparent,
                            contentColor = if (isRecordingMic) ErrorRed else ReviewClientColor
                        ),
                        border = BorderStroke(
                            1.2.dp,
                            if (isRecordingMic) ErrorRed else ReviewClientColor.copy(alpha = 0.55f)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("batch_dictate_mic_btn")
                    ) {
                        if (isTranscribingMic) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                                color = ReviewClientColor
                            )
                        } else {
                            Icon(
                                imageVector = if (isRecordingMic) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (isRecordingMic) "Stop recording and transcribe" else "Dictate voice note",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = when {
                                isTranscribingMic -> "Transcribing..."
                                isRecordingMic -> "Stop (${micElapsedSec}s)"
                                else -> "Dictate Note"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Live microphone status hint
                AnimatedVisibility(visible = !micStatusMessage.isNullOrBlank()) {
                    Text(
                        text = micStatusMessage.orEmpty(),
                        color = if (isRecordingMic) ErrorRed else ReviewClientColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Live Batch WorkManager Progress Bar
                AnimatedVisibility(visible = batchProgress.isRunning || batchProgress.total > 0 || !batchProgress.lastError.isNullOrBlank()) {
                    Surface(
                        color = SelectedSurfaceContainer,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("batch_analyze_progress_bar")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (batchProgress.isRunning) {
                                        "Analyzing ${batchProgress.completed} / ${batchProgress.total} calls..."
                                    } else if (batchProgress.total > 0) {
                                        "Completed ${batchProgress.succeeded} / ${batchProgress.total} calls"
                                    } else {
                                        batchProgress.currentCaller.ifBlank { "Batch Queue Status" }
                                    },
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (batchProgress.isRunning) {
                                    TextButton(
                                        onClick = { BatchCallAnalysisCoordinator.cancelBatchAnalysis(context) },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("batch_analyze_cancel_btn")
                                    ) {
                                        Text("Cancel", color = ErrorRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            if (batchProgress.isRunning && batchProgress.total > 0) {
                                LinearProgressIndicator(
                                    progress = {
                                        (batchProgress.completed.toFloat() / batchProgress.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    },
                                    color = PrimaryActionOrange,
                                    trackColor = BorderGrey,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                )
                            }
                            if (batchProgress.currentCaller.isNotBlank() && batchProgress.isRunning) {
                                Text(
                                    text = "Processing: ${batchProgress.currentCaller}",
                                    color = SecondaryText,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!batchProgress.lastError.isNullOrBlank()) {
                                Text(
                                    text = batchProgress.lastError.orEmpty(),
                                    color = ErrorRed,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 2. SIX FAST REVIEW FILTER TABS ──
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(AiReviewFilterTab.entries, key = { it.key }) { tab ->
                val count = tabCounts[tab] ?: 0
                val selected = selectedTab == tab
                FilterChip(
                    selected = selected,
                    onClick = { selectedTab = tab },
                    label = {
                        Text(
                            text = "${tab.label} ($count)",
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryActionOrange,
                        selectedLabelColor = Color.White,
                        containerColor = SurfaceWhite,
                        labelColor = TextPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = BorderGrey,
                        selectedBorderColor = PrimaryActionOrange
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ai_review_filter_${tab.key}")
                )
            }
        }

        // ── 3. REVIEW CARDS LIST ──
        if (filteredAnalyses.isEmpty()) {
            Surface(
                color = SurfaceWhite,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, BorderGrey),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = PrimaryActionOrange,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = if (analyses.isEmpty()) {
                            "No Analyzed Calls Yet"
                        } else {
                            "No Calls in ${selectedTab.label}"
                        },
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tap Analyze Unreviewed above to batch-transcribe Marathi, Hindi, and English call recordings, or tap Dictate Note to record a quick summary.",
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredAnalyses, key = { it.callLogId }) { entity ->
                    val parsed = remember(entity) { entity.toAnalysisResult() }
                    val matchedCall = callsById[entity.callLogId]
                    val matchedRecording = recordingOutcomesByCallId[entity.callLogId]?.matched
                    val isPlaying = activePlayingCallId == entity.callLogId
                    val isApproving = entity.callLogId in approvingIds

                    BatchReviewCallCard(
                        entity = entity,
                        analysis = parsed,
                        currentDeviceName = currentDeviceName,
                        hasLocalRecording = matchedRecording != null && matchedCall != null,
                        isPlaying = isPlaying,
                        isApproving = isApproving,
                        onTogglePlay = {
                            if (matchedCall != null && matchedRecording != null) {
                                onTogglePlayRecording(matchedCall, matchedRecording)
                            }
                        },
                        onApproveAndSave = {
                            approveAndSaveAnalysis(entity, parsed)
                        },
                        onEditFullScreen = {
                            val employeeName = UserSettingsManager(context).userProfile.value.username.ifBlank { "TLC Team" }
                            when {
                                (entity.callerRole == "CLIENT_LEAD" ||
                                    (entity.callerRole == "BROKER_CP" && parsed.extractedClientLead?.hasAnyField() == true)) &&
                                    onEditExtractedClient != null -> {
                                    val draftLead = buildDraftCrmLeadFromAnalysis(entity, parsed, employeeName)
                                    onEditExtractedClient(draftLead, matchedCall)
                                }
                                (entity.callerRole == "OWNER" ||
                                    entity.callerRole == "DEVELOPER_BUILDER" ||
                                    (entity.callerRole == "BROKER_CP" && parsed.extractedPropertyUpdate?.hasAnyField() == true)) &&
                                    onEditExtractedProperty != null -> {
                                    val draftProp = buildDraftPropertyFromAnalysis(entity, parsed, employeeName)
                                    onEditExtractedProperty(draftProp, matchedCall)
                                }
                                matchedCall != null && onOpenCallActionScreen != null -> {
                                    onOpenCallActionScreen(matchedCall)
                                }
                                else -> {
                                    Toast.makeText(context, "Open Call Logs tab to edit this entry.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onSendWhatsAppDraft = {
                            openWhatsAppDraft(context, entity.phoneNumber, entity.suggestedWhatsAppDraft)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchReviewCallCard(
    entity: CallAnalysisEntity,
    analysis: CallAudioAnalysisResult,
    currentDeviceName: String,
    hasLocalRecording: Boolean,
    isPlaying: Boolean,
    isApproving: Boolean,
    onTogglePlay: () -> Unit,
    onApproveAndSave: () -> Unit,
    onEditFullScreen: () -> Unit,
    onSendWhatsAppDraft: () -> Unit
) {
    val roleColor = roleBadgeColor(entity.callerRole)
    val isApproved = entity.reviewStatus.equals("APPROVED", ignoreCase = true)
    val specPills = remember(analysis) { analysis.buildSpecPills() }
    val displayCaller = remember(entity.callerName, entity.phoneNumber) {
        entity.callerName.takeIf {
            it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
        } ?: ContactUtils.formatDisplayPhone(entity.phoneNumber).ifBlank { entity.phoneNumber.ifBlank { "Voice Note" } }
    }
    val callDateLabel = remember(entity.callTimestamp) {
        runCatching {
            SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH).format(Date(entity.callTimestamp))
        }.getOrDefault("")
    }
    val analyzedOnOtherPhone = entity.deviceName.isNotBlank() &&
        !entity.deviceName.equals(currentDeviceName, ignoreCase = true)

    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (isApproved) 1.dp else 1.25.dp,
            color = if (isApproved) StatusSuccessGreen.copy(alpha = 0.55f) else BorderGrey
        ),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_review_card_${entity.callLogId}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Caller + Role Badge + Language Pill + Play toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = displayCaller,
                            color = TextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isApproved) {
                            Surface(
                                color = StatusSuccessGreen.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "APPROVED",
                                    color = StatusSuccessGreen,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = listOfNotNull(
                            ContactUtils.formatDisplayPhone(entity.phoneNumber)
                                .takeIf { it.isNotBlank() && it != displayCaller },
                            CrmCallManager.formatDuration(entity.durationSeconds).takeIf { entity.durationSeconds > 0 },
                            callDateLabel.takeIf { it.isNotBlank() }
                        ).joinToString(" • "),
                        color = SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (hasLocalRecording) {
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) PrimaryActionOrange else PrimaryActionOrange.copy(alpha = 0.12f))
                            .testTag("review_play_audio_${entity.callLogId}")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Stop call audio" else "Play call audio",
                            tint = if (isPlaying) Color.White else PrimaryActionOrange
                        )
                    }
                }
            }

            // Metadata Badges Row: Role + Language + Confidence + Needs Review + Other Phone
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = roleColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = humanRoleLabel(entity.callerRole),
                        color = roleColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }

                Surface(
                    color = BackgroundSoft,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, BorderGrey)
                ) {
                    Text(
                        text = entity.detectedLanguage.ifBlank { "Mixed" },
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Surface(
                    color = BackgroundSoft,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = "${entity.confidence}% conf",
                        color = SecondaryText,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                if (entity.needsReview) {
                    Surface(
                        color = ReviewAmberWarning.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = "Needs Review",
                            color = ReviewAmberWarning,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                if (analyzedOnOtherPhone) {
                    Surface(
                        color = ReviewClientColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = "Analyzed on ${entity.deviceName}",
                            color = ReviewClientColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // 2–3 bullet clean English summary
            val summaryBody = analysis.formattedBulletsSummary()
            if (summaryBody.isNotBlank()) {
                Text(
                    text = summaryBody,
                    color = TextPrimary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Extracted Spec Pills
            if (specPills.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    specPills.forEach { pill ->
                        Surface(
                            color = SelectedSurfaceContainer,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.25f))
                        ) {
                            Text(
                                text = pill,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Action Buttons Row: Approve & Save | Edit in Full Screen | Send WA Draft
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onApproveAndSave,
                    enabled = !isApproved && !isApproving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isApproved) StatusSuccessGreen.copy(alpha = 0.65f) else StatusSuccessGreen,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .heightIn(min = 48.dp)
                        .testTag("review_approve_save_${entity.callLogId}")
                ) {
                    if (isApproving) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (isApproved) "Saved" else "Approve & Save",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onEditFullScreen,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("review_edit_fullscreen_${entity.callLogId}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Edit",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                if (entity.suggestedWhatsAppDraft.isNotBlank()) {
                    Surface(
                        color = ReviewWaGreen,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onSendWhatsAppDraft)
                            .testTag("review_send_wa_${entity.callLogId}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Chat,
                                contentDescription = "Send WhatsApp draft",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "WA",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun roleBadgeColor(role: String): Color = when (role) {
    "CLIENT_LEAD" -> ReviewClientColor
    "OWNER", "DEVELOPER_BUILDER" -> ReviewOwnerColor
    "BROKER_CP" -> ReviewBrokerColor
    "TENANT" -> ReviewTenantColor
    else -> ReviewNonBizColor
}

private fun humanRoleLabel(role: String): String = when (role) {
    "CLIENT_LEAD" -> "Client Lead"
    "OWNER" -> "Property Owner"
    "DEVELOPER_BUILDER" -> "Developer / Builder"
    "BROKER_CP" -> "Broker / CP"
    "TENANT" -> "Tenant"
    "VENDOR" -> "Vendor"
    "PERSONAL_NON_BUSINESS" -> "Non-Business"
    else -> role
}

private fun openWhatsAppDraft(context: Context, phoneNumber: String, draftText: String) {
    val digits10 = CrmCallManager.last10Digits(phoneNumber)
    val encoded = Uri.encode(draftText)
    val url = if (digits10.length >= 10) {
        "https://wa.me/91$digits10?text=$encoded"
    } else {
        "https://wa.me/?text=$encoded"
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "WhatsApp is not installed on this device.", Toast.LENGTH_SHORT).show()
    }
}

private fun convertDdMmYyyyToIso(ddMmYyyy: String): String {
    val trimmed = ddMmYyyy.trim()
    if (trimmed.isBlank()) return ""
    return runCatching {
        val parsed = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false }.parse(trimmed)
        if (parsed != null) SimpleDateFormat("yyyy-MM-dd", Locale.US).format(parsed) else trimmed
    }.getOrDefault(trimmed)
}

internal fun buildDraftCrmLeadFromAnalysis(
    entity: CallAnalysisEntity,
    analysis: CallAudioAnalysisResult,
    employeeName: String
): CrmLead {
    val lead = analysis.extractedClientLead
    val division = lead?.division?.ifBlank { "Commercial" } ?: "Commercial"
    val tx = lead?.transactionType.orEmpty()
    val propType = lead?.propertyType.orEmpty()
    val config = lead?.configuration.orEmpty()
    val reqLabel = listOf(config, propType, tx).filter { it.isNotBlank() }.joinToString(" • ")
    val location = listOf(lead?.microMarket.orEmpty(), lead?.location.orEmpty())
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(", ")
    val budget = listOf(lead?.budgetMin.orEmpty(), lead?.budgetMax.orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(" - ")
    val nowStamp = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.ENGLISH).format(Date())
    val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val displayName = entity.callerName.takeIf {
        it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
    } ?: ContactUtils.formatDisplayPhone(entity.phoneNumber).ifBlank { "New Lead" }

    return CrmLead(
        id = "CRM-${UUID.randomUUID().toString().take(8).uppercase(Locale.ENGLISH)}",
        name = displayName,
        phone = entity.phoneNumber,
        segment = division,
        requirement = reqLabel,
        location = location,
        budget = budget,
        status = "New Lead",
        nextFollowUp = convertDdMmYyyyToIso(entity.followUpDate),
        notes = analysis.formattedBulletsSummary(),
        assignedTo = employeeName,
        followUpTime = entity.followUpTime,
        updatedBy = employeeName,
        updatedAt = nowStamp,
        carpetAreaRange = lead?.carpetAreaSqFt.orEmpty(),
        nextFollowUpTime = entity.followUpTime,
        nextFollowUpAction = entity.followUpNextAction.ifBlank { "Call Client" },
        areaSqFt = lead?.carpetAreaSqFt.orEmpty(),
        possessionTimeline = lead?.possession.orEmpty(),
        createdDate = todayIso,
        clientType = if (entity.callerRole == "BROKER_CP") "Broker / Channel Partner" else "Direct Client",
        requirementType = tx,
        propertyType = propType,
        minCarpetSqFt = lead?.carpetAreaSqFt.orEmpty(),
        minBudgetInr = lead?.budgetMin.orEmpty(),
        maxBudgetInr = lead?.budgetMax.orEmpty(),
        furnishing = lead?.furnishing.orEmpty(),
        leadSource = if (entity.callerRole == "BROKER_CP") "Broker Call" else "Incoming Call AI",
        clientNotes = analysis.formattedBulletsSummary(),
        createdBy = employeeName
    )
}

internal fun buildDraftPropertyFromAnalysis(
    entity: CallAnalysisEntity,
    analysis: CallAudioAnalysisResult,
    employeeName: String
): PropertyItem {
    val prop = analysis.extractedPropertyUpdate
    val assetType = prop?.assetType.orEmpty()
    val isResi = assetType.contains("BHK", ignoreCase = true) ||
        assetType.contains("Flat", ignoreCase = true) ||
        assetType.contains("Villa", ignoreCase = true) ||
        assetType.contains("Resi", ignoreCase = true)
    val isShop = assetType.contains("Shop", ignoreCase = true) ||
        assetType.contains("Showroom", ignoreCase = true) ||
        assetType.contains("Retail", ignoreCase = true)
    val isSale = prop?.rentOrPrice.orEmpty().contains("Cr", ignoreCase = true) ||
        prop?.actionType.equals("BUILDER_SLAB_UPDATE", ignoreCase = true)
    val tab = when {
        isResi && isSale -> InventoryTab.RESI_RESALE
        isResi -> InventoryTab.RESI_RENT
        isShop && isSale -> InventoryTab.SHOP_OUT
        isShop -> InventoryTab.SHOP_LEASE
        isSale -> InventoryTab.OFF_OUT
        else -> InventoryTab.OFF_LEASE
    }
    val status = when {
        prop?.statusChange.orEmpty().contains("Sold", true) -> AvailabilityStatus.SOLD
        prop?.statusChange.orEmpty().contains("Leased", true) -> AvailabilityStatus.LEASED
        prop?.statusChange.orEmpty().contains("Negotiation", true) -> AvailabilityStatus.UNDER_NEGOTIATION
        prop?.statusChange.orEmpty().contains("Available", true) -> AvailabilityStatus.AVAILABLE
        else -> AvailabilityStatus.NOT_CONFIRMED
    }
    val carpet = prop?.carpetAreaSqFt.orEmpty().replace(",", "")
        .let { Regex("\\d+(?:\\.\\d+)?").find(it)?.value?.toDoubleOrNull() ?: 0.0 }
    val builtUp = prop?.builtUpAreaSqFt.orEmpty().replace(",", "")
        .let { Regex("\\d+(?:\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }
    val priceNumeric = prop?.rentOrPrice.orEmpty().replace(",", "")
        .let { Regex("\\d+(?:\\.\\d+)?").find(it)?.value?.toDoubleOrNull() ?: 0.0 }
    val isBroker = entity.callerRole == "BROKER_CP"
    val callerClean = entity.callerName.takeIf {
        it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
    }.orEmpty()
    val extraRemarks = buildList {
        val bullets = analysis.formattedBulletsSummary()
        if (bullets.isNotBlank()) add(bullets)
        if (!prop?.attachedPantry.isNullOrBlank()) add("Pantry: ${prop?.attachedPantry}")
        if (!prop?.attachedWashroom.isNullOrBlank()) add("Washroom: ${prop?.attachedWashroom}")
        if (!prop?.builderOfferOrBrokerageNote.isNullOrBlank()) add("Offer/Brokerage: ${prop?.builderOfferOrBrokerageNote}")
    }.joinToString("\n")
    val todayDdMmYyyy = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())
    val nowStamp = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())

    return PropertyItem(
        id = "PROP-AI-${UUID.randomUUID().toString().take(8).uppercase(Locale.ENGLISH)}",
        propertyCode = "",
        date = todayDdMmYyyy,
        location = listOf(prop?.microMarket.orEmpty(), prop?.location.orEmpty())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", "),
        projectName = prop?.projectName.orEmpty(),
        unitNumber = if (isBroker) "" else prop?.unitNumber.orEmpty(),
        propertyType = if (isResi) "Residential" else "Commercial",
        tab = tab,
        carpetAreaSqFt = carpet,
        builtUpAreaSqFt = builtUp,
        priceOrRent = priceNumeric,
        priceSale = if (isSale && priceNumeric > 0) priceNumeric else null,
        rent = if (!isSale && priceNumeric > 0) priceNumeric else null,
        furnishing = FurnishingType.UNFURNISHED,
        status = status,
        ownerName = if (!isBroker) callerClean.ifBlank { null } else null,
        ownerContact = if (!isBroker) entity.phoneNumber.ifBlank { null } else null,
        brokerName = if (isBroker) callerClean.ifBlank { null } else null,
        brokerContact = if (isBroker) entity.phoneNumber.ifBlank { null } else null,
        sourceInfo = if (isBroker) "Broker Call AI" else "Owner Call AI",
        remarks = extraRemarks.ifBlank { null },
        isSyncedToSheet = false,
        portfolio = if (isResi) "Residential" else "Commercial",
        transactionType = if (isSale) "Sale" else "Lease",
        verificationStatus = VerificationStatus.PENDING,
        syncUpdatedAt = nowStamp,
        updatedBy = employeeName,
        updatedAt = nowStamp
    )
}
