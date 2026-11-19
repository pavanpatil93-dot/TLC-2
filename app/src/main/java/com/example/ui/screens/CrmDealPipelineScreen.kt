package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.crm.PendingLoopTaskStore
import com.example.loi.LoiDraftFactory
import com.example.model.*
import com.example.ui.components.LoiBuilderBottomSheet
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class CrmDealStage(val label: String, val stepNumber: Int, val badgeColorHex: Long) {
    LOI_TOKEN("LOI / Token", 1, 0xFFE96932),
    DRAFT_AGREEMENT("Draft Agreement", 2, 0xFF3B82F6),
    REGISTRATION("Registration", 3, 0xFF8B5CF6),
    BROKERAGE_BILLED("Brokerage Billed", 4, 0xFFF59E0B),
    COMMISSION_COLLECTED("Commission Collected", 5, 0xFF10B981);

    companion object {
        fun fromLabel(value: String): CrmDealStage {
            return entries.firstOrNull {
                it.label.equals(value.trim(), ignoreCase = true) ||
                    it.name.equals(value.trim(), ignoreCase = true)
            } ?: LOI_TOKEN
        }
    }
}

data class CrmDealItem(
    val id: String = "DL-" + UUID.randomUUID().toString().take(6).uppercase(Locale.US),
    val propertyCode: String,
    val projectName: String,
    val location: String,
    val transactionType: String, // "Lease" or "Sale"
    val carpetAreaSqFt: Double,
    val builtUpAreaSqFt: Double,
    val monthlyRentOrPriceInr: Double,
    val securityDepositInr: Double, // Default 3-month security deposit for Lease
    val lockInPeriod: String = "36 Months",
    val escalationTerms: String = "5% every 12 months",
    val possessionDate: String = "Immediate",
    val clientName: String,
    val ownerName: String,
    val stage: CrmDealStage = CrmDealStage.LOI_TOKEN,
    val clientBrokerageInr: Double = 0.0,
    val landlordBrokerageInr: Double = 0.0,
    val developerBrokerageInr: Double = 0.0,
    val invoiceNotes: String = "",
    val updatedAt: String = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())
) {
    val totalBrokerageInr: Double
        get() = clientBrokerageInr + landlordBrokerageInr + developerBrokerageInr
}

object CrmDealStorage {
    private const val PREFS_NAME = "crm_deal_pipeline_v2"
    private const val KEY_DEALS = "deals_json"

