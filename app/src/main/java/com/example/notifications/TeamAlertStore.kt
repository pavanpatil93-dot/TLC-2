package com.example.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PropertySnapshotEntry(
    val id: String,
    val isLive: Boolean,
    val statusName: String,
    val updatedBy: String,
    val verifiedBy: String
)

data class LeadSnapshotEntry(
    val id: String,
    val siteVisitStatus: String,
    val handoverSignature: String,
    val assignedTo: String,
    val updatedBy: String
)

data class TenantSnapshotEntry(
    val id: String,
    val issueIds: Set<String>
)

data class CallLogSnapshotEntry(
    val id: String,
    val handoverAssignedTo: String,
    val handoverStatus: String
)

data class TeamSyncDiffSnapshot(
    val initializedAtMillis: Long = System.currentTimeMillis(),
    val properties: Map<String, PropertySnapshotEntry> = emptyMap(),
    val leads: Map<String, LeadSnapshotEntry> = emptyMap(),
    val tenants: Map<String, TenantSnapshotEntry> = emptyMap(),
    val callLogs: Map<String, CallLogSnapshotEntry> = emptyMap(),
    val alertedFollowUpKeys: Set<String> = emptySet()
)

object TeamAlertStore {
    private const val FILE_ALERTS = "team_alerts_feed_v1.json"
    private const val FILE_SNAPSHOT = "team_alert_diff_snapshot_v1.json"
    private const val RETENTION_MS = 48L * 60L * 60L * 1000L // 48 hours
    private const val MAX_ALERTS = 200

    private val lock = Any()
    private val _alerts = MutableStateFlow<List<TeamAlertItem>>(emptyList())
    val alerts: StateFlow<List<TeamAlertItem>> = _alerts.asStateFlow()

    @Volatile
    private var hydrated = false

    fun hydrate(context: Context): List<TeamAlertItem> {
        synchronized(lock) {
            val loaded = readAlertsFromDiskLocked(context)
            _alerts.value = loaded
            hydrated = true
            return loaded
        }
    }

    fun appendAlerts(context: Context, newAlerts: List<TeamAlertItem>) {
        if (newAlerts.isEmpty()) return
        synchronized(lock) {
            val existing = if (hydrated) _alerts.value else readAlertsFromDiskLocked(context)
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            val seenIds = HashSet<String>()
            val merged = buildList {
                for (item in newAlerts) {
                    if (item.id.isNotBlank() && seenIds.add(item.id)) add(item)
                }
                for (item in existing) {
                    if (item.timestampMillis >= cutoff && item.id.isNotBlank() && seenIds.add(item.id)) {
                        add(item)
                    }
                }
            }.sortedByDescending { it.timestampMillis }.take(MAX_ALERTS)

            _alerts.value = merged
            hydrated = true
            writeAlertsToDiskLocked(context, merged)
        }
    }

    fun markRead(context: Context, alertId: String) {
        if (alertId.isBlank()) return
        synchronized(lock) {
            val existing = if (hydrated) _alerts.value else readAlertsFromDiskLocked(context)
            var changed = false
            val updated = existing.map { item ->
                if (item.id == alertId && !item.isRead) {
                    changed = true
                    item.copy(isRead = true)
                } else {
                    item
                }
            }
            if (changed) {
                _alerts.value = updated
                writeAlertsToDiskLocked(context, updated)
            }
        }
    }

    fun markAllRead(context: Context) {
        synchronized(lock) {
            val existing = if (hydrated) _alerts.value else readAlertsFromDiskLocked(context)
            if (existing.none { !it.isRead }) return
            val updated = existing.map { it.copy(isRead = true) }
            _alerts.value = updated
            writeAlertsToDiskLocked(context, updated)
        }
    }

    fun clearExpired(context: Context) {
        synchronized(lock) {
            val existing = if (hydrated) _alerts.value else readAlertsFromDiskLocked(context)
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            val pruned = existing.filter { it.timestampMillis >= cutoff }
            if (pruned.size != existing.size) {
                _alerts.value = pruned
                writeAlertsToDiskLocked(context, pruned)
            }
        }
    }

