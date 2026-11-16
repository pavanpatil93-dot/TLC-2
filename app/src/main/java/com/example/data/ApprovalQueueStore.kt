package com.example.data

import android.content.Context
import com.example.model.UserRole
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

enum class ApprovalState { PENDING, APPROVED, REJECTED }

data class ApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val action: String,
    val targetId: String,
    val summary: String,
    val requestedBy: String,
    val requestedAt: String = Instant.now().toString(),
    val state: ApprovalState = ApprovalState.PENDING,
    val decidedBy: String = "",
    val decidedAt: String = ""
)

/** Local, auditable approval queue. It never mutates inventory by itself. */
object ApprovalQueueStore {
    private const val PREFS = "tlc_approval_queue_v1"
    private const val KEY = "requests"

    fun read(context: Context): List<ApprovalRequest> = runCatching {
        val raw = auditedCrmPreferences(context, PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ApprovalRequest(o.optString("id"), o.optString("action"), o.optString("targetId"),
                o.optString("summary"), o.optString("requestedBy"), o.optString("requestedAt"),
                runCatching { ApprovalState.valueOf(o.optString("state", "PENDING")) }.getOrDefault(ApprovalState.PENDING),
                o.optString("decidedBy"), o.optString("decidedAt"))
        }
    }.getOrDefault(emptyList())

    fun request(context: Context, action: String, targetId: String, summary: String): ApprovalRequest {
        val identity = UserSettingsManager(context).auditIdentity()
        val existing = read(context).firstOrNull {
            it.action == action &&
                it.targetId == targetId &&
                it.requestedBy == identity &&
                it.state == ApprovalState.PENDING
        }
        if (existing != null) return existing
        val item = ApprovalRequest(action = action, targetId = targetId, summary = summary, requestedBy = identity)
        write(context, read(context) + item)
        return item
    }

    fun hasApprovedGrant(context: Context, action: String, targetId: String, identity: String = UserSettingsManager(context).auditIdentity()): Boolean {
        return read(context).any {
            it.action == action &&
                it.targetId == targetId &&
                it.requestedBy == identity &&
                it.state == ApprovalState.APPROVED
        }
    }

    fun decide(context: Context, requestId: String, approve: Boolean): Boolean {
        val settings = UserSettingsManager(context)
        if (!settings.userProfile.value.role.canPerformSensitiveAction("CHANGE_PERMISSIONS")) return false
        val now = Instant.now().toString()
        val updated = read(context).map {
            if (it.id == requestId && it.state == ApprovalState.PENDING)
                it.copy(state = if (approve) ApprovalState.APPROVED else ApprovalState.REJECTED,
                    decidedBy = settings.auditIdentity(), decidedAt = now)
            else it
        }
        if (updated.none { it.id == requestId }) return false
        write(context, updated)
        return true
    }

    fun revoke(context: Context, requestId: String): Boolean {
        val settings = UserSettingsManager(context)
        if (!settings.userProfile.value.role.canPerformSensitiveAction("CHANGE_PERMISSIONS")) return false
        val now = Instant.now().toString()
        val updated = read(context).map {
            if (it.id == requestId && it.state == ApprovalState.APPROVED)
                it.copy(state = ApprovalState.REJECTED, decidedBy = settings.auditIdentity(), decidedAt = now)
            else it
        }
        if (updated.none { it.id == requestId }) return false
        write(context, updated)
        return true
    }

    private fun write(context: Context, requests: List<ApprovalRequest>) {
        val array = JSONArray().apply { requests.takeLast(500).forEach { r -> put(JSONObject().apply {
            put("id", r.id); put("action", r.action); put("targetId", r.targetId); put("summary", r.summary)
            put("requestedBy", r.requestedBy); put("requestedAt", r.requestedAt); put("state", r.state.name)
            put("decidedBy", r.decidedBy); put("decidedAt", r.decidedAt)
        }) } }
        check(auditedCrmPreferences(context, PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit()) {
            "Approval queue save failed"
        }
    }
}
