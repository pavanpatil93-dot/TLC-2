package com.example.crm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.AvailabilityStatus
import com.example.model.InventoryTab
import com.example.model.PropertyItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactManagerTest {

    @Test
    fun testNormalizePhoneNumber() {
        assertEquals("9876543210", ContactUtils.normalizePhoneNumber("+91 98765-43210"))
        assertEquals("9876543210", ContactUtils.normalizePhoneNumber("09876543210"))
        assertEquals("9876543210", ContactUtils.normalizePhoneNumber("98765 43210"))
        assertEquals("9876543210", ContactUtils.normalizePhoneNumber("(987) 654-3210"))
    }

    @Test
    fun testFormatDisplayPhone() {
        assertEquals("98765 43210", ContactUtils.formatDisplayPhone("9876543210"))
        assertEquals("98765 43210", ContactUtils.formatDisplayPhone("+91-98765-43210"))
    }

    @Test
    fun testFindDuplicateContactsInProperties() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampleProperties = listOf(
            PropertyItem(
                id = "p1",
                propertyCode = "OL-101",
                tab = InventoryTab.OFF_LEASE,
                projectName = "Gera Imperium",
                ownerName = "Rajesh Shah",
                ownerContact = "9876543210",
                sourceInfo = "Broker: Vikram CP (9822001122)",
                status = AvailabilityStatus.AVAILABLE
            )
        )

        val duplicatesOwner = findDuplicateContacts(context, "9876543210", sampleProperties)
        assertEquals(1, duplicatesOwner.size)
        assertEquals("Property Owner", duplicatesOwner[0].role)
        assertEquals("Rajesh Shah", duplicatesOwner[0].name)
    }
}

