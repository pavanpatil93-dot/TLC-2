package com.example.inventory

import com.example.model.PropertyItem
import com.example.model.InventoryTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventorySearchTest {
    private val items = listOf(
        PropertyItem(id="1",propertyCode="OL-PUN-101",projectName="Gera Imperium Alpha",location="Kharadi",carpetAreaSqFt=500.0,priceOrRent=45000.0,ownerName="Asha",status=com.example.model.AvailabilityStatus.AVAILABLE),
        PropertyItem(id="2",propertyCode="SL-PUN-102",projectName="Riverdale",location="Mundhwa",carpetAreaSqFt=900.0,priceOrRent=90000.0)
    )
    @Test fun searchesProjectLocationAndOwner() {
        assertEquals("1", InventorySearch.filter(items, InventorySearchQuery(text="asha")).single().id)
        assertEquals("1", InventorySearch.filter(items, InventorySearchQuery(text="kharadi")).single().id)
    }
    @Test fun appliesAreaAndPriceFilters() {
        assertEquals("1", InventorySearch.filter(items, InventorySearchQuery(minArea=400.0,maxArea=700.0,maxPrice=50000.0)).single().id)
    }
    @Test fun ranksAvailableBeforeUnconfirmed() {
        val result=InventorySearch.filter(items, InventorySearchQuery())
        assertTrue(result.first().status==com.example.model.AvailabilityStatus.AVAILABLE)
    }
}

