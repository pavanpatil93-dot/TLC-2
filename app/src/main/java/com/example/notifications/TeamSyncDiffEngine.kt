package com.example.notifications

import android.content.Context
import com.example.data.OfflineDiskCache
import com.example.data.UserSettingsManager
import com.example.inventory.InventoryQuality
import com.example.model.AvailabilityStatus
import com.example.model.PropertyItem
import com.example.ui.screens.CrmLead
import com.example.ui.screens.readCrmLeads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object TeamSyncDiffEngine {

    private data class RawDiffEvent(
        val channel: TeamAlertChannel,
        val title: String,
        val message: String,
        val vocalText: String,
        val actorName: String,
        val targetType: AlertTargetType,
        val targetId: String,
        val timestampMillis: Long = System.currentTimeMillis()
    )

    suspend fun processPostSyncDiff(
        context: Context,
        properties: List<PropertyItem>
    ): List<TeamAlertItem> = withContext(Dispatchers.Default) {
        val appContext = context.applicationContext
        TeamNotificationManager.ensureChannelsCreated(appContext)
        TeamAlertStore.hydrate(appContext)
        TeamAlertStore.clearExpired(appContext)

        val leads = runCatching { readCrmLeads(appContext) }.getOrDefault(emptyList())
        val tenants = runCatching { OfflineDiskCache.loadTenants(appContext) }.getOrDefault(emptyList())
        val callLogs = runCatching { OfflineDiskCache.loadCallLogs(appContext) }.getOrDefault(emptyList())

        val currentUserIdentifiers = UserSettingsManager.readCurrentUserIdentifiers(appContext)
        val previousSnapshot = TeamAlertStore.loadSnapshot(appContext)

        val currentPropsMap = buildMap<String, PropertySnapshotEntry> {
            properties.forEach { prop ->
                val key = propertyStableKey(prop)
                if (key.isNotBlank()) {
                    put(
                        key,
                        PropertySnapshotEntry(
                            id = key,
                            isLive = InventoryQuality.isStrictlyLive(prop),
                            statusName = prop.status.name,
                            updatedBy = prop.updatedBy.trim(),
                            verifiedBy = prop.verifiedBy.orEmpty().trim()
                        )
                    )
                }
            }
        }

        val currentLeadsMap = buildMap<String, LeadSnapshotEntry> {
            leads.forEach { lead ->
                val key = lead.id.trim()
                if (key.isNotBlank()) {
                    put(
                        key,
                        LeadSnapshotEntry(
                            id = key,
                            siteVisitStatus = lead.siteVisitStatus.trim(),
                            handoverSignature = "${lead.assignedTo.trim()}|${lead.status.trim()}",
                            assignedTo = lead.assignedTo.trim(),
                            updatedBy = lead.updatedBy.trim()
                        )
                    )
                }
            }
        }

        val currentTenantsMap = buildMap<String, TenantSnapshotEntry> {
            tenants.forEach { tenant ->
                val key = tenant.id.trim().ifBlank { tenant.propertyCode.trim() }
                if (key.isNotBlank()) {
                    val issueIds = tenant.parsedIssues()
                        .mapIndexed { idx, issue ->
                            issue.id.trim().ifBlank { "${issue.category.name}_${issue.reportedAt}_$idx" }
                        }
                        .toSet()
                    put(key, TenantSnapshotEntry(id = key, issueIds = issueIds))
                }
            }
        }

        val currentCallLogsMap = buildMap<String, CallLogSnapshotEntry> {
            callLogs.forEach { call ->
                val key = call.id.trim()
                if (key.isNotBlank()) {
                    put(
                        key,
                        CallLogSnapshotEntry(
                            id = key,
                            handoverAssignedTo = call.handoverAssignedTo.trim(),
                            handoverStatus = call.handoverStatus.trim()
                        )
                    )
                }
            }
        }

        val alertedFollowUps = previousSnapshot?.alertedFollowUpKeys?.toMutableSet() ?: mutableSetOf()
        val rawEvents = mutableListOf<RawDiffEvent>()
        val nowMillis = System.currentTimeMillis()

        // First-ever baseline sync: persist snapshot without flooding historical alerts,
        // only emitting genuinely due Follow-up reminders.
        if (previousSnapshot != null) {
            // 1. Commercial & Residential Inventory Diffs (New Live Property + Unit Sold/Leased Out)
            for (prop in properties) {
                val key = propertyStableKey(prop)
                if (key.isBlank()) continue
                val prev = previousSnapshot.properties[key]
                val nowLive = InventoryQuality.isStrictlyLive(prop)
                val actor = resolvePropertyActor(prop)
                val isSelfEdit = isCurrentUser(actor, currentUserIdentifiers)
                val project = prop.projectName.trim().ifBlank { prop.propertyCode.ifBlank { "Property" } }
                val unit = prop.unitNumber.trim()
                val loc = prop.location.trim().ifBlank { "Pune" }

                if (nowLive && (prev == null || !prev.isLive)) {
                    if (!isSelfEdit) {
                        val displayActor = shortFirstName(actor).ifBlank { "A teammate" }
                        val unitLabel = if (unit.isNotBlank()) " · Unit $unit" else ""
                        rawEvents.add(
                            RawDiffEvent(
                                channel = TeamAlertChannel.NEW_LIVE_PROPERTY,
                                title = "New Live Property · $project",
                                message = "$displayActor added $project$unitLabel in $loc.",
                                vocalText = "New property added by $displayActor in $loc.",
                                actorName = displayActor,
                                targetType = AlertTargetType.PROPERTY,
                                targetId = key,
                                timestampMillis = nowMillis
                            )
                        )
                    }
                }

                val isClosedNow = prop.status == AvailabilityStatus.SOLD || prop.status == AvailabilityStatus.LEASED
                val wasClosedBefore = prev?.statusName == AvailabilityStatus.SOLD.name ||
                    prev?.statusName == AvailabilityStatus.LEASED.name
                if (isClosedNow && prev != null && !wasClosedBefore) {
                    if (!isSelfEdit) {
                        val closedLabel = if (prop.status == AvailabilityStatus.SOLD) "Sold" else "Leased Out"
                        val displayActor = shortFirstName(actor).ifBlank { "Team" }
                        val unitSpeech = if (unit.isNotBlank()) " $unit" else ""
                        rawEvents.add(
                            RawDiffEvent(
                                channel = TeamAlertChannel.UNIT_CLOSED,
                                title = "Unit $closedLabel · $project",
                                message = "$project$unitSpeech was marked $closedLabel by $displayActor.",
                                vocalText = "Alert: $project$unitSpeech is $closedLabel.",
                                actorName = displayActor,
                                targetType = AlertTargetType.PROPERTY,
                                targetId = key,
                                timestampMillis = nowMillis
                            )
                        )
                    }
                }
            }

            // 2. CRM Leads Diffs (New Lead / Handover + Site Visit Completed)
            for (lead in leads) {
                val key = lead.id.trim()
                if (key.isBlank()) continue
                val prev = previousSnapshot.leads[key]
                val actorRaw = lead.updatedBy.ifBlank { lead.createdBy.ifBlank { lead.assignedTo } }.trim()
                val isSelfEdit = isCurrentUser(actorRaw, currentUserIdentifiers)
                val displayActor = shortFirstName(actorRaw).ifBlank { "A teammate" }
                val clientName = lead.name.trim().ifBlank { lead.phone.ifBlank { "Client" } }

                if (prev == null) {
                    if (!isSelfEdit) {
                        val isHandoverToMe = lead.assignedTo.isNotBlank() &&
                            isCurrentUser(lead.assignedTo, currentUserIdentifiers)
                        val title = if (isHandoverToMe) {
                            "Team Handover · $clientName"
                        } else {
                            "New Lead · $clientName"
                        }
                        val msg = if (isHandoverToMe) {
                            "New handover from $displayActor. Call $clientName (${lead.location.ifBlank { lead.segment }})."
                        } else {
                            "$displayActor added lead $clientName (${lead.requirement.ifBlank { lead.segment }})."
                        }
                        val vocal = if (isHandoverToMe) {
                            "New handover from $displayActor. Call $clientName."
                        } else {
                            "New lead added by $displayActor."
                        }
                        rawEvents.add(
                            RawDiffEvent(
                                channel = TeamAlertChannel.NEW_LEAD_HANDOVER,
                                title = title,
                                message = msg,
                                vocalText = vocal,
                                actorName = displayActor,
                                targetType = AlertTargetType.LEAD,
                                targetId = key,
                                timestampMillis = nowMillis
                            )
                        )
                    }
                } else {
                    // Handover reassignment to someone (especially current user) by a teammate
                    val reassigned = lead.assignedTo.isNotBlank() &&
                        !lead.assignedTo.equals(prev.assignedTo, ignoreCase = true)
                    if (reassigned && !isSelfEdit) {
                        rawEvents.add(
                            RawDiffEvent(
                                channel = TeamAlertChannel.NEW_LEAD_HANDOVER,
                                title = "Lead Handover · $clientName",
                                message = "New handover from $displayActor to ${shortFirstName(lead.assignedTo)}. Call $clientName.",
                                vocalText = "New handover from $displayActor. Call $clientName.",
                                actorName = displayActor,
                                targetType = AlertTargetType.LEAD,
                                targetId = key,
                                timestampMillis = nowMillis
                            )
                        )
                    }

                    // Site Visit Completed transition
                    val nowVisitDone = isSiteVisitCompleted(lead.siteVisitStatus)
                    val prevVisitDone = isSiteVisitCompleted(prev.siteVisitStatus)
                    if (nowVisitDone && !prevVisitDone && !isSelfEdit) {
                        val project = resolveLeadProjectLabel(lead, properties)
                        rawEvents.add(
                            RawDiffEvent(
                                channel = TeamAlertChannel.SITE_VISIT_COMPLETED,
                                title = "Site Visit Completed · $project",
                                message = "$displayActor completed a site visit with $clientName at $project.",
                                vocalText = "Site visit done by $displayActor at $project.",
                                actorName = displayActor,
                                targetType = AlertTargetType.LEAD,
                                targetId = key,
                                timestampMillis = nowMillis
                            )
                        )
                    }
                }
            }

            // 3. Call Logs Diffs (Team Handover logged on Call Logs)
            for (call in callLogs) {
                val key = call.id.trim()
                if (key.isBlank()) continue
                val prev = previousSnapshot.callLogs[key]
                val hasHandoverNow = call.handoverAssignedTo.isNotBlank() &&
                    !call.handoverStatus.equals("None", ignoreCase = true)
                val hadHandoverBefore = prev != null &&
                    prev.handoverAssignedTo.equals(call.handoverAssignedTo, ignoreCase = true) &&
                    prev.handoverAssignedTo.isNotBlank()

                if (hasHandoverNow && !hadHandoverBefore) {
                    val actorRaw = call.assignedAgent.trim()
                    if (!isCurrentUser(actorRaw, currentUserIdentifiers)) {
                        val displayActor = shortFirstName(actorRaw).ifBlank { "A teammate" }
                        val clientName = call.callerName.trim().ifBlank { call.phoneNumber.ifBlank { "Client" } }
                        val targetLeadId = call.linkedOpportunityId?.trim().orEmpty()
                            .ifBlank {
                                leads.firstOrNull {
                                    it.phone.filter(Char::isDigit).takeLast(10) ==
                                        call.phoneNumber.filter(Char::isDigit).takeLast(10)
                                }?.id.orEmpty()
                            }
                        rawEvents.add(
                            RawDiffEvent(
                                channel = TeamAlertChannel.NEW_LEAD_HANDOVER,
                                title = "Call Handover · $clientName",
                                message = "New handover from $displayActor to ${shortFirstName(call.handoverAssignedTo)}. Call $clientName.",
                                vocalText = "New handover from $displayActor. Call $clientName.",
                                actorName = displayActor,
                                targetType = if (targetLeadId.isNotBlank()) AlertTargetType.LEAD else AlertTargetType.NONE,
                                targetId = targetLeadId,
                                timestampMillis = nowMillis
                            )
                        )
                    }
                }
            }

            // 4. Tenant Issue Logged Diffs
            for (tenant in tenants) {
                val tenantKey = tenant.id.trim().ifBlank { tenant.propertyCode.trim() }
                if (tenantKey.isBlank()) continue
                val prevIssueIds = previousSnapshot.tenants[tenantKey]?.issueIds ?: emptySet()
                val issues = tenant.parsedIssues()
                issues.forEachIndexed { idx, issue ->
                    val issueKey = issue.id.trim().ifBlank { "${issue.category.name}_${issue.reportedAt}_$idx" }
                    if (issueKey !in prevIssueIds) {
                        val actorRaw = issue.reportedBy.ifBlank { tenant.assignedTo }.trim()
                        if (!isCurrentUser(actorRaw, currentUserIdentifiers)) {
                            val project = tenant.projectName.trim().ifBlank { tenant.propertyCode.ifBlank { "Unit" } }
                            val unit = tenant.unitNumber.trim()
                            val unitSuffix = if (unit.isNotBlank()) " $unit" else ""
                            val categoryLabel = issue.category.label
                            rawEvents.add(
                                RawDiffEvent(
                                    channel = TeamAlertChannel.TENANT_ISSUE,
                                    title = "Tenant Issue · $project$unitSuffix",
                                    message = "$categoryLabel issue logged for ${tenant.tenantName.ifBlank { "tenant" }} at $project$unitSuffix.",
                                    vocalText = "Tenant issue at $project$unitSuffix. $categoryLabel.",
                                    actorName = shortFirstName(actorRaw).ifBlank { "Team" },
                                    targetType = AlertTargetType.TENANT,
                                    targetId = tenant.id.trim().ifBlank { tenantKey },
                                    timestampMillis = nowMillis
                                )
                            )
                        }
                    }
                }
            }
        }

        // 5. Follow-up Reminders Due (evaluated on every sync, deduplicated per lead per IST day)
        val todayIstKey = formatIstDateKey(nowMillis)
        for (lead in leads) {
            if (lead.followUpCompleted) continue
            if (lead.status.equals("Closed", ignoreCase = true) || lead.status.equals("Lost", ignoreCase = true)) continue
            if (lead.nextFollowUp.isBlank()) continue
            val isMineOrUnassigned = lead.assignedTo.isBlank() ||
                currentUserIdentifiers.isEmpty() ||
                isCurrentUser(lead.assignedTo, currentUserIdentifiers)
            if (!isMineOrUnassigned) continue

            if (isFollowUpDueNow(lead.nextFollowUp, lead.nextFollowUpTime, nowMillis)) {
                val dedupeKey = "${lead.id.trim()}|$todayIstKey|${lead.nextFollowUpTime.trim()}"
                if (dedupeKey !in alertedFollowUps) {
                    alertedFollowUps.add(dedupeKey)
                    val clientName = lead.name.trim().ifBlank { lead.phone.ifBlank { "Client" } }
                    val timeHint = lead.nextFollowUpTime.trim().takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                    rawEvents.add(
                        RawDiffEvent(
                            channel = TeamAlertChannel.FOLLOW_UP_DUE,
                            title = "Follow-up Due · $clientName",
                            message = "Follow-up due$timeHint: Call $clientName (${lead.location.ifBlank { lead.segment }}).",
                            vocalText = "Follow-up due: Call $clientName.",
                            actorName = "",
                            targetType = AlertTargetType.LEAD,
                            targetId = lead.id.trim(),
                            timestampMillis = nowMillis
                        )
                    )
                }
            }
        }

        // Apply Smart Grouping: if >= 3 items of the same channel arrive in one sync, collapse into ONE summary alert.
        val groupedAlerts = buildGroupedAlerts(rawEvents, nowMillis)

        // Persist updated snapshot first so retries cannot duplicate notifications
        TeamAlertStore.saveSnapshot(
            appContext,
            TeamSyncDiffSnapshot(
                initializedAtMillis = previousSnapshot?.initializedAtMillis ?: nowMillis,
                properties = currentPropsMap,
                leads = currentLeadsMap,
                tenants = currentTenantsMap,
                callLogs = currentCallLogsMap,
                alertedFollowUpKeys = alertedFollowUps
            )
        )

        if (groupedAlerts.isNotEmpty()) {
            TeamAlertStore.appendAlerts(appContext, groupedAlerts)
            groupedAlerts.forEach { alert ->
                TeamNotificationManager.postAlertNotification(appContext, alert)
            }
            // Speak up to 2 concise vocal alerts per sync cycle so speech never drags on
            groupedAlerts.take(2).forEach { alert ->
                TeamVocalAlertManager.speakAlert(appContext, alert.vocalText)
            }
        }

        groupedAlerts
    }

    private fun buildGroupedAlerts(events: List<RawDiffEvent>, nowMillis: Long): List<TeamAlertItem> {
        if (events.isEmpty()) return emptyList()
        val result = mutableListOf<TeamAlertItem>()
        val byChannel = events.groupBy { it.channel }
        byChannel.forEach { (channel, channelEvents) ->
            if (channelEvents.size >= 3) {
                val count = channelEvents.size
                val distinctActors = channelEvents.map { it.actorName }.filter { it.isNotBlank() }.distinct()
                val singleActor = distinctActors.singleOrNull()
                val firstTarget = channelEvents.first()
                val (summaryTitle, summaryMsg, summaryVocal) = when (channel) {
                    TeamAlertChannel.NEW_LIVE_PROPERTY -> {
                        val headline = if (singleActor != null) "$singleActor added $count new properties"
                        else "$count new live properties added"
                        Triple(headline, "Tap to review newly published live inventory.", "$headline.")
                    }
                    TeamAlertChannel.NEW_LEAD_HANDOVER -> {
                        val headline = if (singleActor != null) "$singleActor sent $count new leads / handovers"
                        else "$count new leads and handovers arrived"
                        Triple(headline, "Tap to open CRM pipeline and call new clients.", "$headline.")
                    }
                    TeamAlertChannel.SITE_VISIT_COMPLETED -> {
                        val headline = if (singleActor != null) "$singleActor completed $count site visits"
                        else "$count site visits completed by team"
                        Triple(headline, "Tap to review completed site visit updates.", "$headline.")
                    }
                    TeamAlertChannel.UNIT_CLOSED -> {
                        val headline = "$count units marked Sold / Leased Out"
                        Triple(headline, "Tap to review recently closed inventory units.", "Alert: $headline.")
                    }
                    TeamAlertChannel.TENANT_ISSUE -> {
                        val headline = "$count new tenant issues logged"
                        Triple(headline, "Tap to open Tenants & Leases issue tracker.", "$headline.")
                    }
                    TeamAlertChannel.FOLLOW_UP_DUE -> {
                        val headline = "$count client follow-ups due"
                        Triple(headline, "Tap to review your pending CRM follow-up calls.", "You have $count follow-ups due.")
                    }
                }
                result.add(
                    TeamAlertItem(
                        id = "grp_${channel.name}_${nowMillis}_${UUID.randomUUID().toString().take(6)}",
                        channel = channel,
                        title = summaryTitle,
                        message = summaryMsg,
                        vocalText = summaryVocal,
                        actorName = singleActor.orEmpty(),
                        targetType = firstTarget.targetType,
                        targetId = firstTarget.targetId,
                        timestampMillis = nowMillis,
                        isRead = false,
                        groupedCount = count
                    )
                )
            } else {
                channelEvents.forEach { ev ->
                    result.add(
                        TeamAlertItem(
                            id = "alt_${channel.name}_${nowMillis}_${UUID.randomUUID().toString().take(8)}",
                            channel = ev.channel,
                            title = ev.title,
                            message = ev.message,
                            vocalText = ev.vocalText,
                            actorName = ev.actorName,
                            targetType = ev.targetType,
                            targetId = ev.targetId,
                            timestampMillis = ev.timestampMillis,
                            isRead = false,
                            groupedCount = 1
                        )
                    )
                }
            }
        }
        return result
    }

    private fun propertyStableKey(prop: PropertyItem): String =
        prop.id.trim().ifBlank { prop.recordId.trim().ifBlank { prop.propertyCode.trim() } }

    private fun resolvePropertyActor(prop: PropertyItem): String {
        val candidates = listOf(
            prop.updatedBy,
            prop.verifiedBy.orEmpty(),
            prop.unmappedColumns["Updated By"].orEmpty(),
            prop.unmappedColumns["Created By"].orEmpty(),
            prop.unmappedColumns["Assigned To"].orEmpty()
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun normalizeIdentity(raw: String): String =
        raw.substringBefore("(")
            .substringBefore("|")
            .trim()
            .lowercase(Locale.ENGLISH)

    private fun shortFirstName(raw: String): String {
        val cleaned = raw.substringBefore("(").substringBefore("|").trim()
        if (cleaned.isBlank()) return ""
        return cleaned.split(Regex("\\s+")).firstOrNull().orEmpty()
    }

    private fun isCurrentUser(actor: String, currentUserIds: Set<String>): Boolean {
        val normActor = normalizeIdentity(actor)
        if (normActor.isBlank() || currentUserIds.isEmpty()) return false
        if (normActor in currentUserIds) return true
        val actorFirst = normActor.split(Regex("\\s+")).firstOrNull().orEmpty()
        return actorFirst.length >= 3 && actorFirst in currentUserIds
    }

    private fun isSiteVisitCompleted(status: String): Boolean {
        val s = status.trim().lowercase(Locale.ENGLISH)
        return s.contains("complete") || s == "done" || s == "visited" || s == "conducted"
    }

    private fun resolveLeadProjectLabel(lead: CrmLead, properties: List<PropertyItem>): String {
        val firstCode = lead.matchedPropertyCodes
            .split(",", ";", "|")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (firstCode.isNotBlank()) {
            val match = properties.firstOrNull {
                it.propertyCode.equals(firstCode, ignoreCase = true) ||
                    it.id.equals(firstCode, ignoreCase = true)
            }
            if (match != null && match.projectName.isNotBlank()) return match.projectName.trim()
        }
        return lead.location.trim().ifBlank { lead.requirement.trim().ifBlank { "site" } }
    }

    private fun formatIstDateKey(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        return fmt.format(Date(millis))
    }

    private fun isFollowUpDueNow(dateStr: String, timeStr: String, nowMillis: Long): Boolean {
        val parsedDate = parseFlexibleDate(dateStr.trim()) ?: return false
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        val nowCal = Calendar.getInstance(ist).apply { timeInMillis = nowMillis }
        val dueCal = Calendar.getInstance(ist).apply { time = parsedDate }

        val nowDay = nowCal.get(Calendar.YEAR) * 1000 + nowCal.get(Calendar.DAY_OF_YEAR)
        val dueDay = dueCal.get(Calendar.YEAR) * 1000 + dueCal.get(Calendar.DAY_OF_YEAR)
        if (dueDay > nowDay) return false
        if (dueDay < nowDay) return true // Overdue

        // Due today: check optional time string (e.g., "10:30 AM", "14:00")
        if (timeStr.isBlank()) return true
        val parsedTime = parseFlexibleTime(timeStr.trim()) ?: return true
        val nowMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
        return nowMinutes >= parsedTime
    }

    private fun parseFlexibleDate(raw: String): Date? {
        if (raw.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "dd-MM-yyyy",
            "dd MMM yyyy",
            "d MMM yyyy",
            "MM/dd/yyyy"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                }.parse(raw)
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseFlexibleTime(raw: String): Int? {
        val patterns = listOf("hh:mm a", "h:mm a", "HH:mm", "H:mm")
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                }.parse(raw)
            }.getOrNull()
            if (parsed != null) {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply { time = parsed }
                return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            }
        }
        return null
    }
}
