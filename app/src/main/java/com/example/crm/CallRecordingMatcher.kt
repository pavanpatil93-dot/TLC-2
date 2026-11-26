package com.example.crm

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.core.content.ContextCompat
import com.example.ai.GeminiRealEstateService
import com.example.data.UserSettingsManager
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

data class CallRecordingCandidate(
    val fileName: String,
    val uriOrPath: String,
    val lastModifiedMs: Long,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String = "audio/mp4",
    val isContentUri: Boolean = false,
    val relativePath: String = ""
)

data class ParsedRecordingMetadata(
    val label: String,
    val fileStartMillis: Long?,
    val filePhoneLast10: String?,
    val isSuspectedSpam: Boolean,
    val hasPhoneDigits: Boolean,
    val meaningfulNameTokens: Set<String> = emptySet(),
    val isSamsungPattern: Boolean = false
) {
    val hasCallerIdentity: Boolean
        get() = filePhoneLast10 != null || meaningfulNameTokens.isNotEmpty()
}

data class MatchedCallRecording(
    val fileName: String,
    val uriOrPath: String,
    val lastModifiedMs: Long,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String = "audio/mp4",
    val isContentUri: Boolean = false,
    val isManualPick: Boolean = false,
    val matchReason: String = "Exact 1-to-1 match"
)

enum class CallRecordingMatchStatus {
    MATCHED,
    AMBIGUOUS,
    NO_MATCH,
    SKIPPED_ZERO_CALL
}

data class CallRecordingMatchOutcome(
    val status: CallRecordingMatchStatus,
    val matched: MatchedCallRecording? = null,
    val ambiguousCandidates: List<MatchedCallRecording> = emptyList(),
    val message: String = ""
)

/**
 * Strict 1-to-1 Call Recording Matcher & On-Demand Player.
 * - Searches the entire linked folder (MediaStore, SAF tree, and OEM directories) off the UI thread with no file-count cutoff.
 * - Parses Samsung trailing `_yyMMdd_HHmmss` (plus Xiaomi/OnePlus/OEM timestamps) as local recording start time,
 *   using it ahead of file modification time and keeping date/time digits strictly out of phone comparison.
 * - Requires time evidence for every automatic match (2-minute start-to-start and end-to-end tolerance for confirmed
 *   caller identity; tight 15s/10s for unlabelled/Suspected Spam recordings).
 * - Matches meaningful whole name tokens (ignoring generic labels such as Bld, CP, TLC, Owner, Broker) or normalized
 *   10-digit phone numbers, and hard-rejects conflicting caller names or phone numbers even at identical timestamps.
 * - Lazily reads audio duration ONLY for shortlisted plausible candidates, distinguishes close calls by duration,
 *   never auto-matches missed/0-second calls, and surfaces ambiguous matches so the user can choose explicitly.
 */
object CallRecordingMatcher {

    val SUPPORTED_EXTENSIONS = setOf("m4a", "mp3", "amr", "wav", "aac", "ogg", "3gp")

    /** Two-minute timing tolerance for start-to-start and end-to-end alignment on timestamped recordings. */
    const val TIME_TOLERANCE_MS = 120_000L
    const val PRIMARY_START_TOLERANCE_MS = TIME_TOLERANCE_MS

    /** Tighter fallback end-time window when filename has no embedded timestamp (non-Samsung OEMs). */
    const val FALLBACK_END_TOLERANCE_MS = 30_000L

    /** Allowed difference between audio file duration and CallLog talk duration. */
    const val MAX_DURATION_DIFF_SEC = 15L

    /** Tight window when filename has no phone or meaningful caller name (e.g. Suspected Spam / Unknown). */
    const val SPAM_OR_UNKNOWN_START_TOLERANCE_MS = 15_000L
    const val SPAM_OR_UNKNOWN_MAX_DURATION_DIFF_SEC = 10L

    /** Minimum combined cost gap (ms) required to auto-select one candidate over a plausible runner-up. */
    const val AMBIGUITY_MARGIN_MS = 10_000L

    private const val MANUAL_PICKS_PREFS = "tlc_manual_call_recording_picks_v1"
    private const val MAX_FOLDER_DEPTH = 3

    // Samsung trailing _yyMMdd_HHmmss (with optional duplicate suffix like " (1)" before extension)
    private val SAMSUNG_TRAILING_TS_REGEX = Regex("""_(\d{6})_(\d{6})(?:\s*\(\d+\))?$""")

    // Xiaomi / OnePlus / Oppo / generic OEM timestamp patterns: _yyyyMMdd_HHmmss, _yyyyMMddHHmmss, _yyyy-MM-dd-HH-mm-ss
    private val OEM_YYYYMMDD_TS_REGEX = Regex("""[_\-\s](\d{8})[_\-T]?(\d{6})(?:\s*\(\d+\))?$""")
    private val OEM_HYPHEN_TS_REGEX = Regex("""[_\-\s](\d{4})-(\d{2})-(\d{2})[T_\-\s](\d{2})[-:.](\d{2})[-:.](\d{2})(?:\s*\(\d+\))?$""")

    private val SAMSUNG_PREFIX_REGEX = Regex("""^call[\s_]+recording[\s_-]*""", RegexOption.IGNORE_CASE)
    private val CALL_RECORDING_PREFIX_REGEX = Regex(
        """^(?:call[\s_]+recording[\s_-]*|(?:call|rec|record)[\s_-]+(?=[\d+a-zA-Z]))""",
        RegexOption.IGNORE_CASE
    )
    private val PHONE_CANDIDATE_REGEX = Regex("""(?:\+?\d[\d\s\-().]{8,16}\d)""")
    private val NON_ALNUM_SPLIT_REGEX = Regex("""[^a-z0-9]+""")

