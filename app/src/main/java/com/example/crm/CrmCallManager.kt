package com.example.crm

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.data.PropertiesLocalDatabase
import com.example.data.projects.DirectoryProject
import com.example.model.PropertyItem
import com.example.model.UnderConstProject
import com.example.ui.screens.CrmLead
import com.example.ui.screens.displayClientTitle
import com.example.ui.screens.isBrokerLead
import com.example.ui.screens.isOwnerLead
import com.example.ui.screens.readCrmLeads
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class CallType(val label: String) {
    INCOMING("Incoming"),
    OUTGOING("Outgoing"),
    MISSED("Missed");

    companion object {
        fun fromString(value: String): CallType =
            values().firstOrNull { it.name.equals(value, true) || it.label.equals(value, true) } ?: INCOMING
    }
}

data class CrmCallRecord(
    val id: String = UUID.randomUUID().toString(),
    val callerName: String,
    val phoneNumber: String,
    val callType: CallType,
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0,
    val notes: String = "",
    val assignedAgent: String = "Rahul Sharma",
    val actionRequired: String = "Schedule Site Visit",
    val nextFollowUpDateTime: String = "Today, 4:00 PM",
    val leadStatus: String = "Active", // Active, Pending Follow-up, Site Visit Scheduled, Negotiation, Closed
    val stagingStatus: String = "Live", // Live, Draft / Pending Review
    val isLinked: Boolean = false,
    val linkedOpportunityType: String? = null, // "Client", "Owner", "Broker"
    val linkedOpportunityTitle: String? = null,
    val linkedOpportunityId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("callerName", callerName)
        put("phoneNumber", phoneNumber)
        put("callType", callType.name)
        put("timestamp", timestamp)
        put("durationSeconds", durationSeconds)
        put("notes", notes)
        put("assignedAgent", assignedAgent)
        put("actionRequired", actionRequired)
        put("nextFollowUpDateTime", nextFollowUpDateTime)
        put("leadStatus", leadStatus)
        put("stagingStatus", stagingStatus)
        put("isLinked", isLinked)
        put("linkedOpportunityType", linkedOpportunityType.orEmpty())
        put("linkedOpportunityTitle", linkedOpportunityTitle.orEmpty())
        put("linkedOpportunityId", linkedOpportunityId.orEmpty())
    }

    companion object {
        fun fromJson(obj: JSONObject): CrmCallRecord = CrmCallRecord(
            id = obj.optString("id", UUID.randomUUID().toString()),
            callerName = obj.optString("callerName", "Unknown Caller"),
            phoneNumber = obj.optString("phoneNumber", ""),
            callType = CallType.fromString(obj.optString("callType", "INCOMING")),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            durationSeconds = obj.optInt("durationSeconds", 0),
            notes = obj.optString("notes", ""),
            assignedAgent = obj.optString("assignedAgent", "Rahul Sharma"),
            actionRequired = obj.optString("actionRequired", "Schedule Site Visit"),
            nextFollowUpDateTime = obj.optString("nextFollowUpDateTime", "Today, 4:00 PM"),
            leadStatus = obj.optString("leadStatus", "Active"),
            stagingStatus = obj.optString("stagingStatus", "Live"),
            isLinked = obj.optBoolean("isLinked", false),
            linkedOpportunityType = obj.optString("linkedOpportunityType").ifBlank { null },
            linkedOpportunityTitle = obj.optString("linkedOpportunityTitle").ifBlank { null },
            linkedOpportunityId = obj.optString("linkedOpportunityId").ifBlank { null }
        )
    }
}

data class CrmActivityLog(
    val id: String = UUID.randomUUID().toString(),
    val entityId: String,
    val type: String, // "Call Logged", "Opportunity Created", "Client Follow-up", "Property Linked"
    val timestamp: Long = System.currentTimeMillis(),
    val summary: String,
    val details: String = "",
    val author: String = "Staff"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("entityId", entityId)
        put("type", type)
        put("timestamp", timestamp)
        put("summary", summary)
        put("details", details)
        put("author", author)
    }

    companion object {
        fun fromJson(obj: JSONObject): CrmActivityLog = CrmActivityLog(
            id = obj.optString("id", UUID.randomUUID().toString()),
            entityId = obj.optString("entityId", ""),
            type = obj.optString("type", "General"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            summary = obj.optString("summary", ""),
            details = obj.optString("details", ""),
            author = obj.optString("author", "Staff")
        )
    }
}

object CrmCallManager {
    private const val PREFS_NAME = "crm_call_intelligence_v1"
    private const val KEY_CALLS = "calls_records"
    private const val KEY_ACTIVITIES = "activity_logs"