    fun loadDeals(context: Context, inventory: List<PropertyItem>): List<CrmDealItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DEALS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                val list = mutableListOf<CrmDealItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(fromJson(obj))
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
            }
        }
        val seeded = buildInitialSeedDeals(inventory)
        saveDeals(context, seeded)
        return seeded
    }

    fun saveDeals(context: Context, deals: List<CrmDealItem>) {
        val arr = JSONArray()
        deals.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEALS, arr.toString())
            .apply()
    }

    private fun buildInitialSeedDeals(inventory: List<PropertyItem>): List<CrmDealItem> {
        val p1 = inventory.firstOrNull { it.propertyCategory == PropertyCategory.COMMERCIAL } ?: inventory.firstOrNull()
        val p2 = inventory.drop(1).firstOrNull { it.propertyCategory == PropertyCategory.COMMERCIAL } ?: inventory.getOrNull(1)
        val p3 = inventory.firstOrNull { it.propertyCategory == PropertyCategory.RESIDENTIAL } ?: inventory.getOrNull(2)

        val rent1 = (p1?.rent ?: p1?.priceOrRent ?: 185000.0).let { if (it <= 0.0) 185000.0 else it }
        val rent2 = (p2?.rent ?: p2?.priceOrRent ?: 320000.0).let { if (it <= 0.0) 320000.0 else it }
        val price3 = (p3?.priceSale ?: p3?.priceOrRent ?: 16500000.0).let { if (it <= 0.0) 16500000.0 else it }

        val bu1 = (p1?.builtUpAreaSqFt ?: 0.0).let { if (it > 0.0) it else 3300.0 }
        val bu2 = (p2?.builtUpAreaSqFt ?: 0.0).let { if (it > 0.0) it else 5500.0 }
        val bu3 = (p3?.builtUpAreaSqFt ?: 0.0).let { if (it > 0.0) it else 2850.0 }

        val dep1 = (p1?.deposit ?: 0.0).let { if (it > 0.0) it else (rent1 * 3.0) }
        val dep2 = (p2?.deposit ?: 0.0).let { if (it > 0.0) it else (rent2 * 3.0) }

        return listOf(
            CrmDealItem(
                id = "DL-101",
                propertyCode = p1?.propertyCode?.ifBlank { "CO-0101" } ?: "CO-0101",
                projectName = p1?.projectName?.ifBlank { "EON Free Zone Phase 2" } ?: "EON Free Zone Phase 2",
                location = p1?.location?.ifBlank { "Kharadi, Pune" } ?: "Kharadi, Pune",
                transactionType = "Lease",
                carpetAreaSqFt = (p1?.carpetAreaSqFt ?: 0.0).let { if (it > 0.0) it else 2450.0 },
                builtUpAreaSqFt = bu1,
                monthlyRentOrPriceInr = rent1,
                securityDepositInr = dep1,
                lockInPeriod = "36 Months",
                escalationTerms = "5% every 12 months",
                possessionDate = p1?.possession?.ifBlank { "Immediate" } ?: "Immediate",
                clientName = "Vertex Cloud Technologies Pvt. Ltd.",
                ownerName = p1?.ownerName?.ifBlank { "Panchshil Corporate Park LLP" } ?: "Panchshil Corporate Park LLP",
                stage = CrmDealStage.LOI_TOKEN,
                clientBrokerageInr = rent1,
                landlordBrokerageInr = rent1,
                developerBrokerageInr = 0.0,
                invoiceNotes = "LOI signed, 3-month security deposit term sheet shared"
            ),
            CrmDealItem(
                id = "DL-102",
                propertyCode = p2?.propertyCode?.ifBlank { "CO-0204" } ?: "CO-0204",
                projectName = p2?.projectName?.ifBlank { "World Trade Center" } ?: "World Trade Center",
                location = p2?.location?.ifBlank { "Kharadi, Pune" } ?: "Kharadi, Pune",
                transactionType = "Lease",
                carpetAreaSqFt = (p2?.carpetAreaSqFt ?: 0.0).let { if (it > 0.0) it else 4100.0 },
                builtUpAreaSqFt = bu2,
                monthlyRentOrPriceInr = rent2,
                securityDepositInr = dep2,
                lockInPeriod = "36 Months",
                escalationTerms = "15% every 36 months",
                possessionDate = "1st Next Month",
                clientName = "Nordic FinTech Global Services",
                ownerName = p2?.ownerName?.ifBlank { "Rohan Holdings" } ?: "Rohan Holdings",
                stage = CrmDealStage.REGISTRATION,
                clientBrokerageInr = rent2,
                landlordBrokerageInr = rent2,
                developerBrokerageInr = 0.0,
                invoiceNotes = "Leave & License registration scheduled; invoice ready"
            ),
            CrmDealItem(
                id = "DL-103",
                propertyCode = p3?.propertyCode?.ifBlank { "RS-0312" } ?: "RS-0312",
                projectName = p3?.projectName?.ifBlank { "Trump Towers / Yoo Pune" } ?: "Trump Towers / Yoo Pune",
                location = p3?.location?.ifBlank { "Kalyani Nagar, Pune" } ?: "Kalyani Nagar, Pune",
                transactionType = "Sale",
                carpetAreaSqFt = (p3?.carpetAreaSqFt ?: 0.0).let { if (it > 0.0) it else 2150.0 },
                builtUpAreaSqFt = bu3,
                monthlyRentOrPriceInr = price3,
                securityDepositInr = price3 * 0.10,
                lockInPeriod = "N/A (Outright Sale)",
                escalationTerms = "N/A",
                possessionDate = "Ready Possession",
                clientName = "Siddharth & Meera Deshmukh",
                ownerName = p3?.ownerName?.ifBlank { "Panchshil Realty" } ?: "Panchshil Realty",
                stage = CrmDealStage.BROKERAGE_BILLED,
                clientBrokerageInr = price3 * 0.01,
                landlordBrokerageInr = 0.0,
                developerBrokerageInr = price3 * 0.02,
                invoiceNotes = "2% developer ladder + 1% buyer mandate billed"
            )
        )
    }

    private fun toJson(item: CrmDealItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("propertyCode", item.propertyCode)
        put("projectName", item.projectName)
        put("location", item.location)
        put("transactionType", item.transactionType)
        put("carpetAreaSqFt", item.carpetAreaSqFt)
        put("builtUpAreaSqFt", item.builtUpAreaSqFt)
        put("monthlyRentOrPriceInr", item.monthlyRentOrPriceInr)
        put("securityDepositInr", item.securityDepositInr)
        put("lockInPeriod", item.lockInPeriod)
        put("escalationTerms", item.escalationTerms)
        put("possessionDate", item.possessionDate)
        put("clientName", item.clientName)
        put("ownerName", item.ownerName)
        put("stage", item.stage.name)
        put("clientBrokerageInr", item.clientBrokerageInr)
        put("landlordBrokerageInr", item.landlordBrokerageInr)
        put("developerBrokerageInr", item.developerBrokerageInr)
        put("invoiceNotes", item.invoiceNotes)
        put("updatedAt", item.updatedAt)
    }

    private fun fromJson(obj: JSONObject): CrmDealItem = CrmDealItem(
        id = obj.optString("id", "DL-100"),
        propertyCode = obj.optString("propertyCode", ""),
        projectName = obj.optString("projectName", ""),
        location = obj.optString("location", ""),
        transactionType = obj.optString("transactionType", "Lease"),
        carpetAreaSqFt = obj.optDouble("carpetAreaSqFt", 0.0),
        builtUpAreaSqFt = obj.optDouble("builtUpAreaSqFt", 0.0),
        monthlyRentOrPriceInr = obj.optDouble("monthlyRentOrPriceInr", 0.0),
        securityDepositInr = obj.optDouble("securityDepositInr", 0.0),
        lockInPeriod = obj.optString("lockInPeriod", "36 Months"),
        escalationTerms = obj.optString("escalationTerms", "5% every 12 months"),
        possessionDate = obj.optString("possessionDate", "Immediate"),
        clientName = obj.optString("clientName", ""),
        ownerName = obj.optString("ownerName", ""),
        stage = CrmDealStage.fromLabel(obj.optString("stage", CrmDealStage.LOI_TOKEN.name)),
        clientBrokerageInr = obj.optDouble("clientBrokerageInr", 0.0),
        landlordBrokerageInr = obj.optDouble("landlordBrokerageInr", 0.0),
        developerBrokerageInr = obj.optDouble("developerBrokerageInr", 0.0),
        invoiceNotes = obj.optString("invoiceNotes", ""),
        updatedAt = obj.optString("updatedAt", "")
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrmDealPipelineScreen(
    inventory: List<PropertyItem>,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var deals by remember { mutableStateOf(CrmDealStorage.loadDeals(context, inventory)) }
    val crmLeads = remember(context) { runCatching { readCrmLeads(context) }.getOrDefault(emptyList()) }
    var selectedStageFilter by remember { mutableStateOf<CrmDealStage?>(null) }
    var showAddDealDialog by remember { mutableStateOf(false) }
    var editingDeal by remember { mutableStateOf<CrmDealItem?>(null) }
    var activeLoiDeal by remember { mutableStateOf<CrmDealItem?>(null) }
    var generatingPdfForId by remember { mutableStateOf<String?>(null) }

    val filteredDeals = remember(deals, selectedStageFilter) {
        if (selectedStageFilter == null) deals
        else deals.filter { it.stage == selectedStageFilter }
    }

    val totalClientBrok = remember(deals) { deals.sumOf { it.clientBrokerageInr } }
    val totalLandlordBrok = remember(deals) { deals.sumOf { it.landlordBrokerageInr } }
    val totalDeveloperBrok = remember(deals) { deals.sumOf { it.developerBrokerageInr } }
    val collectedBrok = remember(deals) {
        deals.filter { it.stage == CrmDealStage.COMMISSION_COLLECTED }.sumOf { it.totalBrokerageInr }
    }
    val pendingBrok = remember(deals) {
        deals.filter { it.stage != CrmDealStage.COMMISSION_COLLECTED }.sumOf { it.totalBrokerageInr }
    }

    Scaffold(
        containerColor = BrandCharcoalDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Deal Pipeline & LOI / Brokerage Tracker",
                            color = TextPrimaryDark,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "LOI / Token → Agreement → Registration → Billed → Collected",
                            color = TextSecondaryDark,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("deal_pipeline_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimaryDark
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { showAddDealDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .height(36.dp)
                            .testTag("add_new_deal_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Deal / LOI", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandCharcoalCard)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Brokerage Breakdown Header Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BrandCharcoalBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "BROKERAGE & COMMISSION LEDGER",
                                color = PrimaryActionOrange,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "${deals.size} Active Deals",
                                color = TextSecondaryDark,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BrokerageMetricPill(
                                label = "Client-Side",
                                amountInr = totalClientBrok,
                                accent = Color(0xFF3B82F6),
                                modifier = Modifier.weight(1f)
                            )
                            BrokerageMetricPill(
                                label = "Landlord-Side",
                                amountInr = totalLandlordBrok,
                                accent = Color(0xFFE96932),
                                modifier = Modifier.weight(1f)
                            )
                            BrokerageMetricPill(
                                label = "Developer",
                                amountInr = totalDeveloperBrok,
                                accent = Color(0xFF8B5CF6),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BrokerageMetricPill(
                                label = "Pipeline / Billed",
                                amountInr = pendingBrok,
                                accent = Color(0xFFF59E0B),
                                modifier = Modifier.weight(1f)
                            )
                            BrokerageMetricPill(
                                label = "Commission Collected",
                                amountInr = collectedBrok,
                                accent = Color(0xFF10B981),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Stage Filter Strip
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedStageFilter == null,
                        onClick = { selectedStageFilter = null },
                        label = {
                            Text(
                                text = "All Stages (${deals.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryActionOrange.copy(alpha = 0.2f),
                            selectedLabelColor = PrimaryActionOrange
                        ),
                        modifier = Modifier.testTag("stage_filter_all")
                    )

                    CrmDealStage.entries.forEach { stage ->
                        val count = deals.count { it.stage == stage }
                        val isSelected = selectedStageFilter == stage
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedStageFilter = if (isSelected) null else stage
                            },
                            label = {
                                Text(
                                    text = "${stage.stepNumber}. ${stage.label} ($count)",
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(stage.badgeColorHex).copy(alpha = 0.2f),
                                selectedLabelColor = Color(stage.badgeColorHex)
                            ),
                            modifier = Modifier.testTag("stage_filter_${stage.name}")
                        )
                    }
                }
            }

            // Deal Cards
            items(filteredDeals, key = { it.id }) { deal ->
                DealPipelineCard(
                    deal = deal,
                    isGeneratingPdf = generatingPdfForId == deal.id,
                    onStageChange = { newStage ->
                        val updated = deals.map {
                            if (it.id == deal.id) {
                                it.copy(
                                    stage = newStage,
                                    updatedAt = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())
                                )
                            } else it
                        }
                        deals = updated
                        CrmDealStorage.saveDeals(context, updated)
                        if (newStage == CrmDealStage.REGISTRATION) {
                            val propMatch = inventory.firstOrNull {
                                it.propertyCode.equals(deal.propertyCode, ignoreCase = true)
                            }
                            PendingLoopTaskStore.enqueueTenantOrDealHandoverConfirmOwner(
                                context = context,
                                entityId = deal.id,
                                subjectLabel = listOf(deal.projectName, deal.propertyCode).filter { it.isNotBlank() }.joinToString(" • "),
                                ownerName = deal.ownerName.ifBlank { propMatch?.ownerName.orEmpty() },
                                ownerPhone = propMatch?.ownerContact.orEmpty(),
                                agentName = "TLC Team",
                                inventory = inventory,
                                propertyHint = deal.propertyCode.ifBlank { deal.projectName }
                            )
                        }
                    },
                    onEditDeal = { editingDeal = deal },
                    onGenerateLoiPdf = {
                        activeLoiDeal = deal
                    }
                )
            }
        }
    }

    if (showAddDealDialog || editingDeal != null) {
        AddOrEditDealDialog(
            initialDeal = editingDeal,
            inventory = inventory,
            onDismiss = {
                showAddDealDialog = false
                editingDeal = null
            },
            onSave = { savedDeal ->
                val exists = deals.any { it.id == savedDeal.id }
                val updated = if (exists) {
                    deals.map { if (it.id == savedDeal.id) savedDeal else it }
                } else {
                    listOf(savedDeal) + deals
                }
                deals = updated
                CrmDealStorage.saveDeals(context, updated)
                showAddDealDialog = false
                editingDeal = null
            }
        )
    }

    activeLoiDeal?.let { deal ->
        val matchedProp = remember(deal, inventory) {
            inventory.firstOrNull {
                deal.propertyCode.isNotBlank() && it.propertyCode.equals(deal.propertyCode, ignoreCase = true)
            } ?: inventory.firstOrNull {
                deal.projectName.isNotBlank() && it.projectName.equals(deal.projectName, ignoreCase = true)
            }
        }
        val matchedLead = remember(deal, crmLeads) {
            crmLeads.firstOrNull {
                it.displayClientTitle().equals(deal.clientName, ignoreCase = true) ||
                    it.companyName.equals(deal.clientName, ignoreCase = true)
            }
        }
        LoiBuilderBottomSheet(
            initialDraft = LoiDraftFactory.fromDeal(deal, matchedProp, matchedLead),
            availableLeads = crmLeads,
            availableProperties = inventory,
            onDismiss = { activeLoiDeal = null }
        )
    }
}

