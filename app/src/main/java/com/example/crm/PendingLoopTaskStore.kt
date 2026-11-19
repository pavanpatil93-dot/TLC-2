package com.example.crm

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.OfflineDiskCache
import com.example.data.PropertiesLocalDatabase
import com.example.model.BrokerVendorRecord
import com.example.model.PropertyItem
import com.example.model.TenantIssueCategory
import com.example.model.TenantIssueRecord
import com.example.ui.screens.CrmLead
import com.example.ui.screens.WhatsAppBrandDarkGreen
import com.example.ui.screens.WhatsAppBrandGreen
import com.example.ui.theme.BorderGrey
import com.example.ui.theme.PrimaryActionOrange
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SelectedSurfaceContainer
import com.example.ui.theme.StatusSuccessGreen
import com.example.ui.theme.SurfaceTint
import com.example.ui.theme.SurfaceWhite
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

enum class PendingLoopTriggerType(val badgeLabel: String) {
    SITE_VISIT_DONE("Visit Done"),
    TENANT_ISSUE_OWNER("Owner Alert"),
    TENANT_ISSUE_VENDOR("Vendor Task"),
    BROKER_HANDOVER_UPDATE("Broker Update"),
    HANDOVER_CONFIRM_OWNER("Handover Confirm");

    companion object {
        fun fromString(raw: String?): PendingLoopTriggerType =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: SITE_VISIT_DONE
    }
}

data class PendingLoopTask(
    val id: String = "LOOP-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}",
    val triggerType: PendingLoopTriggerType,
    val title: String,
    val subtitle: String = "",
    val targetName: String = "",
    val targetPhone: String = "",
    val prefilledMessage: String = "",
    val entityId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    val completedAt: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("triggerType", triggerType.name)
        put("title", title)
        put("subtitle", subtitle)
        put("targetName", targetName)
        put("targetPhone", targetPhone)
        put("prefilledMessage", prefilledMessage)
        put("entityId", entityId)
        put("createdAt", createdAt)
        put("isCompleted", isCompleted)
        if (completedAt != null) put("completedAt", completedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): PendingLoopTask {
            val compAt = if (obj.has("completedAt") && !obj.isNull("completedAt")) {
                obj.optLong("completedAt").takeIf { it > 0L }
            } else null
            return PendingLoopTask(
                id = obj.optString("id", "LOOP-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}"),
                triggerType = PendingLoopTriggerType.fromString(obj.optString("triggerType", "")),
                title = obj.optString("title", ""),
                subtitle = obj.optString("subtitle", ""),
                targetName = obj.optString("targetName", ""),
                targetPhone = obj.optString("targetPhone", ""),
                prefilledMessage = obj.optString("prefilledMessage", ""),
                entityId = obj.optString("entityId", ""),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                isCompleted = obj.optBoolean("isCompleted", false),
                completedAt = compAt
            )
        }
    }
}

/**
 * Local-first AtomicFile store for Batch 22 Auto-Generated "Pending Loop Tasks"
 * in context.filesDir/pending_loop_tasks.json. Zero blocking on UI thread.
 */
object PendingLoopTaskStore {
    private const val FILE_NAME = "pending_loop_tasks.json"
    private const val MAX_TASKS = 150
    private const val COMPLETED_TTL_MS = 14L * 24L * 60L * 60L * 1000L

    private val lock = Any()

    @Volatile
    private var loadedOnce = false

    private val _tasksFlow = MutableStateFlow<List<PendingLoopTask>>(emptyList())
    val tasksFlow: StateFlow<List<PendingLoopTask>> = _tasksFlow.asStateFlow()

