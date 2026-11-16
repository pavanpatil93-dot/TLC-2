package com.example.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.auditedCrmPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Build02CrmAuditTest {
    @Test fun leadAndProfileMutationsStoreIdentityAndTimestampWithoutLosingUnknownFields() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        c.getSharedPreferences("real_estate_user_settings",0).edit().putString("user_username","Himanshu").putString("user_role","Sales Executive").commit()
        val prefs=auditedCrmPreferences(c,"crm_development_v1",0)
        val raw=JSONArray().put(JSONObject().put("id","a").put("legacy","retain"))
        assertTrue(prefs.edit().putString("leads",raw.toString()).commit())
        val saved=JSONArray(prefs.getString("leads","[]")).getJSONObject(0)
        assertEquals("Himanshu | SALES_EXECUTIVE",saved.getString("updatedBy"))
        assertTrue(saved.getString("updatedAt").isNotBlank())
        assertEquals("retain",saved.getString("legacy"))
        assertTrue(runCatching { prefs.edit().putString("leads","[]").commit() }.isFailure)
        assertEquals(1,JSONArray(prefs.getString("leads","[]")).length())
        val stage=auditedCrmPreferences(c,"crm_profile_v1",0)
        assertTrue(stage.edit().putString("a_stage","Negotiation").commit())
        assertEquals("Negotiation",stage.getString("a_stage",""))
        assertEquals("Himanshu | SALES_EXECUTIVE",stage.getString("audit:a_stage:updatedBy",""))
    }
    @Test fun oldLeadsWithoutOptionalColumnsCanStillBeExtendedWithoutBulkEditing() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        c.getSharedPreferences("real_estate_user_settings",0).edit().putString("user_username","Ayush").putString("user_role","Sales Executive").commit()
        val raw=JSONArray().put(JSONObject().put("id","a").put("name","A").put("legacy","keep"))
            .put(JSONObject().put("id","b").put("name","B"))
        c.getSharedPreferences("crm_development_v1",0).edit().putString("leads",raw.toString()).commit()
        val existing=readCrmLeads(c)
        saveCrmLeads(c, existing + existing.first().copy(id="new",name="New"))
        assertEquals(3,readCrmLeads(c).size)
        val saved=JSONArray(c.getSharedPreferences("crm_development_v1",0).getString("leads","[]"))
        assertEquals("keep",saved.getJSONObject(0).getString("legacy"))
        val prefs=auditedCrmPreferences(c,"crm_development_v1",0)
        saved.getJSONObject(0).put("name","Changed A")
        saved.getJSONObject(1).put("name","Changed B")
        assertTrue(runCatching { prefs.edit().putString("leads",saved.toString()).commit() }.isFailure)
        assertEquals("A",readCrmLeads(c).first().name)
    }

}