    /**
     * Generic role prefixes, brokerage tags, and recording boilerplate excluded from whole-token caller matching.
     */
    val IGNORED_CALLER_TOKENS = setOf(
        "bld", "builder", "cp", "tlc", "owner", "broker", "brokerage",
        "sm", "rm", "ag", "agent", "client", "lead", "tenant", "vendor",
        "ops", "poc", "dev", "developer", "channel", "partner", "staff", "team",
        "sir", "madam", "mam", "ji", "mr", "mrs", "ms", "dr", "shri", "smt",
        "call", "recording", "rec", "record", "audio", "voice", "incoming",
        "outgoing", "missed", "phone", "mobile", "contact", "number", "num",
        "leasing", "lease", "rent", "sale", "realty", "realestate",
        "properties", "property", "estate", "commercial", "residential",
        "office", "shop", "flat", "pune",
        "unknown", "private", "spam", "suspected", "caller", "blocked", "anonymous"
    )

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
            "3gp" -> "audio/3gpp"
            else -> "audio/mp4"
        }
    }

    /**
     * Normalizes epoch timestamps: MediaStore DATE_MODIFIED is stored in seconds,
     * while File.lastModified / DocumentsContract.COLUMN_LAST_MODIFIED are in milliseconds.
     */
    fun normalizeEpochMillis(rawTimestamp: Long): Long {
        if (rawTimestamp <= 0L) return 0L
        return if (rawTimestamp < 10_000_000_000L) rawTimestamp * 1000L else rawTimestamp
    }

    /**
     * Normalizes any phone string (+91, spaces, dashes, leading 0) to its last 10 digits.
     * Returns null if fewer than 10 digits exist.
     */
    fun normalizePhoneLast10(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else null
    }

    /**
     * Extracts meaningful whole-word lowercase caller tokens, ignoring generic labels
     * such as Bld, CP, TLC, Owner, Broker, SM, Leasing, etc., single characters, and numeric tokens.
     */
    fun extractMeaningfulNameTokens(rawName: String?): Set<String> {
        if (rawName.isNullOrBlank()) return emptySet()
        return rawName.lowercase(Locale.US)
            .split(NON_ALNUM_SPLIT_REGEX)
            .asSequence()
            .map { it.trim() }
            .filter { token ->
                token.length >= 2 &&
                    token.any { it.isLetter() } &&
                    token !in IGNORED_CALLER_TOKENS
            }
            .toSet()
    }

    /**
     * Parses Samsung & OEM call recording filenames, stripping embedded date/time digits
     * BEFORE extracting phone numbers or meaningful whole-word caller tokens.
     *
     * Examples:
     * - `Call recording Bld Saurabh Tak_261121_132124.m4a`
     * - `Call recording Client Name_261121_133731.m4a`
     * - `Call recording +919730193399_261121_142920.m4a`
     * - `Call recording Suspected Spam_261121_180416.m4a`
     * - `Client Name(9822011223)_20261121133420.mp3`
     */
    fun parseSamsungRecordingMetadata(
        fileName: String,
        timeZone: TimeZone = TimeZone.getDefault()
    ): ParsedRecordingMetadata {
        val baseName = fileName.substringBeforeLast('.', fileName).trim()
        val samsungTsMatch = SAMSUNG_TRAILING_TS_REGEX.find(baseName)
        val oemYmdMatch = if (samsungTsMatch == null) OEM_YYYYMMDD_TS_REGEX.find(baseName) else null
        val oemHyphenMatch = if (samsungTsMatch == null && oemYmdMatch == null) OEM_HYPHEN_TS_REGEX.find(baseName) else null

        val isSamsungPattern = SAMSUNG_PREFIX_REGEX.containsMatchIn(baseName) || samsungTsMatch != null

        val (fileStartMillis, tsStartIndex) = when {
            samsungTsMatch != null -> {
                val millis = parseYyMmDdHhMmSsToMillis(
                    samsungTsMatch.groupValues[1],
                    samsungTsMatch.groupValues[2],
                    timeZone
                )
                millis to samsungTsMatch.range.first
            }
            oemYmdMatch != null -> {
                val millis = parseYyyyMmDdHhMmSsToMillis(
                    oemYmdMatch.groupValues[1],
                    oemYmdMatch.groupValues[2],
                    timeZone
                )
                millis to oemYmdMatch.range.first
            }
            oemHyphenMatch != null -> {
                val y = oemHyphenMatch.groupValues[1].toIntOrNull()
                val mo = oemHyphenMatch.groupValues[2].toIntOrNull()
                val d = oemHyphenMatch.groupValues[3].toIntOrNull()
                val h = oemHyphenMatch.groupValues[4].toIntOrNull()
                val mi = oemHyphenMatch.groupValues[5].toIntOrNull()
                val s = oemHyphenMatch.groupValues[6].toIntOrNull()
                val millis = if (y != null && mo != null && d != null && h != null && mi != null && s != null) {
                    buildLocalMillis(y, mo, d, h, mi, s, timeZone)
                } else null
                millis to oemHyphenMatch.range.first
            }
            else -> null to baseName.length
        }

        // Strip trailing timestamp FIRST so date/time digits never leak into phone comparison
        val beforeTimestamp = baseName.substring(0, tsStartIndex).trim()

        val label = beforeTimestamp
            .replace(CALL_RECORDING_PREFIX_REGEX, "")
            .trim(' ', '_', '-')

        // Extract phone number from caller label only (never from timestamp digits)
        val labelDigits = label.filter { it.isDigit() }
        val nonPhoneChars = label.replace(Regex("""[\s+\-()._]"""), "")
        val labelIsPurePhone = labelDigits.length in 10..15 && nonPhoneChars.all { it.isDigit() }

        var filePhoneLast10: String? = if (labelIsPurePhone) labelDigits.takeLast(10) else null
        var labelWithoutPhone = if (labelIsPurePhone) "" else label

        if (filePhoneLast10 == null) {
            val embeddedPhoneMatch = PHONE_CANDIDATE_REGEX.findAll(label).firstOrNull { match ->
                val digits = match.value.filter { it.isDigit() }
                digits.length in 10..15
            }
            if (embeddedPhoneMatch != null) {
                filePhoneLast10 = embeddedPhoneMatch.value.filter { it.isDigit() }.takeLast(10)
                labelWithoutPhone = label.removeRange(embeddedPhoneMatch.range).trim()
            }
        }

        val isSuspectedSpam = label.equals("Suspected Spam", ignoreCase = true) ||
            label.contains("suspected spam", ignoreCase = true) ||
            label.equals("Spam", ignoreCase = true) ||
            label.equals("Unknown", ignoreCase = true) ||
            label.equals("Unknown Caller", ignoreCase = true) ||
            label.equals("Private number", ignoreCase = true) ||
            label.equals("Private", ignoreCase = true) ||
            label.equals("No Caller ID", ignoreCase = true)

        val meaningfulNameTokens = if (isSuspectedSpam) {
            emptySet()
        } else {
            extractMeaningfulNameTokens(labelWithoutPhone)
        }

        return ParsedRecordingMetadata(
            label = label,
            fileStartMillis = fileStartMillis,
            filePhoneLast10 = filePhoneLast10,
            isSuspectedSpam = isSuspectedSpam,
            hasPhoneDigits = filePhoneLast10 != null,
            meaningfulNameTokens = meaningfulNameTokens,
            isSamsungPattern = isSamsungPattern
        )
    }

    private fun parseYyMmDdHhMmSsToMillis(
        yymmdd: String,
        hhmmss: String,
        timeZone: TimeZone
    ): Long? {
        if (yymmdd.length != 6 || hhmmss.length != 6) return null
        val year = 2000 + (yymmdd.substring(0, 2).toIntOrNull() ?: return null)
        val month = yymmdd.substring(2, 4).toIntOrNull() ?: return null
        val day = yymmdd.substring(4, 6).toIntOrNull() ?: return null
        val hour = hhmmss.substring(0, 2).toIntOrNull() ?: return null
        val minute = hhmmss.substring(2, 4).toIntOrNull() ?: return null
        val second = hhmmss.substring(4, 6).toIntOrNull() ?: return null
        return buildLocalMillis(year, month, day, hour, minute, second, timeZone)
    }

    private fun parseYyyyMmDdHhMmSsToMillis(
        yyyymmdd: String,
        hhmmss: String,
        timeZone: TimeZone
    ): Long? {
        if (yyyymmdd.length != 8 || hhmmss.length != 6) return null
        val year = yyyymmdd.substring(0, 4).toIntOrNull() ?: return null
        val month = yyyymmdd.substring(4, 6).toIntOrNull() ?: return null
        val day = yyyymmdd.substring(6, 8).toIntOrNull() ?: return null
        val hour = hhmmss.substring(0, 2).toIntOrNull() ?: return null
        val minute = hhmmss.substring(2, 4).toIntOrNull() ?: return null
        val second = hhmmss.substring(4, 6).toIntOrNull() ?: return null
        return buildLocalMillis(year, month, day, hour, minute, second, timeZone)
    }

    private fun buildLocalMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        timeZone: TimeZone
    ): Long? {
        if (year !in 2000..2099 || month !in 1..12 || day !in 1..31 ||
            hour !in 0..23 || minute !in 0..59 || second !in 0..59
        ) {
            return null
        }
        return runCatching {
            val cal = Calendar.getInstance(timeZone, Locale.US).apply {
                isLenient = false
                clear()
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }.getOrNull()
    }

    private enum class IdentityMatchLevel(val rank: Int) {
        REJECTED(0),
        UNLABELLED_OR_GENERIC(1),
        NAME_MATCH(2),
        EXACT_PHONE(3)
    }

    /**
     * Evaluates caller identity compatibility between a call log record and a parsed recording filename.
     * - Hard-rejects conflicting phone numbers or conflicting whole-word caller names even when timestamps match.
     * - Ignores generic tokens (Bld, CP, TLC, Owner, Broker, etc.).
     */
    private fun evaluateCallerIdentity(
        call: CrmCallRecord,
        meta: ParsedRecordingMetadata
    ): IdentityMatchLevel {
        val callPhoneLast10 = normalizePhoneLast10(call.phoneNumber)
        val callNameTokens = extractMeaningfulNameTokens(call.callerName)
        val fileNameTokens = meta.meaningfulNameTokens

        // 1. Phone number check (highest authority when present in filename)
        if (meta.filePhoneLast10 != null) {
            if (callPhoneLast10 == null || meta.filePhoneLast10 != callPhoneLast10) {
                return IdentityMatchLevel.REJECTED
            }
            // If filename ALSO contains explicit caller name tokens that completely conflict with call name tokens, reject
            if (fileNameTokens.isNotEmpty() && callNameTokens.isNotEmpty()) {
                val overlap = fileNameTokens.intersect(callNameTokens)
                if (overlap.isEmpty()) {
                    // Phone matches exactly; allow contact rename unless both are distinct full names?
                    // Exact 10-digit phone number match overrides display name renames in CRM.
                    return IdentityMatchLevel.EXACT_PHONE
                }
            }
            return IdentityMatchLevel.EXACT_PHONE
        }

        // 2. Meaningful whole-word caller name token check
        if (fileNameTokens.isNotEmpty()) {
            if (callNameTokens.isEmpty()) {
                // Filename explicitly names a caller (e.g. "Bld Sm Ujwal Hawa Mantra Leasing"),
                // while call log has no meaningful name (e.g. "Unknown" or only generic label "Bld").
                // For Samsung named recordings, reject if call had no matching name to prevent attaching
                // another contact's recording to an unsaved/unknown caller.
                return if (meta.isSamsungPattern) {
                    IdentityMatchLevel.REJECTED
                } else {
                    IdentityMatchLevel.UNLABELLED_OR_GENERIC
                }
            }

            val overlap = fileNameTokens.intersect(callNameTokens)
            if (overlap.isEmpty()) {
                // Conflicting caller names (e.g. "Bld Saurabh Tak" vs "Bld Sm Ujwal Hawa Mantra Leasing")
                return IdentityMatchLevel.REJECTED
            }

            // Guard against sharing only one token while having conflicting distinguishing names
            // (e.g. "Saurabh Tak" vs "Saurabh Joshi")
            val minTokenCount = minOf(fileNameTokens.size, callNameTokens.size)
            if (minTokenCount >= 2 && overlap.size < minTokenCount && overlap.size == 1) {
                return IdentityMatchLevel.REJECTED
            }

            return IdentityMatchLevel.NAME_MATCH
        }

        // 3. Unlabelled, Suspected Spam, or generic-only filename (e.g. "Suspected Spam", "CP Broker", "Rec_261121_132124")
        // Require BOTH timestamp and exact name/phone match whenever the call has a known caller name:
        // never attach an unlabelled or Suspected Spam recording to a named client call.
        if (callNameTokens.isNotEmpty()) {
            return IdentityMatchLevel.REJECTED
        }
        return IdentityMatchLevel.UNLABELLED_OR_GENERIC
    }

    private data class CandidatePairScore(
        val callId: String,
        val candidate: CallRecordingCandidate,
        val fileDedupeKey: String,
        val identityLevel: IdentityMatchLevel,
        val timeDiffMs: Long,
        val endDiffMs: Long,
        val durationDiffSec: Long,
        val hasDurationEvidence: Boolean,
        val cost: Long,
        val matchReason: String
    ) {
        fun toMatchedRecording(): MatchedCallRecording = MatchedCallRecording(
            fileName = candidate.fileName,
            uriOrPath = candidate.uriOrPath,
            lastModifiedMs = normalizeEpochMillis(candidate.lastModifiedMs),
            durationMs = candidate.durationMs,
            sizeBytes = candidate.sizeBytes,
            mimeType = candidate.mimeType.ifBlank { inferMimeType(candidate.fileName) },
            isContentUri = candidate.isContentUri,
            isManualPick = false,
            matchReason = matchReason
        )
    }

    /**
     * Evaluates all eligible `(call, candidate)` pairs and resolves 1-to-1 assignments
     * while detecting ambiguous matches where multiple plausible files remain for a call.
     */
    fun matchRecordingsWithOutcomes(
        calls: List<CrmCallRecord>,
        candidates: List<CallRecordingCandidate>,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Map<String, CallRecordingMatchOutcome> {
        val outcomes = LinkedHashMap<String, CallRecordingMatchOutcome>()
        val eligibleCalls = mutableListOf<CrmCallRecord>()

        for (call in calls) {
            if (call.durationSeconds <= 0 || call.callType == CallType.MISSED) {
                outcomes[call.id] = CallRecordingMatchOutcome(
                    status = CallRecordingMatchStatus.SKIPPED_ZERO_CALL,
                    matched = null,
                    message = "Missed or 0-second call — never auto-matched"
                )
            } else {
                eligibleCalls.add(call)
            }
        }

        if (eligibleCalls.isEmpty()) return outcomes

        val validAudio = deduplicateCandidates(
            candidates.filter { cand ->
                val ext = cand.fileName.substringAfterLast('.', "").lowercase(Locale.US)
                ext in SUPPORTED_EXTENSIONS
            },
            timeZone = timeZone
        )

        if (validAudio.isEmpty()) {
            for (call in eligibleCalls) {
                outcomes[call.id] = CallRecordingMatchOutcome(
                    status = CallRecordingMatchStatus.NO_MATCH,
                    matched = null,
                    message = "No recordings found in linked folder"
                )
            }
            return outcomes
        }

        val parsedCache = validAudio.associateWith { parseSamsungRecordingMetadata(it.fileName, timeZone) }
        val perCallPairs = LinkedHashMap<String, MutableList<CandidatePairScore>>()
        eligibleCalls.forEach { perCallPairs[it.id] = mutableListOf() }

        for (call in eligibleCalls) {
            val callStartMillis = call.timestamp
            val callDurationSec = call.durationSeconds.toLong()
            val callEndMillis = callStartMillis + callDurationSec * 1000L

            for (cand in validAudio) {
                val meta = parsedCache[cand] ?: continue
                val identity = evaluateCallerIdentity(call, meta)
                if (identity == IdentityMatchLevel.REJECTED) continue

                val hasDurationEvidence = cand.durationMs > 0L
                val audioDurationSec = if (hasDurationEvidence) (cand.durationMs + 500L) / 1000L else -1L

                // Duration check: required whenever duration is available (and always required for unlabelled files)
                val durationDiffSec: Long
                if (hasDurationEvidence) {
                    if (audioDurationSec <= 0L) continue
                    durationDiffSec = abs(audioDurationSec - callDurationSec)
                    val maxAllowedDurDiff = if (identity == IdentityMatchLevel.UNLABELLED_OR_GENERIC) {
                        SPAM_OR_UNKNOWN_MAX_DURATION_DIFF_SEC
                    } else {
                        MAX_DURATION_DIFF_SEC
                    }
                    if (durationDiffSec > maxAllowedDurDiff) {
                        continue // Hard reject: duration mismatch
                    }
                } else {
                    // Reject unlabelled or empty/0-byte files lacking duration evidence
                    if (identity == IdentityMatchLevel.UNLABELLED_OR_GENERIC || cand.sizeBytes <= 0L) {
                        continue
                    }
                    durationDiffSec = 0L
                }

                // Time alignment check (ALWAYS required — name or phone alone can never link an old recording)
                val timeDiffMs: Long
                val endDiffMs: Long
                val usedPrimaryStartTime: Boolean

                if (meta.fileStartMillis != null) {
                    // Use embedded local start timestamp ahead of lastModifiedMs
                    timeDiffMs = abs(meta.fileStartMillis - callStartMillis)
                    val maxStartTol = if (identity == IdentityMatchLevel.UNLABELLED_OR_GENERIC) {
                        SPAM_OR_UNKNOWN_START_TOLERANCE_MS
                    } else {
                        PRIMARY_START_TOLERANCE_MS
                    }
                    if (timeDiffMs > maxStartTol) {
                        continue // Hard reject: outside start-time tolerance
                    }

                    // Compare end with end when audio duration is known
                    if (hasDurationEvidence) {
                        val fileEndMillis = meta.fileStartMillis + audioDurationSec * 1000L
                        endDiffMs = abs(fileEndMillis - callEndMillis)
                        val maxEndTol = if (identity == IdentityMatchLevel.UNLABELLED_OR_GENERIC) {
                            SPAM_OR_UNKNOWN_START_TOLERANCE_MS + SPAM_OR_UNKNOWN_MAX_DURATION_DIFF_SEC * 1000L
                        } else {
                            TIME_TOLERANCE_MS + MAX_DURATION_DIFF_SEC * 1000L
                        }
                        if (endDiffMs > maxEndTol) {
                            continue // Hard reject: end-to-end misaligned
                        }
                    } else {
                        endDiffMs = timeDiffMs
                    }
                    usedPrimaryStartTime = true
                } else {
                    // Fallback ONLY when filename has no embedded timestamp: compare lastModifiedMs (call end) to callEndMillis
                    if (identity == IdentityMatchLevel.UNLABELLED_OR_GENERIC) {
                        continue // Never match unlabelled files by modification time alone
                    }
                    val modMs = normalizeEpochMillis(cand.lastModifiedMs)
                    if (modMs <= 0L) continue
                    endDiffMs = abs(modMs - callEndMillis)
                    if (endDiffMs > FALLBACK_END_TOLERANCE_MS) {
                        continue // Hard reject: outside end-time fallback window
                    }
                    timeDiffMs = if (hasDurationEvidence) {
                        val estimatedStartMs = modMs - audioDurationSec * 1000L
                        abs(estimatedStartMs - callStartMillis)
                    } else {
                        endDiffMs
                    }
                    usedPrimaryStartTime = false
                }

                val durationPenaltyMs = if (hasDurationEvidence) durationDiffSec * 1000L else 6_000L
                val cost = timeDiffMs + durationPenaltyMs

                val reason = buildString {
                    when (identity) {
                        IdentityMatchLevel.EXACT_PHONE -> append("Exact number")
                        IdentityMatchLevel.NAME_MATCH -> append("Caller & time match")
                        IdentityMatchLevel.UNLABELLED_OR_GENERIC -> {
                            if (meta.isSuspectedSpam) append("Strict spam/unknown match")
                            else append("Strict start-time & duration match")
                        }
                        IdentityMatchLevel.REJECTED -> Unit
                    }
                    append(" · Δt ")
                    append(timeDiffMs / 1000L)
                    append("s")
                    if (hasDurationEvidence) {
                        append(" · Δd ")
                        append(durationDiffSec)
                        append("s")
                    }
                    if (!usedPrimaryStartTime) append(" (end-time)")
                }

                val fileDedupeKey = buildFileDedupeKey(cand, meta)
                perCallPairs[call.id]?.add(
                    CandidatePairScore(
                        callId = call.id,
                        candidate = cand,
                        fileDedupeKey = fileDedupeKey,
                        identityLevel = identity,
                        timeDiffMs = timeDiffMs,
                        endDiffMs = endDiffMs,
                        durationDiffSec = durationDiffSec,
                        hasDurationEvidence = hasDurationEvidence,
                        cost = cost,
                        matchReason = reason
                    )
                )
            }
        }

        // Identify calls that are intrinsically ambiguous (multiple plausible candidates with close scores)
        val pairComparator = compareByDescending<CandidatePairScore> { it.identityLevel.rank }
            .thenBy { it.cost }
            .thenBy { it.timeDiffMs }
            .thenBy { it.durationDiffSec }
            .thenBy { it.candidate.fileName.lowercase(Locale.US) }

        val ambiguousCallIds = HashSet<String>()
        val allScoredPairs = mutableListOf<CandidatePairScore>()

        for (call in eligibleCalls) {
            val callOptions = perCallPairs[call.id].orEmpty().sortedWith(pairComparator)
            if (callOptions.size >= 2) {
                val first = callOptions[0]
                val second = callOptions[1]
                val sameIdentityTier = first.identityLevel == second.identityLevel
                val withinAmbiguityMargin = abs(second.cost - first.cost) < AMBIGUITY_MARGIN_MS
                val lacksDurationToDistinguish = !first.hasDurationEvidence && !second.hasDurationEvidence
                if (sameIdentityTier && (withinAmbiguityMargin || lacksDurationToDistinguish)) {
                    // Check if the competing second file actually belongs cleanly to a DIFFERENT call in this batch
                    val secondBelongsClearlyToAnotherCall = eligibleCalls.any { other ->
                        other.id != call.id &&
                            (perCallPairs[other.id]?.minWithOrNull(pairComparator)?.let { bestForOther ->
                                bestForOther.fileDedupeKey == second.fileDedupeKey &&
                                    bestForOther.cost + AMBIGUITY_MARGIN_MS <= second.cost
                            } == true)
                    }
                    if (!secondBelongsClearlyToAnotherCall) {
                        ambiguousCallIds.add(call.id)
                        outcomes[call.id] = CallRecordingMatchOutcome(
                            status = CallRecordingMatchStatus.AMBIGUOUS,
                            matched = null,
                            ambiguousCandidates = callOptions.take(5).map { it.toMatchedRecording() },
                            message = "${callOptions.size} possible recordings match — choose the right one"
                        )
                        continue
                    }
                }
            }
            allScoredPairs.addAll(callOptions)
        }

        // Strict 1-to-1 Greedy Assignment for unambiguous calls
        allScoredPairs.sortWith(
            compareBy<CandidatePairScore> { it.cost }
                .thenByDescending { it.identityLevel.rank }
                .thenBy { it.timeDiffMs }
                .thenBy { it.durationDiffSec }
                .thenBy { it.candidate.fileName.lowercase(Locale.US) }
        )

        val assignedCallIds = HashSet<String>()
        val assignedUris = HashSet<String>()
        val assignedFileKeys = HashSet<String>()

        for (pair in allScoredPairs) {
            if (pair.callId in ambiguousCallIds) continue
            if (pair.callId in assignedCallIds) continue
            if (pair.candidate.uriOrPath in assignedUris) continue
            if (pair.fileDedupeKey in assignedFileKeys) continue

            assignedCallIds.add(pair.callId)
            assignedUris.add(pair.candidate.uriOrPath)
            assignedFileKeys.add(pair.fileDedupeKey)

            outcomes[pair.callId] = CallRecordingMatchOutcome(
                status = CallRecordingMatchStatus.MATCHED,
                matched = pair.toMatchedRecording(),
                message = pair.matchReason
            )
        }

        for (call in eligibleCalls) {
            if (call.id !in outcomes) {
                outcomes[call.id] = CallRecordingMatchOutcome(
                    status = CallRecordingMatchStatus.NO_MATCH,
                    matched = null,
                    message = "No reliable recording matched"
                )
            }
        }

        return outcomes
    }

    /**
     * Strict 1-to-1 Greedy Assignment matcher (pure & JVM-testable).
     * Returns ONLY confirmed unambiguous automatic matches; ambiguous, missed, and unmatched calls are omitted.
     */
    fun matchRecordingsStrictOneToOne(
        calls: List<CrmCallRecord>,
        candidates: List<CallRecordingCandidate>,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Map<String, MatchedCallRecording> {
        val outcomes = matchRecordingsWithOutcomes(calls, candidates, timeZone)
        val assignments = LinkedHashMap<String, MatchedCallRecording>()
        for ((callId, outcome) in outcomes) {
            if (outcome.status == CallRecordingMatchStatus.MATCHED && outcome.matched != null) {
                assignments[callId] = outcome.matched
            }
        }
        return assignments
    }

    /**
     * Single-call outcome wrapper exposing MATCHED, AMBIGUOUS (with candidates), NO_MATCH, or SKIPPED_ZERO_CALL.
     */
    fun matchRecordingOutcomeFromCandidates(
        call: CrmCallRecord,
        candidates: List<CallRecordingCandidate>,
        timeZone: TimeZone = TimeZone.getDefault()
    ): CallRecordingMatchOutcome {
        return matchRecordingsWithOutcomes(listOf(call), candidates, timeZone)[call.id]
            ?: CallRecordingMatchOutcome(
                status = CallRecordingMatchStatus.NO_MATCH,
                matched = null,
                message = "No reliable recording matched"
            )
    }

    /**
     * Convenience single-call wrapper returning a confirmed unambiguous match or null.
     */
    fun matchRecordingFromCandidates(
        call: CrmCallRecord,
        candidates: List<CallRecordingCandidate>,
        timeZone: TimeZone = TimeZone.getDefault()
    ): MatchedCallRecording? {
        return matchRecordingOutcomeFromCandidates(call, candidates, timeZone).matched
    }

    private fun buildFileDedupeKey(
        cand: CallRecordingCandidate,
        meta: ParsedRecordingMetadata
    ): String {
        val startKey = meta.fileStartMillis?.toString() ?: normalizeEpochMillis(cand.lastModifiedMs).toString()
        return "${cand.fileName.lowercase(Locale.US)}|$startKey"
    }

    /**
     * Deduplicates scanned candidates across MediaStore, SAF, and filesystem paths,
     * preferring MediaStore CONTENT_URI entries (which already carry accurate DURATION).
     */
    private fun deduplicateCandidates(
        candidates: List<CallRecordingCandidate>,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<CallRecordingCandidate> {
        val byUri = LinkedHashMap<String, CallRecordingCandidate>()
        for (c in candidates) {
            val existing = byUri[c.uriOrPath]
            if (existing == null || (c.durationMs > 0L && existing.durationMs <= 0L)) {
                byUri[c.uriOrPath] = c
            }
        }
        val byFileKey = LinkedHashMap<String, CallRecordingCandidate>()
        for (c in byUri.values) {
            val meta = parseSamsungRecordingMetadata(c.fileName, timeZone)
            val key = if (meta.fileStartMillis != null) {
                "${c.fileName.lowercase(Locale.US)}|${meta.fileStartMillis}"
            } else {
                "${c.fileName.lowercase(Locale.US)}|${c.sizeBytes}"
            }
            val existing = byFileKey[key]
            if (existing == null) {
                byFileKey[key] = c
            } else {
                val cIsMediaStore = c.uriOrPath.startsWith("content://media/")
                val eIsMediaStore = existing.uriOrPath.startsWith("content://media/")
                val preferNew = when {
                    cIsMediaStore && !eIsMediaStore -> true
                    !cIsMediaStore && eIsMediaStore -> false
                    c.durationMs > 0L && existing.durationMs <= 0L -> true
                    c.isContentUri && !existing.isContentUri -> true
                    else -> false
                }
                if (preferNew) {
                    byFileKey[key] = c
                }
            }
        }
        return byFileKey.values.toList()
    }

    /**
     * Checks if Android 13+ READ_MEDIA_AUDIO permission is granted (always true on API <= 32).
     */
    fun hasMediaAudioPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Scans ALL Samsung & OEM call recordings across:
     * 1. MediaStore.Audio.Media.EXTERNAL_CONTENT_URI (Android 10-15+) where RELATIVE_PATH contains `Recordings/Call` or `Call`
     * 2. User-selected SAF folder (entire folder tree, no file count cutoff)
     * 3. Standard `/Recordings/Call/` and OEM directories (entire folder tree, no file count cutoff)
     */
    fun scanAllRecordingCandidates(context: Context): List<CallRecordingCandidate> {
        val candidates = mutableListOf<CallRecordingCandidate>()

        // 1. Primary source: MediaStore.Audio.Media.EXTERNAL_CONTENT_URI (has CONTENT_URI + DURATION)
        candidates.addAll(queryMediaStoreCallRecordings(context))

        // 2. User-selected SAF folder or explicit path
        val settings = UserSettingsManager(context)
        val configuredUriOrPath = settings.callRecordingFolderUri.value.trim()
        if (configuredUriOrPath.startsWith("content://")) {
            candidates.addAll(querySafTreeCandidates(context, Uri.parse(configuredUriOrPath)))
        } else if (configuredUriOrPath.isNotBlank()) {
            candidates.addAll(scanDirectoryCandidates(File(configuredUriOrPath)))
        }

        // 3. Direct filesystem fallback (/Recordings/Call/ & OEM folders)
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

        return deduplicateCandidates(candidates)
    }

    /**
     * Strictly ON-DEMAND 1-to-1 matching with full outcomes across all provided calls (runs on Dispatchers.IO).
     */
    suspend fun matchRecordingOutcomesForCallsOnDemand(
        context: Context,
        calls: List<CrmCallRecord>
    ): Map<String, CallRecordingMatchOutcome> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, CallRecordingMatchOutcome>()

        // Preserve any user-confirmed manual file picks first
        val unmatchedCalls = mutableListOf<CrmCallRecord>()
        for (c in calls) {
            val manual = loadManualRecordingPick(context, c.id)
            if (manual != null) {
                result[c.id] = CallRecordingMatchOutcome(
                    status = CallRecordingMatchStatus.MATCHED,
                    matched = manual,
                    message = manual.matchReason
                )
            } else if (c.durationSeconds <= 0 || c.callType == CallType.MISSED) {
                result[c.id] = CallRecordingMatchOutcome(
                    status = CallRecordingMatchStatus.SKIPPED_ZERO_CALL,
                    matched = null,
                    message = "Missed or 0-second call — never auto-matched"
                )
            } else {
                unmatchedCalls.add(c)
            }
        }
        if (unmatchedCalls.isEmpty()) return@withContext result

        val rawCandidates = scanAllRecordingCandidates(context)
        if (rawCandidates.isEmpty()) {
            unmatchedCalls.forEach { c ->
                result[c.id] = CallRecordingMatchOutcome(
                    status = CallRecordingMatchStatus.NO_MATCH,
                    matched = null,
                    message = "No recordings found in linked folder"
                )
            }
            return@withContext result
        }

        val enrichedCandidates = resolveMissingDurationsForRelevantCandidates(
            calls = unmatchedCalls,
            candidates = rawCandidates,
            durationResolver = { cand ->
                extractAudioDurationMs(context, cand.uriOrPath, cand.isContentUri)
            }
        )

        val outcomes = matchRecordingsWithOutcomes(unmatchedCalls, enrichedCandidates)
        result.putAll(outcomes)
        result
    }

    /**
     * Strictly ON-DEMAND 1-to-1 matching across all provided calls (confirmed matches only).
     */
    suspend fun matchRecordingsForCallsOnDemand(
        context: Context,
        calls: List<CrmCallRecord>
    ): Map<String, MatchedCallRecording> = withContext(Dispatchers.IO) {
        val outcomes = matchRecordingOutcomesForCallsOnDemand(context, calls)
        val result = LinkedHashMap<String, MatchedCallRecording>()
        for ((callId, outcome) in outcomes) {
            if (outcome.status == CallRecordingMatchStatus.MATCHED && outcome.matched != null) {
                result[callId] = outcome.matched
            }
        }
        result
    }

    /**
     * Strictly ON-DEMAND recording lookup returning full CallRecordingMatchOutcome (MATCHED / AMBIGUOUS / NO_MATCH / SKIPPED_ZERO_CALL)
     * while enforcing global 1-to-1 assignment across nearby calls.
     */
    suspend fun matchRecordingOutcomeForCallOnDemand(
        context: Context,
        call: CrmCallRecord,
        allCalls: List<CrmCallRecord>? = null
    ): CallRecordingMatchOutcome = withContext(Dispatchers.IO) {
        loadManualRecordingPick(context, call.id)?.let { manual ->
            return@withContext CallRecordingMatchOutcome(
                status = CallRecordingMatchStatus.MATCHED,
                matched = manual,
                message = manual.matchReason
            )
        }
        if (call.durationSeconds <= 0 || call.callType == CallType.MISSED) {
            return@withContext CallRecordingMatchOutcome(
                status = CallRecordingMatchStatus.SKIPPED_ZERO_CALL,
                matched = null,
                message = "Missed or 0-second call — never auto-matched"
            )
        }

        val callContextList = buildList {
            add(call)
            val others = allCalls ?: runCatching { CrmCallManager.readCalls(context) }.getOrDefault(emptyList())
            for (other in others) {
                if (other.id != call.id && other.durationSeconds > 0 && other.callType != CallType.MISSED) {
                    if (abs(other.timestamp - call.timestamp) <= 10 * 60_000L) {
                        add(other)
                    }
                }
            }
        }

        matchRecordingOutcomesForCallsOnDemand(context, callContextList)[call.id]
            ?: CallRecordingMatchOutcome(
                status = CallRecordingMatchStatus.NO_MATCH,
                matched = null,
                message = "No reliable recording matched"
            )
    }

    /**
     * Strictly ON-DEMAND recording lookup for a single call (returns confirmed match or null).
     */
    suspend fun matchRecordingForCallOnDemand(
        context: Context,
        call: CrmCallRecord,
        allCalls: List<CrmCallRecord>? = null
    ): MatchedCallRecording? = withContext(Dispatchers.IO) {
        matchRecordingOutcomeForCallOnDemand(context, call, allCalls).matched
    }

    /**
     * Queries MediaStore.Audio.Media.EXTERNAL_CONTENT_URI (Android 10-15+) where RELATIVE_PATH
     * contains `Recordings/Call` or `Call`, reading ALL matching rows with no arbitrary file cap.
     */
    private fun queryMediaStoreCallRecordings(context: Context): List<CallRecordingCandidate> {
        return runCatching {
            val out = mutableListOf<CallRecordingCandidate>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.RELATIVE_PATH
                )
            } else {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.MIME_TYPE
                )
            }

            val selection: String
            val selectionArgs: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                selectionArgs = arrayOf("%Recordings/Call%", "%Call%")
            } else {
                @Suppress("DEPRECATION")
                selection = "${MediaStore.Audio.Media.DATA} LIKE ? OR ${MediaStore.Audio.Media.DATA} LIKE ?"
                selectionArgs = arrayOf("%Recordings/Call%", "%Call%")
            }

            val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val modIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val durIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val mimeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val relPathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                    if (ext !in SUPPORTED_EXTENSIONS) continue

                    val rawMod = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                    val durationMs = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                    val relPath = if (relPathIdx >= 0) cursor.getString(relPathIdx).orEmpty() else ""
                    val contentUri = ContentUris.withAppendedId(collection, id)

                    out.add(
                        CallRecordingCandidate(
                            fileName = name,
                            uriOrPath = contentUri.toString(),
                            lastModifiedMs = normalizeEpochMillis(rawMod),
                            durationMs = durationMs.coerceAtLeast(0L),
                            sizeBytes = size.coerceAtLeast(0L),
                            mimeType = mime.ifBlank { inferMimeType(name) },
                            isContentUri = true,
                            relativePath = relPath
                        )
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * Cheap pre-filter that determines whether a candidate could plausibly match at least one target call
     * (passes caller identity and time-window checks) so audio duration metadata is ONLY read for shortlisted files.
     */
    internal fun isCandidateShortlistedForCalls(
        calls: List<CrmCallRecord>,
        cand: CallRecordingCandidate,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        val ext = cand.fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext !in SUPPORTED_EXTENSIONS) return false
        val meta = parseSamsungRecordingMetadata(cand.fileName, timeZone)
        return calls.any { call ->
            if (call.durationSeconds <= 0 || call.callType == CallType.MISSED) return@any false
            val identity = evaluateCallerIdentity(call, meta)
            if (identity == IdentityMatchLevel.REJECTED) return@any false

            if (meta.fileStartMillis != null) {
                val maxTol = if (identity == IdentityMatchLevel.UNLABELLED_OR_GENERIC) {
                    SPAM_OR_UNKNOWN_START_TOLERANCE_MS
                } else {
                    PRIMARY_START_TOLERANCE_MS
                }
                abs(meta.fileStartMillis - call.timestamp) <= maxTol
            } else {
                if (identity == IdentityMatchLevel.UNLABELLED_OR_GENERIC) return@any false
                val callEnd = call.timestamp + call.durationSeconds * 1000L
                val modMs = normalizeEpochMillis(cand.lastModifiedMs)
                modMs > 0L && abs(modMs - callEnd) <= FALLBACK_END_TOLERANCE_MS
            }
        }
    }

    /**
     * Lazily resolves missing audio durations ONLY for shortlisted candidates that pass
     * cheap caller identity + time-window pre-filtering.
     */
    internal fun resolveMissingDurationsForRelevantCandidates(
        calls: List<CrmCallRecord>,
        candidates: List<CallRecordingCandidate>,
        timeZone: TimeZone = TimeZone.getDefault(),
        durationResolver: (CallRecordingCandidate) -> Long
    ): List<CallRecordingCandidate> {
        if (candidates.none { it.durationMs <= 0L }) return candidates

        return candidates.map { cand ->
            if (cand.durationMs > 0L) return@map cand
            if (!isCandidateShortlistedForCalls(calls, cand, timeZone)) {
                cand
            } else {
                val extractedDuration = durationResolver(cand)
                if (extractedDuration > 0L) cand.copy(durationMs = extractedDuration) else cand
            }
        }
    }

    fun extractAudioDurationMs(context: Context, uriOrPath: String, isContentUri: Boolean): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            if (isContentUri || uriOrPath.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(uriOrPath))
            } else {
                retriever.setDataSource(uriOrPath)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Scans the ENTIRE directory tree (up to depth 3) for supported audio recordings with NO 150-file cutoff.
     */
    internal fun scanDirectoryCandidates(dir: File): List<CallRecordingCandidate> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val out = mutableListOf<CallRecordingCandidate>()
        val visitedPaths = HashSet<String>()

        fun walk(currentDir: File, depth: Int) {
            if (depth > MAX_FOLDER_DEPTH) return
            val canonical = runCatching { currentDir.canonicalPath }.getOrDefault(currentDir.absolutePath)
            if (!visitedPaths.add(canonical)) return
            val entries = currentDir.listFiles() ?: return
            for (entry in entries) {
                if (entry.isDirectory) {
                    walk(entry, depth + 1)
                } else if (entry.isFile) {
                    val ext = entry.extension.lowercase(Locale.US)
                    if (ext in SUPPORTED_EXTENSIONS) {
                        out.add(
                            CallRecordingCandidate(
                                fileName = entry.name,
                                uriOrPath = entry.absolutePath,
                                lastModifiedMs = normalizeEpochMillis(entry.lastModified()),
                                durationMs = 0L,
                                sizeBytes = entry.length(),
                                mimeType = inferMimeType(entry.name),
                                isContentUri = false,
                                relativePath = currentDir.absolutePath
                            )
                        )
                    }
                }
            }
        }

        walk(dir, depth = 0)
        return out
    }

    /**
     * Queries the ENTIRE linked SAF tree (including nested subfolders up to depth 3) with NO 150-file cutoff.
     */
    internal fun querySafTreeCandidates(context: Context, treeUri: Uri): List<CallRecordingCandidate> {
        return runCatching {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val out = mutableListOf<CallRecordingCandidate>()
            val visitedDocIds = HashSet<String>()

            fun scanDocFolder(parentDocId: String, depth: Int) {
                if (depth > MAX_FOLDER_DEPTH || !visitedDocIds.add(parentDocId)) return
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val childDocId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            scanDocFolder(childDocId, depth + 1)
                            continue
                        }

                        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                        if (ext !in SUPPORTED_EXTENSIONS) continue

                        val lastMod = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        out.add(
                            CallRecordingCandidate(
                                fileName = name,
                                uriOrPath = docUri.toString(),
                                lastModifiedMs = normalizeEpochMillis(lastMod),
                                durationMs = 0L,
                                sizeBytes = size,
                                mimeType = mime.ifBlank { inferMimeType(name) },
                                isContentUri = true,
                                relativePath = parentDocId
                            )
                        )
                    }
                }
            }

            scanDocFolder(rootDocId, depth = 0)
            out
        }.getOrDefault(emptyList())
    }

    /**
     * Builds a MatchedCallRecording from a user-selected SAF OpenDocument URI.
     */
    fun buildManualPickedRecording(context: Context, uri: Uri): MatchedCallRecording {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected Recording.m4a"
        var sizeBytes = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0) {
                        sizeBytes = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                    }
                }
            }
        }
        val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { inferMimeType(displayName) }
        val durationMs = extractAudioDurationMs(context, uri.toString(), isContentUri = true)
        return MatchedCallRecording(
            fileName = displayName,
            uriOrPath = uri.toString(),
            lastModifiedMs = System.currentTimeMillis(),
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            mimeType = mime,
            isContentUri = true,
            isManualPick = true,
            matchReason = "User selected recording"
        )
    }

    fun saveManualRecordingPick(context: Context, callId: String, recording: MatchedCallRecording) {
        val json = JSONObject().apply {
            put("fileName", recording.fileName)
            put("uriOrPath", recording.uriOrPath)
            put("lastModifiedMs", recording.lastModifiedMs)
            put("durationMs", recording.durationMs)
            put("sizeBytes", recording.sizeBytes)
            put("mimeType", recording.mimeType)
            put("isContentUri", recording.isContentUri)
        }
        context.getSharedPreferences(MANUAL_PICKS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(callId, json.toString())
            .apply()
    }

    fun loadManualRecordingPick(context: Context, callId: String): MatchedCallRecording? {
        val raw = context.getSharedPreferences(MANUAL_PICKS_PREFS, Context.MODE_PRIVATE)
            .getString(callId, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val uriStr = obj.optString("uriOrPath", "")
            if (uriStr.isBlank()) return null
            MatchedCallRecording(
                fileName = obj.optString("fileName", "Selected Recording"),
                uriOrPath = uriStr,
                lastModifiedMs = obj.optLong("lastModifiedMs", 0L),
                durationMs = obj.optLong("durationMs", 0L),
                sizeBytes = obj.optLong("sizeBytes", 0L),
                mimeType = obj.optString("mimeType", "audio/mp4"),
                isContentUri = obj.optBoolean("isContentUri", uriStr.startsWith("content://")),
                isManualPick = true,
                matchReason = "User selected recording"
            )
        }.getOrNull()
    }

    fun clearManualRecordingPick(context: Context, callId: String) {
        context.getSharedPreferences(MANUAL_PICKS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(callId)
            .apply()
    }

    fun canOpenAudioRecording(context: Context, recording: MatchedCallRecording): Boolean {
        return runCatching {
            if (recording.isContentUri || recording.uriOrPath.startsWith("content://")) {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(recording.uriOrPath), "r")?.use { true }
                    ?: false
            } else {
                val f = File(recording.uriOrPath)
                f.exists() && f.canRead() && f.length() > 0L
            }
        }.getOrDefault(false)
    }

    fun readAudioBytesSafely(context: Context, recording: MatchedCallRecording): ByteArray? {
        return runCatching {
            if (recording.isContentUri || recording.uriOrPath.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(recording.uriOrPath))?.use { stream ->
                    val bytes = stream.readBytes()
                    bytes.takeIf { it.isNotEmpty() && it.size <= 14 * 1024 * 1024 }
                }
            } else {
                val f = File(recording.uriOrPath)
                if (f.exists() && f.canRead() && f.length() in 1L..(14L * 1024 * 1024)) f.readBytes() else null
            }
        }.getOrNull()
    }
}