    private fun readAtomic(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            String(AtomicFile(file).readFully(), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun writeAtomic(context: Context, content: String) {
        val file = File(context.filesDir, FILE_NAME)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(StandardCharsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (_: Exception) {
            atomic.failWrite(stream)
        }
    }

    private fun loadInternal(context: Context): List<PendingLoopTask> {
        val raw = readAtomic(context)
        if (raw.isNullOrBlank()) {
            loadedOnce = true
            _tasksFlow.value = emptyList()
            return emptyList()
        }
        val now = System.currentTimeMillis()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { PendingLoopTask.fromJson(it) }
            }
        }.getOrDefault(emptyList())

        val pruned = parsed.filter { task ->
            if (!task.isCompleted) true
            else {
                val cAt = task.completedAt ?: task.createdAt
                (now - cAt) <= COMPLETED_TTL_MS
            }
        }.sortedByDescending { it.createdAt }

        if (pruned.size != parsed.size) {
            saveInternal(context, pruned)
        } else {
            loadedOnce = true
            _tasksFlow.value = pruned
        }
        return pruned
    }

    private fun saveInternal(context: Context, list: List<PendingLoopTask>) {
        val trimmed = list
            .sortedByDescending { it.createdAt }
            .take(MAX_TASKS)
        val arr = JSONArray().apply {
            trimmed.forEach { put(it.toJson()) }
        }
        writeAtomic(context, arr.toString())
        loadedOnce = true
        _tasksFlow.value = trimmed
    }

    fun getAll(context: Context): List<PendingLoopTask> = synchronized(lock) {
        if (!loadedOnce) loadInternal(context) else _tasksFlow.value
    }

    fun refresh(context: Context): List<PendingLoopTask> = synchronized(lock) {
        loadInternal(context)
    }

    fun upsertTask(context: Context, task: PendingLoopTask): List<PendingLoopTask> = synchronized(lock) {
        val current = loadInternal(context).toMutableList()
        val existingIdx = current.indexOfFirst { it.id == task.id }
        if (existingIdx >= 0) {
            val existing = current[existingIdx]
            // Don't reopen an already completed task with the exact same deterministic event ID
            if (existing.isCompleted) return@synchronized current
            current[existingIdx] = task
        } else {
            current.add(0, task)
        }
        saveInternal(context, current)
        current
    }

    fun markCompleted(context: Context, taskId: String): List<PendingLoopTask> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val updated = loadInternal(context).map { task ->
            if (task.id == taskId) task.copy(isCompleted = true, completedAt = now) else task
        }
        saveInternal(context, updated)
        updated
    }

    private fun findMatchingProperty(
        context: Context,
        inventory: List<PropertyItem>,
        propertyOrProject: String,
        propertyCode: String = ""
    ): PropertyItem? {
        val source = inventory.ifEmpty {
            runCatching { PropertiesLocalDatabase.loadProperties(context) }.getOrDefault(emptyList())
        }
        val cleanCode = propertyCode.trim()
        val cleanHint = propertyOrProject.trim()
        if (cleanCode.isNotBlank()) {
            source.firstOrNull { it.propertyCode.equals(cleanCode, ignoreCase = true) }?.let { return it }
        }
        if (cleanHint.isNotBlank()) {
            source.firstOrNull {
                it.propertyCode.equals(cleanHint, ignoreCase = true) ||
                    cleanHint.contains(it.propertyCode, ignoreCase = true) && it.propertyCode.isNotBlank()
            }?.let { return it }
            source.firstOrNull {
                it.projectName.isNotBlank() && cleanHint.contains(it.projectName, ignoreCase = true)
            }?.let { return it }
        }
        return null
    }

