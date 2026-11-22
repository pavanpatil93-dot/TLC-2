package com.example.crm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class CallRecordingMatcherTest {

    private val istZone: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    private fun localMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long {
        return Calendar.getInstance(istZone, Locale.US).apply {
            isLenient = false
            clear()
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun parseSamsungMetadata_extractsPhoneLabelAndExactLocalStartMillis() {
        val meta = CallRecordingMatcher.parseSamsungRecordingMetadata(
            fileName = "Call recording +919730193399_261121_142920.m4a",
            timeZone = istZone
        )
        assertEquals("+919730193399", meta.label)
        assertEquals("9730193399", meta.filePhoneLast10)
        assertEquals(localMillis(2026, 11, 21, 14, 29, 20), meta.fileStartMillis)
        assertFalse(meta.isSuspectedSpam)
        assertTrue(meta.hasPhoneDigits)

        val formattedPhone = CallRecordingMatcher.parseSamsungRecordingMetadata(
            fileName = "Call recording +91 97301-93399_261121_142920.m4a",
            timeZone = istZone
        )
        assertEquals("9730193399", formattedPhone.filePhoneLast10)
    }

    @Test
    fun parseSamsungMetadata_extractsSuspectedSpamLabelWithNoPhone() {
        val meta = CallRecordingMatcher.parseSamsungRecordingMetadata(
            fileName = "Call recording Suspected Spam_261121_180416.m4a",
            timeZone = istZone
        )
        assertEquals("Suspected Spam", meta.label)
        assertNull(meta.filePhoneLast10)
        assertTrue(meta.isSuspectedSpam)
        assertFalse(meta.hasPhoneDigits)
        assertEquals(localMillis(2026, 11, 21, 18, 4, 16), meta.fileStartMillis)
    }

    @Test
    fun long25MinuteCall_matchesViaFilenameStartTimeDespiteLateDateModified() {
        // Call starts at 14:29:20 and lasts 25:45 (1545 seconds), ending at 14:55:05.
        // Samsung writes DATE_MODIFIED at call END (25+ minutes after CallLog.DATE).
        val callStart = localMillis(2026, 11, 21, 14, 29, 20)
        val callDurationSec = 25 * 60 + 45 // 1545s
        val callEnd = callStart + callDurationSec * 1000L

        val call = CrmCallRecord(
            id = "long_call_1",
            callerName = "Client",
            phoneNumber = "+91 97301 93399",
            callType = CallType.OUTGOING,
            timestamp = callStart,
            durationSeconds = callDurationSec
        )

        val samsungFile = CallRecordingCandidate(
            fileName = "Call recording +919730193399_261121_142922.m4a",
            uriOrPath = "content://media/external/audio/media/901",
            lastModifiedMs = callEnd + 3_000L, // 25m 48s after callStart!
            durationMs = 1_544_000L,
            sizeBytes = 6_400_000L,
            isContentUri = true
        )

        val match = CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(samsungFile), istZone)
        assertNotNull(match)
        assertEquals("content://media/external/audio/media/901", match?.uriOrPath)
    }

    @Test
    fun missedOrZeroDurationCall_neverMatchesAnyRecording() {
        val callStart = localMillis(2026, 11, 21, 14, 29, 20)
        val missedCall = CrmCallRecord(
            id = "missed_1",
            callerName = "Missed Caller",
            phoneNumber = "+919730193399",
            callType = CallType.MISSED,
            timestamp = callStart,
            durationSeconds = 0
        )
        val file = CallRecordingCandidate(
            fileName = "Call recording +919730193399_261121_142920.m4a",
            uriOrPath = "content://media/external/audio/media/902",
            lastModifiedMs = callStart + 5_000L,
            durationMs = 5_000L,
            isContentUri = true
        )
        assertNull(CallRecordingMatcher.matchRecordingFromCandidates(missedCall, listOf(file), istZone))
    }

    @Test
    fun phoneNumberHardCheck_rejectsDifferentPhoneEvenAtExactSameTimeAndDuration() {
        val callStart = localMillis(2026, 11, 21, 14, 29, 20)
        val call = CrmCallRecord(
            id = "phone_mismatch",
            callerName = "Caller A",
            phoneNumber = "+919822000001",
            callType = CallType.INCOMING,
            timestamp = callStart,
            durationSeconds = 90
        )
        val wrongPhoneFile = CallRecordingCandidate(
            fileName = "Call recording +919730193399_261121_142920.m4a",
            uriOrPath = "content://media/external/audio/media/903",
            lastModifiedMs = callStart + 90_000L,
            durationMs = 90_000L,
            isContentUri = true
        )
        assertNull(CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(wrongPhoneFile), istZone))
    }

    @Test
    fun durationHardCheck_rejectsWhenDiffExceeds15Seconds() {
        val callStart = localMillis(2026, 11, 21, 14, 29, 20)
        val call = CrmCallRecord(
            id = "dur_mismatch",
            callerName = "Caller A",
            phoneNumber = "+919730193399",
            callType = CallType.INCOMING,
            timestamp = callStart,
            durationSeconds = 120
        )
        val tooShort = CallRecordingCandidate(
            fileName = "Call recording +919730193399_261121_142920.m4a",
            uriOrPath = "content://media/external/audio/media/904",
            lastModifiedMs = callStart + 104_000L,
            durationMs = 104_000L, // 16s diff -> reject
            isContentUri = true
        )
        assertNull(CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(tooShort), istZone))

        val within15s = tooShort.copy(
            uriOrPath = "content://media/external/audio/media/905",
            durationMs = 105_000L // 15s diff -> accept
        )
        assertNotNull(CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(within15s), istZone))
    }

    @Test
    fun suspectedSpamAndUnknownLabel_enforcesStrict15sTimeAnd10sDuration() {
        val callStart = localMillis(2026, 11, 21, 18, 4, 16)
        val spamFile = CallRecordingCandidate(
            fileName = "Call recording Suspected Spam_261121_180416.m4a",
            uriOrPath = "content://media/external/audio/media/906",
            lastModifiedMs = callStart + 45_000L,
            durationMs = 45_000L,
            isContentUri = true
        )

        // Exact within 15s start & 10s duration -> matches
        val matchingCall = CrmCallRecord(
            id = "spam_ok",
            callerName = "Suspected Spam",
            phoneNumber = "1409998888",
            callType = CallType.INCOMING,
            timestamp = callStart - 10_000L, // 10s diff <= 15s
            durationSeconds = 52 // 7s diff <= 10s
        )
        assertNotNull(CallRecordingMatcher.matchRecordingFromCandidates(matchingCall, listOf(spamFile), istZone))

        // 16s start diff -> rejected (even though <= 30s general window)
        val tooFarStart = matchingCall.copy(id = "spam_bad_time", timestamp = callStart - 16_000L)
        assertNull(CallRecordingMatcher.matchRecordingFromCandidates(tooFarStart, listOf(spamFile), istZone))

        // 11s duration diff -> rejected (even though <= 15s general window)
        val tooFarDur = matchingCall.copy(id = "spam_bad_dur", durationSeconds = 56)
        assertNull(CallRecordingMatcher.matchRecordingFromCandidates(tooFarDur, listOf(spamFile), istZone))
    }

    @Test
    fun fallbackEndTimeMatching_worksOnlyWhenFilenameHasNoTimestampAndPhoneAndDurationMatch() {
        val callStart = localMillis(2026, 11, 21, 11, 0, 0)
        val callDurationSec = 75
        val callEnd = callStart + callDurationSec * 1000L

        val call = CrmCallRecord(
            id = "fallback_1",
            callerName = "Client",
            phoneNumber = "9730193399",
            callType = CallType.OUTGOING,
            timestamp = callStart,
            durationSeconds = callDurationSec
        )

        val validFallback = CallRecordingCandidate(
            fileName = "Call_9730193399.m4a",
            uriOrPath = "content://media/external/audio/media/907",
            lastModifiedMs = callEnd + 12_000L, // within 30s of call end
            durationMs = 74_000L,
            isContentUri = true
        )
        assertNotNull(CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(validFallback), istZone))

        val staleFallback = validFallback.copy(
            uriOrPath = "content://media/external/audio/media/908",
            lastModifiedMs = callEnd + 35_000L // > 30s -> reject
        )
        assertNull(CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(staleFallback), istZone))
    }

    @Test
    fun greedyOneToOneAssignment_assignsEachContentUriToAtMostOneCall() {
        val t1 = localMillis(2026, 11, 21, 15, 0, 0)
        val t2 = localMillis(2026, 11, 21, 15, 0, 18) // 18s later, same number

        val call1 = CrmCallRecord(
            id = "c1",
            callerName = "Client",
            phoneNumber = "+919730193399",
            callType = CallType.OUTGOING,
            timestamp = t1,
            durationSeconds = 60
        )
        val call2 = CrmCallRecord(
            id = "c2",
            callerName = "Client",
            phoneNumber = "+919730193399",
            callType = CallType.OUTGOING,
            timestamp = t2,
            durationSeconds = 58
        )

        val onlyOneFile = CallRecordingCandidate(
            fileName = "Call recording +919730193399_261121_150002.m4a",
            uriOrPath = "content://media/external/audio/media/999",
            lastModifiedMs = t1 + 62_000L,
            durationMs = 60_000L,
            isContentUri = true
        )

        val matches = CallRecordingMatcher.matchRecordingsStrictOneToOne(
            calls = listOf(call1, call2),
            candidates = listOf(onlyOneFile),
            timeZone = istZone
        )

        // call1 cost = 2_000 + 0 = 2_000; call2 cost = 16_000 + 2_000 = 18_000
        assertEquals(1, matches.size)
        assertEquals("content://media/external/audio/media/999", matches["c1"]?.uriOrPath)
        assertNull(matches["c2"])
    }

    @Test
    fun normalizeEpochMillis_convertsMediaStoreSecondsToMillis() {
        val sec = 1_795_213_760L
        assertEquals(1_795_213_760_000L, CallRecordingMatcher.normalizeEpochMillis(sec))
        assertEquals(1_795_213_760_123L, CallRecordingMatcher.normalizeEpochMillis(1_795_213_760_123L))
    }

    @Test
    fun acceptance1_bldSaurabhTak_selectsCorrectNov21RecordingAndRejectsMayMantraRecording() {
        // Incoming call, 21 Nov 2026, call log 1m 23s (83s), answered ~4s after ring started
        val callStart = localMillis(2026, 11, 21, 13, 21, 20)
        val callDurationSec = 83
        val callEnd = callStart + callDurationSec * 1000L

        val call = CrmCallRecord(
            id = "saurabh_tak_call",
            callerName = "Bld Saurabh Tak",
            phoneNumber = "+91 98220 44556",
            callType = CallType.INCOMING,
            timestamp = callStart,
            durationSeconds = callDurationSec
        )

        val correctNov21 = CallRecordingCandidate(
            fileName = "Call recording Bld Saurabh Tak_261121_132124.m4a",
            uriOrPath = "content://com.android.externalstorage.documents/document/primary%3ARecordings%2FCall%2Fsaurabh.m4a",
            lastModifiedMs = localMillis(2026, 11, 21, 13, 22, 44), // file end time
            durationMs = 80_000L, // 1m 20s (3s difference from 1m 23s call log is acceptable)
            sizeBytes = 640_000L,
            isContentUri = true
        )

        // Wrong May recording, even with modified lastModifiedMs stamped to today's call end!
        val wrongMayRecording = CallRecordingCandidate(
            fileName = "Call recording Bld Sm Ujwal Hawa Mantra Leasing_260523_112501.m4a",
            uriOrPath = "content://com.android.externalstorage.documents/document/primary%3ARecordings%2FCall%2Fmantra.m4a",
            lastModifiedMs = callEnd, // Tampered/copied modification date matching today
            durationMs = 83_000L,
            sizeBytes = 650_000L,
            isContentUri = true
        )

        // 1. Both present -> picks correct Bld Saurabh Tak recording
        val matchBoth = CallRecordingMatcher.matchRecordingFromCandidates(
            call = call,
            candidates = listOf(wrongMayRecording, correctNov21),
            timeZone = istZone
        )
        assertNotNull(matchBoth)
        assertEquals("Call recording Bld Saurabh Tak_261121_132124.m4a", matchBoth?.fileName)

        // 2. Wrong May recording is the ONLY file (even with today's lastModifiedMs) -> rejected
        val onlyWrongOutcome = CallRecordingMatcher.matchRecordingOutcomeFromCandidates(
            call = call,
            candidates = listOf(wrongMayRecording),
            timeZone = istZone
        )
        assertEquals(CallRecordingMatchStatus.NO_MATCH, onlyWrongOutcome.status)
        assertNull(onlyWrongOutcome.matched)

        // 3. Even same caller name from May with tampered lastModifiedMs -> rejected (name alone never links old file)
        val oldSameCallerFile = CallRecordingCandidate(
            fileName = "Call recording Bld Saurabh Tak_260523_112501.m4a",
            uriOrPath = "content://com.android.externalstorage.documents/document/primary%3ARecordings%2FCall%2Fsaurabh_old.m4a",
            lastModifiedMs = callEnd,
            durationMs = 80_000L,
            sizeBytes = 640_000L,
            isContentUri = true
        )
        assertNull(CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(oldSameCallerFile), istZone))

        // 4. Within two-minute timing tolerance (e.g. 45s ring delay before answer) -> still matches
        val delayedRingCall = call.copy(
            id = "saurabh_tak_ring_delay",
            timestamp = localMillis(2026, 11, 21, 13, 20, 39) // 45s before 13:21:24
        )
        val delayedMatch = CallRecordingMatcher.matchRecordingFromCandidates(
            call = delayedRingCall,
            candidates = listOf(wrongMayRecording, correctNov21),
            timeZone = istZone
        )
        assertEquals("Call recording Bld Saurabh Tak_261121_132124.m4a", delayedMatch?.fileName)
    }

    @Test
    fun acceptance2_satishFukeTwoCalls_eachSelectsOwnRecordingInForwardAndReverseOrder() {
        val earlierCallStart = localMillis(2026, 11, 21, 13, 34, 18)
        val earlierCall = CrmCallRecord(
            id = "satish_call_1",
            callerName = "Satish Fuke",
            phoneNumber = "+91 98230 11223",
            callType = CallType.OUTGOING,
            timestamp = earlierCallStart,
            durationSeconds = 178 // ~2m 58s
        )

        val laterCallStart = localMillis(2026, 11, 21, 13, 37, 29)
        val laterCall = CrmCallRecord(
            id = "satish_call_2",
            callerName = "Satish Fuke",
            phoneNumber = "+91 98230 11223",
            callType = CallType.OUTGOING,
            timestamp = laterCallStart,
            durationSeconds = 462 // ~7m 42s
        )

        val earlierFile = CallRecordingCandidate(
            fileName = "Call recording Satish Fuke_261121_133420.m4a",
            uriOrPath = "content://media/external/audio/media/2001",
            lastModifiedMs = localMillis(2026, 11, 21, 13, 37, 17), // ends ~1:37 PM
            durationMs = 176_000L, // 2m 56s
            sizeBytes = 1_400_000L,
            isContentUri = true
        )
        val laterFile = CallRecordingCandidate(
            fileName = "Call recording Satish Fuke_261121_133731.m4a",
            uriOrPath = "content://media/external/audio/media/2002",
            lastModifiedMs = localMillis(2026, 11, 21, 13, 45, 11), // Samsung My Files displays 1:45 PM
            durationMs = 460_000L, // 7m 40s
            sizeBytes = 3_680_000L,
            isContentUri = true
        )

        val forwardFiles = listOf(earlierFile, laterFile)
        val reverseFiles = listOf(laterFile, earlierFile)

        // Single-call checks in both orders
        assertEquals(earlierFile.fileName, CallRecordingMatcher.matchRecordingFromCandidates(earlierCall, forwardFiles, istZone)?.fileName)
        assertEquals(laterFile.fileName, CallRecordingMatcher.matchRecordingFromCandidates(laterCall, forwardFiles, istZone)?.fileName)
        assertEquals(earlierFile.fileName, CallRecordingMatcher.matchRecordingFromCandidates(earlierCall, reverseFiles, istZone)?.fileName)
        assertEquals(laterFile.fileName, CallRecordingMatcher.matchRecordingFromCandidates(laterCall, reverseFiles, istZone)?.fileName)

        // Batch 1-to-1 checks in both orders
        val batchForward = CallRecordingMatcher.matchRecordingsStrictOneToOne(listOf(earlierCall, laterCall), forwardFiles, istZone)
        assertEquals(earlierFile.fileName, batchForward["satish_call_1"]?.fileName)
        assertEquals(laterFile.fileName, batchForward["satish_call_2"]?.fileName)

        val batchReverse = CallRecordingMatcher.matchRecordingsStrictOneToOne(listOf(laterCall, earlierCall), reverseFiles, istZone)
        assertEquals(earlierFile.fileName, batchReverse["satish_call_1"]?.fileName)
        assertEquals(laterFile.fileName, batchReverse["satish_call_2"]?.fileName)
    }

    @Test
    fun folderScanAfterFile150_findsRecordingPast150AndReadsDurationOnlyForShortlist() {
        val tempDir = java.nio.file.Files.createTempDirectory("samsung_recordings_test").toFile()
        try {
            val targetStart = localMillis(2026, 11, 21, 13, 21, 24)
            // Create 210 unrelated audio files + 1 target recording well past index 150
            for (i in 1..210) {
                val day = (i % 28) + 1
                val sec = i % 60
                val f = java.io.File(
                    tempDir,
                    String.format(Locale.US, "Call recording Other Client %03d_2605%02d_1010%02d.m4a", i, day, sec)
                )
                f.writeBytes(byteArrayOf(1, 2, 3, 4))
                f.setLastModified(localMillis(2026, 11, 21, 16, 0, 0) + i * 1000L) // newer mtime than target!
            }
            val targetFile = java.io.File(tempDir, "Call recording Bld Saurabh Tak_261121_132124.m4a")
            targetFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
            targetFile.setLastModified(localMillis(2026, 11, 21, 13, 22, 44)) // older mtime so sorting would put it > 150!

            val scanned = CallRecordingMatcher.scanDirectoryCandidates(tempDir)
            assertEquals(211, scanned.size)
            assertTrue(scanned.any { it.fileName == targetFile.name })

            val call = CrmCallRecord(
                id = "after_150_call",
                callerName = "Bld Saurabh Tak",
                phoneNumber = "+91 98220 44556",
                callType = CallType.INCOMING,
                timestamp = targetStart - 2_000L,
                durationSeconds = 83
            )

            // Verify duration resolver is invoked ONLY for shortlisted candidate(s), NOT all 211 files
            val probedFiles = mutableListOf<String>()
            val enriched = CallRecordingMatcher.resolveMissingDurationsForRelevantCandidates(
                calls = listOf(call),
                candidates = scanned,
                timeZone = istZone,
                durationResolver = { cand ->
                    probedFiles.add(cand.fileName)
                    if (cand.fileName == targetFile.name) 80_000L else 60_000L
                }
            )
            assertEquals(listOf(targetFile.name), probedFiles)

            val match = CallRecordingMatcher.matchRecordingFromCandidates(call, enriched, istZone)
            assertNotNull(match)
            assertEquals(targetFile.name, match?.fileName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun conflictingCallersAtSimilarTime_andGenericLabels_areHardRejected() {
        val callStart = localMillis(2026, 11, 21, 13, 21, 20)
        val call = CrmCallRecord(
            id = "conflict_1",
            callerName = "Bld Saurabh Tak",
            phoneNumber = "+91 98220 44556",
            callType = CallType.INCOMING,
            timestamp = callStart,
            durationSeconds = 83
        )

        // Different caller sharing only generic prefix "Bld" at almost the EXACT same second and duration
        val conflictingBldCaller = CallRecordingCandidate(
            fileName = "Call recording Bld Sm Ujwal Hawa Mantra Leasing_261121_132122.m4a",
            uriOrPath = "content://media/external/audio/media/3001",
            lastModifiedMs = callStart + 84_000L,
            durationMs = 82_000L,
            sizeBytes = 640_000L,
            isContentUri = true
        )
        assertNull(CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(conflictingBldCaller), istZone))

        // Different surname sharing first name "Saurabh" at exact same second -> rejected
        val conflictingSurname = CallRecordingCandidate(
            fileName = "Call recording Saurabh Joshi_261121_132120.m4a",
            uriOrPath = "content://media/external/audio/media/3002",
            lastModifiedMs = callStart + 83_000L,
            durationMs = 83_000L,
            sizeBytes = 640_000L,
            isContentUri = true
        )
        assertNull(CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(conflictingSurname), istZone))

        // Generic tokens (Bld, CP, TLC, Owner, Broker) are stripped from meaningful tokens
        val genericTokens = CallRecordingMatcher.extractMeaningfulNameTokens("Bld CP TLC Owner Broker")
        assertTrue(genericTokens.isEmpty())

        // Date/time digits must NEVER leak into phone number extraction
        val dateDigitsMeta = CallRecordingMatcher.parseSamsungRecordingMetadata(
            fileName = "Call recording Bld_261121_132124.m4a",
            timeZone = istZone
        )
        assertNull(dateDigitsMeta.filePhoneLast10)
        assertFalse(dateDigitsMeta.hasPhoneDigits)
    }

    @Test
    fun ambiguousMatches_returnAmbiguousOutcomeInsteadOfAutoSelectingWrongFile() {
        val callStart = localMillis(2026, 11, 21, 13, 34, 22)
        val call = CrmCallRecord(
            id = "ambiguous_call",
            callerName = "Satish Fuke",
            phoneNumber = "+91 98230 11223",
            callType = CallType.OUTGOING,
            timestamp = callStart,
            durationSeconds = 177
        )

        val candidateA = CallRecordingCandidate(
            fileName = "Call recording Satish Fuke_261121_133420.m4a",
            uriOrPath = "content://media/external/audio/media/4001",
            lastModifiedMs = localMillis(2026, 11, 21, 13, 37, 16),
            durationMs = 176_000L, // Δt=2s, Δd=1s -> cost 3s
            sizeBytes = 1_400_000L,
            isContentUri = true
        )
        val candidateB = CallRecordingCandidate(
            fileName = "Call recording Satish Fuke_261121_133425.m4a",
            uriOrPath = "content://media/external/audio/media/4002",
            lastModifiedMs = localMillis(2026, 11, 21, 13, 37, 23),
            durationMs = 178_000L, // Δt=3s, Δd=1s -> cost 4s (within 10s ambiguity margin)
            sizeBytes = 1_420_000L,
            isContentUri = true
        )

        val outcome = CallRecordingMatcher.matchRecordingOutcomeFromCandidates(
            call = call,
            candidates = listOf(candidateA, candidateB),
            timeZone = istZone
        )
        assertEquals(CallRecordingMatchStatus.AMBIGUOUS, outcome.status)
        assertNull(outcome.matched)
        assertEquals(2, outcome.ambiguousCandidates.size)
        assertNull(CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(candidateA, candidateB), istZone))
    }

    @Test
    fun otherOemTimestampedRecording_xiaomiFormatMatchesCleanly() {
        val callStart = localMillis(2026, 11, 21, 13, 34, 20)
        val call = CrmCallRecord(
            id = "xiaomi_call",
            callerName = "Satish Fuke",
            phoneNumber = "+91 98220 11223",
            callType = CallType.INCOMING,
            timestamp = callStart,
            durationSeconds = 176
        )
        val xiaomiFile = CallRecordingCandidate(
            fileName = "Satish Fuke(9822011223)_20261121133422.mp3",
            uriOrPath = "/storage/emulated/0/MIUI/sound_recorder/call_rec/Satish Fuke(9822011223)_20261121133422.mp3",
            lastModifiedMs = callStart + 178_000L,
            durationMs = 175_000L,
            sizeBytes = 1_200_000L,
            isContentUri = false
        )
        val match = CallRecordingMatcher.matchRecordingFromCandidates(call, listOf(xiaomiFile), istZone)
        assertNotNull(match)
        assertEquals(xiaomiFile.fileName, match?.fileName)
    }

    @Test
    fun strictIdentityCheck_rejectsSuspectedSpamOrUnlabelledFileForNamedClientCall() {
        val callStart = localMillis(2026, 11, 21, 13, 21, 24)
        val namedClientCall = CrmCallRecord(
            id = "named_client_exact_ts",
            callerName = "Bld Saurabh Tak",
            phoneNumber = "+91 98220 44556",
            callType = CallType.INCOMING,
            timestamp = callStart,
            durationSeconds = 80
        )

        val suspectedSpamSameSecond = CallRecordingCandidate(
            fileName = "Call recording Suspected Spam_261121_132124.m4a",
            uriOrPath = "content://media/external/audio/media/6001",
            lastModifiedMs = callStart + 80_000L,
            durationMs = 80_000L,
            sizeBytes = 640_000L,
            isContentUri = true
        )
        val unknownSameSecond = CallRecordingCandidate(
            fileName = "Call recording Unknown_261121_132124.m4a",
            uriOrPath = "content://media/external/audio/media/6002",
            lastModifiedMs = callStart + 80_000L,
            durationMs = 80_000L,
            sizeBytes = 640_000L,
            isContentUri = true
        )

        // Exact same second and duration, but filename has no matching caller name/phone -> MUST NOT attach
        assertNull(
            CallRecordingMatcher.matchRecordingFromCandidates(
                namedClientCall,
                listOf(suspectedSpamSameSecond, unknownSameSecond),
                istZone
            )
        )
    }

    @Test
    fun delayedBatchMultiCallPass_matchesAllValidCallsOnceAndLeavesWrongOrMissedUnmatched() {
        val saurabhCall = CrmCallRecord(
            id = "batch_saurabh",
            callerName = "Bld Saurabh Tak",
            phoneNumber = "+91 98220 44556",
            callType = CallType.INCOMING,
            timestamp = localMillis(2026, 11, 21, 13, 21, 20),
            durationSeconds = 83
        )
        val satishCall1 = CrmCallRecord(
            id = "batch_satish_1",
            callerName = "Satish Fuke",
            phoneNumber = "+91 98230 11223",
            callType = CallType.OUTGOING,
            timestamp = localMillis(2026, 11, 21, 13, 34, 18),
            durationSeconds = 178
        )
        val satishCall2 = CrmCallRecord(
            id = "batch_satish_2",
            callerName = "Satish Fuke",
            phoneNumber = "+91 98230 11223",
            callType = CallType.OUTGOING,
            timestamp = localMillis(2026, 11, 21, 13, 37, 29),
            durationSeconds = 462
        )
        val conflictingCallerCall = CrmCallRecord(
            id = "batch_unmatched_client",
            callerName = "Rohan Deshmukh",
            phoneNumber = "+91 97650 99887",
            callType = CallType.INCOMING,
            timestamp = localMillis(2026, 11, 21, 14, 10, 0),
            durationSeconds = 95
        )
        val missedCall = CrmCallRecord(
            id = "batch_missed",
            callerName = "Missed Lead",
            phoneNumber = "+91 98220 44556",
            callType = CallType.MISSED,
            timestamp = localMillis(2026, 11, 21, 13, 21, 22),
            durationSeconds = 0
        )

        val candidates = listOf(
            CallRecordingCandidate(
                fileName = "Call recording Bld Sm Ujwal Hawa Mantra Leasing_260523_112501.m4a",
                uriOrPath = "content://media/external/audio/media/7000",
                lastModifiedMs = localMillis(2026, 11, 21, 13, 22, 43),
                durationMs = 83_000L,
                isContentUri = true
            ),
            CallRecordingCandidate(
                fileName = "Call recording Bld Saurabh Tak_261121_132124.m4a",
                uriOrPath = "content://media/external/audio/media/7001",
                lastModifiedMs = localMillis(2026, 11, 21, 13, 22, 44),
                durationMs = 80_000L,
                isContentUri = true
            ),
            CallRecordingCandidate(
                fileName = "Call recording Satish Fuke_261121_133420.m4a",
                uriOrPath = "content://media/external/audio/media/7002",
                lastModifiedMs = localMillis(2026, 11, 21, 13, 37, 17),
                durationMs = 176_000L,
                isContentUri = true
            ),
            CallRecordingCandidate(
                fileName = "Call recording Satish Fuke_261121_133731.m4a",
                uriOrPath = "content://media/external/audio/media/7003",
                lastModifiedMs = localMillis(2026, 11, 21, 13, 45, 11),
                durationMs = 460_000L,
                isContentUri = true
            ),
            CallRecordingCandidate(
                fileName = "Call recording Another Broker_261121_141002.m4a",
                uriOrPath = "content://media/external/audio/media/7004",
                lastModifiedMs = localMillis(2026, 11, 21, 14, 11, 37),
                durationMs = 95_000L,
                isContentUri = true
            )
        )

        val outcomes = CallRecordingMatcher.matchRecordingsWithOutcomes(
            calls = listOf(saurabhCall, satishCall1, satishCall2, conflictingCallerCall, missedCall),
            candidates = candidates,
            timeZone = istZone
        )

        assertEquals(CallRecordingMatchStatus.MATCHED, outcomes["batch_saurabh"]?.status)
        assertEquals("Call recording Bld Saurabh Tak_261121_132124.m4a", outcomes["batch_saurabh"]?.matched?.fileName)

        assertEquals(CallRecordingMatchStatus.MATCHED, outcomes["batch_satish_1"]?.status)
        assertEquals("Call recording Satish Fuke_261121_133420.m4a", outcomes["batch_satish_1"]?.matched?.fileName)

        assertEquals(CallRecordingMatchStatus.MATCHED, outcomes["batch_satish_2"]?.status)
        assertEquals("Call recording Satish Fuke_261121_133731.m4a", outcomes["batch_satish_2"]?.matched?.fileName)

        assertEquals(CallRecordingMatchStatus.NO_MATCH, outcomes["batch_unmatched_client"]?.status)
        assertNull(outcomes["batch_unmatched_client"]?.matched)

        assertEquals(CallRecordingMatchStatus.SKIPPED_ZERO_CALL, outcomes["batch_missed"]?.status)
        assertNull(outcomes["batch_missed"]?.matched)
    }
}