    fun readCalls(context: Context): List<CrmCallRecord> {
        return runCatching {
            val raw = com.example.data.auditedCrmPreferences(context, PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CALLS, "[]") ?: "[]"
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                CrmCallRecord.fromJson(array.getJSONObject(i))
            }
        }.getOrDefault(emptyList())
    }

    fun saveCalls(context: Context, calls: List<CrmCallRecord>) {
        val array = JSONArray()
        calls.forEach { array.put(it.toJson()) }
        com.example.data.auditedCrmPreferences(context, PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CALLS, array.toString())
            .apply()
    }

    fun addOrUpdateCall(context: Context, call: CrmCallRecord) {
        val current = readCalls(context).toMutableList()
        val index = current.indexOfFirst { it.id == call.id }
        if (index >= 0) {
            current[index] = call
        } else {
            current.add(0, call)
        }
        saveCalls(context, current)
    }

    @Synchronized
    fun linkCallToOpportunity(
        context: Context,
        callId: String,
        oppType: String,
        oppTitle: String,
        oppId: String
    ): Boolean {
        if (oppId.isBlank()) return false
        val calls = readCalls(context).toMutableList()
        val index = calls.indexOfFirst { it.id == callId }
        if (index < 0) return false
        val current = calls[index]
        if (current.isLinked && !current.linkedOpportunityId.isNullOrBlank() && current.linkedOpportunityId != oppId) return false
        if (current.isLinked && current.linkedOpportunityId == oppId) return true
        calls[index] = current.copy(isLinked = true, linkedOpportunityType = oppType, linkedOpportunityTitle = oppTitle, linkedOpportunityId = oppId)
        saveCalls(context, calls)
        logActivity(context, oppId, "Opportunity Created", "Linked saved $oppType Opportunity from Call ($oppTitle)", "Linked to Call ID: $callId")
        return true
    }

    fun readActivityLogs(context: Context, entityId: String? = null): List<CrmActivityLog> {
        return runCatching {
            val raw = com.example.data.auditedCrmPreferences(context, PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVITIES, "[]") ?: "[]"
            val array = JSONArray(raw)
            val list = (0 until array.length()).map { i ->
                CrmActivityLog.fromJson(array.getJSONObject(i))
            }
            if (entityId != null) {
                list.filter { it.entityId == entityId }
            } else list
        }.getOrDefault(emptyList())
    }

    fun logActivity(
        context: Context,
        entityId: String,
        type: String,
        summary: String,
        details: String = "",
        author: String = "Staff"
    ) {
        val current = readActivityLogs(context).toMutableList()
        val item = CrmActivityLog(
            id = UUID.randomUUID().toString(),
            entityId = entityId,
            type = type,
            timestamp = System.currentTimeMillis(),
            summary = summary,
            details = details,
            author = author
        )
        current.add(0, item)
        val array = JSONArray()
        current.forEach { array.put(it.toJson()) }
        com.example.data.auditedCrmPreferences(context, PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVITIES, array.toString())
            .apply()
    }

    /**
     * Safely queries device call logs if Android READ_CALL_LOG permission is explicitly granted.
     * Complies with Google Play developer policies:
     * - Returns gracefully if permission is not granted (zero crashes, no forced prompt).
     * - Does NOT transmit call logs outside the local device.
     */
    var lastDeviceReadError: String? = null
        private set

    fun safeReadDeviceCallLogs(context: Context): List<CrmCallRecord> {
        lastDeviceReadError = null
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            lastDeviceReadError = "Call log permission is required. Allow it in Android Settings or add a manual call note."
            return emptyList()
        }

        return try {
            val projection = arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )
            val list = mutableListOf<CrmCallRecord>()
            cursor?.use {
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                while (list.size < 50 && it.moveToNext()) {
                    val rawName = if (nameIdx >= 0) it.getString(nameIdx).orEmpty() else ""
                    val rawNum = if (numIdx >= 0) it.getString(numIdx).orEmpty() else ""
                    val typeVal = if (typeIdx >= 0) it.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                    val dateVal = if (dateIdx >= 0) it.getLong(dateIdx) else System.currentTimeMillis()
                    val durVal = if (durIdx >= 0) it.getInt(durIdx) else 0

                    val callType = when (typeVal) {
                        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
                        else -> CallType.INCOMING
                    }

                    list.add(
                        CrmCallRecord(
                            id = "device_call_${dateVal}_${rawNum.takeLast(4)}",
                            callerName = rawName.ifBlank { "Unknown" },
                            phoneNumber = ContactUtils.normalizePhoneNumber(rawNum),
                            callType = callType,
                            timestamp = dateVal,
                            durationSeconds = durVal,
                            notes = "Imported from device call log",
                            isLinked = false
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            lastDeviceReadError = "Android could not read the call log. Check Phone and Call logs permissions, then retry. Saved calls were preserved."
            emptyList()
        }
    }

    /**
     * Demo Call History for real estate workflow verification.
     * Includes unlinked enquiries, missed calls, incoming leads, and broker follow-ups.
     */
    fun getDemoCalls(): List<CrmCallRecord> {
        val now = System.currentTimeMillis()
        val hour = 3600 * 1000L
        return listOf(
            CrmCallRecord(
                id = "demo_call_1",
                callerName = "Rajesh Singhania",
                phoneNumber = "9822011223",
                callType = CallType.MISSED,
                timestamp = now - (15 * 60 * 1000L),
                durationSeconds = 0,
                notes = "Missed call — called back twice earlier. Inquiring for Kharadi office lease.",
                isLinked = false
            ),
            CrmCallRecord(
                id = "demo_call_2",
                callerName = "Dr. Ananya Kulkarni",
                phoneNumber = "9860012345",
                callType = CallType.INCOMING,
                timestamp = now - (2 * hour),
                durationSeconds = 245,
                notes = "Interested in 3 BHK luxury flat in Keshav Nagar / Mundhwa for self-use. Budget ₹1.8 Cr.",
                isLinked = false
            ),
            CrmCallRecord(
                id = "demo_call_3",
                callerName = "Vikram More (Channel Partner)",
                phoneNumber = "9881122334",
                callType = CallType.INCOMING,
                timestamp = now - (5 * hour),
                durationSeconds = 380,
                notes = "Has pre-leased bank branch property for sale in Baner with 7.2% ROI.",
                isLinked = false
            ),
            CrmCallRecord(
                id = "demo_call_4",
                callerName = "Pooja Deshmukh",
                phoneNumber = "9766001122",
                callType = CallType.OUTGOING,
                timestamp = now - (22 * hour),
                durationSeconds = 190,
                notes = "Follow-up regarding site visit scheduled for Saturday at Gera Imperium Alpha.",
                isLinked = true,
                linkedOpportunityType = "Client",
                linkedOpportunityTitle = "Pooja Deshmukh • Office Lease",
                linkedOpportunityId = "demo_opp_client_1"
            ),
            CrmCallRecord(
                id = "demo_call_5",
                callerName = "Unknown Caller",
                phoneNumber = "9923456780",
                callType = CallType.INCOMING,
                timestamp = now - (26 * hour),
                durationSeconds = 85,
                notes = "Called regarding newspaper listing of retail showroom in Viman Nagar.",
                isLinked = false
            ),
            CrmCallRecord(
                id = "demo_call_6",
                callerName = "Amitabh Sharma (Owner)",
                phoneNumber = "9850123456",
                callType = CallType.OUTGOING,
                timestamp = now - (48 * hour),
                durationSeconds = 310,
                notes = "Confirmed rental expectation of ₹1,40,000/mo for 1,600 sq.ft office in Kalyani Nagar.",
                isLinked = true,
                linkedOpportunityType = "Owner",
                linkedOpportunityTitle = "Owner of OL-204 (Marigold IT)",
                linkedOpportunityId = "demo_opp_owner_1"
            )
        )
    }

    // =========================================================================
    // WhatsApp Integration & Contextual Template Generator
    // =========================================================================
    fun normalizePhoneNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("91") && digits.length == 12 -> "+$digits"
            digits.length == 10 -> "+91$digits"
            raw.startsWith("+") -> raw
            else -> "+91$digits"
        }
    }

    fun generateWhatsAppDraft(
        category: String, // Buyer / Client Enquiry, Owner Listing Intake, Broker Market Update, General Follow-Up
        callerName: String,
        microMarket: String,
        details: String
    ): String {
        return when (category.lowercase()) {
            "buyer", "client enquiry" -> {
                "Dear $callerName,\n\nThank you for connecting with Ascend & Aurea Living LLP regarding your requirement in $microMarket.\n\nSummary of Requirements:\n- Preferred Location: $microMarket\n- Enquiry Notes: $details\n\nWe have compiled a curated shortlist of verified properties matching your criteria. You can review the catalog details shortly.\n\nBest regards,\nTLC-1 Real Estate Intelligence Team"
            }
            "owner", "owner listing intake" -> {
                "Dear $callerName,\n\nThank you for listing your property with us in $microMarket.\n\nProperty Intake Notes:\n$details\n\nWe have recorded your specifications and are preparing the high-res proposal flyer and verification listing for our active corporate clients.\n\nWarm regards,\nTLC-1 Management Team"
            }
            "broker", "broker market update" -> {
                "Hi $callerName,\n\nThanks for sharing the market update / inventory details for $microMarket.\n\nDetails:\n$details\n\nWe have submitted this record to our Staging Database for review and client matching.\n\nBest regards,\nTLC-1 Channel Partner Desk"
            }
            else -> {
                "Dear $callerName,\n\nThank you for your time on our call today regarding $microMarket.\n\nDiscussion Recap:\n$details\n\nWe look forward to our scheduled follow-up.\n\nBest regards,\nTLC-1 Real Estate Team"
            }
        }
    }

    fun openWhatsAppChat(context: android.content.Context, phoneNumber: String, messageText: String) {
        val normalized = normalizePhoneNumber(phoneNumber)
        val encoded = java.net.URLEncoder.encode(messageText, "UTF-8")
        val url = "https://wa.me/${normalized.removePrefix("+")}?text=$encoded"
        
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Try WhatsApp or WhatsApp Business first, fallback to browser / SMS
        try {
            intent.setPackage("com.whatsapp")
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                intent.setPackage("com.whatsapp.w4b")
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    intent.setPackage(null)
                    context.startActivity(intent)
                } catch (ex: Exception) {
                    android.widget.Toast.makeText(context, "Could not open WhatsApp: ${ex.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun formatDuration(seconds: Int): String {
        return if (seconds <= 0) "0s"
        else {
            val mins = seconds / 60
            val secs = seconds % 60
            if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        }
    }

    fun formatTimestamp(epochMillis: Long): String {
        val df = SimpleDateFormat("dd MMM, hh:mm a", Locale.US)
        return df.format(Date(epochMillis))
    }

    fun last10Digits(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    private fun extractCandidatePhoneKeys(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val split = raw.split(Regex("[,/|;\\n]+")).map { last10Digits(it) }.filter { it.length >= 7 }
        val whole = last10Digits(raw).takeIf { it.length >= 7 }
        return (split + listOfNotNull(whole)).distinct()
    }

    /**
     * Queries persisted CRM call logs + device CallLog (when granted) matching
     * any of the client's phone numbers or leadId, sorted newest-first.
     */
    fun findMatchingCallsForLead(
        context: Context,
        phones: List<String>,
        leadId: String? = null
    ): List<CrmCallRecord> {
        val targetPhoneKeys = phones
            .flatMap(::extractCandidatePhoneKeys)
            .toSet()

        val savedCalls = runCatching { readCalls(context) }.getOrDefault(emptyList())
        val deviceCalls = runCatching { safeReadDeviceCallLogs(context) }.getOrDefault(emptyList())

        val combined = savedCalls + deviceCalls
        val matching = combined.filter { call ->
            val matchesLeadId = !leadId.isNullOrBlank() && call.linkedOpportunityId == leadId
            val callPhoneKey = last10Digits(call.phoneNumber)
            val matchesPhone = callPhoneKey.length >= 7 && callPhoneKey in targetPhoneKeys
            matchesLeadId || matchesPhone
        }

        val deduped = linkedMapOf<String, CrmCallRecord>()
        matching.forEach { call ->
            val key = "${last10Digits(call.phoneNumber)}_${call.timestamp / 5000L}_${call.callType.name}"
            if (!deduped.containsKey(key)) {
                deduped[key] = call
            }
        }
        return deduped.values.sortedByDescending { it.timestamp }
    }

    /**
     * Logs a call outcome and optional quick note linked to a CRM lead.
     */
    fun recordLeadCallOutcome(
        context: Context,
        leadId: String,
        leadName: String,
        phone: String,
        outcome: String,
        note: String,
        performer: String
    ): CrmCallRecord {
        val cleanOutcome = outcome.trim()
        val cleanNote = note.trim()
        val combinedNotes = buildString {
            if (cleanOutcome.isNotBlank()) append("Outcome: $cleanOutcome")
            if (cleanNote.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append(cleanNote)
            }
        }.ifBlank { "Call logged" }

        val record = CrmCallRecord(
            id = "lead_call_${System.currentTimeMillis()}",
            callerName = leadName.ifBlank { phone.ifBlank { "CRM Client" } },
            phoneNumber = phone.trim(),
            callType = CallType.OUTGOING,
            timestamp = System.currentTimeMillis(),
            durationSeconds = 0,
            notes = combinedNotes,
            isLinked = true,
            linkedOpportunityType = "Client",
            linkedOpportunityTitle = leadName.ifBlank { leadId },
            linkedOpportunityId = leadId
        )
        addOrUpdateCall(context, record)
        logActivity(
            context = context,
            entityId = leadId,
            type = if (cleanOutcome.isNotBlank()) "Call: $cleanOutcome" else "Call Logged",
            summary = combinedNotes,
            details = phone.trim(),
            author = performer.ifBlank { "TLC-1 Team" }
        )
        return record
    }

    // =========================================================================
    // Batch 5: Post-Call Intelligence, 4-Role Matching, Queuing & Detection
    // =========================================================================

    private const val KEY_SHOWN_POST_CALL_KEYS = "shown_post_call_keys"
    private const val KEY_UNLOGGED_CALL_QUEUE = "unlogged_call_queue"
    private const val KEY_BROKER_CONTACTS = "broker_contacts_map"
    private const val KEY_PROJECT_POC_LINKS = "project_poc_map"
    private const val KEY_WA_CALL_CAPTURE_ENABLED = "wa_call_capture_enabled"
    private const val KEY_WA_CALL_DEDUP_BUCKETS = "wa_call_dedup_buckets"

    private fun rawPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isWhatsAppCallCaptureEnabled(context: Context): Boolean {
        return runCatching {
            rawPrefs(context).getBoolean(KEY_WA_CALL_CAPTURE_ENABLED, true)
        }.getOrDefault(true)
    }

    fun setWhatsAppCallCaptureEnabled(context: Context, enabled: Boolean) {
        runCatching {
            rawPrefs(context).edit().putBoolean(KEY_WA_CALL_CAPTURE_ENABLED, enabled).apply()
        }
    }

    fun isNotificationListenerGranted(context: Context): Boolean {
        return runCatching {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            enabledListeners.contains(context.packageName)
        }.getOrDefault(false)
    }

    fun openNotificationListenerSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            runCatching {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        }
    }

    /**
     * Captures a live WhatsApp or WhatsApp Business incoming/missed call notification,
     * deduplicates within the same 1-minute bucket by caller name, auto-matches callerName
     * against CRM Leads and Property Inventory (Owner / Broker), and enqueues into the
     * Post-Call Smart Queue and Call History.
     */
    @Synchronized
    fun captureWhatsAppCallNotification(
        context: Context,
        packageName: String,
        rawTitle: String,
        rawText: String,
        isMissed: Boolean,
        timestampMs: Long = System.currentTimeMillis()
    ): CrmCallRecord? {
        if (!isWhatsAppCallCaptureEnabled(context)) return null
        if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b") return null

        val cleanTitle = rawTitle.trim()
            .removePrefix("Missed voice call from ")
            .removePrefix("Missed video call from ")
            .removePrefix("Missed call from ")
            .removePrefix("Incoming voice call from ")
            .removePrefix("Incoming video call from ")
            .trim()

        val ignoredTitles = setOf(
            "",
            "whatsapp",
            "whatsapp business",
            "calling...",
            "ringing...",
            "ongoing call",
            "whatsapp voice call",
            "whatsapp video call",
            "incoming voice call",
            "incoming video call",
            "missed voice call",
            "missed video call"
        )
        if (cleanTitle.lowercase(Locale.US) in ignoredTitles) return null

        val minuteBucket = timestampMs / 60_000L
        val dedupBucketKey = "${cleanTitle.lowercase(Locale.US)}_$minuteBucket"
        val prefs = rawPrefs(context)
        val existingBuckets = runCatching {
            JSONArray(prefs.getString(KEY_WA_CALL_DEDUP_BUCKETS, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val bucketList = mutableListOf<String>()
        for (i in 0 until existingBuckets.length()) {
            existingBuckets.optString(i).takeIf { it.isNotBlank() }?.let { bucketList.add(it) }
        }
        if (dedupBucketKey in bucketList) {
            return null
        }
        bucketList.add(0, dedupBucketKey)
        val outBuckets = JSONArray()
        bucketList.take(200).forEach { outBuckets.put(it) }
        prefs.edit().putString(KEY_WA_CALL_DEDUP_BUCKETS, outBuckets.toString()).apply()

        // Attempt phone if title itself contains a 10+ digit number
        val digitsInTitle = last10Digits(cleanTitle)
        var resolvedPhone = if (digitsInTitle.length >= 10) digitsInTitle else ""
        var resolvedOppType: String? = null
        var resolvedOppTitle: String? = null
        var resolvedOppId: String? = null

        // Auto-match callerName against CRM Leads and Property Inventory
        val leads = runCatching { readCrmLeads(context) }.getOrDefault(emptyList())
        val matchedLead = leads.firstOrNull { lead ->
            lead.name.trim().equals(cleanTitle, ignoreCase = true) ||
                lead.displayClientTitle().trim().equals(cleanTitle, ignoreCase = true) ||
                (digitsInTitle.length >= 10 && (last10Digits(lead.phone) == digitsInTitle || last10Digits(lead.alternatePhone) == digitsInTitle))
        }
        if (matchedLead != null) {
            if (resolvedPhone.isBlank()) resolvedPhone = matchedLead.phone
            resolvedOppType = when {
                matchedLead.isOwnerLead() -> "Owner"
                matchedLead.isBrokerLead() -> "Broker"
                else -> "Client"
            }
            resolvedOppTitle = matchedLead.displayClientTitle()
            resolvedOppId = matchedLead.id
        } else {
            val inventory = runCatching { PropertiesLocalDatabase.loadProperties(context) }.getOrDefault(emptyList())
            val ownerProp = inventory.firstOrNull { prop ->
                !prop.ownerName.isNullOrBlank() && prop.ownerName.trim().equals(cleanTitle, ignoreCase = true)
            }
            if (ownerProp != null) {
                if (resolvedPhone.isBlank()) resolvedPhone = ownerProp.ownerContact.orEmpty()
                resolvedOppType = "Owner"
                resolvedOppTitle = "${ownerProp.projectName} • ${ownerProp.unitNumber.ifBlank { ownerProp.propertyCode }}"
                resolvedOppId = ownerProp.id
            } else {
                val brokerProp = inventory.firstOrNull { prop ->
                    !prop.brokerName.isNullOrBlank() && prop.brokerName.trim().equals(cleanTitle, ignoreCase = true)
                }
                val savedBroker = getBrokerContacts(context).values.firstOrNull { b ->
                    b.name.trim().equals(cleanTitle, ignoreCase = true)
                }
                if (brokerProp != null || savedBroker != null) {
                    if (resolvedPhone.isBlank()) {
                        resolvedPhone = brokerProp?.brokerContact?.takeIf { it.isNotBlank() }
                            ?: savedBroker?.phoneKey.orEmpty()
                    }
                    resolvedOppType = "Broker"
                    resolvedOppTitle = savedBroker?.name?.ifBlank { null }
                        ?: brokerProp?.brokerName?.ifBlank { null }
                        ?: cleanTitle
                }
            }
        }

        if (resolvedPhone.isNotBlank() && IgnoredNumbersManager.isIgnoredOrInternal(context, resolvedPhone)) {
            return null
        }

        val appLabel = if (packageName == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"
        val noteLabel = buildString {
            append("WhatsApp Call Notification")
            if (appLabel == "WhatsApp Business") append(" (Business)")
            if (rawText.isNotBlank() && !rawText.equals(cleanTitle, ignoreCase = true)) {
                append(" • ").append(rawText.trim())
            }
        }

        val record = CrmCallRecord(
            id = "wa_call_$timestampMs",
            callerName = cleanTitle,
            phoneNumber = resolvedPhone,
            callType = if (isMissed) CallType.MISSED else CallType.INCOMING,
            timestamp = timestampMs,
            durationSeconds = 0,
            notes = noteLabel,
            isLinked = false,
            linkedOpportunityType = resolvedOppType,
            linkedOpportunityTitle = resolvedOppTitle,
            linkedOpportunityId = resolvedOppId
        )

        addOrUpdateCall(context, record)
        enqueueUnloggedCall(context, record)
        return record
    }

    fun callDedupKey(call: CrmCallRecord): String {
        val phoneKey = last10Digits(call.phoneNumber)
        return if (phoneKey.isNotBlank()) {
            "${phoneKey}_${call.timestamp / 5000L}_${call.callType.name}"
        } else if (call.callerName.isNotBlank()) {
            "wa_${call.callerName.lowercase(Locale.US).trim()}_${call.timestamp / 60_000L}_${call.callType.name}"
        } else {
            call.id
        }
    }

    fun hasShownPostCallPopup(context: Context, call: CrmCallRecord): Boolean {
        val key = callDedupKey(call)
        val arr = runCatching {
            JSONArray(rawPrefs(context).getString(KEY_SHOWN_POST_CALL_KEYS, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == key || arr.optString(i) == call.id) return true
        }
        return false
    }

    fun markPostCallPopupShown(context: Context, call: CrmCallRecord) {
        val key = callDedupKey(call)
        val prefs = rawPrefs(context)
        val existing = runCatching {
            JSONArray(prefs.getString(KEY_SHOWN_POST_CALL_KEYS, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val items = mutableListOf<String>()
        for (i in 0 until existing.length()) {
            existing.optString(i).takeIf { it.isNotBlank() }?.let { items.add(it) }
        }
        if (key !in items) items.add(0, key)
        if (call.id.isNotBlank() && call.id !in items) items.add(0, call.id)
        val trimmed = items.take(250)
        val out = JSONArray()
        trimmed.forEach { out.put(it) }
        prefs.edit().putString(KEY_SHOWN_POST_CALL_KEYS, out.toString()).apply()
    }

    fun getUnloggedCallQueue(context: Context): List<CrmCallRecord> {
        val raw = rawPrefs(context).getString(KEY_UNLOGGED_CALL_QUEUE, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { CrmCallRecord.fromJson(it) }
            }.filterNot {
                IgnoredNumbersManager.isIgnoredOrInternal(context, it.phoneNumber)
            }.sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())
    }

    fun enqueueUnloggedCall(context: Context, call: CrmCallRecord) {
        if (IgnoredNumbersManager.isIgnoredOrInternal(context, call.phoneNumber)) return
        val key = callDedupKey(call)
        val existing = getUnloggedCallQueue(context).toMutableList()
        val alreadyQueued = existing.any { it.id == call.id || callDedupKey(it) == key }
        if (!alreadyQueued) {
            existing.add(0, call)
            val arr = JSONArray()
            existing.take(60).forEach { arr.put(it.toJson()) }
            rawPrefs(context).edit().putString(KEY_UNLOGGED_CALL_QUEUE, arr.toString()).apply()
        }
    }

    fun removeFromUnloggedQueue(context: Context, call: CrmCallRecord) {
        val key = callDedupKey(call)
        val phoneKey = last10Digits(call.phoneNumber)
        val remaining = getUnloggedCallQueue(context).filterNot { item ->
            item.id == call.id ||
                callDedupKey(item) == key ||
                (phoneKey.length >= 7 && last10Digits(item.phoneNumber) == phoneKey && kotlin.math.abs(item.timestamp - call.timestamp) <= 10_000L)
        }
        val arr = JSONArray()
        remaining.forEach { arr.put(it.toJson()) }
        rawPrefs(context).edit().putString(KEY_UNLOGGED_CALL_QUEUE, arr.toString()).apply()
    }

    fun clearUnloggedQueueForNumber(context: Context, phone: String) {
        val phoneKey = last10Digits(phone)
        if (phoneKey.length < 7) return
        val remaining = getUnloggedCallQueue(context).filterNot { item ->
            last10Digits(item.phoneNumber) == phoneKey
        }
        val arr = JSONArray()
        remaining.forEach { arr.put(it.toJson()) }
        rawPrefs(context).edit().putString(KEY_UNLOGGED_CALL_QUEUE, arr.toString()).apply()
    }

    fun saveBrokerContact(
        context: Context,
        phone: String,
        name: String,
        firm: String = "",
        notes: String = ""
    ) {
        val phoneKey = last10Digits(phone)
        if (phoneKey.length < 7) return
        val prefs = rawPrefs(context)
        val map = runCatching {
            JSONObject(prefs.getString(KEY_BROKER_CONTACTS, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        map.put(
            phoneKey,
            JSONObject().apply {
                put("phoneKey", phoneKey)
                put("name", name.trim())
                put("firm", firm.trim())
                put("notes", notes.trim())
                put("taggedAt", System.currentTimeMillis())
            }
        )
        prefs.edit().putString(KEY_BROKER_CONTACTS, map.toString()).apply()
    }

    fun getBrokerContacts(context: Context): Map<String, BrokerContactEntry> {
        val prefs = rawPrefs(context)
        val map = runCatching {
            JSONObject(prefs.getString(KEY_BROKER_CONTACTS, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val out = linkedMapOf<String, BrokerContactEntry>()
        map.keys().forEach { key ->
            map.optJSONObject(key)?.let { obj ->
                out[key] = BrokerContactEntry(
                    phoneKey = key,
                    name = obj.optString("name", ""),
                    firm = obj.optString("firm", ""),
                    notes = obj.optString("notes", ""),
                    taggedAt = obj.optLong("taggedAt", 0L)
                )
            }
        }
        return out
    }

    fun saveProjectPocLink(
        context: Context,
        phone: String,
        projectId: String,
        projectName: String,
        division: String,
        contactName: String
    ) {
        val phoneKey = last10Digits(phone)
        if (phoneKey.length < 7) return
        val prefs = rawPrefs(context)
        val map = runCatching {
            JSONObject(prefs.getString(KEY_PROJECT_POC_LINKS, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        map.put(
            phoneKey,
            JSONObject().apply {
                put("phoneKey", phoneKey)
                put("projectId", projectId)
                put("projectName", projectName)
                put("division", division)
                put("contactName", contactName.trim())
                put("linkedAt", System.currentTimeMillis())
            }
        )
        prefs.edit().putString(KEY_PROJECT_POC_LINKS, map.toString()).apply()
    }

    fun getProjectPocLinks(context: Context): Map<String, ProjectPocEntry> {
        val prefs = rawPrefs(context)
        val map = runCatching {
            JSONObject(prefs.getString(KEY_PROJECT_POC_LINKS, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val out = linkedMapOf<String, ProjectPocEntry>()
        map.keys().forEach { key ->
            map.optJSONObject(key)?.let { obj ->
                out[key] = ProjectPocEntry(
                    phoneKey = key,
                    projectId = obj.optString("projectId", ""),
                    projectName = obj.optString("projectName", ""),
                    division = obj.optString("division", ""),
                    contactName = obj.optString("contactName", ""),
                    linkedAt = obj.optLong("linkedAt", 0L)
                )
            }
        }
        return out
    }

    /**
     * 4-Role Database Matcher:
     * 1. CRM Leads (Client)
     * 2. Property Inventory (Owner / Landlord)
     * 3. Brokers / Channel Partners
     * 4. Projects (Developer Sales POC)
     * 5. Fresh / Unknown Number
     */
    fun matchCallToDatabase(
        context: Context,
        call: CrmCallRecord,
        leads: List<CrmLead>,
        inventory: List<PropertyItem>,
        directoryProjects: List<DirectoryProject>,
        underConstProjects: List<UnderConstProject> = emptyList()
    ): PostCallMatchResult {
        val phoneKey = last10Digits(call.phoneNumber)
        val callerNameClean = call.callerName.trim()
        if (phoneKey.length < 7) {
            if (callerNameClean.isNotBlank() && !callerNameClean.equals("Unknown", true) && !callerNameClean.equals("Unknown Caller", true)) {
                val leadByName = leads.firstOrNull { lead ->
                    lead.name.trim().equals(callerNameClean, ignoreCase = true) ||
                        lead.displayClientTitle().trim().equals(callerNameClean, ignoreCase = true) ||
                        (!call.linkedOpportunityId.isNullOrBlank() && lead.id == call.linkedOpportunityId)
                }
                if (leadByName != null) {
                    val req = leadByName.requirement.ifBlank { "${leadByName.segment} Requirement" }
                    return PostCallMatchResult(
                        call = call.copy(phoneNumber = call.phoneNumber.ifBlank { leadByName.phone }),
                        role = if (leadByName.isOwnerLead()) PostCallRole.PROPERTY_OWNER else if (leadByName.isBrokerLead()) PostCallRole.BROKER else PostCallRole.CLIENT_LEAD,
                        matchedLead = leadByName,
                        displayTitle = leadByName.displayClientTitle(),
                        subtitle = "${leadByName.status.ifBlank { "New" }} • $req"
                    )
                }
                val ownerPropsByName = inventory.filter { prop ->
                    !prop.ownerName.isNullOrBlank() && prop.ownerName.trim().equals(callerNameClean, ignoreCase = true)
                }
                if (ownerPropsByName.isNotEmpty()) {
                    val first = ownerPropsByName.first()
                    return PostCallMatchResult(
                        call = call.copy(phoneNumber = call.phoneNumber.ifBlank { first.ownerContact.orEmpty() }),
                        role = PostCallRole.PROPERTY_OWNER,
                        matchedProperties = ownerPropsByName,
                        displayTitle = first.ownerName ?: callerNameClean,
                        subtitle = "${first.projectName} • ${first.unitNumber.ifBlank { first.propertyCode }}"
                    )
                }
                val brokerPropsByName = inventory.filter { prop ->
                    !prop.brokerName.isNullOrBlank() && prop.brokerName.trim().equals(callerNameClean, ignoreCase = true)
                }
                if (brokerPropsByName.isNotEmpty()) {
                    val first = brokerPropsByName.first()
                    return PostCallMatchResult(
                        call = call.copy(phoneNumber = call.phoneNumber.ifBlank { first.brokerContact.orEmpty() }),
                        role = PostCallRole.BROKER,
                        matchedProperties = brokerPropsByName,
                        displayTitle = first.brokerName ?: callerNameClean,
                        subtitle = "Channel Partner • ${brokerPropsByName.size} Listing(s)"
                    )
                }
            }
            return PostCallMatchResult(
                call = call,
                role = PostCallRole.FRESH_UNKNOWN,
                displayTitle = call.callerName.ifBlank { call.phoneNumber.ifBlank { "Fresh Caller" } },
                subtitle = if (call.notes.contains("WhatsApp", true)) "WhatsApp Call — tap to link or classify" else "Unknown number — tap to classify"
            )
        }

        // 1. CRM Leads (Client)
        val matchedLead = leads.firstOrNull { lead ->
            last10Digits(lead.phone) == phoneKey ||
                last10Digits(lead.alternatePhone) == phoneKey ||
                (!call.linkedOpportunityId.isNullOrBlank() && lead.id == call.linkedOpportunityId)
        }
        if (matchedLead != null) {
            val title = matchedLead.displayClientTitle()
            val req = matchedLead.requirement.ifBlank { "${matchedLead.segment} Requirement" }
            return PostCallMatchResult(
                call = call,
                role = PostCallRole.CLIENT_LEAD,
                matchedLead = matchedLead,
                displayTitle = title,
                subtitle = "${matchedLead.status.ifBlank { "New" }} • $req"
            )
        }

        // 2. Property Inventory (Owner / Landlord matching ownerContact)
        val ownerProps = inventory.filter { prop ->
            phoneKey in extractCandidatePhoneKeys(prop.ownerContact)
        }
        if (ownerProps.isNotEmpty()) {
            val first = ownerProps.first()
            val ownerTitle = first.ownerName?.takeIf { it.isNotBlank() }
                ?: call.callerName.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
                ?: "Property Owner"
            val summary = if (ownerProps.size == 1) {
                "${first.projectName} • ${first.unitNumber.ifBlank { first.propertyCode }}"
            } else {
                "${first.projectName} (+${ownerProps.size - 1} more units)"
            }
            return PostCallMatchResult(
                call = call,
                role = PostCallRole.PROPERTY_OWNER,
                matchedProperties = ownerProps,
                displayTitle = ownerTitle,
                subtitle = summary
            )
        }

        // 3. Brokers / Channel Partners (brokerContact or saved broker calls/registry)
        val savedBrokers = getBrokerContacts(context)
        val brokerEntry = savedBrokers[phoneKey]
        val brokerProps = inventory.filter { prop ->
            phoneKey in extractCandidatePhoneKeys(prop.brokerContact)
        }
        val pastBrokerCall = readCalls(context).firstOrNull { existing ->
            last10Digits(existing.phoneNumber) == phoneKey &&
                existing.linkedOpportunityType?.contains("Broker", ignoreCase = true) == true
        }
        if (brokerEntry != null || brokerProps.isNotEmpty() || pastBrokerCall != null) {
            val propBrokerName = brokerProps.firstOrNull()?.brokerName?.takeIf { it.isNotBlank() }
            val resolvedBroker = brokerEntry ?: BrokerContactEntry(
                phoneKey = phoneKey,
                name = propBrokerName ?: call.callerName.ifBlank { "Channel Partner" },
                firm = "",
                notes = pastBrokerCall?.notes.orEmpty(),
                taggedAt = System.currentTimeMillis()
            )
            val title = resolvedBroker.name.ifBlank { "Channel Partner" }
            val sub = buildString {
                append("Channel Partner")
                if (resolvedBroker.firm.isNotBlank()) append(" • ${resolvedBroker.firm}")
                else if (brokerProps.isNotEmpty()) append(" • ${brokerProps.size} Listing(s)")
            }
            return PostCallMatchResult(
                call = call,
                role = PostCallRole.BROKER,
                matchedProperties = brokerProps,
                matchedBroker = resolvedBroker,
                displayTitle = title,
                subtitle = sub
            )
        }

        // 4. Projects (Developer Sales POC)
        val pocLinks = getProjectPocLinks(context)
        val linkedPoc = pocLinks[phoneKey]
        val matchedDirProj = if (linkedPoc != null) {
            directoryProjects.firstOrNull { it.id == linkedPoc.projectId }
        } else {
            directoryProjects.firstOrNull { proj ->
                val raw = proj.raw
                val rawNumbers = listOf(
                    raw.optString("contactNumber"),
                    raw.optString("salesPocPhone"),
                    raw.optString("developerContact"),
                    raw.optString("developerPhone"),
                    raw.optString("pocPhone"),
                    raw.optString("phone"),
                    raw.optString("mobile")
                )
                rawNumbers.any { rawNum -> phoneKey in extractCandidatePhoneKeys(rawNum) }
            }
        }
        val matchedUcProj = underConstProjects.firstOrNull { uc ->
            phoneKey in extractCandidatePhoneKeys(uc.builderPhone)
        }
        if (linkedPoc != null || matchedDirProj != null || matchedUcProj != null) {
            val projName = matchedDirProj?.name
                ?: matchedUcProj?.projectName
                ?: linkedPoc?.projectName.orEmpty()
            val projCode = matchedDirProj?.id
                ?: matchedUcProj?.projectCode
                ?: linkedPoc?.projectId.orEmpty()
            val pocName = linkedPoc?.contactName?.takeIf { it.isNotBlank() }
                ?: matchedUcProj?.builderContactPerson?.takeIf { it.isNotBlank() }
                ?: matchedDirProj?.developer?.takeIf { it.isNotBlank() }
                ?: call.callerName.ifBlank { "Developer Sales POC" }
            return PostCallMatchResult(
                call = call,
                role = PostCallRole.DEVELOPER_POC,
                matchedDirectoryProject = matchedDirProj,
                matchedUnderConstProject = matchedUcProj,
                matchedDeveloperPoc = linkedPoc,
                displayTitle = pocName,
                subtitle = if (projCode.isNotBlank()) "$projCode • $projName" else projName
            )
        }

        // 5. Fresh / Unknown Number
        val fallbackName = call.callerName
            .takeIf { it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true) }
            ?: ContactUtils.formatDisplayPhone(call.phoneNumber).ifBlank { call.phoneNumber }
        return PostCallMatchResult(
            call = call,
            role = PostCallRole.FRESH_UNKNOWN,
            displayTitle = fallbackName,
            subtitle = "Fresh Number — Who was on this call?"
        )
    }

    /**
     * Checks newly ended calls in the device CallLog (gracefully returns empty if READ_CALL_LOG is denied).
     * Never returns ignored/internal numbers or calls whose pop-up was already shown.
     */
    fun checkForNewUnloggedDeviceCalls(
        context: Context,
        sinceEpochMs: Long = System.currentTimeMillis() - (12L * 60L * 60L * 1000L)
    ): List<CrmCallRecord> {
        val deviceCalls = safeReadDeviceCallLogs(context)
        if (deviceCalls.isEmpty()) return emptyList()
        val existingSaved = readCalls(context)
        val linkedKeys = existingSaved.filter { it.isLinked }.map(::callDedupKey).toSet()

        val freshCalls = deviceCalls.filter { call ->
            val phoneKey = last10Digits(call.phoneNumber)
            val key = callDedupKey(call)
            call.timestamp >= sinceEpochMs &&
                phoneKey.length >= 7 &&
                !IgnoredNumbersManager.isIgnoredOrInternal(context, call.phoneNumber) &&
                key !in linkedKeys &&
                !hasShownPostCallPopup(context, call)
        }
        freshCalls.forEach { call ->
            enqueueUnloggedCall(context, call)
        }
        return freshCalls
    }

    /**
     * Registers an Android 12+ TelephonyCallback (with PhoneStateListener fallback for Android <12)
     * to detect OFFHOOK/RINGING -> IDLE call completion. Degrades cleanly if READ_PHONE_STATE is not granted.
     */
    @Suppress("DEPRECATION")
    fun registerCallStateMonitor(
        context: Context,
        onCallEnded: () -> Unit
    ): () -> Unit {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) return {}

        val telephonyManager = runCatching {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull() ?: return {}

        val handler = Handler(Looper.getMainLooper())
        var previousState = runCatching { telephonyManager.callState }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)

        fun handleStateChange(state: Int) {
            val wasActive = previousState == TelephonyManager.CALL_STATE_OFFHOOK ||
                previousState == TelephonyManager.CALL_STATE_RINGING
            previousState = state
            if (wasActive && state == TelephonyManager.CALL_STATE_IDLE) {
                handler.postDelayed({ onCallEnded() }, 1200L)
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleStateChange(state)
                }
            }
            val registered = runCatching {
                telephonyManager.registerTelephonyCallback(ContextCompat.getMainExecutor(context), callback)
                true
            }.getOrDefault(false)
            if (registered) {
                {
                    handler.removeCallbacksAndMessages(null)
                    runCatching { telephonyManager.unregisterTelephonyCallback(callback) }
                }
            } else {
                {}
            }
        } else {
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleStateChange(state)
                }
            }
            val registered = runCatching {
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                true
            }.getOrDefault(false)
            if (registered) {
                {
                    handler.removeCallbacksAndMessages(null)
                    runCatching { telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE) }
                }
            } else {
                {}
            }
        }
    }

    /**
     * Converts display time like "11:00 AM" / "4:30 PM" to 24-hour "HH:mm" for CrmTaskRepository.
     */
    fun to24HourTimeOrEmpty(rawTime: String): String {
        val clean = rawTime.trim()
        if (clean.isBlank()) return ""
        if (Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(clean)) return clean
        val parsed = runCatching {
            SimpleDateFormat("h:mm a", Locale.US).parse(clean)
        }.getOrNull() ?: runCatching {
            SimpleDateFormat("hh:mm a", Locale.US).parse(clean)
        }.getOrNull() ?: return ""
        return SimpleDateFormat("HH:mm", Locale.US).format(parsed)
    }

    /**
     * Persists a reviewed imported call with optional real follow-up task scheduling
     * (CallFollowUpStore + CrmTaskRepository) and CRM/entity linkage.
     */
    fun saveReviewedCallAndSchedule(
        context: Context,
        call: CrmCallRecord,
        callerRole: String,
        actionOutcome: String,
        discussionNotes: String,
        followUpDate: String,
        followUpTime: String,
        assignedEmployee: String,
        linkedLeadId: String? = null,
        linkedLeadName: String? = null
    ): CrmCallRecord {
        val trimmedRole = callerRole.trim().ifBlank { "Client" }
        val trimmedOutcome = actionOutcome.trim().ifBlank { "Call Logged" }
        val cleanDate = followUpDate.trim()
        val cleanTime = followUpTime.trim()
        val noteSummary = buildString {
            append(trimmedRole).append(" • ").append(trimmedOutcome)
            if (discussionNotes.isNotBlank()) append(" — ").append(discussionNotes.trim())
            if (cleanDate.isNotBlank()) {
                append(" (Follow-up: ").append(cleanDate)
                if (cleanTime.isNotBlank()) append(" ").append(cleanTime)
                append(")")
            }
        }

        val resolvedLinkedType = when {
            linkedLeadId != null -> if (trimmedRole.contains("Broker", true)) "Broker" else "Client"
            trimmedRole.contains("Broker", true) -> "Broker"
            trimmedRole.contains("Owner", true) -> "Owner"
            trimmedRole.contains("Developer", true) -> "Developer POC"
            trimmedRole.contains("Client", true) -> "Client"
            else -> call.linkedOpportunityType
        }
        val resolvedLinkedTitle = linkedLeadName?.takeIf { it.isNotBlank() }
            ?: call.linkedOpportunityTitle
            ?: call.callerName.ifBlank { call.phoneNumber }

        val updatedCall = call.copy(
            notes = noteSummary,
            actionRequired = trimmedOutcome,
            nextFollowUpDateTime = listOf(cleanDate, cleanTime).filter { it.isNotBlank() }.joinToString(" ").ifBlank { call.nextFollowUpDateTime },
            assignedAgent = assignedEmployee.ifBlank { call.assignedAgent },
            isLinked = linkedLeadId != null || resolvedLinkedType != null,
            linkedOpportunityType = resolvedLinkedType,
            linkedOpportunityTitle = resolvedLinkedTitle,
            linkedOpportunityId = linkedLeadId ?: call.linkedOpportunityId
        )

        addOrUpdateCall(context, updatedCall)
        if (updatedCall.isLinked) {
            markPostCallPopupShown(context, updatedCall)
            removeFromUnloggedQueue(context, updatedCall)
        }

        if (cleanDate.isNotBlank()) {
            CallFollowUpStore.set(context, updatedCall.id, cleanDate)
            val taskTime = to24HourTimeOrEmpty(cleanTime)
            val origin = if (!linkedLeadId.isNullOrBlank()) CrmTaskOrigin.LEAD else CrmTaskOrigin.CALL
            val originId = linkedLeadId?.takeIf { it.isNotBlank() } ?: updatedCall.id
            val existingTasks = runCatching { CrmTaskRepository.read(context) }.getOrDefault(emptyList())
            val existing = existingTasks.firstOrNull { it.origin == origin && it.originId == originId && it.status == CrmTaskStatus.OPEN }
            val task = (existing ?: CrmTask(
                title = "$trimmedOutcome • ${resolvedLinkedTitle.ifBlank { updatedCall.phoneNumber }}",
                origin = origin,
                originId = originId
            )).copy(
                title = "$trimmedOutcome • ${resolvedLinkedTitle.ifBlank { updatedCall.phoneNumber }}",
                assignedTo = assignedEmployee.ifBlank { updatedCall.assignedAgent },
                dueDate = cleanDate,
                dueTime = taskTime,
                notes = discussionNotes.trim().ifBlank { noteSummary },
                status = CrmTaskStatus.OPEN
            )
            runCatching { CrmTaskRepository.upsert(context, task) }
        }

        if (trimmedRole.contains("Broker", true)) {
            val brokerTitle = linkedLeadName?.takeIf { it.isNotBlank() } ?: updatedCall.callerName.ifBlank { "Channel Partner" }
            saveBrokerContact(context, updatedCall.phoneNumber, brokerTitle, "", noteSummary)
        }

        logActivity(
            context = context,
            entityId = linkedLeadId ?: updatedCall.id,
            type = "Call Reviewed • $trimmedRole",
            summary = "${resolvedLinkedTitle} (${updatedCall.phoneNumber})",
            details = noteSummary,
            author = assignedEmployee
        )

        return updatedCall
    }
}

enum class PostCallRole(val label: String) {
    CLIENT_LEAD("Client"),
    PROPERTY_OWNER("Property Owner"),
    BROKER("Broker / CP"),
    DEVELOPER_POC("Developer POC"),
    FRESH_UNKNOWN("Fresh Number")
}

data class BrokerContactEntry(
    val phoneKey: String,
    val name: String,
    val firm: String = "",
    val notes: String = "",
    val taggedAt: Long = System.currentTimeMillis()
)

data class ProjectPocEntry(
    val phoneKey: String,
    val projectId: String,
    val projectName: String,
    val division: String = "",
    val contactName: String = "",
    val linkedAt: Long = System.currentTimeMillis()
)

data class PostCallMatchResult(
    val call: CrmCallRecord,
    val role: PostCallRole,
    val matchedLead: CrmLead? = null,
    val matchedProperties: List<PropertyItem> = emptyList(),
    val matchedBroker: BrokerContactEntry? = null,
    val matchedDirectoryProject: DirectoryProject? = null,
    val matchedUnderConstProject: UnderConstProject? = null,
    val matchedDeveloperPoc: ProjectPocEntry? = null,
    val displayTitle: String,
    val subtitle: String
)

object IgnoredNumbersManager {
    private const val PREFS_NAME = "crm_call_intelligence_v1"
    private const val KEY_IGNORED_PERSONAL = "ignored_personal_numbers"
    private const val KEY_INTERNAL_EMPLOYEES = "internal_employee_phones"
    private val defaultDeskPhones = setOf("9766880099", "9766668099")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalizeKey(raw: String): String = CrmCallManager.last10Digits(raw)

    fun markPersonalIgnored(context: Context, phone: String) {
        val key = normalizeKey(phone)
        if (key.length < 7) return
        val p = prefs(context)
        val obj = runCatching {
            JSONObject(p.getString(KEY_IGNORED_PERSONAL, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        obj.put(key, System.currentTimeMillis())
        p.edit().putString(KEY_IGNORED_PERSONAL, obj.toString()).apply()
        CrmCallManager.clearUnloggedQueueForNumber(context, phone)
    }

    fun unignoreNumber(context: Context, phone: String) {
        val key = normalizeKey(phone)
        if (key.isBlank()) return
        val p = prefs(context)
        val obj = runCatching {
            JSONObject(p.getString(KEY_IGNORED_PERSONAL, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        obj.remove(key)
        p.edit().putString(KEY_IGNORED_PERSONAL, obj.toString()).apply()
    }

    fun getIgnoredNumbers(context: Context): Set<String> {
        val obj = runCatching {
            JSONObject(prefs(context).getString(KEY_IGNORED_PERSONAL, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        return obj.keys().asSequence().toSet()
    }

    fun syncInternalEmployeeNumbers(context: Context, phones: Collection<String>) {
        val normalized = phones.map(::normalizeKey).filter { it.length >= 7 }.toSet()
        val arr = JSONArray()
        normalized.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_INTERNAL_EMPLOYEES, arr.toString()).apply()
    }

    fun getInternalEmployeeNumbers(context: Context): Set<String> {
        val arr = runCatching {
            JSONArray(prefs(context).getString(KEY_INTERNAL_EMPLOYEES, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val set = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.length >= 7) set.add(v)
        }
        return set
    }

    fun isIgnoredOrInternal(context: Context, phone: String): Boolean {
        val key = normalizeKey(phone)
        if (key.length < 7) return false
        return key in defaultDeskPhones ||
            key in getIgnoredNumbers(context) ||
            key in getInternalEmployeeNumbers(context)
    }
}

/**
 * Batch 9: Optional NotificationListenerService that captures incoming and missed
 * WhatsApp ("com.whatsapp") and WhatsApp Business ("com.whatsapp.w4b") voice/video calls,
 * matches the caller against CRM Leads & Inventory, and queues the call in the Post-Call Smart Queue.
 * Degrades gracefully with zero crashes if Notification Access is not granted.
 */
class TlcWhatsAppCallListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val statusBarNotification = sbn ?: return
            val pkg = statusBarNotification.packageName ?: return
            if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

            val ctx = applicationContext ?: return
            if (!CrmCallManager.isWhatsAppCallCaptureEnabled(ctx)) return

            val notification = statusBarNotification.notification ?: return
            val extras = notification.extras
            val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val rawText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val rawSubText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
            val rawBigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

            val actionTitles = notification.actions
                ?.mapNotNull { it.title?.toString()?.trim() }
                ?.joinToString(" ")
                .orEmpty()

            val combined = "$rawTitle $rawText $rawSubText $rawBigText $actionTitles".lowercase(Locale.US)
            val isCategoryCall = notification.category == Notification.CATEGORY_CALL

            val hasCallSignal = isCategoryCall ||
                combined.contains("whatsapp voice call") ||
                combined.contains("whatsapp video call") ||
                combined.contains("incoming voice call") ||
                combined.contains("incoming video call") ||
                combined.contains("missed voice call") ||
                combined.contains("missed video call") ||
                combined.contains("missed call") ||
                (combined.contains("incoming") && (combined.contains("call") || combined.contains("answer") || combined.contains("decline"))) ||
                (combined.contains("voice call") && (combined.contains("answer") || combined.contains("decline") || combined.contains("missed"))) ||
                (combined.contains("video call") && (combined.contains("answer") || combined.contains("decline") || combined.contains("missed")))

            if (!hasCallSignal) return

            val isMissed = combined.contains("missed")
            val effectiveText = rawText.ifBlank { rawBigText }.ifBlank { rawSubText }

            CrmCallManager.captureWhatsAppCallNotification(
                context = ctx,
                packageName = pkg,
                rawTitle = rawTitle,
                rawText = effectiveText,
                isMissed = isMissed,
                timestampMs = statusBarNotification.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        } catch (_: Exception) {
            // Never crash on malformed system notification extras
        }
    }
}



