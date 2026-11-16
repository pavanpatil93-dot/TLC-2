package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.UserSettingsManager
import com.example.model.CalculationUtils
import com.example.model.PropertyItem
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DealType(val title: String) {
    COMMERCIAL_LEASE("Commercial Lease"),
    OUTRIGHT_SALE("Outright Sale")
}

data class PaymentMilestone(
    val stageName: String,
    val percentage: Double,
    val amount: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrokerageDealCalculator(
    inventory: List<PropertyItem> = emptyList(),
    initialProperty: PropertyItem? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userProfile = remember { UserSettingsManager(context).userProfile.value }

    var selectedDealType by remember { mutableStateOf(DealType.COMMERCIAL_LEASE) }

    // Common Inputs
    var projectName by remember { mutableStateOf(initialProperty?.projectName ?: "") }
    var unitRefCode by remember { mutableStateOf(initialProperty?.propertyCode ?: "") }
    var location by remember { mutableStateOf(initialProperty?.location ?: "Kharadi, Pune") }
    var carpetAreaInput by remember {
        mutableStateOf(if ((initialProperty?.carpetAreaSqFt ?: 0.0) > 0) initialProperty!!.carpetAreaSqFt.toInt().toString() else "2500")
    }

    // Commercial Lease Specifics
    var monthlyRentInput by remember {
        mutableStateOf(if ((initialProperty?.priceOrRent ?: 0.0) > 0 && (initialProperty?.tab?.isRental == true)) {
            initialProperty!!.priceOrRent.toLong().toString()
        } else "150000")
    }
    var depositMonths by remember { mutableStateOf(6.0) }
    var leaseTenureYears by remember { mutableStateOf(5) }
    var lockInYears by remember { mutableStateOf(3) }
    var escalationType by remember { mutableStateOf("15% every 3 Years") }
    var leaseBrokerageOption by remember { mutableStateOf("1 Month Rent") } // "1 Month Rent", "2 Months Rent", "Custom"
    var customLeaseBrokerageAmount by remember { mutableStateOf("150000") }

    // Outright Sale Specifics
    var totalSalePriceInput by remember {
        mutableStateOf(if ((initialProperty?.priceOrRent ?: 0.0) > 0 && (initialProperty?.tab?.isRental == false)) {
            initialProperty!!.priceOrRent.toLong().toString()
        } else "25000000") // 2.5 Cr default
    }
    var saleBrokeragePercent by remember { mutableStateOf(2.0) } // 1.0, 1.5, 2.0
    var saleMilestonePreset by remember { mutableStateOf("Standard Under-Construction (RERA)") }

    // Tax Options
    var applyGstOnBrokerage by remember { mutableStateOf(true) } // 18% Commercial GST
    var commissionPayerSplit by remember { mutableStateOf("100% Client") } // "100% Client", "100% Owner/Developer", "50-50 Split"

    // Property Selector Dialog
    var showPropertyPicker by remember { mutableStateOf(false) }

    // Computed Values
    val carpetArea = carpetAreaInput.toDoubleOrNull() ?: 0.0

    // Lease computations
    val monthlyRent = monthlyRentInput.toDoubleOrNull() ?: 0.0
    val rentPerSqFt = if (carpetArea > 0) monthlyRent / carpetArea else 0.0
    val securityDeposit = monthlyRent * depositMonths
    val baseLeaseBrokerage = when (leaseBrokerageOption) {
        "1 Month Rent" -> monthlyRent
        "2 Months Rent" -> monthlyRent * 2.0
        else -> customLeaseBrokerageAmount.toDoubleOrNull() ?: monthlyRent
    }

    // Sale computations
    val salePrice = totalSalePriceInput.toDoubleOrNull() ?: 0.0
    val ratePerSqFtSale = if (carpetArea > 0) salePrice / carpetArea else 0.0
    val baseSaleBrokerage = salePrice * (saleBrokeragePercent / 100.0)

    // Brokerage Total with GST
    val activeBaseBrokerage = if (selectedDealType == DealType.COMMERCIAL_LEASE) baseLeaseBrokerage else baseSaleBrokerage
    val gstAmount = if (applyGstOnBrokerage) activeBaseBrokerage * 0.18 else 0.0
    val totalBrokerageWithGst = activeBaseBrokerage + gstAmount

    // Milestones for Outright Sale
    val milestones = remember(salePrice, saleMilestonePreset) {
        if (saleMilestonePreset == "Ready Possession") {
            listOf(
                PaymentMilestone("1. Booking Token / Advance", 10.0, salePrice * 0.10),
                PaymentMilestone("2. Agreement & Registration (within 30 days)", 15.0, salePrice * 0.15),
                PaymentMilestone("3. Final Balance on Key Handover & Possession", 75.0, salePrice * 0.75)
            )
        } else {
            listOf(
                PaymentMilestone("1. Booking Token Amount", 10.0, salePrice * 0.10),
                PaymentMilestone("2. Agreement for Sale / Registration", 10.0, salePrice * 0.10),
                PaymentMilestone("3. Plinth & Foundation Completion", 15.0, salePrice * 0.15),
                PaymentMilestone("4. RCC Slabs Completion (Floors 1-10)", 25.0, salePrice * 0.25),
                PaymentMilestone("5. Internal Brickwork & Plastering", 15.0, salePrice * 0.15),
                PaymentMilestone("6. Flooring, Plumbing & Electrical", 15.0, salePrice * 0.15),
                PaymentMilestone("7. Key Handover & Possession Notice", 10.0, salePrice * 0.10)
            )
        }
    }

    // Deal Sheet Content Generator
    fun generateDealSummaryText(): String {
        val todayStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())
        val agentName = userProfile.username.ifBlank { "TLC Commercial Advisor" }
        val agentPhone = userProfile.mobileNumber.ifBlank { "+91 9766880099" }

        return buildString {
            appendLine("═══════════════════════════════════════")
            appendLine("🏢 TLC DEAL SHEET & FINANCIAL SUMMARY")
            appendLine("Aurea Living LLP & Ascend Commercial Solutions LLP")
            appendLine("Date: $todayStr | Ref: ${unitRefCode.ifBlank { "TLC-DEAL" }}")
            appendLine("═══════════════════════════════════════")
            appendLine()
            appendLine("📌 PROPERTY DETAILS:")
            appendLine("• Project: ${projectName.ifBlank { "Commercial / Residential Unit" }}")
            appendLine("• Location: $location")
            appendLine("• Carpet Area: ${carpetArea.toInt()} sq.ft")
            if (selectedDealType == DealType.COMMERCIAL_LEASE) {
                appendLine("• Rent Rate: ₹${String.format(Locale.US, "%.2f", rentPerSqFt)}/sq.ft/month")
            } else {
                appendLine("• Sale Rate: ₹${String.format(Locale.US, "%.2f", ratePerSqFtSale)}/sq.ft carpet")
            }
            appendLine()
            appendLine("💰 TRANSACTION FINANCIALS (${selectedDealType.title.uppercase()}):")
            if (selectedDealType == DealType.COMMERCIAL_LEASE) {
                appendLine("• Base Monthly Rent: ${CalculationUtils.formatIndianCurrencyFull(monthlyRent)}/mo")
                appendLine("• Security Deposit (${depositMonths.toInt()} Months): ${CalculationUtils.formatIndianCurrencyFull(securityDeposit)}")
                appendLine("• Lease Tenure: $leaseTenureYears Years")
                appendLine("• Lock-in Period: $lockInYears Years")
                appendLine("• Rent Escalation: $escalationType")
            } else {
                appendLine("• Total Consideration Value: ${CalculationUtils.formatIndianCurrencyFull(salePrice)} (${CalculationUtils.formatIndianCurrency(salePrice)})")
                appendLine("• Token / Booking (10%): ${CalculationUtils.formatIndianCurrencyFull(salePrice * 0.10)}")
            }
            appendLine()
            appendLine("💼 BROKERAGE & COMMISSION STRUCTURE:")
            if (selectedDealType == DealType.COMMERCIAL_LEASE) {
                appendLine("• Brokerage Terms: $leaseBrokerageOption")
            } else {
                appendLine("• Brokerage Terms: $saleBrokeragePercent% of Agreement Value")
            }
            appendLine("• Base Brokerage: ${CalculationUtils.formatIndianCurrencyFull(activeBaseBrokerage)}")
            if (applyGstOnBrokerage) {
                appendLine("• GST (18% Commercial): ${CalculationUtils.formatIndianCurrencyFull(gstAmount)}")
            } else {
                appendLine("• GST: Excluded / Not Applicable")
            }
            appendLine("• TOTAL BROKERAGE RECEIVABLE: ${CalculationUtils.formatIndianCurrencyFull(totalBrokerageWithGst)}")
            appendLine("• Payer Allocation: $commissionPayerSplit")
            appendLine()

            if (selectedDealType == DealType.OUTRIGHT_SALE) {
                appendLine("📊 PAYMENT MILESTONE SCHEDULE:")
                milestones.forEach { m ->
                    appendLine("  ${m.stageName} (${m.percentage.toInt()}%): ${CalculationUtils.formatIndianCurrencyFull(m.amount)}")
                }
                appendLine()
            }

            appendLine("👤 DEAL LEAD / INTERNAL ADVISOR:")
            appendLine("• Name: $agentName")
            appendLine("• Phone: $agentPhone")
            appendLine("═══════════════════════════════════════")
            appendLine("*CONFIDENTIAL — INTERNAL MANAGEMENT APPROVAL ONLY*")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundSoft)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey),
            modifier = Modifier.fillMaxWidth().testTag("calculator_header_card")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DEAL FINANCIALS & CALCULATOR",
                            color = PrimaryNavy,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Aurea Living LLP • Ascend Commercial Solutions LLP",
                            color = SecondaryText,
                            fontSize = 12.sp
                        )
                    }

                    if (inventory.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = { showPropertyPicker = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = TealSubtle,
                                contentColor = PrimaryTeal
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("calculator_auto_fill_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Auto-Fill Unit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Deal Type Selector Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BackgroundSoft)
                        .padding(4.dp)
                ) {
                    DealType.values().forEach { dealType ->
                        val isSelected = selectedDealType == dealType
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) PrimaryNavy else Color.Transparent)
                                .clickable { selectedDealType = dealType }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dealType.title,
                                color = if (isSelected) SurfaceWhite else SecondaryText,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // Section 1: Property & Micro-Market Parameters
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey),
            modifier = Modifier.fillMaxWidth().testTag("calculator_property_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "PROPERTY & LOCATION",
                    color = PrimaryNavy,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        label = { Text("Project / Building") },
                        placeholder = { Text("e.g. Eon Free Zone") },
                        modifier = Modifier.weight(1.3f).testTag("calculator_project_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = BorderGrey
                        )
                    )

                    OutlinedTextField(
                        value = unitRefCode,
                        onValueChange = { unitRefCode = it },
                        label = { Text("Unit / Ref Code") },
                        placeholder = { Text("e.g. ASC-04") },
                        modifier = Modifier.weight(0.9f).testTag("calculator_unit_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = BorderGrey
                        )
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("East Pune Micro-Market") },
                        placeholder = { Text("e.g. Kharadi, Koregaon Park") },
                        modifier = Modifier.weight(1.2f).testTag("calculator_location_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = BorderGrey
                        )
                    )

                    OutlinedTextField(
                        value = carpetAreaInput,
                        onValueChange = { carpetAreaInput = it },
                        label = { Text("Carpet Area (sq.ft)") },
                        modifier = Modifier.weight(1.0f).testTag("calculator_carpet_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = BorderGrey
                        )
                    )
                }
            }
        }

        // Section 2: Financial Terms according to Deal Type
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey),
            modifier = Modifier.fillMaxWidth().testTag("calculator_financial_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = if (selectedDealType == DealType.COMMERCIAL_LEASE) "LEASE FINANCIALS" else "SALE FINANCIALS",
                    color = PrimaryNavy,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                if (selectedDealType == DealType.COMMERCIAL_LEASE) {
                    // Monthly Rent Input & Auto Rate per Sq.Ft
                    OutlinedTextField(
                        value = monthlyRentInput,
                        onValueChange = { monthlyRentInput = it },
                        label = { Text("Base Monthly Rent (₹/month)") },
                        modifier = Modifier.fillMaxWidth().testTag("calculator_monthly_rent_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        supportingText = {
                            if (carpetArea > 0 && monthlyRent > 0) {
                                Text(
                                    "Effective Rent Rate: ₹${String.format(Locale.US, "%.2f", rentPerSqFt)} / sq.ft / mo",
                                    color = PrimaryTeal,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = BorderGrey
                        )
                    )

                    // Security Deposit (Months Selector)
                    Text("Security Deposit Duration", color = SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(3.0, 4.0, 6.0, 9.0).forEach { months ->
                            val isSelected = depositMonths == months
                            OutlinedButton(
                                onClick = { depositMonths = months },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) PrimaryTeal else BorderGrey
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isSelected) TealSubtle else Color.Transparent,
                                    contentColor = if (isSelected) PrimaryTeal else SecondaryText
                                )
                            ) {
                                Text("${months.toInt()} Mo", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Lease Tenure & Lock-In
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tenure: $leaseTenureYears Years", color = SecondaryText, fontSize = 12.sp)
                            Slider(
                                value = leaseTenureYears.toFloat(),
                                onValueChange = { leaseTenureYears = it.toInt() },
                                valueRange = 1f..9f,
                                steps = 7,
                                colors = SliderDefaults.colors(
                                    thumbColor = PrimaryTeal,
                                    activeTrackColor = PrimaryTeal
                                )
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Lock-In: $lockInYears Years", color = SecondaryText, fontSize = 12.sp)
                            Slider(
                                value = lockInYears.toFloat(),
                                onValueChange = { lockInYears = it.toInt() },
                                valueRange = 1f..5f,
                                steps = 3,
                                colors = SliderDefaults.colors(
                                    thumbColor = PrimaryNavy,
                                    activeTrackColor = PrimaryNavy
                                )
                            )
                        }
                    }

                    // Escalation Terms
                    Text("Rent Escalation Standard", color = SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("5% Annual", "15% every 3 Years", "10% every 2 Years").forEach { term ->
                            val isSelected = escalationType == term
                            FilterChip(
                                selected = isSelected,
                                onClick = { escalationType = term },
                                label = { Text(term, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TealSubtle,
                                    selectedLabelColor = PrimaryTeal
                                )
                            )
                        }
                    }

                    // Commercial Lease Commission Structure
                    HorizontalDivider(color = BorderGrey, thickness = 0.8.dp)
                    Text("Lease Brokerage Structure", color = PrimaryNavy, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("1 Month Rent", "2 Months Rent", "Custom").forEach { opt ->
                            val isSelected = leaseBrokerageOption == opt
                            OutlinedButton(
                                onClick = { leaseBrokerageOption = opt },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) PrimaryTeal else BorderGrey
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isSelected) TealSubtle else Color.Transparent,
                                    contentColor = if (isSelected) PrimaryTeal else SecondaryText
                                )
                            ) {
                                Text(opt, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (leaseBrokerageOption == "Custom") {
                        OutlinedTextField(
                            value = customLeaseBrokerageAmount,
                            onValueChange = { customLeaseBrokerageAmount = it },
                            label = { Text("Custom Brokerage Amount (₹)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                } else {
                    // Outright Sale Inputs
                    OutlinedTextField(
                        value = totalSalePriceInput,
                        onValueChange = { totalSalePriceInput = it },
                        label = { Text("Total Agreement Consideration (₹)") },
                        modifier = Modifier.fillMaxWidth().testTag("calculator_sale_price_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        supportingText = {
                            if (salePrice > 0) {
                                Text(
                                    "Amount: ${CalculationUtils.formatIndianCurrency(salePrice)} " +
                                            if (carpetArea > 0) "(₹${String.format(Locale.US, "%.0f", ratePerSqFtSale)} / sq.ft)" else "",
                                    color = PrimaryTeal,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = BorderGrey
                        )
                    )

                    Text("Brokerage Percentage", color = SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1.0, 1.5, 2.0).forEach { pct ->
                            val isSelected = saleBrokeragePercent == pct
                            OutlinedButton(
                                onClick = { saleBrokeragePercent = pct },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) PrimaryTeal else BorderGrey
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isSelected) TealSubtle else Color.Transparent,
                                    contentColor = if (isSelected) PrimaryTeal else SecondaryText
                                )
                            ) {
                                Text("$pct%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text("Milestone Schedule Preset", color = SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Standard Under-Construction (RERA)", "Ready Possession").forEach { preset ->
                            val isSelected = saleMilestonePreset == preset
                            FilterChip(
                                selected = isSelected,
                                onClick = { saleMilestonePreset = preset },
                                label = { Text(if (preset.startsWith("Standard")) "Construction Linked" else "Ready Handover", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TealSubtle,
                                    selectedLabelColor = PrimaryTeal
                                )
                            )
                        }
                    }
                }

                // GST & Split Options
                HorizontalDivider(color = BorderGrey, thickness = 0.8.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Apply Commercial GST (18%)", color = PrimaryNavy, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Required for registered corporate billing", color = MutedText, fontSize = 11.sp)
                    }
                    Switch(
                        checked = applyGstOnBrokerage,
                        onCheckedChange = { applyGstOnBrokerage = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PrimaryTeal,
                            checkedTrackColor = TealSubtle
                        )
                    )
                }

                // Payer Allocation
                Text("Commission Source / Allocation", color = SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("100% Client", "100% Owner", "50-50 Split").forEach { split ->
                        val isSelected = commissionPayerSplit == split
                        FilterChip(
                            selected = isSelected,
                            onClick = { commissionPayerSplit = split },
                            label = { Text(split, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TealSubtle,
                                selectedLabelColor = PrimaryTeal
                            )
                        )
                    }
                }
            }
        }

        // Section 3: Financial Summary & Milestone Breakdown Card
        Card(
            colors = CardDefaults.cardColors(containerColor = PrimaryNavy),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().testTag("calculator_summary_card")
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DEAL RECEIVABLES SUMMARY",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Surface(
                        color = AccentGold.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, AccentGold)
                    ) {
                        Text(
                            text = selectedDealType.title.uppercase(),
                            color = AccentGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // Prominent Total Brokerage
                Column {
                    Text(
                        text = "Total Brokerage (Incl. GST)",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                    Text(
                        text = CalculationUtils.formatIndianCurrencyFull(totalBrokerageWithGst),
                        color = AccentGold,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.15f), thickness = 0.8.dp)

                // Sub-breakdowns
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Base Brokerage", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Text(CalculationUtils.formatIndianCurrencyFull(activeBaseBrokerage), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("18% Commercial GST", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Text(
                            if (applyGstOnBrokerage) CalculationUtils.formatIndianCurrencyFull(gstAmount) else "₹0 (Exempt)",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }

                if (selectedDealType == DealType.COMMERCIAL_LEASE) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Security Deposit (${depositMonths.toInt()} Mo)", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            Text(CalculationUtils.formatIndianCurrencyFull(securityDeposit), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Lock-In / Tenure", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            Text("$lockInYears Yrs / $leaseTenureYears Yrs", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }

                if (selectedDealType == DealType.OUTRIGHT_SALE) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "PAYMENT MILESTONE BREAKDOWN",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )

                    milestones.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${m.stageName} (${m.percentage.toInt()}%)",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = CalculationUtils.formatIndianCurrencyFull(m.amount),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Section 4: Export & Action Buttons
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey),
            modifier = Modifier.fillMaxWidth().testTag("calculator_actions_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "DEAL SHEET EXPORT & ACTIONS",
                    color = PrimaryNavy,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                // 1-Tap Export as PDF / Share with Management
                Button(
                    onClick = {
                        val dealText = generateDealSummaryText()
                        try {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_SUBJECT, "TLC Internal Deal Summary Sheet - $projectName (${unitRefCode.ifBlank { "Ref" }})")
                                putExtra(Intent.EXTRA_TEXT, dealText)
                                type = "text/plain"
                            }
                            val shareIntent = Intent.createChooser(sendIntent, "Export Deal Sheet / Share with Management")
                            context.startActivity(shareIntent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("TLC Deal Sheet", dealText))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("export_deal_sheet_pdf_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export as PDF / Share with Management", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                // 1-Tap WhatsApp Share
                Button(
                    onClick = {
                        val dealText = generateDealSummaryText()
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("https://api.whatsapp.com/send?text=" + Uri.encode(dealText))
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "WhatsApp not available, copied to clipboard", Toast.LENGTH_SHORT).show()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("TLC Deal Sheet", dealText))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp).testTag("share_deal_sheet_whatsapp_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share Deal Sheet via WhatsApp", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Copy to Clipboard
                OutlinedButton(
                    onClick = {
                        val dealText = generateDealSummaryText()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TLC Deal Sheet", dealText))
                        Toast.makeText(context, "Deal Sheet copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("copy_deal_sheet_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryNavy),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNavy)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Formatted Deal Sheet", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }

    // Modal Property Picker to auto-fill unit values
    if (showPropertyPicker && inventory.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showPropertyPicker = false },
            title = {
                Text(
                    "Select Unit to Auto-Fill",
                    color = PrimaryNavy,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    inventory.take(20).forEach { item ->
                        Surface(
                            onClick = {
                                projectName = item.projectName
                                unitRefCode = item.propertyCode
                                location = item.location
                                carpetAreaInput = item.carpetAreaSqFt.toInt().toString()
                                if (item.tab.isRental) {
                                    selectedDealType = DealType.COMMERCIAL_LEASE
                                    monthlyRentInput = item.priceOrRent.toLong().toString()
                                    if ((item.deposit ?: 0.0) > 0 && item.priceOrRent > 0) {
                                        depositMonths = (item.deposit!! / item.priceOrRent).coerceIn(1.0, 12.0)
                                    }
                                } else {
                                    selectedDealType = DealType.OUTRIGHT_SALE
                                    totalSalePriceInput = item.priceOrRent.toLong().toString()
                                }
                                showPropertyPicker = false
                                Toast.makeText(context, "Loaded ${item.propertyCode}", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = BackgroundSoft,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(item.propertyCode, fontWeight = FontWeight.Bold, color = PrimaryTeal, fontSize = 12.sp)
                                    Text(
                                        if (item.tab.isRental) "Lease" else "Sale",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = if (item.tab.isRental) PrimaryTeal else AccentGold
                                    )
                                }
                                Text(item.projectName.ifBlank { "Untitled Project" }, fontWeight = FontWeight.SemiBold, color = PrimaryNavy, fontSize = 13.sp)
                                Text("${item.location} • ${item.carpetAreaSqFt.toInt()} sq.ft • ₹${CalculationUtils.formatIndianCurrency(item.priceOrRent, item.tab.isRental)}", color = SecondaryText, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPropertyPicker = false }) {
                    Text("Cancel", color = PrimaryTeal)
                }
            }
        )
    }
}

