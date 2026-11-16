package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

    var similarProjectWarning by remember { mutableStateOf<ProjectEntity?>(null) }
    var showWarningDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BrandCharcoalSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Business, contentDescription = null, tint = BrandGold)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Register New Master Project", color = TextPrimaryDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Add a canonical project reference to the Pune Master Directory.", color = TextSecondaryDark, fontSize = 12.sp)

                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Project Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandGold,
                        unfocusedBorderColor = BrandCharcoalBorder,
                        focusedLabelColor = BrandGold,
                        unfocusedLabelColor = TextSecondaryDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )

                // Project Type Selector
                Text("Project Division / Type:", color = TextSecondaryDark, fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { projectType = ProjectType.COMMERCIAL },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (projectType == ProjectType.COMMERCIAL) PrimaryTeal else BrandCharcoalCard
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Commercial", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { projectType = ProjectType.RESIDENTIAL },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (projectType == ProjectType.RESIDENTIAL) BrandGold else BrandCharcoalCard,
                            contentColor = if (projectType == ProjectType.RESIDENTIAL) BrandCharcoalDark else TextPrimaryDark
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Residential", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = developerName,
                    onValueChange = { developerName = it },
                    label = { Text("Developer Name *") },
                    placeholder = { Text("e.g. Panchshil Realty, Kasturi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandGold,
                        unfocusedBorderColor = BrandCharcoalBorder,
                        focusedLabelColor = BrandGold,
                        unfocusedLabelColor = TextSecondaryDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )

                OutlinedTextField(
                    value = microMarket,
                    onValueChange = { microMarket = it },
                    label = { Text("Micro Market / Locality *") },
                    placeholder = { Text("e.g. Kharadi, Viman Nagar, Wakad") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandGold,
                        unfocusedBorderColor = BrandCharcoalBorder,
                        focusedLabelColor = BrandGold,
                        unfocusedLabelColor = TextSecondaryDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category / Asset Subclass *") },
                    placeholder = { Text("e.g. IT Park, Retail Showroom, 3 BHK") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandGold,
                        unfocusedBorderColor = BrandCharcoalBorder,
                        focusedLabelColor = BrandGold,
                        unfocusedLabelColor = TextSecondaryDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )

                OutlinedTextField(
                    value = reraNumber,
                    onValueChange = { reraNumber = it },
                    label = { Text("MahaRERA Registration Number (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandGold,
                        unfocusedBorderColor = BrandCharcoalBorder,
                        focusedLabelColor = BrandGold,
                        unfocusedLabelColor = TextSecondaryDark,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )
            }
        },
        confirmButton = {
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
                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save Project", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondaryDark)
            }
        }
    )

    if (showWarningDialog && similarProjectWarning != null) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            containerColor = BrandCharcoalSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Potential Duplicate Detected", color = TextPrimaryDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "A project named '${similarProjectWarning!!.projectName}' (${similarProjectWarning!!.projectId}) already exists in ${similarProjectWarning!!.microMarket}. Do you still want to register this new project?",
                    color = TextSecondaryDark,
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Proceed & Register", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) {
                    Text("Review Existing", color = BrandGold)
                }
            }
        )
    }
}
