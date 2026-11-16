package com.example.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local-only activity journal for the employee-testing phase.
 * Not a tamper-proof or cross-device audit service.
 */
object EmployeeActivityJournal {
    @Synchronized
    fun record(context: Context?, action: String, entityType: String, entityId: String, details: String = "") {
        if (context == null) return
        val prefs = context.getSharedPreferences("real_estate_user_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("employee_registered", false)) return
        val entry = JSONObject()
            .put("timestampUtcMs", System.currentTimeMillis())
            .put("employeeName", prefs.getString("user_username", ""))
            .put("employeePhone", prefs.getString("user_mobile", ""))
            .put("action", action)
            .put("entityType", entityType)
            .put("entityId", entityId)
            .put("details", details)
        val file = File(context.filesDir, "employee_activity.json")
        try {
            val existing = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            existing.put(entry)
            val tmp = File(context.filesDir, "employee_activity.json.tmp")
            tmp.writeText(existing.toString())
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw java.io.IOException("Could not persist employee activity")
            }
        } catch (e: Exception) {
            android.util.Log.e("EmployeeActivity", "Failed to persist activity", e)
        }
    }
}

