package com.example.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crm.CallType
import com.example.crm.CrmCallManager
import com.example.crm.CrmCallRecord
import com.example.crm.CrmPipelineAndWorkloadEngine
import com.example.crm.PendingLoopsQueueCard
import com.example.crm.TeamHandoverRecord
import com.example.crm.TeamHandoverStore
import com.example.data.OfflineDiskCache
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
private val POA_CUTOFF: LocalTime = LocalTime.of(10, 30)
private val EOD_CUTOFF: LocalTime = LocalTime.of(19, 30)
private val PHONE_SCRUB_REGEX = Regex("""(?:\+?91[\s-]?)?[6-9]\d{9}|\b\d{8,14}\b""")

internal enum class CutoffStatus(val label: String) {
    ON_TIME("On Time"),
    LATE("After Cutoff"),
    PENDING("Pending")
}

internal data class TodayAutoPullMetrics(
    val callsTotal: Int = 0,
    val callsDurationMins: Int = 0,
    val externalCallsTotal: Int = 0,
    val externalCallsDurationMins: Int = 0,
    val internalCallsTotal: Int = 0,
    val internalCallsDurationMins: Int = 0,
    val newLeads: Int = 0,
    val followUpsDone: Int = 0,
    val propertiesAdded: Int = 0,
    val propertiesVerified: Int = 0
)

private enum class TeamPulseRange(val label: String) {
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    CUSTOM("Custom Date")
}

private data class TeamCallPulseRow(
    val employeeName: String,
    val externalCalls: Int,
    val internalCalls: Int,
    val talkTimeMins: Int,
    val missedPending: Int,
    val notesLogged: Int,
    val handoversCalledBack: Int,
    val handoversPending: Int,
    val hasOverdueHandover2h: Boolean
)

private fun scrubContactNumbers(text: String): String =
    PHONE_SCRUB_REGEX.replace(text, "[Protected]").replace(Regex("\\s{2,}"), " ").trim()

private fun currentIstTimeLabel(): String =
    LocalTime.now(IST_ZONE).format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)) + " IST"

private fun todayIstIso(): String =
    LocalDate.now(IST_ZONE).toString()

internal fun parseIstTimeFromTimestamp(raw: String): LocalTime? {
    val clean = raw.trim()
    if (clean.isBlank()) return null
    runCatching {
        return Instant.parse(clean).atZone(IST_ZONE).toLocalTime()
    }
    val upper = clean.uppercase(Locale.ENGLISH).removeSuffix("IST").trim()
    listOf(
        "hh:mm a",
        "h:mm a",
        "HH:mm",
        "H:mm",
        "dd/MM/yyyy hh:mm a",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss"
    ).forEach { pattern ->
        runCatching {
            val fmt = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
            return if (pattern.contains("yyyy")) {
                LocalDateTime.parse(upper, fmt).toLocalTime()
            } else {
                LocalTime.parse(upper, fmt)
            }
        }
    }
    val m = Regex("""(\d{1,2}):(\d{2})\s*(AM|PM)?""", RegexOption.IGNORE_CASE).find(clean)
    if (m != null) {
        val hrRaw = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val ampm = m.groupValues[3].uppercase(Locale.ENGLISH)
        val hr24 = when {
            ampm == "PM" && hrRaw in 1..11 -> hrRaw + 12
            ampm == "AM" && hrRaw == 12 -> 0
            else -> hrRaw
        }.coerceIn(0, 23)
        return LocalTime.of(hr24, min.coerceIn(0, 59))
    }
    return null
}

internal fun evaluateCutoff(submittedAt: String, cutoff: LocalTime): CutoffStatus {
    if (submittedAt.isBlank()) return CutoffStatus.PENDING
    val parsed = parseIstTimeFromTimestamp(submittedAt) ?: return CutoffStatus.ON_TIME
    return if (!parsed.isAfter(cutoff)) CutoffStatus.ON_TIME else CutoffStatus.LATE
}

private fun parsePropertyDateToLocalDate(raw: String): LocalDate? {
    val clean = raw.trim()
    if (clean.isBlank()) return null
    listOf(
        "yyyy-MM-dd",
        "dd/MM/yyyy",
        "dd-MM-yyyy",
        "dd MMM yyyy",
        "dd MMM yyyy, hh:mm a",
        "dd/MM/yyyy HH:mm"
    ).forEach { p ->
        runCatching {
            val fmt = DateTimeFormatter.ofPattern(p, Locale.ENGLISH)
            return if (p.contains("hh") || p.contains("HH")) {
                LocalDateTime.parse(clean.take(20), fmt).toLocalDate()
            } else {
                LocalDate.parse(clean.take(10), fmt)
            }
        }
    }
    val isoMatch = Regex("""(\d{4}-\d{2}-\d{2})""").find(clean)?.groupValues?.get(1)
    if (isoMatch != null) return runCatching { LocalDate.parse(isoMatch) }.getOrNull()
    val slashMatch = Regex("""(\d{2})/(\d{2})/(\d{4})""").find(clean)
    if (slashMatch != null) {
        return runCatching {
            LocalDate.of(
                slashMatch.groupValues[3].toInt(),
                slashMatch.groupValues[2].toInt(),
                slashMatch.groupValues[1].toInt()
            )
        }.getOrNull()
    }
    return null
}

