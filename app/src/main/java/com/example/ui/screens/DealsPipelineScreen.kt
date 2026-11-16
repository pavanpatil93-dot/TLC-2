package com.example.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PropertyItem
import com.example.ui.theme.*

@Composable
fun DealsPipelineScreen(
    inventory: List<PropertyItem>,
    onBack: () -> Unit
) {
    val dealStages = listOf(
        "New Inquiry", "Shortlisted", "Site Visit Scheduled", "Site Visit Done",
        "Negotiation", "Term Sheet Issued", "Token Received", "Agreement Closed Won", "Closed Lost"
    )

    var selectedStage by remember { mutableStateOf("All Stages") }

    val context = androidx.compose.ui.platform.LocalContext.current
    val actualDeals = remember(inventory, selectedStage) {
        readCrmLeads(context)
            .filter { !it.id.startsWith("demo-lead-") }
            .filter { selectedStage == "All Stages" || it.status.equals(selectedStage, ignoreCase = true) }
            .map { lead ->
                Triple(
                    "${lead.name.ifBlank { "Client" }} • ${lead.location.ifBlank { lead.segment }}",
                    lead.status.ifBlank { "New" },
                    lead.budget.ifBlank { "Budget not specified" }
                )
            }
    }
    val closedWonCount = remember(actualDeals) {
        readCrmLeads(context).count { !it.id.startsWith("demo-lead-") && it.status.equals("Closed Won", ignoreCase = true) }
    }
    val activeNegotiationCount = remember(actualDeals) {
        readCrmLeads(context).count { !it.id.startsWith("demo-lead-") && it.status.equals("Negotiation", ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .padding(16.dp)
            .testTag("deals_pipeline_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Deals Pipeline & Stages",
                    color = TextPrimaryDark,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "ACTUAL CRM PIPELINE RECORDS (NO INVENTED REVENUE)",
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

        // Pipeline Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Active Negotiations", color = TextSecondaryDark, fontSize = 11.sp)
                    Text("$activeNegotiationCount Leads", color = AscendGold, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Closed Won", color = TextSecondaryDark, fontSize = 11.sp)
                    Text("$closedWonCount Deals", color = StatusAvailable, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "DEAL MILESTONES",
            color = AscendGold,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(actualDeals) { (dealName, stage, brokerage) ->
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
                                text = dealName,
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = StatusAvailable.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = stage,
                                    color = StatusAvailable,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Brokerage Receivable:",
                                color = TextSecondaryDark,
                                fontSize = 12.sp
                            )
                            Text(
                                text = brokerage,
                                color = AscendGold,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}
