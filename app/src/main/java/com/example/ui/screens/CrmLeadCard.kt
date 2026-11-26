package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AiPitchTone
import com.example.ai.CrmReplyScenario
import com.example.ai.GeminiProductivityEngine
import com.example.data.SheetsRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

internal fun readLeadCardActivities(context: Context, id: String): List<JSONObject> = runCatching {
    val raw = com.example.data.auditedCrmPreferences(context, "crm_development_v1", Context.MODE_PRIVATE)
        .getString("card_activities_$id", "[]") ?: "[]"
    val a = JSONArray(raw)
    (0 until a.length()).map { a.getJSONObject(it) }
}.getOrDefault(emptyList())

// One atomic preference commit for lead changes and activity. Preserve unknown JSON fields and other leads.
internal fun saveLeadCardAction(
    context: Context,
    original: CrmLead,
    outcome: String?,
    note: String,
    followUp: String,
    now: Long = System.currentTimeMillis(),
    followUpTime: String = original.followUpTime
): Result<CrmLead> = runCatching {
    if (followUp.isNotBlank()) {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(followUp) && format.parse(followUp) != null) {
            "Enter a valid follow-up date: YYYY-MM-DD"
        }
    }
    if (followUpTime.isNotBlank()) {
        require(followUp.isNotBlank() && Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(followUpTime)) {
            "Choose a valid follow-up date and time"
        }
    }
    synchronized(CrmLeadCardWriteLock) {
        val prefs = com.example.data.auditedCrmPreferences(context, "crm_development_v1", Context.MODE_PRIVATE)
        val all = JSONArray(prefs.getString("leads", "[]") ?: "[]")
        val matches = (0 until all.length()).filter { all.getJSONObject(it).optString("id") == original.id }
        require(matches.size == 1) { "Lead missing or duplicated. Reopen CRM before saving." }
        val target = all.getJSONObject(matches.single())
        val snapshot = original.toJson()
        require(
            snapshot.keys().asSequence().filter { it !in setOf("updatedBy", "updatedAt") }.all { key ->
                target.optString(key) == snapshot.optString(key)
            }
        ) {
            "This lead changed. Reopen it before saving; newer data was preserved."
        }
        val key = "card_activities_${original.id}"
        val activities = JSONArray(prefs.getString(key, "[]") ?: "[]")
        (0 until activities.length()).forEach { activities.getJSONObject(it) }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))
        val type = outcome?.let { "Call: $it" } ?: "Follow-up updated"
        val activity = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("type", type)
            .put("date", date)
            .put("notes", note)
            .put("nextFollowUp", followUp)
            .put("followUpTime", followUpTime)
        val nextActivities = JSONArray().put(activity)
        (0 until activities.length()).forEach { nextActivities.put(activities.getJSONObject(it)) }
        val updatedNotes = if (note.isBlank()) {
            original.notes
        } else {
            listOf(original.notes, "$date • $type: $note").filter { it.isNotBlank() }.joinToString("\n")
        }
        target.put("nextFollowUp", followUp).put("followUpTime", followUpTime).put("notes", updatedNotes)
        check(prefs.edit().putString("leads", all.toString()).putString(key, nextActivities.toString()).commit()) {
            "Could not save. Your activity is still open; retry."
        }
        original.copy(nextFollowUp = followUp, notes = updatedNotes, followUpTime = followUpTime)
    }
}