    /**
     * Trigger 1: Site Visit marked "Done" / "Completed" ->
     * "Update Owner on Visit Feedback" (1-tap pre-filled Owner WA).
     */
    fun enqueueSiteVisitDoneOwnerUpdate(
        context: Context,
        visitId: String,
        propertyOrProject: String,
        feedbackSummary: String,
        clientName: String,
        agentName: String,
        inventory: List<PropertyItem> = emptyList(),
        explicitOwnerName: String = "",
        explicitOwnerPhone: String = ""
    ) {
        val matchedProp = findMatchingProperty(context, inventory, propertyOrProject)
        val unitTitle = listOfNotNull(
            matchedProp?.projectName?.takeIf { it.isNotBlank() } ?: propertyOrProject.takeIf { it.isNotBlank() },
            matchedProp?.unitNumber?.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank { "your property" }

        val ownerName = explicitOwnerName.ifBlank { matchedProp?.ownerName.orEmpty() }.ifBlank { "Sir/Madam" }
        val ownerPhone = CrmCallManager.last10Digits(explicitOwnerPhone.ifBlank { matchedProp?.ownerContact.orEmpty() })
        val safeAgent = agentName.trim().ifBlank { "TLC Team" }
        val cleanFeedback = feedbackSummary.trim().ifBlank { "Site visit completed cleanly." }
        val cleanClient = clientName.trim().takeIf { it.isNotBlank() }?.let { "Client ($it)" } ?: "our client"

        val msg = buildString {
            appendLine("Hi $ownerName,")
            appendLine("Update from *The Leasing Company* regarding *$unitTitle*:")
            appendLine("We completed a site visit with $cleanClient today.")
            appendLine("*Visit Feedback:* $cleanFeedback")
            append("Will keep you posted on the next step.\n- $safeAgent (TLC)")
        }

        upsertTask(
            context = context,
            task = PendingLoopTask(
                id = "loop_visit_${visitId.ifBlank { UUID.randomUUID().toString().take(8) }}",
                triggerType = PendingLoopTriggerType.SITE_VISIT_DONE,
                title = "Update Owner on Visit Feedback",
                subtitle = "$unitTitle • Owner: ${ownerName.ifBlank { "Add Owner No." }}",
                targetName = ownerName,
                targetPhone = ownerPhone,
                prefilledMessage = msg,
                entityId = matchedProp?.id ?: visitId
            )
        )
    }

    /**
     * Trigger 2: Tenant logs Maintenance / Parking / Electricity issue ->
     * Auto-create "Inform Owner about <Issue>" + "Forward to Vendor".
     */
    fun enqueueTenantIssueTasks(
        context: Context,
        tenant: OfflineDiskCache.TenantCacheRecord,
        issue: TenantIssueRecord,
        agentName: String,
        inventory: List<PropertyItem> = emptyList()
    ) {
        val cat = issue.category
        if (cat != TenantIssueCategory.MAINTENANCE &&
            cat != TenantIssueCategory.PARKING &&
            cat != TenantIssueCategory.ELECTRICITY
        ) {
            return
        }
        val safeAgent = agentName.trim().ifBlank { "TLC Team" }
        val unitLabel = listOf(tenant.projectName, tenant.unitNumber.ifBlank { tenant.propertyCode })
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "leased unit" }
        val issueDesc = issue.description.trim().ifBlank { "${cat.label} issue reported" }

        val matchedProp = findMatchingProperty(context, inventory, tenant.projectName, tenant.propertyCode)
        val ownerName = tenant.ownerName.ifBlank { matchedProp?.ownerName.orEmpty() }.ifBlank { "Sir/Madam" }
        val ownerPhone = CrmCallManager.last10Digits(tenant.ownerContact.ifBlank { matchedProp?.ownerContact.orEmpty() })

        val ownerMsg = buildString {
            appendLine("Hi $ownerName,")
            appendLine("Tenant ${tenant.tenantName.ifBlank { "" }} (*$unitLabel*) has logged a *${cat.label}* issue:")
            appendLine("\"$issueDesc\"")
            append("Please advise / approve next steps.\n- $safeAgent (TLC)")
        }

        upsertTask(
            context = context,
            task = PendingLoopTask(
                id = "loop_issue_owner_${issue.id}",
                triggerType = PendingLoopTriggerType.TENANT_ISSUE_OWNER,
                title = "Inform Owner about ${cat.label}",
                subtitle = "$unitLabel • Owner: $ownerName",
                targetName = ownerName,
                targetPhone = ownerPhone,
                prefilledMessage = ownerMsg,
                entityId = tenant.id
            )
        )

        val vendors = OfflineDiskCache.loadBrokersVendors(context)
            .filter { it.roleCategory.contains("Ops", true) || it.roleCategory.contains("Vendor", true) }
        val matchedVendor: BrokerVendorRecord? = when (cat) {
            TenantIssueCategory.ELECTRICITY -> vendors.firstOrNull {
                it.subRole.contains("Electric", true) || it.notes.contains("electric", true)
            }
            TenantIssueCategory.MAINTENANCE -> vendors.firstOrNull {
                it.subRole.contains("Plumb", true) ||
                    it.subRole.contains("Carpent", true) ||
                    it.subRole.contains("Facility", true)
            }
            TenantIssueCategory.PARKING -> vendors.firstOrNull {
                it.subRole.contains("Society", true) ||
                    it.subRole.contains("Facility", true)
            }
            else -> null
        } ?: vendors.firstOrNull()

        val vendorPhone = CrmCallManager.last10Digits(matchedVendor?.phone.orEmpty())
        val vendorName = matchedVendor?.name?.ifBlank { matchedVendor.subRole } ?: "Ops / Vendor"
        val vendorMsg = buildString {
            appendLine("Hi $vendorName,")
            appendLine("Assistance required for *${cat.label}* issue at *$unitLabel* (${tenant.location.ifBlank { "Pune" }}):")
            appendLine("Details: $issueDesc")
            append("Please share availability & estimate.\n- $safeAgent (TLC)")
        }

        upsertTask(
            context = context,
            task = PendingLoopTask(
                id = "loop_issue_vendor_${issue.id}",
                triggerType = PendingLoopTriggerType.TENANT_ISSUE_VENDOR,
                title = "Forward to Vendor",
                subtitle = "$unitLabel • ${matchedVendor?.let { "${it.name} (${it.subRole})" } ?: "Pick Ops / Vendor"}",
                targetName = vendorName,
                targetPhone = vendorPhone,
                prefilledMessage = vendorMsg,
                entityId = tenant.id
            )
        )
    }

