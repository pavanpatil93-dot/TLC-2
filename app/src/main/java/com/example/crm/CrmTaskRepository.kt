package com.example.crm

import android.content.Context
import com.example.data.auditedCrmPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

enum class CrmTaskStatus { OPEN, COMPLETED, CANCELLED }
enum class CrmTaskOrigin { MANUAL, CALL, LEAD, VISIT }
data class CrmTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val origin: CrmTaskOrigin,
    val originId: String,
    val assignedTo: String = "",
    val dueDate: String = "",
    val dueTime: String = "",
    val notes: String = "",
    val status: CrmTaskStatus = CrmTaskStatus.OPEN,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) {
    fun isOverdue(today: String = LocalDate.now().toString()): Boolean =
        status == CrmTaskStatus.OPEN && dueDate.isNotBlank() && dueDate < today
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("origin", origin.name); put("originId", originId)
        put("assignedTo", assignedTo); put("dueDate", dueDate); put("dueTime", dueTime)
        put("notes", notes); put("status", status.name); put("createdAt", createdAt); put("updatedAt", updatedAt)
    }
    companion object {
        fun fromJson(json: JSONObject): CrmTask = CrmTask(
            id = json.getString("id"), title = json.getString("title"),
            origin = CrmTaskOrigin.valueOf(json.getString("origin")),
            originId = json.optString("originId"), assignedTo = json.optString("assignedTo"),
            dueDate = json.optString("dueDate"), dueTime = json.optString("dueTime"),
            notes = json.optString("notes"),
            status = CrmTaskStatus.valueOf(json.optString("status", "OPEN")),
            createdAt = json.optLong("createdAt"), updatedAt = json.optLong("updatedAt")
        )
    }
}

/** Local-only durable task store. Not a shared-team backend or notification scheduler. */
object CrmTaskRepository {
    private const val PREFS = "crm_tasks_v1"
    private const val KEY = "tasks"
    private fun prefs(context: Context) = auditedCrmPreferences(context, PREFS, Context.MODE_PRIVATE)

    @Synchronized fun read(context: Context): List<CrmTask> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        // Never overwrite malformed data; surface corruption instead of silently deleting tasks.
        val array = JSONArray(raw)
        return (0 until array.length()).map { CrmTask.fromJson(array.getJSONObject(it)) }
    }
    @Synchronized fun upsert(context: Context, task: CrmTask): Boolean {
        require(task.title.isNotBlank()) { "Task title required" }
        require(task.dueDate.isBlank() || runCatching { LocalDate.parse(task.dueDate) }.isSuccess) { "Invalid due date" }
        require(task.dueTime.isBlank() || Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(task.dueTime)) { "Invalid due time" }
        val items = read(context).toMutableList()
        val index = items.indexOfFirst { it.id == task.id }
        val updated = task.copy(updatedAt = System.currentTimeMillis())
        if (index >= 0) items[index] = updated else items.add(updated)
        return persist(context, items)
    }
    @Synchronized fun setStatus(context: Context, id: String, status: CrmTaskStatus): Boolean {
        val items = read(context).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items[index] = items[index].copy(status = status, updatedAt = System.currentTimeMillis())
        return persist(context, items)
    }
    /** Stable origin key prevents duplicate tasks on call-log re-import or repeated saves. */
    @Synchronized fun ensureFromOrigin(context: Context, origin: CrmTaskOrigin, originId: String, title: String, dueDate: String = "", dueTime: String = ""): CrmTask {
        require(origin != CrmTaskOrigin.MANUAL && originId.isNotBlank())
        read(context).firstOrNull { it.origin == origin && it.originId == originId }?.let { return it }
        val task = CrmTask(title = title, origin = origin, originId = originId, dueDate = dueDate, dueTime = dueTime)
        check(upsert(context, task)) { "Task save failed" }
        return task
    }
    private fun persist(context: Context, items: List<CrmTask>): Boolean {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        return prefs(context).edit().putString(KEY, array.toString()).commit()
    }
}