@Composable
fun DailyOpsScreen(
    userProfile: UserProfile,
    properties: List<PropertyItem>,
    dailyReports: List<DailyReportRecord>,
    onSaveDailyReport: suspend (DailyReportRecord) -> Result<Unit>,
    onRefreshSync: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val todayIso = remember { todayIstIso() }
    val cutoff48h = remember(todayIso) { LocalDate.parse(todayIso).minusDays(2) }
    val isAdminOrManager = userProfile.role == UserRole.ADMIN ||
        userProfile.role == UserRole.COMMERCIAL_MANAGER ||
        userProfile.role == UserRole.RESIDENTIAL_MANAGER

    // ALL logged-in roles (including Admin) have full access to Tab 0 ("My POA & EOD")
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showMorningUpdates by rememberSaveable { mutableStateOf(true) }

    // Background disk cache load for CRM leads, calls, and employees (never block main thread)
    var cachedLeads by remember { mutableStateOf<List<CrmLead>>(emptyList()) }
    var cachedCalls by remember { mutableStateOf<List<CrmCallRecord>>(emptyList()) }
    var cachedHandovers by remember { mutableStateOf<List<TeamHandoverRecord>>(emptyList()) }
    var autoMetrics by remember { mutableStateOf(TodayAutoPullMetrics()) }
    var teamDirectoryNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val myName = userProfile.username.ifBlank { "Employee" }
    val myEmpId = userProfile.employeeId.ifBlank {
        "EMP-${myName.uppercase(Locale.ENGLISH).replace(Regex("[^A-Z0-9]"), "").take(8)}"
    }

    LaunchedEffect(refreshTrigger, properties.size, myName) {
        withContext(Dispatchers.IO) {
            val leads = runCatching { readCrmLeads(context) }.getOrDefault(emptyList())
            CrmCallManager.retagInternalTeamCalls(context)
            val calls = runCatching { CrmCallManager.readCalls(context) }.getOrDefault(emptyList())
            val handovers = runCatching { TeamHandoverStore.reconcileWithCallLogs(context, calls) }.getOrDefault(emptyList())
            val todayLocal = LocalDate.now(IST_ZONE)

            val todayCalls = calls.filter { rec ->
                val callDate = Instant.ofEpochMilli(rec.timestamp).atZone(IST_ZONE).toLocalDate()
                val agentMatch = rec.assignedAgent.isBlank() ||
                    rec.assignedAgent.equals(myName, ignoreCase = true) ||
                    myName.equals("Admin", ignoreCase = true)
                callDate == todayLocal && agentMatch
            }
            val effectiveCalls = if (todayCalls.isNotEmpty()) {
                todayCalls
            } else {
                calls.filter { Instant.ofEpochMilli(it.timestamp).atZone(IST_ZONE).toLocalDate() == todayLocal }
            }
            val externalCalls = effectiveCalls.filterNot { CrmCallManager.isInternalTeamCall(context, it) }
            val internalCalls = effectiveCalls.filter { CrmCallManager.isInternalTeamCall(context, it) }
            // Keep counting ALL calls (including <20s) in EOD Calls Total
            val totalMins = (effectiveCalls.sumOf { it.durationSeconds } + 30) / 60
            val externalMins = (externalCalls.sumOf { it.durationSeconds } + 30) / 60
            val internalMins = (internalCalls.sumOf { it.durationSeconds } + 30) / 60

            val todayLeads = leads.count { lead ->
                val d = parsePropertyDateToLocalDate(lead.createdDate.ifBlank { lead.updatedAt })
                val ownerMatch = lead.assignedTo.isBlank() || lead.assignedTo.equals(myName, ignoreCase = true)
                d == todayLocal && ownerMatch
            }
            val todayFollowUps = leads.count { lead ->
                val d = parsePropertyDateToLocalDate(lead.updatedAt)
                val ownerMatch = lead.assignedTo.isBlank() || lead.assignedTo.equals(myName, ignoreCase = true)
                lead.followUpCompleted && d == todayLocal && ownerMatch
            }
            val propsAddedToday = properties.count { prop ->
                val d = parsePropertyDateToLocalDate(prop.date.ifBlank { prop.createdAt })
                val actorMatch = prop.updatedBy.isBlank() || prop.updatedBy.equals(myName, ignoreCase = true)
                d == todayLocal && actorMatch
            }
            val propsVerifiedToday = properties.count { prop ->
                val d = parsePropertyDateToLocalDate(prop.lastVerifiedDateClean.ifBlank { prop.lastVerificationDate })
                val actorMatch = prop.verifiedBy.isNullOrBlank() || prop.verifiedBy.equals(myName, ignoreCase = true)
                prop.verificationStatus == VerificationStatus.VERIFIED && d == todayLocal && actorMatch
            }

            val empFromDisk = runCatching { OfflineDiskCache.loadEmployeeNames(context) }.getOrDefault(emptyList())
            val empFromAccess = runCatching {
                CrmPipelineAndWorkloadEngine.loadEligibleEmployees(context).map { it.employeeName }
            }.getOrDefault(emptyList())

            cachedLeads = leads
            cachedCalls = calls
            cachedHandovers = handovers
            autoMetrics = TodayAutoPullMetrics(
                callsTotal = effectiveCalls.size,
                callsDurationMins = totalMins,
                externalCallsTotal = externalCalls.size,
                externalCallsDurationMins = externalMins,
                internalCallsTotal = internalCalls.size,
                internalCallsDurationMins = internalMins,
                newLeads = todayLeads,
                followUpsDone = todayFollowUps,
                propertiesAdded = propsAddedToday,
                propertiesVerified = propsVerifiedToday
            )
            teamDirectoryNames = (empFromDisk + empFromAccess + listOf(myName))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.ENGLISH) }
        }
    }

    // Recent Morning Updates (last 24-48h): strictly Summary + Who Added It ONLY (Zero phone numbers / owner / broker / client contacts)
    val recentPropertiesSummary = remember(properties, cutoff48h) {
        properties
            .filter { prop ->
                prop.archiveStatus != ArchiveStatus.ARCHIVED &&
                    (parsePropertyDateToLocalDate(prop.date.ifBlank { prop.createdAt })?.let { !it.isBefore(cutoff48h) } == true)
            }
            .sortedByDescending { it.rowNumber }
            .take(5)
            .map { prop ->
                val amt = CalculationUtils.primaryAmountForProperty(prop)
                val priceLabel = if (amt > 0.0) CalculationUtils.formatIndianCurrency(amt, prop.tab.isRental) else "On Request"
                val areaLabel = if (prop.carpetAreaSqFt > 0.0) "${prop.carpetAreaSqFt.toInt()} sq.ft." else "Area flex"
                val who = prop.updatedBy.ifBlank { prop.verifiedBy ?: "Team" }
                val summary = scrubContactNumbers(
                    "${prop.propertyType.ifBlank { prop.tab.code }} · ${prop.projectName.ifBlank { prop.location }} (${prop.location}) · $areaLabel · $priceLabel"
                )
                summary to who
            }
    }

    val recentRequirementsSummary = remember(cachedLeads, cutoff48h) {
        cachedLeads
            .filter { lead ->
                !lead.status.equals("Closed", ignoreCase = true) &&
                    !lead.status.equals("Archived", ignoreCase = true) &&
                    (parsePropertyDateToLocalDate(lead.createdDate.ifBlank { lead.updatedAt })?.let { !it.isBefore(cutoff48h) } == true)
            }
            .take(5)
            .map { lead ->
                val who = lead.assignedTo.ifBlank { lead.updatedBy.ifBlank { "CRM Desk" } }
                val budgetLabel = lead.budget.takeIf { it.isNotBlank() } ?: "Budget TBD"
                val reqDesc = scrubContactNumbers(lead.requirement.ifBlank { lead.propertyType.ifBlank { "Requirement" } }).take(60)
                val summary = scrubContactNumbers(
                    "${lead.segment.ifBlank { "Lead" }} · ${lead.location.ifBlank { "Pune" }} · $reqDesc · $budgetLabel"
                )
                summary to who
            }
    }

    // Existing report for current user today
    val existingTodayReport = remember(dailyReports, todayIso, myName, myEmpId) {
        dailyReports.firstOrNull { rep ->
            rep.date == todayIso && (
                rep.employeeId.equals(myEmpId, ignoreCase = true) ||
                    rep.employeeName.equals(myName, ignoreCase = true)
                )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundSoft)
            .testTag("daily_ops_screen")
    ) {
        // ── TOP BAR SUMMARY STRIP ──
        Surface(
            color = SurfaceWhite,
            border = BorderStroke(1.dp, BorderGrey),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Daily Operations · $todayIso",
                            color = TextPrimary,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "POA Cutoff 10:30 AM IST · EOD Cutoff 7:30 PM IST",
                            color = PrimaryActionOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = {
                            refreshTrigger++
                            onRefreshSync()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("daily_ops_refresh_btn")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Daily Metrics", tint = PrimaryActionOrange)
                    }
                }

                if (isAdminOrManager) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DailyOpsTabButton(
                            label = "My POA & EOD",
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            modifier = Modifier.weight(1f).testTag("daily_ops_tab_mine")
                        )
                        DailyOpsTabButton(
                            label = "Team Report (${dailyReports.count { it.date == todayIso }})",
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            modifier = Modifier.weight(1f).testTag("daily_ops_tab_team")
                        )
                    }
                }
            }
        }

        if (selectedTab == 0 || !isAdminOrManager) {
            MyPoaAndEodTab(
                userProfile = userProfile,
                myEmpId = myEmpId,
                myName = myName,
                todayIso = todayIso,
                existingReport = existingTodayReport,
                autoMetrics = autoMetrics,
                recentProperties = recentPropertiesSummary,
                recentRequirements = recentRequirementsSummary,
                showMorningUpdates = showMorningUpdates,
                onToggleMorningUpdates = { showMorningUpdates = !showMorningUpdates },
                onRefreshMetrics = { refreshTrigger++ },
                onSaveDailyReport = { record ->
                    coroutineScope.launch {
                        val res = onSaveDailyReport(record)
                        if (res.isSuccess) {
                            Toast.makeText(context, "Daily Report saved & synced to Cloud!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Saved offline — queued for Sheet sync", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        } else {
            TeamDailyReportTab(
                todayIso = todayIso,
                dailyReports = dailyReports,
                teamNames = teamDirectoryNames,
                allCalls = cachedCalls,
                handovers = cachedHandovers
            )
        }
    }
}

@Composable
private fun DailyOpsTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = if (selected) PrimaryActionOrange else SurfaceMuted,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) PrimaryActionOrange else BorderGrey),
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                color = if (selected) OnPrimaryActionColor else TextPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MyPoaAndEodTab(
    userProfile: UserProfile,
    myEmpId: String,
    myName: String,
    todayIso: String,
    existingReport: DailyReportRecord?,
    autoMetrics: TodayAutoPullMetrics,
    recentProperties: List<Pair<String, String>>,
    recentRequirements: List<Pair<String, String>>,
    showMorningUpdates: Boolean,
    onToggleMorningUpdates: () -> Unit,
    onRefreshMetrics: () -> Unit,
    onSaveDailyReport: (DailyReportRecord) -> Unit
) {
    val context = LocalContext.current

    var poaTasksInput by remember(existingReport?.reportId) {
        mutableStateOf((existingReport?.poaTasksCount ?: 5).toString())
    }
    var poaPlanSummary by remember(existingReport?.reportId) {
        mutableStateOf(existingReport?.poaPlanSummary.orEmpty())
    }
    var siteVisitsInput by remember(existingReport?.reportId) {
        mutableStateOf((existingReport?.siteVisits ?: 0).toString())
    }
    var proposalsSharedInput by remember(existingReport?.reportId) {
        mutableStateOf((existingReport?.proposalsShared ?: 0).toString())
    }
    var poaScoreInput by remember(existingReport?.reportId) {
        mutableStateOf((existingReport?.poaScore ?: 80).toString())
    }
    var keyWins by remember(existingReport?.reportId) {
        mutableStateOf(existingReport?.keyWins.orEmpty())
    }
    var blockers by remember(existingReport?.reportId) {
        mutableStateOf(existingReport?.blockers.orEmpty())
    }

    val callsTotal = maxOf(autoMetrics.callsTotal, existingReport?.callsTotal ?: 0)
    val callsDurationMins = maxOf(autoMetrics.callsDurationMins, existingReport?.callsDurationMins ?: 0)
    val newLeads = maxOf(autoMetrics.newLeads, existingReport?.newLeads ?: 0)
    val followUpsDone = maxOf(autoMetrics.followUpsDone, existingReport?.followUpsDone ?: 0)
    val propsAdded = maxOf(autoMetrics.propertiesAdded, existingReport?.propertiesAdded ?: 0)
    val propsVerified = maxOf(autoMetrics.propertiesVerified, existingReport?.propertiesVerified ?: 0)

    val poaStatus = evaluateCutoff(existingReport?.poaSubmittedAt.orEmpty(), POA_CUTOFF)
    val eodStatus = evaluateCutoff(existingReport?.eodSubmittedAt.orEmpty(), EOD_CUTOFF)

    val reportId = existingReport?.reportId ?: "REP-${todayIso.replace("-", "")}-${myEmpId.take(8)}"

    fun buildRecord(markPoa: Boolean, markEod: Boolean): DailyReportRecord {
        val nowTime = currentIstTimeLabel()
        val nowIso = Instant.now().toString()
        val finalPoaAt = existingReport?.poaSubmittedAt?.takeIf { it.isNotBlank() }
            ?: if (markPoa || poaPlanSummary.isNotBlank()) nowTime else ""
        val finalEodAt = if (markEod) {
            existingReport?.eodSubmittedAt?.takeIf { it.isNotBlank() } ?: nowTime
        } else {
            existingReport?.eodSubmittedAt.orEmpty()
        }
        return DailyReportRecord(
            reportId = reportId,
            date = todayIso,
            employeeId = myEmpId,
            employeeName = myName,
            division = userProfile.division.ifBlank { "All Businesses" },
            poaSubmittedAt = finalPoaAt,
            poaTasksCount = poaTasksInput.toIntOrNull() ?: 0,
            poaPlanSummary = poaPlanSummary.trim(),
            eodSubmittedAt = finalEodAt,
            callsTotal = callsTotal,
            callsDurationMins = callsDurationMins,
            newLeads = newLeads,
            followUpsDone = followUpsDone,
            propertiesAdded = propsAdded,
            propertiesVerified = propsVerified,
            siteVisits = siteVisitsInput.toIntOrNull() ?: 0,
            proposalsShared = proposalsSharedInput.toIntOrNull() ?: 0,
            poaScore = (poaScoreInput.toIntOrNull() ?: 0).coerceIn(0, 100),
            keyWins = scrubContactNumbers(keyWins),
            blockers = scrubContactNumbers(blockers),
            updatedAt = nowIso
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .padding(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 1. COMPACT MORNING UPDATES (24-48H) — SUMMARY + WHO ADDED ONLY ──
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, BorderGrey),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("morning_updates_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleMorningUpdates() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WbSunny,
                            contentDescription = null,
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Morning Updates (24–48h)",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "${recentProperties.size} Properties · ${recentRequirements.size} Requirements (No Contact Numbers)",
                                color = SecondaryText,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val digest = buildString {
                                    appendLine("*THE LEASING COMPANY — MORNING UPDATES ($todayIso)*")
                                    appendLine()
                                    appendLine("*New Properties (Last 48h):*")
                                    if (recentProperties.isEmpty()) {
                                        appendLine("• No new additions in last 48h")
                                    } else {
                                        recentProperties.forEach { (sum, who) ->
                                            appendLine("• $sum — Added by $who")
                                        }
                                    }
                                    appendLine()
                                    appendLine("*New Client Requirements (Last 48h):*")
                                    if (recentRequirements.isEmpty()) {
                                        appendLine("• No new requirements in last 48h")
                                    } else {
                                        recentRequirements.forEach { (sum, who) ->
                                            appendLine("• $sum — Added by $who")
                                        }
                                    }
                                }.trim()
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, digest)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Morning Updates"))
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Morning Digest",
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Icon(
                            imageVector = if (showMorningUpdates) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Toggle Morning Updates",
                            tint = SecondaryText
                        )
                    }
                }

                AnimatedVisibility(visible = showMorningUpdates) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = BorderSubtle)
                        Text(
                            text = "NEW PROPERTIES",
                            color = PrimaryActionOrange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                        if (recentProperties.isEmpty()) {
                            Text("No new properties added in the last 48 hours.", color = SecondaryText, fontSize = 11.5.sp)
                        } else {
                            recentProperties.forEach { (summary, who) ->
                                Text(
                                    text = "• $summary — Added by $who",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "NEW REQUIREMENTS",
                            color = FocusOutlineColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                        if (recentRequirements.isEmpty()) {
                            Text("No new client requirements added in the last 48 hours.", color = SecondaryText, fontSize = 11.5.sp)
                        } else {
                            recentRequirements.forEach { (summary, who) ->
                                Text(
                                    text = "• $summary — Added by $who",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 2. MORNING POA PLANNER (CUTOFF 10:30 AM IST) ──
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, BorderGrey),
            modifier = Modifier.fillMaxWidth()
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Morning Plan of Action (POA)",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = existingReport?.poaSubmittedAt?.takeIf { it.isNotBlank() }?.let {
                                "Submitted at $it"
                            } ?: "Cutoff: 10:30 AM IST",
                            color = SecondaryText,
                            fontSize = 11.5.sp
                        )
                    }
                    CutoffPill(status = poaStatus)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = poaTasksInput,
                        onValueChange = { poaTasksInput = it.filter(Char::isDigit).take(3) },
                        label = { Text("Planned Tasks") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .width(135.dp)
                            .testTag("poa_tasks_input")
                    )
                    Button(
                        onClick = { onSaveDailyReport(buildRecord(markPoa = true, markEod = false)) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp)
                            .testTag("submit_poa_btn")
                    ) {
                        Icon(Icons.Default.ScheduleSend, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (poaStatus == CutoffStatus.PENDING) "Submit POA" else "Update POA",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                OutlinedTextField(
                    value = poaPlanSummary,
                    onValueChange = { poaPlanSummary = it },
                    label = { Text("Today's Plan (Calls, Inspections, Site Visits, Follow-ups)") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("poa_plan_summary_input")
                )
            }
        }

        // ── 2B. PENDING LOOP TASKS QUEUE (BATCH 22) ──
        PendingLoopsQueueCard(
            testTagPrefix = "eod_pending_loops"
        )

        // ── 3. EVENING EOD REPORT (AUTO-PULLED + EDITABLE, CUTOFF 7:30 PM IST) ──
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, BorderGrey),
            modifier = Modifier.fillMaxWidth()
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Evening EOD Report",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = existingReport?.eodSubmittedAt?.takeIf { it.isNotBlank() }?.let {
                                "Submitted at $it"
                            } ?: "Cutoff: 7:30 PM IST",
                            color = SecondaryText,
                            fontSize = 11.5.sp
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = onRefreshMetrics) {
                            Text("Pull Latest", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                        }
                        CutoffPill(status = eodStatus)
                    }
                }

                // Auto-Pulled Counters Grid
                Text(
                    text = "AUTO-PULLED TODAY (FROM CALLS, CRM & INVENTORY)",
                    color = SecondaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val callSubLabel = if (autoMetrics.callsTotal > 0) {
                        "Ext ${autoMetrics.externalCallsTotal} · Team ${autoMetrics.internalCallsTotal} (${callsDurationMins}m)"
                    } else {
                        "${callsDurationMins}m"
                    }
                    AutoMetricTile("Calls", "$callsTotal", callSubLabel, Modifier.weight(1f))
                    AutoMetricTile("New Leads", "$newLeads", "Follow-ups $followUpsDone", Modifier.weight(1f))
                    AutoMetricTile("Properties", "+$propsAdded", "Verified $propsVerified", Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = siteVisitsInput,
                        onValueChange = { siteVisitsInput = it.filter(Char::isDigit).take(3) },
                        label = { Text("Site Visits") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = proposalsSharedInput,
                        onValueChange = { proposalsSharedInput = it.filter(Char::isDigit).take(3) },
                        label = { Text("Proposals") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = poaScoreInput,
                        onValueChange = { poaScoreInput = it.filter(Char::isDigit).take(3) },
                        label = { Text("POA Score %") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = keyWins,
                    onValueChange = { keyWins = it },
                    label = { Text("Key Wins / Hot Deals (No client phone numbers)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = blockers,
                    onValueChange = { blockers = it },
                    label = { Text("Blockers / Support Needed") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val rec = buildRecord(markPoa = false, markEod = false)
                            val waText = buildString {
                                appendLine("*DAILY POA & EOD — ${rec.employeeName} (${rec.date})*")
                                appendLine("Division: ${rec.division}")
                                if (rec.poaPlanSummary.isNotBlank()) {
                                    appendLine("POA (${rec.poaTasksCount} tasks): ${rec.poaPlanSummary}")
                                }
                                appendLine("Calls: ${rec.callsTotal} (${rec.callsDurationMins} mins)")
                                appendLine("Leads: +${rec.newLeads} · Follow-ups: ${rec.followUpsDone}")
                                appendLine("Properties: +${rec.propertiesAdded} · Verified: ${rec.propertiesVerified}")
                                appendLine("Site Visits: ${rec.siteVisits} · Proposals: ${rec.proposalsShared} · POA Score: ${rec.poaScore}%")
                                if (rec.keyWins.isNotBlank()) appendLine("Key Wins: ${rec.keyWins}")
                                if (rec.blockers.isNotBlank()) appendLine("Blockers: ${rec.blockers}")
                            }.trim()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, scrubContactNumbers(waText))
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Daily Report"))
                        },
                        border = BorderStroke(1.dp, FocusOutlineColor),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(0.9f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = FocusOutlineColor, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("WhatsApp", color = FocusOutlineColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = { onSaveDailyReport(buildRecord(markPoa = true, markEod = true)) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .heightIn(min = 48.dp)
                            .testTag("save_sync_eod_btn")
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save & Sync EOD", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamDailyReportTab(
    todayIso: String,
    dailyReports: List<DailyReportRecord>,
    teamNames: List<String>,
    allCalls: List<CrmCallRecord>,
    handovers: List<TeamHandoverRecord>
) {
    val context = LocalContext.current
    val dateOptions = remember(todayIso) {
        val base = LocalDate.parse(todayIso)
        listOf(
            base.toString() to "Today",
            base.minusDays(1).toString() to "Yesterday",
            base.minusDays(2).toString() to base.minusDays(2).format(DateTimeFormatter.ofPattern("dd MMM")),
            base.minusDays(3).toString() to base.minusDays(3).format(DateTimeFormatter.ofPattern("dd MMM"))
        )
    }
    var selectedDate by rememberSaveable { mutableStateOf(todayIso) }

    // Team Call Pulse Range Filter (Today / Last 7 Days / Custom Date)
    var pulseRange by rememberSaveable { mutableStateOf(TeamPulseRange.TODAY) }
    var customPulseDateIso by rememberSaveable { mutableStateOf(todayIso) }

    val openPulseDatePicker = {
        val initial = runCatching { LocalDate.parse(customPulseDateIso) }.getOrElse { LocalDate.now(IST_ZONE) }
        android.app.DatePickerDialog(
            context,
            { _, y, m, d ->
                customPulseDateIso = LocalDate.of(y, m + 1, d).toString()
                pulseRange = TeamPulseRange.CUSTOM
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        ).show()
    }

    val reportsForDate = remember(dailyReports, selectedDate) {
        dailyReports.filter { it.date == selectedDate }
    }

    val allRosterNames = remember(teamNames, reportsForDate, allCalls, handovers) {
        (teamNames +
            reportsForDate.map { it.employeeName } +
            allCalls.map { it.assignedAgent } +
            handovers.map { it.assignedTo })
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("Unassigned", true) }
            .distinctBy { it.lowercase(Locale.ENGLISH) }
            .sorted()
    }

    val reportsByEmp = remember(reportsForDate) {
        reportsForDate.associateBy { it.employeeName.trim().lowercase(Locale.ENGLISH) }
    }

    val pulseTodayLocal = remember(todayIso) {
        runCatching { LocalDate.parse(todayIso) }.getOrElse { LocalDate.now(IST_ZONE) }
    }
    val customPulseLocal = remember(customPulseDateIso, pulseTodayLocal) {
        runCatching { LocalDate.parse(customPulseDateIso) }.getOrElse { pulseTodayLocal }
    }

    val pulseRows = remember(allCalls, handovers, allRosterNames, pulseRange, customPulseLocal, pulseTodayLocal) {
        val nowMs = System.currentTimeMillis()
        val callsInWindow = allCalls.filter { c ->
            val d = Instant.ofEpochMilli(c.timestamp).atZone(IST_ZONE).toLocalDate()
            when (pulseRange) {
                TeamPulseRange.TODAY -> d == pulseTodayLocal
                TeamPulseRange.LAST_7_DAYS -> !d.isBefore(pulseTodayLocal.minusDays(6)) && !d.isAfter(pulseTodayLocal)
                TeamPulseRange.CUSTOM -> d == customPulseLocal
            }
        }
        val handoversInWindow = handovers.filter { ho ->
            val d = Instant.ofEpochMilli(ho.handedOverAt).atZone(IST_ZONE).toLocalDate()
            when (pulseRange) {
                TeamPulseRange.TODAY -> d == pulseTodayLocal
                TeamPulseRange.LAST_7_DAYS -> !d.isBefore(pulseTodayLocal.minusDays(6)) && !d.isAfter(pulseTodayLocal)
                TeamPulseRange.CUSTOM -> d == customPulseLocal
            }
        }
        allRosterNames.map { empName ->
            val empCalls = callsInWindow.filter { c ->
                c.assignedAgent.equals(empName, ignoreCase = true)
            }
            val internalCalls = empCalls.filter { CrmCallManager.isInternalTeamCall(context, it) }
            val externalCalls = empCalls.filterNot { CrmCallManager.isInternalTeamCall(context, it) }
            val externalMins = (externalCalls.sumOf { it.durationSeconds } + 30) / 60
            val missedPending = externalCalls.count { m ->
                if (m.callType != CallType.MISSED) return@count false
                val num10 = CrmCallManager.last10Digits(m.phoneNumber)
                val hasCallback = empCalls.any { other ->
                    other.id != m.id &&
                        other.timestamp > m.timestamp &&
                        CrmCallManager.last10Digits(other.phoneNumber) == num10 &&
                        (other.callType == CallType.OUTGOING || (other.callType == CallType.INCOMING && other.durationSeconds > 0))
                }
                !hasCallback
            }
            val notesLogged = externalCalls.count { CrmCallManager.noteCountsAsLogged(it.notes) }
            val empHandovers = handoversInWindow.filter { ho ->
                ho.assignedTo.equals(empName, ignoreCase = true) ||
                    ho.assignedTo.contains(empName, ignoreCase = true)
            }
            val hoCalledBack = empHandovers.count { !it.isPending() }
            val hoPending = empHandovers.count { it.isPending() }
            val hoOverdue2h = empHandovers.any { it.isOverdue2Hours(nowMs) }

            TeamCallPulseRow(
                employeeName = empName,
                externalCalls = externalCalls.size,
                internalCalls = internalCalls.size,
                talkTimeMins = externalMins,
                missedPending = missedPending,
                notesLogged = notesLogged,
                handoversCalledBack = hoCalledBack,
                handoversPending = hoPending,
                hasOverdueHandover2h = hoOverdue2h
            )
        }.sortedWith(
            compareByDescending<TeamCallPulseRow> { it.externalCalls }
                .thenByDescending { it.handoversPending }
                .thenBy { it.employeeName }
        )
    }

    val pendingHandovers = remember(handovers) {
        handovers.filter { it.isPending() }
    }
    val overdue2hHandovers = remember(pendingHandovers) {
        val nowMs = System.currentTimeMillis()
        pendingHandovers.filter { it.isOverdue2Hours(nowMs) }
    }

    val poaCount = reportsForDate.count { it.poaSubmittedAt.isNotBlank() }
    val eodCount = reportsForDate.count { it.eodSubmittedAt.isNotBlank() }
    val totalCalls = reportsForDate.sumOf { it.callsTotal }
    val totalVisits = reportsForDate.sumOf { it.siteVisits }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("team_daily_report_list"),
        contentPadding = PaddingValues(14.dp, 12.dp, 14.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── BATCH 21: TEAM CALL PULSE SCOREBOARD & HANDOVER CALLBACK ACCOUNTABILITY ──
        item {
            Surface(
                color = SurfaceWhite,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, BorderGrey),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("team_call_pulse_card")
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Team Call Pulse",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "External Calls vs Internal Team · Notes · Handovers",
                                color = SecondaryText,
                                fontSize = 11.sp
                            )
                        }
                        if (overdue2hHandovers.isNotEmpty()) {
                            Surface(
                                color = ErrorRedSubtle,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${overdue2hHandovers.size} Callback >2h",
                                    color = StatusDangerRed,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TeamPulseRange.entries.forEach { range ->
                            val chipLabel = if (range == TeamPulseRange.CUSTOM && pulseRange == TeamPulseRange.CUSTOM) {
                                "Date: $customPulseDateIso"
                            } else {
                                range.label
                            }
                            FilterChip(
                                selected = pulseRange == range,
                                onClick = {
                                    if (range == TeamPulseRange.CUSTOM) {
                                        openPulseDatePicker()
                                    } else {
                                        pulseRange = range
                                    }
                                },
                                label = { Text(chipLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier.testTag("team_pulse_range_${range.name.lowercase(Locale.US)}")
                            )
                        }
                    }

                    val totalExtPulse = pulseRows.sumOf { it.externalCalls }
                    val totalIntPulse = pulseRows.sumOf { it.internalCalls }
                    val totalPulseMins = pulseRows.sumOf { it.talkTimeMins }
                    val totalPendingHo = pulseRows.sumOf { it.handoversPending }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AutoMetricTile("External", "$totalExtPulse", "${totalPulseMins}m talk", Modifier.weight(1f))
                        AutoMetricTile("Internal", "$totalIntPulse", "Team excluded", Modifier.weight(1f))
                        AutoMetricTile("Handovers", "$totalPendingHo", "Pending callbacks", Modifier.weight(1f))
                    }

                    if (pendingHandovers.isNotEmpty()) {
                        Surface(
                            color = if (overdue2hHandovers.isNotEmpty()) ErrorRedSubtle else WarningAmberSubtle,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (overdue2hHandovers.isNotEmpty()) StatusDangerRed.copy(alpha = 0.5f)
                                else StatusWarningAmber.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (overdue2hHandovers.isNotEmpty()) {
                                        "Pending Handover Callbacks (${overdue2hHandovers.size} overdue >2h)"
                                    } else {
                                        "Pending Handover Callbacks (${pendingHandovers.size})"
                                    },
                                    color = if (overdue2hHandovers.isNotEmpty()) StatusDangerRed else StatusWarningAmber,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                pendingHandovers.take(4).forEach { ho ->
                                    val isOver2h = ho.isOverdue2Hours()
                                    val elapsedMins = ((System.currentTimeMillis() - ho.handedOverAt).coerceAtLeast(0L) / 60_000L)
                                    val ageLabel = if (elapsedMins >= 60L) "${elapsedMins / 60L}h ${elapsedMins % 60L}m ago" else "${elapsedMins}m ago"
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${ho.assignedTo} → ${ho.callerName.ifBlank { ho.targetMobile }} (${ho.roleLabel.ifBlank { "Enquiry" }})",
                                            color = TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = ageLabel,
                                            color = if (isOver2h) StatusDangerRed else StatusWarningAmber,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Scoreboard List Header
                    HorizontalDivider(color = BorderSubtle)
                    pulseRows.forEach { row ->
                        Surface(
                            color = SurfaceMuted,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (row.hasOverdueHandover2h) StatusDangerRed.copy(alpha = 0.6f)
                                else if (row.handoversPending > 0) StatusWarningAmber.copy(alpha = 0.6f)
                                else BorderSubtle
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = row.employeeName,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    val hoColor = when {
                                        row.hasOverdueHandover2h -> StatusDangerRed
                                        row.handoversPending > 0 -> StatusWarningAmber
                                        row.handoversCalledBack > 0 -> StatusSuccessGreen
                                        else -> SecondaryText
                                    }
                                    val hoBg = when {
                                        row.hasOverdueHandover2h -> ErrorRedSubtle
                                        row.handoversPending > 0 -> WarningAmberSubtle
                                        row.handoversCalledBack > 0 -> SuccessGreenSubtle
                                        else -> Color.Transparent
                                    }
                                    Surface(color = hoBg, shape = RoundedCornerShape(6.dp)) {
                                        Text(
                                            text = "HO: ${row.handoversCalledBack}/${row.handoversPending} pend",
                                            color = hoColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Ext ${row.externalCalls} · Team ${row.internalCalls}",
                                        color = FocusOutlineColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${row.talkTimeMins}m talk",
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${row.missedPending} missed",
                                        color = if (row.missedPending > 0) StatusDangerRed else SecondaryText,
                                        fontSize = 11.sp,
                                        fontWeight = if (row.missedPending > 0) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        text = "${row.notesLogged} notes",
                                        color = StatusSuccessGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                dateOptions.forEach { (iso, label) ->
                    FilterChip(
                        selected = selectedDate == iso,
                        onClick = { selectedDate = iso },
                        label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AutoMetricTile("POA Filed", "$poaCount/${allRosterNames.size}", "10:30 AM cutoff", Modifier.weight(1f))
                AutoMetricTile("EOD Filed", "$eodCount/${allRosterNames.size}", "7:30 PM cutoff", Modifier.weight(1f))
                AutoMetricTile("Team Calls", "$totalCalls", "$totalVisits visits", Modifier.weight(1f))
            }
        }

        items(allRosterNames, key = { it.lowercase(Locale.ENGLISH) }) { empName ->
            val rec = reportsByEmp[empName.lowercase(Locale.ENGLISH)]
            val poaStat = evaluateCutoff(rec?.poaSubmittedAt.orEmpty(), POA_CUTOFF)
            val eodStat = evaluateCutoff(rec?.eodSubmittedAt.orEmpty(), EOD_CUTOFF)
            var expanded by remember(empName, selectedDate) { mutableStateOf(false) }

            Surface(
                color = SurfaceWhite,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderGrey),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = empName,
                                color = TextPrimary,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = rec?.division?.takeIf { it.isNotBlank() } ?: "Team Member",
                                color = SecondaryText,
                                fontSize = 11.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CompactStatusBadge(prefix = "POA", status = poaStat, time = rec?.poaSubmittedAt.orEmpty())
                            CompactStatusBadge(prefix = "EOD", status = eodStat, time = rec?.eodSubmittedAt.orEmpty())
                        }
                    }

                    if (rec != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Calls: ${rec.callsTotal} (${rec.callsDurationMins}m)", color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("Leads: +${rec.newLeads}", color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("Visits: ${rec.siteVisits}", color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("POA: ${rec.poaScore}%", color = FocusOutlineColor, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }

                        AnimatedVisibility(visible = expanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                HorizontalDivider(color = BorderSubtle)
                                if (rec.poaPlanSummary.isNotBlank()) {
                                    Text("POA (${rec.poaTasksCount} tasks): ${rec.poaPlanSummary}", color = SecondaryText, fontSize = 12.sp)
                                }
                                if (rec.keyWins.isNotBlank()) {
                                    Text("Key Wins: ${rec.keyWins}", color = StatusSuccessGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                if (rec.blockers.isNotBlank()) {
                                    Text("Blockers: ${rec.blockers}", color = StatusDangerRed, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No POA or EOD submitted yet for $selectedDate.",
                            color = SecondaryText,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CutoffPill(status: CutoffStatus) {
    val bg = when (status) {
        CutoffStatus.ON_TIME -> SuccessGreenSubtle
        CutoffStatus.LATE -> WarningAmberSubtle
        CutoffStatus.PENDING -> ErrorRedSubtle
    }
    val fg = when (status) {
        CutoffStatus.ON_TIME -> StatusSuccessGreen
        CutoffStatus.LATE -> StatusWarningAmber
        CutoffStatus.PENDING -> StatusDangerRed
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = status.label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun CompactStatusBadge(prefix: String, status: CutoffStatus, time: String) {
    val bg = when (status) {
        CutoffStatus.ON_TIME -> SuccessGreenSubtle
        CutoffStatus.LATE -> WarningAmberSubtle
        CutoffStatus.PENDING -> ErrorRedSubtle
    }
    val fg = when (status) {
        CutoffStatus.ON_TIME -> StatusSuccessGreen
        CutoffStatus.LATE -> StatusWarningAmber
        CutoffStatus.PENDING -> StatusDangerRed
    }
    val shortTime = time.removeSuffix("IST").trim().take(8)
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = if (status == CutoffStatus.PENDING) "$prefix: Pending" else "$prefix: ${shortTime.ifBlank { status.label }}",
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun AutoMetricTile(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SurfaceMuted,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, color = SecondaryText, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
            Text(text = value, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text(text = subtitle, color = FocusOutlineColor, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
