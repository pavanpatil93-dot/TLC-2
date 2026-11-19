package com.example.crm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.OfflineDiskCache
import com.example.data.SheetsRepository
import com.example.ui.screens.TeamHandoverSummary
import com.example.ui.screens.buildTeamHandoverWhatsAppMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Batch19HandoverTest {

    @Test
    fun buildTeamHandoverWhatsAppMessage_exactFormattedTemplate_allFieldsPresent() {
        val summary = TeamHandoverSummary(
            senderEmployeeName = "Pawan Patil",
            roleLabel = "Client Lead",
            subRoleLabel = "Investor",
            callerName = "Rajesh Singhania",
            callerMobile10 = "+91 98220 11223",
            propertyOrProject = "OL-204 • Gera Imperium Alpha 402",
            followUpDayTime = "Tomorrow, 2026-06-02 12:00 PM",
            callNote = "Needs 1,500 sq.ft furnished office near Kharadi"
        )

        val expected = listOf(
            "*Handover from Pawan Patil*",
            "*Type:* Client Lead (Investor)",
            "*Name:* Rajesh Singhania",
            "*Mobile:* 9822011223",
            "*Property / Project:* OL-204 • Gera Imperium Alpha 402",
            "*Follow-up:* Tomorrow, 2026-06-02 12:00 PM",
            "*Note:* Needs 1,500 sq.ft furnished office near Kharadi"
        ).joinToString("\n")

        assertEquals(expected, buildTeamHandoverWhatsAppMessage(summary))
    }

    @Test
    fun buildTeamHandoverWhatsAppMessage_defaultsOptionalFieldsCleanly() {
        val summary = TeamHandoverSummary(
            senderEmployeeName = "Priya Thorat",
            roleLabel = "Ops / Vendor",
            subRoleLabel = "Electrician",
            callerName = "Suresh Verma",
            callerMobile10 = "9765001122",
            propertyOrProject = "",
            followUpDayTime = "",
            callNote = ""
        )

        val expected = listOf(
            "*Handover from Priya Thorat*",
            "*Type:* Ops / Vendor (Electrician)",
            "*Name:* Suresh Verma",
            "*Mobile:* 9765001122",
            "*Property / Project:* N/A",
            "*Follow-up:* None",
            "*Note:* N/A"
        ).joinToString("\n")

        assertEquals(expected, buildTeamHandoverWhatsAppMessage(summary))
    }

    @Test
    fun offlineDiskCache_employeeDirectoryAndNamesBackwardCompatibility() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val employees = listOf(
            OfflineDiskCache.CachedEmployeeMember(
                id = "EMP-01",
                name = "Pawan Patil",
                mobile = "+91 97668 80099",
                role = "Admin",
                division = "All Businesses",
                status = "Active"
            ),
            OfflineDiskCache.CachedEmployeeMember(
                id = "EMP-02",
                name = "Sagar I. Chaudhari",
                mobile = "9822005544",
                role = "Commercial Manager",
                division = "Commercial",
                status = "Active"
            ),
            OfflineDiskCache.CachedEmployeeMember(
                id = "EMP-03",
                name = "Former Staff",
                mobile = "9822009999",
                role = "Sales Executive",
                division = "Residential",
                status = "Inactive"
            )
        )

        OfflineDiskCache.saveEmployeeDirectory(context, employees)

        val activeDirectory = OfflineDiskCache.loadEmployeeDirectory(context)
        assertEquals(2, activeDirectory.size)
        assertEquals("PP", activeDirectory[0].initials())
        assertEquals("9766880099", activeDirectory[0].tenDigitMobile())
        assertEquals("Commercial Manager", activeDirectory[1].roleAndDivisionLabel())
        assertEquals(
            "Sales Executive • Commercial",
            OfflineDiskCache.CachedEmployeeMember(name = "Priya", role = "Sales Executive", division = "Commercial").roleAndDivisionLabel()
        )
        assertFalse(activeDirectory.any { it.name == "Former Staff" })

        val names = OfflineDiskCache.loadEmployeeNames(context)
        assertTrue(names.contains("Pawan Patil"))
        assertTrue(names.contains("Sagar I. Chaudhari"))

        val repoDir = SheetsRepository.getSyncedActiveEmployeeDirectory(context)
        assertTrue(repoDir.any { it.name == "Sagar I. Chaudhari" && it.tenDigitMobile() == "9822005544" })

        val withRoom = runBlocking { SheetsRepository.loadActiveEmployeeDirectoryWithRoom(context) }
        assertTrue(withRoom.any { it.name == "Pawan Patil" })
    }

    @Test
    fun tenantAssignedToRoundtripAndCallHandoverAutoLog() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val tenant = OfflineDiskCache.TenantCacheRecord(
            id = "TNT-B19",
            tenantName = "Apex FinServe",
            propertyCode = "TLC-302",
            projectName = "World Trade Center",
            location = "Kharadi",
            contactNumber = "9890011223",
            assignedTo = "Ayush S. Khedkar"
        )
        OfflineDiskCache.saveTenants(context, listOf(tenant))
        val loaded = OfflineDiskCache.loadTenants(context).first { it.id == "TNT-B19" }
        assertEquals("Ayush S. Khedkar", loaded.assignedTo)

        val call = CrmCallRecord(
            id = "call_handover_19",
            callerName = "Apex FinServe",
            phoneNumber = "9890011223",
            callType = CallType.INCOMING,
            notes = "AC ducting repair requested • Handed over to Ayush S. Khedkar on WhatsApp",
            assignedAgent = "Ayush S. Khedkar",
            isLinked = true,
            linkedOpportunityType = "Tenant",
            linkedOpportunityId = "TNT-B19",
            waLogged = true
        )
        CrmCallManager.addOrUpdateCall(context, call)
        val storedCall = CrmCallManager.readCalls(context).first { it.id == "call_handover_19" }
        assertEquals("Ayush S. Khedkar", storedCall.assignedAgent)
        assertTrue(storedCall.notes.contains("Handed over to Ayush S. Khedkar on WhatsApp"))
        assertTrue(storedCall.waLogged)
    }
}