/**
 * Reusable "Last Called By" Banner displayed inside PropertyDetailScreen,
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
 * Strict 1-to-1 On-Demand Call Recording Match ("Play", "Choose recording", & "Summarize").
 * - Searches the entire linked folder off the UI thread ONLY when composed.
 * - Enables Play and Summarize ONLY for a confirmed automatic match or a file the user explicitly selected.
 * - Surfaces ambiguous candidates so the user can choose explicitly, or offers Android's audio file picker.
 * - Stops playback and cancels stale searches/summaries immediately whenever the active call card changes.
 * - If audio cannot be opened, displays a clear error and leaves call notes completely intact.
 */
@Composable
fun CallRecordingOnDemandSection(
    call: CrmCallRecord,
    currentNotes: String,
    onSummaryGenerated: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "call_recording",
    allCallsForStrictDedup: List<CrmCallRecord>? = null,
    precomputedOutcome: CallRecordingMatchOutcome? = null,
    onRecordingOutcomeChanged: ((CallRecordingMatchOutcome) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val geminiService = remember { GeminiRealEstateService() }

    val latestCallId by rememberUpdatedState(call.id)
    val latestOnSummaryGenerated by rememberUpdatedState(onSummaryGenerated)
    val latestOnOutcomeChanged by rememberUpdatedState(onRecordingOutcomeChanged)

    var isCheckingRecording by remember(call.id) { mutableStateOf(precomputedOutcome == null) }
    var matchOutcome by remember(call.id) {
        mutableStateOf(
            precomputedOutcome ?: CallRecordingMatchOutcome(
                status = CallRecordingMatchStatus.NO_MATCH,
                matched = null,
                message = "Scanning recordings..."
            )
        )
    }
    var matchedRecording by remember(call.id) { mutableStateOf<MatchedCallRecording?>(precomputedOutcome?.matched) }
    var isPlaying by remember(call.id) { mutableStateOf(false) }
    var isSummarizing by remember(call.id) { mutableStateOf(false) }
    var audioError by remember(call.id) { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var activeSummarizeJob by remember { mutableStateOf<Job?>(null) }
    var activePickerJob by remember { mutableStateOf<Job?>(null) }
    var pendingPickerCallId by remember { mutableStateOf<String?>(null) }

    var audioPermissionGranted by remember {
        mutableStateOf(CallRecordingMatcher.hasMediaAudioPermission(context))
    }
    var rematchTrigger by remember(call.id) { mutableIntStateOf(0) }

    fun stopAndReleasePlayer() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        isPlaying = false
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioPermissionGranted = granted
        if (granted) {
            rematchTrigger++
        } else {
            Toast.makeText(
                context,
                "Audio access denied. You can still tap Choose recording or link a folder in Settings.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val manualFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val expectedCallId = pendingPickerCallId
        pendingPickerCallId = null
        if (uri != null && expectedCallId != null && expectedCallId == latestCallId) {
            activePickerJob?.cancel()
            activePickerJob = coroutineScope.launch {
                val picked = withContext(Dispatchers.IO) {
                    val rec = CallRecordingMatcher.buildManualPickedRecording(context, uri)
                    if (!CallRecordingMatcher.canOpenAudioRecording(context, rec)) {
                        return@withContext null
                    }
                    CallRecordingMatcher.saveManualRecordingPick(context, expectedCallId, rec)
                    rec
                }
                if (!isActive || expectedCallId != latestCallId) return@launch
                if (picked == null) {
                    audioError = "Could not open the selected audio file. Call notes were left intact."
                    Toast.makeText(context, "Unable to open selected recording", Toast.LENGTH_SHORT).show()
                } else {
                    stopAndReleasePlayer()
                    audioError = null
                    matchedRecording = picked
                    val updatedOutcome = CallRecordingMatchOutcome(
                        status = CallRecordingMatchStatus.MATCHED,
                        matched = picked,
                        message = picked.matchReason
                    )
                    matchOutcome = updatedOutcome
                    latestOnOutcomeChanged?.invoke(updatedOutcome)
                }
            }
        }
    }

    // Immediately stop playback and cancel in-flight summary/picker jobs when switching call cards or disposing
    DisposableEffect(call.id) {
        onDispose {
            activeSummarizeJob?.cancel()
            activeSummarizeJob = null
            activePickerJob?.cancel()
            activePickerJob = null
            stopAndReleasePlayer()
        }
    }

    // Sync with precomputed batch outcome when provided; otherwise run on-demand full-folder scan off the UI thread
    LaunchedEffect(call.id, call.phoneNumber, call.callerName, call.timestamp, call.durationSeconds, rematchTrigger, precomputedOutcome) {
        val targetCallId = call.id
        if (rematchTrigger == 0 && precomputedOutcome != null) {
            matchOutcome = precomputedOutcome
            matchedRecording = precomputedOutcome.matched
            isCheckingRecording = false
            return@LaunchedEffect
        }
        isCheckingRecording = true
        audioError = null
        val outcome = CallRecordingMatcher.matchRecordingOutcomeForCallOnDemand(
            context = context,
            call = call,
            allCalls = allCallsForStrictDedup
        )
        if (!isActive || targetCallId != latestCallId) return@LaunchedEffect
        matchOutcome = outcome
        matchedRecording = outcome.matched
        isCheckingRecording = false
        latestOnOutcomeChanged?.invoke(outcome)
    }

    val activeRec = matchedRecording
    val recordingDurationSec = remember(activeRec, call.durationSeconds) {
        val ms = activeRec?.durationMs ?: 0L
        if (ms > 0L) ((ms + 500L) / 1000L).toInt() else call.durationSeconds
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
                        tint = if (activeRec != null) PrimaryActionOrange else SecondaryText,
                        modifier = Modifier.size(16.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isCheckingRecording -> "Scanning linked recording folder..."
                                activeRec != null -> activeRec.fileName
                                call.durationSeconds <= 0 || call.callType == CallType.MISSED ->
                                    "No recording (missed / 0s call)"
                                matchOutcome.status == CallRecordingMatchStatus.AMBIGUOUS ->
                                    "Multiple recordings match — please choose"
                                else -> "No reliable recording matched"
                            },
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                activeRec != null -> activeRec.matchReason
                                call.durationSeconds <= 0 || call.callType == CallType.MISSED ->
                                    "Missed & 0s calls are never auto-matched"
                                matchOutcome.status == CallRecordingMatchStatus.AMBIGUOUS ->
                                    "Select one below or tap Choose recording"
                                !audioPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                    "Grant audio access or tap Choose recording"
                                else -> "Tap Choose recording to select the audio file"
                            },
                            fontSize = 10.sp,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (activeRec != null && activeRec.isManualPick) {
                    TextButton(
                        onClick = {
                            stopAndReleasePlayer()
                            audioError = null
                            CallRecordingMatcher.clearManualRecordingPick(context, call.id)
                            matchedRecording = null
                            rematchTrigger++
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .testTag("${testTagPrefix}_clear_manual_btn")
                    ) {
                        Text("Clear", fontSize = 11.sp, color = SecondaryText)
                    }
                }
            }

            // Clear inline error banner when audio cannot be opened (call notes remain untouched)
            if (!audioError.isNullOrBlank()) {
                Surface(
                    color = Color(0xFFFEF2F2),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${testTagPrefix}_error_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Audio Error",
                            tint = Color(0xFFB91C1C),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = audioError.orEmpty(),
                            fontSize = 10.5.sp,
                            color = Color(0xFF991B1B),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Ambiguous candidates chooser (lets user pick explicitly without auto-selecting)
            if (activeRec == null && !isCheckingRecording &&
                matchOutcome.status == CallRecordingMatchStatus.AMBIGUOUS &&
                matchOutcome.ambiguousCandidates.isNotEmpty()
            ) {
                Surface(
                    color = Color(0xFFFFFBEB),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFFCD34D)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${testTagPrefix}_ambiguous_list")
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Tap the matching recording to confirm:",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF92400E)
                        )
                        matchOutcome.ambiguousCandidates.forEachIndexed { idx, candRec ->
                            val durLabel = if (candRec.durationMs > 0L) {
                                CrmCallManager.formatDuration(((candRec.durationMs + 500L) / 1000L).toInt())
                            } else ""
                            Surface(
                                color = SurfaceWhite,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val selected = candRec.copy(
                                            isManualPick = true,
                                            matchReason = "User selected recording"
                                        )
                                        CallRecordingMatcher.saveManualRecordingPick(context, call.id, selected)
                                        stopAndReleasePlayer()
                                        audioError = null
                                        matchedRecording = selected
                                        val updatedOutcome = CallRecordingMatchOutcome(
                                            status = CallRecordingMatchStatus.MATCHED,
                                            matched = selected,
                                            message = selected.matchReason
                                        )
                                        matchOutcome = updatedOutcome
                                        latestOnOutcomeChanged?.invoke(updatedOutcome)
                                    }
                                    .testTag("${testTagPrefix}_ambiguous_option_$idx")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = candRec.fileName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = listOf(durLabel, candRec.matchReason)
                                                .filter { it.isNotBlank() }
                                                .joinToString(" · "),
                                            fontSize = 9.5.sp,
                                            color = SecondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.CheckCircleOutline,
                                        contentDescription = "Select Recording",
                                        tint = PrimaryActionOrange,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (activeRec == null && !isCheckingRecording &&
                call.durationSeconds > 0 && call.callType != CallType.MISSED &&
                !audioPermissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                OutlinedButton(
                    onClick = {
                        audioPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .testTag("${testTagPrefix}_grant_audio_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = "Allow Call Recording Audio Access",
                        tint = PrimaryActionOrange,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Allow Audio Access to Auto-Match Samsung Recordings",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeRec != null) {
                    // Play / Pause Button (enabled ONLY for confirmed automatic match or user-selected file)
                    OutlinedButton(
                        enabled = !isCheckingRecording,
                        onClick = {
                            if (isPlaying) {
                                runCatching { mediaPlayer?.pause() }
                                isPlaying = false
                            } else {
                                audioError = null
                                runCatching {
                                    if (!CallRecordingMatcher.canOpenAudioRecording(context, activeRec)) {
                                        throw IllegalStateException("Audio file cannot be opened")
                                    }
                                    mediaPlayer?.release()
                                    val mp = MediaPlayer()
                                    if (activeRec.isContentUri || activeRec.uriOrPath.startsWith("content://")) {
                                        mp.setDataSource(context, Uri.parse(activeRec.uriOrPath))
                                    } else {
                                        mp.setDataSource(activeRec.uriOrPath)
                                    }
                                    mp.setOnCompletionListener { isPlaying = false }
                                    mp.prepare()
                                    mp.start()
                                    mediaPlayer = mp
                                    isPlaying = true
                                }.onFailure {
                                    stopAndReleasePlayer()
                                    audioError = "Could not open \"${activeRec.fileName}\". Call notes were left intact."
                                    Toast.makeText(
                                        context,
                                        "Unable to open ${activeRec.fileName}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
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
                            text = if (isPlaying) "Pause" else "Play (${CrmCallManager.formatDuration(recordingDurationSec)})",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    // Android audio file picker when no reliable match exists
                    OutlinedButton(
                        onClick = {
                            pendingPickerCallId = call.id
                            manualFilePickerLauncher.launch(arrayOf("audio/*"))
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("${testTagPrefix}_choose_recording_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Choose Recording",
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Choose recording",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Summarize Button: enabled ONLY for a confirmed automatic match or user-selected recording
                Button(
                    enabled = !isCheckingRecording && !isSummarizing && activeRec != null,
                    onClick = {
                        val targetCallId = call.id
                        val targetRec = matchedRecording ?: return@Button
                        val targetCallerName = call.callerName
                        val targetCallerPhone = call.phoneNumber
                        val targetDuration = call.durationSeconds
                        val targetNotes = currentNotes.ifBlank { call.notes }

                        audioError = null
                        isSummarizing = true
                        activeSummarizeJob?.cancel()
                        activeSummarizeJob = coroutineScope.launch {
                            val audioBytes = withContext(Dispatchers.IO) {
                                CallRecordingMatcher.readAudioBytesSafely(context, targetRec)
                            }
                            if (!isActive || targetCallId != latestCallId) return@launch

                            if (audioBytes == null || audioBytes.isEmpty()) {
                                isSummarizing = false
                                audioError = "Could not open \"${targetRec.fileName}\" for summary. Call notes were left intact."
                                Toast.makeText(
                                    context,
                                    "Unable to open recording. Call notes unchanged.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }

                            val summaryResult = runCatching {
                                geminiService.summarizeCallRecordingOnDemand(
                                    callerName = targetCallerName,
                                    callerPhone = targetCallerPhone,
                                    durationSeconds = targetDuration,
                                    existingNotes = targetNotes,
                                    recordingFileName = targetRec.fileName,
                                    audioBytes = audioBytes,
                                    audioMimeType = targetRec.mimeType
                                )
                            }

                            if (!isActive || targetCallId != latestCallId) return@launch
                            isSummarizing = false

                            summaryResult.onSuccess { summary ->
                                if (summary.isNotBlank()) {
                                    latestOnSummaryGenerated(summary)
                                    Toast.makeText(context, "Call summary written to Call Note", Toast.LENGTH_SHORT).show()
                                }
                            }.onFailure {
                                audioError = "Could not summarize \"${targetRec.fileName}\". Call notes were left intact."
                                Toast.makeText(context, "Summary failed. Call notes unchanged.", Toast.LENGTH_SHORT).show()
                            }
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
                        .heightIn(min = 48.dp)
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
                            text = "Summarize",
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
 * Top-level PendingCallNavigation state stored at MainScaffold / NavHost
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
    val subRole: String = "",
    val segmentHint: String = "",
    val isBrokerClient: Boolean = false,
    val initialPropertyTab: com.example.model.InventoryTab? = null,
    val callId: String = ""
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
            subRole: String = "",
            segmentHint: String = "",
            isBrokerClient: Boolean = false,
            initialPropertyTab: com.example.model.InventoryTab? = null
        ): PendingCallNavigation {
            val route = when {
                isBrokerClient -> ROUTE_ADD_EDIT_LEAD
                role == PostCallRole.CLIENT_LEAD || role == PostCallRole.FRESH_UNKNOWN -> ROUTE_ADD_EDIT_LEAD
                role == PostCallRole.PROPERTY_OWNER -> ROUTE_ADD_EDIT_PROPERTY
                role == PostCallRole.DEVELOPER_POC -> ROUTE_ADD_EDIT_PROJECT
                role == PostCallRole.TENANT -> ROUTE_ADD_EDIT_TENANT
                else -> ROUTE_OPS_VENDOR
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
                subRole = subRole,
                segmentHint = segmentHint,
                isBrokerClient = isBrokerClient,
                initialPropertyTab = initialPropertyTab,
                callId = call.id
            )
        }
    }
}
