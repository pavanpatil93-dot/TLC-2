package com.example.crm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrmCallManagerTest {

    @Test
    fun testSaveAndReadCalls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val newCall = CrmCallRecord(
            id = "test_call_101",
            callerName = "Aarav Sharma",
            phoneNumber = "9876543210",
            callType = CallType.INCOMING,
            durationSeconds = 120,
            notes = "Inquired about 2,000 sq.ft Kharadi office."
        )

        CrmCallManager.addOrUpdateCall(context, newCall)

        val calls = CrmCallManager.readCalls(context)
        assertTrue(calls.any { it.id == "test_call_101" })

        val saved = calls.first { it.id == "test_call_101" }
        assertEquals("Aarav Sharma", saved.callerName)
        assertEquals("9876543210", saved.phoneNumber)
        assertEquals(CallType.INCOMING, saved.callType)
        assertFalse(saved.isLinked)
    }

    @Test
    fun testLinkCallToOpportunity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val call = CrmCallRecord(
            id = "test_call_202",
            callerName = "Dr. Kulkarni",
            phoneNumber = "9860011223",
            callType = CallType.MISSED,
            notes = "Missed call regarding 3 BHK purchase."
        )

        CrmCallManager.addOrUpdateCall(context, call)

        CrmCallManager.linkCallToOpportunity(
            context = context,
            callId = "test_call_202",
            oppType = "Client",
            oppTitle = "Dr. Kulkarni • 3 BHK Purchase",
            oppId = "opp_303"
        )

        val updatedCalls = CrmCallManager.readCalls(context)
        val linked = updatedCalls.first { it.id == "test_call_202" }

        assertTrue(linked.isLinked)
        assertEquals("Client", linked.linkedOpportunityType)
        assertEquals("Dr. Kulkarni • 3 BHK Purchase", linked.linkedOpportunityTitle)
        assertEquals("opp_303", linked.linkedOpportunityId)

        // Verify Activity Log was recorded automatically
        val activities = CrmCallManager.readActivityLogs(context, "opp_303")
        assertEquals(1, activities.size)
        assertEquals("Opportunity Created", activities[0].type)
        assertTrue(activities[0].summary.contains("Dr. Kulkarni"))
    }

    @Test
    fun testFormatDuration() {
        assertEquals("0s", CrmCallManager.formatDuration(0))
        assertEquals("45s", CrmCallManager.formatDuration(45))
        assertEquals("2m 30s", CrmCallManager.formatDuration(150))
    }
}

