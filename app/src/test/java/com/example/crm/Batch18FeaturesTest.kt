package com.example.crm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.OfflineDiskCache
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Batch18FeaturesTest {

    @Test
    fun googleContactsPrefixAndNotesFormatting_all8Roles() {
        assertEquals(
            "CL - Rohan Deshmukh",
            GoogleContactsSyncHelper.formatTlcContactDisplayName(
                GoogleContactSaveRequest(TlcContactRolePrefix.CLIENT_LEAD, "Rohan Deshmukh", "9822011223")
            )
        )
        assertEquals(
            "INV - Vikram Mehta",
            GoogleContactsSyncHelper.formatTlcContactDisplayName(
                GoogleContactSaveRequest(TlcContactRolePrefix.INVESTOR, "Vikram Mehta", "9822011224")
            )
        )
        assertEquals(
            "O - Amit Kulkarni - Gera Imperium 402",
            GoogleContactsSyncHelper.formatTlcContactDisplayName(
                GoogleContactSaveRequest(TlcContactRolePrefix.OWNER, "Amit Kulkarni", "9822011225", projectOrUnit = "Gera Imperium 402")
            )
        )
        assertEquals(
            "CP - Neha Joshi - Kharadi",
            GoogleContactsSyncHelper.formatTlcContactDisplayName(
                GoogleContactSaveRequest(TlcContactRolePrefix.BROKER_CP, "Neha Joshi", "9822011226", areaOrFirm = "Kharadi")
            )
        )
        assertEquals(
            "BLD - Sameer Shah - Panchshil Futura",
            GoogleContactsSyncHelper.formatTlcContactDisplayName(
                GoogleContactSaveRequest(TlcContactRolePrefix.DEVELOPER_SALES, "Sameer Shah", "9822011227", projectOrUnit = "Panchshil Futura")
            )
        )
        assertEquals(
            "SM - Priya Nair - Kohinoor World Towers",
            GoogleContactsSyncHelper.formatTlcContactDisplayName(
                GoogleContactSaveRequest(TlcContactRolePrefix.SOURCING_MANAGER, "Priya Nair", "9822011228", projectOrUnit = "Kohinoor World Towers")
            )
        )
        assertEquals(
            "TNT - FinTech Labs - Amar Business Zone 901",
            GoogleContactsSyncHelper.formatTlcContactDisplayName(
                GoogleContactSaveRequest(TlcContactRolePrefix.TENANT, "FinTech Labs", "9822011229", projectOrUnit = "Amar Business Zone 901")
            )
        )
        assertEquals(
            "OPS - Electrician - Suresh Verma",
            GoogleContactsSyncHelper.formatTlcContactDisplayName(
                GoogleContactSaveRequest(TlcContactRolePrefix.OPS_VENDOR, "Suresh Verma", "9822011230", subRole = "Electrician")
            )
        )

        // Ensure stripping duplicate existing prefixes
        assertEquals(
            "CL - Rohan",
            GoogleContactsSyncHelper.formatTlcContactDisplayName(
                GoogleContactSaveRequest(TlcContactRolePrefix.CLIENT_LEAD, "CL - Rohan", "9822011223")
            )
        )

        val notes = GoogleContactsSyncHelper.buildTlcContactNotes(
            GoogleContactSaveRequest(
                rolePrefix = TlcContactRolePrefix.OWNER,
                rawName = "Amit",
                phone = "9822011225",
                projectOrUnit = "Gera 402",
                referenceId = "TLC-1042"
            )
        )
        assertTrue(notes.contains("TLC App"))
        assertTrue(notes.contains("Ref: TLC-1042"))
    }

    @Test
    fun roleBasedPhoneMasking_masksExecutivesAndRevealsAdminsManagers() {
        val admin = UserProfile(username = "Pawan", role = UserRole.ADMIN)
        val manager = UserProfile(username = "Sneha", role = UserRole.COMMERCIAL_MANAGER)
        val exec = UserProfile(username = "Kiran", role = UserRole.SALES_EXECUTIVE)

        assertTrue(PhoneMaskingUtils.canViewUnmaskedConfidentialPhone(admin, revealToggleOn = false))
        assertTrue(PhoneMaskingUtils.canViewUnmaskedConfidentialPhone(manager, revealToggleOn = false))
        assertFalse(PhoneMaskingUtils.canViewUnmaskedConfidentialPhone(exec, revealToggleOn = false))
        assertTrue(PhoneMaskingUtils.canViewUnmaskedConfidentialPhone(exec, revealToggleOn = true))

        val masked = PhoneMaskingUtils.formatPhoneForRole("+91 9876543210", canViewFull = false)
        assertEquals("+91 98••• ••210", masked)

        val unmasked = PhoneMaskingUtils.formatPhoneForRole("9876543210", canViewFull = true)
        assertTrue(unmasked.contains("9876543210") || unmasked.replace(" ", "").endsWith("9876543210"))
    }

    @Test
    fun tenantLeaseAlertHelper_renewal90dAndEscalation30d() {
        val today = LocalDate.of(2026, 6, 1)
        assertTrue(TenantLeaseAlertHelper.isRenewal90dAlert("2026-08-20", today))
        assertFalse(TenantLeaseAlertHelper.isRenewal90dAlert("2027-01-15", today))

        // Lease started 2025-06-20 -> next anniversary 2026-06-20 (19 days away <= 30d)
        assertTrue(
            TenantLeaseAlertHelper.isEscalation30dAlert(
                leaseStartDate = "2025-06-20",
                leaseExpiryDate = "2028-06-19",
                today = today
            )
        )
        assertFalse(
            TenantLeaseAlertHelper.isEscalation30dAlert(
                leaseStartDate = "2025-11-20",
                leaseExpiryDate = "2028-11-19",
                today = today
            )
        )
    }

    @Test
    fun offlineDiskCache_22ColTenantAnd9ColBrokerVendorRoundtrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val checklist = TenantMoveInChecklist(
            agreementSigned = true,
            policeVerification = true,
            societyNoc = false,
            keysHandedOver = true,
            inventoryListSigned = false
        )
        val issue = TenantIssueRecord(
            id = "ISS-101",
            tenantId = "TNT-101",
            propertyCode = "TLC-1001",
            category = TenantIssueCategory.ELECTRICITY,
            status = TenantIssueStatus.WAITING_ON_OWNER,
            description = "DG meter backup check"
        )
        val tenant = OfflineDiskCache.TenantCacheRecord(
            id = "TNT-101",
            tenantName = "Nova Systems",
            propertyCode = "TLC-1001",
            projectName = "Panchshil Futura",
            location = "Viman Nagar",
            monthlyRent = "145000",
            leaseStartDate = "2025-06-20",
            leaseExpiryDate = "2028-06-19",
            lockInPeriod = "24 Months",
            contactNumber = "9822001122",
            notes = "Corporate office lease",
            unitNumber = "704",
            division = "Commercial",
            companyName = "Nova Systems Pvt Ltd",
            tenantEmail = "ops@novasystems.in",
            depositAmount = "870000",
            escalationPercent = "5",
            lockInEndDate = "2027-06-19",
            status = "Active",
            moveInChecklistJson = checklist.toJson().toString(),
            openIssuesJson = TenantIssueRecord.serializeList(listOf(issue)),
            ownerName = "Rajesh Patil",
            ownerContact = "9811223344",
            updatedAt = "2026-06-01T10:00:00Z"
        )
        OfflineDiskCache.saveTenants(context, listOf(tenant))
        val loadedTenants = OfflineDiskCache.loadTenants(context)
        assertEquals(1, loadedTenants.size)
        val rt = loadedTenants.first()
        assertEquals("704", rt.unitNumber)
        assertEquals("Rajesh Patil", rt.ownerName)
        assertEquals(3, rt.parsedChecklist().completedCount())
        assertEquals(1, rt.openIssuesCount())
        assertEquals(TenantIssueCategory.ELECTRICITY, rt.parsedIssues().first().category)

        val bv = BrokerVendorRecord(
            id = "BV-201",
            name = "Mahesh Electric",
            phone = "9765001122",
            roleCategory = "Ops / Vendor",
            subRole = "Electrician",
            firmName = "VoltPro Services",
            operatingAreas = "Kharadi, Viman Nagar",
            notes = "Fast response"
        )
        OfflineDiskCache.saveBrokersVendors(context, listOf(bv))
        val loadedBv = OfflineDiskCache.loadBrokersVendors(context)
        assertEquals(1, loadedBv.size)
        assertEquals("Electrician", loadedBv.first().subRole)
        assertEquals("VoltPro Services", loadedBv.first().firmName)
    }

    @Test
    fun muteForever_andWhatsAppAutoLog_andSixRoleMatching() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Mute Forever fast in-memory + disk persistence
        IgnoredNumbersManager.markPersonalIgnored(context, "+91 99887-76655")
        assertTrue(IgnoredNumbersManager.isIgnoredOrInternal(context, "9988776655"))
        IgnoredNumbersManager.unignoreNumber(context, "9988776655")
        assertFalse(IgnoredNumbersManager.isIgnoredOrInternal(context, "9988776655"))

        // Header WhatsApp Auto-Log
        val baseCall = CrmCallRecord(
            id = "call_wa_1",
            callerName = "Unknown Caller",
            phoneNumber = "9123456780",
            callType = CallType.INCOMING
        )
        val logged = CrmCallManager.logWhatsAppTouchOnCall(context, baseCall)
        assertTrue(logged.waLogged)
        assertTrue(logged.notes.contains("Connected on WhatsApp"))

        // 6-Role Matching: Tenant & Ops/Vendor
        val tenantList = listOf(
            OfflineDiskCache.TenantCacheRecord(
                id = "TNT-900",
                tenantName = "Ananya Verma",
                projectName = "BlueRidge",
                unitNumber = "1204",
                contactNumber = "+91 98100 22334"
            )
        )
        val bvList = listOf(
            BrokerVendorRecord(
                id = "BV-900",
                name = "Ramesh Plumber",
                phone = "9820033445",
                roleCategory = "Ops / Vendor",
                subRole = "Plumber",
                firmName = "Pune Plumbing"
            )
        )

        val tenantMatch = CrmCallManager.matchCallToDatabase(
            context = context,
            call = CrmCallRecord(callerName = "Unknown", phoneNumber = "9810022334", callType = CallType.INCOMING),
            leads = emptyList(),
            inventory = emptyList(),
            directoryProjects = emptyList(),
            tenants = tenantList,
            brokersVendors = bvList
        )
        assertEquals(PostCallRole.TENANT, tenantMatch.role)
        assertEquals("Ananya Verma", tenantMatch.displayTitle)

        val opsMatch = CrmCallManager.matchCallToDatabase(
            context = context,
            call = CrmCallRecord(callerName = "Unknown", phoneNumber = "+91 9820033445", callType = CallType.INCOMING),
            leads = emptyList(),
            inventory = emptyList(),
            directoryProjects = emptyList(),
            tenants = tenantList,
            brokersVendors = bvList
        )
        assertEquals(PostCallRole.OPS_VENDOR, opsMatch.role)
        assertEquals("Ramesh Plumber", opsMatch.displayTitle)
    }
}
