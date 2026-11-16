package com.example.model

/**
 * Public-facing broker group messages. Never include owner, POC, broker source,
 * internal remarks, deposit contacts, or other confidential inventory fields.
 */
object BrokerGroupShareFormatter {
    fun formatProperty(property: PropertyItem): String = buildList {
        add("*${if (property.tab.isRental) "FOR LEASE" else "FOR SALE"} · ${property.propertyCode.ifBlank { "Property" }}*")
        add("🏢 *${property.projectName.ifBlank { "Project Not Mentioned" }}*")
        if (property.location.isNotBlank()) add("📍 ${property.location}")
        val kind = property.propertyType.trim().takeUnless {
            it.isBlank() || it.equals("Commercial", true) || it.equals("Residential", true)
        } ?: when (property.tab) {
            InventoryTab.OFF_LEASE, InventoryTab.OFF_OUT -> "Office"
            InventoryTab.SHOP_LEASE, InventoryTab.SHOP_OUT -> "Shop / Showroom"
            InventoryTab.PRE_LEASED -> "Pre-Leased Commercial"
            else -> property.tab.title
        }
        add("🏷 Type: $kind")
        if (property.carpetAreaSqFt > 0) add("📐 Carpet: ${property.carpetAreaSqFt.toInt()} sq.ft")
        property.builtUpAreaSqFt?.takeIf { it > 0 }?.let { add("📐 Built-up: ${it.toInt()} sq.ft") }
        add("💰 ${if (property.tab.isRental) "Rent" else "Price"}: ${if (property.priceOrRent > 0) CalculationUtils.formatIndianCurrency(property.priceOrRent, property.tab.isRental) else "Not Mentioned"}")
        val rate = CalculationUtils.calculateRatePerBuiltUp(property.priceOrRent, property.builtUpAreaSqFt)
        add("📊 Built-up rate: ${rate?.let { CalculationUtils.formatIndianCurrencyFull(it) + "/sq.ft" + if (property.tab.isRental) "/mo" else "" } ?: "Not Available"}")
        add("🧾 Maintenance: ${property.maintenance?.takeIf { it.isFinite() && it >= 0 }?.let { CalculationUtils.formatIndianCurrency(it) + " (period not specified)" } ?: "Not Mentioned"}")
        add("🔑 Possession: ${property.possession.ifBlank { "Not Mentioned" }}")
        add("📌 Status: ${property.status.label}")
    }.joinToString("\n")

    fun formatBatch(properties: List<PropertyItem>): String =
        properties.distinctBy { it.id }.joinToString("\n\n────────────\n\n", transform = ::formatProperty)
}