internal fun saveLeadReassignment(
    context: Context,
    original: CrmLead,
    newAssignee: String,
    reason: String,
    reassignedBy: String,
    now: Long = System.currentTimeMillis()
): Result<CrmLead> = runCatching {
    val cleanAssignee = newAssignee.trim()
    require(cleanAssignee.isNotBlank()) { "Please select or enter an employee to assign this lead." }
    synchronized(CrmLeadCardWriteLock) {
        val prefs = com.example.data.auditedCrmPreferences(context, "crm_development_v1", Context.MODE_PRIVATE)
        val all = JSONArray(prefs.getString("leads", "[]") ?: "[]")
        val matches = (0 until all.length()).filter { all.getJSONObject(it).optString("id") == original.id }
        require(matches.size == 1) { "Lead missing or duplicated. Reopen CRM before saving." }
        val target = all.getJSONObject(matches.single())
        val key = "card_activities_${original.id}"
        val activities = JSONArray(prefs.getString(key, "[]") ?: "[]")
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))
        val previousAssignee = original.assignedTo.ifBlank { "Unassigned" }
        val auditLine = "Passed On / Reassigned from $previousAssignee to $cleanAssignee by $reassignedBy" +
            (if (reason.isNotBlank()) " — Reason: $reason" else "")
        val activity = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("type", "Lead Reassigned")
            .put("date", date)
            .put("notes", auditLine)
            .put("nextFollowUp", original.nextFollowUp)
            .put("followUpTime", original.followUpTime)
        val nextActivities = JSONArray().put(activity)
        (0 until activities.length()).forEach { nextActivities.put(activities.getJSONObject(it)) }
        val updatedNotes = listOf(original.notes, "$date • $auditLine").filter { it.isNotBlank() }.joinToString("\n")
        target.put("assignedTo", cleanAssignee).put("notes", updatedNotes)
        check(prefs.edit().putString("leads", all.toString()).putString(key, nextActivities.toString()).commit()) {
            "Could not save reassignment. Please retry."
        }
        original.copy(assignedTo = cleanAssignee, notes = updatedNotes)
    }
}

private object CrmLeadCardWriteLock

