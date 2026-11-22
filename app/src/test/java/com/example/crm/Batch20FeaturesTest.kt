package com.example.crm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiRealEstateService
import com.example.data.UserSettingsManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class Batch20FeaturesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "skipped_call_ids.json").delete()
        File(context.filesDir, "unlogged_calls_queue.json").delete()
        File(context.filesDir, "crm_call_logs_v1.json").delete()
    }

    @Test
    fun skippedCalls_areExcludedFromUnloggedQueue_butCountedInTotalCallsForEod() {
        val call1 = CrmCallRecord(
            id = "call_q_1",
            callerName = "Rohan Kulkarni",
            phoneNumber = "9822011111",
            callType = CallType.INCOMING,
            durationSeconds = 45,
            timestamp = 1_763_400_000_000L,
            notes = "",
            isLinked = false
        )
        val call2Short = CrmCallRecord(
            id = "call_q_2",
            callerName = "Short Call Caller",
            phoneNumber = "9822022222",
            callType = CallType.OUTGOING,
            durationSeconds = 12, // <20s still counted in EOD total
            timestamp = 1_763_400_050_000L,
            notes = "",
            isLinked = false
        )
        CrmCallManager.saveCalls(context, listOf(call1, call2Short))
        CrmCallManager.enqueueUnloggedCall(context, call1)
        CrmCallManager.enqueueUnloggedCall(context, call2Short)

        assertEquals(2, CrmCallManager.getUnloggedCallQueue(context).size)

        // Skip call1
        CrmCallManager.markCallSkipped(context, call1)
        assertTrue(CrmCallManager.isCallSkipped(context, call1))
        assertFalse(CrmCallManager.isCallSkipped(context, call2Short))

        // Active unlogged queue excludes call1
        val remainingQueue = CrmCallManager.getUnloggedCallQueue(context)
        assertEquals(1, remainingQueue.size)
        assertEquals("call_q_2", remainingQueue.first().id)

        // Re-enqueuing a skipped call does not add it back to the active Needs Logging queue
        CrmCallManager.enqueueUnloggedCall(context, call1)
        assertEquals(1, CrmCallManager.getUnloggedCallQueue(context).size)

        // All calls (including <20s and skipped) remain in readCalls for EOD Calls Total
        val allCalls = CrmCallManager.readCalls(context)
        assertEquals(2, allCalls.size)
    }

    @Test
    fun pendingCallNavigation_mapsAllRolesAndPrefillsFieldsCleanly() {
        val sampleCall = CrmCallRecord(
            id = "call_nav_1",
            callerName = "Vikram Deshmukh",
            phoneNumber = "+91 98230 55443",
            callType = CallType.INCOMING,
            durationSeconds = 85,
            timestamp = 1_763_400_100_000L,
            notes = "Looking for 3500 sq.ft office in Baner budget 2.5 Lac",
            isLinked = true
        )

        val leadNav = PendingCallNavigation.fromPostCallAction(
            role = PostCallRole.CLIENT_LEAD,
            call = sampleCall,
            prefilledName = "Vikram Deshmukh",
            prefilledNotes = "Looking for 3500 sq.ft office in Baner",
            linkedEntityId = "LD-101"
        )
        assertEquals(PendingCallNavigation.ROUTE_ADD_EDIT_LEAD, leadNav.targetRoute)
        assertEquals("Vikram Deshmukh", leadNav.prefillName)
        assertEquals("9823055443", leadNav.prefillPhone)
        assertEquals("LD-101", leadNav.taggedPropertyId)

        val ownerNav = PendingCallNavigation.fromPostCallAction(
            role = PostCallRole.PROPERTY_OWNER,
            call = sampleCall,
            prefilledName = "Owner Deshmukh",
            prefilledNotes = "Unit 402 available at 95/sqft",
            linkedEntityId = "TLC-2603-C001"
        )
        assertEquals(PendingCallNavigation.ROUTE_ADD_EDIT_PROPERTY, ownerNav.targetRoute)
        assertEquals("TLC-2603-C001", ownerNav.taggedPropertyId)

        val tenantNav = PendingCallNavigation.fromPostCallAction(
            role = PostCallRole.TENANT,
            call = sampleCall,
            prefilledName = "Tenant Corp",
            prefilledNotes = "AC maintenance request",
            linkedEntityId = "TNT-009"
        )
        assertEquals(PendingCallNavigation.ROUTE_ADD_EDIT_TENANT, tenantNav.targetRoute)
        assertEquals("TNT-009", tenantNav.taggedPropertyId)

        val opsNav = PendingCallNavigation.fromPostCallAction(
            role = PostCallRole.OPS_VENDOR,
            call = sampleCall,
            prefilledName = "CoolAir HVAC",
            prefilledNotes = "Quoted 4500 for servicing",
            linkedEntityId = "BV-202",
            subRole = "HVAC / AC"
        )
        assertEquals(PendingCallNavigation.ROUTE_OPS_VENDOR, opsNav.targetRoute)
        assertEquals("HVAC / AC", opsNav.subRole)
    }

    @Test
    fun missedCallWaCallback_marksCallHandledAndUsesPoliteMessage() {
        assertEquals(
            "Sorry I missed your call from The Leasing Company—calling you back shortly, or feel free to share your requirement here.",
            CrmCallManager.MISSED_CALL_WA_CALLBACK_MESSAGE
        )

        val missedCall = CrmCallRecord(
            id = "missed_101",
            callerName = "Neha Joshi",
            phoneNumber = "9890012345",
            callType = CallType.MISSED,
            durationSeconds = 0,
            timestamp = 1_763_400_200_000L,
            notes = "",
            isLinked = false
        )
        CrmCallManager.saveCalls(context, listOf(missedCall))
        CrmCallManager.enqueueUnloggedCall(context, missedCall)

        val updated = CrmCallManager.triggerMissedCallWaCallback(
            context = context,
            call = missedCall,
            assignedAgent = "Pawan Patil",
            openBrowserOrApp = false
        )

        assertTrue(updated.isLinked)
        assertTrue(updated.waLogged)
        assertEquals("Pawan Patil", updated.assignedAgent)
        assertTrue(updated.notes.contains("WA Callback Sent"))
        assertTrue(CrmCallManager.getUnloggedCallQueue(context).isEmpty())
    }

    @Test
    fun lastCalledByHistory_findsLatestTeamCallAndFormatsBanner() {
        val olderCall = CrmCallRecord(
            id = "call_hist_1",
            callerName = "Gera Owner",
            phoneNumber = "+91 98220 77889",
            callType = CallType.OUTGOING,
            durationSeconds = 60,
            timestamp = 1_763_300_000_000L,
            notes = "Initial availability check",
            isLinked = true,
            linkedOpportunityId = "TLC-2603-C010",
            assignedAgent = "Amit"
        )
        val latestCall = CrmCallRecord(
            id = "call_hist_2",
            callerName = "Gera Owner",
            phoneNumber = "9822077889",
            callType = CallType.OUTGOING,
            durationSeconds = 115,
            timestamp = 1_763_410_000_000L,
            notes = "Confirmed rent 1.25 Lac negotiable",
            isLinked = true,
            linkedOpportunityId = "TLC-2603-C010",
            assignedAgent = "Pawan Patil"
        )
        val candidates = listOf(olderCall, latestCall)

        val foundByPhone = CrmCallManager.findLastCallForTarget(
            context = context,
            phones = listOf("09822077889"),
            entityIds = emptyList(),
            candidateCalls = candidates
        )
        assertNotNull(foundByPhone)
        assertEquals("call_hist_2", foundByPhone?.id)

        val bannerText = CrmCallManager.formatLastCalledByBannerText(foundByPhone!!)
        assertTrue(bannerText.startsWith("Last called by Pawan Patil · "))
        assertTrue(bannerText.endsWith("· 'Confirmed rent 1.25 Lac negotiable'"))
    }

    @Test
    fun callRecordingMatcher_matchesStrictOneToOneBySamsungMetadata() = runBlocking {
        // Verify OEM defaults exist for Samsung, Xiaomi, OnePlus/Oppo
        val paths = CallRecordingMatcher.OEM_DEFAULT_RELATIVE_PATHS.map { it.first }
        assertTrue(paths.contains("Recordings/Call"))
        assertTrue(paths.contains("MIUI/sound_recorder/call_rec"))
        assertTrue(paths.contains("Music/Recordings/Call Recordings"))

        val tz = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        val callStart = java.util.Calendar.getInstance(tz, java.util.Locale.US).apply {
            clear()
            set(2026, 10, 18, 14, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val callDurationSec = 60
        val callEnd = callStart + callDurationSec * 1000L
        val call = CrmCallRecord(
            id = "rec_call_1",
            callerName = "Kharadi Client",
            phoneNumber = "9766880099",
            callType = CallType.INCOMING,
            durationSeconds = callDurationSec,
            timestamp = callStart,
            notes = "Looking for 2500 sq.ft office in Kharadi budget 1.8 Lac"
        )

        val unrelatedWrongPhone = CallRecordingCandidate(
            fileName = "Call recording +919111122233_261118_143000.m4a",
            uriOrPath = "content://media/external/audio/media/101",
            lastModifiedMs = callEnd,
            durationMs = 60_000L,
            sizeBytes = 120_000L,
            isContentUri = true
        )
        val wrongDuration = CallRecordingCandidate(
            fileName = "Call recording +919766880099_261118_143002.m4a",
            uriOrPath = "content://media/external/audio/media/102",
            lastModifiedMs = callStart + 20_000L,
            durationMs = 20_000L, // 40s diff -> rejected
            sizeBytes = 240_000L,
            isContentUri = true
        )
        val exactSamsungMatch = CallRecordingCandidate(
            fileName = "Call recording +919766880099_261118_143003.m4a",
            uriOrPath = "content://media/external/audio/media/103",
            lastModifiedMs = callEnd + 5_000L,
            durationMs = 59_000L,
            sizeBytes = 260_000L,
            isContentUri = true
        )

        val best = CallRecordingMatcher.matchRecordingFromCandidates(
            call = call,
            candidates = listOf(unrelatedWrongPhone, wrongDuration, exactSamsungMatch),
            timeZone = tz
        )
        assertNotNull(best)
        assertEquals("Call recording +919766880099_261118_143003.m4a", best?.fileName)

        // Verify wrong phone / wrong duration never falls back to a wrong file
        val none = CallRecordingMatcher.matchRecordingFromCandidates(
            call = call,
            candidates = listOf(unrelatedWrongPhone, wrongDuration),
            timeZone = tz
        )
        assertNull(none)

        // Verify on-demand summarize fallback writes 2-line summary + Budget/Area/Location
        val summary = GeminiRealEstateService().summarizeCallRecordingOnDemand(
            audioBytes = null,
            audioMimeType = "audio/mp4",
            audioFileName = "Call_9766880099_20261118.m4a",
            callerName = call.callerName,
            phoneNumber = call.phoneNumber,
            durationSeconds = call.durationSeconds,
            existingNotes = call.notes
        )
        assertTrue(summary.contains("Budget:"))
        assertTrue(summary.contains("Area:"))
        assertTrue(summary.contains("Location:"))
        assertTrue(summary.contains("Kharadi"))

        // Verify UserSettingsManager persists Call Recording Folder URI
        val settings = UserSettingsManager(context)
        settings.setCallRecordingFolder("content://com.android.externalstorage.documents/tree/primary%3ARecordings%2FCall", "Recordings/Call")
        assertEquals("Recordings/Call", settings.callRecordingFolderLabel.value)
    }

    @Test
    fun samsungCallRecordingMatcher_handlesBldSaurabhTak_satishFukePair_andAmbiguousMatches() {
        val tz = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        fun ts(y: Int, m: Int, d: Int, hh: Int, mm: Int, ss: Int): Long =
            java.util.Calendar.getInstance(tz, java.util.Locale.US).apply {
                isLenient = false
                clear()
                set(y, m - 1, d, hh, mm, ss)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

        // 1. Bld Saurabh Tak (1m 23s) vs May Bld Sm Ujwal Hawa Mantra Leasing
        val saurabhCall = CrmCallRecord(
            id = "b20_saurabh",
            callerName = "Bld Saurabh Tak",
            phoneNumber = "9822044556",
            callType = CallType.INCOMING,
            durationSeconds = 83,
            timestamp = ts(2026, 11, 21, 13, 21, 20)
        )
        val saurabhCorrect = CallRecordingCandidate(
            fileName = "Call recording Bld Saurabh Tak_261121_132124.m4a",
            uriOrPath = "content://media/external/audio/media/501",
            lastModifiedMs = ts(2026, 11, 21, 13, 22, 44),
            durationMs = 80_000L,
            sizeBytes = 600_000L,
            isContentUri = true
        )
        val mantraMayWrong = CallRecordingCandidate(
            fileName = "Call recording Bld Sm Ujwal Hawa Mantra Leasing_260523_112501.m4a",
            uriOrPath = "content://media/external/audio/media/502",
            lastModifiedMs = ts(2026, 11, 21, 13, 22, 43), // even if modified today
            durationMs = 83_000L,
            sizeBytes = 610_000L,
            isContentUri = true
        )
        assertEquals(
            saurabhCorrect.fileName,
            CallRecordingMatcher.matchRecordingFromCandidates(saurabhCall, listOf(mantraMayWrong, saurabhCorrect), tz)?.fileName
        )
        assertNull(
            CallRecordingMatcher.matchRecordingFromCandidates(saurabhCall, listOf(mantraMayWrong), tz)
        )

        // 2. Satish Fuke two calls on 21 Nov 2026 (forward & reverse order)
        val satish1 = CrmCallRecord(
            id = "b20_satish_1",
            callerName = "Satish Fuke",
            phoneNumber = "9823011223",
            callType = CallType.OUTGOING,
            durationSeconds = 178,
            timestamp = ts(2026, 11, 21, 13, 34, 18)
        )
        val satish2 = CrmCallRecord(
            id = "b20_satish_2",
            callerName = "Satish Fuke",
            phoneNumber = "9823011223",
            callType = CallType.OUTGOING,
            durationSeconds = 462,
            timestamp = ts(2026, 11, 21, 13, 37, 29)
        )
        val satishFile1 = CallRecordingCandidate(
            fileName = "Call recording Satish Fuke_261121_133420.m4a",
            uriOrPath = "content://media/external/audio/media/503",
            lastModifiedMs = ts(2026, 11, 21, 13, 37, 17),
            durationMs = 176_000L,
            sizeBytes = 1_400_000L,
            isContentUri = true
        )
        val satishFile2 = CallRecordingCandidate(
            fileName = "Call recording Satish Fuke_261121_133731.m4a",
            uriOrPath = "content://media/external/audio/media/504",
            lastModifiedMs = ts(2026, 11, 21, 13, 45, 11),
            durationMs = 460_000L,
            sizeBytes = 3_680_000L,
            isContentUri = true
        )
        val reverseResult = CallRecordingMatcher.matchRecordingsStrictOneToOne(
            calls = listOf(satish2, satish1),
            candidates = listOf(satishFile2, satishFile1),
            timeZone = tz
        )
        assertEquals(satishFile1.fileName, reverseResult["b20_satish_1"]?.fileName)
        assertEquals(satishFile2.fileName, reverseResult["b20_satish_2"]?.fileName)
    }

    @Test
    fun unwantedCallAutoTagging_detectsSpamPersonalAndShort_whileProtectingRealAndLinkedCalls() {
        val spam140 = CrmCallRecord(
            id = "u_spam_140",
            callerName = "Unknown",
            phoneNumber = "+91 1409876543",
            callType = CallType.INCOMING,
            durationSeconds = 35,
            timestamp = 1_763_400_000_000L
        )
        val spamNamed = CrmCallRecord(
            id = "u_spam_named",
            callerName = "Suspected Spam HDFC Loan",
            phoneNumber = "9822000111",
            callType = CallType.INCOMING,
            durationSeconds = 25,
            timestamp = 1_763_400_010_000L
        )
        val personalCall = CrmCallRecord(
            id = "u_personal",
            callerName = "Mom Home",
            phoneNumber = "9822000222",
            callType = CallType.OUTGOING,
            durationSeconds = 120,
            timestamp = 1_763_400_020_000L
        )
        val shortCall = CrmCallRecord(
            id = "u_short",
            callerName = "New Inquiry",
            phoneNumber = "9822000333",
            callType = CallType.INCOMING,
            durationSeconds = 6,
            timestamp = 1_763_400_030_000L
        )
        val missedCall = CrmCallRecord(
            id = "u_missed",
            callerName = "Callback Lead",
            phoneNumber = "9822000444",
            callType = CallType.MISSED,
            durationSeconds = 0,
            timestamp = 1_763_400_040_000L
        )
        val realBusinessCall = CrmCallRecord(
            id = "u_real",
            callerName = "Bld Saurabh Tak",
            phoneNumber = "+91 98220 44556",
            callType = CallType.INCOMING,
            durationSeconds = 83,
            timestamp = 1_763_400_050_000L
        )
        val linkedShortCall = shortCall.copy(id = "u_linked_short", isLinked = true)

        assertEquals(UnwantedCallReason.SPAM_TELEMARKETER, CrmCallManager.classifyUnwantedCall(context, spam140))
        assertEquals(UnwantedCallReason.SPAM_TELEMARKETER, CrmCallManager.classifyUnwantedCall(context, spamNamed))
        assertEquals(UnwantedCallReason.PERSONAL_OR_IGNORED, CrmCallManager.classifyUnwantedCall(context, personalCall))
        assertEquals(UnwantedCallReason.SHORT_OR_MISSED, CrmCallManager.classifyUnwantedCall(context, shortCall))
        assertEquals(UnwantedCallReason.SHORT_OR_MISSED, CrmCallManager.classifyUnwantedCall(context, missedCall))
        assertEquals(UnwantedCallReason.NONE, CrmCallManager.classifyUnwantedCall(context, realBusinessCall))
        assertEquals(UnwantedCallReason.NONE, CrmCallManager.classifyUnwantedCall(context, linkedShortCall))
    }

    @Test
    fun bulkHideToIgnoredAndReversibleRestore_updatesSkippedSetCleanly() {
        val c1 = CrmCallRecord(
            id = "bulk_1",
            callerName = "Telemarketer",
            phoneNumber = "1401112222",
            callType = CallType.INCOMING,
            durationSeconds = 12,
            timestamp = 1_763_401_000_000L,
            isLinked = false
        )
        val c2 = CrmCallRecord(
            id = "bulk_2",
            callerName = "Mom",
            phoneNumber = "9822099988",
            callType = CallType.OUTGOING,
            durationSeconds = 55,
            timestamp = 1_763_401_100_000L,
            isLinked = false
        )
        val c3 = CrmCallRecord(
            id = "bulk_3",
            callerName = "Satish Fuke",
            phoneNumber = "9823011223",
            callType = CallType.OUTGOING,
            durationSeconds = 178,
            timestamp = 1_763_401_200_000L,
            isLinked = false
        )
        CrmCallManager.saveCalls(context, listOf(c1, c2, c3))
        CrmCallManager.enqueueUnloggedCall(context, c1)
        CrmCallManager.enqueueUnloggedCall(context, c2)
        CrmCallManager.enqueueUnloggedCall(context, c3)

        // Bulk hide c1 & c2 to Ignored
        CrmCallManager.markCallsSkipped(context, listOf(c1, c2))
        assertTrue(CrmCallManager.isCallSkipped(context, c1))
        assertTrue(CrmCallManager.isCallSkipped(context, c2))
        assertFalse(CrmCallManager.isCallSkipped(context, c3))
        assertEquals(listOf("bulk_3"), CrmCallManager.getUnloggedCallQueue(context).map { it.id })

        // Reversible restore of c2 from Ignored back to active list
        CrmCallManager.unskipCalls(context, listOf(c2))
        assertTrue(CrmCallManager.isCallSkipped(context, c1))
        assertFalse(CrmCallManager.isCallSkipped(context, c2))
        assertFalse(CrmCallManager.isCallSkipped(context, c3))
    }
}

