package com.example.ui.components

import android.app.DatePickerDialog
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.loi.IndianCurrencyWords
import com.example.loi.LoiDraftData
import com.example.loi.LoiDraftFactory
import com.example.loi.LoiPdfGenerator
import com.example.model.PropertyItem
import com.example.ui.screens.CrmLead
import com.example.ui.screens.displayClientTitle
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoiBuilderBottomSheet(
    initialDraft: LoiDraftData,
    availableLeads: List<CrmLead> = emptyList(),
    availableProperties: List<PropertyItem> = emptyList(),
    onDismiss: () -> Unit,
    onPdfShared: ((LoiDraftData) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }
    var isGenerating by remember { mutableStateOf(false) }
    var expandOptionalScope by remember {
        mutableStateOf(
            initialDraft.ownerScopeOfWork.isNotBlank() ||
                initialDraft.tenantScopeOfWork.isNotBlank() ||
                initialDraft.furnitureListAnnexureA.isNotBlank()
        )
    }
    var expandClauses by remember { mutableStateOf(false) }
    var showLeadPicker by remember { mutableStateOf(false) }
    var showPropertyPicker by remember { mutableStateOf(false) }

    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH) }
    fun pickDate(onPicked: (String) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, y, m, d ->
                val picked = Calendar.getInstance().apply { set(y, m, d) }
                onPicked(dateFormatter.format(picked.time))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Scaffold(
            containerColor = BackgroundSoft,
            topBar = {
                Surface(
                    color = TextPrimary,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Official TLC LOI Generator",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Auto-filled A4 Branded PDF • Draft underlines for blank KYC",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = { draft = initialDraft },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("loi_refill_defaults_btn")
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Re-Fill", color = PrimaryActionOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("loi_builder_close_btn")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close LOI Builder", tint = Color.White)
                        }
                    }
                }
            },
            bottomBar = {
                Surface(
                    color = SurfaceWhite,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(0.75f)
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Cancel", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = {
                                if (isGenerating) return@Button
                                isGenerating = true
                                coroutineScope.launch {
                                    try {
                                        val pdfFile = LoiPdfGenerator.generatePdfFile(context, draft)
                                        val shareIntent = LoiPdfGenerator.buildShareIntent(context, pdfFile, draft)
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Official TLC LOI PDF"))
                                        onPdfShared?.invoke(draft)
                                        Toast.makeText(context, "Generated ${pdfFile.name}", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "PDF failed: ${e.message ?: "Unknown error"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        isGenerating = false
                                    }
                                }
                            },
                            enabled = !isGenerating,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryActionOrange,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1.65f)
                                .heightIn(min = 48.dp)
                                .testTag("loi_generate_share_pdf_btn")
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Rendering Branded PDF...", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            } else {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Generate & Share LOI PDF", fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp)
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1-Tap Quick-Fill Helpers from Linked CRM Lead / Inventory
                if (availableLeads.isNotEmpty() || availableProperties.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (availableLeads.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { showLeadPicker = true },
                                border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("loi_pick_crm_lead_btn")
                            ) {
                                Icon(Icons.Default.PersonSearch, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Auto-Fill Client", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                            }
                        }
                        if (availableProperties.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { showPropertyPicker = true },
                                border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("loi_pick_property_btn")
                            ) {
                                Icon(Icons.Default.Apartment, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Auto-Fill Unit", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                            }
                        }
                    }
                }

                // SECTION 1: PARTIES & LICENSEE LEGAL / KYC
                LoiSectionCard(title = "1. Parties & Licensee Legal / KYC") {
                    Text(
                        text = "Leave optional KYC fields blank to print clean \"__________________\" underlines for Draft LOIs.",
                        fontSize = 11.sp,
                        color = SecondaryText
                    )
                    OutlinedTextField(
                        value = draft.licensorName,
                        onValueChange = { draft = draft.copy(licensorName = it) },
                        label = { Text("Licensor Name (Owner / Landlord)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("loi_licensor_input")
                    )
                    OutlinedTextField(
                        value = draft.licenseeName,
                        onValueChange = { draft = draft.copy(licenseeName = it) },
                        label = { Text("Licensee Name (Company / Tenant)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("loi_licensee_input")
                    )
                    OutlinedTextField(
                        value = draft.licenseeRegisteredAddress,
                        onValueChange = { draft = draft.copy(licenseeRegisteredAddress = it) },
                        label = { Text("Registered Address (Optional)") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.licenseeAuthorizedRep,
                            onValueChange = { draft = draft.copy(licenseeAuthorizedRep = it) },
                            label = { Text("Authorized Rep") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.licenseeDesignation,
                            onValueChange = { draft = draft.copy(licenseeDesignation = it) },
                            label = { Text("Designation") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.licenseePan,
                            onValueChange = { draft = draft.copy(licenseePan = it.uppercase(Locale.US)) },
                            label = { Text("PAN") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.licenseeGst,
                            onValueChange = { draft = draft.copy(licenseeGst = it.uppercase(Locale.US)) },
                            label = { Text("GST") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.licenseeCin,
                            onValueChange = { draft = draft.copy(licenseeCin = it.uppercase(Locale.US)) },
                            label = { Text("CIN") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.boardResolutionDate,
                            onValueChange = { draft = draft.copy(boardResolutionDate = it) },
                            label = { Text("Board Res. Date") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = { pickDate { draft = draft.copy(boardResolutionDate = it) } },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = "Pick Board Resolution Date", tint = PrimaryActionOrange)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // SECTION 2: PREMISES & PARKING
                LoiSectionCard(title = "2. Premises & Parking") {
                    OutlinedTextField(
                        value = draft.propertyName,
                        onValueChange = { draft = draft.copy(propertyName = it) },
                        label = { Text("Property / Building Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.unitNumber,
                            onValueChange = { draft = draft.copy(unitNumber = it) },
                            label = { Text("Unit No.") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.wing,
                            onValueChange = { draft = draft.copy(wing = it) },
                            label = { Text("Wing / Tower") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.floor,
                            onValueChange = { draft = draft.copy(floor = it) },
                            label = { Text("Floor") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.locality,
                            onValueChange = { draft = draft.copy(locality = it) },
                            label = { Text("Locality / Micro-Market") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.cityState,
                            onValueChange = { draft = draft.copy(cityState = it) },
                            label = { Text("City / State") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.carpetAreaSqFt,
                            onValueChange = { draft = draft.copy(carpetAreaSqFt = it.filter { ch -> ch.isDigit() || ch == '.' }) },
                            label = { Text("Carpet (sq.ft)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.builtUpAreaSqFt,
                            onValueChange = { draft = draft.copy(builtUpAreaSqFt = it.filter { ch -> ch.isDigit() || ch == '.' }) },
                            label = { Text("Built-up (sq.ft)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = draft.furnishing,
                        onValueChange = { draft = draft.copy(furnishing = it) },
                        label = { Text("Furnishing Status") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.parking4Wheeler,
                            onValueChange = { draft = draft.copy(parking4Wheeler = it) },
                            label = { Text("4-Wheeler") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.parking2Wheeler,
                            onValueChange = { draft = draft.copy(parking2Wheeler = it) },
                            label = { Text("2-Wheeler") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.parkingVisitor,
                            onValueChange = { draft = draft.copy(parkingVisitor = it) },
                            label = { Text("Visitor") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // SECTION 3: FINANCIALS (WITH AUTO INR WORDS PREVIEW)
                LoiSectionCard(title = "3. Financials & Lock-in") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.monthlyFeeInr,
                            onValueChange = { draft = draft.copy(monthlyFeeInr = it) },
                            label = { Text("Monthly License Fee (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("loi_monthly_fee_input")
                        )
                        OutlinedTextField(
                            value = draft.securityDepositInr,
                            onValueChange = { draft = draft.copy(securityDepositInr = it) },
                            label = { Text("Security Deposit (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("loi_security_deposit_input")
                        )
                    }

                    // Quick 3x / 6x Deposit helpers + Live Indian Words Preview
                    val monthlyNum = draft.monthlyFeeDouble()
                    if (monthlyNum > 0.0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Quick Deposit:", fontSize = 11.sp, color = SecondaryText, fontWeight = FontWeight.SemiBold)
                            listOf(3, 4, 6).forEach { mult ->
                                FilterChip(
                                    selected = draft.securityDepositDouble().roundToLong() == (monthlyNum * mult).roundToLong(),
                                    onClick = {
                                        draft = draft.copy(securityDepositInr = (monthlyNum * mult).roundToLong().toString())
                                    },
                                    label = { Text("${mult}x Rent", fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    Surface(
                        color = SelectedSurfaceContainer,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.8.dp, PrimaryActionOrange.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "Fee: ${IndianCurrencyWords.formatRsWithWords(draft.monthlyFeeDouble())}",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Deposit: ${IndianCurrencyWords.formatRsWithWords(draft.securityDepositDouble())}",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryActionOrange
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.lockInMonths,
                            onValueChange = { draft = draft.copy(lockInMonths = it.filter { ch -> ch.isDigit() }) },
                            label = { Text("Lock-in (Months)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.escalationPercentYoy,
                            onValueChange = { draft = draft.copy(escalationPercentYoy = it.filter { ch -> ch.isDigit() || ch == '.' }) },
                            label = { Text("Escalation (% YOY)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = draft.maintenanceTerms,
                        onValueChange = { draft = draft.copy(maintenanceTerms = it) },
                        label = { Text("Maintenance Terms") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // SECTION 4: LOI DATES & TENURE
                LoiSectionCard(title = "4. LOI Dates & Tenure Parameters") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.loiDate,
                            onValueChange = { draft = draft.copy(loiDate = it) },
                            label = { Text("LOI Date") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = { pickDate { draft = draft.copy(loiDate = it) } },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = "Pick LOI Date", tint = PrimaryActionOrange)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.place,
                            onValueChange = { draft = draft.copy(place = it) },
                            label = { Text("Place") },
                            singleLine = true,
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.agreementStartDate,
                            onValueChange = { draft = draft.copy(agreementStartDate = it) },
                            label = { Text("Agreement Start Date") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.handoverDate,
                            onValueChange = { draft = draft.copy(handoverDate = it) },
                            label = { Text("Handover Date") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.monthlyFeeStartDate,
                            onValueChange = { draft = draft.copy(monthlyFeeStartDate = it) },
                            label = { Text("Monthly Fee Start") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.loiValidityDate,
                            onValueChange = { draft = draft.copy(loiValidityDate = it) },
                            label = { Text("LOI Validity Date") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.agreementDurationMonths,
                            onValueChange = { draft = draft.copy(agreementDurationMonths = it.filter { ch -> ch.isDigit() }) },
                            label = { Text("Duration (Mos)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.noticePeriodMonths,
                            onValueChange = { draft = draft.copy(noticePeriodMonths = it.filter { ch -> ch.isDigit() }) },
                            label = { Text("Notice (Mos)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.daysToSignAgreement,
                            onValueChange = { draft = draft.copy(daysToSignAgreement = it.filter { ch -> ch.isDigit() }) },
                            label = { Text("Sign Days") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.9f)
                        )
                    }
                }

                // SECTION 5: UTILITIES & CHARGES
                LoiSectionCard(title = "5. Utilities & Statutory Charges") {
                    Text("Internet Service & Operating Hours", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = SecondaryText)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("No", "Yes").forEach { opt ->
                            FilterChip(
                                selected = draft.internetService.equals(opt, ignoreCase = true),
                                onClick = { draft = draft.copy(internetService = opt) },
                                label = { Text("Internet: $opt", fontSize = 11.5.sp) }
                            )
                        }
                        listOf("24/7 Access", "Society Norms").forEach { hrs ->
                            FilterChip(
                                selected = draft.operatingHours.equals(hrs, ignoreCase = true),
                                onClick = { draft = draft.copy(operatingHours = hrs) },
                                label = { Text(hrs, fontSize = 11.5.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.electricityTerms,
                        onValueChange = { draft = draft.copy(electricityTerms = it) },
                        label = { Text("Electricity Charges") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.maintenanceFeePerSqFtBua,
                        onValueChange = { draft = draft.copy(maintenanceFeePerSqFtBua = it) },
                        label = { Text("Maintenance Fee (INR/sq.ft on BUA)") },
                        placeholder = { Text("e.g. Rs. 8.50 / sq.ft. on BUA") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.softServicesAmenities,
                        onValueChange = { draft = draft.copy(softServicesAmenities = it) },
                        label = { Text("Soft Services / Amenities") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.additionalParkingFee,
                        onValueChange = { draft = draft.copy(additionalParkingFee = it) },
                        label = { Text("Additional Parking Fee") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.stampDutyRegistration,
                        onValueChange = { draft = draft.copy(stampDutyRegistration = it) },
                        label = { Text("Stamp Duty & Registration") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // SECTION 6: EDITABLE STANDARD CLAUSES
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandClauses = !expandClauses }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("6. Standard Clauses (Pre-loaded & Editable)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Termination & Default • Force Majeure • Binding & TDS/Tax", fontSize = 11.sp, color = SecondaryText)
                            }
                            Icon(
                                imageVector = if (expandClauses) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle Standard Clauses",
                                tint = PrimaryActionOrange
                            )
                        }
                        AnimatedVisibility(visible = expandClauses) {
                            Column(
                                modifier = Modifier.padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = draft.terminationDefaultClause,
                                    onValueChange = { draft = draft.copy(terminationDefaultClause = it) },
                                    label = { Text("Termination & Default Terms") },
                                    maxLines = 6,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = draft.forceMajeureClause,
                                    onValueChange = { draft = draft.copy(forceMajeureClause = it) },
                                    label = { Text("Force Majeure Clause") },
                                    maxLines = 6,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = draft.bindingAndTaxClause,
                                    onValueChange = { draft = draft.copy(bindingAndTaxClause = it) },
                                    label = { Text("Binding Agreement, TDS & Property Tax") },
                                    maxLines = 6,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // SECTION 7: OPTIONAL SCOPE OF WORK & FURNITURE ANNEXURE A
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandOptionalScope = !expandOptionalScope }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("7. Optional Scope of Work & Annexure A", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Owner Scope • Tenant Scope • Furniture List (Annexure A)", fontSize = 11.sp, color = SecondaryText)
                            }
                            Icon(
                                imageVector = if (expandOptionalScope) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle Scope & Furniture",
                                tint = PrimaryActionOrange
                            )
                        }
                        AnimatedVisibility(visible = expandOptionalScope) {
                            Column(
                                modifier = Modifier.padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = draft.ownerScopeOfWork,
                                    onValueChange = { draft = draft.copy(ownerScopeOfWork = it) },
                                    label = { Text("Owner Scope of Work (Optional)") },
                                    placeholder = { Text("e.g. Repainting, AC servicing & electrical earthing check prior to handover") },
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = draft.tenantScopeOfWork,
                                    onValueChange = { draft = draft.copy(tenantScopeOfWork = it) },
                                    label = { Text("Tenant Scope of Work (Optional)") },
                                    placeholder = { Text("e.g. Custom workstations, LAN cabling & interior branding") },
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = draft.furnitureListAnnexureA,
                                    onValueChange = { draft = draft.copy(furnitureListAnnexureA = it) },
                                    label = { Text("Furniture List (Annexure A — Optional)") },
                                    placeholder = { Text("e.g. 25 Linear Workstations, 2 MD Cabins, 1 Conference Table, 3 Split ACs") },
                                    maxLines = 5,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // SECTION 8: SEPARATE BROKERAGE PAGE TOGGLES
                val slabRuleText = "${draft.brokerageMonthsSlab()} month's rent (${if (draft.durationMonthsInt() > 36) "> 3 years" else "up to 3 years"})"
                val brokTotalPreview = IndianCurrencyWords.formatRsWithWords(draft.computedBrokerageInr(), "As per Monthly Rent")
                LoiSectionCard(title = "8. Separate Brokerage Confirmation Pages") {
                    Text(
                        text = "Standard Slab: 1 month's rent up to 3 years; 2 months' rent above 3 years. Current (${draft.durationMonthsInt()} mos): $slabRuleText → $brokTotalPreview",
                        fontSize = 11.5.sp,
                        color = SecondaryText
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Include Licensee Brokerage Page",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Appends dedicated Licensee fee sign-off page ($slabRuleText)",
                                fontSize = 11.sp,
                                color = SecondaryText
                            )
                        }
                        Switch(
                            checked = draft.includeLicenseeBrokeragePage,
                            onCheckedChange = { draft = draft.copy(includeLicenseeBrokeragePage = it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryActionOrange),
                            modifier = Modifier.testTag("loi_toggle_licensee_brokerage")
                        )
                    }
                    HorizontalDivider(color = BorderSubtle)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Include Licensor Brokerage Page",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Appends dedicated Licensor fee sign-off page ($slabRuleText)",
                                fontSize = 11.sp,
                                color = SecondaryText
                            )
                        }
                        Switch(
                            checked = draft.includeLicensorBrokeragePage,
                            onCheckedChange = { draft = draft.copy(includeLicensorBrokeragePage = it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryActionOrange),
                            modifier = Modifier.testTag("loi_toggle_licensor_brokerage")
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // Optional Lead Auto-Fill Picker
    if (showLeadPicker) {
        var search by remember { mutableStateOf("") }
        val filtered = remember(availableLeads, search) {
            val q = search.trim().lowercase(Locale.ENGLISH)
            availableLeads.filter {
                !it.id.startsWith("demo-") && (
                    q.isBlank() ||
                        it.displayClientTitle().lowercase(Locale.ENGLISH).contains(q) ||
                        it.companyName.lowercase(Locale.ENGLISH).contains(q) ||
                        it.location.lowercase(Locale.ENGLISH).contains(q)
                    )
            }.take(40)
        }
        AlertDialog(
            onDismissRequest = { showLeadPicker = false },
            containerColor = SurfaceWhite,
            title = { Text("Auto-Fill Licensee from CRM", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search client or company...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filtered, key = { it.id }) { lead ->
                            Surface(
                                color = BackgroundSoft,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.8.dp, BorderGrey),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        draft = draft.copy(
                                            licenseeName = lead.companyName.ifBlank { lead.displayClientTitle() },
                                            licenseeAuthorizedRep = lead.displayClientTitle()
                                        )
                                        showLeadPicker = false
                                    }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(lead.displayClientTitle(), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                                    Text("${lead.status} • ${lead.location.ifBlank { "Pune" }}", fontSize = 11.sp, color = SecondaryText)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLeadPicker = false }) { Text("Close") } }
        )
    }

    // Optional Property Auto-Fill Picker
    if (showPropertyPicker) {
        var search by remember { mutableStateOf("") }
        val filtered = remember(availableProperties, search) {
            val q = search.trim().lowercase(Locale.ENGLISH)
            availableProperties.filter {
                q.isBlank() ||
                    it.projectName.lowercase(Locale.ENGLISH).contains(q) ||
                    it.propertyCode.lowercase(Locale.ENGLISH).contains(q) ||
                    it.location.lowercase(Locale.ENGLISH).contains(q)
            }.take(40)
        }
        AlertDialog(
            onDismissRequest = { showPropertyPicker = false },
            containerColor = SurfaceWhite,
            title = { Text("Auto-Fill Premises from Inventory", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search building, code or locality...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filtered, key = { it.id.ifBlank { it.propertyCode } }) { prop ->
                            Surface(
                                color = BackgroundSoft,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.8.dp, BorderGrey),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val propDraft = LoiDraftFactory.fromProperty(prop)
                                        draft = draft.copy(
                                            propertyCode = propDraft.propertyCode,
                                            propertyName = propDraft.propertyName,
                                            unitNumber = propDraft.unitNumber,
                                            floor = propDraft.floor,
                                            wing = propDraft.wing,
                                            locality = propDraft.locality,
                                            cityState = propDraft.cityState,
                                            carpetAreaSqFt = propDraft.carpetAreaSqFt,
                                            builtUpAreaSqFt = propDraft.builtUpAreaSqFt,
                                            furnishing = propDraft.furnishing,
                                            parking4Wheeler = propDraft.parking4Wheeler,
                                            licensorName = propDraft.licensorName.ifBlank { draft.licensorName },
                                            monthlyFeeInr = propDraft.monthlyFeeInr.ifBlank { draft.monthlyFeeInr },
                                            securityDepositInr = propDraft.securityDepositInr.ifBlank { draft.securityDepositInr },
                                            lockInMonths = propDraft.lockInMonths,
                                            escalationPercentYoy = propDraft.escalationPercentYoy,
                                            maintenanceTerms = propDraft.maintenanceTerms
                                        )
                                        showPropertyPicker = false
                                    }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        "${prop.propertyCode} • ${prop.projectName}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        "${prop.location} • ${prop.carpetAreaSqFt.toInt()} sq.ft",
                                        fontSize = 11.sp,
                                        color = SecondaryText
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPropertyPicker = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun LoiSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderGrey),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            content()
        }
    }
}
