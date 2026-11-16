package com.example.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PropertyItem
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SiteVisitsScreen(
    inventory: List<PropertyItem>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    var selectedTab by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }

    // Aggregate actual persisted visits across CRM leads
    val allVisits = remember(inventory, selectedTab) {
        val explicitVisits = readAllCrmVisits(context).map { (lead, visit) ->
            Triple(
                lead.name.ifBlank { "Unnamed Client" },
                visit.property.ifBlank { lead.location.ifBlank { "Location not specified" } },
                if (visit.status == "Completed") "Completed (${visit.date})"
                else if (visit.date == today) "Today (${visit.date})"
                else if (visit.date.isNotBlank() && visit.date < today) "Overdue (${visit.date})"
                else "Upcoming (${visit.date.ifBlank { "Scheduled" }})"
            )
        }
        val stageFallbackVisits = readCrmLeads(context)
            .filter { it.status in listOf("Site Visit Pending", "Site Visit Done") && readVisits(context, it.id).isEmpty() }
            .map { lead ->
                Triple(
                    lead.name.ifBlank { "Unnamed Client" },
                    "${lead.requirement.ifBlank { lead.segment }} • ${lead.location.ifBlank { "Pune" }}",
                    if (lead.status == "Site Visit Done") "Completed"
                    else if (lead.nextFollowUp == today) "Today (${lead.nextFollowUp})"
                    else if (lead.nextFollowUp.isNotBlank() && lead.nextFollowUp < today) "Overdue (${lead.nextFollowUp})"
                    else "Upcoming (${lead.nextFollowUp.ifBlank { "Date not set" }})"
                )
            }
        (explicitVisits + stageFallbackVisits).filter { (_, _, statusText) ->
            when (selectedTab) {
                "Today" -> statusText.contains("Today", ignoreCase = true)
                "Upcoming" -> statusText.contains("Upcoming", ignoreCase = true)
                "Completed" -> statusText.contains("Completed", ignoreCase = true)
                else -> true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .padding(16.dp)
            .testTag("site_visits_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Site Visits Management",
                    color = TextPrimaryDark,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "SCHEDULED, TODAY, UPCOMING & COMPLETED VISITS",
                    color = AscendGold,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }

            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Tabs: All, Today, Upcoming, Completed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(BrandCharcoalSurface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val tabs = listOf("All", "Today", "Upcoming", "Completed")
            tabs.forEach { tab ->
                val isSelected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AscendGold else Color.Transparent)
                        .clickable { selectedTab = tab }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab,
                        color = if (isSelected) Color.White else TextSecondaryDark,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(allVisits) { (client, location, timeStatus) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = client,
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = when {
                                    timeStatus.contains("Today") -> StatusAvailable.copy(alpha = 0.15f)
                                    timeStatus.contains("Overdue") -> StatusNotAvailable.copy(alpha = 0.15f)
                                    else -> AscendGold.copy(alpha = 0.15f)
                                }
                            ) {
                                Text(
                                    text = timeStatus,
                                    color = when {
                                        timeStatus.contains("Today") -> StatusAvailable
                                        timeStatus.contains("Overdue") -> StatusNotAvailable
                                        else -> AscendGold
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Place, contentDescription = null, tint = AscendGold, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = location,
                                color = TextSecondaryDark,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode("$location Pune"))
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                },
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, tint = AscendGold, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Navigate", fontSize = 11.sp, color = AscendGold)
                            }
                        }
                    }
                }
            }
        }
    }
}
