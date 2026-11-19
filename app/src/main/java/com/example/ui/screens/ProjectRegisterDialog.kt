package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.master.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ProjectRegisterDialog(
    repository: MasterProjectRepository,
    onDismiss: () -> Unit,
    onProjectRegistered: (ProjectEntity) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var projectName by remember { mutableStateOf("") }
    var projectType by remember { mutableStateOf(ProjectType.COMMERCIAL) }
    var developerName by remember { mutableStateOf("") }
    var microMarket by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var reraNumber by remember { mutableStateOf("") }
    var possessionDate by remember { mutableStateOf("") }
    var minCarpet by remember { mutableStateOf("") }
    var maxCarpet by remember { mutableStateOf("") }
    var priceFrom by remember { mutableStateOf("") }
    var pocName by remember { mutableStateOf("") }
    var pocMobile by remember { mutableStateOf("") }
    var remarks by remember { mutableStateOf("") }

    var similarProjectWarning by remember { mutableStateOf<ProjectEntity?>(null) }
    var showWarningDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BackgroundSoft
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar
            Surface(
                color = SurfaceWhite,
                shadowElevation = 2.dp,
                border = BorderStroke(0.5.dp, BorderGrey),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Register New Master Project",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Scrollable Section Cards
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Card 1: Project Identity
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    border = BorderStroke(1.dp, BorderGrey),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Project Identity", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)

                        OutlinedTextField(
                            value = projectName,
                            onValueChange = { projectName = it },
                            label = { Text("Project Name *") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryActionOrange,
                                unfocusedBorderColor = BorderGrey,
                                focusedLabelColor = PrimaryActionOrange
                            )
                        )

                        Text("Project Division / Type", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(ProjectType.COMMERCIAL to "Commercial", ProjectType.RESIDENTIAL to "Residential").forEach { (type, label) ->
                                val selected = projectType == type
                                Surface(
                                    selected = selected,
                                    onClick = { projectType = type },
                                    color = if (selected) PrimaryActionOrange else BackgroundSoft,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (selected) PrimaryActionOrange else BorderGrey),
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Text(
                                            text = label,
                                            color = if (selected) OnPrimaryActionColor else TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = developerName,
                            onValueChange = { developerName = it },
                            label = { Text("Developer Name *") },
                            placeholder = { Text("e.g. Panchshil Realty") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                        )

                        OutlinedTextField(
                            value = microMarket,
                            onValueChange = { microMarket = it },
                            label = { Text("Micro-Market / Locality *") },
                            placeholder = { Text("e.g. Kharadi, Viman Nagar, Wakad") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                        )
                    }
                }

                // Card 2: Configurations & RERA
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    border = BorderStroke(1.dp, BorderGrey),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Configurations & RERA", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)

                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            label = { Text("Commercial Types / Configurations *") },
                            placeholder = { Text("e.g. IT Park, Retail Showroom, 3 BHK") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = minCarpet,
                                onValueChange = { minCarpet = it },
                                label = { Text("Min Area (sq.ft)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                            )
                            OutlinedTextField(
                                value = maxCarpet,
                                onValueChange = { maxCarpet = it },
                                label = { Text("Max Area (sq.ft)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = priceFrom,
                                onValueChange = { priceFrom = it },
                                label = { Text("Price From / Rent From") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                            )
                            OutlinedTextField(
                                value = possessionDate,
                                onValueChange = { possessionDate = it },
                                label = { Text("Possession Date") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                            )
                        }

                        OutlinedTextField(
                            value = reraNumber,
                            onValueChange = { reraNumber = it },
                            label = { Text("MahaRERA Registration Number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                        )
                    }
                }

                // Card 3: Developer Sales POC
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    border = BorderStroke(1.dp, BorderGrey),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Developer Sales POC", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)

                        OutlinedTextField(
                            value = pocName,
                            onValueChange = { pocName = it },
                            label = { Text("POC Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                        )

                        OutlinedTextField(
                            value = pocMobile,
                            onValueChange = { pocMobile = it },
                            label = { Text("10-digit Mobile") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                        )

                        OutlinedTextField(
                            value = remarks,
                            onValueChange = { remarks = it },
                            label = { Text("Remarks") },
                            singleLine = false,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryActionOrange, unfocusedBorderColor = BorderGrey)
                        )
                    }
                }
            }

            // Sticky Bottom Bar with navigationBarsPadding and imePadding
            Surface(
                color = SurfaceWhite,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, BorderGrey),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            if (projectName.isBlank() || developerName.isBlank() || microMarket.isBlank() || category.isBlank()) {
                                return@Button
                            }
                            coroutineScope.launch {
                                val similar = repository.findSimilarProject(projectName)
                                if (similar != null) {
                                    similarProjectWarning = similar
                                    showWarningDialog = true
                                } else {
                                    val created = repository.registerProject(
                                        projectName = projectName,
                                        projectType = projectType,
                                        developerName = developerName,
                                        microMarket = microMarket,
                                        category = category,
                                        reraNumber = reraNumber.ifBlank { null }
                                    )
                                    onProjectRegistered(created)
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                        modifier = Modifier.weight(1.8f).height(48.dp)
                    ) {
                        Text("Save & Go Live", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showWarningDialog && similarProjectWarning != null) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            containerColor = SurfaceWhite,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarningAmber)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Potential Duplicate Detected", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "A project named '${similarProjectWarning!!.projectName}' (${similarProjectWarning!!.projectId}) already exists in ${similarProjectWarning!!.microMarket}. Do you still want to register this new project?",
                    color = SecondaryText,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWarningDialog = false
                        coroutineScope.launch {
                            val created = repository.registerProject(
                                projectName = projectName,
                                projectType = projectType,
                                developerName = developerName,
                                microMarket = microMarket,
                                category = category,
                                reraNumber = reraNumber.ifBlank { null }
                            )
                            onProjectRegistered(created)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusWarningAmber, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Proceed & Register", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) {
                    Text("Review Existing", color = PrimaryActionOrange)
                }
            }
        )
    }
}
