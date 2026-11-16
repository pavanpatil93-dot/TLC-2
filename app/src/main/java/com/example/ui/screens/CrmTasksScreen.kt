package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crm.*
import com.example.data.UserSettingsManager
import com.example.ui.theme.*
import java.time.LocalDate
import java.time.ZoneId

enum class CrmTaskOutcomeOption(val label: String) {
    CONNECTED("Connected"),
    NO_ANSWER("No Answer"),
    BUSY("Busy"),
    WRONG_NUMBER("Wrong Number"),
    DETAILS_SHARED("Details Shared"),
    VISIT_COMPLETED("Visit Completed"),
    NOT_INTERESTED("Not Interested"),
    OTHER("Other")
}

data class UnifiedDailyTaskProjection(
    val projectionId: String,
    val taskId: String?,
    val leadId: String?,
    val clientName: String,
    val clientPhone: String,
    val businessDivision: String,
    val assignedEmployeeName: String,
    val taskTypeLabel: String,
    val title: String,
    val nextActionNote: String,
    val dueDate: String,
    val dueTime: String,
    val status: CrmTaskStatus,
    val isOverdue: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrmTasksScreen(
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val currentProfile = remember { UserSettingsManager(context).userProfile.value }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()) }
    var selectedTab by remember { mutableStateOf("Today") }
    var showTeamTasks by remember { mutableStateOf(true) }
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var completingItem by remember { mutableStateOf<UnifiedDailyTaskProjection?>(null) }
    var reschedulingItem by remember { mutableStateOf<UnifiedDailyTaskProjection?>(null) }

    val today = remember { LocalDate.now(ZoneId.of("Asia/Kolkata")).toString() }

    val unifiedTasks = remember(refreshTrigger, today, showTeamTasks) {
        val repoTasks = runCatching { CrmTaskRepository.read(context) }.getOrDefault(emptyList())
        val leads = readCrmLeads(context)
        val leadsById = leads.associateBy { it.id }

        val fromRepo = repoTasks.map { t ->
            val linkedLead = leadsById[t.originId]
            UnifiedDailyTaskProjection(
                projectionId = "task_${t.id}",
                taskId = t.id,
                leadId = linkedLead?.id,
                clientName = linkedLead?.name ?: t.title,
                clientPhone = linkedLead?.phone.orEmpty(),
                businessDivision = linkedLead?.segment ?: "Commercial",
                assignedEmployeeName = t.assignedTo.ifBlank { linkedLead?.assignedTo.orEmpty() },
                taskTypeLabel = t.origin.name,
                title = t.title,
                nextActionNote = t.notes,
                dueDate = t.dueDate,
                dueTime = t.dueTime,
                status = t.status,
                isOverdue = t.isOverdue(today)
            )
        }

        val repoLeadIdsWithOpenTask = repoTasks
            .filter { it.status == CrmTaskStatus.OPEN && it.originId.isNotBlank() }
            .map { it.originId }
            .toSet()

        val fromLeads = leads.mapNotNull { lead ->
            if (lead.nextFollowUp.isBlank() || lead.id in repoLeadIdsWithOpenTask) return@mapNotNull null
            val proj = CrmPipelineAndWorkloadEngine.getProjection(context, lead)
            if (proj.workState != CrmWorkState.ACTIVE && !lead.followUpCompleted) return@mapNotNull null
            val st = if (lead.followUpCompleted) CrmTaskStatus.COMPLETED else CrmTaskStatus.OPEN
            UnifiedDailyTaskProjection(
                projectionId = "lead_${lead.id}",
                taskId = null,
                leadId = lead.id,
                clientName = lead.name.ifBlank { "Unnamed Client" },
                clientPhone = lead.phone,
                businessDivision = lead.segment.ifBlank { "Commercial" },
                assignedEmployeeName = lead.assignedTo,
                taskTypeLabel = lead.nextFollowUpAction.ifBlank { "Follow-up" },
                title = "${lead.nextFollowUpAction.ifBlank { "Follow up" }} • ${lead.name}",
                nextActionNote = lead.notes,
                dueDate = lead.nextFollowUp,
                dueTime = lead.nextFollowUpTime.ifBlank { lead.followUpTime },
                status = st,
                isOverdue = st == CrmTaskStatus.OPEN && lead.nextFollowUp < today
            )
        }

        (fromRepo + fromLeads).filter { item ->
            showTeamTasks ||
                item.assignedEmployeeName.isBlank() ||
                item.assignedEmployeeName.equals(currentProfile.username, ignoreCase = true)
        }
    }

    val filtered = remember(unifiedTasks, selectedTab, today) {
        unifiedTasks.filter {
            when (selectedTab) {
                "Overdue" -> it.status == CrmTaskStatus.OPEN && it.isOverdue
                "Today" -> it.status == CrmTaskStatus.OPEN && it.dueDate == today
                "Upcoming" -> it.status == CrmTaskStatus.OPEN && it.dueDate > today
                "Completed" -> it.status == CrmTaskStatus.COMPLETED
                else -> true
            }
        }.sortedWith(
            compareBy<UnifiedDailyTaskProjection> { it.dueDate.ifBlank { "9999-12-31" } }
                .thenBy { it.dueTime }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundSoft)
            .padding(16.dp)
            .testTag("crm_tasks_screen")
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CRM Daily Task Centre",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "FOLLOW-UPS, CALL OUTCOMES & SITE VISITS • ASIA/KOLKATA",
                    color = PrimaryActionOrange,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { showAddTaskDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Task", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Scope Toggle: My Tasks | Team Tasks
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !showTeamTasks,
                onClick = { showTeamTasks = false },
                label = { Text("My Tasks", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) }
            )
            FilterChip(
                selected = showTeamTasks,
                onClick = { showTeamTasks = true },
                label = { Text("Team Tasks", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tabs: Today, Overdue, Upcoming, Completed, All
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceMuted)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val tabs = listOf("Today", "Overdue", "Upcoming", "Completed", "All")
            tabs.forEach { option ->
                val isSelected = selectedTab == option
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) PrimaryActionOrange else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { selectedTab = option }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option,
                        color = if (isSelected) OnPrimaryActionColor else SecondaryText,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        error?.let {
            Text(it, color = StatusNotAvailable, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered, key = { it.projectionId }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    border = BorderStroke(
                        1.dp,
                        if (item.isOverdue && item.status == CrmTaskStatus.OPEN) StatusNotAvailable.copy(alpha = 0.6f) else BorderGrey
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${item.businessDivision} • Assigned to: ${item.assignedEmployeeName.ifBlank { "Unassigned" }}",
                                    color = SecondaryText,
                                    fontSize = 11.sp
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = when (item.status) {
                                    CrmTaskStatus.COMPLETED -> StatusAvailable.copy(alpha = 0.15f)
                                    else -> if (item.isOverdue) StatusNotAvailable.copy(alpha = 0.15f) else SelectedSurfaceContainer
                                }
                            ) {
                                Text(
                                    text = if (item.status == CrmTaskStatus.COMPLETED) "Completed" else if (item.isOverdue) "Overdue" else "Open",
                                    color = when (item.status) {
                                        CrmTaskStatus.COMPLETED -> StatusAvailable
                                        else -> if (item.isOverdue) StatusNotAvailable else PrimaryActionOrange
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        if (item.nextActionNote.isNotBlank()) {
                            Text(
                                text = item.nextActionNote,
                                color = SecondaryText,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Event, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Due: ${item.dueDate.ifBlank { "Not set" }} ${item.dueTime}",
                                color = SecondaryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Type: ${item.taskTypeLabel}",
                                color = SecondaryText,
                                fontSize = 11.sp
                            )
                        }

                        if (item.status == CrmTaskStatus.OPEN) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val digits = item.clientPhone.filter { it.isDigit() }
                                if (digits.length >= 7) {
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
                                            }
                                        },
                                        border = BorderStroke(1.dp, BorderGrey),
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextPrimary)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Call", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { reschedulingItem = item },
                                    border = BorderStroke(1.dp, BorderGrey),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) {
                                    Text("Reschedule", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { completingItem = item },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange,
                                        contentColor = OnPrimaryActionColor
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1.2f).heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Complete", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.TaskAlt, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No tasks found in '$selectedTab'", color = SecondaryText, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // Add Task Dialog
    if (showAddTaskDialog) {
        AlertDialog(
            onDismissRequest = { showAddTaskDialog = false },
            title = { Text("Add Follow-up Task", color = TextPrimary, fontWeight = FontWeight.Bold) },
            containerColor = SurfaceWhite,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Task description / Client follow-up") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = due,
                        onValueChange = { due = it },
                        label = { Text("Due date (YYYY-MM-DD)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            check(
                                CrmTaskRepository.upsert(
                                    context,
                                    CrmTask(
                                        title = title.trim(),
                                        origin = CrmTaskOrigin.MANUAL,
                                        originId = "",
                                        assignedTo = currentProfile.username,
                                        dueDate = due.trim()
                                    )
                                )
                            )
                            refreshTrigger++
                            title = ""
                            due = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
                            error = null
                            showAddTaskDialog = false
                        }.onFailure { error = it.message ?: "Unable to save task" }
                    },
                    enabled = title.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Save Task", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTaskDialog = false }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }

    // Complete Task Outcome Dialog
    completingItem?.let { target ->
        var selectedOutcome by remember { mutableStateOf(CrmTaskOutcomeOption.CONNECTED) }
        var outcomeNote by remember { mutableStateOf("") }
        var createNextFollowUp by remember { mutableStateOf(false) }
        var nextDueDate by remember {
            mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2).toString())
        }
        var dialogError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { completingItem = null },
            containerColor = SurfaceWhite,
            title = {
                Text("Complete Task & Record Outcome", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(target.title, color = SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Select Actual Outcome:", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    CrmTaskOutcomeOption.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { opt ->
                                FilterChip(
                                    selected = selectedOutcome == opt,
                                    onClick = { selectedOutcome = opt },
                                    label = { Text(opt.label, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = outcomeNote,
                        onValueChange = { outcomeNote = it },
                        label = { Text("Outcome Note / Client Feedback") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = createNextFollowUp,
                            onCheckedChange = { createNextFollowUp = it },
                            colors = CheckboxDefaults.colors(checkedColor = PrimaryActionOrange, checkmarkColor = OnPrimaryActionColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Create Next Follow-up", color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                    if (createNextFollowUp) {
                        OutlinedTextField(
                            value = nextDueDate,
                            onValueChange = { nextDueDate = it },
                            label = { Text("Next Follow-up Date (YYYY-MM-DD)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    dialogError?.let {
                        Text(it, color = StatusNotAvailable, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            if (createNextFollowUp) {
                                require(runCatching { LocalDate.parse(nextDueDate.trim()) }.isSuccess) {
                                    "Enter a valid YYYY-MM-DD date for next follow-up."
                                }
                            }
                            if (target.taskId != null) {
                                check(CrmTaskRepository.setStatus(context, target.taskId, CrmTaskStatus.COMPLETED))
                            }
                            if (target.leadId != null) {
                                val leads = readCrmLeads(context)
                                val lead = leads.firstOrNull { it.id == target.leadId }
                                if (lead != null) {
                                    val updatedLead = if (createNextFollowUp) {
                                        lead.copy(nextFollowUp = nextDueDate.trim(), followUpCompleted = false)
                                    } else {
                                        lead.copy(followUpCompleted = true)
                                    }
                                    saveCrmLeads(context, leads.map { if (it.id == lead.id) updatedLead else it })
                                    appendCrmActivity(
                                        context = context,
                                        leadId = lead.id,
                                        type = "Task Completed (${selectedOutcome.label})",
                                        notes = outcomeNote.trim().ifBlank { selectedOutcome.label },
                                        nextFollowUp = if (createNextFollowUp) nextDueDate.trim() else "",
                                        performedBy = currentProfile.username
                                    )
                                }
                            }
                            if (createNextFollowUp && target.taskId != null) {
                                CrmTaskRepository.upsert(
                                    context,
                                    CrmTask(
                                        title = "Follow-up: ${target.clientName}",
                                        origin = CrmTaskOrigin.LEAD,
                                        originId = target.leadId.orEmpty(),
                                        assignedTo = target.assignedEmployeeName,
                                        dueDate = nextDueDate.trim(),
                                        notes = outcomeNote.trim()
                                    )
                                )
                            }
                            refreshTrigger++
                            completingItem = null
                            Toast.makeText(context, "Task marked completed", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            dialogError = err.message ?: "Unable to complete task"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Save Outcome", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { completingItem = null }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }

    // Reschedule Dialog
    reschedulingItem?.let { target ->
        var newDate by remember { mutableStateOf(target.dueDate.ifBlank { today }) }
        var rescheduleReason by remember { mutableStateOf("") }
        var dialogError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { reschedulingItem = null },
            containerColor = SurfaceWhite,
            title = { Text("Reschedule Task", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val todayDate = LocalDate.now(ZoneId.of("Asia/Kolkata"))
                        listOf(
                            "Today" to todayDate.toString(),
                            "Tomorrow" to todayDate.plusDays(1).toString(),
                            "+3 Days" to todayDate.plusDays(3).toString(),
                            "Next Week" to todayDate.plusDays(7).toString()
                        ).forEach { (lbl, dt) ->
                            FilterChip(
                                selected = newDate == dt,
                                onClick = { newDate = dt },
                                label = { Text(lbl, fontSize = 11.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = newDate,
                        onValueChange = { newDate = it },
                        label = { Text("New Due Date (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = rescheduleReason,
                        onValueChange = { rescheduleReason = it },
                        label = { Text("Reason / Note") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    dialogError?.let {
                        Text(it, color = StatusNotAvailable, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            require(runCatching { LocalDate.parse(newDate.trim()) }.isSuccess) {
                                "Enter a valid YYYY-MM-DD date."
                            }
                            if (target.taskId != null) {
                                val existing = CrmTaskRepository.read(context).firstOrNull { it.id == target.taskId }
                                if (existing != null) {
                                    CrmTaskRepository.upsert(context, existing.copy(dueDate = newDate.trim(), notes = rescheduleReason.trim().ifBlank { existing.notes }))
                                }
                            }
                            if (target.leadId != null) {
                                val leads = readCrmLeads(context)
                                val lead = leads.firstOrNull { it.id == target.leadId }
                                if (lead != null) {
                                    saveCrmLeads(context, leads.map { if (it.id == lead.id) lead.copy(nextFollowUp = newDate.trim(), followUpCompleted = false) else it })
                                }
                            }
                            refreshTrigger++
                            reschedulingItem = null
                        }.onFailure { err ->
                            dialogError = err.message ?: "Unable to reschedule"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Save Date", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { reschedulingItem = null }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }
}