    /**
     * Trigger 3A: Broker's Client handed over to Team & colleague logs callback ->
     * "Update Broker <CP Name> on Status".
     */
    fun enqueueBrokerStatusUpdate(
        context: Context,
        handover: TeamHandoverRecord,
        agentName: String = handover.handedOverBy
    ) {
        if (!handover.roleLabel.contains("Broker", true) && !handover.roleLabel.contains("CP", true)) {
            return
        }
        val cpName = handover.callerName.ifBlank { "Partner" }
        val cpPhone = CrmCallManager.last10Digits(handover.targetMobile)
        val colleague = handover.assignedTo.ifBlank { "our team" }
        val safeAgent = agentName.trim().ifBlank { "TLC Team" }
        val ref = handover.propertyOrProject.takeIf { it.isNotBlank() }?.let { " for *$it*" }.orEmpty()

        val msg = buildString {
            appendLine("Hi $cpName,")
            appendLine("Quick update from *The Leasing Company*$ref:")
            appendLine("$colleague has followed up on the co-broking discussion we coordinated.")
            append("Let us know your client feedback / next slot.\n- $safeAgent (TLC)")
        }

        upsertTask(
            context = context,
            task = PendingLoopTask(
                id = "loop_broker_ho_${handover.id}",
                triggerType = PendingLoopTriggerType.BROKER_HANDOVER_UPDATE,
                title = "Update Broker $cpName on Status",
                subtitle = "Callback logged by $colleague",
                targetName = cpName,
                targetPhone = cpPhone,
                prefilledMessage = msg,
                entityId = handover.callId.ifBlank { handover.id }
            )
        )
    }

    /**
     * Trigger 3B: Visit completed on a Broker-sourced Lead ->
     * "Update Broker <CP Name> on Status".
     */
    fun enqueueBrokerStatusUpdateForLeadVisit(
        context: Context,
        lead: CrmLead,
        visitId: String,
        propertyOrProject: String,
        feedbackSummary: String,
        agentName: String
    ) {
        val isBrokerSourced = lead.leadSource.contains("Broker", true) ||
            lead.leadSource.contains("CP", true) ||
            lead.clientType.contains("Broker", true)
        if (!isBrokerSourced) return

        val cpNameFromSource = lead.leadSource.substringAfter(":", "").trim()
        val cpName = cpNameFromSource.ifBlank { lead.name }.ifBlank { "Channel Partner" }
        val brokers = OfflineDiskCache.loadBrokersVendors(context)
        val matchedBroker = brokers.firstOrNull {
            it.roleCategory.contains("Broker", true) &&
                (it.name.equals(cpName, true) || CrmCallManager.last10Digits(it.phone) == CrmCallManager.last10Digits(lead.phone))
        }
        val cpPhone = CrmCallManager.last10Digits(matchedBroker?.phone?.ifBlank { lead.phone } ?: lead.phone)
        val safeAgent = agentName.trim().ifBlank { "TLC Team" }

        val msg = buildString {
            appendLine("Hi $cpName,")
            appendLine("Site visit update for *${propertyOrProject.ifBlank { lead.location }}*:")
            appendLine("*Status:* ${feedbackSummary.ifBlank { "Visit completed" }}")
            append("Let's align on the next steps.\n- $safeAgent (TLC)")
        }

        upsertTask(
            context = context,
            task = PendingLoopTask(
                id = "loop_broker_visit_${visitId.ifBlank { lead.id }}",
                triggerType = PendingLoopTriggerType.BROKER_HANDOVER_UPDATE,
                title = "Update Broker $cpName on Status",
                subtitle = "Visit completed · $propertyOrProject",
                targetName = cpName,
                targetPhone = cpPhone,
                prefilledMessage = msg,
                entityId = lead.id
            )
        )
    }

