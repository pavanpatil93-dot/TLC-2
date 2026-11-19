package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crm.*
import com.example.data.OfflineDiskCache
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

private data class FlattenedTenantIssue(
    val tenant: OfflineDiskCache.TenantCacheRecord,
    val issue: TenantIssueRecord
)

@Composable
fun TenantsManagementScreen(
    tenants: List<OfflineDiskCache.TenantCacheRecord>,
    brokersVendors: List<BrokerVendorRecord>,
    inventory: List<PropertyItem>,
    userProfile: UserProfile,
    canViewFullConfidentialPhones: Boolean,
    autoSaveGoogleContactsEnabled: Boolean,
    prefilledTenantProperty: PropertyItem? = null,
    prefilledTenantId: String? = null,
    prefilledTenantDraft: OfflineDiskCache.TenantCacheRecord? = null,
    prefilledBrokerVendorDraft: BrokerVendorRecord? = null,
    initialTabIndex: Int = 0,
    onConsumePrefills: () -> Unit = {},
    onSaveTenant: (OfflineDiskCache.TenantCacheRecord) -> Unit,
    onSaveBrokerVendor: (BrokerVendorRecord) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val safeAgentName = remember(userProfile) { userProfile.username.ifBlank { "TLC Team" } }

    var activeTab by remember(initialTabIndex) { mutableIntStateOf(initialTabIndex) } // 0: Active Tenants, 1: Open Issues, 2: Brokers & Vendors
    var searchQuery by remember { mutableStateOf("") }
    var tenantFilter by remember { mutableStateOf("All") } // All, 90d Renewal, 30d Escalation, Pending Checklist
    var issueCategoryFilter by remember { mutableStateOf<TenantIssueCategory?>(null) }
    var brokerFilter by remember { mutableStateOf("All") } // All, Broker / CP, Ops / Vendor
    var revealedPhoneIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var editingTenant by remember { mutableStateOf<OfflineDiskCache.TenantCacheRecord?>(null) }
    var checklistTargetTenant by remember { mutableStateOf<OfflineDiskCache.TenantCacheRecord?>(null) }
    var logIssueTargetTenant by remember { mutableStateOf<OfflineDiskCache.TenantCacheRecord?>(null) }
    var editingBrokerVendor by remember { mutableStateOf<BrokerVendorRecord?>(null) }

    // Consume external navigation prefills (from PropertyDetailScreen "+ Link / View Tenant" or PostCallSmartSheet)
    LaunchedEffect(prefilledTenantProperty?.id, prefilledTenantId, prefilledTenantDraft?.id, prefilledBrokerVendorDraft?.id) {
        if (prefilledBrokerVendorDraft != null) {
            activeTab = 2
            editingBrokerVendor = prefilledBrokerVendorDraft
            onConsumePrefills()
        } else if (prefilledTenantDraft != null) {
            activeTab = 0
            editingTenant = prefilledTenantDraft
            onConsumePrefills()
        } else if (!prefilledTenantId.isNullOrBlank()) {
            activeTab = 0
            val existing = tenants.firstOrNull { it.id == prefilledTenantId }
            if (existing != null) {
                editingTenant = existing
            }
            onConsumePrefills()
        } else if (prefilledTenantProperty != null) {
            activeTab = 0
            val code = prefilledTenantProperty.propertyCode.ifBlank { prefilledTenantProperty.id }
            val existing = tenants.firstOrNull {
                it.propertyCode.equals(code, ignoreCase = true) ||
                    (it.projectName.equals(prefilledTenantProperty.projectName, true) &&
                        it.unitNumber.equals(prefilledTenantProperty.unitNumber, true) &&
                        prefilledTenantProperty.unitNumber.isNotBlank())
            }
            editingTenant = existing ?: OfflineDiskCache.TenantCacheRecord(
                id = "TNT-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}",
                tenantName = prefilledTenantProperty.tenantName.orEmpty(),
                propertyCode = code,
                projectName = prefilledTenantProperty.projectName,
                unitNumber = prefilledTenantProperty.unitNumber,
                location = prefilledTenantProperty.location,
                division = if (prefilledTenantProperty.isResidential()) "Residential" else "Commercial",
                monthlyRent = (prefilledTenantProperty.rent ?: prefilledTenantProperty.priceOrRent)
                    .takeIf { it > 0.0 }?.toLong()?.toString().orEmpty(),
                leaseStartDate = "",
                leaseExpiryDate = prefilledTenantProperty.leaseExpiryDate,
                lockInPeriod = prefilledTenantProperty.lockInPeriod,
                escalationPercent = prefilledTenantProperty.escalationPercent?.toInt()?.toString() ?: "5",
                ownerName = prefilledTenantProperty.ownerName.orEmpty(),
                ownerContact = prefilledTenantProperty.ownerContact.orEmpty(),
                status = "Active"
            )
            onConsumePrefills()
        }
    }

    val renewalAlertsCount = remember(tenants) {
        tenants.count { !it.status.equals("Vacated", true) && TenantLeaseAlertHelper.isRenewal90dAlert(it.leaseExpiryDate) }
    }
    val escalationAlertsCount = remember(tenants) {
        tenants.count { !it.status.equals("Vacated", true) && TenantLeaseAlertHelper.isEscalation30dAlert(it.leaseStartDate, it.leaseExpiryDate) }
    }
    val allFlattenedIssues = remember(tenants) {
        tenants.flatMap { tenant ->
            tenant.parsedIssues().map { issue -> FlattenedTenantIssue(tenant, issue) }
        }.sortedWith(
            compareBy<FlattenedTenantIssue> {
                when (it.issue.status) {
                    TenantIssueStatus.OPEN -> 0
                    TenantIssueStatus.WAITING_ON_OWNER -> 1
                    TenantIssueStatus.RESOLVED -> 2
                }
            }.thenByDescending { it.issue.reportedAt }
        )
    }
    val openIssuesCount = remember(allFlattenedIssues) {
        allFlattenedIssues.count { it.issue.status != TenantIssueStatus.RESOLVED }
    }

    val filteredTenants = remember(tenants, searchQuery, tenantFilter) {
        val q = searchQuery.trim().lowercase(Locale.ENGLISH)
        tenants.filter { t ->
            val matchesFilter = when (tenantFilter) {
                "90d Renewal" -> TenantLeaseAlertHelper.isRenewal90dAlert(t.leaseExpiryDate)
                "30d Escalation" -> TenantLeaseAlertHelper.isEscalation30dAlert(t.leaseStartDate, t.leaseExpiryDate)
                "Checklist <5/5" -> t.parsedChecklist().completedCount() < 5
                else -> true
            }
            val matchesSearch = q.isBlank() || listOf(
                t.tenantName,
                t.companyName,
                t.projectName,
                t.unitNumber,
                t.propertyCode,
                t.location,
                t.ownerName
            ).any { it.contains(q, ignoreCase = true) }
            matchesFilter && matchesSearch
        }
    }

    val filteredIssues = remember(allFlattenedIssues, issueCategoryFilter, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ENGLISH)
        allFlattenedIssues.filter { row ->
            val catOk = issueCategoryFilter == null || row.issue.category == issueCategoryFilter
            val searchOk = q.isBlank() ||
                row.tenant.tenantName.contains(q, true) ||
                row.tenant.projectName.contains(q, true) ||
                row.issue.description.contains(q, true)
            catOk && searchOk
        }
    }

    val filteredBrokers = remember(brokersVendors, brokerFilter, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ENGLISH)
        brokersVendors.filter { bv ->
            val catOk = when (brokerFilter) {
                "Broker / CP" -> !bv.roleCategory.contains("Ops", true)
                "Ops / Vendor" -> bv.roleCategory.contains("Ops", true)
                else -> true
            }
            val searchOk = q.isBlank() || listOf(
                bv.name,
                bv.firmName,
                bv.subRole,
                bv.operatingAreas,
                bv.phone
            ).any { it.contains(q, true) }
            catOk && searchOk
        }
    }

    Scaffold(
        containerColor = BackgroundSoft,
        topBar = {
            Surface(
                color = SurfaceWhite,
                shadowElevation = 2.dp,
                border = BorderStroke(0.5.dp, BorderGrey)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                        Column {
                            Text(
                                text = "Tenants, Leases & Partners",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${tenants.size} Leases · ${brokersVendors.size} Brokers & Vendors",
                                fontSize = 11.5.sp,
                                color = SecondaryText
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (activeTab == 2) {
                                editingBrokerVendor = BrokerVendorRecord(
                                    roleCategory = if (brokerFilter == "Ops / Vendor") "Ops / Vendor" else "Broker / CP",
                                    subRole = if (brokerFilter == "Ops / Vendor") BrokerVendorRecord.opsSubRoles.first() else "Channel Partner"
                                )
                            } else {
                                editingTenant = OfflineDiskCache.TenantCacheRecord(
                                    id = "TNT-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}",
                                    tenantName = "",
                                    status = "Active"
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("tenants_add_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (activeTab == 2) "Partner" else "Tenant", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("tenants_management_screen")
        ) {
            // ── ALERT STRIP ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = {
                        activeTab = 0
                        tenantFilter = if (tenantFilter == "90d Renewal") "All" else "90d Renewal"
                    },
                    color = if (tenantFilter == "90d Renewal") StatusWarningAmber.copy(alpha = 0.18f) else SurfaceWhite,
                    border = BorderStroke(1.dp, if (renewalAlertsCount > 0) StatusWarningAmber else BorderGrey),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "90d Renewal Alerts: $renewalAlertsCount",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (renewalAlertsCount > 0) StatusWarningAmber else SecondaryText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }

                Surface(
                    onClick = {
                        activeTab = 0
                        tenantFilter = if (tenantFilter == "30d Escalation") "All" else "30d Escalation"
                    },
                    color = if (tenantFilter == "30d Escalation") PrimaryActionOrange.copy(alpha = 0.18f) else SurfaceWhite,
                    border = BorderStroke(1.dp, if (escalationAlertsCount > 0) PrimaryActionOrange else BorderGrey),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "30d Escalation Due: $escalationAlertsCount",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (escalationAlertsCount > 0) PrimaryActionOrange else SecondaryText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }

                Surface(
                    onClick = { activeTab = 1 },
                    color = if (activeTab == 1) StatusDangerRed.copy(alpha = 0.14f) else SurfaceWhite,
                    border = BorderStroke(1.dp, if (openIssuesCount > 0) StatusDangerRed else BorderGrey),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Open Issues: $openIssuesCount",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (openIssuesCount > 0) StatusDangerRed else SecondaryText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            // ── 3-TAB BAR ────────────────────────────────────────────────────
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = SurfaceWhite,
                contentColor = PrimaryActionOrange
            ) {
                listOf(
                    "Active Tenants (${tenants.size})",
                    "Open Issues ($openIssuesCount)",
                    "Brokers & Vendors (${brokersVendors.size})"
                ).forEachIndexed { idx, title ->
                    Tab(
                        selected = activeTab == idx,
                        onClick = { activeTab = idx },
                        text = {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = if (activeTab == idx) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            // ── SEARCH BAR ───────────────────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = when (activeTab) {
                            1 -> "Search issues, tenants or units..."
                            2 -> "Search brokers, vendors, areas or firms..."
                            else -> "Search tenant, project, unit or code..."
                        },
                        fontSize = 13.sp
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // ── TAB CONTENT ──────────────────────────────────────────────────
            when (activeTab) {
                0 -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("All", "90d Renewal", "30d Escalation", "Checklist <5/5").forEach { chip ->
                            FilterChip(
                                selected = tenantFilter == chip,
                                onClick = { tenantFilter = chip },
                                label = { Text(chip, fontSize = 11.sp) }
                            )
                        }
                    }

                    if (filteredTenants.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No leased tenants match this filter. Tap + Tenant to add.", color = SecondaryText, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredTenants, key = { it.id }) { tenant ->
                                TenantCard(
                                    tenant = tenant,
                                    canViewFullPhone = canViewFullConfidentialPhones || revealedPhoneIds.contains(tenant.id),
                                    onToggleRevealPhone = {
                                        revealedPhoneIds = if (revealedPhoneIds.contains(tenant.id)) {
                                            revealedPhoneIds - tenant.id
                                        } else {
                                            revealedPhoneIds + tenant.id
                                        }
                                    },
                                    onEdit = { editingTenant = tenant },
                                    onOpenChecklist = { checklistTargetTenant = tenant },
                                    onLogIssue = { logIssueTargetTenant = tenant },
                                    onMessageOwner = {
                                        if (tenant.ownerContact.isBlank()) {
                                            Toast.makeText(context, "Add Owner Phone on Tenant card first", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val unitLabel = listOf(tenant.projectName, tenant.unitNumber).filter { it.isNotBlank() }.joinToString(" ")
                                            val msg = "Hi ${tenant.ownerName.ifBlank { "Sir/Madam" }}, quick update regarding *$unitLabel* (Tenant: ${tenant.tenantName}).\n- $safeAgentName (TLC)"
                                            CrmCallManager.openWhatsAppChat(context, tenant.ownerContact, msg)
                                        }
                                    },
                                    onMessageTenant = {
                                        if (tenant.contactNumber.isBlank()) {
                                            Toast.makeText(context, "Tenant phone not recorded", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val unitLabel = listOf(tenant.projectName, tenant.unitNumber).filter { it.isNotBlank() }.joinToString(" ")
                                            val msg = "Hi ${tenant.tenantName}, checking in from TLC regarding your lease at *$unitLabel*.\n- $safeAgentName"
                                            CrmCallManager.openWhatsAppChat(context, tenant.contactNumber, msg)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = issueCategoryFilter == null,
                            onClick = { issueCategoryFilter = null },
                            label = { Text("All", fontSize = 11.sp) }
                        )
                        TenantIssueCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = issueCategoryFilter == cat,
                                onClick = { issueCategoryFilter = cat },
                                label = { Text(cat.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    if (filteredIssues.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No tenant issues in this category.", color = SecondaryText, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredIssues, key = { "${it.tenant.id}_${it.issue.id}" }) { row ->
                                TenantIssueCard(
                                    row = row,
                                    onCycleStatus = {
                                        val nextStatus = when (row.issue.status) {
                                            TenantIssueStatus.OPEN -> TenantIssueStatus.WAITING_ON_OWNER
                                            TenantIssueStatus.WAITING_ON_OWNER -> TenantIssueStatus.RESOLVED
                                            TenantIssueStatus.RESOLVED -> TenantIssueStatus.OPEN
                                        }
                                        val updatedList = row.tenant.parsedIssues().map { iss ->
                                            if (iss.id == row.issue.id) {
                                                iss.copy(
                                                    status = nextStatus,
                                                    resolvedAt = if (nextStatus == TenantIssueStatus.RESOLVED) LocalDate.now().toString() else ""
                                                )
                                            } else iss
                                        }
                                        onSaveTenant(row.tenant.copy(openIssuesJson = TenantIssueRecord.serializeList(updatedList)))
                                    },
                                    onForwardOwner = {
                                        if (row.tenant.ownerContact.isBlank()) {
                                            Toast.makeText(context, "Add Owner Phone on Tenant card first", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val unitLabel = listOf(row.tenant.projectName, row.tenant.unitNumber).filter { it.isNotBlank() }.joinToString(" ")
                                            val relay = "Hi ${row.tenant.ownerName.ifBlank { "Sir/Madam" }}, Tenant ${row.tenant.tenantName} ($unitLabel) raised a *${row.issue.category.label}* issue:\n\"${row.issue.description}\"\nPlease advise.\n- $safeAgentName (TLC)"
                                            CrmCallManager.openWhatsAppChat(context, row.tenant.ownerContact, relay)
                                        }
                                    },
                                    onMessageTenant = {
                                        if (row.tenant.contactNumber.isNotBlank()) {
                                            val msg = "Hi ${row.tenant.tenantName}, update on your ${row.issue.category.label} ticket: Status is now *${row.issue.status.label}*.\n- $safeAgentName (TLC)"
                                            CrmCallManager.openWhatsAppChat(context, row.tenant.contactNumber, msg)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("All", "Broker / CP", "Ops / Vendor").forEach { chip ->
                            FilterChip(
                                selected = brokerFilter == chip,
                                onClick = { brokerFilter = chip },
                                label = { Text(chip, fontSize = 11.sp) }
                            )
                        }
                    }

                    if (filteredBrokers.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No Brokers or Ops Vendors saved yet. Tap + Partner to add.", color = SecondaryText, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredBrokers, key = { it.id }) { bv ->
                                BrokerVendorCard(
                                    record = bv,
                                    onCall = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${bv.phone}")))
                                        }.onFailure {
                                            Toast.makeText(context, "Unable to open dialer", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onWhatsApp = {
                                        val msg = "Hi ${bv.name}, connecting from TLC regarding ${bv.operatingAreas.ifBlank { "Pune properties" }}.\n- $safeAgentName"
                                        CrmCallManager.openWhatsAppChat(context, bv.phone, msg)
                                    },
                                    onEdit = { editingBrokerVendor = bv }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── MODALS ───────────────────────────────────────────────────────────────
    editingTenant?.let { draft ->
        TenantEditorDialog(
            initialTenant = draft,
            inventory = inventory,
            onDismiss = { editingTenant = null },
            onSave = { saved ->
                onSaveTenant(saved)
                editingTenant = null
                Toast.makeText(context, "Tenant saved & queued for sheet sync", Toast.LENGTH_SHORT).show()
                if (autoSaveGoogleContactsEnabled && saved.contactNumber.isNotBlank()) {
                    coroutineScope.launch {
                        GoogleContactsSyncHelper.upsertContactIfPermitted(
                            context = context,
                            request = GoogleContactSaveRequest(
                                rolePrefix = TlcContactRolePrefix.TENANT,
                                rawName = saved.tenantName,
                                phone = saved.contactNumber,
                                projectOrUnit = listOf(saved.projectName, saved.unitNumber).filter { it.isNotBlank() }.joinToString(" "),
                                areaOrFirm = saved.companyName,
                                referenceId = saved.propertyCode.ifBlank { saved.id },
                                extraNotes = saved.notes
                            )
                        )
                    }
                }
            }
        )
    }

    checklistTargetTenant?.let { target ->
        TenantMoveInChecklistDialog(
            tenant = target,
            onDismiss = { checklistTargetTenant = null },
            onSaveChecklist = { updatedChecklist ->
                val wasKeys = target.parsedChecklist().keysHandedOver
                onSaveTenant(target.copy(moveInChecklistJson = updatedChecklist.toJson().toString()))
                if (!wasKeys && updatedChecklist.keysHandedOver) {
                    val unitLabel = listOf(target.tenantName, target.projectName, target.unitNumber)
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                    PendingLoopTaskStore.enqueueTenantOrDealHandoverConfirmOwner(
                        context = context,
                        entityId = target.id,
                        subjectLabel = unitLabel.ifBlank { "Tenant Keys Handover" },
                        ownerName = target.ownerName,
                        ownerPhone = target.ownerContact,
                        agentName = safeAgentName,
                        inventory = inventory,
                        propertyHint = target.propertyCode.ifBlank { target.projectName }
                    )
                }
                checklistTargetTenant = null
                Toast.makeText(context, "Checklist updated (${updatedChecklist.completedCount()}/5)", Toast.LENGTH_SHORT).show()
            }
        )
    }

    logIssueTargetTenant?.let { target ->
        TenantLogIssueDialog(
            tenant = target,
            reportedBy = safeAgentName,
            onDismiss = { logIssueTargetTenant = null },
            onSaveIssue = { newIssue, forwardWa ->
                val updatedIssues = listOf(newIssue) + target.parsedIssues()
                onSaveTenant(target.copy(openIssuesJson = TenantIssueRecord.serializeList(updatedIssues)))
                PendingLoopTaskStore.enqueueTenantIssueTasks(
                    context = context,
                    tenant = target,
                    issue = newIssue,
                    agentName = safeAgentName,
                    inventory = inventory
                )
                logIssueTargetTenant = null
                Toast.makeText(context, "Issue logged & syncing", Toast.LENGTH_SHORT).show()
                if (forwardWa && target.ownerContact.isNotBlank()) {
                    val unitLabel = listOf(target.projectName, target.unitNumber).filter { it.isNotBlank() }.joinToString(" ")
                    val relay = "Hi ${target.ownerName.ifBlank { "Sir/Madam" }}, Tenant ${target.tenantName} ($unitLabel) raised a *${newIssue.category.label}* issue:\n\"${newIssue.description}\"\nPlease advise.\n- $safeAgentName (TLC)"
                    CrmCallManager.openWhatsAppChat(context, target.ownerContact, relay)
                }
            }
        )
    }

    editingBrokerVendor?.let { bvDraft ->
        BrokerVendorEditorDialog(
            initialRecord = bvDraft,
            onDismiss = { editingBrokerVendor = null },
            onSave = { saved ->
                onSaveBrokerVendor(saved)
                editingBrokerVendor = null
                Toast.makeText(context, "${saved.name} saved & queued for sheet sync", Toast.LENGTH_SHORT).show()
                if (autoSaveGoogleContactsEnabled && saved.phone.isNotBlank()) {
                    val isOps = saved.roleCategory.contains("Ops", true)
                    coroutineScope.launch {
                        GoogleContactsSyncHelper.upsertContactIfPermitted(
                            context = context,
                            request = GoogleContactSaveRequest(
                                rolePrefix = if (isOps) TlcContactRolePrefix.OPS_VENDOR else TlcContactRolePrefix.BROKER_CP,
                                rawName = saved.name,
                                phone = saved.phone,
                                areaOrFirm = saved.operatingAreas.ifBlank { saved.firmName },
                                subRole = if (isOps) saved.subRole else "",
                                referenceId = saved.id,
                                extraNotes = saved.notes
                            )
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun TenantCard(
    tenant: OfflineDiskCache.TenantCacheRecord,
    canViewFullPhone: Boolean,
    onToggleRevealPhone: () -> Unit,
    onEdit: () -> Unit,
    onOpenChecklist: () -> Unit,
    onLogIssue: () -> Unit,
    onMessageOwner: () -> Unit,
    onMessageTenant: () -> Unit
) {
    val checklist = remember(tenant.moveInChecklistJson) { tenant.parsedChecklist() }
    val openIssues = remember(tenant.openIssuesJson) { tenant.openIssuesCount() }
    val renewalAlert = remember(tenant.leaseExpiryDate) { TenantLeaseAlertHelper.isRenewal90dAlert(tenant.leaseExpiryDate) }
    val escalationAlert = remember(tenant.leaseStartDate, tenant.leaseExpiryDate) {
        TenantLeaseAlertHelper.isEscalation30dAlert(tenant.leaseStartDate, tenant.leaseExpiryDate)
    }
    val rentFormatted = remember(tenant.monthlyRent) {
        CalculationUtils.formatSmartIndianAmount(tenant.monthlyRent, isMonthly = true).ifBlank { "Rent TBD" }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BorderGrey),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tenant.tenantName.ifBlank { "Unnamed Tenant" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (tenant.companyName.isNotBlank()) {
                        Text(tenant.companyName, fontSize = 12.sp, color = SecondaryText)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = PrimaryActionOrange.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = tenant.status.ifBlank { "Active" },
                            color = PrimaryActionOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Tenant", tint = PrimaryActionOrange, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Text(
                text = listOf(tenant.projectName, tenant.unitNumber, tenant.location, tenant.propertyCode)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "Property Not Linked" },
                fontSize = 12.5.sp,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Rent: $rentFormatted", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = StatusSuccessGreen)
                if (tenant.leaseExpiryDate.isNotBlank()) {
                    Text("Expires: ${tenant.leaseExpiryDate}", fontSize = 12.sp, color = SecondaryText)
                }
            }

            // Batch 20: Team "Last Called By" History Banner on Tenant Detail/Card
            val contextForTenantCall = LocalContext.current
            val lastTenantCall = remember(tenant.id, tenant.contactNumber, tenant.ownerContact, tenant.propertyCode) {
                CrmCallManager.findLastCallForTarget(
                    context = contextForTenantCall,
                    phones = listOf(tenant.contactNumber, tenant.ownerContact),
                    entityIds = listOf(tenant.id, tenant.propertyCode)
                )
            }
            if (lastTenantCall != null) {
                LastCalledByBanner(
                    lastCall = lastTenantCall,
                    testTag = "tenant_last_called_by_banner_${tenant.id}"
                )
            }

            if (renewalAlert || escalationAlert || openIssues > 0) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (renewalAlert) {
                        Surface(color = StatusWarningAmber.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                            Text("Renewal Due <=90d", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = StatusWarningAmber, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                        }
                    }
                    if (escalationAlert) {
                        Surface(color = PrimaryActionOrange.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                            Text("Escalation ${tenant.escalationPercent}% <=30d", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                        }
                    }
                    if (openIssues > 0) {
                        Surface(color = StatusDangerRed.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                            Text("$openIssues Open Issue(s)", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = StatusDangerRed, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                        }
                    }
                }
            }

            // Confidential phones (Role masked)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (tenant.contactNumber.isNotBlank()) {
                        Text(
                            text = "Tenant: ${PhoneMaskingUtils.formatPhoneForRole(tenant.contactNumber, canViewFullPhone)}",
                            fontSize = 11.5.sp,
                            color = TextPrimary
                        )
                    }
                    if (tenant.ownerContact.isNotBlank()) {
                        Text(
                            text = "Owner (${tenant.ownerName.ifBlank { "POC" }}): ${PhoneMaskingUtils.formatPhoneForRole(tenant.ownerContact, canViewFullPhone)}",
                            fontSize = 11.5.sp,
                            color = SecondaryText
                        )
                    }
                }
                IconButton(onClick = onToggleRevealPhone, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (canViewFullPhone) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (canViewFullPhone) "Mask Phone" else "Reveal Phone",
                        tint = PrimaryActionOrange,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onOpenChecklist,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                ) {
                    Text("${checklist.completedCount()}/5 Checklist", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onLogIssue,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                ) {
                    Text("+ Log Issue", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onMessageOwner,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                ) {
                    Text("Message Owner", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onMessageTenant,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                ) {
                    Text("Message Tenant", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E7E45), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TenantIssueCard(
    row: FlattenedTenantIssue,
    onCycleStatus: () -> Unit,
    onForwardOwner: () -> Unit,
    onMessageTenant: () -> Unit
) {
    val statusColor = when (row.issue.status) {
        TenantIssueStatus.OPEN -> StatusDangerRed
        TenantIssueStatus.WAITING_ON_OWNER -> StatusWarningAmber
        TenantIssueStatus.RESOLVED -> StatusSuccessGreen
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderGrey),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(color = PrimaryActionOrange.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                    Text(row.issue.category.label, color = PrimaryActionOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Surface(
                    onClick = onCycleStatus,
                    color = statusColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, statusColor),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("${row.issue.status.label} ↻", color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Text(row.issue.description.ifBlank { "${row.issue.category.label} ticket" }, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(
                "${row.tenant.tenantName} · ${row.tenant.projectName} ${row.tenant.unitNumber} · ${row.issue.reportedAt}",
                fontSize = 11.5.sp,
                color = SecondaryText
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onForwardOwner, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                    Text("Forward to Owner", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                }
                OutlinedButton(onClick = onMessageTenant, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                    Text("Message Tenant", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E7E45))
                }
            }
        }
    }
}

@Composable
private fun BrokerVendorCard(
    record: BrokerVendorRecord,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderGrey),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(record.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    if (record.firmName.isNotBlank()) {
                        Text(record.firmName, fontSize = 12.sp, color = SecondaryText)
                    }
                }
                Surface(color = PrimaryActionOrange.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = record.subRole.ifBlank { record.roleCategory },
                        color = PrimaryActionOrange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            if (record.operatingAreas.isNotBlank()) {
                Text("Areas: ${record.operatingAreas}", fontSize = 12.sp, color = TextPrimary)
            }
            if (record.notes.isNotBlank()) {
                Text(record.notes, fontSize = 11.5.sp, color = SecondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCall, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Call", fontSize = 11.5.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onWhatsApp, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                    Icon(Icons.Default.Chat, contentDescription = null, tint = Color(0xFF1E7E45), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("WhatsApp", fontSize = 11.5.sp, color = Color(0xFF1E7E45), fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit Partner", tint = SecondaryText)
                }
            }
        }
    }
}
