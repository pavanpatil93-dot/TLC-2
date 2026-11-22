package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ai.GeminiRealEstateService
import com.example.crm.*
import com.example.data.OfflineDiskCache
import com.example.data.projects.DirectoryProject
import com.example.model.*
import com.example.ui.components.PocTypeChipSelector
import com.example.ui.components.SearchableDropdownField
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private data class DayPresetOption(
    val label: String,
    val dateYmd: String,
    val isToday: Boolean
)

private fun buildQuickDayPresets(): List<DayPresetOption> {
    val ymdFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val dayFmt = SimpleDateFormat("EEE", Locale.US)
    val todayCal = Calendar.getInstance()
    val tomorrowCal = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val dayAfterCal = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 2) }

    val base = mutableListOf(
        DayPresetOption("Today", ymdFmt.format(todayCal.time), isToday = true),
        DayPresetOption("Tomorrow", ymdFmt.format(tomorrowCal.time), isToday = false),
        DayPresetOption("Day After", ymdFmt.format(dayAfterCal.time), isToday = false)
    )
    val targetDays = listOf(Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
    targetDays.forEach { targetDow ->
        val c = Calendar.getInstance()
        val currentDow = c.get(Calendar.DAY_OF_WEEK)
        var delta = (targetDow - currentDow + 7) % 7
        if (delta == 0) delta = 7
        c.add(Calendar.DAY_OF_YEAR, delta)
        val ymd = ymdFmt.format(c.time)
        if (base.none { it.dateYmd == ymd }) {
            base.add(DayPresetOption(dayFmt.format(c.time), ymd, isToday = false))
        }
    }
    return base
}

private fun resolveQuickTimeSelection(isToday: Boolean, option: String): String {
    if (!isToday) {
        return when (option) {
            "9 AM" -> "09:00 AM"
            "12 PM" -> "12:00 PM"
            "3 PM" -> "03:00 PM"
            else -> "06:00 PM"
        }
    }
    val timeFmt = SimpleDateFormat("hh:mm a", Locale.US)
    val cal = Calendar.getInstance()
    return when (option) {
        "+1h" -> {
            cal.add(Calendar.HOUR_OF_DAY, 1)
            timeFmt.format(cal.time)
        }
        "+2h" -> {
            cal.add(Calendar.HOUR_OF_DAY, 2)
            timeFmt.format(cal.time)
        }
        "+3h" -> {
            cal.add(Calendar.HOUR_OF_DAY, 3)
            timeFmt.format(cal.time)
        }
        else -> "06:00 PM"
    }
}

private fun matchPropertiesForPostCall(
    queryDivision: String,
    queryLocation: String,
    queryReqType: String,
    maxBudgetInr: Long?,
    inventory: List<PropertyItem>
): List<PropertyItem> {
    val active = inventory.filter {
        it.status == AvailabilityStatus.AVAILABLE || it.status == AvailabilityStatus.UNDER_NEGOTIATION
    }
    if (active.isEmpty()) return emptyList()
    val cleanLoc = queryLocation.trim().lowercase(Locale.ENGLISH)
    val scored = active.map { prop ->
        var score = 0
        val divMatch = if (queryDivision.equals("Residential", ignoreCase = true)) {
            prop.isResidential()
        } else {
            !prop.isResidential()
        }
        if (divMatch) score += 3
        if (cleanLoc.isNotBlank() && (
                prop.location.contains(cleanLoc, ignoreCase = true) ||
                    prop.projectName.contains(cleanLoc, ignoreCase = true)
                )
        ) score += 4
        if (queryReqType.isNotBlank() && prop.transactionType.contains(queryReqType, ignoreCase = true)) {
            score += 2
        }
        val price = prop.rent ?: prop.priceSale ?: prop.priceOrRent
        if (maxBudgetInr != null && maxBudgetInr > 0 && price > 0.0 && price <= maxBudgetInr * 1.2) {
            score += 2
        }
        prop to score
    }.filter { it.second >= 3 }
        .sortedByDescending { it.second }
    return scored.map { it.first }
}

/**
 * Strictly client-safe WhatsApp property summary (never includes Owner, Broker, Source, or Unit Internal Phone).
 */
