package com.example.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrmLeadCardActionTest {
    private lateinit var context: Context
    private val lead = CrmLead("lead-a", "Test client", "TEST-NUMBER", "Commercial", "Lease office", "Kharadi", "50000", "Details Shared", "", "Original notes", "Sagar")
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE).edit().clear().commit()
        saveCrmLeads(context, listOf(lead, lead.copy(id = "other", name = "Other client")))
    }
    @Test fun logPreservesOtherLeadsUnknownFieldsNotesStageAndAssignment() {
        val prefs = context.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE)
        val all = JSONArray(prefs.getString("leads", "[]"))
        all.getJSONObject(0).put("legacyField", "retain")
        prefs.edit().putString("leads", all.toString()).commit()
        val result = saveLeadCardAction(context, lead, "No answer", "Retry tomorrow", "2026-11-13", 1000000L)
        assertTrue(result.isSuccess)
        val saved = readCrmLeads(context)
        assertEquals(2, saved.size)
        assertEquals("Other client", saved[1].name)
        assertEquals(lead.status, saved[0].status)
        assertEquals(lead.assignedTo, saved[0].assignedTo)
        assertTrue(saved[0].notes.startsWith("Original notes"))
        assertEquals("retain", JSONArray(prefs.getString("leads", "[]")).getJSONObject(0).getString("legacyField"))
        assertEquals("Call: No answer", readLeadCardActivities(context, lead.id).single().getString("type"))
    }
    @Test fun followUpTimeSurvivesReloadAndInvalidTimeDoesNotWrite() {
        assertTrue(saveLeadCardAction(context, lead, null, "", "2026-11-13", followUpTime = "25:00").isFailure)
        assertEquals(lead, readCrmLeads(context).first().copy(updatedBy = "", updatedAt = ""))
        assertTrue(saveLeadCardAction(context, lead, null, "", "2026-11-13", followUpTime = "14:35").isSuccess)
        assertEquals("14:35", readCrmLeads(context).first().followUpTime)
        assertEquals("14:35", readLeadCardActivities(context, lead.id).single().getString("followUpTime"))
    }

    @Test fun damagedLeadJsonIsNotReplaced() {
        val prefs = context.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE)
        prefs.edit().putString("leads", "[broken").commit()
        assertTrue(saveLeadCardAction(context, lead, "Busy", "note", "").isFailure)
        assertEquals("[broken", prefs.getString("leads", ""))
        assertTrue(readLeadCardActivities(context, lead.id).isEmpty())
    }
    @Test fun staleCardCannotOverwriteNewerLead() {
        context.getSharedPreferences("real_estate_user_settings",0).edit().putString("user_role","Admin").commit()
        saveCrmLeads(context, listOf(lead.copy(assignedTo = "Himanshu")))
        assertTrue(saveLeadCardAction(context, lead, null, "note", "2026-11-13").isFailure)
        assertEquals("Himanshu", readCrmLeads(context).single().assignedTo)
    }
    @Test fun invalidDateDoesNotWriteAndFollowUpOnlyDoesNotInventCall() {
        assertTrue(saveLeadCardAction(context, lead, null, "", "2026-02-30").isFailure)
        assertEquals(lead, readCrmLeads(context).first().copy(updatedBy = "", updatedAt = ""))
        assertTrue(saveLeadCardAction(context, lead, null, "", "2026-11-13").isSuccess)
        assertEquals("Follow-up updated", readLeadCardActivities(context, lead.id).single().getString("type"))
    }
    @Test fun damagedActivityHistoryBlocksWriteAndRemainsIntact() {
        val prefs = context.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE)
        prefs.edit().putString("card_activities_${lead.id}", "[bad").commit()
        assertTrue(saveLeadCardAction(context, lead, "Connected", "note", "").isFailure)
        assertEquals(lead, readCrmLeads(context).first().copy(updatedBy = "", updatedAt = ""))
        assertEquals("[bad", prefs.getString("card_activities_${lead.id}", ""))
    }
}