    fun loadSnapshot(context: Context): TeamSyncDiffSnapshot? {
        synchronized(lock) {
            val file = File(context.applicationContext.filesDir, FILE_SNAPSHOT)
            if (!file.exists()) return null
            val raw = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
            return runCatching {
                val root = JSONObject(raw)
                val props = mutableMapOf<String, PropertySnapshotEntry>()
                root.optJSONObject("properties")?.let { obj ->
                    obj.keys().forEach { key ->
                        val p = obj.optJSONObject(key) ?: return@forEach
                        props[key] = PropertySnapshotEntry(
                            id = key,
                            isLive = p.optBoolean("isLive", false),
                            statusName = p.optString("statusName", ""),
                            updatedBy = p.optString("updatedBy", ""),
                            verifiedBy = p.optString("verifiedBy", "")
                        )
                    }
                }
                val leads = mutableMapOf<String, LeadSnapshotEntry>()
                root.optJSONObject("leads")?.let { obj ->
                    obj.keys().forEach { key ->
                        val l = obj.optJSONObject(key) ?: return@forEach
                        leads[key] = LeadSnapshotEntry(
                            id = key,
                            siteVisitStatus = l.optString("siteVisitStatus", ""),
                            handoverSignature = l.optString("handoverSignature", ""),
                            assignedTo = l.optString("assignedTo", ""),
                            updatedBy = l.optString("updatedBy", "")
                        )
                    }
                }
                val tenants = mutableMapOf<String, TenantSnapshotEntry>()
                root.optJSONObject("tenants")?.let { obj ->
                    obj.keys().forEach { key ->
                        val arr = obj.optJSONArray(key)
                        val issueIds = mutableSetOf<String>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val v = arr.optString(i, "")
                                if (v.isNotBlank()) issueIds.add(v)
                            }
                        }
                        tenants[key] = TenantSnapshotEntry(id = key, issueIds = issueIds)
                    }
                }
                val callLogs = mutableMapOf<String, CallLogSnapshotEntry>()
                root.optJSONObject("callLogs")?.let { obj ->
                    obj.keys().forEach { key ->
                        val c = obj.optJSONObject(key) ?: return@forEach
                        callLogs[key] = CallLogSnapshotEntry(
                            id = key,
                            handoverAssignedTo = c.optString("handoverAssignedTo", ""),
                            handoverStatus = c.optString("handoverStatus", "")
                        )
                    }
                }
                val followUpKeys = mutableSetOf<String>()
                root.optJSONArray("alertedFollowUpKeys")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "")
                        if (v.isNotBlank()) followUpKeys.add(v)
                    }
                }
                TeamSyncDiffSnapshot(
                    initializedAtMillis = root.optLong("initializedAtMillis", System.currentTimeMillis()),
                    properties = props,
                    leads = leads,
                    tenants = tenants,
                    callLogs = callLogs,
                    alertedFollowUpKeys = followUpKeys
                )
            }.getOrNull()
        }
    }

    fun saveSnapshot(context: Context, snapshot: TeamSyncDiffSnapshot) {
        synchronized(lock) {
            val root = JSONObject().apply {
                put("initializedAtMillis", snapshot.initializedAtMillis)
                put("properties", JSONObject().apply {
                    snapshot.properties.forEach { (k, v) ->
                        put(k, JSONObject().apply {
                            put("isLive", v.isLive)
                            put("statusName", v.statusName)
                            put("updatedBy", v.updatedBy)
                            put("verifiedBy", v.verifiedBy)
                        })
                    }
                })
                put("leads", JSONObject().apply {
                    snapshot.leads.forEach { (k, v) ->
                        put(k, JSONObject().apply {
                            put("siteVisitStatus", v.siteVisitStatus)
                            put("handoverSignature", v.handoverSignature)
                            put("assignedTo", v.assignedTo)
                            put("updatedBy", v.updatedBy)
                        })
                    }
                })
                put("tenants", JSONObject().apply {
                    snapshot.tenants.forEach { (k, v) ->
                        put(k, JSONArray().apply { v.issueIds.forEach { put(it) } })
                    }
                })
                put("callLogs", JSONObject().apply {
                    snapshot.callLogs.forEach { (k, v) ->
                        put(k, JSONObject().apply {
                            put("handoverAssignedTo", v.handoverAssignedTo)
                            put("handoverStatus", v.handoverStatus)
                        })
                    }
                })
                put("alertedFollowUpKeys", JSONArray().apply {
                    // Retain last 400 keys so file stays compact
                    snapshot.alertedFollowUpKeys.toList().takeLast(400).forEach { put(it) }
                })
            }
            writeTextAtomically(context, FILE_SNAPSHOT, root.toString())
        }
    }

    private fun readAlertsFromDiskLocked(context: Context): List<TeamAlertItem> {
        val file = File(context.applicationContext.filesDir, FILE_ALERTS)
        if (!file.exists()) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                runCatching { TeamAlertItem.fromJson(arr.getJSONObject(idx)) }.getOrNull()
                    ?.takeIf { it.id.isNotBlank() && it.timestampMillis >= cutoff }
            }.sortedByDescending { it.timestampMillis }.take(MAX_ALERTS)
        }.getOrDefault(emptyList())
    }

    private fun writeAlertsToDiskLocked(context: Context, items: List<TeamAlertItem>) {
        val arr = JSONArray().apply { items.forEach { put(it.toJson()) } }
        writeTextAtomically(context, FILE_ALERTS, arr.toString())
    }

    private fun writeTextAtomically(context: Context, fileName: String, content: String) {
        val dir = context.applicationContext.filesDir
        val target = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        runCatching {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }
    }
}
