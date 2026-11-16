package com.example.model

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.WhatsAppIntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Build06SharingTest {

    @Test
    fun publicTemplateExcludesPrivateFieldsAndUsesDeskContacts() {
        val property = PropertyItem(
            id = "p-priv",
            propertyCode = "OL-101",
            projectName = "Test Tower",
            location = "Kharadi",
            unitNumber = "Unit 909 Secret Door Pin 1234",
            carpetAreaSqFt = 500.0,
            priceOrRent = 45000.0,
            ownerName = "PRIVATE OWNER",
            ownerContact = "PRIVATE PHONE",
            brokerName = "PRIVATE BROKER",
            brokerContact = "PRIVATE BROKER PHONE",
            sourceInfo = "PRIVATE SOURCE",
            internalRemarks = "PRIVATE INTERNAL",
            tenantName = "PRIVATE TENANT",
            images = listOf("/data/user/0/com.example/files/private_photo.jpg"),
            listingLink = "https://portal.example.com/listing/ol-101?utm_source=crm&owner=PRIVATE&token=SECRET99&view=public"
        )

        val text = WhatsAppShareTemplates.property(property, senderName = "Amit Kulkarni")

        assertTrue(text.contains(WhatsAppShareTemplates.DESK_CONTACTS))
        assertTrue(text.contains("Shared by: Amit Kulkarni"))
        assertTrue(text.contains("https://portal.example.com/listing/ol-101?view=public"))
        listOf(
            "PRIVATE OWNER",
            "PRIVATE PHONE",
            "PRIVATE BROKER",
            "PRIVATE BROKER PHONE",
            "PRIVATE SOURCE",
            "PRIVATE INTERNAL",
            "PRIVATE TENANT",
            "Secret Door Pin 1234",
            "private_photo.jpg",
            "SECRET99",
            "utm_source",
            "Ascend",
            "Aurea",
            "The Leasing Company"
        ).forEach { forbidden ->
            assertFalse("Must not leak '$forbidden' in:\n$text", text.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun leasePropertyFormatsMonthlyRentRatesMaintenanceAndLockInAccurately() {
        val property = PropertyItem(
            id = "p-lease",
            propertyCode = "OL-510",
            projectName = "Platinum Square",
            location = "Viman Nagar, Pune",
            floor = "4th Floor",
            propertyType = "Office",
            tab = InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 1000.0,
            builtUpAreaSqFt = 1400.0,
            priceOrRent = 140000.0,
            deposit = 600000.0,
            maintenance = 7000.0,
            remarks = "maintenance extra paid by licensee",
            lockInPeriodYears = 2,
            leaseDurationYears = 5,
            escalationPercent = 5.0,
            escalationIntervalYears = 1,
            parking = "2 Covered Car Parks",
            possession = "Ready",
            furnishing = FurnishingType.FULLY_FURNISHED,
            workstations = 24,
            cabins = 2,
            status = AvailabilityStatus.AVAILABLE
        )

        val preview = WhatsAppShareTemplates.buildSinglePropertyPreview(property)
        val text = preview.message

        assertTrue(text.contains("Asking Monthly Rent:* ₹1.4 Lac / month"))
        assertTrue(text.contains("Carpet Area:* 1000 sq.ft"))
        assertTrue(text.contains("Built-up Area:* 1400 sq.ft"))
        assertTrue(text.contains("Carpet Rate:* ₹140/sq.ft/month"))
        assertTrue(text.contains("Built-up Rate:* ₹100/sq.ft/month"))
        assertTrue(text.contains("Deposit:* ₹6 Lac"))
        assertTrue(text.contains("Maintenance:* ₹7k (extra)"))
        assertTrue(text.contains("Lease / Lock-in:* 5 yr(s) lease, 2 yr(s) lock-in"))
        assertTrue(text.contains("Escalation:* 5% every 1 yr(s)"))
        assertTrue(text.contains("24 Workstations • 2 Cabins"))
        assertTrue(preview.missingEssentialFields.isEmpty())
        assertFalse(preview.requiresRestrictedStatusConfirmation)
    }

    @Test
    fun salePropertyOmitsBuiltUpRateWhenBuiltUpMissingAndNeverUsesDefaults() {
        val property = PropertyItem(
            id = "p-sale",
            propertyCode = "RS-208",
            projectName = "Pratham Meadows",
            location = "Baner, Pune",
            propertyType = "Apartment",
            tab = InventoryTab.RESI_RESALE,
            bhk = "3 BHK",
            carpetAreaSqFt = 1000.0,
            builtUpAreaSqFt = null,
            priceOrRent = 18500000.0,
            possession = "Immediate",
            furnishing = FurnishingType.SEMI_FURNISHED,
            status = AvailabilityStatus.AVAILABLE
        )

        val text = WhatsAppShareTemplates.property(property)

        assertTrue(text.contains("Asking Sale Price:* ₹1.85 Cr"))
        assertTrue(text.contains("Carpet Rate:* ₹18,500/sq.ft"))
        assertTrue(text.contains("Configuration:* 3 BHK"))
        assertFalse(text.contains("Built-up Area"))
        assertFalse(text.contains("Built-up Rate"))
        assertFalse(text.contains("/month"))
        assertFalse(text.contains("Gross Yield", ignoreCase = true))
    }

    @Test
    fun preLeasedComputesIndicativeGrossYieldOnlyFromValidSavedInputs() {
        val validPreLeased = PropertyItem(
            id = "pl-1",
            propertyCode = "PL-206",
            projectName = "EON Free Zone Phase 2",
            location = "Kharadi, Pune",
            tab = InventoryTab.PRE_LEASED,
            carpetAreaSqFt = 5000.0,
            priceOrRent = 80000000.0,
            monthlyRentalIncome = 500000.0,
            possession = "Leased",
            status = AvailabilityStatus.AVAILABLE
        )
        val validText = WhatsAppShareTemplates.property(validPreLeased)
        assertTrue(validText.contains("Monthly Rental Income:* ₹5 Lac / month"))
        assertTrue(validText.contains("Indicative gross yield:* 7.50% p.a."))
        assertFalse(validText.contains("Guaranteed", ignoreCase = true))

        val missingIncomePreLeased = validPreLeased.copy(
            monthlyRentalIncome = 0.0,
            priceOrRent = 0.0,
            carpetAreaSqFt = 0.0,
            possession = ""
        )
        val missingPreview = WhatsAppShareTemplates.buildSinglePropertyPreview(missingIncomePreLeased)
        assertFalse(missingPreview.message.contains("₹0"))
        assertFalse(missingPreview.message.contains("Indicative gross yield"))
        assertFalse(missingPreview.message.contains("Carpet Rate"))
        assertTrue(missingPreview.missingEssentialFields.containsAll(listOf("Carpet Area", "Sale Price", "Monthly Rental Income", "Possession")))
    }

    @Test
    fun statusHandlingFlagsSoldLeasedWithdrawnAndNotesUnconfirmedAvailability() {
        listOf(AvailabilityStatus.SOLD, AvailabilityStatus.LEASED, AvailabilityStatus.WITHDRAWN).forEach { status ->
            val preview = WhatsAppShareTemplates.buildSinglePropertyPreview(
                PropertyItem(id = status.name, propertyCode = "ST-1", projectName = "Tower A", location = "Pune", carpetAreaSqFt = 1000.0, priceOrRent = 5000000.0, possession = "Ready", status = status)
            )
            assertTrue("Expected confirmation requirement for $status", preview.requiresRestrictedStatusConfirmation)
            assertTrue(preview.message.contains("Status:* ${status.label}"))
        }

        val unconfirmed = WhatsAppShareTemplates.buildSinglePropertyPreview(
            PropertyItem(id = "unc", propertyCode = "ST-2", projectName = "Tower B", location = "Pune", status = AvailabilityStatus.NOT_CONFIRMED)
        )
        assertFalse(unconfirmed.requiresRestrictedStatusConfirmation)
        assertTrue(unconfirmed.message.contains("Availability subject to confirmation."))
    }

    @Test
    fun formatsEnglishNumeralsRegardlessOfDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            val text = WhatsAppShareTemplates.property(
                PropertyItem(
                    id = "loc",
                    propertyCode = "OL-900",
                    projectName = "WTC Kharadi",
                    location = "Kharadi",
                    tab = InventoryTab.OFF_LEASE,
                    carpetAreaSqFt = 1250.0,
                    priceOrRent = 125000.0,
                    possession = "Ready",
                    status = AvailabilityStatus.AVAILABLE
                )
            )
            assertTrue(text.contains("1250 sq.ft"))
            assertTrue(text.contains("₹1.25 Lac / month"))
            assertTrue(text.contains("₹100/sq.ft/month"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun intentRouterSupportsWhatsAppBusinessAndChooserFallback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = shadowOf(app.packageManager)

        // 1. Neither WhatsApp nor WhatsApp Business installed -> system share chooser
        val fallbackIntent = WhatsAppIntentRouter.buildShareIntent(app, "Hello Listing")
        assertEquals(Intent.ACTION_CHOOSER, fallbackIntent.action)
        val innerFallback = fallbackIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNull(innerFallback?.`package`)
        assertEquals("Hello Listing", innerFallback?.getStringExtra(Intent.EXTRA_TEXT))

        // 2. Only WhatsApp Business installed -> direct com.whatsapp.w4b intent
        val w4bProbe = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(WhatsAppIntentRouter.WHATSAPP_BUSINESS_PACKAGE)
        }
        shadowPm.addResolveInfoForIntent(
            w4bProbe,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = WhatsAppIntentRouter.WHATSAPP_BUSINESS_PACKAGE
                    name = "com.whatsapp.ContactPicker"
                }
            }
        )
        val w4bIntent = WhatsAppIntentRouter.buildShareIntent(app, "Business Share")
        assertEquals(Intent.ACTION_SEND, w4bIntent.action)
        assertEquals(WhatsAppIntentRouter.WHATSAPP_BUSINESS_PACKAGE, w4bIntent.`package`)
        assertEquals("Business Share", w4bIntent.getStringExtra(Intent.EXTRA_TEXT))

        // 3. Both WhatsApp and WhatsApp Business installed -> chooser prioritizing both
        val waProbe = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(WhatsAppIntentRouter.WHATSAPP_PACKAGE)
        }
        shadowPm.addResolveInfoForIntent(
            waProbe,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = WhatsAppIntentRouter.WHATSAPP_PACKAGE
                    name = "com.whatsapp.ContactPicker"
                }
            }
        )
        val bothIntent = WhatsAppIntentRouter.buildShareIntent(app, "Dual Share")
        assertEquals(Intent.ACTION_CHOOSER, bothIntent.action)
        val baseWa = bothIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals(WhatsAppIntentRouter.WHATSAPP_PACKAGE, baseWa?.`package`)
    }
}
