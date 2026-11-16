package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.*
import com.example.ui.theme.*

@Composable
fun AddEditPropertyDialog(
    existingProperty: PropertyItem? = null,
    onCheckDuplicates: (String, String, String, String?) -> List<PropertyItem>,
    onSave: (PropertyItem) -> Boolean,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(existingProperty?.tab ?: InventoryTab.OFF_LEASE) }
    val context = LocalContext.current
    var propertyCode by remember { mutableStateOf(existingProperty?.propertyCode ?: "OL-${(100..999).random()}") }
    var projectName by remember { mutableStateOf(existingProperty?.projectName ?: "") }
    var location by remember { mutableStateOf(existingProperty?.location ?: "") }
    var unitNumber by remember { mutableStateOf(existingProperty?.unitNumber ?: "") }
    var carpetArea by remember { mutableStateOf(existingProperty?.carpetAreaSqFt?.takeIf { it > 0 }?.toInt()?.toString() ?: "") }
    var priceOrRent by remember { mutableStateOf(existingProperty?.priceOrRent?.takeIf { it > 0 }?.toInt()?.toString() ?: "") }
    var deposit by remember { mutableStateOf(existingProperty?.deposit?.takeIf { it > 0 }?.toInt()?.toString() ?: "") }
    var furnishing by remember { mutableStateOf(existingProperty?.furnishing ?: FurnishingType.SEMI_FURNISHED) }
    var possession by remember { mutableStateOf(existingProperty?.possession ?: "Immediate") }
    var parking by remember { mutableStateOf(existingProperty?.parking ?: "2 Covered") }
    var status by remember { mutableStateOf(existingProperty?.status ?: AvailabilityStatus.NOT_CONFIRMED) }

    // Internal fields
    var ownerName by remember { mutableStateOf(existingProperty?.ownerName ?: "") }
    var ownerContact by remember { mutableStateOf(existingProperty?.ownerContact ?: "") }
    var internalRemarks by remember { mutableStateOf(existingProperty?.internalRemarks ?: "") }

    val duplicates = remember(propertyCode, projectName, unitNumber) {
        onCheckDuplicates(propertyCode, projectName, unitNumber, existingProperty?.id)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(16.dp))
                .testTag("add_property_dialog"),
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (existingProperty == null) "Add Inventory Record" else "Edit Listing",
                            color = TextPrimaryDark,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "New listings default to 'Not Confirmed'",
                            color = StatusUnconfirmed,
                            fontSize = 10.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark)
                    }
                }

                // Duplicate Alert Warning
                if (duplicates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF331B1B))
                            .border(1.dp, StatusNotAvailable, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = StatusNotAvailable, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Warning: Duplicate code or project/unit found (${duplicates.first().propertyCode})",
                                color = StatusNotAvailable,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Inventory Tab Selector
                Text("Select Inventory Sheet:", color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    InventoryTab.values().filter { it != InventoryTab.UND_CONST }.take(4).forEach { t ->
                        val isSel = tab == t
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) BrandGold else BrandCharcoalCard)
                                .clickable { tab = t }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(t.code, color = if (isSel) BrandCharcoalDark else TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    InventoryTab.values().filter { it != InventoryTab.UND_CONST }.drop(4).forEach { t ->
                        val isSel = tab == t
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) BrandGold else BrandCharcoalCard)
                                .clickable { tab = t }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(t.code, color = if (isSel) BrandCharcoalDark else TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                DialogInput(label = "Property Code", value = propertyCode, onValueChange = { propertyCode = it })
                DialogInput(label = "Project / Building Name", value = projectName, onValueChange = { projectName = it })
                DialogInput(label = "Location (e.g. Kharadi, Pune)", value = location, onValueChange = { location = it })
                DialogInput(label = "Unit Number / Floor", value = unitNumber, onValueChange = { unitNumber = it })
                DialogInput(label = "Carpet Area (sq.ft)", value = carpetArea, onValueChange = { carpetArea = it })
                DialogInput(label = if (tab.isRental) "Monthly Rent (₹)" else "Sale Price (₹)", value = priceOrRent, onValueChange = { priceOrRent = it })
                DialogInput(label = "Security Deposit (₹)", value = deposit, onValueChange = { deposit = it })

                Spacer(modifier = Modifier.height(10.dp))
                Text("INTERNAL DATA (CONFIDENTIAL):", color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                com.example.crm.ReusableContactPickerField(
                    name = ownerName,
                    onNameChange = { ownerName = it },
                    phone = ownerContact,
                    onPhoneChange = { ownerContact = it },
                    labelPrefix = "Owner / Landlord"
                )
                DialogInput(label = "Internal Remarks", value = internalRemarks, onValueChange = { internalRemarks = it })

                Spacer(modifier = Modifier.height(14.dp))

                // Save Button
                Button(
                    onClick = {
                        val validation = com.example.inventory.PropertyEntryValidation.error(com.example.inventory.PropertyEntryDraft(projectName, location, ownerContact, carpetArea, priceOrRent))
                        if (validation != null) { Toast.makeText(context, validation, Toast.LENGTH_SHORT).show(); return@Button }
                        val prop = PropertyItem(
                            id = existingProperty?.id ?: "prop_${System.currentTimeMillis()}",
                            propertyCode = propertyCode.trim(),
                            tab = tab,
                            projectName = projectName.trim(),
                            location = location.trim(),
                            unitNumber = unitNumber.trim(),
                            carpetAreaSqFt = carpetArea.toDoubleOrNull() ?: 0.0,
                            priceOrRent = priceOrRent.toDoubleOrNull() ?: 0.0,
                            deposit = deposit.toDoubleOrNull(),
                            furnishing = furnishing,
                            possession = possession,
                            parking = parking,
                            status = status,
                            ownerName = ownerName.trim(),
                            ownerContact = ownerContact.trim(),
                            internalRemarks = internalRemarks.trim(),
                            images = listOf("https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&w=1200&q=80")
                        )
                        if (onSave(prop)) onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save Record to Inventory", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun DialogInput(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(2.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = BrandCharcoalDark,
                unfocusedContainerColor = BrandCharcoalDark,
                focusedBorderColor = BrandGold,
                unfocusedBorderColor = BrandCharcoalBorder,
                focusedTextColor = TextPrimaryDark,
                unfocusedTextColor = TextPrimaryDark
            ),
            singleLine = true
        )
    }
}

