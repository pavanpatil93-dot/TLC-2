package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.master.*
import com.example.model.CalculationUtils
import com.example.ui.theme.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterProjectDashboardScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { MasterProjectRepository(context) }

    var selectedTab by remember { mutableStateOf(0) } // 0: Commercial, 1: Residential, 2: All Properties
    var searchQuery by remember { mutableStateOf("") }
    var showRegisterDialog by remember { mutableStateOf(false) }
    var selectedProjectForUnits by remember { mutableStateOf<ProjectWithUnits?>(null) }
    var showAddUnitDialog by remember { mutableStateOf(false) }

    val commercialProjects by repository.commercialProjectsWithUnits.collectAsState(initial = emptyList())
    val residentialProjects by repository.residentialProjectsWithUnits.collectAsState(initial = emptyList())
    val allProjects by repository.allProjectsWithUnits.collectAsState(initial = emptyList())

    val currentList = when (selectedTab) {
        0 -> commercialProjects
        1 -> residentialProjects
        else -> allProjects
    }.filter {
        searchQuery.isBlank() ||
        it.project.projectName.contains(searchQuery, ignoreCase = true) ||
        it.project.developerName.contains(searchQuery, ignoreCase = true) ||
        it.project.microMarket.contains(searchQuery, ignoreCase = true) ||
        it.project.projectId.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "PROJECT MASTER & INVENTORY",
                            color = TextPrimaryDark,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "TLC-1 Pune Real Estate Intelligence Hub",
                            color = BrandGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimaryDark)
                    }
                },
                actions = {
                    Button(
                        onClick = { showRegisterDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGold),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = BrandCharcoalDark, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Project", color = BrandCharcoalDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Tab Navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BrandCharcoalSurface)
                    .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(10.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TabButton(
                    title = "Commercial (${commercialProjects.size})",
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    title = "Residential (${residentialProjects.size})",
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    title = "All Projects (${allProjects.size})",
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search by Project Name, Developer, Micro-Market...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BrandGold) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondaryDark)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandGold,
                    unfocusedBorderColor = BrandCharcoalBorder,
                    focusedLabelColor = BrandGold,
                    unfocusedLabelColor = TextSecondaryDark,
                    focusedTextColor = TextPrimaryDark,
                    unfocusedTextColor = TextPrimaryDark
                ),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Project List
            if (currentList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextSecondaryDark, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No projects found matching query.", color = TextSecondaryDark, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(currentList, key = { it.project.projectId }) { item ->
                        ProjectSummaryCard(
                            projectWithUnits = item,
                            onViewUnits = { selectedProjectForUnits = item },
                            onAddUnit = {
                                selectedProjectForUnits = item
                                showAddUnitDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showRegisterDialog) {
        ProjectRegisterDialog(
            repository = repository,
            onDismiss = { showRegisterDialog = false },
            onProjectRegistered = {
                showRegisterDialog = false
            }
        )
    }

    if (selectedProjectForUnits != null && !showAddUnitDialog) {
        ProjectUnitsDetailScreen(
            projectWithUnits = selectedProjectForUnits!!,
            repository = repository,
            onBack = { selectedProjectForUnits = null },
            onAddUnitClick = { showAddUnitDialog = true }
        )
    }

    if (showAddUnitDialog && selectedProjectForUnits != null) {
        AddUnitDialog(
            project = selectedProjectForUnits!!.project,
            repository = repository,
            onDismiss = { showAddUnitDialog = false },
            onUnitAdded = {
                showAddUnitDialog = false
            }
        )
    }
}

@Composable
fun TabButton(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) BrandGold else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (selected) BrandCharcoalDark else TextPrimaryDark,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ProjectSummaryCard(
    projectWithUnits: ProjectWithUnits,
    onViewUnits: () -> Unit,
    onAddUnit: () -> Unit
) {
    val project = projectWithUnits.project
    val units = projectWithUnits.units

    val totalUnits = units.size
    val availableCount = units.count { it.status.equals("Available", ignoreCase = true) }
    val preLeasedCount = units.count { it.tab.contains("Pre", ignoreCase = true) || it.tab.contains("Lease", ignoreCase = true) }

    val minPrice = units.minOfOrNull { it.pricing } ?: 0.0
    val maxPrice = units.maxOfOrNull { it.pricing } ?: 0.0
    val avgRatePerSqFt = if (units.isNotEmpty()) units.map { it.pricing / it.areaSqFt }.average() else 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BrandCharcoalBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (project.projectType == ProjectType.COMMERCIAL) PrimaryTeal.copy(alpha = 0.2f) else BrandGold.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = project.projectId,
                            color = if (project.projectType == ProjectType.COMMERCIAL) PrimaryTeal else BrandGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = project.projectName,
                        color = TextPrimaryDark,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(project.verificationStatus.name, color = Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Developer & Location
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Developer: ${project.developerName}", color = TextSecondaryDark, fontSize = 12.sp)
                Text(text = "${project.microMarket}, ${project.location}", color = BrandGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            Divider(color = BrandCharcoalBorder, thickness = 0.8.dp)

            // Dynamic Inventory Rollup
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InventoryStatItem("Total Units", "$totalUnits")
                InventoryStatItem("Available", "$availableCount", Color(0xFF10B981))
                InventoryStatItem("Leased/Pre", "$preLeasedCount", PrimaryTeal)
            }

            // Financial Analytics
            if (units.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Price Range: ₹${CalculationUtils.formatIndianCurrency(minPrice)} - ₹${CalculationUtils.formatIndianCurrency(maxPrice)}",
                        color = TextPrimaryDark,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Avg Rate: ₹${avgRatePerSqFt.toInt()}/sq.ft",
                        color = BrandGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Text(
                    text = "No employee units linked yet. Add a unit below.",
                    color = TextSecondaryDark,
                    fontSize = 11.sp
                )
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onViewUnits,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BrandGold),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandGold)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("View Units ($totalUnits)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onAddUnit,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("+ Add Unit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun InventoryStatItem(label: String, value: String, valueColor: Color = TextPrimaryDark) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = TextSecondaryDark, fontSize = 10.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
