package com.example.ai

import com.example.crm.CallType
import com.example.crm.CrmCallRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallAudioAiAnalyzerTest {

    // Fixed anchor timestamp: Thursday, 26 Nov 2026 11:30 AM
    private fun thursdayNov26_2026(): Long = Calendar.getInstance(Locale.ENGLISH).apply {
        set(2026, Calendar.NOVEMBER, 26, 11, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ── 1. AUDIO PRE-FILTERING TESTS (10s CUTOFF, MISSED, SPAM/IVR, PERSONAL, ALREADY ANALYZED) ──

    @Test
    fun preFilter_skipsCallsUnder10Seconds_andAllows10SecondsOrMore() {
        assertEquals(
            AudioPreFilterSkipReason.TOO_SHORT,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 0,
                callType = CallType.INCOMING,
                phoneNumber = "+919822012345"
            )
        )
        assertEquals(
            AudioPreFilterSkipReason.TOO_SHORT,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 9,
                callType = CallType.OUTGOING,
                phoneNumber = "9822012345"
            )
        )
        assertEquals(
            AudioPreFilterSkipReason.NONE,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 10,
                callType = CallType.INCOMING,
                phoneNumber = "+919822012345"
            )
        )
        assertEquals(
            AudioPreFilterSkipReason.NONE,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 145,
                callType = CallType.OUTGOING,
                phoneNumber = "9822012345"
            )
        )
    }

    @Test
    fun preFilter_skipsMissedAndRejectedCalls() {
        assertEquals(
            AudioPreFilterSkipReason.MISSED_OR_REJECTED,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 30,
                callType = CallType.MISSED,
                phoneNumber = "+919822012345"
            )
        )
        assertEquals(
            AudioPreFilterSkipReason.MISSED_OR_REJECTED,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 20,
                callType = CallType.INCOMING,
                phoneNumber = "+919822012345",
                leadStatus = "Rejected"
            )
        )
    }

    @Test
    fun preFilter_skipsSpamTelemarketerIvrPrefixes_140_160_1800() {
        assertEquals(
            AudioPreFilterSkipReason.SPAM_IVR_NUMBER,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 45,
                callType = CallType.INCOMING,
                phoneNumber = "+911408801234"
            )
        )
        assertEquals(
            AudioPreFilterSkipReason.SPAM_IVR_NUMBER,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 45,
                callType = CallType.INCOMING,
                phoneNumber = "1600987654"
            )
        )
        assertEquals(
            AudioPreFilterSkipReason.SPAM_IVR_NUMBER,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 45,
                callType = CallType.INCOMING,
                phoneNumber = "1800123456"
            )
        )
        assertEquals(
            AudioPreFilterSkipReason.SPAM_IVR_NUMBER,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 60,
                callType = CallType.INCOMING,
                phoneNumber = "9822099999",
                callerName = "Suspected Spam Loan Offer"
            )
        )
    }

    @Test
    fun preFilter_skipsIgnoredPersonalAndAlreadyAnalyzedCalls() {
        assertEquals(
            AudioPreFilterSkipReason.IGNORED_OR_PERSONAL,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 95,
                callType = CallType.INCOMING,
                phoneNumber = "9822012345",
                isIgnoredOrPersonal = true
            )
        )
        assertEquals(
            AudioPreFilterSkipReason.ALREADY_ANALYZED,
            CallAudioPreFilter.evaluateEligibility(
                durationSeconds = 95,
                callType = CallType.INCOMING,
                phoneNumber = "9822012345",
                aiAnalyzedAt = 1764150000000L
            )
        )
    }

    // ── 2. MODEL SELECTION & ESCALATION TESTS ──

    @Test
    fun modelSelection_usesFlashLiteByDefault_andEscalatesForLongOrMultilingualCalls() {
        assertEquals(
            CallAudioAiAnalyzer.MODEL_FLASH_LITE,
            CallAudioAiAnalyzer.selectExtractionModel(durationSeconds = 75, languageOrContextHint = "English")
        )
        assertEquals(
            CallAudioAiAnalyzer.MODEL_FLASH,
            CallAudioAiAnalyzer.selectExtractionModel(durationSeconds = 181, languageOrContextHint = "English")
        )
        assertEquals(
            CallAudioAiAnalyzer.MODEL_FLASH,
            CallAudioAiAnalyzer.selectExtractionModel(durationSeconds = 45, languageOrContextHint = "Marathi Kharadi madhe")
        )
        assertEquals(
            CallAudioAiAnalyzer.MODEL_FLASH,
            CallAudioAiAnalyzer.selectExtractionModel(durationSeconds = 50, languageOrContextHint = "खराडी मध्ये ऑफिस")
        )
        assertEquals("gemini-3.5-transcribe", CallAudioAiAnalyzer.MODEL_TRANSCRIBE)
    }

    // ── 3. MULTILINGUAL JSON PARSING & ZERO FABRICATED VALUES TESTS ──

    @Test
    fun parseAnalysisJson_marathiClientLead_cleanEnglishOutput_zeroFabricatedBlanks() {
        val rawJson = """
            ```json
            {
              "callerRole": "CLIENT_LEAD",
              "detectedLanguage": "Marathi",
              "summaryBullets": [
                "Client wants a 1,500 sq.ft. warm shell office on lease in Kharadi.",
                "Budget is 1.25L to 1.50L per month; requested WhatsApp shortlist tomorrow morning."
              ],
              "extractedClientLead": {
                "division": "Commercial",
                "transactionType": "Lease",
                "propertyType": "Office Space",
                "configuration": "N/A",
                "location": "Pune",
                "microMarket": "Kharadi",
                "carpetAreaSqFt": "1500",
                "budgetMin": "1.25L",
                "budgetMax": "1.50L",
                "furnishing": "Warm Shell",
                "possession": "not mentioned"
              },
              "extractedPropertyUpdate": null,
              "extractedTenantNote": null,
              "followUp": {
                "date": "udya",
                "time": "10:30 AM",
                "nextAction": "Send WA Shortlist",
                "suggestedWhatsAppDraft": "Hello, thank you for speaking with The Leasing Company. We will share warm shell office options in Kharadi around 1,500 sq.ft. shortly."
              },
              "confidence": 92,
              "needsReview": false
            }
            ```
        """.trimIndent()

        val result = CallAudioAiAnalyzer.parseAnalysisJson(
            rawJson = rawJson,
            callTimestampMs = thursdayNov26_2026(),
            modelUsed = CallAudioAiAnalyzer.MODEL_FLASH
        )

        assertEquals("CLIENT_LEAD", result.callerRole)
        assertEquals("Marathi", result.detectedLanguage)
        assertEquals(2, result.summaryBullets.size)
        assertEquals(92, result.confidence)
        assertFalse(result.needsReview)

        val lead = result.extractedClientLead
        assertNotNull(lead)
        assertEquals("Commercial", lead!!.division)
        assertEquals("Lease", lead.transactionType)
        assertEquals("Office Space", lead.propertyType)
        assertEquals("Kharadi", lead.microMarket)
        assertEquals("1500", lead.carpetAreaSqFt)
        assertEquals("Warm Shell", lead.furnishing)
        // Zero fabricated placeholders scrubbed to blank
        assertEquals("", lead.configuration)
        assertEquals("", lead.possession)
        assertNull(result.extractedPropertyUpdate)
        assertNull(result.extractedTenantNote)

        // Marathi relative date "udya" resolved relative to Thu 26/11/2026 -> 27/11/2026
        assertEquals("27/11/2026", result.followUp.date)
        assertEquals("10:30 AM", result.followUp.time)
        assertEquals("Send WA Shortlist", result.followUp.nextAction)
        assertTrue(result.followUp.suggestedWhatsAppDraft.contains("The Leasing Company"))

        // Round-trip Room entity mapping
        val sampleCall = CrmCallRecord(
            id = "call_marathi_01",
            callerName = "Rajesh Kulkarni",
            phoneNumber = "+919822011111",
            callType = CallType.INCOMING,
            durationSeconds = 210,
            timestamp = thursdayNov26_2026()
        )
        val entity = result.toEntity(sampleCall, deviceName = "S22 Ultra")
        val roundTripped = entity.toAnalysisResult()
        assertEquals("CLIENT_LEAD", roundTripped.callerRole)
        assertEquals("Marathi", roundTripped.detectedLanguage)
        assertEquals("27/11/2026", roundTripped.followUp.date)
        assertEquals("Kharadi", roundTripped.extractedClientLead?.microMarket)
        assertTrue(roundTripped.buildSpecPills().any { it.contains("Kharadi") })
    }

    @Test
    fun parseAnalysisJson_hindiOwnerPropertyUpdate_andBrokerBlankUnitNumber() {
        val ownerJson = """
            {
              "callerRole": "OWNER",
              "detectedLanguage": "Hindi",
              "summaryBullets": [
                "Owner confirmed unit 402 at Panchshil Business Park is now Under Negotiation.",
                "Unit has attached pantry and washroom; asking rent revised to 2.1L."
              ],
              "extractedPropertyUpdate": {
                "actionType": "UPDATE_EXISTING",
                "projectName": "Panchshil Business Park",
                "unitNumber": "402",
                "location": "Pune",
                "microMarket": "Viman Nagar",
                "assetType": "Office Space",
                "carpetAreaSqFt": "1850",
                "builtUpAreaSqFt": "null",
                "rentOrPrice": "2.1L/month",
                "attachedPantry": "Yes",
                "attachedWashroom": "Yes",
                "statusChange": "Under Negotiation",
                "builderOfferOrBrokerageNote": ""
              },
              "followUp": {
                "date": "Saturday",
                "time": "04:00 PM",
                "nextAction": "Verify Unit",
                "suggestedWhatsAppDraft": ""
              },
              "confidence": 88,
              "needsReview": false
            }
        """.trimIndent()

        val ownerRes = CallAudioAiAnalyzer.parseAnalysisJson(ownerJson, thursdayNov26_2026())
        assertEquals("OWNER", ownerRes.callerRole)
        assertEquals("Hindi", ownerRes.detectedLanguage)
        assertEquals("28/11/2026", ownerRes.followUp.date)
        val prop = ownerRes.extractedPropertyUpdate
        assertNotNull(prop)
        assertEquals("402", prop!!.unitNumber)
        assertEquals("", prop.builtUpAreaSqFt) // "null" scrubbed
        assertEquals("Yes", prop.attachedPantry)
        assertEquals("Yes", prop.attachedWashroom)
        assertEquals("Under Negotiation", prop.statusChange)

        val brokerJson = """
            {
              "callerRole": "BROKER_CP",
              "detectedLanguage": "Mixed",
              "summaryBullets": [
                "CP shared an office option in World Trade Center Kharadi without disclosing unit number."
              ],
              "extractedPropertyUpdate": {
                "actionType": "NEW_PROPERTY",
                "projectName": "World Trade Center",
                "unitNumber": "not stated",
                "location": "Kharadi",
                "assetType": "Office Space",
                "carpetAreaSqFt": "2200",
                "rentOrPrice": "2.4L"
              },
              "followUp": {
                "date": "",
                "time": "",
                "nextAction": "Call Back",
                "suggestedWhatsAppDraft": ""
              },
              "confidence": 64,
              "needsReview": true
            }
        """.trimIndent()

        val brokerRes = CallAudioAiAnalyzer.parseAnalysisJson(brokerJson, thursdayNov26_2026())
        assertEquals("BROKER_CP", brokerRes.callerRole)
        assertEquals("Mixed", brokerRes.detectedLanguage)
        assertTrue(brokerRes.needsReview)
        assertEquals("", brokerRes.extractedPropertyUpdate?.unitNumber)
        assertEquals("", brokerRes.followUp.date)
    }

    @Test
    fun parseAnalysisJson_englishTenantCall_extractsTenantNoteOnly() {
        val tenantJson = """
            {
              "callerRole": "TENANT",
              "detectedLanguage": "English",
              "summaryBullets": [
                "Tenant requested lease renewal terms 60 days ahead of expiry."
              ],
              "extractedTenantNote": {
                "tenantAction": "Renewal",
                "notes": "Wants 5% escalation cap for 3-year renewal."
              },
              "followUp": {
                "date": "2026-12-05",
                "time": "11:00 AM",
                "nextAction": "Call Back",
                "suggestedWhatsAppDraft": "Hello, thank you for calling The Leasing Company regarding your lease renewal."
              },
              "confidence": 95,
              "needsReview": false
            }
        """.trimIndent()

        val res = CallAudioAiAnalyzer.parseAnalysisJson(tenantJson, thursdayNov26_2026())
        assertEquals("TENANT", res.callerRole)
        assertEquals("English", res.detectedLanguage)
        assertNull(res.extractedClientLead)
        assertNull(res.extractedPropertyUpdate)
        assertEquals("Renewal", res.extractedTenantNote?.tenantAction)
        assertEquals("05/12/2026", res.followUp.date)
    }

    // ── 4. FOLLOW-UP DATE dd/MM/yyyy NORMALIZATION TESTS ──

    @Test
    fun resolveFollowUpDateDdMmYyyy_handlesRelativeMarathiHindiEnglishAndExplicitFormats() {
        val thu = thursdayNov26_2026()
        assertEquals("26/11/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("today", thu))
        assertEquals("26/11/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("aaj", thu))
        assertEquals("27/11/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("tomorrow", thu))
        assertEquals("27/11/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("udya", thu))
        assertEquals("27/11/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("kal", thu))
        assertEquals("28/11/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("day after tomorrow", thu))
        assertEquals("28/11/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("parso", thu))
        assertEquals("28/11/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("Saturday", thu))
        assertEquals("28/11/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("shanivar", thu))
        assertEquals("03/12/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("next week", thu))
        assertEquals("05/12/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("2026-12-05", thu))
        assertEquals("29/11/2026", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("29/11/2026", thu))
        assertEquals("", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("", thu))
        assertEquals("", CallAudioAiAnalyzer.resolveFollowUpDateDdMmYyyy("not mentioned", thu))
    }
}
