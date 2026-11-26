package com.example.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.crm.ActiveCallActionContext
import com.example.crm.CallActionSessionManager
import com.example.crm.CrmCallManager
import com.example.crm.PinnedCallMiniAudioBar
import com.example.data.OfflineDiskCache
import com.example.model.*
import com.example.ui.components.SearchableDropdownField
import com.example.ui.theme.*
import java.util.Calendar
import java.util.Locale
import java.util.UUID

@Composable
internal fun TenantEditorDialog(
    initialTenant: OfflineDiskCache.TenantCacheRecord,
    inventory: List<PropertyItem>,
    activeCallContext: ActiveCallActionContext? = null,
    onDismiss: () -> Unit,
    onSave: (OfflineDiskCache.TenantCacheRecord) -> Unit
) {
    val context = LocalContext.current
    var tenantName by remember(initialTenant.id) { mutableStateOf(initialTenant.tenantName) }
    var companyName by remember(initialTenant.id) { mutableStateOf(initialTenant.companyName) }
    var contactNumber by remember(initialTenant.id) { mutableStateOf(initialTenant.contactNumber) }
    var tenantEmail by remember(initialTenant.id) { mutableStateOf(initialTenant.tenantEmail) }
    var division by remember(initialTenant.id) { mutableStateOf(initialTenant.division.ifBlank { "Commercial" }) }
    var propertyCode by remember(initialTenant.id) { mutableStateOf(initialTenant.propertyCode) }
    var projectName by remember(initialTenant.id) { mutableStateOf(initialTenant.projectName) }
    var unitNumber by remember(initialTenant.id) { mutableStateOf(initialTenant.unitNumber) }
    var location by remember(initialTenant.id) { mutableStateOf(initialTenant.location) }
    var monthlyRent by remember(initialTenant.id) { mutableStateOf(initialTenant.monthlyRent) }
    var depositAmount by remember(initialTenant.id) { mutableStateOf(initialTenant.depositAmount) }
    var escalationPercent by remember(initialTenant.id) { mutableStateOf(initialTenant.escalationPercent.ifBlank { "5" }) }
    var lockInPeriod by remember(initialTenant.id) { mutableStateOf(initialTenant.lockInPeriod.ifBlank { "12 Months" }) }
    var leaseStartDate by remember(initialTenant.id) { mutableStateOf(initialTenant.leaseStartDate) }
    var leaseExpiryDate by remember(initialTenant.id) { mutableStateOf(initialTenant.leaseExpiryDate) }
    var status by remember(initialTenant.id) { mutableStateOf(initialTenant.status.ifBlank { "Active" }) }
    var ownerName by remember(initialTenant.id) { mutableStateOf(initialTenant.ownerName) }
    var ownerContact by remember(initialTenant.id) { mutableStateOf(initialTenant.ownerContact) }
    var notes by remember(initialTenant.id) { mutableStateOf(initialTenant.notes) }

    val propOptions = remember(inventory) {
        inventory.take(150).map { p ->
            val u = p.unitNumber.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            "${p.propertyCode} • ${p.projectName}$u"
        }
    }
    var selectedPropOption by remember(initialTenant.id) { mutableStateOf("") }

    val showDatePicker: ((String) -> Unit) -> Unit = { onPicked ->
        val cal = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, day ->
                onPicked(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .testTag("tenant_editor_dialog")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (initialTenant.tenantName.isBlank()) "Add Tenant / Lease" else "Edit Tenant & Lease",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = SecondaryText)
                    }
                }
                HorizontalDivider(color = BorderGrey)

                // Audio playback remains strictly inline on CallLogActionScreen; activeCallContext stays active for Auto-Link on Save.

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (propOptions.isNotEmpty()) {
                        SearchableDropdownField(
                            label = "Quick Fill from Inventory Unit (Optional)",
                            selectedValue = selectedPropOption,
                            options = propOptions,
                            onValueSelected = { picked ->
                                selectedPropOption = picked
                                val code = picked.substringBefore(" •").trim()
                                val match = inventory.firstOrNull { it.propertyCode.equals(code, true) }
                                if (match != null) {
                                    propertyCode = match.propertyCode
                                    projectName = match.projectName
                                    unitNumber = match.unitNumber
                                    location = match.location
                                    division = if (match.isResidential()) "Residential" else "Commercial"
                                    if (monthlyRent.isBlank() && (match.rent ?: 0.0) > 0.0) {
                                        monthlyRent = match.rent?.toLong()?.toString().orEmpty()
                                    }
                                    if (ownerName.isBlank()) ownerName = match.ownerName.orEmpty()
                                    if (ownerContact.isBlank()) ownerContact = match.ownerContact.orEmpty()
                                }
                            },
                            allowAddNew = false,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Commercial", "Residential").forEach { div ->
                            FilterChip(
                                selected = division.equals(div, true),
                                onClick = { division = div },
                                label = { Text(div, fontSize = 11.5.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = tenantName,
                        onValueChange = { tenantName = it },
                        label = { Text("Tenant Name *") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tenant_name_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = contactNumber,
                            onValueChange = { contactNumber = it },
                            label = { Text("Tenant Phone *") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = companyName,
                            onValueChange = { companyName = it },
                            label = { Text("Company / Brand") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = tenantEmail,
                        onValueChange = { tenantEmail = it },
                        label = { Text("Tenant Email (Optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = projectName,
                            onValueChange = { projectName = it },
                            label = { Text("Project / Building") },
                            singleLine = true,
                            modifier = Modifier.weight(1.3f)
                        )
                        OutlinedTextField(
                            value = unitNumber,
                            onValueChange = { unitNumber = it },
                            label = { Text("Unit No.") },
                            singleLine = true,
                            modifier = Modifier.weight(0.7f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = propertyCode,
                            onValueChange = { propertyCode = it },
                            label = { Text("Property Code") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = location,
                            onValueChange = { location = it },
                            label = { Text("Micro-Market") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = monthlyRent,
                            onValueChange = { monthlyRent = it },
                            label = { Text("Monthly Rent (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = depositAmount,
                            onValueChange = { depositAmount = it },
                            label = { Text("Security Deposit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = escalationPercent,
                            onValueChange = { escalationPercent = it },
                            label = { Text("Escalation %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(0.9f)
                        )
                        OutlinedTextField(
                            value = lockInPeriod,
                            onValueChange = { lockInPeriod = it },
                            label = { Text("Lock-in Period") },
                            singleLine = true,
                            modifier = Modifier.weight(1.1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = leaseStartDate,
                            onValueChange = { leaseStartDate = it },
                            label = { Text("Lease Start (YYYY-MM-DD)") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showDatePicker { leaseStartDate = it } }) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = "Pick start date", modifier = Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = leaseExpiryDate,
                            onValueChange = { leaseExpiryDate = it },
                            label = { Text("Lease Expiry (YYYY-MM-DD)") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showDatePicker { leaseExpiryDate = it } }) {
                                    Icon(Icons.Default.Event, contentDescription = "Pick expiry date", modifier = Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("Lease Status", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Active", "Notice Period", "Renewed", "Vacated").forEach { st ->
                            FilterChip(
                                selected = status.equals(st, true),
                                onClick = { status = st },
                                label = { Text(st, fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ownerName,
                            onValueChange = { ownerName = it },
                            label = { Text("Landlord / Owner Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = ownerContact,
                            onValueChange = { ownerContact = it },
                            label = { Text("Owner Phone") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Lease Notes & Terms") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(color = BorderGrey)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Cancel", color = TextPrimary)
                    }
                    Button(
                        onClick = {
                            val cleanId = initialTenant.id.ifBlank {
                                "TNT-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}"
                            }
                            onSave(
                                initialTenant.copy(
                                    id = cleanId,
                                    tenantName = tenantName.trim().ifBlank { "Tenant" },
                                    companyName = companyName.trim(),
                                    contactNumber = contactNumber.trim(),
                                    tenantEmail = tenantEmail.trim(),
                                    division = division,
                                    propertyCode = propertyCode.trim(),
                                    projectName = projectName.trim(),
                                    unitNumber = unitNumber.trim(),
                                    location = location.trim(),
                                    monthlyRent = monthlyRent.trim(),
                                    depositAmount = depositAmount.trim(),
                                    escalationPercent = escalationPercent.trim(),
                                    lockInPeriod = lockInPeriod.trim(),
                                    leaseStartDate = leaseStartDate.trim(),
                                    leaseExpiryDate = leaseExpiryDate.trim(),
                                    status = status,
                                    ownerName = ownerName.trim(),
                                    ownerContact = ownerContact.trim(),
                                    notes = notes.trim()
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                        modifier = Modifier
                            .weight(1.4f)
                            .heightIn(min = 48.dp)
                            .testTag("tenant_save_btn")
                    ) {
                        Text("Save & Sync Lease", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TenantMoveInChecklistDialog(
    tenant: OfflineDiskCache.TenantCacheRecord,
    onDismiss: () -> Unit,
    onSaveChecklist: (TenantMoveInChecklist) -> Unit
) {
    var checklist by remember(tenant.id) { mutableStateOf(tenant.parsedChecklist()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        title = {
            Column {
                Text("Move-in Checklist", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                Text(
                    "${tenant.tenantName} • ${checklist.completedCount()}/5 Complete",
                    fontSize = 12.sp,
                    color = SecondaryText
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val items = listOf(
                    Triple("Agreement Signed", checklist.agreementSigned) { checked: Boolean ->
                        checklist = checklist.copy(agreementSigned = checked)
                    },
                    Triple("Police Verification", checklist.policeVerification) { checked: Boolean ->
                        checklist = checklist.copy(policeVerification = checked)
                    },
                    Triple("Society NOC", checklist.societyNoc) { checked: Boolean ->
                        checklist = checklist.copy(societyNoc = checked)
                    },
                    Triple("Keys Handed Over", checklist.keysHandedOver) { checked: Boolean ->
                        checklist = checklist.copy(keysHandedOver = checked)
                    },
                    Triple("Inventory List Signed", checklist.inventoryListSigned) { checked: Boolean ->
                        checklist = checklist.copy(inventoryListSigned = checked)
                    }
                )
                items.forEach { (label, checked, onToggle) ->
                    Surface(
                        onClick = { onToggle(!checked) },
                        color = if (checked) StatusSuccessGreen.copy(alpha = 0.08f) else SurfaceMuted,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (checked) StatusSuccessGreen else BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Checkbox(checked = checked, onCheckedChange = onToggle)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveChecklist(checklist) },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor)
            ) {
                Text("Save Checklist", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SecondaryText)
            }
        }
    )
}

@Composable
internal fun TenantLogIssueDialog(
    tenant: OfflineDiskCache.TenantCacheRecord,
    reportedBy: String,
    onDismiss: () -> Unit,
    onSaveIssue: (TenantIssueRecord, Boolean) -> Unit
) {
    var category by remember { mutableStateOf(TenantIssueCategory.MAINTENANCE) }
    var status by remember { mutableStateOf(TenantIssueStatus.OPEN) }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        title = {
            Column {
                Text("Log Tenant Issue", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                Text("${tenant.tenantName} • ${tenant.projectName}", fontSize = 12.sp, color = SecondaryText)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Issue Category", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TenantIssueCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.label, fontSize = 11.sp) }
                        )
                    }
                }
                Text("Status", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TenantIssueStatus.entries.forEach { st ->
                        FilterChip(
                            selected = status == st,
                            onClick = { status = st },
                            label = { Text(st.label, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Issue Details / Owner Message") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tenant.ownerContact.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            onSaveIssue(
                                TenantIssueRecord(
                                    tenantId = tenant.id,
                                    propertyCode = tenant.propertyCode,
                                    category = category,
                                    status = TenantIssueStatus.WAITING_ON_OWNER,
                                    description = description.trim().ifBlank { "${category.label} request" },
                                    reportedBy = reportedBy
                                ),
                                true
                            )
                        }
                    ) {
                        Text("Save & WA Owner", fontSize = 11.5.sp, color = Color(0xFF1E7E45), fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = {
                        onSaveIssue(
                            TenantIssueRecord(
                                tenantId = tenant.id,
                                propertyCode = tenant.propertyCode,
                                category = category,
                                status = status,
                                description = description.trim().ifBlank { "${category.label} logged" },
                                reportedBy = reportedBy
                            ),
                            false
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor)
                ) {
                    Text("Save Issue", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = SecondaryText) }
        }
    )
}

@Composable
internal fun BrokerVendorEditorDialog(
    initialRecord: BrokerVendorRecord,
    activeCallContext: ActiveCallActionContext? = null,
    onDismiss: () -> Unit,
    onSave: (BrokerVendorRecord) -> Unit
) {
    var name by remember(initialRecord.id) { mutableStateOf(initialRecord.name) }
    var phone by remember(initialRecord.id) { mutableStateOf(initialRecord.phone) }
    var roleCategory by remember(initialRecord.id) {
        mutableStateOf(
            if (initialRecord.roleCategory.contains("Ops", true) || initialRecord.roleCategory.contains("Vendor", true)) {
                "Ops / Vendor"
            } else {
                "Broker / CP"
            }
        )
    }
    var subRole by remember(initialRecord.id) {
        mutableStateOf(
            initialRecord.subRole.ifBlank {
                if (roleCategory == "Broker / CP") "Channel Partner" else BrokerVendorRecord.opsSubRoles.first()
            }
        )
    }
    var firmName by remember(initialRecord.id) { mutableStateOf(initialRecord.firmName) }
    var operatingAreas by remember(initialRecord.id) { mutableStateOf(initialRecord.operatingAreas) }
    var notes by remember(initialRecord.id) { mutableStateOf(initialRecord.notes) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .testTag("broker_vendor_editor_dialog")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (initialRecord.name.isBlank()) "Add Broker / Vendor" else "Edit Partner / Vendor",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = SecondaryText)
                    }
                }

                // Audio playback remains strictly inline on CallLogActionScreen; activeCallContext stays active for Auto-Link on Save.

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Broker / CP", "Ops / Vendor").forEach { cat ->
                        FilterChip(
                            selected = roleCategory == cat,
                            onClick = {
                                roleCategory = cat
                                subRole = if (cat == "Broker / CP") "Channel Partner" else BrokerVendorRecord.opsSubRoles.first()
                            },
                            label = { Text(cat, fontSize = 11.5.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (roleCategory == "Ops / Vendor") {
                    Text("Ops / Vendor Sub-Role", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BrokerVendorRecord.opsSubRoles.forEach { sr ->
                            FilterChip(
                                selected = subRole == sr,
                                onClick = { subRole = sr },
                                label = { Text(sr, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Contact Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = firmName,
                    onValueChange = { firmName = it },
                    label = { Text("Firm / Agency / Company") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = operatingAreas,
                    onValueChange = { operatingAreas = it },
                    label = { Text("Operating Areas / Micro-Markets") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes / Specialization") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) { Text("Cancel", color = TextPrimary) }
                    Button(
                        onClick = {
                            onSave(
                                initialRecord.copy(
                                    name = name.trim().ifBlank { "Partner" },
                                    phone = phone.trim(),
                                    roleCategory = roleCategory,
                                    subRole = subRole,
                                    firmName = firmName.trim(),
                                    operatingAreas = operatingAreas.trim(),
                                    notes = notes.trim()
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                        modifier = Modifier
                            .weight(1.3f)
                            .heightIn(min = 48.dp)
                    ) { Text("Save Partner", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