private fun formatClientSafePropertySummary(prop: PropertyItem): String {
    val amount = prop.rent ?: prop.priceSale ?: prop.priceOrRent
    val isRent = prop.transactionType.contains("Lease", true) || prop.transactionType.contains("Rent", true)
    val formattedPrice = CalculationUtils.formatIndianCurrency(amount, isMonthly = isRent)
    val carpet = if (prop.carpetAreaSqFt > 0) "${prop.carpetAreaSqFt.toInt()} sq.ft. Carpet" else ""
    val furn = prop.furnishing.label
    return buildString {
        append("• *").append(prop.projectName.ifBlank { prop.propertyCode }).append("*")
        if (prop.location.isNotBlank()) append(" (${prop.location})")
        append("\n  ")
        append(listOf(carpet, furn, formattedPrice).filter { it.isNotBlank() }.joinToString(" · "))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostCallSmartSheet(
    call: CrmCallRecord,
    leads: List<CrmLead>,
    inventory: List<PropertyItem>,
    directoryProjects: List<DirectoryProject> = emptyList(),
    underConstProjects: List<UnderConstProject> = emptyList(),
    employeeName: String,
    employeeMobile: String,
    onDismissLater: (CrmCallRecord) -> Unit,
    onMarkPersonalIgnore: (CrmCallRecord) -> Unit,
    onSaveLeadAndSync: (lead: CrmLead, activityType: String, activityNotes: String) -> Unit,
    onShareMatchingProps: (CrmLead) -> Unit,
    onScheduleSiteVisit: (CrmLead) -> Unit,
    onUpdateOwnerPropertyStatus: (property: PropertyItem, newStatus: AvailabilityStatus, callNote: String, updatedPriceOrRent: Double?) -> Boolean,
    onLinkOwnerExistingProperty: (property: PropertyItem, call: CrmCallRecord, ownerNameInput: String) -> Boolean,
    onAddNewProperty: (PropertyItem) -> Boolean,
    onOpenFullAddProperty: (ownerPhone: String) -> Unit,
    onCallResolved: (CrmCallRecord) -> Unit,
    // Batch 18 Extensions (optional with defaults for full backward compatibility)
    tenants: List<OfflineDiskCache.TenantCacheRecord> = emptyList(),
    brokersVendors: List<BrokerVendorRecord> = emptyList(),
    canViewFullConfidentialPhones: Boolean = true,
    autoSaveGoogleContactsEnabled: Boolean = true,
    onSaveTenantAndSync: ((OfflineDiskCache.TenantCacheRecord) -> Unit)? = null,
    onSaveBrokerVendorAndSync: ((BrokerVendorRecord) -> Unit)? = null,
    onOpenMoreDetailsForRole: ((
        role: PostCallRole,
        call: CrmCallRecord,
        prefilledName: String,
        prefilledNotes: String,
        linkedEntityId: String?,
        subRole: String
    ) -> Unit)? = null,
    // Batch 20: Sequential "Clear Queue" Mode & Missed Call WA Callback
    queuePosition: Int = 1,
    queueTotal: Int = 1,
    onCloseQueueDone: (() -> Unit)? = null,
    onMissedCallWaCallback: ((CrmCallRecord) -> Unit)? = null,
    // Batch 21: New project created callback from "Link to Project"
    onProjectCreated: ((DirectoryProject) -> Unit)? = null,
    // Batch 22: Broker Has Property direct capture callback
    onOpenCaptureBrokerUnit: ((brokerName: String, brokerPhone: String, callNote: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val effectiveTenants = remember(tenants) {
        tenants.ifEmpty { OfflineDiskCache.loadTenants(context) }
    }
    val effectiveBrokersVendors = remember(brokersVendors) {
        brokersVendors.ifEmpty { OfflineDiskCache.loadBrokersVendors(context) }
    }

    var workingCall by remember(call.id) { mutableStateOf(call) }
    var match by remember(call.id) {
        mutableStateOf(
            PostCallMatchResult(
                call = call,
                role = PostCallRole.FRESH_UNKNOWN,
                displayTitle = call.callerName.takeIf {
                    it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
                } ?: ContactUtils.formatDisplayPhone(call.phoneNumber).ifBlank { call.phoneNumber },
                subtitle = "Matching caller..."
            )
        )
    }

    LaunchedEffect(call, leads, inventory, directoryProjects, underConstProjects, effectiveTenants, effectiveBrokersVendors) {
        match = withContext(Dispatchers.IO) {
            CrmCallManager.matchCallToDatabase(
                context = context,
                call = workingCall,
                leads = leads,
                inventory = inventory,
                directoryProjects = directoryProjects,
                underConstProjects = underConstProjects,
                tenants = effectiveTenants,
                brokersVendors = effectiveBrokersVendors
            )
        }
    }

    val safeEmployeeName = employeeName.trim().ifBlank { "TLC Team" }
    val safeEmployeeMobile = employeeMobile.trim().ifBlank { "9766880099" }

    // Role override (2x3 grid): null until user clicks or DB matches
    var selectedRole by remember(call.id) { mutableStateOf<PostCallRole?>(null) }
    LaunchedEffect(match.role) {
        if (selectedRole == null && match.role != PostCallRole.FRESH_UNKNOWN) {
            selectedRole = match.role
        }
    }

    // Header Editable Name & Phone Reveal Toggle
    var isEditingCallerName by remember(call.id) { mutableStateOf(false) }
    var editableCallerName by remember(call.id) {
        mutableStateOf(
            call.callerName.takeIf {
                it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
            }.orEmpty()
        )
    }
    LaunchedEffect(match.displayTitle) {
        if (editableCallerName.isBlank() &&
            !match.displayTitle.equals("Unknown Caller", true) &&
            !match.displayTitle.startsWith("+91")
        ) {
            editableCallerName = match.displayTitle
        }
    }
    var revealMaskedPhoneLocally by remember(call.id) { mutableStateOf(false) }
    val shouldShowFullPhone = remember(canViewFullConfidentialPhones, revealMaskedPhoneLocally, selectedRole) {
        val isConfidentialRole = selectedRole == PostCallRole.PROPERTY_OWNER || selectedRole == PostCallRole.TENANT
        !isConfidentialRole || canViewFullConfidentialPhones || revealMaskedPhoneLocally
    }
    val headerPhoneText = remember(workingCall.phoneNumber, shouldShowFullPhone) {
        PhoneMaskingUtils.formatPhoneForRole(workingCall.phoneNumber, shouldShowFullPhone)
            .ifBlank { workingCall.phoneNumber }
    }

    // Notes + Voice
    var notes by remember(call.id) {
        mutableStateOf(
            call.notes.takeUnless {
                it.equals("Imported from device call log", ignoreCase = true)
            }.orEmpty()
        )
    }
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrBlank()) {
                notes = if (notes.isBlank()) spoken else "$notes\n$spoken"
            }
        }
    }
    val launchVoiceToNote: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak call summary...")
        }
        runCatching { voiceLauncher.launch(intent) }.onFailure {
            Toast.makeText(context, "Voice dictation unavailable. Please type note.", Toast.LENGTH_SHORT).show()
        }
    }

    // Contacts permission launcher for graceful WRITE_CONTACTS request if enabled
    var pendingContactRequest by remember { mutableStateOf<GoogleContactSaveRequest?>(null) }
    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it }
        val req = pendingContactRequest
        pendingContactRequest = null
        if (granted && req != null) {
            coroutineScope.launch {
                val res = GoogleContactsSyncHelper.upsertContactIfPermitted(context, req)
                if (res.succeeded) {
                    Toast.makeText(context, "Saved Contact: ${res.formattedDisplayName}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 2-Step Follow-up state (works WITH or WITHOUT Role)
    val dayPresets = remember { buildQuickDayPresets() }
    var selectedDayLabel by remember(call.id) { mutableStateOf<String?>(null) }
    var followUpDateYmd by remember(call.id) { mutableStateOf("") }
    var selectedTimeLabel by remember(call.id) { mutableStateOf<String?>(null) }
    var followUpTimeFormatted by remember(call.id) { mutableStateOf("") }

    // Fresh Number mode: Link to Existing | Create New
    var freshLinkMode by remember(call.id) { mutableStateOf("Create New") }

    // Sub-role for Ops / Vendor & Developer POC
    var opsSubRole by remember(call.id) {
        mutableStateOf(
            match.matchedBrokerVendor?.subRole?.takeIf { it in BrokerVendorRecord.opsSubRoles }
                ?: BrokerVendorRecord.opsSubRoles.first()
        )
    }
    var devIsSourcingManager by remember(call.id) { mutableStateOf(false) }

    // Client Lead state
    var clientDivision by remember(call.id) { mutableStateOf(match.matchedLead?.segment?.ifBlank { "Commercial" } ?: "Commercial") }
    var clientReqType by remember(call.id) { mutableStateOf(match.matchedLead?.requirementType?.ifBlank { "Lease" } ?: "Lease") }
    var clientLocation by remember(call.id) { mutableStateOf(match.matchedLead?.location?.ifBlank { "Kharadi" } ?: "Kharadi") }
    var clientBudget by remember(call.id) { mutableStateOf(match.matchedLead?.budget.orEmpty()) }
    var clientIsInvestor by remember(call.id) { mutableStateOf(match.matchedLead?.clientType?.contains("Investor", true) == true) }
    var selectedExistingLeadLabel by remember(call.id) { mutableStateOf("") }
    val existingLeadOptions = remember(leads) {
        leads.take(150).map { "${it.displayClientTitle()} • ${it.phone}" }
    }

    // Owner state
    var selectedOwnerPropIdx by remember(call.id) { mutableIntStateOf(0) }
    var selectedOwnerUnitLabel by remember(call.id) { mutableStateOf("") }
    var ownerStatusChoice by remember(call.id) { mutableStateOf<AvailabilityStatus?>(null) }
    var ownerPocType by remember(call.id) {
        mutableStateOf(
            match.matchedProperties.firstOrNull()?.pocType?.let { normalizePocType(it) }?.ifBlank { "Property Owner" }
                ?: "Property Owner"
        )
    }
    val ownerUnitOptions = remember(inventory) {
        inventory.take(160).map { prop ->
            val unit = prop.unitNumber.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            "${prop.propertyCode} • ${prop.projectName}$unit (${prop.location})"
        }
    }

    // Broker / CP state (Batch 22: 2-Way Actions)
    var brokerFirm by remember(call.id) {
        mutableStateOf(match.matchedBrokerVendor?.firmName ?: match.matchedBroker?.firm.orEmpty())
    }
    var brokerAreas by remember(call.id) {
        mutableStateOf(match.matchedBrokerVendor?.operatingAreas.orEmpty())
    }
    var brokerActionTab by remember(call.id) { mutableIntStateOf(0) } // 0 = Broker Has Client, 1 = Broker Has Property
    var brokerClientReqType by remember(call.id) { mutableStateOf("Lease") }
    var brokerClientAreaSqFt by remember(call.id) { mutableStateOf("") }
    var brokerClientBudget by remember(call.id) { mutableStateOf("") }
    var brokerClientLocation by remember(call.id) { mutableStateOf("Kharadi") }
    var brokerClientLeadSaved by remember(call.id) { mutableStateOf(false) }
    var showBrokerMultiPicker by remember(call.id) { mutableStateOf(false) }
    var selectedBrokerPropertyIds by remember(call.id) { mutableStateOf<Set<String>>(emptySet()) }
    val brokerMatchedInventory = remember(notes, inventory, brokerClientLocation, brokerClientReqType) {
        val draft = runCatching { GeminiRealEstateService().fallbackParseLeadsBatch(notes).firstOrNull() }.getOrNull()
        val locQuery = brokerClientLocation.ifBlank { draft?.location.orEmpty() }
        val reqQuery = draft?.transactionType?.ifBlank { brokerClientReqType } ?: brokerClientReqType
        val maxB = CalculationUtils.parseCurrencyInput(brokerClientBudget)?.toLong() ?: draft?.maxBudgetInr
        matchPropertiesForPostCall(
            queryDivision = draft?.division ?: "Commercial",
            queryLocation = locQuery,
            queryReqType = reqQuery,
            maxBudgetInr = maxB,
            inventory = inventory
        ).ifEmpty { inventory.filter { it.status == AvailabilityStatus.AVAILABLE }.take(12) }
    }

    // Tenant state
    val tenantOptions = remember(effectiveTenants) {
        effectiveTenants.map { "${it.tenantName} • ${it.projectName.ifBlank { it.propertyCode }}" }
    }
    var selectedTenantLabel by remember(call.id) { mutableStateOf("") }
    val activeMatchedTenant = remember(match.matchedTenant, selectedTenantLabel, effectiveTenants) {
        val idx = tenantOptions.indexOf(selectedTenantLabel)
        if (idx >= 0) effectiveTenants[idx] else match.matchedTenant
    }
    var selectedIssueCategory by remember(call.id) { mutableStateOf(TenantIssueCategory.MAINTENANCE) }
    var selectedIssueStatus by remember(call.id) { mutableStateOf(TenantIssueStatus.OPEN) }

    // Developer POC & Ops/Vendor project state
    var localAddedProjects by remember(call.id) { mutableStateOf<List<DirectoryProject>>(emptyList()) }
    var showAddNewProjectSheet by remember(call.id) { mutableStateOf(false) }
    var newProjectPrefillName by remember(call.id) { mutableStateOf("") }
    val mergedDirectoryProjects = remember(directoryProjects, localAddedProjects) {
        (localAddedProjects + directoryProjects).distinctBy { it.id }
    }
    val projectOptions = remember(mergedDirectoryProjects, underConstProjects) {
        val dir = mergedDirectoryProjects.map { "${it.id} • ${it.name}" }
        val uc = underConstProjects.map { "${it.projectCode} • ${it.projectName}" }
        (dir + uc).distinct()
    }
    var selectedProjectOption by remember(call.id) {
        mutableStateOf(
            match.matchedDirectoryProject?.let { "${it.id} • ${it.name}" }
                ?: match.matchedUnderConstProject?.let { "${it.projectCode} • ${it.projectName}" }
                ?: match.matchedDeveloperPoc?.let { "${it.projectId} • ${it.projectName}" }
                ?: ""
        )
    }
    var opsTargetTenantIssue by remember(call.id) { mutableStateOf("") }

    // Client live match computation
    val liveClientMatches = remember(clientDivision, clientLocation, clientReqType, clientBudget, inventory, selectedRole) {
        if (selectedRole != PostCallRole.CLIENT_LEAD) emptyList()
        else {
            val maxBudgetVal = CalculationUtils.parseCurrencyInput(clientBudget)?.toLong()
            matchPropertiesForPostCall(clientDivision, clientLocation, clientReqType, maxBudgetVal, inventory)
        }
    }

    // Auto-populate name/match details once match settles
    LaunchedEffect(match) {
        if (match.matchedBrokerVendor != null) {
            val bv = match.matchedBrokerVendor!!
            if (brokerFirm.isBlank()) brokerFirm = bv.firmName
            if (brokerAreas.isBlank()) brokerAreas = bv.operatingAreas
            if (bv.subRole in BrokerVendorRecord.opsSubRoles) opsSubRole = bv.subRole
        }
        if (match.matchedLead != null) {
            val l = match.matchedLead!!
            clientDivision = l.segment.ifBlank { clientDivision }
            clientReqType = l.requirementType.ifBlank { clientReqType }
            clientLocation = l.location.ifBlank { clientLocation }
            if (clientBudget.isBlank()) clientBudget = l.budget
            clientIsInvestor = l.clientType.contains("Investor", true)
        }
        if (match.matchedDirectoryProject != null && selectedProjectOption.isBlank()) {
            selectedProjectOption = "${match.matchedDirectoryProject!!.id} • ${match.matchedDirectoryProject!!.name}"
        }
    }

    val openDatePicker: ((String) -> Unit) -> Unit = { onPicked ->
        val cal = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, y, m, d ->
                onPicked(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun triggerGoogleContactAutoSave(request: GoogleContactSaveRequest) {
        if (!autoSaveGoogleContactsEnabled) return
        if (GoogleContactsSyncHelper.hasContactsPermission(context)) {
            coroutineScope.launch {
                val res = GoogleContactsSyncHelper.upsertContactIfPermitted(context, request)
                if (res.succeeded) {
                    Toast.makeText(
                        context,
                        if (res.updatedExisting) "Contact updated: ${res.formattedDisplayName}" else "Saved Contact: ${res.formattedDisplayName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            pendingContactRequest = request
            contactsPermLauncher.launch(
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            )
        }
    }

    var showHandoverToTeamSheet by remember(call.id) { mutableStateOf(false) }

    fun resolveSubRoleForHandover(role: PostCallRole): String = when (role) {
        PostCallRole.CLIENT_LEAD -> if (clientIsInvestor) "Investor" else ""
        PostCallRole.PROPERTY_OWNER -> ownerPocType
        PostCallRole.BROKER -> "Channel Partner"
        PostCallRole.DEVELOPER_POC -> if (devIsSourcingManager) "Sourcing Manager" else "Developer Sales Team"
        PostCallRole.TENANT -> ""
        PostCallRole.OPS_VENDOR -> opsSubRole
        PostCallRole.FRESH_UNKNOWN -> ""
    }

    fun resolveTaggedPropertyOrProject(role: PostCallRole): String = when (role) {
        PostCallRole.PROPERTY_OWNER -> {
            val chosenUnitIdx = ownerUnitOptions.indexOf(selectedOwnerUnitLabel)
            val prop = match.matchedProperties.getOrNull(selectedOwnerPropIdx)
                ?: if (chosenUnitIdx >= 0) inventory[chosenUnitIdx] else null
            prop?.let { "${it.propertyCode} • ${it.projectName} ${it.unitNumber}".trim() }
                ?: selectedOwnerUnitLabel.takeIf { it.isNotBlank() }.orEmpty()
        }
        PostCallRole.TENANT -> {
            activeMatchedTenant?.let {
                listOf(it.projectName, it.unitNumber.ifBlank { it.propertyCode })
                    .filter { part -> part.isNotBlank() }
                    .joinToString(" • ")
            } ?: selectedTenantLabel.takeIf { it.isNotBlank() }.orEmpty()
        }
        PostCallRole.DEVELOPER_POC -> selectedProjectOption.trim()
        PostCallRole.OPS_VENDOR -> listOf(opsTargetTenantIssue, selectedProjectOption)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
        PostCallRole.BROKER -> {
            val pickedProps = inventory.filter { it.id in selectedBrokerPropertyIds }
                .map { "${it.propertyCode} • ${it.projectName}".trim() }
            pickedProps.joinToString(", ")
        }
        PostCallRole.CLIENT_LEAD -> match.matchedLead?.matchedPropertyCodes?.takeIf { it.isNotBlank() }.orEmpty()
        PostCallRole.FRESH_UNKNOWN -> ""
    }

    fun buildCurrentHandoverSummary(): TeamHandoverSummary {
        val activeRole = selectedRole ?: PostCallRole.FRESH_UNKNOWN
        val resolvedCallerName = editableCallerName.trim().ifBlank {
            match.displayTitle.takeIf {
                it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
            } ?: activeRole.label
        }
        val mobile10 = CrmCallManager.last10Digits(workingCall.phoneNumber).ifBlank { workingCall.phoneNumber }
        val fuDisplay = if (followUpDateYmd.isNotBlank()) {
            val dayPrefix = selectedDayLabel?.takeIf { it.isNotBlank() }?.let { "$it, " }.orEmpty()
            "$dayPrefix$followUpDateYmd ${followUpTimeFormatted}".trim()
        } else ""
        return TeamHandoverSummary(
            senderEmployeeName = safeEmployeeName,
            roleLabel = activeRole.label,
            subRoleLabel = resolveSubRoleForHandover(activeRole),
            callerName = resolvedCallerName,
            callerMobile10 = mobile10,
            callerMobileDisplay = headerPhoneText,
            propertyOrProject = resolveTaggedPropertyOrProject(activeRole),
            followUpDayTime = fuDisplay,
            callNote = notes.trim()
        )
    }

    /**
     * Unified Commit for "Save", "Add More Details →", and Batch 19 Team Handover.
     * Returns Pair(savedCallRecord, linkedEntityId).
     */
    fun commitPostCallChanges(
        redirectToForm: Boolean,
        handoverTargetMemberName: String? = null
    ): Pair<CrmCallRecord, String?> {
        val role = selectedRole
        val resolvedName = editableCallerName.trim().ifBlank {
            match.displayTitle.takeIf {
                it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
            } ?: when (role) {
                PostCallRole.PROPERTY_OWNER -> "Property Owner"
                PostCallRole.BROKER -> "Channel Partner"
                PostCallRole.DEVELOPER_POC -> "Developer POC"
                PostCallRole.TENANT -> "Tenant"
                PostCallRole.OPS_VENDOR -> opsSubRole
                PostCallRole.CLIENT_LEAD -> "Client Lead"
                else -> "Caller $headerPhoneText"
            }
        }

        val cleanPhone = ContactUtils.normalizePhoneNumber(workingCall.phoneNumber)
        val rawNoteClean = notes.trim()
        val handoverTarget = handoverTargetMemberName?.trim()?.takeIf { it.isNotBlank() }
        val handoverNoteTag = handoverTarget?.let { "Handed over to $it on WhatsApp" }
        val noteClean = listOf(rawNoteClean, handoverNoteTag)
            .filter { !it.isNullOrBlank() }
            .joinToString(" • ")
        val effectiveAssignedTo = handoverTarget ?: safeEmployeeName
        val hasFollowUp = followUpDateYmd.isNotBlank()
        val fuDate = followUpDateYmd
        val fuTime = followUpTimeFormatted.ifBlank { if (hasFollowUp) "11:00 AM" else "" }
        val todayYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        var linkedType: String? = workingCall.linkedOpportunityType
        var linkedTitle: String? = workingCall.linkedOpportunityTitle
        var linkedId: String? = workingCall.linkedOpportunityId
        var contactSaveReq: GoogleContactSaveRequest? = null
        var subRoleSaved = workingCall.pocSubRole

        when (role) {
            PostCallRole.CLIENT_LEAD -> {
                linkedType = "Client"
                val existingLead = match.matchedLead ?: run {
                    val idx = existingLeadOptions.indexOf(selectedExistingLeadLabel)
                    if (freshLinkMode == "Link to Existing" && idx >= 0) leads[idx] else null
                }
                if (existingLead != null) {
                    val mergedAltPhone = if (freshLinkMode == "Link to Existing" &&
                        cleanPhone.isNotBlank() &&
                        CrmCallManager.last10Digits(existingLead.phone) != CrmCallManager.last10Digits(cleanPhone) &&
                        !existingLead.alternatePhone.contains(cleanPhone.takeLast(10))
                    ) {
                        listOf(existingLead.alternatePhone, cleanPhone).filter { it.isNotBlank() }.joinToString(", ")
                    } else existingLead.alternatePhone

                    val normalizedExistingStatus = if (existingLead.status.isBlank() || existingLead.status.equals("New", true)) {
                        "New Lead"
                    } else {
                        existingLead.status
                    }
                    val updatedLead = existingLead.copy(
                        name = resolvedName.ifBlank { existingLead.name },
                        alternatePhone = mergedAltPhone,
                        segment = clientDivision,
                        requirementType = clientReqType,
                        location = clientLocation,
                        budget = clientBudget.ifBlank { existingLead.budget },
                        clientType = if (clientIsInvestor) "Investor" else existingLead.clientType,
                        status = normalizedExistingStatus,
                        verificationStatus = "Verified Live",
                        assignedTo = if (handoverTarget != null) handoverTarget else existingLead.assignedTo,
                        nextFollowUp = if (hasFollowUp) fuDate else existingLead.nextFollowUp,
                        nextFollowUpTime = if (hasFollowUp) fuTime else existingLead.nextFollowUpTime,
                        followUpTime = if (hasFollowUp) fuTime else existingLead.followUpTime,
                        lastContactDate = todayYmd,
                        lastContactMode = "Call",
                        notes = if (noteClean.isNotBlank()) "$noteClean\n${existingLead.notes}".trim() else existingLead.notes
                    )
                    onSaveLeadAndSync(
                        updatedLead,
                        if (handoverTarget != null) "Team Handover" else "Client Call",
                        noteClean.ifBlank { "Logged Client Call" }
                    )
                    linkedId = updatedLead.id
                    linkedTitle = updatedLead.displayClientTitle()
                } else {
                    val reqText = noteClean.ifBlank { "$clientDivision $clientReqType in $clientLocation" }
                    val newLeadId = "CRM-${System.currentTimeMillis().toString().takeLast(6)}"
                    val newLead = CrmLead(
                        id = newLeadId,
                        name = resolvedName,
                        phone = cleanPhone,
                        clientType = if (clientIsInvestor) "Investor" else "Direct Client",
                        segment = clientDivision,
                        requirement = reqText,
                        requirementType = clientReqType,
                        location = clientLocation,
                        budget = clientBudget.trim().ifBlank { "Negotiable" },
                        status = "New Lead",
                        verificationStatus = "Verified Live",
                        nextFollowUp = if (hasFollowUp) fuDate else "",
                        nextFollowUpTime = fuTime,
                        followUpTime = fuTime,
                        notes = reqText,
                        assignedTo = effectiveAssignedTo,
                        lastContactDate = todayYmd,
                        lastContactMode = "Call",
                        leadSource = "Inbound Call"
                    )
                    onSaveLeadAndSync(
                        newLead,
                        if (handoverTarget != null) "Team Handover" else "New Client Call",
                        reqText
                    )
                    linkedId = newLead.id
                    linkedTitle = resolvedName
                }
                contactSaveReq = GoogleContactSaveRequest(
                    rolePrefix = if (clientIsInvestor) TlcContactRolePrefix.INVESTOR else TlcContactRolePrefix.CLIENT_LEAD,
                    rawName = resolvedName,
                    phone = workingCall.phoneNumber,
                    projectOrUnit = "$clientDivision $clientLocation".trim(),
                    referenceId = linkedId.orEmpty(),
                    extraNotes = noteClean
                )
            }

            PostCallRole.PROPERTY_OWNER -> {
                linkedType = "Owner"
                val chosenUnitIdx = ownerUnitOptions.indexOf(selectedOwnerUnitLabel)
                val targetProp = match.matchedProperties.getOrNull(selectedOwnerPropIdx)
                    ?: if (chosenUnitIdx >= 0) inventory[chosenUnitIdx] else null

                if (targetProp != null) {
                    val propWithPoc = targetProp.copy(pocType = ownerPocType)
                    if (ownerStatusChoice != null) {
                        onUpdateOwnerPropertyStatus(
                            propWithPoc,
                            ownerStatusChoice!!,
                            noteClean.ifBlank { "Owner Call: ${ownerStatusChoice!!.label}" },
                            null
                        )
                    } else if (match.matchedProperties.isEmpty()) {
                        // Link caller phone to existing property without duplicating
                        onLinkOwnerExistingProperty(
                            propWithPoc,
                            workingCall.copy(notes = noteClean),
                            resolvedName
                        )
                    }
                    linkedId = targetProp.id
                    linkedTitle = "${targetProp.propertyCode} • ${targetProp.projectName}"
                    contactSaveReq = GoogleContactSaveRequest(
                        rolePrefix = TlcContactRolePrefix.OWNER,
                        rawName = resolvedName,
                        phone = workingCall.phoneNumber,
                        projectOrUnit = "${targetProp.projectName} ${targetProp.unitNumber}".trim(),
                        referenceId = targetProp.propertyCode.ifBlank { targetProp.id },
                        extraNotes = noteClean
                    )
                } else {
                    contactSaveReq = GoogleContactSaveRequest(
                        rolePrefix = TlcContactRolePrefix.OWNER,
                        rawName = resolvedName,
                        phone = workingCall.phoneNumber,
                        extraNotes = noteClean
                    )
                }
            }

            PostCallRole.BROKER -> {
                linkedType = "Broker"
                val existingBv = match.matchedBrokerVendor
                val bvRecord = BrokerVendorRecord(
                    id = existingBv?.id ?: "BV-${cleanPhone.ifBlank { System.currentTimeMillis().toString().takeLast(6) }}",
                    name = resolvedName,
                    phone = cleanPhone.ifBlank { workingCall.phoneNumber },
                    roleCategory = "Broker / CP",
                    subRole = "Channel Partner",
                    firmName = brokerFirm.trim(),
                    operatingAreas = brokerAreas.trim(),
                    notes = noteClean.ifBlank { existingBv?.notes.orEmpty() }
                )
                CrmCallManager.saveBrokerContact(context, workingCall.phoneNumber, resolvedName, brokerFirm.trim(), noteClean)
                if (onSaveBrokerVendorAndSync != null) {
                    onSaveBrokerVendorAndSync(bvRecord)
                } else {
                    val cur = OfflineDiskCache.loadBrokersVendors(context).filterNot { it.id == bvRecord.id } + bvRecord
                    OfflineDiskCache.saveBrokersVendors(context, cur)
                }
                linkedId = bvRecord.id
                linkedTitle = resolvedName
                contactSaveReq = GoogleContactSaveRequest(
                    rolePrefix = TlcContactRolePrefix.BROKER_CP,
                    rawName = resolvedName,
                    phone = workingCall.phoneNumber,
                    areaOrFirm = brokerAreas.ifBlank { brokerFirm },
                    referenceId = bvRecord.id,
                    extraNotes = noteClean
                )
            }

            PostCallRole.DEVELOPER_POC -> {
                linkedType = "Developer POC"
                subRoleSaved = if (devIsSourcingManager) "Sourcing Manager" else "Developer Sales Team"
                val parts = selectedProjectOption.split(" • ", limit = 2)
                val pId = parts.firstOrNull().orEmpty().trim()
                val pName = parts.getOrNull(1).orEmpty().ifBlank { selectedProjectOption }
                if (pId.isNotBlank() || pName.isNotBlank()) {
                    val div = mergedDirectoryProjects.firstOrNull { it.id == pId }?.division ?: "Residential"
                    CrmCallManager.saveProjectPocLink(context, workingCall.phoneNumber, pId, pName, div, resolvedName)
                    linkedId = pId
                    linkedTitle = if (pId.isNotBlank()) "$pId • $pName" else pName
                } else {
                    linkedTitle = resolvedName
                }
                contactSaveReq = GoogleContactSaveRequest(
                    rolePrefix = if (devIsSourcingManager) TlcContactRolePrefix.SOURCING_MANAGER else TlcContactRolePrefix.DEVELOPER_SALES,
                    rawName = resolvedName,
                    phone = workingCall.phoneNumber,
                    projectOrUnit = pName,
                    referenceId = pId,
                    extraNotes = noteClean
                )
            }

            PostCallRole.TENANT -> {
                linkedType = "Tenant"
                val baseTenant = activeMatchedTenant ?: OfflineDiskCache.TenantCacheRecord(
                    id = "TEN-${cleanPhone.ifBlank { System.currentTimeMillis().toString().takeLast(6) }}",
                    tenantName = resolvedName,
                    contactNumber = cleanPhone
                )
                val newIssue = if (rawNoteClean.isNotBlank() || selectedIssueCategory != TenantIssueCategory.MAINTENANCE || selectedIssueStatus != TenantIssueStatus.OPEN) {
                    TenantIssueRecord(
                        tenantId = baseTenant.id,
                        propertyCode = baseTenant.propertyCode,
                        category = selectedIssueCategory,
                        status = selectedIssueStatus,
                        description = noteClean.ifBlank { "${selectedIssueCategory.label} check-in via call" },
                        reportedBy = safeEmployeeName
                    )
                } else null
                val mergedIssues = if (newIssue != null) listOf(newIssue) + baseTenant.parsedIssues() else baseTenant.parsedIssues()
                val updatedTenant = baseTenant.copy(
                    tenantName = resolvedName.ifBlank { baseTenant.tenantName },
                    contactNumber = cleanPhone.ifBlank { baseTenant.contactNumber },
                    openIssuesJson = if (mergedIssues.isNotEmpty()) TenantIssueRecord.serializeList(mergedIssues) else baseTenant.openIssuesJson,
                    notes = if (noteClean.isNotBlank()) "$noteClean\n${baseTenant.notes}".trim() else baseTenant.notes,
                    assignedTo = if (handoverTarget != null) handoverTarget else baseTenant.assignedTo
                )
                if (onSaveTenantAndSync != null) {
                    onSaveTenantAndSync(updatedTenant)
                } else {
                    val cur = OfflineDiskCache.loadTenants(context).filterNot { it.id == updatedTenant.id } + updatedTenant
                    OfflineDiskCache.saveTenants(context, cur)
                }
                if (newIssue != null) {
                    PendingLoopTaskStore.enqueueTenantIssueTasks(
                        context = context,
                        tenant = updatedTenant,
                        issue = newIssue,
                        agentName = safeEmployeeName,
                        inventory = inventory
                    )
                }
                linkedId = updatedTenant.id
                linkedTitle = "${updatedTenant.tenantName} • ${updatedTenant.projectName.ifBlank { updatedTenant.propertyCode }}".trimEnd(' ', '•')
                contactSaveReq = GoogleContactSaveRequest(
                    rolePrefix = TlcContactRolePrefix.TENANT,
                    rawName = resolvedName,
                    phone = workingCall.phoneNumber,
                    projectOrUnit = "${updatedTenant.projectName} ${updatedTenant.unitNumber}".trim(),
                    referenceId = updatedTenant.propertyCode.ifBlank { updatedTenant.id },
                    extraNotes = noteClean
                )
            }

            PostCallRole.OPS_VENDOR -> {
                linkedType = "Ops / Vendor"
                subRoleSaved = opsSubRole
                val existingBv = match.matchedBrokerVendor
                val bvRecord = BrokerVendorRecord(
                    id = existingBv?.id ?: "BV-OPS-${cleanPhone.ifBlank { System.currentTimeMillis().toString().takeLast(6) }}",
                    name = resolvedName,
                    phone = cleanPhone.ifBlank { workingCall.phoneNumber },
                    roleCategory = "Ops / Vendor",
                    subRole = opsSubRole,
                    operatingAreas = selectedProjectOption.ifBlank { existingBv?.operatingAreas.orEmpty() },
                    notes = listOf(opsTargetTenantIssue, noteClean).filter { it.isNotBlank() }.joinToString(" • ")
                )
                if (onSaveBrokerVendorAndSync != null) {
                    onSaveBrokerVendorAndSync(bvRecord)
                } else {
                    val cur = OfflineDiskCache.loadBrokersVendors(context).filterNot { it.id == bvRecord.id } + bvRecord
                    OfflineDiskCache.saveBrokersVendors(context, cur)
                }
                linkedId = bvRecord.id
                linkedTitle = "$opsSubRole: $resolvedName"
                contactSaveReq = GoogleContactSaveRequest(
                    rolePrefix = TlcContactRolePrefix.OPS_VENDOR,
                    rawName = resolvedName,
                    phone = workingCall.phoneNumber,
                    subRole = opsSubRole,
                    projectOrUnit = selectedProjectOption,
                    referenceId = bvRecord.id,
                    extraNotes = noteClean
                )
            }

            null, PostCallRole.FRESH_UNKNOWN -> Unit
        }

        val finalFollowUpStr = if (hasFollowUp) "$fuDate $fuTime".trim() else workingCall.nextFollowUpDateTime
        val nowMs = System.currentTimeMillis()
        val updatedCall = workingCall.copy(
            callerName = resolvedName,
            notes = noteClean.ifBlank { workingCall.notes },
            nextFollowUpDateTime = finalFollowUpStr,
            assignedAgent = effectiveAssignedTo,
            isLinked = role != null && role != PostCallRole.FRESH_UNKNOWN,
            linkedOpportunityType = linkedType,
            linkedOpportunityTitle = linkedTitle,
            linkedOpportunityId = linkedId,
            pocSubRole = subRoleSaved,
            waLogged = workingCall.waLogged || handoverTarget != null,
            handoverStatus = if (handoverTarget != null) "Pending Callback" else workingCall.handoverStatus,
            handoverAssignedTo = handoverTarget ?: workingCall.handoverAssignedTo,
            handedOverAt = if (handoverTarget != null) nowMs else workingCall.handedOverAt
        )

        CrmCallManager.addOrUpdateCall(context, updatedCall)

        // Independent Follow-up Reminder persistence (works with or without role!)
        if (hasFollowUp) {
            CallFollowUpStore.set(context, updatedCall.id, fuDate)
            val task = CrmTask(
                title = "${role?.label ?: "Follow-up"} • $resolvedName",
                origin = if (!linkedId.isNullOrBlank() && role == PostCallRole.CLIENT_LEAD) CrmTaskOrigin.LEAD else CrmTaskOrigin.CALL,
                originId = linkedId?.takeIf { it.isNotBlank() } ?: updatedCall.id,
                assignedTo = effectiveAssignedTo,
                dueDate = fuDate,
                dueTime = CrmCallManager.to24HourTimeOrEmpty(fuTime),
                notes = noteClean.ifBlank { "Post-call follow-up with $resolvedName" },
                status = CrmTaskStatus.OPEN
            )
            runCatching { CrmTaskRepository.upsert(context, task) }
        }

        CrmCallManager.logActivity(
            context = context,
            entityId = linkedId ?: updatedCall.id,
            type = if (handoverTarget != null) "Team Handover • ${role?.label ?: "General"}" else "Call Logged • ${role?.label ?: "General"}",
            summary = "$resolvedName ($headerPhoneText)",
            details = noteClean.ifBlank { "Follow-up: $finalFollowUpStr" },
            author = safeEmployeeName
        )

        CrmCallManager.unskipCall(context, updatedCall)

        if (contactSaveReq != null) {
            triggerGoogleContactAutoSave(contactSaveReq)
        }

        if (redirectToForm && role != null) {
            if (onOpenMoreDetailsForRole != null) {
                onOpenMoreDetailsForRole(role, updatedCall, resolvedName, noteClean, linkedId, subRoleSaved)
                return updatedCall to linkedId
            } else {
                when (role) {
                    PostCallRole.PROPERTY_OWNER -> onOpenFullAddProperty(cleanPhone)
                    PostCallRole.CLIENT_LEAD -> match.matchedLead?.let { onShareMatchingProps(it) }
                    else -> Unit
                }
            }
        }
        onCallResolved(updatedCall)
        return updatedCall to linkedId
    }

    ModalBottomSheet(
        onDismissRequest = { (onCloseQueueDone ?: { onDismissLater(workingCall) }).invoke() },
        sheetState = sheetState,
        containerColor = BackgroundSoft,
        contentColor = TextPrimary,
        modifier = Modifier.testTag("post_call_smart_sheet")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ================= SCROLLABLE BODY =================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── 0. SEQUENTIAL CLEAR QUEUE PROGRESS BAR & DONE BUTTON ──────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("post_call_queue_header_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = PrimaryActionOrange.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.35f)),
                        modifier = Modifier.testTag("post_call_queue_progress_badge")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlaylistPlay,
                                contentDescription = null,
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "${queuePosition.coerceAtLeast(1)} of ${queueTotal.coerceAtLeast(1)} Unlinked",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = PrimaryActionOrange
                            )
                        }
                    }

                    Surface(
                        onClick = { (onCloseQueueDone ?: { onDismissLater(workingCall) }).invoke() },
                        shape = RoundedCornerShape(14.dp),
                        color = SurfaceWhite,
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier
                            .heightIn(min = 34.dp)
                            .testTag("post_call_queue_done_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Done",
                                tint = TextPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Done",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // ── 1. COMPACT HEADER ──────────────────────────────────────────
                val (badgeIcon, badgeColor) = when (workingCall.callType) {
                    CallType.INCOMING -> Icons.Default.CallReceived to SuccessGreen
                    CallType.OUTGOING -> Icons.Default.CallMade to Color(0xFF1565C0)
                    CallType.MISSED -> Icons.Default.CallMissed to ErrorRed
                }
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(badgeColor.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(badgeIcon, contentDescription = workingCall.callType.label, tint = badgeColor, modifier = Modifier.size(18.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isEditingCallerName) {
                                            OutlinedTextField(
                                                value = editableCallerName,
                                                onValueChange = { editableCallerName = it },
                                                placeholder = { Text("Caller Name", fontSize = 12.sp) },
                                                singleLine = true,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("post_call_edit_name_input")
                                            )
                                            IconButton(
                                                onClick = { isEditingCallerName = false },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = "Done editing name", tint = PrimaryActionOrange)
                                            }
                                        } else {
                                            Text(
                                                text = editableCallerName.ifBlank { match.displayTitle.ifBlank { "Unknown Caller" } },
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            IconButton(
                                                onClick = { isEditingCallerName = true },
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .testTag("post_call_edit_name_btn")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Edit caller name",
                                                    tint = SecondaryText,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "$headerPhoneText • ${workingCall.callType.label} • ${CrmCallManager.formatDuration(workingCall.durationSeconds)}",
                                            fontSize = 11.sp,
                                            color = SecondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if ((selectedRole == PostCallRole.PROPERTY_OWNER || selectedRole == PostCallRole.TENANT) && !canViewFullConfidentialPhones) {
                                            Icon(
                                                imageVector = if (revealMaskedPhoneLocally) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = "Toggle phone visibility",
                                                tint = PrimaryActionOrange,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable { revealMaskedPhoneLocally = !revealMaskedPhoneLocally }
                                            )
                                        }
                                    }
                                }
                            }

                            // Green Header WhatsApp Auto-Log + Skip Pill + Mute Forever
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            val updated = withContext(Dispatchers.IO) {
                                                CrmCallManager.logWhatsAppTouchOnCall(context, workingCall)
                                            }
                                            workingCall = updated
                                            if (!notes.contains("Connected on WhatsApp", true)) {
                                                notes = if (notes.isBlank()) "Connected on WhatsApp" else "$notes • Connected on WhatsApp"
                                            }
                                        }
                                        CrmCallManager.openWhatsAppChat(context, workingCall.phoneNumber, "")
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("post_call_header_wa_btn")
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF1E7E45).copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                                            contentDescription = "Open WhatsApp & Log",
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Surface(
                                    onClick = { onDismissLater(workingCall) },
                                    shape = RoundedCornerShape(16.dp),
                                    color = SurfaceTint,
                                    border = BorderStroke(1.dp, BorderGrey),
                                    modifier = Modifier
                                        .heightIn(min = 36.dp)
                                        .testTag("post_call_later_btn")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Skip", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    }
                                }

                                IconButton(
                                    onClick = { onMarkPersonalIgnore(workingCall) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("post_call_personal_ignore_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NotificationsOff,
                                        contentDescription = "Mute Forever",
                                        tint = SecondaryText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        if (workingCall.waLogged) {
                            Surface(
                                color = Color(0xFF1E7E45).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "WA • Connected on WhatsApp logged",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1E7E45),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                // Missed Call WA Callback Banner (Batch 20: 1-tap WA Callback + marks handled + advances queue)
                if (workingCall.callType == CallType.MISSED) {
                    Button(
                        onClick = {
                            val updated = CrmCallManager.triggerMissedCallWaCallback(
                                context = context,
                                call = workingCall,
                                assignedAgent = safeEmployeeName,
                                openBrowserOrApp = true
                            )
                            workingCall = updated
                            if (onMissedCallWaCallback != null) {
                                onMissedCallWaCallback(updated)
                            } else {
                                onCallResolved(updated)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E7E45), contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("post_call_missed_wa_rescue_btn")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("WA Callback • Send Missed Call Message", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // ── 2. CALL NOTE + VOICE MIC + ON-DEMAND CALL RECORDING ───────
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Call Note / Discussion", fontSize = 12.sp) },
                    placeholder = { Text("Tap mic, summarize recording, or type summary...", fontSize = 12.sp) },
                    trailingIcon = {
                        IconButton(
                            onClick = launchVoiceToNote,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("post_call_voice_note_btn")
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Voice Note", tint = PrimaryActionOrange)
                        }
                    },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryActionOrange,
                        unfocusedBorderColor = BorderGrey,
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("post_call_notes_input")
                )

                // Strictly On-Demand Call Recording Match (Play, Choose recording & Summarize)
                // Keyed by workingCall.id so switching call cards immediately stops playback and cancels stale searches/summaries.
                key(workingCall.id) {
                    val boundCallId = workingCall.id
                    CallRecordingOnDemandSection(
                        call = workingCall,
                        currentNotes = notes,
                        onSummaryGenerated = { summary ->
                            if (workingCall.id == boundCallId) {
                                notes = if (notes.isBlank()) summary else "$notes\n$summary"
                            }
                        },
                        testTagPrefix = "post_call_recording"
                    )
                }

                // ── 3. 2-STEP QUICK FOLLOW-UP (INDEPENDENT OF ROLE) ───────────
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Quick Follow-up Reminder", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            if (followUpDateYmd.isNotBlank()) {
                                Text(
                                    text = "$followUpDateYmd ${followUpTimeFormatted}".trim(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryActionOrange
                                )
                            } else {
                                Text("Optional", fontSize = 10.5.sp, color = SecondaryText)
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            dayPresets.forEach { preset ->
                                val isSelected = selectedDayLabel == preset.label
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (isSelected) {
                                            selectedDayLabel = null
                                            followUpDateYmd = ""
                                            selectedTimeLabel = null
                                            followUpTimeFormatted = ""
                                        } else {
                                            selectedDayLabel = preset.label
                                            followUpDateYmd = preset.dateYmd
                                            val defaultTimeOpt = if (preset.isToday) "+1h" else "12 PM"
                                            selectedTimeLabel = defaultTimeOpt
                                            followUpTimeFormatted = resolveQuickTimeSelection(preset.isToday, defaultTimeOpt)
                                        }
                                    },
                                    label = { Text(preset.label, fontSize = 11.sp) },
                                    modifier = Modifier.heightIn(min = 40.dp)
                                )
                            }
                            FilterChip(
                                selected = selectedDayLabel == "Custom",
                                onClick = {
                                    openDatePicker { picked ->
                                        selectedDayLabel = "Custom"
                                        followUpDateYmd = picked
                                        selectedTimeLabel = "12 PM"
                                        followUpTimeFormatted = "12:00 PM"
                                    }
                                },
                                label = { Text("Date", fontSize = 11.sp) },
                                modifier = Modifier.heightIn(min = 40.dp)
                            )
                        }

                        AnimatedVisibility(visible = selectedDayLabel != null) {
                            val isTodayPicked = selectedDayLabel == "Today"
                            val timeChoices = if (isTodayPicked) listOf("+1h", "+2h", "+3h", "6 PM") else listOf("9 AM", "12 PM", "3 PM", "6 PM")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                timeChoices.forEach { tLabel ->
                                    val sel = selectedTimeLabel == tLabel
                                    FilterChip(
                                        selected = sel,
                                        onClick = {
                                            selectedTimeLabel = tLabel
                                            followUpTimeFormatted = resolveQuickTimeSelection(isTodayPicked, tLabel)
                                        },
                                        label = { Text(tLabel, fontSize = 11.sp) },
                                        modifier = Modifier.heightIn(min = 40.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 4. RELATIONSHIP / ROLE 2x3 SLIM PILL GRID ─────────────────
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Caller Relationship / Role", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            if (match.role != PostCallRole.FRESH_UNKNOWN) {
                                Text("Matched: ${match.role.label}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                            }
                        }

                        val row1 = listOf(PostCallRole.CLIENT_LEAD, PostCallRole.PROPERTY_OWNER, PostCallRole.BROKER)
                        val row2 = listOf(PostCallRole.DEVELOPER_POC, PostCallRole.TENANT, PostCallRole.OPS_VENDOR)

                        listOf(row1, row2).forEach { rowRoles ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowRoles.forEach { role ->
                                    val active = selectedRole == role
                                    Surface(
                                        onClick = { selectedRole = if (active) null else role },
                                        color = if (active) SelectedContainerColor else SurfaceTint,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(if (active) 1.5.dp else 0.8.dp, if (active) PrimaryActionOrange else BorderGrey),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("post_call_role_${role.name.lowercase()}")
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                                            Text(
                                                text = role.label,
                                                fontSize = 11.sp,
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                                color = if (active) PrimaryActionOrange else TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 5. SMART ROLE ACTIONS & FRESH CALLER LINKING ─────────────
                if (selectedRole != null) {
                    val activeRole = selectedRole!!
                    val isFreshForSelectedRole = when (activeRole) {
                        PostCallRole.CLIENT_LEAD -> match.matchedLead == null
                        PostCallRole.PROPERTY_OWNER -> match.matchedProperties.isEmpty()
                        PostCallRole.TENANT -> match.matchedTenant == null
                        PostCallRole.BROKER -> match.matchedBroker == null && match.matchedBrokerVendor == null
                        PostCallRole.DEVELOPER_POC -> match.matchedDirectoryProject == null && match.matchedDeveloperPoc == null
                        PostCallRole.OPS_VENDOR -> match.matchedBrokerVendor == null
                        PostCallRole.FRESH_UNKNOWN -> true
                    }

                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isFreshForSelectedRole && activeRole in listOf(PostCallRole.CLIENT_LEAD, PostCallRole.PROPERTY_OWNER, PostCallRole.TENANT)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf("Link to Existing", "Create New").forEach { mode ->
                                        val isSel = freshLinkMode == mode
                                        FilterChip(
                                            selected = isSel,
                                            onClick = { freshLinkMode = mode },
                                            label = { Text(mode, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 42.dp)
                                        )
                                    }
                                }
                            }

                            when (activeRole) {
                                PostCallRole.CLIENT_LEAD -> {
                                    if (match.matchedLead == null && freshLinkMode == "Link to Existing") {
                                        SearchableDropdownField(
                                            label = "Append Phone to Existing Lead (Alternate Mobile)",
                                            selectedValue = selectedExistingLeadLabel,
                                            options = existingLeadOptions,
                                            onValueSelected = { selectedExistingLeadLabel = it },
                                            allowAddNew = false,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    val parsed = GeminiRealEstateService().fallbackParseLeadsBatch(notes).firstOrNull()
                                                    if (parsed != null) {
                                                        clientDivision = parsed.division.ifBlank { clientDivision }
                                                        clientReqType = parsed.transactionType.ifBlank { clientReqType }
                                                        if (parsed.location.isNotBlank()) clientLocation = parsed.location
                                                        if (parsed.budgetText.isNotBlank()) clientBudget = parsed.budgetText
                                                        Toast.makeText(context, "Auto-filled requirement from call note", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.heightIn(min = 42.dp)
                                            ) {
                                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp), tint = PrimaryActionOrange)
                                                Spacer(Modifier.width(4.dp))
                                                Text("Auto-Fill from Note", fontSize = 11.sp, color = TextPrimary)
                                            }
                                            Surface(
                                                color = SelectedSurfaceContainer,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = "${liveClientMatches.size} Matches Found",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PrimaryActionOrange,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                                )
                                            }
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            TlcSurfaceDropdownField(
                                                label = "Division",
                                                selectedValue = clientDivision,
                                                options = listOf("Commercial", "Residential"),
                                                onValueSelected = { clientDivision = it },
                                                modifier = Modifier.weight(1f)
                                            )
                                            TlcSurfaceDropdownField(
                                                label = "Type",
                                                selectedValue = clientReqType,
                                                options = listOf("Lease", "Buy", "Pre-Leased"),
                                                onValueSelected = { clientReqType = it },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            SearchableDropdownField(
                                                label = "Location",
                                                selectedValue = clientLocation,
                                                options = TlcStandardOptions.puneLocations,
                                                onValueSelected = { clientLocation = it },
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedTextField(
                                                value = clientBudget,
                                                onValueChange = { clientBudget = it },
                                                label = { Text("Budget") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        FilterChip(
                                            selected = clientIsInvestor,
                                            onClick = { clientIsInvestor = !clientIsInvestor },
                                            label = { Text(if (clientIsInvestor) "Investor (INV - Contact)" else "Direct Client (CL - Contact)", fontSize = 11.sp) }
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = {
                                                if (match.matchedLead != null) {
                                                    onShareMatchingProps(match.matchedLead!!)
                                                } else {
                                                    val topProps = liveClientMatches.take(3)
                                                    val msg = if (topProps.isNotEmpty()) {
                                                        buildString {
                                                            append("Hi ${editableCallerName.ifBlank { "there" }},\n\nShortlisted matching options from *The Leasing Company*:\n\n")
                                                            topProps.forEach { append(formatClientSafePropertySummary(it)).append("\n\n") }
                                                            append("Regards,\n$safeEmployeeName ($safeEmployeeMobile)")
                                                        }
                                                    } else {
                                                        "Hi ${editableCallerName.ifBlank { "there" }}, thank you for your enquiry for $clientDivision $clientReqType in $clientLocation. Sharing matching options shortly.\n- $safeEmployeeName ($safeEmployeeMobile)"
                                                    }
                                                    CrmCallManager.openWhatsAppChat(context, workingCall.phoneNumber, msg)
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Text("Pick & Send on WhatsApp", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val target = match.matchedLead ?: CrmLead(
                                                    id = "CRM-TMP",
                                                    name = editableCallerName.ifBlank { "Client" },
                                                    phone = ContactUtils.normalizePhoneNumber(workingCall.phoneNumber),
                                                    segment = clientDivision,
                                                    requirement = clientReqType,
                                                    location = clientLocation,
                                                    budget = clientBudget,
                                                    status = "Active",
                                                    nextFollowUp = followUpDateYmd,
                                                    notes = notes
                                                )
                                                onScheduleSiteVisit(target)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Text("+ Schedule Site Visit", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                                        }
                                    }
                                }

                                PostCallRole.PROPERTY_OWNER -> {
                                    if (match.matchedProperties.isEmpty()) {
                                        SearchableDropdownField(
                                            label = "Tag Existing Property Unit (No Duplicate)",
                                            selectedValue = selectedOwnerUnitLabel,
                                            options = ownerUnitOptions,
                                            onValueSelected = { selectedOwnerUnitLabel = it },
                                            allowAddNew = false,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else if (match.matchedProperties.size > 1) {
                                        val labels = match.matchedProperties.map { "${it.propertyCode} • ${it.projectName} ${it.unitNumber}".trim() }
                                        TlcSurfaceDropdownField(
                                            label = "Owner Property",
                                            selectedValue = labels.getOrElse(selectedOwnerPropIdx) { labels.first() },
                                            options = labels,
                                            onValueSelected = { picked ->
                                                val idx = labels.indexOf(picked)
                                                if (idx >= 0) selectedOwnerPropIdx = idx
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        val p = match.matchedProperties.first()
                                        Text(
                                            text = "${p.propertyCode} • ${p.projectName} ${p.unitNumber} (${p.status.label})",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryActionOrange
                                        )
                                    }

                                    PocTypeChipSelector(
                                        selectedPocType = ownerPocType,
                                        onSelect = { ownerPocType = it },
                                        title = "Type of POC",
                                        testTagPrefix = "post_call_owner_poc"
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            "Still Available - Reset 60d" to AvailabilityStatus.AVAILABLE,
                                            "Under Negotiation" to AvailabilityStatus.UNDER_NEGOTIATION,
                                            "Leased / Sold Out" to AvailabilityStatus.LEASED
                                        ).forEach { (label, st) ->
                                            FilterChip(
                                                selected = ownerStatusChoice == st,
                                                onClick = { ownerStatusChoice = if (ownerStatusChoice == st) null else st },
                                                label = { Text(label, fontSize = 11.sp) }
                                            )
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val prop = match.matchedProperties.getOrNull(selectedOwnerPropIdx)
                                            val unitTitle = prop?.let { "${it.projectName} ${it.unitNumber}".trim() } ?: "your property"
                                            val msg = "Hi ${editableCallerName.ifBlank { "Sir/Madam" }}, quick verification check from *The Leasing Company* regarding $unitTitle. Is it still available on the same terms? Please confirm.\n- $safeEmployeeName ($safeEmployeeMobile)"
                                            CrmCallManager.openWhatsAppChat(context, workingCall.phoneNumber, msg)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Text("Send Owner Update on WhatsApp", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E7E45))
                                    }
                                }

                                PostCallRole.BROKER -> {
                                    val brokerDisplayName = editableCallerName.trim().ifBlank {
                                        match.displayTitle.takeIf {
                                            it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
                                        } ?: "Channel Partner"
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedTextField(
                                            value = brokerFirm,
                                            onValueChange = { brokerFirm = it },
                                            label = { Text("Agency / Firm") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = brokerAreas,
                                            onValueChange = { brokerAreas = it },
                                            label = { Text("Operating Areas") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    // Batch 22: Clean 2-Tab Switcher ("Broker Has Client" | "Broker Has Property")
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(SurfaceTint)
                                            .padding(4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(0 to "Broker Has Client", 1 to "Broker Has Property").forEach { (tabIdx, tabLabel) ->
                                            val isSel = brokerActionTab == tabIdx
                                            Surface(
                                                onClick = { brokerActionTab = tabIdx },
                                                color = if (isSel) PrimaryActionOrange else SurfaceWhite,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, if (isSel) PrimaryActionOrange else BorderGrey),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 48.dp)
                                                    .testTag(if (tabIdx == 0) "broker_tab_has_client" else "broker_tab_has_property")
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        text = tabLabel,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSel) OnPrimaryActionColor else TextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (brokerActionTab == 0) {
                                        // Tab A — "Broker Has Client"
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                listOf("Lease", "Sale").forEach { reqOpt ->
                                                    FilterChip(
                                                        selected = brokerClientReqType.equals(reqOpt, ignoreCase = true),
                                                        onClick = { brokerClientReqType = reqOpt },
                                                        label = { Text(reqOpt, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                                    )
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    val parsed = GeminiRealEstateService().fallbackParseLeadsBatch(notes).firstOrNull()
                                                    if (parsed != null) {
                                                        brokerClientReqType = if (parsed.transactionType.contains("Buy", true) ||
                                                            parsed.transactionType.contains("Sale", true)
                                                        ) "Sale" else "Lease"
                                                        if (parsed.location.isNotBlank()) brokerClientLocation = parsed.location
                                                        if (parsed.budgetText.isNotBlank()) brokerClientBudget = parsed.budgetText
                                                        val sqftVal = parsed.maxCarpetSqFt ?: parsed.minCarpetSqFt
                                                        if (sqftVal != null && sqftVal > 0L) {
                                                            brokerClientAreaSqFt = sqftVal.toString()
                                                        } else {
                                                            val m = Regex("(\\d[\\d,.]*)\\s*(?:sq\\.?\\s*ft|sqft|sft)", RegexOption.IGNORE_CASE).find(notes)
                                                            if (m != null) brokerClientAreaSqFt = m.groupValues[1].replace(",", "")
                                                        }
                                                        Toast.makeText(context, "Auto-filled broker client requirement", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        val m = Regex("(\\d[\\d,.]*)\\s*(?:sq\\.?\\s*ft|sqft|sft)", RegexOption.IGNORE_CASE).find(notes)
                                                        if (m != null) brokerClientAreaSqFt = m.groupValues[1].replace(",", "")
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier
                                                    .heightIn(min = 44.dp)
                                                    .testTag("broker_autofill_note_btn")
                                            ) {
                                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = PrimaryActionOrange)
                                                Spacer(Modifier.width(4.dp))
                                                Text("Auto-Fill from Note", fontSize = 11.sp, color = TextPrimary)
                                            }
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            OutlinedTextField(
                                                value = brokerClientAreaSqFt,
                                                onValueChange = { raw -> brokerClientAreaSqFt = raw.filter { it.isDigit() || it == '-' }.take(12) },
                                                label = { Text("Area (sq.ft)") },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier
                                                    .weight(0.95f)
                                                    .testTag("broker_client_area_input")
                                            )
                                            OutlinedTextField(
                                                value = brokerClientBudget,
                                                onValueChange = { brokerClientBudget = it },
                                                label = { Text("Budget") },
                                                singleLine = true,
                                                modifier = Modifier
                                                    .weight(1.05f)
                                                    .testTag("broker_client_budget_input")
                                            )
                                        }

                                        SearchableDropdownField(
                                            label = "Location / Micro-Market",
                                            selectedValue = brokerClientLocation,
                                            options = TlcStandardOptions.puneLocations,
                                            onValueSelected = { brokerClientLocation = it },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Button(
                                            onClick = {
                                                val cleanPhone10 = ContactUtils.normalizePhoneNumber(workingCall.phoneNumber)
                                                val cleanAreaText = brokerClientAreaSqFt.trim().let {
                                                    if (it.isNotBlank()) "$it sq.ft." else ""
                                                }
                                                val reqDesc = buildString {
                                                    append("Broker Client ($brokerClientReqType)")
                                                    if (cleanAreaText.isNotBlank()) append(" • $cleanAreaText")
                                                    if (brokerClientLocation.isNotBlank()) append(" • $brokerClientLocation")
                                                    if (notes.isNotBlank()) append(" • ${notes.trim().take(120)}")
                                                }
                                                val areaNum = brokerClientAreaSqFt.filter { it.isDigit() }
                                                val newBrokerLead = CrmLead(
                                                    id = "CRM-CP-${System.currentTimeMillis().toString().takeLast(6)}",
                                                    name = "$brokerDisplayName (Client)",
                                                    phone = cleanPhone10,
                                                    clientType = "Broker / CP",
                                                    segment = "Commercial",
                                                    requirement = reqDesc,
                                                    requirementType = if (brokerClientReqType.equals("Sale", true)) "Buy" else "Lease",
                                                    carpetAreaRange = cleanAreaText,
                                                    minCarpetSqFt = areaNum,
                                                    maxCarpetSqFt = areaNum,
                                                    location = brokerClientLocation.ifBlank { "Pune" },
                                                    budget = brokerClientBudget.trim().ifBlank { "Negotiable" },
                                                    status = "New Lead",
                                                    verificationStatus = "Verified Live",
                                                    nextFollowUp = followUpDateYmd,
                                                    nextFollowUpTime = followUpTimeFormatted,
                                                    followUpTime = followUpTimeFormatted,
                                                    notes = notes.trim().ifBlank { reqDesc },
                                                    assignedTo = safeEmployeeName,
                                                    lastContactDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                                                    lastContactMode = "Call",
                                                    leadSource = "Broker / CP: $brokerDisplayName"
                                                )
                                                onSaveLeadAndSync(
                                                    newBrokerLead,
                                                    "Broker Client Lead",
                                                    reqDesc
                                                )
                                                brokerClientLeadSaved = true
                                                Toast.makeText(context, "Saved Broker Lead to Live CRM", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (brokerClientLeadSaved) WhatsAppBrandDarkGreen else PrimaryActionOrange,
                                                contentColor = OnPrimaryActionColor
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .testTag("broker_save_client_lead_btn")
                                        ) {
                                            Icon(
                                                imageVector = if (brokerClientLeadSaved) Icons.Default.CheckCircle else Icons.Default.PersonAdd,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = if (brokerClientLeadSaved) "Saved as Broker Lead ✓" else "Save as Broker Lead",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            OutlinedButton(
                                                onClick = { showBrokerMultiPicker = !showBrokerMultiPicker },
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 48.dp)
                                                    .testTag("broker_select_properties_multi_btn")
                                            ) {
                                                Text(
                                                    text = "Select Properties (${selectedBrokerPropertyIds.size})",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = TextPrimary
                                                )
                                            }
                                            Button(
                                                onClick = {
                                                    val chosen = inventory.filter { it.id in selectedBrokerPropertyIds }
                                                        .ifEmpty { brokerMatchedInventory.take(3) }
                                                    val body = buildString {
                                                        append("Hi $brokerDisplayName,\n\nSharing client-safe options from *The Leasing Company*:\n\n")
                                                        chosen.forEach { append(formatClientSafePropertySummary(it)).append("\n\n") }
                                                        append("Let us know for site visit.\n- $safeEmployeeName ($safeEmployeeMobile)")
                                                    }
                                                    CrmCallManager.openWhatsAppChat(context, workingCall.phoneNumber, body)
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = WhatsAppBrandGreen,
                                                    contentColor = Color.White
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .weight(1.1f)
                                                    .heightIn(min = 48.dp)
                                                    .testTag("broker_send_wa_options_btn")
                                            ) {
                                                Text("Send on Broker WA", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        AnimatedVisibility(visible = showBrokerMultiPicker) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                brokerMatchedInventory.take(8).forEach { prop ->
                                                    val checked = prop.id in selectedBrokerPropertyIds
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                selectedBrokerPropertyIds = if (checked) {
                                                                    selectedBrokerPropertyIds - prop.id
                                                                } else {
                                                                    selectedBrokerPropertyIds + prop.id
                                                                }
                                                            }
                                                    ) {
                                                        Checkbox(
                                                            checked = checked,
                                                            onCheckedChange = {
                                                                selectedBrokerPropertyIds = if (it) selectedBrokerPropertyIds + prop.id else selectedBrokerPropertyIds - prop.id
                                                            }
                                                        )
                                                        Text(
                                                            text = "${prop.projectName} • ${prop.location} (${CalculationUtils.formatIndianCurrency(prop.rent ?: prop.priceSale ?: prop.priceOrRent)})",
                                                            fontSize = 11.5.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Tab B — "Broker Has Property"
                                        Surface(
                                            color = SelectedSurfaceContainer,
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.35f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "Pre-fills Add Property as Broker / CP",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                                Text(
                                                    text = "POC: Broker • CP $brokerDisplayName • ${ContactUtils.normalizePhoneNumber(workingCall.phoneNumber).ifBlank { headerPhoneText }}",
                                                    fontSize = 11.sp,
                                                    color = SecondaryText
                                                )
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                val cleanPhone10 = ContactUtils.normalizePhoneNumber(workingCall.phoneNumber)
                                                val cpPrefixed = if (brokerDisplayName.startsWith("CP ", ignoreCase = true)) {
                                                    brokerDisplayName
                                                } else {
                                                    "CP $brokerDisplayName"
                                                }
                                                val noteClean = notes.trim()
                                                val savedRecord = workingCall.copy(
                                                    callerName = brokerDisplayName,
                                                    notes = noteClean.ifBlank { workingCall.notes },
                                                    assignedAgent = safeEmployeeName,
                                                    isLinked = true,
                                                    linkedOpportunityType = "Broker",
                                                    linkedOpportunityTitle = brokerDisplayName
                                                )
                                                CrmCallManager.addOrUpdateCall(context, savedRecord)
                                                CrmCallManager.saveBrokerContact(context, workingCall.phoneNumber, brokerDisplayName, brokerFirm.trim(), noteClean)
                                                CrmCallManager.unskipCall(context, savedRecord)
                                                CrmCallManager.markPostCallPopupShown(context, savedRecord)
                                                CrmCallManager.removeFromUnloggedQueue(context, savedRecord)
                                                if (onOpenCaptureBrokerUnit != null) {
                                                    onOpenCaptureBrokerUnit(cpPrefixed, cleanPhone10, noteClean)
                                                } else {
                                                    onOpenFullAddProperty(cleanPhone10)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = PrimaryActionOrange,
                                                contentColor = OnPrimaryActionColor
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .testTag("broker_capture_unit_btn")
                                        ) {
                                            Text("Capture Broker Unit →", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                PostCallRole.TENANT -> {
                                    if (match.matchedTenant == null) {
                                        SearchableDropdownField(
                                            label = "Tag Leased Property / Tenant",
                                            selectedValue = selectedTenantLabel,
                                            options = tenantOptions,
                                            onValueSelected = { selectedTenantLabel = it },
                                            allowAddNew = false,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        val t = match.matchedTenant!!
                                        Text(
                                            text = "Leased Unit: ${t.projectName} ${t.unitNumber.ifBlank { t.propertyCode }}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryActionOrange
                                        )
                                    }
                                    Text("Issue Category", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        TenantIssueCategory.entries.forEach { cat ->
                                            FilterChip(
                                                selected = selectedIssueCategory == cat,
                                                onClick = { selectedIssueCategory = cat },
                                                label = { Text(cat.label, fontSize = 10.5.sp) }
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        TenantIssueStatus.entries.forEach { st ->
                                            FilterChip(
                                                selected = selectedIssueStatus == st,
                                                onClick = { selectedIssueStatus = st },
                                                label = { Text(st.label, fontSize = 10.5.sp) }
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = {
                                                val ownerPhone = activeMatchedTenant?.ownerContact?.takeIf { it.isNotBlank() }
                                                if (ownerPhone == null) {
                                                    Toast.makeText(context, "Add Landlord/Owner phone on Tenant card to forward directly", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    val unitTitle = listOf(activeMatchedTenant?.projectName.orEmpty(), activeMatchedTenant?.unitNumber.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
                                                    val relay = "Hi ${activeMatchedTenant?.ownerName?.ifBlank { "Sir/Madam" } ?: "Sir/Madam"}, Tenant ${editableCallerName.ifBlank { activeMatchedTenant?.tenantName.orEmpty() }} ($unitTitle) reported a *${selectedIssueCategory.label}* request:\n\"${notes.ifBlank { "Needs attention" }}\"\nPlease advise.\n- $safeEmployeeName (TLC)"
                                                    CrmCallManager.openWhatsAppChat(context, ownerPhone, relay)
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Text("Forward to Owner", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val unitTitle = listOf(activeMatchedTenant?.projectName.orEmpty(), activeMatchedTenant?.unitNumber.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
                                                val msg = "Hi ${editableCallerName.ifBlank { "there" }}, we have logged your ${selectedIssueCategory.label} update for $unitTitle (${selectedIssueStatus.label}). We will keep you posted.\n- $safeEmployeeName (TLC)"
                                                CrmCallManager.openWhatsAppChat(context, workingCall.phoneNumber, msg)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Text("Message Tenant", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E7E45))
                                        }
                                    }
                                }

                                PostCallRole.DEVELOPER_POC -> {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        FilterChip(
                                            selected = !devIsSourcingManager,
                                            onClick = { devIsSourcingManager = false },
                                            label = { Text("Developer Sales (BLD -)", fontSize = 10.5.sp) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        FilterChip(
                                            selected = devIsSourcingManager,
                                            onClick = { devIsSourcingManager = true },
                                            label = { Text("Sourcing Mgr (SM -)", fontSize = 10.5.sp) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    SearchableDropdownField(
                                        label = "Link to Project",
                                        selectedValue = selectedProjectOption,
                                        options = projectOptions,
                                        onValueSelected = { selectedProjectOption = it },
                                        addNewButtonLabel = "+ Add New Project",
                                        onAddNewRequested = { typedQuery ->
                                            newProjectPrefillName = typedQuery
                                            showAddNewProjectSheet = true
                                        },
                                        emptySearchText = "No matching projects found",
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            val projTitle = selectedProjectOption.substringAfter(" • ", selectedProjectOption).ifBlank { "your project" }
                                            val msg = "Hi ${editableCallerName.ifBlank { "Sir/Madam" }}, please share the latest cost sheet, availability & CP payout structure for *$projTitle*.\n- $safeEmployeeName ($safeEmployeeMobile, TLC)"
                                            CrmCallManager.openWhatsAppChat(context, workingCall.phoneNumber, msg)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Text("Request Latest Price / Availability on WA", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E7E45))
                                    }
                                }

                                PostCallRole.OPS_VENDOR -> {
                                    Text("Vendor / Ops Sub-Role", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        BrokerVendorRecord.opsSubRoles.forEach { sr ->
                                            FilterChip(
                                                selected = opsSubRole == sr,
                                                onClick = { opsSubRole = sr },
                                                label = { Text(sr, fontSize = 10.5.sp) }
                                            )
                                        }
                                    }
                                    SearchableDropdownField(
                                        label = "Tag Project (Optional)",
                                        selectedValue = selectedProjectOption,
                                        options = projectOptions,
                                        onValueSelected = { selectedProjectOption = it },
                                        addNewButtonLabel = "+ Add New Project",
                                        onAddNewRequested = { typedQuery ->
                                            newProjectPrefillName = typedQuery
                                            showAddNewProjectSheet = true
                                        },
                                        emptySearchText = "No matching projects found",
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    SearchableDropdownField(
                                        label = "Tag Tenant / Unit Issue (Optional)",
                                        selectedValue = opsTargetTenantIssue,
                                        options = tenantOptions,
                                        onValueSelected = { opsTargetTenantIssue = it },
                                        allowAddNew = false,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            val target = opsTargetTenantIssue.ifBlank { selectedProjectOption }.ifBlank { "our site" }
                                            val msg = "Hi ${editableCallerName.ifBlank { opsSubRole }}, coordinating task for *$target*:\n${notes.ifBlank { "Please assist with inspection/work." }}\n- $safeEmployeeName ($safeEmployeeMobile)"
                                            CrmCallManager.openWhatsAppChat(context, workingCall.phoneNumber, msg)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Text("Send Unit & Task on WhatsApp", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E7E45))
                                    }
                                }

                                PostCallRole.FRESH_UNKNOWN -> Unit
                            }

                            // Batch 19: Compact "Handover to Team" WhatsApp button in Smart Role Actions bar for all 6 roles
                            if (activeRole != PostCallRole.FRESH_UNKNOWN) {
                                HorizontalDivider(color = BorderGrey.copy(alpha = 0.55f))
                                OutlinedButton(
                                    onClick = { showHandoverToTeamSheet = true },
                                    border = BorderStroke(1.2.dp, WhatsAppBrandGreen),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = WhatsAppBrandGreen.copy(alpha = 0.08f),
                                        contentColor = WhatsAppBrandDarkGreen
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("post_call_handover_to_team_btn")
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                                        contentDescription = "Handover to Team via WhatsApp",
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Handover to Team",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = WhatsAppBrandDarkGreen
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ================= STICKY BOTTOM 2-BUTTON BAR =================
            HorizontalDivider(color = BorderGrey, thickness = 0.8.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceWhite)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { commitPostCallChanges(redirectToForm = false) },
                    border = BorderStroke(1.dp, BorderGrey),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("post_call_sticky_save_btn")
                ) {
                    Text("Save", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                }

                Button(
                    onClick = { commitPostCallChanges(redirectToForm = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.35f)
                        .heightIn(min = 48.dp)
                        .testTag("post_call_sticky_add_details_btn")
                ) {
                    Text("Add More Details →", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }

    if (showHandoverToTeamSheet) {
        val handoverSummary = buildCurrentHandoverSummary()
        HandoverToTeamSheet(
            summary = handoverSummary,
            onDismiss = { showHandoverToTeamSheet = false },
            onConfirmSendHandover = { member, messageText ->
                val targetPhone = member.tenDigitMobile().ifBlank { member.mobile }
                CrmCallManager.openWhatsAppChat(context, targetPhone, messageText)
                showHandoverToTeamSheet = false
                val (savedCall, _) = commitPostCallChanges(
                    redirectToForm = false,
                    handoverTargetMemberName = member.name
                )
                val handedAt = savedCall.handedOverAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                val handoverRec = TeamHandoverRecord(
                    id = "ho_${savedCall.id}_$handedAt",
                    callId = savedCall.id,
                    assignedTo = member.name,
                    handedOverBy = safeEmployeeName,
                    targetMobile = handoverSummary.callerMobile10,
                    callerName = handoverSummary.callerName,
                    roleLabel = handoverSummary.roleLabel,
                    propertyOrProject = handoverSummary.propertyOrProject,
                    note = handoverSummary.callNote,
                    handedOverAt = handedAt,
                    status = "Pending Callback"
                )
                coroutineScope.launch(Dispatchers.IO) {
                    TeamHandoverStore.recordHandover(context, handoverRec)
                    com.example.data.SheetsRepository(context.applicationContext, startForegroundSync = false)
                        .pushCallLogToMasterSheet(savedCall)
                    if (selectedRole == PostCallRole.TENANT) {
                        val tRec = activeMatchedTenant
                        PendingLoopTaskStore.enqueueTenantOrDealHandoverConfirmOwner(
                            context = context,
                            entityId = tRec?.id ?: savedCall.id,
                            subjectLabel = handoverSummary.propertyOrProject.ifBlank { handoverSummary.callerName },
                            ownerName = tRec?.ownerName.orEmpty(),
                            ownerPhone = tRec?.ownerContact.orEmpty(),
                            agentName = safeEmployeeName,
                            inventory = inventory,
                            propertyHint = tRec?.propertyCode ?: handoverSummary.propertyOrProject
                        )
                    }
                }
            }
        )
    }

    if (showAddNewProjectSheet) {
        val defaultContactName = editableCallerName.trim().ifBlank {
            match.displayTitle.takeIf {
                it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("Unknown Caller", true)
            }.orEmpty()
        }
        val defaultContactPhone = CrmCallManager.last10Digits(workingCall.phoneNumber)
            .ifBlank { workingCall.phoneNumber.trim() }
        QuickAddNewProjectModal(
            initialProjectName = newProjectPrefillName,
            initialDivision = if (clientDivision.equals("Residential", true)) "Residential" else "Commercial",
            initialLocation = clientLocation.ifBlank { "Kharadi" },
            initialContactName = defaultContactName,
            initialContactNumber = defaultContactPhone,
            onDismiss = { showAddNewProjectSheet = false },
            onSaveProject = { name, division, location, developer, contactName, contactPhone ->
                coroutineScope.launch {
                    val created = withContext(Dispatchers.IO) {
                        com.example.data.projects.ProjectDirectoryStore.createWithSequentialCode(
                            context = context,
                            division = division,
                            name = name,
                            location = location,
                            developer = developer,
                            contactName = contactName,
                            contactPhone = contactPhone
                        )
                    }
                    localAddedProjects = listOf(created) + localAddedProjects.filterNot { it.id == created.id }
                    selectedProjectOption = "${created.id} • ${created.name}"
                    if (editableCallerName.isBlank() && contactName.isNotBlank()) {
                        editableCallerName = contactName
                    }
                    CrmCallManager.saveProjectPocLink(
                        context = context,
                        phone = contactPhone.ifBlank { workingCall.phoneNumber },
                        projectId = created.id,
                        projectName = created.name,
                        division = created.division,
                        contactName = contactName.ifBlank { editableCallerName.ifBlank { created.name } }
                    )
                    if (onProjectCreated != null) {
                        onProjectCreated(created)
                    } else {
                        launch(Dispatchers.IO) {
                            com.example.data.SheetsRepository(context.applicationContext, startForegroundSync = false)
                                .pushDirectoryProjectToMasterSheet(created)
                        }
                    }
                    showAddNewProjectSheet = false
                    Toast.makeText(
                        context,
                        "Created ${created.id} • ${created.name} & queued Sheet sync",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddNewProjectModal(
    initialProjectName: String,
    initialDivision: String,
    initialLocation: String,
    initialContactName: String,
    initialContactNumber: String,
    onDismiss: () -> Unit,
    onSaveProject: (
        name: String,
        division: String,
        location: String,
        developer: String,
        contactName: String,
        contactPhone: String
    ) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var projectName by remember(initialProjectName) { mutableStateOf(initialProjectName) }
    var division by remember(initialDivision) { mutableStateOf(initialDivision) }
    var location by remember(initialLocation) { mutableStateOf(initialLocation) }
    var developerName by remember { mutableStateOf("") }
    var contactName by remember(initialContactName) { mutableStateOf(initialContactName) }
    var contactNumber by remember(initialContactNumber) { mutableStateOf(initialContactNumber) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundSoft,
        contentColor = TextPrimary,
        modifier = Modifier.testTag("quick_add_new_project_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add New Project",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Saves locally immediately & syncs next CP/RP code to Master Sheet",
                        fontSize = 11.sp,
                        color = SecondaryText
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                }
            }

            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it },
                label = { Text("Project Name *") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new_project_name_input")
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Division", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Commercial", "Residential").forEach { divOption ->
                        val selected = division.equals(divOption, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = { division = divOption },
                            label = { Text(divOption, fontWeight = FontWeight.SemiBold) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("new_project_div_${divOption.lowercase(Locale.US)}")
                        )
                    }
                }
            }

            SearchableDropdownField(
                label = "Micro-Market / Location",
                selectedValue = location,
                options = TlcStandardOptions.puneLocations,
                onValueSelected = { location = it },
                allowAddNew = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = developerName,
                onValueChange = { developerName = it },
                label = { Text("Developer Name") },
                placeholder = { Text("e.g. Panchshil Realty, Gera Developments") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("new_project_developer_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = contactName,
                    onValueChange = { contactName = it },
                    label = { Text("Contact Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("new_project_contact_name_input")
                )
                OutlinedTextField(
                    value = contactNumber,
                    onValueChange = { raw -> contactNumber = raw.filter { it.isDigit() || it == '+' }.take(13) },
                    label = { Text("Contact Number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("new_project_contact_phone_input")
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(0.9f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Button(
                    onClick = {
                        onSaveProject(
                            projectName.trim(),
                            division,
                            location.trim(),
                            developerName.trim(),
                            contactName.trim(),
                            contactNumber.trim()
                        )
                    },
                    enabled = projectName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .heightIn(min = 48.dp)
                        .testTag("new_project_save_btn")
                ) {
                    Text("Save & Link Project", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