@Composable
internal fun CrmLeadCard(
    lead: CrmLead,
    onEdit: () -> Unit,
    onProfile: () -> Unit,
    onVisits: () -> Unit,
    onMatch: () -> Unit,
    onUpdated: () -> Unit
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<String?>(null) }
    var showReassignDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showMessageTemplateDialog by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }

    val activities = remember(lead) { readLeadCardActivities(context, lead.id) }
    val lastCall = activities.firstOrNull { it.optString("type").startsWith("Call:") }
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val nowTime = SimpleDateFormat("HH:mm", Locale.US).format(Date())
    val due = lead.nextFollowUp

    val isOverdue = due.isNotBlank() && (due < today || (due == today && lead.followUpTime.isNotBlank() && lead.followUpTime < nowTime))
    val isDueToday = due == today && !isOverdue

    val dueText = when {
        due.isBlank() -> lead.status.ifBlank { "No follow-up scheduled" }
        isOverdue -> "Overdue • $due"
        isDueToday -> "Due Today${if (lead.followUpTime.isNotBlank()) " • ${lead.followUpTime}" else ""}"
        else -> "Follow-up • $due${if (lead.followUpTime.isNotBlank()) " ${lead.followUpTime}" else ""}"
    }

    val number = lead.phone.filter { it.isDigit() }
    val dialable = number.length in 7..15 && !lead.id.startsWith("demo-")

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            feedback = "No suitable app found. Check this contact number."
        }
    }

    val initials = remember(lead.name) {
        val parts = lead.name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        when {
            parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            parts.size == 1 && parts[0].length >= 2 -> parts[0].take(2).uppercase(Locale.US)
            parts.size == 1 -> parts[0].uppercase(Locale.US)
            else -> "CL"
        }
    }

    val isHot = lead.status.contains("Hot", ignoreCase = true) ||
        lead.status.contains("Negotiation", ignoreCase = true) ||
        lead.status.contains("Closing", ignoreCase = true)

    val stageBadgeBg = when {
        isHot -> ErrorRedSubtle
        isOverdue -> ErrorRedSubtle
        isDueToday -> WarningAmberSubtle
        else -> SelectedSurfaceContainer
    }
    val stageBadgeFg = when {
        isHot -> StatusDangerRed
        isOverdue -> StatusDangerRed
        isDueToday -> StatusWarningAmber
        else -> FocusOutlineColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onProfile() }
            .testTag("crm_lead_card_${lead.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, BorderGrey),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── TOP ROW: Avatar + Name & Phone + Stage Pill ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (isHot) Color(0xFFD1FAE5) else Color(0xFFDBEAFE)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            color = if (isHot) Color(0xFF065F46) else Color(0xFF1E40AF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = lead.name.ifBlank { "Unnamed Client" },
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Phone,
                                contentDescription = null,
                                tint = SecondaryText,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = lead.phone.ifBlank { "Number not provided" },
                                color = SecondaryText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Surface(
                    color = stageBadgeBg,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = lead.status.ifBlank { "Active Lead" },
                        color = stageBadgeFg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            // ── REQUIREMENT SUMMARY ROWS (matching Group 3 reference) ──
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Row 1: Property Type | Transaction / Segment
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Apartment,
                        contentDescription = null,
                        tint = SecondaryText,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "${lead.requirement.ifBlank { "Property" }}  |  ${lead.segment.ifBlank { "Commercial" }}",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Row 2: Preferred Location
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = SecondaryText,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = lead.location.ifBlank { "Pune (All Micro-Markets)" },
                        color = SecondaryText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Row 3: Budget | Area Range
                val budgetPart = lead.budget.ifBlank { "Budget Open" }
                val areaPart = lead.carpetAreaRange.takeIf { it.isNotBlank() }?.let {
                    if (it.contains("sq", ignoreCase = true)) it else "$it sq.ft."
                } ?: "Area Open"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CurrencyRupee,
                        contentDescription = null,
                        tint = SecondaryText,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "$budgetPart   |   $areaPart",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Row 4: Follow-up Status & Assigned Employee
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Event,
                            contentDescription = null,
                            tint = when {
                                isOverdue -> StatusDangerRed
                                isDueToday -> StatusWarningAmber
                                else -> FocusOutlineColor
                            },
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = dueText,
                            color = when {
                                isOverdue -> StatusDangerRed
                                isDueToday -> StatusWarningAmber
                                else -> SecondaryText
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = "Assigned: ${lead.assignedTo.ifBlank { "Unassigned" }}",
                        color = SecondaryText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (lastCall != null) {
                    Text(
                        text = "Last call: ${lastCall.optString("type").removePrefix("Call: ")} • ${lastCall.optString("date")}",
                        color = SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

            // ── COMPACT SINGLE-ROW QUICK ACTIONS (Call, WhatsApp, Follow-up, Pass On, More) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Call Icon Button
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (dialable) SuccessGreenSubtle else SurfaceMuted,
                    border = BorderStroke(1.dp, if (dialable) StatusSuccessGreen.copy(alpha = 0.4f) else BorderGrey),
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(enabled = dialable) {
                            open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
                        }
                        .testTag("crm_card_call_${lead.id}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call Client",
                            tint = if (dialable) StatusSuccessGreen else SecondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 2. WhatsApp Icon Button
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (dialable) SuccessGreenSubtle else SurfaceMuted,
                    border = BorderStroke(1.dp, if (dialable) StatusSuccessGreen.copy(alpha = 0.4f) else BorderGrey),
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(enabled = dialable) {
                            val international = if (number.length == 10) "91$number" else number
                            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$international")))
                        }
                        .testTag("crm_card_whatsapp_${lead.id}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = "WhatsApp Client",
                            tint = if (dialable) StatusSuccessGreen else SecondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 3. Message Template Button
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFEFF6FF),
                    border = BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.4f)),
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { showMessageTemplateDialog = true }
                        .testTag("crm_card_message_${lead.id}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Message,
                            contentDescription = "Message Template",
                            tint = Color(0xFF1D4ED8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 4. Follow-up Button
                OutlinedButton(
                    onClick = { action = "follow" },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("crm_card_followup_${lead.id}"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF2563EB)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFFEFF6FF),
                        contentColor = Color(0xFF1D4ED8)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Event,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Follow-up",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // 4. More Actions Menu (Log Call, Pass On / Reassign, Tag, Match Properties, Visits, Edit)
                Box {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SurfaceMuted,
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { menu = true }
                            .testTag("crm_card_more_${lead.id}")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "More CRM Actions",
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Log Call / Activity") },
                            leadingIcon = { Icon(Icons.Outlined.PhoneCallback, contentDescription = null) },
                            onClick = {
                                menu = false
                                action = "log"
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Pass On / Reassign Lead") },
                            leadingIcon = { Icon(Icons.Outlined.PersonAddAlt, contentDescription = null) },
                            onClick = {
                                menu = false
                                showReassignDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Tag Client") },
                            leadingIcon = { Icon(Icons.Outlined.Label, contentDescription = null) },
                            onClick = {
                                menu = false
                                showTagDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Find Matching Properties") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            onClick = {
                                menu = false
                                onMatch()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Site Visits") },
                            leadingIcon = { Icon(Icons.Outlined.Place, contentDescription = null) },
                            onClick = {
                                menu = false
                                onVisits()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Client Details & Timeline") },
                            leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                            onClick = {
                                menu = false
                                onProfile()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Lead") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menu = false
                                onEdit()
                            }
                        )
                    }
                }
            }

            if (feedback.isNotBlank()) {
                Text(
                    text = feedback,
                    color = Color(0xFFDC2626),
                    fontSize = 11.sp
                )
            }
        }
    }

    action?.let { mode ->
        CrmLeadActionDialog(
            lead = lead,
            isCall = mode == "log",
            onDismiss = { action = null },
            onSaved = {
                action = null
                onUpdated()
            }
        )
    }

    if (showReassignDialog) {
        CrmLeadReassignDialog(
            lead = lead,
            onDismiss = { showReassignDialog = false },
            onSaved = {
                showReassignDialog = false
                onUpdated()
            }
        )
    }

    if (showTagDialog) {
        CrmLeadTagDialog(
            lead = lead,
            onDismiss = { showTagDialog = false },
            onSaved = {
                showTagDialog = false
                onUpdated()
            }
        )
    }

    if (showMessageTemplateDialog) {
        CrmMessageTemplateDialog(
            lead = lead,
            onDismiss = { showMessageTemplateDialog = false }
        )
    }
}

@Composable
internal fun CrmMessageTemplateDialog(
    lead: CrmLead,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val latestActivityNote = remember(lead.id) {
        readLeadCardActivities(context, lead.id)
            .firstOrNull()
            ?.optString("notes")
            .orEmpty()
            .ifBlank { lead.notes.ifBlank { lead.clientNotes } }
    }

    var selectedScenario by remember {
        mutableStateOf(
            when {
                lead.status.contains("Visit Done", ignoreCase = true) -> CrmReplyScenario.POST_VISIT_FEEDBACK
                lead.status.contains("Visit", ignoreCase = true) || lead.siteVisitDate.isNotBlank() -> CrmReplyScenario.SITE_VISIT_CONFIRMATION
                else -> CrmReplyScenario.POST_CALL_FOLLOW_UP
            }
        )
    }
    var selectedTone by remember { mutableStateOf(AiPitchTone.EXECUTIVE) }
    var isDrafting by remember { mutableStateOf(false) }
    var statusNote by remember { mutableStateOf<String?>(null) }
    var messageText by remember {
        mutableStateOf(
            GeminiProductivityEngine.buildDeterministicCrmReply(
                cleanName = lead.name.ifBlank { "Sir/Ma'am" },
                requirement = lead.requirement.ifBlank { "property requirement" },
                location = lead.location.ifBlank { "Pune" },
                siteVisitDate = lead.siteVisitDate,
                scenario = selectedScenario,
                tone = selectedTone,
                employeeName = lead.assignedTo.ifBlank { "The Leasing Company" }
            )
        )
    }

    fun triggerAiDraft(scenario: CrmReplyScenario, tone: AiPitchTone) {
        if (isDrafting) return
        isDrafting = true
        statusNote = null
        coroutineScope.launch {
            val res = GeminiProductivityEngine.draftCrmFollowUpMessage(
                leadName = lead.name,
                requirement = lead.requirement,
                location = lead.location,
                budget = lead.budget,
                status = lead.status,
                lastNote = latestActivityNote,
                nextFollowUp = lead.nextFollowUp,
                siteVisitDate = lead.siteVisitDate,
                scenario = scenario,
                tone = tone,
                employeeName = lead.assignedTo.ifBlank { "TLC Advisory" }
            )
            messageText = res.text
            statusNote = res.statusNote
            isDrafting = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BrandGold, modifier = Modifier.size(18.dp))
                Text(
                    text = "AI Reply Drafter • ${lead.name}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Scenario", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SecondaryText)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CrmReplyScenario.entries.forEach { sc ->
                        FilterChip(
                            selected = selectedScenario == sc,
                            onClick = {
                                selectedScenario = sc
                                triggerAiDraft(sc, selectedTone)
                            },
                            label = { Text(sc.label, fontSize = 11.sp, maxLines = 1) },
                            modifier = Modifier.testTag("crm_reply_scenario_${sc.name.lowercase(Locale.ENGLISH)}")
                        )
                    }
                }

                Text("Tone", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SecondaryText)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiPitchTone.entries.forEach { tone ->
                        FilterChip(
                            selected = selectedTone == tone,
                            onClick = {
                                selectedTone = tone
                                triggerAiDraft(selectedScenario, tone)
                            },
                            label = { Text(tone.label, fontSize = 11.sp, maxLines = 1) },
                            modifier = Modifier.testTag("crm_reply_tone_${tone.name.lowercase(Locale.ENGLISH)}")
                        )
                    }
                }

                OutlinedButton(
                    onClick = { triggerAiDraft(selectedScenario, selectedTone) },
                    enabled = !isDrafting,
                    border = BorderStroke(1.dp, PrimaryActionOrange),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .testTag("crm_reply_generate_ai_btn")
                ) {
                    if (isDrafting) {
                        CircularProgressIndicator(
                            color = PrimaryActionOrange,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Drafting with Gemini AI...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Draft with Gemini AI", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                    }
                }

                if (!statusNote.isNullOrBlank()) {
                    Text(
                        text = statusNote.orEmpty(),
                        fontSize = 10.5.sp,
                        color = SecondaryText,
                        fontWeight = FontWeight.Medium
                    )
                }

                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Editable WhatsApp Message") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 130.dp)
                        .testTag("crm_reply_message_input"),
                    minLines = 5
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("CRM Follow-up", messageText))
                        Toast.makeText(context, "Reply copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("crm_reply_copy_btn")
                ) {
                    Text("Copy", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        val phone = lead.phone.filter { it.isDigit() }
                        val international = if (phone.length == 10) "91$phone" else phone
                        val encoded = Uri.encode(messageText)
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$international?text=$encoded"))
                        runCatching { context.startActivity(intent) }.onFailure {
                            Toast.makeText(context, "Could not open WhatsApp", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366), contentColor = Color.White),
                    modifier = Modifier.testTag("crm_reply_send_wa_btn")
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Send WA", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = SurfaceWhite
    )
}

@Composable
internal fun CrmAllCallLogsDialog(
    lead: CrmLead,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activities = remember(lead.id) { readLeadCardActivities(context, lead.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call Logs & Activity History • ${lead.name}", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (activities.isEmpty()) {
                    Text("No call logs or activities recorded for this lead yet.", color = SecondaryText, fontSize = 13.sp)
                } else {
                    activities.forEach { act ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SurfaceMuted),
                            border = BorderStroke(1.dp, BorderGrey)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(act.optString("type", "Activity"), fontWeight = FontWeight.Bold, color = PrimaryActionOrange, fontSize = 13.sp)
                                    Text(act.optString("date", ""), fontSize = 11.sp, color = SecondaryText)
                                }
                                Text(act.optString("notes", ""), fontSize = 12.5.sp, color = TextPrimary)
                                val followUp = act.optString("nextFollowUp", "")
                                if (followUp.isNotBlank()) {
                                    Text("Follow-up: $followUp", fontSize = 11.sp, color = StatusSuccessGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", fontWeight = FontWeight.Bold) }
        },
        containerColor = SurfaceWhite
    )
}

@Composable
private fun CrmLeadReassignDialog(
    lead: CrmLead,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val allEmployees = remember(context) {
        com.example.data.SheetsRepository.getSyncedActiveEmployeeNames(context)
    }
    var selectedAssignee by remember { mutableStateOf(lead.assignedTo) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Pass On / Reassign • ${lead.name}",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Current Assignee: ${lead.assignedTo.ifBlank { "Unassigned" }}",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
                Text(
                    text = "Select team member to assign:",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    allEmployees.chunked(2).forEach { rowNames ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowNames.forEach { empName ->
                                FilterChip(
                                    selected = selectedAssignee.equals(empName, ignoreCase = true),
                                    onClick = { selectedAssignee = empName },
                                    label = { Text(empName, fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = selectedAssignee,
                    onValueChange = { selectedAssignee = it },
                    label = { Text("Assigned Employee Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Handover Note / Reason") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Text(
                    text = "Full assignment history and previous notes are preserved in the client timeline.",
                    color = SecondaryText,
                    fontSize = 11.sp
                )
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val actor = "Team Member"
                    saveLeadReassignment(
                        context = context,
                        original = lead,
                        newAssignee = selectedAssignee,
                        reason = reason.trim(),
                        reassignedBy = actor
                    ).onSuccess {
                        onSaved()
                    }.onFailure {
                        error = it.message ?: "Unable to reassign lead."
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor
                )
            ) {
                Text("Confirm Reassign", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = SurfaceWhite,
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun CrmLeadTagDialog(
    lead: CrmLead,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    var selectedTag by remember { mutableStateOf("Priority Follow-up") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val tags = listOf(
        "Priority Follow-up",
        "Site Visit Pending",
        "Details Shared",
        "Shortlisted",
        "Negotiation",
        "Corporate Client",
        "Investor"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Tag Client • ${lead.name}",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        pair.forEach { tag ->
                            FilterChip(
                                selected = selectedTag == tag,
                                onClick = { selectedTag = tag },
                                label = { Text(tag, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Optional tag note") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tagNote = listOf("[Tag: $selectedTag]", note.trim()).filter { it.isNotBlank() }.joinToString(" ")
                    saveLeadCardAction(
                        context = context,
                        original = lead,
                        outcome = null,
                        note = tagNote,
                        followUp = lead.nextFollowUp,
                        followUpTime = lead.followUpTime
                    ).onSuccess {
                        onSaved()
                    }.onFailure {
                        error = it.message ?: "Unable to save tag."
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor
                )
            ) {
                Text("Save Tag", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = SurfaceWhite,
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun CrmLeadActionDialog(
    lead: CrmLead,
    isCall: Boolean,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    var outcome by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var followUp by remember { mutableStateOf(lead.nextFollowUp) }
    var followUpTime by remember { mutableStateOf(lead.followUpTime) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        title = {
            Text(
                text = if (isCall) "Log Call • ${lead.name}" else "Schedule Follow-up • ${lead.name}",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isCall) {
                    Text("Call outcome — choose what actually happened")
                    listOf(
                        listOf("Connected", "No answer"),
                        listOf("Busy", "Switched off"),
                        listOf("Not reachable", "Wrong number")
                    ).forEach { options ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            options.forEach { value ->
                                FilterChip(
                                    selected = outcome == value,
                                    onClick = { outcome = value },
                                    label = { Text(value) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                Text("Follow-up date and time", style = MaterialTheme.typography.titleSmall)
                FollowUpDateTimePicker(followUp, followUpTime, { followUp = it }, { followUpTime = it })
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Notes / next action") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Text(
                    text = "Saved to this device. Notification reminders are not enabled by this form.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isCall || outcome.isNotBlank(),
                onClick = {
                    saveLeadCardAction(
                        context = context,
                        original = lead,
                        outcome = if (isCall) outcome else null,
                        note = note.trim(),
                        followUp = followUp.trim(),
                        followUpTime = if (followUp.isBlank()) "" else followUpTime.trim()
                    ).onSuccess {
                        onSaved()
                    }.onFailure {
                        error = it.message ?: "Unable to save; original records retained."
                    }
                }
            ) {
                Text(if (isCall) "Save call" else "Save Follow-up", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
