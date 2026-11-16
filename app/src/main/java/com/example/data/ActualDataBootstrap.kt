package com.example.data

import android.content.Context
import org.json.JSONArray

object ActualDataBootstrap {
    fun seedCrm(context: Context): Int {
        val prefs = auditedCrmPreferences(context, "crm_development_v1", Context.MODE_PRIVATE)
        val existing = prefs.getString("leads", "[]") ?: "[]"
        if (runCatching { JSONArray(existing).length() }.getOrDefault(0) > 0) return 0
        return runCatching {
            val raw = context.assets.open("actual_crm.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val count = JSONArray(raw).length()
            if (count > 0 && prefs.edit().putString("leads", raw).putBoolean("actual_crm_seeded_v1", true).commit()) count else 0
        }.getOrDefault(0)
    }
}

