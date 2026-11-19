package com.example.crm

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.ai.GeminiRealEstateService
import com.example.data.UserSettingsManager
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class CallRecordingCandidate(
    val fileName: String,
    val uriOrPath: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long = 0L,
    val mimeType: String = "audio/mp4",
    val isContentUri: Boolean = false
)

data class MatchedCallRecording(
    val fileName: String,
    val uriOrPath: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long = 0L,
    val mimeType: String = "audio/mp4",
    val isContentUri: Boolean = false,
    val matchReason: String = "Matched by caller & timestamp"
)

/**
 * Batch 20: Strictly ON-DEMAND Call Recording Matcher & Player.
 * - NEVER scans audio files on app startup (zero startup lag).
 * - Scans ONLY when viewing/expanding a Call Card or tapping Play / Summarize.
 * - Supports custom SAF Tree URI (`ActivityResultContracts.OpenDocumentTree`) + auto-detected OEM folders:
 *   1. Samsung: `Recordings/Call`
 *   2. Xiaomi / MIUI: `MIUI/sound_recorder/call_rec`
 *   3. OnePlus / Oppo / Realme: `Music/Recordings/Call Recordings`
 */
object CallRecordingMatcher {

    val SUPPORTED_EXTENSIONS = setOf("m4a", "mp3", "amr", "wav", "aac", "ogg", "3gp")
    const val TIMESTAMP_TOLERANCE_MS = 2 * 60 * 1000L // ±2 minutes (120,000 ms)

    val OEM_DEFAULT_RELATIVE_PATHS = listOf(
        "Recordings/Call" to "Samsung (Recordings/Call)",
        "MIUI/sound_recorder/call_rec" to "Xiaomi (MIUI/sound_recorder/call_rec)",
        "Music/Recordings/Call Recordings" to "OnePlus/Oppo (Music/Recordings/Call Recordings)",
        "Recordings/Call Recordings" to "Android Call Recordings (Recordings/Call Recordings)"
    )

    fun autoDetectOemRecordingFolder(context: Context? = null): Pair<String, String> {
        val roots = buildList {
            runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { add(it) }
            add(File("/storage/emulated/0"))
            add(File("/sdcard"))
            context?.filesDir?.let { add(File(it, "call_recordings")) }
        }.distinctBy { it.absolutePath }

        for (root in roots) {
            for ((relPath, label) in OEM_DEFAULT_RELATIVE_PATHS) {
                val candidateDir = File(root, relPath)
                if (candidateDir.exists() && candidateDir.isDirectory) {
                    return candidateDir.absolutePath to label
                }
            }
        }
        val fallbackSamsung = File("/storage/emulated/0/Recordings/Call")
        return fallbackSamsung.absolutePath to "Samsung (Recordings/Call)"
    }

