package com.example.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crm.WhatsAppFollowUpStore
import com.example.model.AvailabilityStatus
import com.example.model.FurnishingType
import com.example.model.InventoryTab
import com.example.model.PropertyItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeminiProductivitySuiteTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("wa_followup_messages.json").delete()
        context.filesDir.resolve("wa_muted_contacts.json").delete()
        context.filesDir.resolve("daily_ai_briefing_cache_v1.json").delete()
    }

    @Test
    fun `hasRealEstateKeyword detects listings and requirements while ignoring casual personal chats`() {
        assertTrue(WhatsAppFollowUpStore.hasRealEstateKeyword("Available 1450 sqft furnished office for lease in Kharadi, rent 1.2L"))
        assertTrue(WhatsAppFollowUpStore.hasRealEstateKeyword("Client looking for 3 BHK flat in Baner budget 1.8 Cr"))
        assertTrue(WhatsAppFollowUpStore.hasRealEstateKeyword("Can we confirm site visit tomorrow at Viman Nagar?"))

        assertFalse(WhatsAppFollowUpStore.hasRealEstateKeyword("Happy birthday bro! Party tonight at 8pm?"))
        assertFalse(WhatsAppFollowUpStore.hasRealEstateKeyword("Reached home safely, talk tomorrow"))
        assertFalse(WhatsAppFollowUpStore.hasRealEstateKeyword("Please bring milk and bread on your way back"))
    }

    @Test
    fun `WhatsApp AI Inbox persists classification, appears in AI Inbox filter, and clears after approval`() = runBlocking {
        val listingMsg = "Available for lease: Commercial Office at EON Free Zone, Kharadi. 2200 sq.ft carpet, fully furnished with 45 workstations, rent 2.1 Lakh/month."
        val reqMsg = "Urgent client requirement: Looking for 3 BHK flat on rent in Wakad, budget 45k monthly, immediate possession."
        val personalMsg = "See you for dinner tonight!"

        WhatsAppFollowUpStore.captureIncomingMessageNotification(
            context = context,
            packageName = "com.whatsapp",
            rawTitle = "Broker Rajesh",
            rawText = listingMsg,
            timestampMs = 1_730_000_000_000L
        )
        WhatsAppFollowUpStore.captureIncomingMessageNotification(
            context = context,
            packageName = "com.whatsapp.w4b",
            rawTitle = "Ananya Deshmukh",
            rawText = reqMsg,
            timestampMs = 1_730_000_001_000L
        )
        WhatsAppFollowUpStore.captureIncomingMessageNotification(
            context = context,
            packageName = "com.whatsapp",
            rawTitle = "Mom",
            rawText = personalMsg,
            timestampMs = 1_730_000_002_000L
        )

        val captured = WhatsAppFollowUpStore.getMessages(context)
        val savedListing = captured.firstOrNull { it.senderName == "Broker Rajesh" }
        val savedReq = captured.firstOrNull { it.senderName == "Ananya Deshmukh" }
        val savedPersonal = captured.firstOrNull { it.senderName == "Mom" }

        assertNotNull(savedListing)
        assertNotNull(savedReq)
        assertNotNull(savedPersonal)
        assertTrue(savedListing!!.aiStatus in setOf("PENDING", "ANALYZED"))
        assertTrue(savedReq!!.aiStatus in setOf("PENDING", "ANALYZED"))
        assertEquals("NONE", savedPersonal!!.aiStatus)

        val aiSvc = GeminiRealEstateService()
        WhatsAppFollowUpStore.analyzeMessageForAiInbox(context, savedListing, aiSvc)
        WhatsAppFollowUpStore.analyzeMessageForAiInbox(context, savedReq, aiSvc)

        val stored = WhatsAppFollowUpStore.getMessages(context)
        val updatedListing = stored.first { it.id == savedListing.id }
        val updatedReq = stored.first { it.id == savedReq.id }
        val updatedPersonal = stored.first { it.id == savedPersonal.id }

        assertEquals("ANALYZED", updatedListing.aiStatus)
        assertEquals("PROPERTY_LISTING", updatedListing.aiIntent)
        assertEquals("ANALYZED", updatedReq.aiStatus)
        assertEquals("CLIENT_REQUIREMENT", updatedReq.aiIntent)
        assertEquals("NONE", updatedPersonal.aiStatus)

        val aiInboxThreads = WhatsAppFollowUpStore.buildCompiledThreads(
            messages = stored,
            filter = "AI Inbox",
            leads = emptyList(),
            inventory = emptyList(),
            brokerContacts = emptyMap(),
            pocLinks = emptyMap()
        )
        assertEquals(2, aiInboxThreads.size)
        assertFalse(aiInboxThreads.any { it.displayName == "Mom" })

        // 1-tap approval removes thread from AI Inbox
        WhatsAppFollowUpStore.markThreadReviewed(context, listOf(savedListing.id))
        val afterApproval = WhatsAppFollowUpStore.buildCompiledThreads(
            messages = WhatsAppFollowUpStore.getMessages(context),
            filter = "AI Inbox",
            leads = emptyList(),
            inventory = emptyList(),
            brokerContacts = emptyMap(),
            pocLinks = emptyMap()
        )
        assertEquals(1, afterApproval.size)
        assertEquals("CLIENT_REQUIREMENT", afterApproval.first().aiIntent)
    }

    @Test
    fun `property share pitch strictly strips confidential owner and broker contacts across all 3 tones`() = runBlocking {
        val sampleProp = PropertyItem(
            id = "prop_1",
            propertyCode = "OL-401",
            location = "Kharadi",
            projectName = "Gera Imperium Alpha",
            propertyType = "Commercial Office",
            tab = InventoryTab.OFF_LEASE,
            transactionType = "Lease",
            carpetAreaSqFt = 1450.0,
            builtUpAreaSqFt = 1900.0,
            priceOrRent = 125000.0,
            rent = 125000.0,
            deposit = 600000.0,
            furnishing = FurnishingType.FULLY_FURNISHED,
            status = AvailabilityStatus.AVAILABLE,
            parking = "2 Covered Car Parks",
            possession = "Immediate",
            ownerName = "Secret Landlord Vikram",
            ownerContact = "9822001122",
            brokerName = "Secret Broker Suresh",
            brokerContact = "9765443322",
            remarks = "Confidential owner will accept 1.15L last price"
        )

        val safeContext = GeminiProductivityEngine.buildPrivacySafePropertyContext(
            properties = listOf(sampleProp),
            employeeName = "Pavan Patil",
            employeeMobile = "9766880099"
        )
        assertTrue(safeContext.contains("Gera Imperium Alpha"))
        assertTrue(safeContext.contains("Kharadi"))
        assertFalse(safeContext.contains("Secret Landlord Vikram"))
        assertFalse(safeContext.contains("9822001122"))
        assertFalse(safeContext.contains("Secret Broker Suresh"))
        assertFalse(safeContext.contains("9765443322"))
        assertFalse(safeContext.contains("Confidential owner"))

        for (tone in AiPitchTone.entries) {
            val draft = GeminiProductivityEngine.draftPropertySharePitch(
                properties = listOf(sampleProp),
                tone = tone,
                audienceLabel = "Client",
                employeeName = "Pavan Patil",
                employeeMobile = "9766880099"
            )
            assertTrue("Draft for ${tone.label} should mention project", draft.text.contains("Gera Imperium Alpha"))
            assertFalse("Draft for ${tone.label} leaked owner name", draft.text.contains("Secret Landlord Vikram"))
            assertFalse("Draft for ${tone.label} leaked owner phone", draft.text.contains("9822001122"))
            assertFalse("Draft for ${tone.label} leaked broker phone", draft.text.contains("9765443322"))
            assertFalse("Draft for ${tone.label} leaked internal remark", draft.text.contains("1.15L last price"))
        }

        val sanitized = GeminiProductivityEngine.sanitizeOutgoingPitch(
            rawText = "Contact 9822001122 or +91 9765443322 for keys",
            properties = listOf(sampleProp)
        )
        assertFalse(sanitized.contains("9822001122"))
        assertFalse(sanitized.contains("9765443322"))
    }

    @Test
    fun `CRM follow-up drafter generates tailored drafts for all scenarios and tones`() = runBlocking {
        for (scenario in CrmReplyScenario.entries) {
            for (tone in AiPitchTone.entries) {
                val res = GeminiProductivityEngine.draftCrmFollowUpMessage(
                    leadName = "Rohan Kulkarni",
                    requirement = "Lease Office 1500 sqft",
                    location = "Baner",
                    budget = "1.5 Lakh/mo",
                    status = "Site Visit Pending",
                    lastNote = "Prefers furnished office near highway",
                    nextFollowUp = "2026-11-22",
                    siteVisitDate = "2026-11-23",
                    scenario = scenario,
                    tone = tone,
                    employeeName = "Pavan Patil"
                )
                assertTrue(res.text.contains("Rohan Kulkarni"))
                assertTrue(res.text.contains("Baner"))
                assertTrue(res.text.contains("Pavan Patil"))
            }
        }
    }

    @Test
    fun `Daily AI Priority Briefing aggregates counts, prioritizes urgent actions, and caches per IST day`() = runBlocking {
        val snapshot = DailyBriefingSnapshot(
            employeeName = "Pavan Patil",
            overdueFollowUps = 4,
            dueTodayFollowUps = 2,
            hotLeadsCount = 3,
            hotLeadsWithLiveMatches = 2,
            todaySiteVisits = 1,
            unloggedCallsCount = 5,
            pendingAiInboxCount = 3,
            pendingAiPropertyCount = 2,
            pendingAiRequirementCount = 1,
            availableCommercialCount = 18,
            availableResidentialCount = 11,
            topOverdueLeadNames = listOf("Amit Shah", "Sneha Joshi"),
            topHotLeadSummaries = listOf("TechCorp (Kharadi)")
        )

        val first = GeminiProductivityEngine.generateDailyPriorityBriefing(
            context = context,
            snapshot = snapshot,
            forceRefresh = true
        )
        assertTrue(first.headline.isNotBlank())
        assertTrue(first.priorityBullets.size in 3..4)
        assertTrue(first.priorityBullets.any { it.targetKey == "CRM" })
        assertTrue(first.priorityBullets.any { it.targetKey == "AI_INBOX" })

        val cached = GeminiProductivityEngine.loadCachedDailyBriefing(context)
        assertNotNull(cached)
        assertEquals(first.headline, cached!!.headline)
        assertEquals(first.dataSignature, cached.dataSignature)
        assertEquals(first.priorityBullets.size, cached.priorityBullets.size)
    }
}
