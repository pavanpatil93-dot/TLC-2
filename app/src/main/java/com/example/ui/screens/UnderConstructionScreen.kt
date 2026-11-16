package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.projects.ProjectDirectoryStore
import com.example.model.CalculationUtils
import com.example.model.UnderConstProject
import com.example.ui.theme.*

@Composable
fun UnderConstructionScreen(
    projects: List<UnderConstProject>,
    isInternalMode: Boolean,
    onProjectClick: (UnderConstProject) -> Unit,
    onShareWhatsApp: (UnderConstProject) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
    ) {
        // Screen Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Under Construction Projects",
                        color = TextPrimaryDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "1 PROJECT = 1 CARD • MULTI-CONFIGURATION SUITES",
                        color = BrandGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(UnderConstTeal.copy(alpha = 0.15f))
                        .border(1.dp, UnderConstTeal, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "${projects.size} Projects",
                        color = UnderConstTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(projects, key = { it.id }) { project ->
                UnderConstructionProjectCard(
                    project = project,
                    isInternalMode = isInternalMode,
                    onClick = { onProjectClick(project) },
                    onShareWhatsApp = { onShareWhatsApp(project) }
                )
            }
        }
    }
}

@Composable
fun UnderConstructionProjectCard(
    project: UnderConstProject,
    isInternalMode: Boolean,
    onClick: () -> Unit,
    onShareWhatsApp: () -> Unit
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }

    val directoryRecord = remember(context, project.projectCode, project.projectName) {
        runCatching {
            ProjectDirectoryStore.read(context).firstOrNull { dir ->
                (project.projectCode.isNotBlank() && dir.id.equals(project.projectCode, ignoreCase = true)) ||
                    dir.name.equals(project.projectName, ignoreCase = true)
            }
        }.getOrNull()
    }

    val coverImageUrl = remember(project.photoGallery, directoryRecord) {
        project.photoGallery.firstOrNull { it.isNotBlank() }
            ?: directoryRecord?.raw?.optString("coverImageUrl")?.takeIf { it.isNotBlank() }
            ?: directoryRecord?.raw?.optString("imageUrl")?.takeIf { it.isNotBlank() }
            ?: ""
    }

    val priceFromDisplay = remember(project.startingPrice, directoryRecord) {
        val rawPriceFrom = directoryRecord?.raw?.optString("priceFrom").orEmpty()
        when {
            project.startingPrice > 0.0 -> CalculationUtils.formatIndianCurrency(project.startingPrice)
            rawPriceFrom.isNotBlank() -> CalculationUtils.formatSmartIndianAmount(rawPriceFrom, isMonthly = false)
            else -> ""
        }
    }

    val rentFromDisplay = remember(directoryRecord) {
        val rawRentFrom = directoryRecord?.raw?.optString("rentFrom").orEmpty()
        if (rawRentFrom.isNotBlank()) {
            CalculationUtils.formatSmartIndianAmount(rawRentFrom, isMonthly = true)
        } else ""
    }

    val areaRangeDisplay = remember(project.configurations, directoryRecord) {
        val rawArea = directoryRecord?.raw?.optString("publishedAreaRange").orEmpty()
        if (rawArea.isNotBlank()) {
            rawArea
        } else {
            val areas = project.configurations.map { it.carpetAreaSqFt }.filter { it > 0.0 }
            if (areas.isNotEmpty()) {
                val minA = areas.minOrNull() ?: 0.0
                val maxA = areas.maxOrNull() ?: 0.0
                if (minA == maxA) "${CalculationUtils.formatNumber(minA)} sq.ft"
                else "${CalculationUtils.formatNumber(minA)}–${CalculationUtils.formatNumber(maxA)} sq.ft"
            } else ""
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("uc_project_card_${project.projectCode}"),
        colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            // Compact Top Row: 72.dp Cover Thumbnail (or sleek building icon) + Core Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandCharcoalSurface)
                        .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverImageUrl.isNotBlank()) {
                        AsyncImage(
                            model = coverImageUrl,
                            contentDescription = project.projectName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Apartment,
                            contentDescription = project.projectName,
                            tint = BrandGold,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = project.projectName,
                            color = TextPrimaryDark,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (project.projectCode.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(BrandGold.copy(alpha = 0.16f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = project.projectCode,
                                    color = BrandGold,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text(
                        text = "${project.builderName.ifBlank { "Developer" }} • ${project.location}",
                        color = TextSecondaryDark,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (priceFromDisplay.isNotBlank()) {
                            Text(
                                text = "From $priceFromDisplay",
                                color = BrandGoldLight,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (rentFromDisplay.isNotBlank()) {
                            Text(
                                text = "Rent $rentFromDisplay",
                                color = UnderConstTeal,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (areaRangeDisplay.isNotBlank()) {
                            Text(
                                text = "• $areaRangeDisplay",
                                color = TextSecondaryDark,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (project.mahaReraNumber.isNotBlank()) {
                            Text(
                                text = "RERA: ${project.mahaReraNumber}",
                                color = BrandGold,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (project.possessionYear.isNotBlank()) {
                            Text(
                                text = "• Poss: ${project.possessionYear}",
                                color = TextSecondaryDark,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            if (project.configurations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = BrandCharcoalBorder, thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CONFIGURATIONS (${project.configurations.size})",
                        color = BrandGold,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Configurations",
                        tint = BrandGold,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        project.configurations.forEach { cfg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BrandCharcoalSurface)
                                    .border(0.5.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = cfg.configName,
                                        color = TextPrimaryDark,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${CalculationUtils.formatNumber(cfg.carpetAreaSqFt)} sq.ft • ${cfg.possessionDate}",
                                        color = TextSecondaryDark,
                                        fontSize = 10.5.sp
                                    )
                                }
                                Text(
                                    text = CalculationUtils.formatIndianCurrency(cfg.price),
                                    color = BrandGold,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Compact 1-Tap Action Row: View Units + Maps 📍 + Share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onClick,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .testTag("uc_view_units_btn_${project.projectCode}"),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Apartment, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("View Units", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        runCatching {
                            val mapUri = if (project.lat != 0.0 && project.lng != 0.0) {
                                Uri.parse("geo:${project.lat},${project.lng}?q=${project.lat},${project.lng}(${Uri.encode(project.projectName)})")
                            } else {
                                Uri.parse("geo:0,0?q=${Uri.encode("${project.projectName}, ${project.location}, Pune")}")
                            }
                            val mapIntent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(mapIntent)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .testTag("uc_maps_btn_${project.projectCode}"),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark),
                    border = BorderStroke(1.dp, BrandCharcoalBorder)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = BrandGold, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Maps 📍", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = onShareWhatsApp,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BrandCharcoalSurface)
                        .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Share Project",
                        tint = BrandGold,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (isInternalMode && (!project.builderContactPerson.isNullOrBlank() || !project.builderPhone.isNullOrBlank())) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF151B22))
                        .border(0.8.dp, BrandGold.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(6.dp)
                ) {
                    Text(
                        text = "POC: ${project.builderContactPerson ?: "N/A"} (${project.builderPhone ?: "N/A"})",
                        color = BrandGold,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

