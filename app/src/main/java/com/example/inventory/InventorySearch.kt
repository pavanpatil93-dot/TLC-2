package com.example.inventory

import com.example.model.PropertyItem
import java.util.Locale

data class InventorySearchQuery(
    val text: String = "",
    val minArea: Double? = null,
    val maxArea: Double? = null,
    val maxPrice: Double? = null
)

object InventorySearch {
    fun filter(properties: List<PropertyItem>, query: InventorySearchQuery): List<PropertyItem> {
        val text = query.text.trim().lowercase(Locale.US)
        return properties.filter { item ->
            val haystack = listOf(
                item.propertyCode, item.projectName, item.location, item.unitNumber,
                item.propertyType, item.ownerName, item.ownerContact, item.remarks
            ).filterNotNull().joinToString(" ").lowercase(Locale.US)
            val textMatch = text.isBlank() || haystack.contains(text)
            val areaMatch = (query.minArea == null || item.carpetAreaSqFt >= query.minArea) &&
                (query.maxArea == null || item.carpetAreaSqFt <= query.maxArea)
            val priceMatch = query.maxPrice == null || item.priceOrRent <= query.maxPrice
            textMatch && areaMatch && priceMatch
        }.sortedWith(compareBy<PropertyItem> { it.status.name != "AVAILABLE" }.thenBy { it.projectName.lowercase(Locale.US) })
    }
}

