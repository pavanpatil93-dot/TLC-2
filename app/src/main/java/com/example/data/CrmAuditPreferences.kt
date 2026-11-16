package com.example.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One preferences transaction writes CRM payload and audit metadata together.
 * Existing keys and unknown JSON fields survive; reads never rewrite data.
 */
fun auditedCrmPreferences(context: Context, name: String, mode: Int): SharedPreferences {
    val delegate = context.getSharedPreferences(name, mode)
    return object : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            val by = UserSettingsManager(context).auditIdentity()
            val at = Instant.now().toString()
            fun permit(action: String) {
                check(UserSettingsManager(context).userProfile.value.role.canPerformSensitiveAction(action)) { "Manager access required: $action" }
            }
            fun business(o: JSONObject): Map<String, String> = o.keys().asSequence()
                .filter { it !in setOf("updatedBy", "updatedAt") && !o.isNull(it) && o.optString(it).isNotEmpty() }
                .associateWith { o.get(it).toString() }
            fun guardArray(key: String?, incoming: JSONArray) {
                val raw = delegate.getString(key, "[]") ?: "[]"
                val old = JSONArray(raw)
                val oldRows = (0 until old.length()).mapNotNull { old.optJSONObject(it) }.associateBy { it.optString("id") }
                val nextRows = (0 until incoming.length()).mapNotNull { incoming.optJSONObject(it) }.associateBy { it.optString("id") }
                if (oldRows.keys.any { it !in nextRows }) permit("DELETE_RECORD")
                if (oldRows.keys.count { id -> nextRows[id]?.let { business(it) != business(oldRows.getValue(id)) } == true } > 1) permit("BULK_EDIT")
            }
            fun stamp(o: JSONObject): JSONObject = o.put("updatedBy", by).put("updatedAt", at)
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    val audited = if (value == null) null else when {
                        value.trim().startsWith("[") -> JSONArray(value).also { a ->
                            guardArray(key, a)
                            (0 until a.length()).forEach { i -> a.optJSONObject(i)?.let { stamp(it) } }
                        }.toString()
                        value.trim().startsWith("{") -> stamp(JSONObject(value)).toString()
                        else -> value
                    }
                    editor.putString(key, audited)
                    editor.putString("audit:${key}:updatedBy", by).putString("audit:${key}:updatedAt", at)
                    return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    permit("DELETE_RECORD")
                    editor.remove(key)
                    editor.putString("audit:${key}:updatedBy", by).putString("audit:${key}:updatedAt", at)
                    return this
                }
                override fun clear(): SharedPreferences.Editor {
                    permit("DELETE_RECORD"); permit("BULK_EDIT")
                    editor.clear().putString("updatedBy", by).putString("updatedAt", at)
                    return this
                }
                override fun commit(): Boolean = editor.commit()
                override fun apply() { check(editor.commit()) { "CRM save failed; please retry" } }
            }
        }
    }
}
