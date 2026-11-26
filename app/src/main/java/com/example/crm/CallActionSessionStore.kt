package com.example.crm

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Holds the active Call Log handoff context when the user routes from `CallLogActionScreen`
 * into a full destination form (`Add Client`, `Add Property`, `Tenants`, `Vendors`, or `Projects`).
 *
 * Powers two seamless capabilities:
 * 1. **Auto-Link on Save**: Destination Save callbacks call `CallActionSessionManager.completeAndAutoLink(...)`
 *    to link the new/updated record back to `callId` and mark the call Handled automatically.
 * 2. **Pinned Mini Audio Player Bar**: Destination forms display `PinnedCallMiniAudioBar` at the top so
 *    the user can listen to the call recording while typing carpet area, budget, or unit number.
 */
data class ActiveCallActionContext(
    val callId: String,
    val callRecord: CrmCallRecord,
    val callerName: String,
    val callerPhone: String,
    val durationSeconds: Int,
    val aiSummaryNote: String = "",
    val matchedRecording: MatchedCallRecording? = null,
    val targetRoleLabel: String = "Client"
)

object CallActionSessionManager {
    private val _activeSession = MutableStateFlow<ActiveCallActionContext?>(null)
    val activeSession: StateFlow<ActiveCallActionContext?> = _activeSession.asStateFlow()

    fun beginSession(session: ActiveCallActionContext) {
        _activeSession.value = session
    }

    fun updateMatchedRecording(callId: String, recording: MatchedCallRecording?) {
        val current = _activeSession.value ?: return
        if (current.callId == callId) {
            _activeSession.value = current.copy(matchedRecording = recording)
        }
    }

    fun updateAiSummary(callId: String, summary: String) {
        val current = _activeSession.value ?: return
        if (current.callId == callId) {
            _activeSession.value = current.copy(aiSummaryNote = summary.trim())
        }
    }

    /**
     * Links the active Call Log to the newly saved Lead / Property / Tenant / Broker / Project,
     * marks the call as Handled, triggers sheet sync, and clears the handoff session.
     */
    fun completeAndAutoLink(
        context: Context,
        entityType: String,
        entityTitle: String,
        entityId: String,
        onCompleted: (() -> Unit)? = null
    ): Boolean {
        val session = _activeSession.value ?: return false
        val linked = CrmCallManager.markCallHandledAndLinked(
            context = context,
            callId = session.callId,
            fallbackCall = session.callRecord,
            oppType = entityType.ifBlank { session.targetRoleLabel },
            oppTitle = entityTitle.ifBlank { session.callerName },
            oppId = entityId,
            notesOverride = session.aiSummaryNote
        )
        _activeSession.value = null
        if (linked) {
            onCompleted?.invoke()
        }
        return linked
    }

    fun clearSession() {
        _activeSession.value = null
    }
}

/**
 * Slim pinned audio strip displayed at the top of destination forms (`Add Client`, `Add Property`,
 * `Tenants & Partners`) when launched from `CallLogActionScreen`, allowing the user to play/pause
 * the call recording and glance at the AI summary without leaving the form.
 */
