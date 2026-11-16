package com.example.inventory

import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class Build02InventoryTest {
    private fun item(id: String="a") = PropertyItem(id=id, projectName="Test Tower", location="Kharadi", unitNumber="101", floor="1", carpetAreaSqFt=500.0, builtUpAreaSqFt=700.0, status=com.example.model.AvailabilityStatus.LEASED, tab=InventoryTab.OFF_LEASE)
    @Test fun everyCategorySupportsEightLifecycleStates() {
        assertEquals(8, InventoryTab.entries.size)
        assertEquals(8, com.example.model.AvailabilityStatus.lifecycleStates.size)
        InventoryTab.entries.forEach { tab -> com.example.model.AvailabilityStatus.lifecycleStates.forEach { state ->
            assertEquals(state, item().copy(tab=tab,status=state).status)
            assertEquals(state, com.example.model.AvailabilityStatus.parse(state.label))
        } }
    }
    @Test fun vacancyBoundariesAndRestrictions() {
        val now=LocalDate.of(2026,11,12)
        assertTrue(InventoryQuality.upcoming(item().copy(leaseExpiryDate=now.toString()),now))
        assertTrue(InventoryQuality.upcoming(item().copy(leaseExpiryDate=now.plusDays(90).toString()),now))
        assertFalse(InventoryQuality.upcoming(item().copy(leaseExpiryDate=now.plusDays(91).toString()),now))
        assertFalse(InventoryQuality.upcoming(item().copy(leaseExpiryDate=now.minusDays(1).toString()),now))
        assertTrue(InventoryQuality.upcoming(item().copy(upcomingVacancyDate=now.plusDays(5).toString()),now))
        assertFalse(InventoryQuality.upcoming(item().copy(tab=InventoryTab.RESI_RENT,leaseExpiryDate=now.toString()),now))
        assertFalse(InventoryQuality.upcoming(item().copy(status=com.example.model.AvailabilityStatus.SOLD,leaseExpiryDate=now.toString()),now))
    }
    @Test fun duplicateFactorsRequireEvidence() {
        assertEquals(9,InventoryQuality.factors(item(),item("b")).size)
        assertTrue(InventoryQuality.suspected(item(),item("b")))
        assertFalse(InventoryQuality.suspected(PropertyItem(id="a",floor=""), PropertyItem(id="b",floor="")))
        assertFalse(InventoryQuality.suspected(item(),item()))
    }
    @Test fun missingDatesAndCompletenessAreHonest() {
        assertFalse(InventoryQuality.validDates(item().copy(leaseExpiryDate="2026-02-30")))
        assertTrue(InventoryQuality.validDates(item().copy(leaseExpiryDate="")))
        val missing=PropertyItem(id="empty",possession="",parking="",floor="")
        assertEquals(0,InventoryQuality.completeness(missing))
        assertEquals("Not Mentioned",InventoryQuality.shown(null))
    }
    @Test fun publicSharingNeverIncludesPrivateFields() {
        val p=item().copy(ownerName="SECRET_OWNER", ownerContact="SECRET_PHONE",brokerName="SECRET_BROKER",brokerContact="SECRET_BROKER_PHONE",sourceInfo="SECRET_SOURCE",internalRemarks="SECRET_INTERNAL",remarks="SECRET_LEGACY_REMARK")
        assertFalse(BrokerGroupShareFormatter.formatProperty(p).contains("SECRET"))
        assertFalse(BrokerGroupShareFormatter.formatBatch(listOf(p)).contains("SECRET"))
    }
}
