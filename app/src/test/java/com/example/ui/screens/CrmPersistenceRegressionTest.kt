package com.example.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrmPersistenceRegressionTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun malformedJsonDoesNotCrashOrOverwriteSavedData() {
        val prefs = context.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE)
        listOf("[broken", "{\"wrong\":\"shape\"}", "null", "[42]").forEach { raw ->
            assertTrue(prefs.edit().putString("leads", raw).commit())
            assertTrue(readCrmLeads(context).isEmpty())
            assertEquals(raw, prefs.getString("leads", null))
        }
    }

    @Test fun emptyCrmDoesNotCreateDemoClients() {
        assertTrue(readCrmLeads(context).isEmpty())
    }

    @Test fun carpetRangeCanBeSavedEditedAndClearedWithoutLosingLegacyFields() {
        val prefs = context.getSharedPreferences("crm_development_v1", Context.MODE_PRIVATE)
        prefs.edit().putString("leads", """[{"id":"range","name":"Client","phone":"TEST","legacySource":"keep"}]""").commit()
        val original = readCrmLeads(context).single()
        assertEquals("", original.carpetAreaRange)
        saveCrmLeads(context, listOf(original.copy(carpetAreaRange = "500–800")))
        val saved = readCrmLeads(context).single()
        assertEquals("500–800", saved.carpetAreaRange)
        saveCrmLeads(context, listOf(saved.copy(carpetAreaRange = "")))
        assertEquals("", readCrmLeads(context).single().carpetAreaRange)
        assertEquals("keep", org.json.JSONArray(prefs.getString("leads", "[]")).getJSONObject(0).getString("legacySource"))
    }

    @Test fun validLeadsPreserveAllFieldsAcrossSaveAndReload() {
        val lead = CrmLead(
            "existing-id", "Test client", "TEST-CONTACT", "Commercial",
            "Furnished office", "Kharadi", "100000", "Details Shared",
            "2026-11-12", "Call tomorrow", "Sagar"
        )
        saveCrmLeads(context, listOf(lead))
        assertEquals(listOf(lead), readCrmLeads(context).map { it.copy(updatedBy = "", updatedAt = "") })
    }
}