    /**
     * Trigger 4: Tenant or Deal handed over ->
     * "Confirm Handover with Owner".
     */
    fun enqueueTenantOrDealHandoverConfirmOwner(
        context: Context,
        entityId: String,
        subjectLabel: String,
        ownerName: String,
        ownerPhone: String,
        agentName: String,
        inventory: List<PropertyItem> = emptyList(),
        propertyHint: String = ""
    ) {
        val matchedProp = if (ownerPhone.isBlank() && propertyHint.isNotBlank()) {
            findMatchingProperty(context, inventory, propertyHint, propertyHint)
        } else null
        val resolvedOwnerName = ownerName.ifBlank { matchedProp?.ownerName.orEmpty() }.ifBlank { "Sir/Madam" }
        val resolvedOwnerPhone = CrmCallManager.last10Digits(ownerPhone.ifBlank { matchedProp?.ownerContact.orEmpty() })
        val safeAgent = agentName.trim().ifBlank { "TLC Team" }

        val msg = buildString {
            appendLine("Hi $resolvedOwnerName,")
            appendLine("Confirmation check from *The Leasing Company* regarding *$subjectLabel*:")
            appendLine("Please confirm that the handover & keys/closure formalities are complete from your end.")
            append("- $safeAgent (TLC)")
        }

        upsertTask(
            context = context,
            task = PendingLoopTask(
                id = "loop_handover_${entityId.ifBlank { UUID.randomUUID().toString().take(8) }}",
                triggerType = PendingLoopTriggerType.HANDOVER_CONFIRM_OWNER,
                title = "Confirm Handover with Owner",
                subtitle = "$subjectLabel • Owner: $resolvedOwnerName",
                targetName = resolvedOwnerName,
                targetPhone = resolvedOwnerPhone,
                prefilledMessage = msg,
                entityId = entityId
            )
        )
    }
}

@Composable
fun PendingLoopsQueueCard(
    modifier: Modifier = Modifier,
    testTagPrefix: String = "pending_loops"
) {
    val context = LocalContext.current
    val allTasks by PendingLoopTaskStore.tasksFlow.collectAsState()
    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            PendingLoopTaskStore.refresh(context)
        }
    }

    val openTasks = allTasks.filter { !it.isCompleted }
    if (openTasks.isEmpty()) return

    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.2.dp, PrimaryActionOrange.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(SelectedSurfaceContainer, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Loop,
                            contentDescription = "Pending Loops",
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Pending Loops (${openTasks.size})",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary,
                            modifier = Modifier.testTag("${testTagPrefix}_header_count")
                        )
                        Text(
                            text = "1-tap WhatsApp follow-up closures",
                            fontSize = 11.sp,
                            color = SecondaryText
                        )
                    }
                }
            }

            openTasks.take(6).forEachIndexed { idx, task ->
                if (idx > 0) {
                    HorizontalDivider(color = BorderGrey.copy(alpha = 0.55f))
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${testTagPrefix}_item_$idx")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = task.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = PrimaryActionOrange.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = task.triggerType.badgeLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryActionOrange,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (task.subtitle.isNotBlank()) {
                        Text(
                            text = task.subtitle,
                            fontSize = 11.5.sp,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (task.targetPhone.isNotBlank()) {
                                    CrmCallManager.openWhatsAppChat(context, task.targetPhone, task.prefilledMessage)
                                } else {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, task.prefilledMessage)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(shareIntent, "Send WhatsApp Follow-up"))
                                    }.onFailure {
                                        Toast.makeText(context, "Unable to open WhatsApp share", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                PendingLoopTaskStore.markCompleted(context, task.id)
                                Toast.makeText(context, "Loop completed", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WhatsAppBrandGreen,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1.45f)
                                .heightIn(min = 48.dp)
                                .testTag("${testTagPrefix}_wa_btn_$idx")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                                contentDescription = "Send WhatsApp & Complete",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Send WA & Complete",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                PendingLoopTaskStore.markCompleted(context, task.id)
                                Toast.makeText(context, "Marked Done", Toast.LENGTH_SHORT).show()
                            },
                            border = BorderStroke(1.dp, StatusSuccessGreen),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = SurfaceTint,
                                contentColor = WhatsAppBrandDarkGreen
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(0.75f)
                                .heightIn(min = 48.dp)
                                .testTag("${testTagPrefix}_done_btn_$idx")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Mark Done",
                                tint = WhatsAppBrandDarkGreen,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Done",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
