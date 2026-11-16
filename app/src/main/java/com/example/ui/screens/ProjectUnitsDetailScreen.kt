package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.master.*
import com.example.model.CalculationUtils
import com.example.ui.theme.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectUnitsDetailScreen(
    projectWithUnits: ProjectWithUnits,
    repository: MasterProjectRepository,
    onBack: () -> Unit,
    onAddUnitClick: () -> Unit
) {
    val project = projectWithUnits.project
    val units by repository.observeUnitsForProject(project.projectId).collectAsState(initial = projectWithUnits.units)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = project.projectName,
                            color = TextPrimaryDark,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Developer: ${project.developerName} • ${project.microMarket}",
                            color = BrandGold,
                            fontSize = 10.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimaryDark)
                    }
                },
                actions = {
                    Button(
                        onClick = onAddUnitClick,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGold),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = BrandCharcoalDark, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Unit", color = BrandCharcoalDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandCharcoalDark)
            )
        },
        containerColor = BrandCharcoalDark
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(BrandCharcoalDark)
                .padding(16.dp)
        ) {
            // Project Specifications Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BrandCharcoalBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Project ID: ${project.projectId}", color = PrimaryTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("MahaRERA: ${project.reraNumber ?: "N/A"}", color = TextSecondaryDark, fontSize = 11.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Category: ${project.category}", color = TextPrimaryDark, fontSize = 12.sp)
                        Text("Status: ${project.status.name}", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "LINKED EMPLOYEE UNIT INVENTORY (${units.size})",
                color = BrandGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (units.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Inventory2, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No units linked to this project yet.", color = TextSecondaryDark, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(units, key = { it.unitId }) { unit ->
                        UnitItemCard(unit = unit)
                    }
                }
            }
        }
    }
}

@Composable
fun UnitItemCard(unit: PropertyUnitEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.8.dp, BrandCharcoalBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = unit.propertyCode, color = BrandGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "• Unit ${unit.unitNumber} (${unit.towerBuilding ?: "Main"})", color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(unit.status, color = Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Carpet Area: ${unit.areaSqFt.toInt()} sq.ft", color = TextSecondaryDark, fontSize = 11.sp)
                Text(text = "Price: ₹${CalculationUtils.formatIndianCurrency(unit.pricing)}", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            if (!unit.ownerName.isNullOrBlank()) {
                Text(text = "Owner POC: ${unit.ownerName} (${unit.ownerContact ?: ""})", color = TextSecondaryDark, fontSize = 10.sp)
            }
        }
    }
}
