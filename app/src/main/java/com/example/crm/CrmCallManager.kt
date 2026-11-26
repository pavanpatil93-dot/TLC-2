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
import com.example.data.OfflineDiskCache
import com.example.data.PropertiesLocalDatabase
import com.example.data.projects.DirectoryProject
import com.example.model.BrokerVendorRecord
import com.example.model.PropertyItem
import com.example.model.UnderConstProject
import com.example.ui.screens.CrmLead
import com.example.ui.screens.displayClientTitle
import com.example.ui.screens.isBrokerLead
import com.example.ui.screens.isOwnerLead
import com.example.ui.screens.readCrmLeads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class CallType(val label: String) {
    INCOMING("Incoming"),
    OUTGOING("Outgoing"),
    MISSED("Missed");

    companion object {
        fun fromString(value: String): CallType =
            values().firstOrNull { it.name.equals(value, true) || it.label.equals(value, true) } ?: INCOMING
    }
}

enum class UnwantedCallReason(val badgeLabel: String) {
    NONE(""),
    SPAM_TELEMARKETER("Spam"),
    PERSONAL_OR_IGNORED("Personal"),
    SHORT_OR_MISSED("Short")
}

data class CrmCallRecord(
    val id: String = UUID.randomUUID().toString(),
    val callerName: String,
    val phoneNumber: String,
    val callType: CallType,
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0,
    val notes: String = "",
    val assignedAgent: String = "",
    val actionRequired: String = "Schedule Site Visit",
    val nextFollowUpDateTime: String = "Today, 4:00 PM",
    val leadStatus: String = "Active", // Active, Pending Follow-up, Site Visit Scheduled, Negotiation, Closed
    val stagingStatus: String = "Live", // Live, Draft / Pending Review
    val isLinked: Boolean = false,
    val linkedOpportunityType: String? = null, // "Client", "Owner", "Broker", "Tenant", "Developer POC", "Ops / Vendor"
    val linkedOpportunityTitle: String? = null,
    val linkedOpportunityId: String? = null,
    val waLogged: Boolean = false,
    val pocSubRole: String = "",
    val handoverStatus: String = "",
    val handoverAssignedTo: String = "",
    val handedOverAt: Long = 0L,
    val handoverCallbackAt: Long = 0L,
    val deviceName: String? = null,
    val syncStatus: String = "PENDING", // PENDING / SYNCED
    val recordingFilename: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toRoomEntity(): com.example.data.master.CallLogEntity {
        val roleAndId = listOfNotNull(
            linkedOpportunityType?.takeIf { it.isNotBlank() },
            linkedOpportunityId?.takeIf { it.isNotBlank() }
        ).joinToString(" | ")
        return com.example.data.master.CallLogEntity(
            callId = id,
            timestamp = timestamp,
            callerName = callerName,
            phoneNumber = phoneNumber,
            direction = callType.name,
            durationSeconds = durationSeconds,
            roleOrLinkedId = roleAndId,
            linkedTitle = linkedOpportunityTitle.orEmpty(),
            notesOrAiSummary = notes,
            employeeName = assignedAgent,
            deviceName = deviceName,
            recordingFilename = recordingFilename,
            syncStatus = syncStatus.ifBlank { "PENDING" },
            updatedAt = updatedAt
        )
    }

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
        put("waLogged", waLogged)
        put("pocSubRole", pocSubRole)
        put("handoverStatus", handoverStatus)
        put("handoverAssignedTo", handoverAssignedTo)
        put("handedOverAt", handedOverAt)
        put("handoverCallbackAt", handoverCallbackAt)
        put("deviceName", deviceName.orEmpty())
        put("syncStatus", syncStatus)
        put("recordingFilename", recordingFilename.orEmpty())
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromRoomEntity(entity: com.example.data.master.CallLogEntity): CrmCallRecord {
            val parts = entity.roleOrLinkedId.split("|").map { it.trim() }.filter { it.isNotBlank() }
            val role = parts.firstOrNull()
            val linkedId = parts.getOrNull(1) ?: parts.firstOrNull()?.takeIf { it.contains("-") }
            return CrmCallRecord(
                id = entity.callId,
                callerName = entity.callerName.ifBlank { "Unknown Caller" },
                phoneNumber = entity.phoneNumber,
                callType = CallType.fromString(entity.direction),
                timestamp = entity.timestamp,
                durationSeconds = entity.durationSeconds,
                notes = entity.notesOrAiSummary,
                assignedAgent = entity.employeeName,
                isLinked = !linkedId.isNullOrBlank() || !role.isNullOrBlank(),
                linkedOpportunityType = role,
                linkedOpportunityTitle = entity.linkedTitle.ifBlank { null },
                linkedOpportunityId = linkedId,
                deviceName = entity.deviceName?.takeIf { it.isNotBlank() },
                syncStatus = entity.syncStatus.ifBlank { "SYNCED" },
                recordingFilename = entity.recordingFilename?.takeIf { it.isNotBlank() },
                updatedAt = entity.updatedAt
            )
        }

        fun fromJson(obj: JSONObject): CrmCallRecord = CrmCallRecord(
            id = obj.optString("id", UUID.randomUUID().toString()),
            callerName = obj.optString("callerName", "Unknown Caller"),
            phoneNumber = obj.optString("phoneNumber", ""),
            callType = CallType.fromString(obj.optString("callType", "INCOMING")),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            durationSeconds = obj.optInt("durationSeconds", 0),
            notes = obj.optString("notes", ""),
            assignedAgent = obj.optString("assignedAgent", "").let {
                if (it.equals("Rahul Sharma", ignoreCase = true)) "" else it
            },
            actionRequired = obj.optString("actionRequired", "Schedule Site Visit"),
            nextFollowUpDateTime = obj.optString("nextFollowUpDateTime", "Today, 4:00 PM"),
            leadStatus = obj.optString("leadStatus", "Active"),
            stagingStatus = obj.optString("stagingStatus", "Live"),
            isLinked = obj.optBoolean("isLinked", false),
            linkedOpportunityType = obj.optString("linkedOpportunityType").ifBlank { null },
            linkedOpportunityTitle = obj.optString("linkedOpportunityTitle").ifBlank { null },
            linkedOpportunityId = obj.optString("linkedOpportunityId").ifBlank { null },
            waLogged = obj.optBoolean("waLogged", false),
            pocSubRole = obj.optString("pocSubRole", ""),
            handoverStatus = obj.optString("handoverStatus", ""),
            handoverAssignedTo = obj.optString("handoverAssignedTo", ""),
            handedOverAt = obj.optLong("handedOverAt", 0L),
            handoverCallbackAt = obj.optLong("handoverCallbackAt", 0L),
            deviceName = obj.optString("deviceName", "").ifBlank { null },
            syncStatus = obj.optString("syncStatus", "SYNCED").ifBlank { "SYNCED" },
            recordingFilename = obj.optString("recordingFilename", "").ifBlank { null },
            updatedAt = obj.optLong("updatedAt", obj.optLong("timestamp", System.currentTimeMillis()))
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
        val diskCached = com.example.data.OfflineDiskCache.loadCallLogs(context)
        if (diskCached.isNotEmpty()) return diskCached
        val fromPrefs = runCatching {
            val raw = com.example.data.auditedCrmPreferences(context, PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CALLS, "[]") ?: "[]"
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                CrmCallRecord.fromJson(array.getJSONObject(i))
            }
        }.getOrDefault(emptyList())
        if (fromPrefs.isNotEmpty()) {
            com.example.data.OfflineDiskCache.saveCallLogs(context, fromPrefs)
        }
        return fromPrefs
    }

    fun saveCalls(context: Context, calls: List<CrmCallRecord>, syncToRoom: Boolean = true) {
        val cleaned = calls.filterNot { it.id.startsWith("demo_call_", ignoreCase = true) }
        com.example.data.OfflineDiskCache.saveCallLogs(context, cleaned)
        val array = JSONArray()
        cleaned.forEach { array.put(it.toJson()) }
        com.example.data.auditedCrmPreferences(context, PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CALLS, array.toString())
            .apply()
        if (syncToRoom) {
            callSyncScope.launch {
                runCatching {
                    val dao = com.example.data.master.MasterProjectDatabase.getDatabase(context).masterProjectDao()
                    dao.upsertCallLogs(cleaned.map { it.toRoomEntity() })
                }
            }
        }
    }

    /**
     * Persists calls pulled/reconciled from Google Sheets without re-triggering push work
     * or blocking non-admin users via manual DELETE_RECORD audit guards.
     */
    fun saveCallsFromPull(context: Context, calls: List<CrmCallRecord>) {
        val cleaned = calls.filterNot { it.id.startsWith("demo_call_", ignoreCase = true) }
        com.example.data.OfflineDiskCache.saveCallLogs(context, cleaned)
        val array = JSONArray()
        cleaned.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CALLS, array.toString())
            .apply()
    }

    private val callSyncScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    private fun pushCallAsync(context: Context, call: CrmCallRecord) {
        val appContext = context.applicationContext ?: context
        // Enqueue durable WorkManager OneTimeWorkRequest (NetworkType.CONNECTED) immediately
        runCatching { com.example.data.BackgroundSyncScheduler.enqueueImmediatePush(appContext) }
        callSyncScope.launch {
            runCatching {
                val repo = com.example.data.SheetsRepository(appContext, startForegroundSync = false)
                try {
                    repo.pushCallLogToMasterSheet(call)
                } finally {
                    repo.close()
                }
            }
        }
    }

    fun markCallSyncedLocal(context: Context, callId: String, updatedAt: Long = System.currentTimeMillis()) {
        val current = readCalls(context).toMutableList()
        val idx = current.indexOfFirst { it.id == callId }
        if (idx >= 0 && current[idx].syncStatus != "SYNCED") {
            current[idx] = current[idx].copy(syncStatus = "SYNCED", updatedAt = updatedAt)
            saveCalls(context, current, syncToRoom = false)
        }
        callSyncScope.launch {
            runCatching {
                com.example.data.master.MasterProjectDatabase.getDatabase(context)
                    .masterProjectDao()
                    .markCallLogSynced(callId, updatedAt)
            }
        }
    }

    fun addOrUpdateCall(context: Context, call: CrmCallRecord) {
        val deviceName = call.deviceName?.takeIf { it.isNotBlank() }
            ?: com.example.data.DeviceIdentityManager.getFriendlyDeviceName(context)
        val safeId = call.id.trim().takeIf { it.isNotBlank() }
            ?: com.example.data.DeviceIdentityManager.generateCallLogId(context, call.timestamp)
        val loggedEmployee = call.assignedAgent.trim().ifBlank {
            runCatching { com.example.data.UserSettingsManager(context).userProfile.value.username.trim() }.getOrDefault("")
        }
        val stampedCall = call.copy(
            id = safeId,
            assignedAgent = loggedEmployee,
            deviceName = deviceName,
            syncStatus = "PENDING",
            updatedAt = System.currentTimeMillis()
        )
        val taggedCall = tagInternalTeamCallIfNeeded(context, stampedCall)
        val current = readCalls(context).toMutableList()
        val index = current.indexOfFirst { it.id == taggedCall.id }
        if (index >= 0) {
            current[index] = taggedCall
        } else {
            current.add(0, taggedCall)
        }
        saveCalls(context, current)
        if (taggedCall.callType == CallType.OUTGOING || (taggedCall.callType == CallType.INCOMING && taggedCall.durationSeconds > 0)) {
            runCatching { TeamHandoverStore.reconcileWithCallLogs(context, current) }
        }
        pushCallAsync(context, taggedCall)
    }

    /**
     * Batch 18: Header WhatsApp Auto-Log.
     * Tapping the green WhatsApp button in PostCallSmartSheet automatically logs/updates the call
     * with "Connected on WhatsApp" and marks `waLogged = true`.
     */
    fun logWhatsAppTouchOnCall(context: Context, call: CrmCallRecord): CrmCallRecord {
        val existingNotes = call.notes.trim()
        val updatedNotes = if (existingNotes.contains("Connected on WhatsApp", ignoreCase = true)) {
            existingNotes
        } else if (existingNotes.isBlank() || existingNotes.equals("Imported from device call log", ignoreCase = true)) {
            "Connected on WhatsApp"
        } else {
            "$existingNotes • Connected on WhatsApp"
        }
        val updated = call.copy(
            notes = updatedNotes,
            waLogged = true
        )
        addOrUpdateCall(context, updated)
        return updated
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
        val updated = current.copy(isLinked = true, linkedOpportunityType = oppType, linkedOpportunityTitle = oppTitle, linkedOpportunityId = oppId)
        calls[index] = updated
        saveCalls(context, calls)
        logActivity(context, oppId, "Opportunity Created", "Linked saved $oppType Opportunity from Call ($oppTitle)", "Linked to Call ID: $callId")
        pushCallAsync(context, updated)
        return true
    }

    /**
     * Auto-links a Call Log to a newly saved or opened Lead / Property / Tenant / Broker / Project,
     * marks the call as Handled (`isLinked = true`, `stagingStatus = "Live"`), clears it from the
     * unlogged queue, persists to Room + cache, and triggers immediate two-way sheet push.
     */
    @Synchronized
    fun markCallHandledAndLinked(
        context: Context,
        callId: String,
        fallbackCall: CrmCallRecord? = null,
        oppType: String,
        oppTitle: String,
        oppId: String,
        notesOverride: String = ""
    ): Boolean {
        if (callId.isBlank() || oppId.isBlank()) return false
        val calls = readCalls(context).toMutableList()
        val index = calls.indexOfFirst { it.id == callId }
        val base = if (index >= 0) calls[index] else fallbackCall ?: return false
        val mergedNotes = when {
            notesOverride.isNotBlank() && !base.notes.contains(notesOverride.trim(), ignoreCase = true) ->
                if (base.notes.isBlank() || base.notes.equals("Imported from device call log", ignoreCase = true)) {
                    notesOverride.trim()
                } else {
                    "${base.notes.trim()}\n${notesOverride.trim()}"
                }
            else -> base.notes
        }
        val updated = base.copy(
            isLinked = true,
            linkedOpportunityType = oppType.ifBlank { base.linkedOpportunityType ?: "Client" },
            linkedOpportunityTitle = oppTitle.ifBlank { base.linkedOpportunityTitle ?: oppId },
            linkedOpportunityId = oppId,
            stagingStatus = "Live",
            leadStatus = if (base.leadStatus.isBlank() || base.leadStatus.equals("Unlogged", true)) "Handled" else base.leadStatus,
            notes = mergedNotes,
            syncStatus = "PENDING",
            updatedAt = System.currentTimeMillis()
        )
        if (index >= 0) {
            calls[index] = updated
        } else {
            calls.add(0, updated)
        }
        saveCalls(context, calls)
        removeFromUnloggedQueue(context, updated)
        markPostCallPopupShown(context, updated)
        logActivity(
            context = context,
            entityId = oppId,
            type = "Call Auto-Linked",
            summary = "Auto-linked $oppType call (${updated.callerName.ifBlank { updated.phoneNumber }})",
            details = "Call ID: $callId • ${mergedNotes.take(200)}"
        )
        pushCallAsync(context, updated)
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

                while (list.size < 200 && it.moveToNext()) {
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

                    val deviceTag = com.example.data.DeviceIdentityManager.getDeviceTag(context)
                    val friendlyDevice = com.example.data.DeviceIdentityManager.getFriendlyDeviceName(context)
                    val last4 = rawNum.filter { ch -> ch.isDigit() }.takeLast(4).ifBlank { "0000" }
                    val employeeName = runCatching {
                        com.example.data.UserSettingsManager(context).userProfile.value.username.trim()
                    }.getOrDefault("")
                    list.add(
                        CrmCallRecord(
                            id = "CALL-${deviceTag}-${dateVal}_${last4}",
                            callerName = rawName.ifBlank { "Unknown" },
                            phoneNumber = ContactUtils.normalizePhoneNumber(rawNum),
                            callType = callType,
                            timestamp = dateVal,
                            durationSeconds = durVal,
                            notes = "Imported from device call log",
                            assignedAgent = employeeName,
                            isLinked = false,
                            deviceName = friendlyDevice,
                            syncStatus = "PENDING",
                            updatedAt = System.currentTimeMillis()
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
     * Sample/demo call history has been permanently removed so local storage strictly reflects
     * real device calls and synced `Call Logs` rows from TLC Master sheet.
     */
    fun getDemoCalls(): List<CrmCallRecord> = emptyList()

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

        if (resolvedPhone.isNotBlank() && IgnoredNumbersManager.isPersonalIgnored(context, resolvedPhone)) {
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

        val baseRecord = CrmCallRecord(
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

        val record = tagInternalTeamCallIfNeeded(context, baseRecord)
        addOrUpdateCall(context, record)
        if (!isInternalTeamCall(context, record)) {
            enqueueUnloggedCall(context, record)
        }
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
                IgnoredNumbersManager.isIgnoredOrInternal(context, it.phoneNumber) ||
                    isInternalTeamCall(context, it) ||
                    isCallSkipped(context, it)
            }.sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())
    }

    fun enqueueUnloggedCall(context: Context, call: CrmCallRecord) {
        if (IgnoredNumbersManager.isIgnoredOrInternal(context, call.phoneNumber)) return
        if (isInternalTeamCall(context, call)) return
        if (isCallSkipped(context, call)) return
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
     * 6-Role Database Matcher:
     * 1. CRM Leads (Client)
     * 2. Tenants (Post-Lease Management)
     * 3. Property Inventory (Owner / Landlord)
     * 4. Brokers / Channel Partners & Ops / Vendors
     * 5. Projects (Developer Sales POC / Sourcing Mgr)
     * 6. Fresh / Unknown Number
     */
    fun matchCallToDatabase(
        context: Context,
        call: CrmCallRecord,
        leads: List<CrmLead>,
        inventory: List<PropertyItem>,
        directoryProjects: List<DirectoryProject>,
        underConstProjects: List<UnderConstProject> = emptyList(),
        tenants: List<OfflineDiskCache.TenantCacheRecord> = OfflineDiskCache.loadTenants(context),
        brokersVendors: List<BrokerVendorRecord> = OfflineDiskCache.loadBrokersVendors(context)
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
                val tenantByName = tenants.firstOrNull { t ->
                    t.tenantName.trim().equals(callerNameClean, ignoreCase = true)
                }
                if (tenantByName != null) {
                    return PostCallMatchResult(
                        call = call.copy(phoneNumber = call.phoneNumber.ifBlank { tenantByName.contactNumber }),
                        role = PostCallRole.TENANT,
                        matchedTenant = tenantByName,
                        displayTitle = tenantByName.tenantName,
                        subtitle = "Tenant • ${tenantByName.projectName.ifBlank { tenantByName.propertyCode }}"
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
                val bvByName = brokersVendors.firstOrNull { bv ->
                    bv.name.trim().equals(callerNameClean, ignoreCase = true)
                }
                if (bvByName != null) {
                    val isOps = bvByName.roleCategory.contains("Ops", true) || bvByName.roleCategory.contains("Vendor", true)
                    return PostCallMatchResult(
                        call = call.copy(phoneNumber = call.phoneNumber.ifBlank { bvByName.phone }),
                        role = if (isOps) PostCallRole.OPS_VENDOR else PostCallRole.BROKER,
                        matchedBrokerVendor = bvByName,
                        displayTitle = bvByName.name,
                        subtitle = "${bvByName.subRole.ifBlank { bvByName.roleCategory }} • ${bvByName.firmName.ifBlank { bvByName.operatingAreas }}".trimEnd(' ', '•')
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

        // 2. Tenants (Post-Lease Management)
        val matchedTenant = tenants.firstOrNull { tenant ->
            phoneKey in extractCandidatePhoneKeys(tenant.contactNumber)
        }
        if (matchedTenant != null) {
            val tenantTitle = matchedTenant.tenantName.ifBlank { callerNameClean.ifBlank { "Tenant" } }
            val unitLabel = listOf(matchedTenant.projectName, matchedTenant.unitNumber.ifBlank { matchedTenant.propertyCode })
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            return PostCallMatchResult(
                call = call,
                role = PostCallRole.TENANT,
                matchedTenant = matchedTenant,
                displayTitle = tenantTitle,
                subtitle = if (unitLabel.isNotBlank()) "Tenant • $unitLabel" else "Active Tenant"
            )
        }

        // 3. Property Inventory (Owner / Landlord matching ownerContact)
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

        // 4. Brokers & Vendors Directory (Master Sheet + Local)
        val matchedBv = brokersVendors.firstOrNull { bv ->
            phoneKey in extractCandidatePhoneKeys(bv.phone)
        }
        if (matchedBv != null) {
            val isOps = matchedBv.roleCategory.contains("Ops", ignoreCase = true) ||
                matchedBv.roleCategory.contains("Vendor", ignoreCase = true)
            val bvProps = if (!isOps) {
                inventory.filter { prop -> phoneKey in extractCandidatePhoneKeys(prop.brokerContact) }
            } else emptyList()
            return PostCallMatchResult(
                call = call,
                role = if (isOps) PostCallRole.OPS_VENDOR else PostCallRole.BROKER,
                matchedProperties = bvProps,
                matchedBroker = if (!isOps) BrokerContactEntry(
                    phoneKey = phoneKey,
                    name = matchedBv.name,
                    firm = matchedBv.firmName,
                    notes = matchedBv.notes
                ) else null,
                matchedBrokerVendor = matchedBv,
                displayTitle = matchedBv.name.ifBlank { if (isOps) "Ops / Vendor" else "Channel Partner" },
                subtitle = listOf(matchedBv.subRole.ifBlank { matchedBv.roleCategory }, matchedBv.firmName.ifBlank { matchedBv.operatingAreas })
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
            )
        }

        // 5. Legacy Brokers / Channel Partners (brokerContact or saved broker calls/registry)
        val savedBrokers = getBrokerContacts(context)
        val brokerEntry = savedBrokers[phoneKey]
        val brokerProps = inventory.filter { prop ->
            phoneKey in extractCandidatePhoneKeys(prop.brokerContact)
        }
        val pastCallsForPhone = readCalls(context).filter { existing ->
            last10Digits(existing.phoneNumber) == phoneKey
        }
        val pastOpsCall = pastCallsForPhone.firstOrNull {
            it.linkedOpportunityType?.contains("Ops", ignoreCase = true) == true ||
                it.linkedOpportunityType?.contains("Vendor", ignoreCase = true) == true
        }
        if (pastOpsCall != null) {
            return PostCallMatchResult(
                call = call,
                role = PostCallRole.OPS_VENDOR,
                displayTitle = pastOpsCall.callerName.ifBlank { call.callerName.ifBlank { "Ops / Vendor" } },
                subtitle = pastOpsCall.pocSubRole.ifBlank { "Ops / Vendor" }
            )
        }
        val pastBrokerCall = pastCallsForPhone.firstOrNull { existing ->
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

        // 6. Projects (Developer Sales POC / Sourcing Manager)
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

        // 7. Fresh / Unknown Number
        val fallbackName = call.callerName
            .takeIf { it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true) }
            ?: ContactUtils.formatDisplayPhone(call.phoneNumber).ifBlank { call.phoneNumber }
        return PostCallMatchResult(
            call = call,
            role = PostCallRole.FRESH_UNKNOWN,
            displayTitle = fallbackName,
            subtitle = "Who was on this call?"
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
        val existingKeys = existingSaved.map(::callDedupKey).toSet()
        val linkedKeys = existingSaved.filter { it.isLinked }.map(::callDedupKey).toSet()

        // Batch 21: Auto-save & tag colleague-to-colleague calls as "Internal Team" for metrics without firing popup
        var newDeviceCallsDetected = false
        deviceCalls.forEach { call ->
            if (call.timestamp >= sinceEpochMs && IgnoredNumbersManager.isInternalEmployeePhone(context, call.phoneNumber)) {
                val key = callDedupKey(call)
                if (key !in existingKeys) {
                    newDeviceCallsDetected = true
                    addOrUpdateCall(context, tagInternalTeamCallIfNeeded(context, call))
                }
            } else if (call.timestamp >= sinceEpochMs &&
                (call.callType == CallType.OUTGOING || (call.callType == CallType.INCOMING && call.durationSeconds > 0))
            ) {
                newDeviceCallsDetected = true
            }
        }
        if (newDeviceCallsDetected) {
            val mergedForReconcile = (readCalls(context) + deviceCalls.filter { it.timestamp >= sinceEpochMs })
                .distinctBy(::callDedupKey)
            runCatching { TeamHandoverStore.reconcileWithCallLogs(context, mergedForReconcile) }
        }

        val freshCalls = deviceCalls.filter { call ->
            val phoneKey = last10Digits(call.phoneNumber)
            val key = callDedupKey(call)
            call.timestamp >= sinceEpochMs &&
                phoneKey.length >= 7 &&
                !IgnoredNumbersManager.isIgnoredOrInternal(context, call.phoneNumber) &&
                !isInternalTeamCall(context, call) &&
                !isCallSkipped(context, call) &&
                key !in linkedKeys &&
                !hasShownPostCallPopup(context, call)
        }
        freshCalls.take(15).forEach { call ->
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

        unskipCall(context, updatedCall)
        return updatedCall
    }

    // =========================================================================
    // Batch 20: Skipped Calls Store, Missed Call WA Callback & Last Called By
    // =========================================================================

    private const val FILE_SKIPPED_CALLS = "skipped_call_ids.json"
    private val skippedKeysSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile
    private var skippedHydrated = false

    const val MISSED_CALL_WA_CALLBACK_MESSAGE =
        "Sorry I missed your call from The Leasing Company—calling you back shortly, or feel free to share your requirement here."

    private fun ensureSkippedHydrated(context: Context) {
        if (skippedHydrated) return
        synchronized(this) {
            if (skippedHydrated) return
            val file = File(context.filesDir, FILE_SKIPPED_CALLS)
            if (file.exists()) {
                runCatching {
                    val arr = JSONArray(file.readText(Charsets.UTF_8))
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let { skippedKeysSet.add(it) }
                    }
                }
            }
            skippedHydrated = true
        }
    }

    private fun persistSkippedAsync(context: Context) {
        val appContext = context.applicationContext ?: context
        val snapshot = skippedKeysSet.toList()
        callSyncScope.launch {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it) }
                File(appContext.filesDir, FILE_SKIPPED_CALLS).writeText(arr.toString(), Charsets.UTF_8)
            }
        }
    }

    fun isCallSkipped(context: Context, call: CrmCallRecord): Boolean {
        if (call.isLinked) return false
        ensureSkippedHydrated(context)
        val dedup = callDedupKey(call)
        return call.id in skippedKeysSet || dedup in skippedKeysSet
    }

    fun isCallIdSkipped(context: Context, callId: String): Boolean {
        ensureSkippedHydrated(context)
        return callId in skippedKeysSet
    }

    fun getSkippedCallKeys(context: Context): Set<String> {
        ensureSkippedHydrated(context)
        return skippedKeysSet.toSet()
    }

    fun markCallSkipped(context: Context, call: CrmCallRecord) {
        ensureSkippedHydrated(context)
        if (call.id.isNotBlank()) skippedKeysSet.add(call.id)
        val dedup = callDedupKey(call)
        if (dedup.isNotBlank()) skippedKeysSet.add(dedup)
        persistSkippedAsync(context)
        markPostCallPopupShown(context, call)
        removeFromUnloggedQueue(context, call)
    }

    fun markCallsSkipped(context: Context, callsToIgnore: List<CrmCallRecord>) {
        if (callsToIgnore.isEmpty()) return
        ensureSkippedHydrated(context)
        for (call in callsToIgnore) {
            if (call.id.isNotBlank()) skippedKeysSet.add(call.id)
            val dedup = callDedupKey(call)
            if (dedup.isNotBlank()) skippedKeysSet.add(dedup)
            markPostCallPopupShown(context, call)
            removeFromUnloggedQueue(context, call)
        }
        persistSkippedAsync(context)
    }

    fun unskipCall(context: Context, call: CrmCallRecord) {
        ensureSkippedHydrated(context)
        skippedKeysSet.remove(call.id)
        skippedKeysSet.remove(callDedupKey(call))
        persistSkippedAsync(context)
    }

    fun unskipCalls(context: Context, callsToRestore: List<CrmCallRecord>) {
        if (callsToRestore.isEmpty()) return
        ensureSkippedHydrated(context)
        for (call in callsToRestore) {
            skippedKeysSet.remove(call.id)
            skippedKeysSet.remove(callDedupKey(call))
        }
        persistSkippedAsync(context)
    }

    private val SPAM_NAME_REGEX = Regex(
        """\b(spam|suspected\s+spam|scam|fraud|telemarket\w*|loan\s+offer|credit\s+card|insurance\s+offer|policybazaar|bajaj\s+fin\w*|swiggy|zomato|amazon\s+delivery|flipkart|bluedart|delhivery|ekart|courier|delivery\s+boy|customer\s+care|ivr|promotional|automated)\b""",
        RegexOption.IGNORE_CASE
    )
    private val PERSONAL_NAME_REGEX = Regex(
        """\b(mom|mummy|maa|aai|papa|dad|baba|wife|husband|home|bro|brother|sister|didi|tai|family|personal|maid|cook|driver|milkman|watchman|plumber|electrician|doctor|salon|gym)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Deterministic unwanted-call auto-tagger for fast batch cleanup.
     * Already-linked business calls are never tagged as unwanted.
     */
    fun classifyUnwantedCall(
        call: CrmCallRecord,
        isPersonalOrIgnoredNumber: Boolean = false
    ): UnwantedCallReason {
        if (call.isLinked) return UnwantedCallReason.NONE

        val rawDigits = call.phoneNumber.filter { it.isDigit() }
        val normalizedLocal = when {
            rawDigits.startsWith("91") && rawDigits.length >= 12 -> rawDigits.drop(2)
            rawDigits.startsWith("0") && rawDigits.length >= 11 -> rawDigits.drop(1)
            else -> rawDigits
        }

        val isTelemarketerPrefix = normalizedLocal.startsWith("140") ||
            normalizedLocal.startsWith("160") ||
            normalizedLocal.startsWith("1800") ||
            normalizedLocal.startsWith("1860") ||
            (rawDigits.isNotEmpty() && rawDigits.length <= 6)

        if (isTelemarketerPrefix || SPAM_NAME_REGEX.containsMatchIn(call.callerName)) {
            return UnwantedCallReason.SPAM_TELEMARKETER
        }

        if (isPersonalOrIgnoredNumber || PERSONAL_NAME_REGEX.containsMatchIn(call.callerName)) {
            return UnwantedCallReason.PERSONAL_OR_IGNORED
        }

        if (call.callType == CallType.MISSED || call.durationSeconds in 0..9) {
            return UnwantedCallReason.SHORT_OR_MISSED
        }

        return UnwantedCallReason.NONE
    }

    fun classifyUnwantedCall(context: Context, call: CrmCallRecord): UnwantedCallReason {
        val ignored = IgnoredNumbersManager.isIgnoredOrInternal(context, call.phoneNumber)
        return classifyUnwantedCall(call, isPersonalOrIgnoredNumber = ignored)
    }

    /**
     * Batch 20: 1-tap Missed Call "WA Callback" action.
     * Opens https://wa.me/91<Caller10DigitMobile> with the polite callback message
     * and marks the missed call handled so it clears from the unlinked queue.
     */
    fun triggerMissedCallWaCallback(
        context: Context,
        call: CrmCallRecord,
        assignedAgent: String = "",
        openBrowserOrApp: Boolean = true
    ): CrmCallRecord {
        val existingNotes = call.notes.trim()
        val updatedNotes = when {
            existingNotes.contains("WA Callback sent", ignoreCase = true) -> existingNotes
            existingNotes.isBlank() || existingNotes.equals("Imported from device call log", ignoreCase = true) ->
                "WA Callback sent • $MISSED_CALL_WA_CALLBACK_MESSAGE"
            else -> "$existingNotes • WA Callback sent"
        }
        val updated = call.copy(
            notes = updatedNotes,
            actionRequired = "WA Callback Sent",
            assignedAgent = assignedAgent.ifBlank { call.assignedAgent },
            waLogged = true,
            isLinked = true,
            linkedOpportunityType = call.linkedOpportunityType ?: "Missed Call WA Callback",
            linkedOpportunityTitle = call.linkedOpportunityTitle ?: call.callerName.ifBlank { call.phoneNumber }
        )
        unskipCall(context, updated)
        addOrUpdateCall(context, updated)
        markPostCallPopupShown(context, updated)
        removeFromUnloggedQueue(context, updated)
        if (openBrowserOrApp && call.phoneNumber.isNotBlank()) {
            openWhatsAppChat(context, call.phoneNumber, MISSED_CALL_WA_CALLBACK_MESSAGE)
        }
        return updated
    }

    /**
     * Batch 20: Resolves the most recent call from synced/local Call Logs matching any of the
     * target phone numbers or entityId (Property ID/Code, CRM Lead ID, or Tenant ID).
     */
    fun findLastCallForTarget(
        context: Context,
        phones: List<String>,
        entityIds: List<String> = emptyList(),
        candidateCalls: List<CrmCallRecord>? = null
    ): CrmCallRecord? {
        val phoneKeys = phones
            .flatMap(::extractCandidatePhoneKeys)
            .filter { it.length >= 7 }
            .toSet()
        val cleanIds = entityIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (phoneKeys.isEmpty() && cleanIds.isEmpty()) return null

        val source = candidateCalls ?: runCatching { readCalls(context) }.getOrDefault(emptyList())
        return source
            .asSequence()
            .filter { call ->
                val callPhoneKey = last10Digits(call.phoneNumber)
                val matchesPhone = callPhoneKey.length >= 7 && callPhoneKey in phoneKeys
                val matchesEntityId = !call.linkedOpportunityId.isNullOrBlank() &&
                    cleanIds.any { id -> id.equals(call.linkedOpportunityId, ignoreCase = true) }
                matchesPhone || matchesEntityId
            }
            .maxByOrNull { it.timestamp }
    }

    /**
     * Formats the exact Batch 20 "Last Called By" banner string:
     * "Last called by <Employee Name> · <Date/Time> · '<Note>'"
     */
    fun formatLastCalledByBannerText(call: CrmCallRecord): String {
        val employee = call.assignedAgent.trim().ifBlank { "TLC Team" }
        val dateTime = formatTimestamp(call.timestamp)
        val rawNote = call.notes.trim()
            .takeUnless { it.equals("Imported from device call log", ignoreCase = true) }
            .orEmpty()
            .ifBlank { call.actionRequired.trim().ifBlank { "${call.callType.label} Call" } }
            .replace("\n", " ")
            .let { if (it.length > 72) it.take(69) + "..." else it }
        return "Last called by $employee · $dateTime · '$rawNote'"
    }

    // =========================================================================
    // Batch 21: Internal Team Call Detection, Auto-Tagging & Re-Tagging
    // =========================================================================

    fun isInternalTeamCall(context: Context, call: CrmCallRecord): Boolean {
        if (call.linkedOpportunityType.equals("Internal Team", ignoreCase = true)) return true
        if (IgnoredNumbersManager.isPersonalIgnored(context, call.phoneNumber)) return false
        return IgnoredNumbersManager.isInternalEmployeePhone(context, call.phoneNumber)
    }

    fun tagInternalTeamCallIfNeeded(context: Context, call: CrmCallRecord): CrmCallRecord {
        if (IgnoredNumbersManager.isPersonalIgnored(context, call.phoneNumber)) return call
        val matchedEmp = IgnoredNumbersManager.findEmployeeForPhone(context, call.phoneNumber) ?: return call
        val roleSuffix = matchedEmp.role.trim().ifBlank { "Team" }
        val cleanName = if (
            call.callerName.isBlank() ||
            call.callerName.equals("Unknown", ignoreCase = true) ||
            call.callerName.equals("Unknown Caller", ignoreCase = true) ||
            call.callerName.filter { it.isDigit() }.length >= 7
        ) {
            matchedEmp.name
        } else {
            call.callerName
        }
        return call.copy(
            callerName = cleanName,
            isLinked = true,
            linkedOpportunityType = "Internal Team",
            linkedOpportunityTitle = "${matchedEmp.name} • $roleSuffix",
            linkedOpportunityId = matchedEmp.id.ifBlank { "EMP-${matchedEmp.name.uppercase(Locale.US).take(10)}" },
            stagingStatus = "Internal Team"
        )
    }

    fun retagInternalTeamCalls(context: Context): Int {
        val current = readCalls(context)
        if (current.isEmpty()) return 0
        var updatedCount = 0
        val next = current.map { call ->
            if (!call.linkedOpportunityType.equals("Internal Team", ignoreCase = true) &&
                IgnoredNumbersManager.isInternalEmployeePhone(context, call.phoneNumber) &&
                !IgnoredNumbersManager.isPersonalIgnored(context, call.phoneNumber)
            ) {
                val tagged = tagInternalTeamCallIfNeeded(context, call)
                if (tagged != call) {
                    updatedCount++
                    removeFromUnloggedQueue(context, tagged)
                }
                tagged
            } else {
                call
            }
        }
        if (updatedCount > 0) {
            saveCalls(context, next)
        }
        return updatedCount
    }

    fun noteCountsAsLogged(notes: String): Boolean {
        val clean = notes.trim()
        if (clean.isBlank()) return false
        return !clean.equals("Imported from device call log", ignoreCase = true) &&
            !clean.equals("WhatsApp Call Notification", ignoreCase = true)
    }
}

enum class PostCallRole(val label: String) {
    CLIENT_LEAD("Client Lead"),
    PROPERTY_OWNER("Owner"),
    BROKER("Broker / CP"),
    DEVELOPER_POC("Developer POC"),
    TENANT("Tenant"),
    OPS_VENDOR("Ops / Vendor"),
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
    val matchedTenant: OfflineDiskCache.TenantCacheRecord? = null,
    val matchedBrokerVendor: BrokerVendorRecord? = null,
    val displayTitle: String,
    val subtitle: String
)

/**
 * Batch 18: Lightweight zero-lag Muted Numbers store.
 * Keeps normalized 10-digit numbers in a thread-safe HashSet hydrated lazily once from
 * `context.filesDir/muted_call_numbers.json` and flushed asynchronously on IO.
 */
object IgnoredNumbersManager {
    private const val PREFS_NAME = "crm_call_intelligence_v1"
    private const val KEY_IGNORED_PERSONAL = "ignored_personal_numbers"
    private const val KEY_INTERNAL_EMPLOYEES = "internal_employee_phones"
    private const val FILE_MUTED_NUMBERS = "muted_call_numbers.json"
    private val defaultDeskPhones = setOf("9766880099", "9766668099")

    private val mutedSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val internalSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile
    private var mutedHydrated = false
    @Volatile
    private var internalHydrated = false

    private val ioScope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalizeKey(raw: String): String = CrmCallManager.last10Digits(raw)

    private fun ensureMutedHydrated(context: Context) {
        if (mutedHydrated) return
        synchronized(this) {
            if (mutedHydrated) return
            val file = File(context.filesDir, FILE_MUTED_NUMBERS)
            if (file.exists()) {
                runCatching {
                    val arr = JSONArray(file.readText(Charsets.UTF_8))
                    for (i in 0 until arr.length()) {
                        val v = normalizeKey(arr.optString(i))
                        if (v.length >= 7) mutedSet.add(v)
                    }
                }
            }
            // One-time migration from legacy SharedPreferences map
            val legacyObj = runCatching {
                JSONObject(prefs(context).getString(KEY_IGNORED_PERSONAL, "{}") ?: "{}")
            }.getOrNull()
            if (legacyObj != null && legacyObj.length() > 0) {
                legacyObj.keys().forEach { key ->
                    val norm = normalizeKey(key)
                    if (norm.length >= 7) mutedSet.add(norm)
                }
                persistMutedAsync(context)
            }
            mutedHydrated = true
        }
    }

    private fun ensureInternalHydrated(context: Context) {
        if (internalHydrated) return
        synchronized(this) {
            if (internalHydrated) return
            val arr = runCatching {
                JSONArray(prefs(context).getString(KEY_INTERNAL_EMPLOYEES, "[]") ?: "[]")
            }.getOrDefault(JSONArray())
            for (i in 0 until arr.length()) {
                val v = normalizeKey(arr.optString(i))
                if (v.length >= 7) internalSet.add(v)
            }
            internalHydrated = true
        }
    }

    private fun persistMutedAsync(context: Context) {
        val snapshot = mutedSet.toList()
        val appCtx = context.applicationContext ?: context
        ioScope.launch {
            runCatching {
                val arr = JSONArray().apply { snapshot.forEach { put(it) } }
                File(appCtx.filesDir, FILE_MUTED_NUMBERS).writeText(arr.toString(), Charsets.UTF_8)
                val legacyObj = JSONObject().apply {
                    snapshot.forEach { put(it, System.currentTimeMillis()) }
                }
                prefs(appCtx).edit().putString(KEY_IGNORED_PERSONAL, legacyObj.toString()).apply()
            }
        }
    }

    fun markPersonalIgnored(context: Context, phone: String) {
        ensureMutedHydrated(context)
        val key = normalizeKey(phone)
        if (key.length < 7) return
        mutedSet.add(key)
        persistMutedAsync(context)
        CrmCallManager.clearUnloggedQueueForNumber(context, phone)
    }

    fun unignoreNumber(context: Context, phone: String) {
        ensureMutedHydrated(context)
        val key = normalizeKey(phone)
        if (key.isBlank()) return
        mutedSet.remove(key)
        persistMutedAsync(context)
    }

    fun getIgnoredNumbers(context: Context): Set<String> {
        ensureMutedHydrated(context)
        return mutedSet.toSet()
    }

    fun syncInternalEmployeeNumbers(context: Context, phones: Collection<String>) {
        ensureInternalHydrated(context)
        val normalized = phones.map(::normalizeKey).filter { it.length >= 7 }.toSet()
        internalSet.clear()
        internalSet.addAll(normalized)
        val appCtx = context.applicationContext ?: context
        ioScope.launch {
            val arr = JSONArray().apply { normalized.forEach { put(it) } }
            prefs(appCtx).edit().putString(KEY_INTERNAL_EMPLOYEES, arr.toString()).apply()
        }
    }

    fun getInternalEmployeeNumbers(context: Context): Set<String> {
        ensureInternalHydrated(context)
        return internalSet.toSet()
    }

    fun isPersonalIgnored(context: Context, phone: String): Boolean {
        val key = normalizeKey(phone)
        if (key.length < 7) return false
        ensureMutedHydrated(context)
        return key in mutedSet
    }

    fun findEmployeeForPhone(context: Context, phone: String): OfflineDiskCache.CachedEmployeeMember? {
        val key = normalizeKey(phone)
        if (key.length < 7) return null
        val dir = OfflineDiskCache.loadEmployeeDirectory(context)
        val matched = dir.firstOrNull { member ->
            val mKey = normalizeKey(member.mobile)
            mKey.length >= 7 && mKey == key
        }
        if (matched != null) return matched
        ensureInternalHydrated(context)
        if (key in internalSet || key in defaultDeskPhones) {
            return OfflineDiskCache.CachedEmployeeMember(
                id = "EMP-DESK",
                name = "TLC Team Desk",
                mobile = key,
                role = "Internal Team"
            )
        }
        return null
    }

    fun isInternalEmployeePhone(context: Context, phone: String): Boolean =
        findEmployeeForPhone(context, phone) != null

    fun isIgnoredOrInternal(context: Context, phone: String): Boolean {
        val key = normalizeKey(phone)
        if (key.length < 7) return false
        if (isPersonalIgnored(context, key)) return true
        return isInternalEmployeePhone(context, key)
    }
}

/**
 * Batch 21: Handover Callback Accountability Store (`cache_team_handovers.json` in `context.filesDir`).
 * Tracks every "Handover to Team via WhatsApp" event as "Pending Callback" and automatically
 * resolves it to "Called Back" when the assigned employee logs an outgoing or connected call to
 * that targetMobile after `handedOverAt`.
 */
data class TeamHandoverRecord(
    val id: String = UUID.randomUUID().toString(),
    val callId: String = "",
    val assignedTo: String = "",
    val handedOverBy: String = "",
    val targetMobile: String = "",
    val callerName: String = "",
    val roleLabel: String = "",
    val propertyOrProject: String = "",
    val note: String = "",
    val handedOverAt: Long = System.currentTimeMillis(),
    val status: String = "Pending Callback", // "Pending Callback" | "Called Back"
    val calledBackAt: Long = 0L,
    val callbackCallId: String = ""
) {
    fun isPending(): Boolean = !status.equals("Called Back", ignoreCase = true)

    fun isOverdue2Hours(nowMs: Long = System.currentTimeMillis()): Boolean =
        isPending() && handedOverAt > 0L && (nowMs - handedOverAt) > (2L * 60L * 60L * 1000L)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("callId", callId)
        put("assignedTo", assignedTo)
        put("handedOverBy", handedOverBy)
        put("targetMobile", targetMobile)
        put("callerName", callerName)
        put("roleLabel", roleLabel)
        put("propertyOrProject", propertyOrProject)
        put("note", note)
        put("handedOverAt", handedOverAt)
        put("status", status)
        put("calledBackAt", calledBackAt)
        put("callbackCallId", callbackCallId)
    }

    companion object {
        fun fromJson(obj: JSONObject): TeamHandoverRecord = TeamHandoverRecord(
            id = obj.optString("id", UUID.randomUUID().toString()),
            callId = obj.optString("callId", ""),
            assignedTo = obj.optString("assignedTo", ""),
            handedOverBy = obj.optString("handedOverBy", ""),
            targetMobile = obj.optString("targetMobile", ""),
            callerName = obj.optString("callerName", ""),
            roleLabel = obj.optString("roleLabel", ""),
            propertyOrProject = obj.optString("propertyOrProject", ""),
            note = obj.optString("note", ""),
            handedOverAt = obj.optLong("handedOverAt", 0L),
            status = obj.optString("status", "Pending Callback").ifBlank { "Pending Callback" },
            calledBackAt = obj.optLong("calledBackAt", 0L),
            callbackCallId = obj.optString("callbackCallId", "")
        )
    }
}

object TeamHandoverStore {
    private val lock = Any()

    fun read(context: Context): List<TeamHandoverRecord> = synchronized(lock) {
        val raw = OfflineDiskCache.readTextSafely(context, OfflineDiskCache.FILE_TEAM_HANDOVERS)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { TeamHandoverRecord.fromJson(it) }
            }.sortedByDescending { it.handedOverAt }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<TeamHandoverRecord>) {
        val arr = JSONArray().apply {
            list.sortedByDescending { it.handedOverAt }.forEach { put(it.toJson()) }
        }
        OfflineDiskCache.writeTextAtomically(context, OfflineDiskCache.FILE_TEAM_HANDOVERS, arr.toString())
    }

    fun recordHandover(context: Context, record: TeamHandoverRecord): List<TeamHandoverRecord> = synchronized(lock) {
        val normTarget = CrmCallManager.last10Digits(record.targetMobile).ifBlank { record.targetMobile.trim() }
        val cleanRec = record.copy(
            targetMobile = normTarget,
            status = record.status.ifBlank { "Pending Callback" }
        )
        val current = read(context).toMutableList()
        val idx = current.indexOfFirst {
            it.id == cleanRec.id ||
                (cleanRec.callId.isNotBlank() && it.callId == cleanRec.callId && it.assignedTo.equals(cleanRec.assignedTo, true))
        }
        if (idx >= 0) {
            current[idx] = cleanRec
        } else {
            current.add(0, cleanRec)
        }
        save(context, current)
        current
    }

    private fun agentNamesMatch(callAgent: String, assignedTo: String): Boolean {
        val a = callAgent.trim().lowercase(Locale.ENGLISH)
        val b = assignedTo.trim().lowercase(Locale.ENGLISH)
        if (a.isBlank() || b.isBlank()) return true
        return a == b || a.contains(b) || b.contains(a)
    }

    /**
     * Merges any synced Call Log handover metadata into local handovers and automatically checks
     * whether the assigned employee has logged an outgoing or connected incoming (durationSeconds > 0)
     * call to `targetMobile` after `handedOverAt`.
     */
    fun reconcileWithCallLogs(
        context: Context,
        calls: List<CrmCallRecord> = CrmCallManager.readCalls(context)
    ): List<TeamHandoverRecord> = synchronized(lock) {
        val current = read(context).associateBy { it.id }.toMutableMap()
        var dirty = false

        // 1. Seed any synced handover rows from Call Logs that aren't in local store yet
        calls.forEach { c ->
            if (c.handoverStatus.isNotBlank() && c.handoverAssignedTo.isNotBlank()) {
                val tMobile = CrmCallManager.last10Digits(c.phoneNumber)
                if (tMobile.length >= 7) {
                    val existing = current.values.firstOrNull {
                        it.callId == c.id && it.assignedTo.equals(c.handoverAssignedTo, ignoreCase = true)
                    }
                    if (existing == null) {
                        val seeded = TeamHandoverRecord(
                            id = "ho_${c.id}",
                            callId = c.id,
                            assignedTo = c.handoverAssignedTo.trim(),
                            handedOverBy = c.assignedAgent.trim(),
                            targetMobile = tMobile,
                            callerName = c.callerName.ifBlank { tMobile },
                            roleLabel = c.linkedOpportunityType.orEmpty(),
                            propertyOrProject = c.linkedOpportunityTitle.orEmpty(),
                            note = c.notes,
                            handedOverAt = c.handedOverAt.takeIf { it > 0L } ?: c.timestamp,
                            status = if (c.handoverStatus.equals("Called Back", true)) "Called Back" else "Pending Callback",
                            calledBackAt = c.handoverCallbackAt
                        )
                        current[seeded.id] = seeded
                        dirty = true
                    } else if (c.handoverStatus.equals("Called Back", true) && existing.isPending()) {
                        current[existing.id] = existing.copy(
                            status = "Called Back",
                            calledBackAt = maxOf(existing.calledBackAt, c.handoverCallbackAt)
                        )
                        dirty = true
                    }
                }
            }
        }

        // 2. Check all pending handovers for matching outgoing or connected incoming callbacks
        val resolvedCallIds = mutableMapOf<String, Long>()
        current.keys.toList().forEach { key ->
            val ho = current[key] ?: return@forEach
            if (!ho.isPending()) return@forEach
            val target10 = CrmCallManager.last10Digits(ho.targetMobile)
            if (target10.length < 7) return@forEach

            val callback = calls
                .filter { c ->
                    c.id != ho.callId &&
                        CrmCallManager.last10Digits(c.phoneNumber) == target10 &&
                        c.timestamp > ho.handedOverAt &&
                        (c.callType == CallType.OUTGOING || (c.callType == CallType.INCOMING && c.durationSeconds > 0)) &&
                        agentNamesMatch(c.assignedAgent, ho.assignedTo)
                }
                .minByOrNull { it.timestamp }

            if (callback != null) {
                val updatedHo = ho.copy(
                    status = "Called Back",
                    calledBackAt = callback.timestamp,
                    callbackCallId = callback.id
                )
                current[key] = updatedHo
                if (ho.callId.isNotBlank()) {
                    resolvedCallIds[ho.callId] = callback.timestamp
                }
                if (ho.roleLabel.contains("Broker", ignoreCase = true) ||
                    ho.roleLabel.contains("CP", ignoreCase = true)
                ) {
                    PendingLoopTaskStore.enqueueBrokerStatusUpdate(
                        context = context,
                        handover = updatedHo,
                        agentName = ho.handedOverBy
                    )
                }
                dirty = true
            }
        }

        val finalList = current.values.sortedByDescending { it.handedOverAt }
        if (dirty) {
            save(context, finalList)
            if (resolvedCallIds.isNotEmpty()) {
                val updatedCalls = calls.map { c ->
                    val cbTime = resolvedCallIds[c.id]
                    if (cbTime != null && !c.handoverStatus.equals("Called Back", true)) {
                        c.copy(handoverStatus = "Called Back", handoverCallbackAt = cbTime)
                    } else c
                }
                CrmCallManager.saveCalls(context, updatedCalls)
            }
        }
        return finalList
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

            if (hasCallSignal) {
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
            } else {
                // Skip group summary / ongoing sync notifications
                val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 ||
                    (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
                if (isGroupSummary) return

                val messageBody = rawBigText.ifBlank { rawText }.ifBlank { rawSubText }
                WhatsAppFollowUpStore.captureIncomingMessageNotification(
                    context = ctx,
                    packageName = pkg,
                    rawTitle = rawTitle,
                    rawText = messageBody,
                    timestampMs = statusBarNotification.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }
        } catch (_: Exception) {
            // Never crash on malformed system notification extras
        }
    }
}