@Composable
private fun BrokerageMetricPill(
    label: String,
    amountInr: Double,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = BrandCharcoalSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = label,
                color = TextSecondaryDark,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (amountInr > 0) CalculationUtils.formatIndianCurrency(amountInr) else "₹0",
                color = accent,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DealPipelineCard(
    deal: CrmDealItem,
    isGeneratingPdf: Boolean,
    onStageChange: (CrmDealStage) -> Unit,
    onEditDeal: () -> Unit,
    onGenerateLoiPdf: () -> Unit
) {
    val stageColor = Color(deal.stage.badgeColorHex)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("deal_card_${deal.id}"),
        colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BrandCharcoalBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top Row: Property Code + Project + Stage Badge + Edit Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = PrimaryActionOrange.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = deal.propertyCode.ifBlank { deal.id },
                            color = PrimaryActionOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = deal.projectName,
                            color = TextPrimaryDark,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${deal.location} • ${deal.transactionType}",
                            color = TextSecondaryDark,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onEditDeal,
                    modifier = Modifier.size(34.dp).testTag("edit_deal_btn_${deal.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Deal & Brokerage",
                        tint = TextSecondaryDark,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 5-Stage Interactive Progress Stepper
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CrmDealStage.entries.forEach { stage ->
                    val isCurrent = deal.stage == stage
                    val isCompleted = stage.stepNumber < deal.stage.stepNumber
                    val chipColor = when {
                        isCurrent -> stageColor
                        isCompleted -> Color(0xFF10B981)
                        else -> TextSecondaryDark
                    }
                    Surface(
                        color = if (isCurrent) stageColor.copy(alpha = 0.2f) else BrandCharcoalSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isCurrent) stageColor else BrandCharcoalBorder
                        ),
                        modifier = Modifier
                            .clickable { onStageChange(stage) }
                            .testTag("deal_${deal.id}_stage_${stage.name}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            if (isCompleted) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = chipColor,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = "${stage.stepNumber}. ${stage.label}",
                                color = if (isCurrent) chipColor else TextPrimaryDark,
                                fontSize = 11.sp,
                                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Key Commercial Terms Grid
            Surface(
                color = BrandCharcoalSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TermKeyValue(
                            label = "Carpet / Built-up",
                            value = "${deal.carpetAreaSqFt.toInt()} / ${deal.builtUpAreaSqFt.toInt()} sq.ft",
                            modifier = Modifier.weight(1f)
                        )
                        TermKeyValue(
                            label = if (deal.transactionType.equals("Sale", true)) "Sale Price" else "Monthly Rent",
                            value = CalculationUtils.formatIndianCurrency(deal.monthlyRentOrPriceInr),
                            valueColor = PrimaryActionOrange,
                            modifier = Modifier.weight(1f)
                        )
                        TermKeyValue(
                            label = "3M Security Deposit",
                            value = CalculationUtils.formatIndianCurrency(deal.securityDepositInr),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TermKeyValue(
                            label = "Lock-in Period",
                            value = deal.lockInPeriod,
                            modifier = Modifier.weight(1f)
                        )
                        TermKeyValue(
                            label = "Escalation",
                            value = deal.escalationTerms,
                            modifier = Modifier.weight(1f)
                        )
                        TermKeyValue(
                            label = "Possession",
                            value = deal.possessionDate,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HorizontalDivider(color = BrandCharcoalBorder)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TermKeyValue(
                            label = "Client / Tenant",
                            value = deal.clientName,
                            modifier = Modifier.weight(1f)
                        )
                        TermKeyValue(
                            label = "Owner / Landlord",
                            value = deal.ownerName,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Separate Brokerage Breakdown Row (Client-Side, Landlord-Side, Developer)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BrokerageSideChip(
                    title = "Client Brok.",
                    amount = deal.clientBrokerageInr,
                    color = Color(0xFF3B82F6),
                    modifier = Modifier.weight(1f)
                )
                BrokerageSideChip(
                    title = "Landlord Brok.",
                    amount = deal.landlordBrokerageInr,
                    color = Color(0xFFE96932),
                    modifier = Modifier.weight(1f)
                )
                BrokerageSideChip(
                    title = "Developer Brok.",
                    amount = deal.developerBrokerageInr,
                    color = Color(0xFF8B5CF6),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 1-Tap Generate LOI / Term-Sheet PDF Button
            Button(
                onClick = onGenerateLoiPdf,
                enabled = !isGeneratingPdf,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("generate_loi_pdf_btn_${deal.id}")
            ) {
                if (isGeneratingPdf) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Building LOI / Term-Sheet PDF...", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                } else {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate LOI PDF", fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun TermKeyValue(
    label: String,
    value: String,
    valueColor: Color = TextPrimaryDark,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(end = 4.dp)) {
        Text(
            text = label,
            color = TextSecondaryDark,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value.ifBlank { "—" },
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BrokerageSideChip(
    title: String,
    amount: Double,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(text = title, color = TextSecondaryDark, fontSize = 9.5.sp, maxLines = 1)
            Text(
                text = if (amount > 0) CalculationUtils.formatIndianCurrency(amount) else "₹0",
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AddOrEditDealDialog(
    initialDeal: CrmDealItem?,
    inventory: List<PropertyItem>,
    onDismiss: () -> Unit,
    onSave: (CrmDealItem) -> Unit
) {
    var propertyCode by remember { mutableStateOf(initialDeal?.propertyCode ?: "") }
    var projectName by remember { mutableStateOf(initialDeal?.projectName ?: "") }
    var location by remember { mutableStateOf(initialDeal?.location ?: "Kharadi, Pune") }
    var transactionType by remember { mutableStateOf(initialDeal?.transactionType ?: "Lease") }
    var carpetArea by remember { mutableStateOf(initialDeal?.carpetAreaSqFt?.toInt()?.toString() ?: "2500") }
    var builtUpArea by remember { mutableStateOf(initialDeal?.builtUpAreaSqFt?.toInt()?.toString() ?: "3400") }
    var rentOrPrice by remember { mutableStateOf(initialDeal?.monthlyRentOrPriceInr?.toLong()?.toString() ?: "175000") }
    var securityDeposit by remember {
        mutableStateOf(
            initialDeal?.securityDepositInr?.toLong()?.toString()
                ?: ((initialDeal?.monthlyRentOrPriceInr ?: 175000.0) * 3).toLong().toString()
        )
    }
    var lockIn by remember { mutableStateOf(initialDeal?.lockInPeriod ?: "36 Months") }
    var escalation by remember { mutableStateOf(initialDeal?.escalationTerms ?: "5% every 12 months") }
    var possession by remember { mutableStateOf(initialDeal?.possessionDate ?: "Immediate") }
    var clientName by remember { mutableStateOf(initialDeal?.clientName ?: "") }
    var ownerName by remember { mutableStateOf(initialDeal?.ownerName ?: "") }
    var stage by remember { mutableStateOf(initialDeal?.stage ?: CrmDealStage.LOI_TOKEN) }
    var clientBrok by remember { mutableStateOf(initialDeal?.clientBrokerageInr?.toLong()?.toString() ?: "175000") }
    var landlordBrok by remember { mutableStateOf(initialDeal?.landlordBrokerageInr?.toLong()?.toString() ?: "175000") }
    var developerBrok by remember { mutableStateOf(initialDeal?.developerBrokerageInr?.toLong()?.toString() ?: "0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BrandCharcoalCard,
        title = {
            Text(
                text = if (initialDeal == null) "New Deal / LOI Term Sheet" else "Update Deal & Brokerage",
                color = TextPrimaryDark,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = propertyCode,
                    onValueChange = { code ->
                        propertyCode = code
                        val matched = inventory.firstOrNull { it.propertyCode.equals(code.trim(), ignoreCase = true) }
                        if (matched != null) {
                            projectName = matched.projectName
                            location = matched.location
                            if (matched.carpetAreaSqFt > 0) carpetArea = matched.carpetAreaSqFt.toInt().toString()
                            val buSqFt = matched.builtUpAreaSqFt ?: 0.0
                            if (buSqFt > 0.0) builtUpArea = buSqFt.toInt().toString()
                            val r = matched.rent ?: matched.priceOrRent
                            if (r > 0) {
                                rentOrPrice = r.toLong().toString()
                                val dep = matched.deposit ?: 0.0
                                securityDeposit = (if (dep > 0.0) dep else (r * 3.0)).toLong().toString()
                            }
                            if (!matched.ownerName.isNullOrBlank()) ownerName = matched.ownerName
                        }
                    },
                    label = { Text("Property Code (auto-fills from inventory)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = carpetArea,
                        onValueChange = { carpetArea = it },
                        label = { Text("Carpet (sq.ft)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = builtUpArea,
                        onValueChange = { builtUpArea = it },
                        label = { Text("Built-up (sq.ft)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rentOrPrice,
                        onValueChange = {
                            rentOrPrice = it
                            val v = it.toDoubleOrNull() ?: 0.0
                            if (v > 0 && transactionType.equals("Lease", true)) {
                                securityDeposit = (v * 3).toLong().toString()
                            }
                        },
                        label = { Text("Monthly Rent / Price (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = securityDeposit,
                        onValueChange = { securityDeposit = it },
                        label = { Text("3M Security Deposit (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lockIn,
                        onValueChange = { lockIn = it },
                        label = { Text("Lock-in") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = escalation,
                        onValueChange = { escalation = it },
                        label = { Text("Escalation") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = possession,
                    onValueChange = { possession = it },
                    label = { Text("Possession") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = clientName,
                    onValueChange = { clientName = it },
                    label = { Text("Client / Tenant Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ownerName,
                    onValueChange = { ownerName = it },
                    label = { Text("Owner / Landlord Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Brokerage Amounts (₹)",
                    color = PrimaryActionOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = clientBrok,
                        onValueChange = { clientBrok = it },
                        label = { Text("Client Side") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = landlordBrok,
                        onValueChange = { landlordBrok = it },
                        label = { Text("Landlord Side") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = developerBrok,
                        onValueChange = { developerBrok = it },
                        label = { Text("Developer") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val rentVal = rentOrPrice.toDoubleOrNull() ?: 0.0
                    val depVal = securityDeposit.toDoubleOrNull() ?: (rentVal * 3.0)
                    onSave(
                        CrmDealItem(
                            id = initialDeal?.id ?: ("DL-" + UUID.randomUUID().toString().take(5).uppercase(Locale.US)),
                            propertyCode = propertyCode.ifBlank { "CO-NEW" },
                            projectName = projectName.ifBlank { "Commercial Tower" },
                            location = location.ifBlank { "Pune" },
                            transactionType = transactionType,
                            carpetAreaSqFt = carpetArea.toDoubleOrNull() ?: 0.0,
                            builtUpAreaSqFt = builtUpArea.toDoubleOrNull() ?: 0.0,
                            monthlyRentOrPriceInr = rentVal,
                            securityDepositInr = depVal,
                            lockInPeriod = lockIn.ifBlank { "36 Months" },
                            escalationTerms = escalation.ifBlank { "5% every 12 months" },
                            possessionDate = possession.ifBlank { "Immediate" },
                            clientName = clientName.ifBlank { "Prospective Corporate Tenant" },
                            ownerName = ownerName.ifBlank { "Property Landlord" },
                            stage = stage,
                            clientBrokerageInr = clientBrok.toDoubleOrNull() ?: 0.0,
                            landlordBrokerageInr = landlordBrok.toDoubleOrNull() ?: 0.0,
                            developerBrokerageInr = developerBrok.toDoubleOrNull() ?: 0.0
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = Color.White)
            ) {
                Text("Save Deal", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondaryDark)
            }
        }
    )
}

private suspend fun generateAndShareLoiPdf(
    context: Context,
    deal: CrmDealItem
) {
    val pdfFile = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE }
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), bgPaint)

        // Navy Header Banner
        val navyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.parseColor("#0B132B") }
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 110f, navyPaint)

        val goldAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.parseColor("#D4AF37") }
        canvas.drawRect(0f, 106f, pageWidth.toFloat(), 112f, goldAccentPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#D4AF37")
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("THE LEASING COMPANY", 36f, 44f, titlePaint)
        canvas.drawText("LETTER OF INTENT (LOI) & COMMERCIAL TERM SHEET", 36f, 68f, subHeaderPaint)

        val metaWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#CBD5E1")
            textSize = 10f
        }
        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())
        canvas.drawText("Ref: ${deal.id}  |  Property Code: ${deal.propertyCode}  |  Date: $dateStr", 36f, 90f, metaWhitePaint)

        var y = 145f
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#0B132B")
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#475569")
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#0F172A")
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val rowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#F8FAFC")
        }
        val rowBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#E2E8F0")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        canvas.drawText("1. PARTIES & COMMERCIAL TERMS", 36f, y, sectionTitlePaint)
        y += 14f

        val priceLabel = if (deal.transactionType.equals("Sale", true)) {
            "Offered Sale Consideration"
        } else {
            "Offered Monthly License Fee (Rent)"
        }
        val rows: List<Pair<String, String>> = listOf(
            "Client / Intending Lessee / Buyer" to deal.clientName,
            "Owner / Intending Lessor / Seller" to deal.ownerName,
            "Property Code & Reference" to "${deal.propertyCode} (${deal.transactionType})",
            "Project / Building Name" to "${deal.projectName}, ${deal.location}",
            "Carpet Area / Built-up Area" to "${deal.carpetAreaSqFt.toInt()} sq.ft Carpet  |  ${deal.builtUpAreaSqFt.toInt()} sq.ft Built-up",
            priceLabel to CalculationUtils.formatIndianCurrency(deal.monthlyRentOrPriceInr),
            "Interest-Free Security Deposit (3 Months)" to CalculationUtils.formatIndianCurrency(deal.securityDepositInr),
            "Lock-in Period" to deal.lockInPeriod,
            "License Fee Escalation" to deal.escalationTerms,
            "Handover & Possession" to deal.possessionDate,
            "Current Deal Stage" to deal.stage.label
        )

        rows.forEachIndexed { index, pair ->
            val k = pair.first
            val v = pair.second
            val top = y
            val bottom = y + 34f
            val rect = RectF(36f, top, (pageWidth - 36).toFloat(), bottom)
            if (index % 2 == 0) {
                canvas.drawRoundRect(rect, 4f, 4f, rowBgPaint)
            }
            canvas.drawRoundRect(rect, 4f, 4f, rowBorderPaint)
            canvas.drawText(k, 48f, top + 21f, labelPaint)
            canvas.drawText(v.take(54), 255f, top + 21f, valuePaint)
            y = bottom + 6f
        }

        y += 20f
        canvas.drawText("2. STANDARD TERMS & NEXT STEPS", 36f, y, sectionTitlePaint)
        y += 18f
        val noteLines = listOf(
            "• This Letter of Intent (LOI) records the principal commercial understanding subject to formal agreement execution.",
            "• Stamp Duty & Registration charges shall be borne as mutually agreed prior to Registration.",
            "• Professional Advisory & Brokerage payable to The Leasing Company upon execution/registration."
        )
        noteLines.forEach { line ->
            canvas.drawText(line, 40f, y, labelPaint)
            y += 18f
        }

        // Signature Blocks
        y = 735f
        canvas.drawLine(36f, y, 220f, y, rowBorderPaint)
        canvas.drawLine(360f, y, 555f, y, rowBorderPaint)
        canvas.drawText("For ${deal.clientName.take(28)}", 36f, y + 18f, valuePaint)
        canvas.drawText("Authorized Signatory (Client)", 36f, y + 34f, labelPaint)

        canvas.drawText("For ${deal.ownerName.take(28)}", 360f, y + 18f, valuePaint)
        canvas.drawText("Authorized Signatory (Owner / Lessor)", 360f, y + 34f, labelPaint)

        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared_pdfs").apply { mkdirs() }
        val safeCode = deal.propertyCode.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val outFile = File(dir, "TLC_LOI_TermSheet_${safeCode}.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "LOI / Commercial Term Sheet - ${deal.projectName} (${deal.propertyCode})"
            )
            putExtra(
                Intent.EXTRA_TEXT,
                "Please find attached the Letter of Intent (LOI) & Commercial Term Sheet for ${deal.projectName} (${deal.propertyCode})."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share LOI / Term-Sheet PDF"))
    } catch (e: Exception) {
        Toast.makeText(context, "LOI PDF generated: ${pdfFile.name}", Toast.LENGTH_LONG).show()
    }
}
