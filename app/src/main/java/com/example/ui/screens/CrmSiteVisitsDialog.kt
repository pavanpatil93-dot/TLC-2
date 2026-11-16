package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.data.UserSettingsManager
import com.example.model.CalculationUtils
import com.example.model.PropertyItem
import com.example.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class CrmVisit(
    val id: String,
    val property: String,
    val date: String,
    val status: String, // "Scheduled", "Completed", "Cancelled"
    val outcome: String = "",
    val feedbackRating: String = "", // "LOVED_IT", "HESITANT", "REJECTED"
    val objectionReason: String = "",
    val alternativePitchPropertyCode: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

internal fun readVisits(context: Context, leadId: String): List<CrmVisit> = runCatching {
    val raw = com.example.data.auditedCrmPreferences(context, "crm_site_visits_v1", Context.MODE_PRIVATE)
        .getString(leadId, "[]") ?: "[]"
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        val v = arr.getJSONObject(i)
        CrmVisit(
            id = v.optString("id", UUID.randomUUID().toString()),
            property = v.optString("property"),
            date = v.optString("date"),
            status = v.optString("status", "Scheduled"),
            outcome = v.optString("outcome"),
            feedbackRating = v.optString("feedbackRating", ""),
            objectionReason = v.optString("objectionReason", ""),
            alternativePitchPropertyCode = v.optString("alternativePitchPropertyCode", ""),
            timestamp = v.optLong("timestamp", System.currentTimeMillis())
        )
    }
}.getOrDefault(emptyList())

internal fun readAllCrmVisits(context: Context): List<Pair<CrmLead, CrmVisit>> {
    val leads = readCrmLeads(context)
    return leads.flatMap { lead ->
        readVisits(context, lead.id).map { visit -> lead to visit }
    }.sortedByDescending { it.second.timestamp }
}