    fun inferMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mp3" -> "audio/mpeg"
            "amr" -> "audio/amr"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            else -> "audio/mp4"
        }
    }

    /**
     * Pure matching algorithm (testable in JVM / Robolectric unit tests):
     * Matches a call against candidate audio files (.m4a, .mp3, .amr) by:
     * 1. Filename containing caller's 10-digit number (or last 7+ digits) or caller name token, OR
     * 2. File `lastModified` timestamp within ±2 minutes (120,000 ms) of the call end time
     *    (`call.timestamp + call.durationSeconds * 1000L`, or `call.timestamp` if recorded at end).
     */
    fun matchRecordingFromCandidates(
        call: CrmCallRecord,
        candidates: List<CallRecordingCandidate>
    ): MatchedCallRecording? {
        val validAudio = candidates.filter { cand ->
            val ext = cand.fileName.substringAfterLast('.', "").lowercase(Locale.US)
            ext in SUPPORTED_EXTENSIONS
        }
        if (validAudio.isEmpty()) return null

        val callStartMs = call.timestamp
        val callEndMs = call.timestamp + (call.durationSeconds.coerceAtLeast(0) * 1000L)
        val phone10 = CrmCallManager.last10Digits(call.phoneNumber)
        val callerTokens = call.callerName
            .lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in setOf("unknown", "caller", "client", "owner", "broker", "the") }

        var bestMatch: MatchedCallRecording? = null
        var bestScore = -1
        var bestDeltaMs = Long.MAX_VALUE

        for (cand in validAudio) {
            val lowerName = cand.fileName.lowercase(Locale.US)
            val fileDigits = cand.fileName.filter { it.isDigit() }
            val hasPhoneMatch = phone10.length >= 7 && (
                lowerName.contains(phone10) ||
                    fileDigits.contains(phone10) ||
                    (phone10.length == 10 && fileDigits.contains(phone10.takeLast(8)))
                )
            val hasNameMatch = callerTokens.isNotEmpty() && callerTokens.any { token -> lowerName.contains(token) }

            val deltaFromEnd = abs(cand.lastModifiedMs - callEndMs)
            val deltaFromStart = abs(cand.lastModifiedMs - callStartMs)
            val minDelta = minOf(deltaFromEnd, deltaFromStart)
            val withinTwoMinutes = minDelta <= TIMESTAMP_TOLERANCE_MS

            if (!hasPhoneMatch && !hasNameMatch && !withinTwoMinutes) continue

            val score = (if (hasPhoneMatch) 4 else 0) +
                (if (hasNameMatch) 2 else 0) +
                (if (withinTwoMinutes) 3 else 0)

            val reason = when {
                hasPhoneMatch && withinTwoMinutes -> "Matched caller number & ±2m timestamp"
                hasPhoneMatch -> "Matched caller number in filename"
                hasNameMatch && withinTwoMinutes -> "Matched caller name & ±2m timestamp"
                withinTwoMinutes -> "Matched call end time (±2 min)"
                else -> "Matched caller name in filename"
            }

            if (score > bestScore || (score == bestScore && minDelta < bestDeltaMs)) {
                bestScore = score
                bestDeltaMs = minDelta
                bestMatch = MatchedCallRecording(
                    fileName = cand.fileName,
                    uriOrPath = cand.uriOrPath,
                    lastModifiedMs = cand.lastModifiedMs,
                    sizeBytes = cand.sizeBytes,
                    mimeType = cand.mimeType.ifBlank { inferMimeType(cand.fileName) },
                    isContentUri = cand.isContentUri,
                    matchReason = reason
                )
            }
        }
        return bestMatch
    }

    /**
     * Strictly ON-DEMAND folder scan + match for a specific call.
     * Never invoked on app launch; only called when a user views/expands a call card or opens PostCallSmartSheet.
     */
    suspend fun matchRecordingForCallOnDemand(
        context: Context,
        call: CrmCallRecord
    ): MatchedCallRecording? = withContext(Dispatchers.IO) {
        val settings = UserSettingsManager(context)
        val configuredUriOrPath = settings.callRecordingFolderUri.value.trim()
        val candidates = mutableListOf<CallRecordingCandidate>()

        // 1. Check custom SAF Tree URI or file path if configured
        if (configuredUriOrPath.startsWith("content://")) {
            candidates.addAll(querySafTreeCandidates(context, Uri.parse(configuredUriOrPath)))
        } else if (configuredUriOrPath.isNotBlank()) {
            candidates.addAll(scanDirectoryCandidates(File(configuredUriOrPath)))
        }

        // 2. Also check standard OEM folders & app-local call_recordings folder on-demand
        val roots = buildList {
            runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { add(it) }
            add(File("/storage/emulated/0"))
            add(File(context.filesDir, "call_recordings"))
        }.distinctBy { it.absolutePath }

        for (root in roots) {
            if (root.name == "call_recordings") {
                candidates.addAll(scanDirectoryCandidates(root))
            } else {
                for ((relPath, _) in OEM_DEFAULT_RELATIVE_PATHS) {
                    candidates.addAll(scanDirectoryCandidates(File(root, relPath)))
                }
            }
        }

        matchRecordingFromCandidates(call, candidates.distinctBy { it.uriOrPath })
    }

    private fun scanDirectoryCandidates(dir: File): List<CallRecordingCandidate> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile }
            .filter { it.extension.lowercase(Locale.US) in SUPPORTED_EXTENSIONS }
            .take(150)
            .map { f ->
                CallRecordingCandidate(
                    fileName = f.name,
                    uriOrPath = f.absolutePath,
                    lastModifiedMs = f.lastModified(),
                    sizeBytes = f.length(),
                    mimeType = inferMimeType(f.name),
                    isContentUri = false
                )
            }
            .toList()
    }

    private fun querySafTreeCandidates(context: Context, treeUri: Uri): List<CallRecordingCandidate> {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val out = mutableListOf<CallRecordingCandidate>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (out.size < 150 && cursor.moveToNext()) {
                    val childDocId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                    if (ext !in SUPPORTED_EXTENSIONS) continue
                    val lastMod = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else inferMimeType(name)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                    out.add(
                        CallRecordingCandidate(
                            fileName = name,
                            uriOrPath = docUri.toString(),
                            lastModifiedMs = lastMod,
                            sizeBytes = size,
                            mimeType = mime.ifBlank { inferMimeType(name) },
                            isContentUri = true
                        )
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    fun readAudioBytesSafely(context: Context, recording: MatchedCallRecording): ByteArray? {
        return runCatching {
            if (recording.isContentUri || recording.uriOrPath.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(recording.uriOrPath))?.use { it.readBytes() }
            } else {
                val f = File(recording.uriOrPath)
                if (f.exists() && f.length() <= 14 * 1024 * 1024) f.readBytes() else null
            }
        }.getOrNull()
    }
}

/**
 * Batch 20: Reusable "Last Called By" Banner displayed inside PropertyDetailScreen,
 * CrmClientProfile, and TenantDetailScreen / TenantCard so team members never double-call
 * the same Owner, Client, or Tenant.
 */
@Composable
fun LastCalledByBanner(
    lastCall: CrmCallRecord?,
    modifier: Modifier = Modifier,
    testTag: String = "last_called_by_banner"
) {
    if (lastCall == null) return
    val bannerText = remember(lastCall) {
        CrmCallManager.formatLastCalledByBannerText(lastCall)
    }
    Surface(
        color = Color(0xFFFFF7ED),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhoneCallback,
                contentDescription = "Last Called By",
                tint = PrimaryActionOrange,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = bannerText,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Batch 20: On-Demand Call Recording Match ("▶ Play" audio bar & "✨ Summarize" button).
 * Runs folder lookup ONLY when this composable enters composition (viewing/expanding a Call Card
 * or inside PostCallSmartSheet)—never on app startup.
 * Gemini Flash runs ONLY when the user explicitly taps "✨ Summarize".
 */
@Composable
fun CallRecordingOnDemandSection(
    call: CrmCallRecord,
    currentNotes: String,
    onSummaryGenerated: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "call_recording"
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val geminiService = remember { GeminiRealEstateService() }

    var isCheckingRecording by remember(call.id) { mutableStateOf(true) }
    var matchedRecording by remember(call.id) { mutableStateOf<MatchedCallRecording?>(null) }
    var isPlaying by remember(call.id) { mutableStateOf(false) }
    var isSummarizing by remember(call.id) { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(call.id) {
        onDispose {
            runCatching {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            }
            mediaPlayer = null
        }
    }

    LaunchedEffect(call.id, call.phoneNumber, call.timestamp) {
        isCheckingRecording = true
        matchedRecording = CallRecordingMatcher.matchRecordingForCallOnDemand(context, call)
        isCheckingRecording = false
    }

    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderGrey),
        modifier = modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_bar")
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = PrimaryActionOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isCheckingRecording -> "Checking call recording folder..."
                                matchedRecording != null -> matchedRecording!!.fileName
                                else -> "On-Demand Call Recording & AI Summary"
                            },
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = matchedRecording?.matchReason
                                ?: "Matches .m4a/.mp3/.amr by caller or ±2m timestamp",
                            fontSize = 10.sp,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ▶ Play / ⏸ Pause Button
                OutlinedButton(
                    onClick = {
                        if (isPlaying) {
                            runCatching {
                                mediaPlayer?.pause()
                            }
                            isPlaying = false
                        } else {
                            coroutineScope.launch {
                                val rec = matchedRecording ?: CallRecordingMatcher.matchRecordingForCallOnDemand(context, call)
                                matchedRecording = rec
                                if (rec == null) {
                                    Toast.makeText(
                                        context,
                                        "No matching .m4a/.mp3/.amr recording found within ±2 min. Check Call Recording Folder in Settings.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    runCatching {
                                        mediaPlayer?.release()
                                        val mp = MediaPlayer()
                                        if (rec.isContentUri || rec.uriOrPath.startsWith("content://")) {
                                            mp.setDataSource(context, Uri.parse(rec.uriOrPath))
                                        } else {
                                            mp.setDataSource(rec.uriOrPath)
                                        }
                                        mp.setOnCompletionListener {
                                            isPlaying = false
                                        }
                                        mp.prepare()
                                        mp.start()
                                        mediaPlayer = mp
                                        isPlaying = true
                                    }.onFailure {
                                        isPlaying = false
                                        Toast.makeText(context, "Playing ${rec.fileName}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .testTag("${testTagPrefix}_play_btn")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause Recording" else "Play Recording",
                        tint = PrimaryActionOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isPlaying) "⏸ Pause" else "▶ Play (${CrmCallManager.formatDuration(call.durationSeconds)})",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // ✨ Summarize Button (Strictly On-Demand Gemini Flash)
                Button(
                    enabled = !isSummarizing,
                    onClick = {
                        isSummarizing = true
                        coroutineScope.launch {
                            val rec = matchedRecording ?: CallRecordingMatcher.matchRecordingForCallOnDemand(context, call)
                            matchedRecording = rec
                            val audioBytes = rec?.let { CallRecordingMatcher.readAudioBytesSafely(context, it) }
                            val summary = geminiService.summarizeCallRecordingOnDemand(
                                callerName = call.callerName,
                                callerPhone = call.phoneNumber,
                                durationSeconds = call.durationSeconds,
                                existingNotes = currentNotes.ifBlank { call.notes },
                                recordingFileName = rec?.fileName ?: "call_${CrmCallManager.last10Digits(call.phoneNumber)}.m4a",
                                audioBytes = audioBytes,
                                audioMimeType = rec?.mimeType ?: "audio/mp4"
                            )
                            isSummarizing = false
                            onSummaryGenerated(summary)
                            Toast.makeText(context, "✨ Call summary written to Call Note", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .testTag("${testTagPrefix}_summarize_btn")
                ) {
                    if (isSummarizing) {
                        CircularProgressIndicator(
                            color = OnPrimaryActionColor,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Summarizing...", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    } else {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Summarize Call Recording",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "✨ Summarize",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Batch 20: Top-level PendingCallNavigation state stored at MainScaffold / NavHost
 * so tapping "Add More Details →" inside PostCallSmartSheet saves the Call Log,
 * closes the ModalBottomSheet cleanly, and triggers navigation via
 * LaunchedEffect(pendingCallNavigation) to the pre-filled target screen.
 */
data class PendingCallNavigation(
    val targetRoute: String,
    val prefillName: String,
    val prefillPhone: String,
    val prefillNote: String,
    val taggedPropertyId: String? = null,
    val role: PostCallRole = PostCallRole.CLIENT_LEAD,
    val subRole: String = ""
) {
    companion object {
        const val ROUTE_ADD_EDIT_LEAD = "CRM_WORKSPACE"
        const val ROUTE_ADD_EDIT_PROPERTY = "ADD_PROPERTY"
        const val ROUTE_ADD_EDIT_PROJECT = "PROJECT_DIRECTORY"
        const val ROUTE_ADD_EDIT_TENANT = "TENANTS_LEASES"
        const val ROUTE_OPS_VENDOR = "OPS_VENDOR"

        fun fromPostCallAction(
            role: PostCallRole,
            call: CrmCallRecord,
            prefilledName: String,
            prefilledNotes: String,
            linkedEntityId: String?,
            subRole: String = ""
        ): PendingCallNavigation {
            val route = when (role) {
                PostCallRole.CLIENT_LEAD, PostCallRole.FRESH_UNKNOWN -> ROUTE_ADD_EDIT_LEAD
                PostCallRole.PROPERTY_OWNER -> ROUTE_ADD_EDIT_PROPERTY
                PostCallRole.DEVELOPER_POC -> ROUTE_ADD_EDIT_PROJECT
                PostCallRole.TENANT -> ROUTE_ADD_EDIT_TENANT
                PostCallRole.BROKER, PostCallRole.OPS_VENDOR -> ROUTE_OPS_VENDOR
            }
            val normalizedPhone = ContactUtils.normalizePhoneNumber(call.phoneNumber).ifBlank { call.phoneNumber }
            val resolvedName = prefilledName.trim().ifBlank {
                call.callerName.takeIf {
                    it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
                } ?: normalizedPhone
            }
            return PendingCallNavigation(
                targetRoute = route,
                prefillName = resolvedName,
                prefillPhone = normalizedPhone,
                prefillNote = prefilledNotes.trim().ifBlank { call.notes.trim() },
                taggedPropertyId = linkedEntityId?.takeIf { it.isNotBlank() },
                role = role,
                subRole = subRole
            )
        }
    }
}