@Composable
fun PinnedCallMiniAudioBar(
    session: ActiveCallActionContext?,
    modifier: Modifier = Modifier,
    onDismissBar: (() -> Unit)? = null
) {
    if (session == null) return

    val context = LocalContext.current
    var resolvedRecording by remember(session.callId, session.matchedRecording?.uriOrPath) {
        mutableStateOf(session.matchedRecording)
    }
    var isResolving by remember(session.callId) { mutableStateOf(session.matchedRecording == null) }
    var isPlaying by remember(session.callId) { mutableStateOf(false) }
    var elapsedSec by remember(session.callId) { mutableIntStateOf(0) }
    var totalDurationSec by remember(session.callId) {
        mutableIntStateOf(
            session.matchedRecording?.durationMs?.takeIf { it > 0L }?.let { (it / 1000L).toInt() }
                ?: session.durationSeconds.coerceAtLeast(0)
        )
    }
    var showSummaryPeek by remember(session.callId) { mutableStateOf(false) }
    var mediaPlayer by remember(session.callId) { mutableStateOf<MediaPlayer?>(null) }

    fun releasePlayer() {
        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        mediaPlayer = null
        isPlaying = false
    }

    DisposableEffect(session.callId) {
        onDispose { releasePlayer() }
    }

    LaunchedEffect(session.callId) {
        if (resolvedRecording == null) {
            isResolving = true
            val match = withContext(Dispatchers.IO) {
                runCatching {
                    CallRecordingMatcher.matchRecordingForCallOnDemand(context, session.callRecord)
                }.getOrNull()
            }
            if (match != null) {
                resolvedRecording = match
                if (match.durationMs > 0L) totalDurationSec = (match.durationMs / 1000L).toInt()
                CallActionSessionManager.updateMatchedRecording(session.callId, match)
            }
            isResolving = false
        }
    }

    LaunchedEffect(isPlaying, mediaPlayer) {
        while (isPlaying) {
            val mp = mediaPlayer
            if (mp != null) {
                runCatching {
                    elapsedSec = (mp.currentPosition / 1000).coerceAtLeast(0)
                    val dur = (mp.duration / 1000).coerceAtLeast(0)
                    if (dur > 0) totalDurationSec = dur
                }
            }
            delay(500)
        }
    }

    val durationText = CrmCallManager.formatDuration(totalDurationSec)
    val elapsedText = CrmCallManager.formatDuration(elapsedSec)
    val displayCaller = session.callerName.ifBlank {
        ContactUtils.formatDisplayPhone(session.callerPhone).ifBlank { session.callerPhone }
    }

    Surface(
        color = PrimaryActionOrange.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.32f)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("pinned_call_mini_audio_bar")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Play / Pause circular trigger
                val activeRec = resolvedRecording
                if (activeRec != null) {
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                runCatching { mediaPlayer?.pause() }
                                isPlaying = false
                            } else {
                                val existing = mediaPlayer
                                if (existing != null) {
                                    runCatching {
                                        existing.start()
                                        isPlaying = true
                                    }.onFailure {
                                        releasePlayer()
                                    }
                                } else {
                                    runCatching {
                                        val mp = MediaPlayer()
                                        if (activeRec.isContentUri) {
                                            mp.setDataSource(context, android.net.Uri.parse(activeRec.uriOrPath))
                                        } else {
                                            mp.setDataSource(activeRec.uriOrPath)
                                        }
                                        mp.setOnCompletionListener {
                                            isPlaying = false
                                            elapsedSec = 0
                                        }
                                        mp.prepare()
                                        mp.start()
                                        mediaPlayer = mp
                                        val dur = (mp.duration / 1000).coerceAtLeast(0)
                                        if (dur > 0) totalDurationSec = dur
                                        isPlaying = true
                                    }.onFailure {
                                        releasePlayer()
                                        Toast.makeText(
                                            context,
                                            "Unable to play ${activeRec.fileName}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(PrimaryActionOrange)
                            .testTag("mini_audio_play_pause_btn")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause call audio" else "Play call audio",
                            tint = OnPrimaryActionColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(PrimaryActionOrange.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Active Call Context",
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    val statusHeadline = when {
                        isPlaying -> "Playing call $elapsedText / $durationText"
                        activeRec != null -> "Play call $durationText • $displayCaller"
                        isResolving -> "Checking call audio • $displayCaller"
                        else -> "Auto-linking call ($durationText) • $displayCaller"
                    }
                    Text(
                        text = statusHeadline,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (activeRec != null) {
                            "Saves & auto-links call to ${session.targetRoleLabel}"
                        } else {
                            "No local recording • Will auto-link on Save"
                        },
                        fontSize = 10.5.sp,
                        color = SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (session.aiSummaryNote.isNotBlank()) {
                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clickable { showSummaryPeek = !showSummaryPeek }
                            .testTag("mini_audio_ai_notes_toggle")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Toggle AI call notes",
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (showSummaryPeek) "Hide" else "Notes",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryActionOrange
                            )
                        }
                    }
                }

                if (onDismissBar != null) {
                    IconButton(
                        onClick = {
                            releasePlayer()
                            onDismissBar()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("mini_audio_dismiss_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss call audio bar",
                            tint = SecondaryText,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = showSummaryPeek && session.aiSummaryNote.isNotBlank()) {
                Surface(
                    color = SurfaceWhite.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = session.aiSummaryNote,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
