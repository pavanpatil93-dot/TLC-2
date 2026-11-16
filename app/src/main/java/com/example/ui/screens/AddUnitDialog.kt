package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.master.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AddUnitDialog(
    project: ProjectEntity,
    repository: MasterProjectRepository,
    onDismiss: () -> Unit,
    onUnitAdded: (PropertyUnitEntity) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var propertyCode by remember { mutableStateOf("UNIT-${(100..999).random()}") }
    var towerBuilding by remember { mutableStateOf("Tower A") }
    var unitNumber by remember { mutableStateOf("101") }
    var floorStr by remember { mutableStateOf("2") }
    var areaSqFtStr by remember { mutableStateOf("1200") }
    var pricingStr by remember { mutableStateOf("15000000") }
    var ownerName by remember { mutableStateOf("") }
    var ownerContact by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BrandCharcoalSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AddHome, contentDescription = null, tint = BrandGold)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Unit to ${project.projectName}", color = TextPrimaryDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = propertyCode,
                    onValueChange = { propertyCode = it },
                    label = { Text("Property Code *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = towerBuilding,
                    onValueChange = { towerBuilding = it },
                    label = { Text("Tower / Building") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = unitNumber,
                        onValueChange = { unitNumber = it },
                        label = { Text("Unit / Office No *") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = floorStr,
                        onValueChange = { floorStr = it },
                        label = { Text("Floor") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = areaSqFtStr,
                        onValueChange = { areaSqFtStr = it },
                        label = { Text("Carpet Area (sq.ft) *") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pricingStr,
                        onValueChange = { pricingStr = it },
                        label = { Text("Price / Value (₹) *") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = ownerName,
                    onValueChange = { ownerName = it },
                    label = { Text("Owner / Landlord Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = ownerContact,
                    onValueChange = { ownerContact = it },
                    label = { Text("Owner Mobile Number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val area = areaSqFtStr.toDoubleOrNull() ?: 1000.0
                    val price = pricingStr.toDoubleOrNull() ?: 5000000.0
                    val floor = floorStr.toIntOrNull() ?: 1

                    coroutineScope.launch {
                        val unit = repository.addUnitToProject(
                            projectId = project.projectId,
                            propertyCode = propertyCode,
                            towerBuilding = towerBuilding,
                            unitNumber = unitNumber,
                            floor = floor,
                            areaSqFt = area,
                            pricing = price,
                            rentAmount = null,
                            tab = if (project.projectType == ProjectType.COMMERCIAL) "Off Lease" else "Resi Resale",
                            furnishing = "Fully Furnished",
                            status = "Available",
                            ownerName = ownerName.ifBlank { null },
                            ownerContact = ownerContact.ifBlank { null }
                        )
                        onUnitAdded(unit)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save Unit", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondaryDark)
            }
        }
    )
}