private fun saveVisits(context: Context, leadId: String, visits: List<CrmVisit>) {
    val arr = JSONArray()
    visits.forEach { visit ->
        arr.put(JSONObject().apply {
            put("id", visit.id)
            put("property", visit.property)
            put("date", visit.date)
            put("status", visit.status)
            put("outcome", visit.outcome)
            put("feedbackRating", visit.feedbackRating)
            put("objectionReason", visit.objectionReason)
            put("alternativePitchPropertyCode", visit.alternativePitchPropertyCode)
            put("timestamp", visit.timestamp)
        })
    }
    com.example.data.auditedCrmPreferences(context, "crm_site_visits_v1", Context.MODE_PRIVATE)
        .edit().putString(leadId, arr.toString()).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrmSiteVisitsDialog(
    leadId: String,
    clientName: String,
    inventory: List<PropertyItem> = emptyList(),
    lead: CrmLead? = null,
    onOpenCalculatorForProperty: ((PropertyItem) -> Unit)? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val userProfile = remember { UserSettingsManager(context).userProfile.value }
    var visits by remember(leadId) { mutableStateOf(readVisits(context, leadId)) }

    // Schedule / Edit Visit State
    var editingVisit by remember { mutableStateOf<CrmVisit?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }

    // Feature #5: Instant Post-Visit Feedback State
    var feedbackVisit by remember { mutableStateOf<CrmVisit?>(null) }

    val completedCount = visits.count { it.status == "Completed" }
    val scheduledCount = visits.count { it.status == "Scheduled" }

    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SITE VISIT LOG & TRACKER",
                        color = PrimaryNavy,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = SecondaryText)
                    }
                }
                Text(
                    text = "Client: $clientName • ${lead?.location ?: "East Pune"}",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Metric Chips Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = if (scheduledCount > 0) TealSubtle else BackgroundSoft,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, if (scheduledCount > 0) PrimaryTeal else BorderGrey)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$scheduledCount", fontWeight = FontWeight.Bold, color = PrimaryTeal, fontSize = 16.sp)
                            Text("Scheduled", color = SecondaryText, fontSize = 10.sp)
                        }
                    }

                    Surface(
                        color = if (completedCount > 0) Color(0xFFDCFCE7) else BackgroundSoft,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, if (completedCount > 0) SuccessGreen else BorderGrey)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$completedCount", fontWeight = FontWeight.Bold, color = SuccessGreen, fontSize = 16.sp)
                            Text("Completed", color = SecondaryText, fontSize = 10.sp)
                        }
                    }

                    Surface(
                        color = BackgroundSoft,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, BorderGrey)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${visits.size}", fontWeight = FontWeight.Bold, color = PrimaryNavy, fontSize = 16.sp)
                            Text("Total", color = SecondaryText, fontSize = 10.sp)
                        }
                    }
                }

                // Schedule New Site Visit Button
                Button(
                    onClick = {
                        editingVisit = null
                        showScheduleDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp).testTag("schedule_new_visit_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Schedule New Site Visit", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Visits List
                if (visits.isEmpty()) {
                    Surface(
                        color = BackgroundSoft,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, BorderGrey)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.HomeWork, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No site visits recorded yet", color = PrimaryNavy, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Schedule field visits to track client interest and capture post-visit feedback.", color = SecondaryText, fontSize = 11.sp)
                        }
                    }
                } else {
                    visits.sortedByDescending { it.timestamp }.forEach { visit ->
                        val isCompleted = visit.status == "Completed"
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth().testTag("visit_card_${visit.id}")
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = visit.property,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryNavy,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "Visit Date: ${visit.date}",
                                            color = SecondaryText,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Surface(
                                        color = when (visit.status) {
                                            "Completed" -> Color(0xFFDCFCE7)
                                            "Cancelled" -> Color(0xFFFEE2E2)
                                            else -> TealSubtle
                                        },
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = visit.status.uppercase(),
                                            color = when (visit.status) {
                                                "Completed" -> SuccessGreen
                                                "Cancelled" -> ErrorRed
                                                else -> PrimaryTeal
                                            },
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                // If Completed, display Feedback Rating & Objections
                                if (isCompleted && visit.feedbackRating.isNotBlank()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = when (visit.feedbackRating) {
                                                "LOVED_IT" -> Color(0xFFDCFCE7)
                                                "HESITANT" -> Color(0xFFFEF3C7)
                                                else -> Color(0xFFFEE2E2)
                                            },
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = when (visit.feedbackRating) {
                                                    "LOVED_IT" -> "🟢 LOVED IT (HOT LEAD)"
                                                    "HESITANT" -> "🟡 LIKED BUT HESITANT"
                                                    else -> "🔴 REJECTED"
                                                },
                                                color = when (visit.feedbackRating) {
                                                    "LOVED_IT" -> Color(0xFF15803D)
                                                    "HESITANT" -> Color(0xFFB45309)
                                                    else -> Color(0xFFB91C1C)
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        if (visit.objectionReason.isNotBlank()) {
                                            Text(
                                                text = "• ${visit.objectionReason}",
                                                color = SecondaryText,
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                if (visit.outcome.isNotBlank()) {
                                    Text(
                                        text = "Outcome: ${visit.outcome}",
                                        color = PrimaryNavy,
                                        fontSize = 12.sp
                                    )
                                }

                                HorizontalDivider(color = BorderGrey, thickness = 0.5.dp)

                                // Action Buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!isCompleted) {
                                        // 1-Tap Trigger for Feature #5 Feedback Card
                                        Button(
                                            onClick = { feedbackVisit = visit },
                                            modifier = Modifier.height(34.dp).testTag("log_visit_feedback_button"),
                                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp)
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Mark Completed & Log Feedback", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        TextButton(
                                            onClick = { feedbackVisit = visit },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("Update Feedback", fontSize = 11.sp, color = PrimaryTeal)
                                        }
                                    }

                                    Row {
                                        IconButton(
                                            onClick = {
                                                editingVisit = visit
                                                showScheduleDialog = true
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = SecondaryText, modifier = Modifier.size(16.dp))
                                        }

                                        IconButton(
                                            onClick = {
                                                visits = visits.filter { it.id != visit.id }
                                                saveVisits(context, leadId, visits)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ErrorRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Close", color = PrimaryTeal, fontWeight = FontWeight.Bold)
            }
        }
    )

    // =========================================================================
    // FEATURE #5: 1-Tap Post-Visit Feedback Card with 3 Quick Options
    // =========================================================================
    feedbackVisit?.let { visit ->
        var selectedOption by remember { mutableStateOf(visit.feedbackRating.ifBlank { "LOVED_IT" }) }
        var selectedObjection by remember { mutableStateOf(visit.objectionReason) }
        var selectedRejectionReason by remember { mutableStateOf(visit.objectionReason) }

        // Find 2 alternative matching inventory units from East Pune database
        val alternativeUnits = remember(selectedObjection, inventory) {
            inventory.filter { item ->
                item.propertyCode != visit.property &&
                item.status == com.example.model.AvailabilityStatus.AVAILABLE &&
                (lead == null || item.tab.isCommercial == lead.segment.equals("Commercial", true))
            }.take(2)
        }

        AlertDialog(
            onDismissRequest = { feedbackVisit = null },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AssignmentTurnedIn, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("POST-VISIT FIELD FEEDBACK", color = PrimaryNavy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${visit.property} • $clientName", color = SecondaryText, fontSize = 11.sp)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Capture field feedback instantly to eliminate lost deal momentum and maintain broker accountability.",
                        color = SecondaryText,
                        fontSize = 11.sp
                    )

                    // 3 Quick Options Row
                    Text("CLIENT REACTION / VERDICT", color = PrimaryNavy, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 1. Loved It
                        Surface(
                            onClick = { selectedOption = "LOVED_IT" },
                            modifier = Modifier.weight(1f).testTag("feedback_loved_it_button"),
                            color = if (selectedOption == "LOVED_IT") Color(0xFFDCFCE7) else BackgroundSoft,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedOption == "LOVED_IT") SuccessGreen else BorderGrey)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🟢 Loved It", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (selectedOption == "LOVED_IT") Color(0xFF15803D) else PrimaryNavy)
                                Text("Hot Lead", fontSize = 9.sp, color = SecondaryText)
                            }
                        }

                        // 2. Liked But Hesitant
                        Surface(
                            onClick = { selectedOption = "HESITANT" },
                            modifier = Modifier.weight(1f).testTag("feedback_hesitant_button"),
                            color = if (selectedOption == "HESITANT") Color(0xFFFEF3C7) else BackgroundSoft,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedOption == "HESITANT") WarningAmber else BorderGrey)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🟡 Hesitant", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (selectedOption == "HESITANT") Color(0xFFB45309) else PrimaryNavy)
                                Text("Needs Pivot", fontSize = 9.sp, color = SecondaryText)
                            }
                        }

                        // 3. Rejected
                        Surface(
                            onClick = { selectedOption = "REJECTED" },
                            modifier = Modifier.weight(1f).testTag("feedback_rejected_button"),
                            color = if (selectedOption == "REJECTED") Color(0xFFFEE2E2) else BackgroundSoft,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedOption == "REJECTED") ErrorRed else BorderGrey)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🔴 Rejected", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (selectedOption == "REJECTED") Color(0xFFB91C1C) else PrimaryNavy)
                                Text("Closed Lost", fontSize = 9.sp, color = SecondaryText)
                            }
                        }
                    }

                    // Dynamic Sub-Workflows based on selection
                    when (selectedOption) {
                        "LOVED_IT" -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, SuccessGreen)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("HIGH VELOCITY ACTION:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF15803D))
                                    Text("Client is ready to close! Advance lead stage to 'Negotiation' and draft commercial proposal / deal sheet.", fontSize = 11.sp, color = PrimaryNavy)

                                    if (onOpenCalculatorForProperty != null && inventory.isNotEmpty()) {
                                        val matchedProp = inventory.find { it.propertyCode == visit.property || it.projectName.contains(visit.property, true) }
                                            ?: inventory.firstOrNull()

                                        if (matchedProp != null) {
                                            Button(
                                                onClick = {
                                                    // Sync visit as Completed & Loved It
                                                    val updatedVisit = visit.copy(
                                                        status = "Completed",
                                                        feedbackRating = "LOVED_IT",
                                                        outcome = "Client Loved It! Commercial proposal / token initiated."
                                                    )
                                                    visits = visits.map { if (it.id == updatedVisit.id) updatedVisit else it }
                                                    saveVisits(context, leadId, visits)

                                                    // Auto-sync lead in CRM
                                                    if (lead != null) {
                                                        val updatedLeads = readCrmLeads(context).map {
                                                            if (it.id == lead.id) it.copy(
                                                                status = "Negotiation",
                                                                notes = "Site Visit Loved It: ${visit.property}. Drafting proposal & token sheet."
                                                            ) else it
                                                        }
                                                        saveCrmLeads(context, updatedLeads)
                                                    }

                                                    feedbackVisit = null
                                                    onOpenCalculatorForProperty(matchedProp)
                                                },
                                                modifier = Modifier.fillMaxWidth().height(40.dp).testTag("open_calculator_proposal_button"),
                                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Open Brokerage & Deal Calculator", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        "HESITANT" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SELECT CLIENT OBJECTION:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = PrimaryNavy)
                                val objections = listOf(
                                    "Price too high / budget mismatch",
                                    "Carpet area too small / layout issue",
                                    "Location mismatch / accessibility",
                                    "Parking & amenities shortage",
                                    "Possession timeline delayed"
                                )
                                objections.forEach { obj ->
                                    val isSelected = selectedObjection == obj
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedObjection = obj },
                                        label = { Text(obj, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFEF3C7),
                                            selectedLabelColor = Color(0xFFB45309)
                                        )
                                    )
                                }

                                Spacer(Modifier.height(4.dp))
                                Text("AUTOMATIC ALTERNATIVE SHORTLIST (2 MATCHES):", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = PrimaryTeal)

                                alternativeUnits.forEach { alt ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = BackgroundSoft),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.8.dp, BorderGrey),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(alt.projectName.ifBlank { alt.propertyCode }, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = PrimaryNavy)
                                                Text(alt.propertyCode, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = PrimaryTeal)
                                            }
                                            Text("${alt.location} • ${alt.carpetAreaSqFt.toInt()} sq.ft • ₹${CalculationUtils.formatIndianCurrency(alt.priceOrRent, alt.tab.isRental)}", fontSize = 11.sp, color = SecondaryText)

                                            // 1-Tap Pitch Alternative via WhatsApp
                                            OutlinedButton(
                                                onClick = {
                                                    val agentName = userProfile.username.ifBlank { "TLC Commercial Advisor" }
                                                    val agentPhone = userProfile.mobileNumber.ifBlank { "+91 9766880099" }
                                                    val pitchMsg = buildString {
                                                        appendLine("Hello $clientName,")
                                                        appendLine()
                                                        appendLine("Following our site visit to ${visit.property}, based on your feedback regarding $selectedObjection, here is an ideal alternative unit:")
                                                        appendLine()
                                                        appendLine("🏢 *${alt.projectName.ifBlank { alt.propertyCode }}*")
                                                        appendLine("📍 Location: ${alt.location}")
                                                        appendLine("📐 Area: ${alt.carpetAreaSqFt.toInt()} sq.ft carpet")
                                                        appendLine("💰 ${if (alt.tab.isRental) "Rent" else "Price"}: ₹${CalculationUtils.formatIndianCurrency(alt.priceOrRent, alt.tab.isRental)}")
                                                        appendLine("🔖 Ref Code: ${alt.propertyCode}")
                                                        appendLine()
                                                        appendLine("Let me know if we can schedule a visit to this alternative unit today.")
                                                        appendLine()
                                                        appendLine("Best regards,")
                                                        appendLine(agentName)
                                                        appendLine(agentPhone)
                                                    }
                                                    try {
                                                        val cleanPhone = lead?.phone?.replace(Regex("[^0-9]"), "").orEmpty()
                                                        val uri = if (cleanPhone.length >= 10 && cleanPhone != "0000000000") {
                                                            val intlPhone = if (cleanPhone.length == 10) "91$cleanPhone" else cleanPhone
                                                            Uri.parse("https://api.whatsapp.com/send?phone=$intlPhone&text=" + Uri.encode(pitchMsg))
                                                        } else {
                                                            Uri.parse("https://api.whatsapp.com/send?text=" + Uri.encode(pitchMsg))
                                                        }
                                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "WhatsApp not available", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth().height(32.dp),
                                                shape = RoundedCornerShape(6.dp),
                                                border = androidx.compose.foundation.BorderStroke(0.8.dp, SuccessGreen),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = SuccessGreen),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Pitch Alternative on WhatsApp", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        "REJECTED" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SELECT REJECTION REASON:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = PrimaryNavy)
                                val reasons = listOf(
                                    "Client opted for competitor project",
                                    "Budget curtailed / requirement shelved",
                                    "Management rejected micro-market location",
                                    "Floor plate / commercial Vastu rejected"
                                )
                                reasons.forEach { r ->
                                    val isSelected = selectedRejectionReason == r
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedRejectionReason = r },
                                        label = { Text(r, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFEE2E2),
                                            selectedLabelColor = Color(0xFFB91C1C)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val outcomeText = when (selectedOption) {
                            "LOVED_IT" -> "Client Loved It! Ready for commercial proposal / token."
                            "HESITANT" -> "Hesitant on ${visit.property} due to: $selectedObjection."
                            else -> "Rejected: $selectedRejectionReason"
                        }

                        val updatedVisit = visit.copy(
                            status = "Completed",
                            feedbackRating = selectedOption,
                            objectionReason = if (selectedOption == "HESITANT") selectedObjection else selectedRejectionReason,
                            outcome = outcomeText,
                            timestamp = System.currentTimeMillis()
                        )

                        visits = visits.map { if (it.id == updatedVisit.id) updatedVisit else it }
                        saveVisits(context, leadId, visits)

                        // Automated Sync: Update lead stage and notes in CRM without manual typing
                        if (lead != null) {
                            val newLeadStatus = when (selectedOption) {
                                "LOVED_IT" -> "Negotiation"
                                "HESITANT" -> "Details Shared"
                                else -> "Site Visit Done"
                            }
                            val updatedLeads = readCrmLeads(context).map {
                                if (it.id == lead.id) it.copy(
                                    status = newLeadStatus,
                                    notes = "Site Visit ${visit.date} (${visit.property}): $outcomeText"
                                ) else it
                            }
                            saveCrmLeads(context, updatedLeads)
                        }

                        Toast.makeText(context, "Feedback logged & CRM stage updated!", Toast.LENGTH_SHORT).show()
                        feedbackVisit = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Save & Sync CRM", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { feedbackVisit = null }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }

    // Schedule / Edit Visit Dialog
    if (showScheduleDialog) {
        var propertyInput by remember(editingVisit) { mutableStateOf(editingVisit?.property.orEmpty()) }
        var dateInput by remember(editingVisit) {
            mutableStateOf(editingVisit?.date ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
        }
        var statusInput by remember(editingVisit) { mutableStateOf(editingVisit?.status ?: "Scheduled") }
        var outcomeInput by remember(editingVisit) { mutableStateOf(editingVisit?.outcome.orEmpty()) }

        AlertDialog(
            onDismissRequest = { showScheduleDialog = false },
            title = {
                Text(
                    text = if (editingVisit == null) "Schedule Site Visit" else "Edit Site Visit",
                    color = PrimaryNavy,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = propertyInput,
                        onValueChange = { propertyInput = it },
                        label = { Text("Property / Project Name") },
                        placeholder = { Text("e.g. Eon IT Park, Kharadi") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Quick Pick from Inventory
                    if (inventory.isNotEmpty()) {
                        Text("Quick Pick from Inventory", fontSize = 11.sp, color = SecondaryText, fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(inventory.take(6)) { prop ->
                                FilterChip(
                                    selected = propertyInput == prop.projectName || propertyInput == prop.propertyCode,
                                    onClick = { propertyInput = prop.projectName.ifBlank { prop.propertyCode } },
                                    label = { Text(prop.propertyCode, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = dateInput,
                        onValueChange = { dateInput = it },
                        label = { Text("Visit Date (YYYY-MM-DD)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text("Visit Status", color = PrimaryNavy, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Scheduled", "Completed", "Cancelled").forEach { st ->
                            FilterChip(
                                selected = statusInput == st,
                                onClick = { statusInput = st },
                                label = { Text(st, fontSize = 11.sp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = outcomeInput,
                        onValueChange = { outcomeInput = it },
                        label = { Text("Notes / Next Step (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = propertyInput.isNotBlank() && dateInput.isNotBlank(),
                    onClick = {
                        val record = CrmVisit(
                            id = editingVisit?.id ?: UUID.randomUUID().toString(),
                            property = propertyInput.trim(),
                            date = dateInput.trim(),
                            status = statusInput,
                            outcome = outcomeInput.trim(),
                            feedbackRating = editingVisit?.feedbackRating.orEmpty(),
                            objectionReason = editingVisit?.objectionReason.orEmpty()
                        )
                        visits = if (editingVisit == null) visits + record else visits.map {
                            if (it.id == record.id) record else it
                        }
                        saveVisits(context, leadId, visits)

                        // If marked completed right here, prompt for feedback
                        if (statusInput == "Completed" && editingVisit?.status != "Completed") {
                            feedbackVisit = record
                        }

                        showScheduleDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Save Visit", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showScheduleDialog = false }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }
}

