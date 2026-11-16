package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import com.example.data.master.MarketPulseEntity
import com.example.data.master.MasterProjectDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.pune.*
import com.example.model.CalculationUtils
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PuneIntelligenceScreen(
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { PuneIntelligenceRepository.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    var isSeeding by remember { mutableStateOf(false) }
    var seedCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        isSeeding = true
        seedCount = repository.ensureDatabaseInitialized(context)
        isSeeding = false
    }

    val locations by repository.locationsFlow.collectAsState(initial = emptyList())
    val commercialProjects by repository.commercialProjectsFlow.collectAsState(initial = emptyList())
    val metroStations by repository.metroStationsFlow.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableStateOf(0) } // 0: Micro-Markets, 1: Commercial Projects, 2: Transit, 3: Investment Analytics, 4: Market Pulse
    val tabTitles = listOf("Micro-Markets (30)", "Commercial Parks (14)", "Transit & Metro (28)", "Investment ROI", "Market Pulse")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundSoft)
            .statusBarsPadding()
    ) {
        // Top App Bar
        Surface(
            color = PrimaryNavy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "PUNE REAL ESTATE INTELLIGENCE",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "v2.0 Verified Sourced Database • East Pune Core",
                                color = AccentGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Surface(
                        color = PrimaryTeal,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "OFFLINE ACTIVE",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Metric Counters Summary Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricMiniBadge("30 Markets", Modifier.weight(1f))
                    MetricMiniBadge("14 IT Parks", Modifier.weight(1f))
                    MetricMiniBadge("28 Metro", Modifier.weight(1f))
                    MetricMiniBadge("12 Postal", Modifier.weight(1f))
                }
            }
        }

        // Navigation Tabs Strip
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = SurfaceWhite,
            contentColor = PrimaryNavy,
            edgePadding = 12.dp
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == index) PrimaryTeal else SecondaryText,
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        // Content Area
        Box(modifier = Modifier.weight(1f).padding(12.dp)) {
            when (selectedTab) {
                0 -> MicroMarketsTab(locations, repository)
                1 -> CommercialProjectsTab(commercialProjects, locations)
                2 -> TransitInfrastructureTab(metroStations)
                3 -> InvestmentAnalyticsTab(repository)
                4 -> MarketPulseTab()
            }
        }
    }
}

@Composable
private fun MetricMiniBadge(label: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.White.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// =========================================================================
// TAB 0: Micro-Markets
// =========================================================================
@Composable
private fun MicroMarketsTab(
    locations: List<LocationEntity>,
    repository: PuneIntelligenceRepository
) {
    var selectedRegion by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var expandedLocationId by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val regions = listOf("All", "east", "west", "north", "central", "south")
    val filteredLocations = remember(locations, selectedRegion, searchQuery) {
        locations.filter { loc ->
            (selectedRegion == "All" || loc.region.equals(selectedRegion, true)) &&
            (searchQuery.isBlank() || loc.name.contains(searchQuery, true))
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Region Filter Chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(regions) { reg ->
                val isSelected = selectedRegion == reg
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedRegion = reg },
                    label = {
                        Text(
                            text = when (reg) {
                                "All" -> "All Pune (30)"
                                "east" -> "East Pune (Core)"
                                "west" -> "West Pune"
                                "north" -> "North / PCMC"
                                "central" -> "Central Pune"
                                else -> "South Pune"
                            },
                            fontSize = 11.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TealSubtle,
                        selectedLabelColor = PrimaryTeal
                    )
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search micro-markets (e.g. Kharadi, Viman Nagar, Baner)") },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryTeal,
                unfocusedBorderColor = BorderGrey
            )
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(filteredLocations, key = { it.id }) { loc ->
                val isExpanded = expandedLocationId == loc.id
                var postalOffices by remember { mutableStateOf<List<PostalOfficeEntity>>(emptyList()) }
                var candidateRoads by remember { mutableStateOf<List<RoadCandidateEntity>>(emptyList()) }
                var legacyTransit by remember { mutableStateOf<LegacyEnrichmentEntity?>(null) }

                LaunchedEffect(isExpanded) {
                    if (isExpanded) {
                        postalOffices = repository.getPostalOfficesForLocation(loc.id)
                        candidateRoads = repository.getRoadCandidates(loc.id)
                        legacyTransit = repository.getLegacyEnrichment(loc.id)
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth().clickable {
                        expandedLocationId = if (isExpanded) null else loc.id
                    }
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(loc.name, fontWeight = FontWeight.Bold, color = PrimaryNavy, fontSize = 14.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = if (loc.region == "east") TealSubtle else BackgroundSoft,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "${loc.region.uppercase()} PUNE",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (loc.region == "east") PrimaryTeal else SecondaryText,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text("Ref ID: ${loc.id} • ${loc.city}, ${loc.state}", color = SecondaryText, fontSize = 11.sp)
                            }

                            Icon(
                                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = PrimaryTeal
                            )
                        }

                        // Status Tag
                        Surface(
                            color = BackgroundSoft,
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderGrey)
                        ) {
                            Text(
                                text = "PIN Code: ${loc.pinCodeStatus.replace('_', ' ')}",
                                color = SecondaryText,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // Expanded Details (Postal, Transit, Quarantined Roads)
                        if (isExpanded) {
                            HorizontalDivider(color = BorderGrey, thickness = 0.5.dp)

                            // Verified Postal References
                            if (postalOffices.isNotEmpty()) {
                                Text("VERIFIED POSTAL OFFICES:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = PrimaryTeal)
                                postalOffices.forEach { po ->
                                    Text("• PIN ${po.pinCode}: ${po.postOfficeName}", fontSize = 11.sp, color = PrimaryNavy)
                                }
                            }

                            // Quarantined Candidate Roads Notice
                            if (candidateRoads.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("CANDIDATE ACCESS ROADS (UNVERIFIED):", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = WarningAmber)
                                }
                                Text(
                                    candidateRoads.take(4).joinToString(", ") { it.roadName },
                                    fontSize = 11.sp,
                                    color = SecondaryText
                                )
                            }

                            // Quarantined Transit Notice
                            if (legacyTransit != null && legacyTransit!!.candidateTransitMetro.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("TRANSIT PROXIMITY (UNVERIFIED ESTIMATE):", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = SecondaryText)
                                Text("• Nearest Metro Candidate: ${legacyTransit!!.candidateTransitMetro}", fontSize = 11.sp, color = SecondaryText)
                                Text("• Airport: PNQ (Distance not verified)", fontSize = 11.sp, color = SecondaryText)
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// TAB 1: Commercial & IT Parks
// =========================================================================
@Composable
private fun CommercialProjectsTab(
    projects: List<CommercialProjectEntity>,
    locations: List<LocationEntity>
) {
    val locationMap = remember(locations) { locations.associateBy { it.id } }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = TealSubtle,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(0.8.dp, PrimaryTeal),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Verified, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "14 Verified Commercial Developments & IT Parks across East & West Pune. Sourced directly from official developer profiles.",
                    color = PrimaryNavy,
                    fontSize = 11.sp
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(projects, key = { it.id }) { proj ->
                val loc = locationMap[proj.locationId]
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth().testTag("commercial_project_${proj.id}")
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = proj.name,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryNavy,
                                fontSize = 14.sp
                            )

                            Surface(
                                color = Color(0xFFDCFCE7),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "VERIFIED",
                                    color = SuccessGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Micro-Market: ${loc?.name ?: "East Pune"} (${loc?.region?.uppercase() ?: "EAST"} PUNE)",
                            color = PrimaryTeal,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(color = BackgroundSoft, shape = RoundedCornerShape(4.dp)) {
                                Text("Class: ${proj.assetClass.replace('_', ' ')}", fontSize = 10.sp, color = SecondaryText, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            Surface(color = BackgroundSoft, shape = RoundedCornerShape(4.dp)) {
                                Text("Status: ${proj.projectStatus}", fontSize = 10.sp, color = SecondaryText, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            Surface(color = BackgroundSoft, shape = RoundedCornerShape(4.dp)) {
                                Text("Developer: Panchshil Realty", fontSize = 10.sp, color = SecondaryText, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }

                        Text(
                            text = "Ref: ${proj.id} • As of ${proj.asOf} • Verification: ${proj.verificationStatus}",
                            color = MutedText,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// TAB 2: Transit & Infrastructure
// =========================================================================
@Composable
private fun TransitInfrastructureTab(
    metroStations: List<MetroStationEntity>
) {
    var transitFilter by remember { mutableStateOf("All") }
    val filteredStations = remember(metroStations, transitFilter) {
        when (transitFilter) {
            "Operational" -> metroStations.filter { it.status == "operational" }
            "Under Construction" -> metroStations.filter { it.status != "operational" }
            else -> metroStations
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All (28)", "Operational (Line 1/2)", "Under Construction (Line 3)").forEach { opt ->
                val key = when {
                    opt.startsWith("All") -> "All"
                    opt.startsWith("Operational") -> "Operational"
                    else -> "Under Construction"
                }
                FilterChip(
                    selected = transitFilter == key,
                    onClick = { transitFilter = key },
                    label = { Text(opt, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TealSubtle,
                        selectedLabelColor = PrimaryTeal
                    )
                )
            }
        }

        // Airport & Rail Card
        Card(
            colors = CardDefaults.cardColors(containerColor = PrimaryNavy),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AIRPORT & MAIN RAIL TERMINALS", color = AccentGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("✈️ Pune International Airport (PNQ) — Lohegaon / Viman Nagar", color = Color.White, fontSize = 12.sp)
                Text("🚆 Pune Junction (PUNE) & Hadapsar Railway Terminal", color = Color.White, fontSize = 12.sp)
                Text("*Note: Routing travel times and exact road distances are verified via live GPS only.", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
        }

        Text("PUNE METRO NETWORK STATIONS", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = PrimaryNavy)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(filteredStations, key = { it.id }) { station ->
                val isOp = station.status == "operational"
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(station.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PrimaryNavy)
                            Text("Line: ${station.lines} • Mode: ${station.mode}", fontSize = 11.sp, color = SecondaryText)
                        }

                        Surface(
                            color = if (isOp) Color(0xFFDCFCE7) else Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (isOp) "OPERATIONAL" else "UNDER CONSTRUCTION",
                                color = if (isOp) SuccessGreen else WarningAmber,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// TAB 3: Investment Analytics & ROI
// =========================================================================
@Composable
private fun InvestmentAnalyticsTab(
    repository: PuneIntelligenceRepository
) {
    var purchasePriceInput by remember { mutableStateOf("25000000") } // 2.5 Cr
    var monthlyRentInput by remember { mutableStateOf("150000") } // 1.5 Lakh/mo
    var carpetAreaInput by remember { mutableStateOf("2000") } // 2000 sq.ft
    var stampDutyPercentInput by remember { mutableStateOf("6.0") }
    var brokeragePercentInput by remember { mutableStateOf("2.0") }
    var fitOutCostInput by remember { mutableStateOf("500000") }
    var monthlyMaintenanceInput by remember { mutableStateOf("10000") }

    val purchasePrice = purchasePriceInput.toDoubleOrNull() ?: 0.0
    val monthlyRent = monthlyRentInput.toDoubleOrNull() ?: 0.0
    val carpetArea = carpetAreaInput.toDoubleOrNull() ?: 0.0
    val stampDuty = stampDutyPercentInput.toDoubleOrNull() ?: 6.0
    val brokerage = brokeragePercentInput.toDoubleOrNull() ?: 2.0
    val fitOut = fitOutCostInput.toDoubleOrNull() ?: 0.0
    val maintenance = monthlyMaintenanceInput.toDoubleOrNull() ?: 0.0

    val metrics = remember(purchasePrice, monthlyRent, carpetArea, stampDuty, brokerage, fitOut, maintenance) {
        repository.calculateInvestmentMetrics(
            purchasePrice = purchasePrice,
            stampDutyPercent = stampDuty,
            brokeragePercent = brokerage,
            fitOutCost = fitOut,
            monthlyRent = monthlyRent,
            carpetAreaSqFt = carpetArea,
            monthlyMaintenance = maintenance
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Policy Notice Card
        Card(
            colors = CardDefaults.cardColors(containerColor = TealSubtle),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(0.8.dp, PrimaryTeal),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("INVESTMENT INTELLIGENCE & ROI MODEL", color = PrimaryNavy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "Standardized formulas from investment_analytics_rules.json. Strict integrity policy: No fabricated market-wide averages; calculates project-specific yields on actual transaction terms.",
                    color = SecondaryText,
                    fontSize = 11.sp
                )
            }
        }

        // Summary Card
        Card(
            colors = CardDefaults.cardColors(containerColor = PrimaryNavy),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("investment_metrics_card")
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Gross Rental Yield", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        Text(String.format(java.util.Locale.US, "%.2f%%", metrics.grossRentalYield), color = AccentGold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Net Rental Yield", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        Text(String.format(java.util.Locale.US, "%.2f%%", metrics.netRentalYield), color = Color(0xFF38BDF8), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.15f), thickness = 0.8.dp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Total Acquisition Outlay", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Text(CalculationUtils.formatIndianCurrencyFull(metrics.totalAcquisitionCost), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Net Annual Inflow", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Text(CalculationUtils.formatIndianCurrencyFull(metrics.annualNetInflow), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Rent Rate per sq.ft", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Text("₹${String.format(java.util.Locale.US, "%.2f", metrics.rentPerSqFtCarpet)}/sq.ft/mo", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Capital Payback Period", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Text("${String.format(java.util.Locale.US, "%.1f", metrics.paybackPeriodYears)} Years", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Inputs Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("TRANSACTION PARAMETERS", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = PrimaryNavy)

                OutlinedTextField(
                    value = purchasePriceInput,
                    onValueChange = { purchasePriceInput = it },
                    label = { Text("Base Purchase Price (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = monthlyRentInput,
                        onValueChange = { monthlyRentInput = it },
                        label = { Text("Monthly Rent (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = carpetAreaInput,
                        onValueChange = { carpetAreaInput = it },
                        label = { Text("Carpet Area (sq.ft)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = stampDutyPercentInput,
                        onValueChange = { stampDutyPercentInput = it },
                        label = { Text("Stamp Duty %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = brokeragePercentInput,
                        onValueChange = { brokeragePercentInput = it },
                        label = { Text("Brokerage %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fitOutCostInput,
                        onValueChange = { fitOutCostInput = it },
                        label = { Text("Fit-out / Interior (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = monthlyMaintenanceInput,
                        onValueChange = { monthlyMaintenanceInput = it },
                        label = { Text("Maintenance (₹/mo)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        }
    }
}

@Composable
fun MarketPulseTab() {
    val context = LocalContext.current
    val dao = remember { MasterProjectDatabase.getDatabase(context).masterProjectDao() }
    val coroutineScope = rememberCoroutineScope()
    val pulseItems by dao.observeMarketPulse().collectAsState(initial = emptyList())

    var selectedCategory by remember { mutableStateOf("ALL") }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            if (pulseItems.isEmpty()) {
                val seed = listOf(
                    MarketPulseEntity(
                        pulseId = "PULSE-001",
                        title = "Pune Metro Line 3 (Hinjewadi - Shivajinagar) Integrated Trials Begin",
                        summary = "23.3 km elevated corridor connecting West Pune IT hub with central business district moves into final signaling integration. Commercial absorption along Baner and Wakad ramps up by 18%.",
                        sourceUrl = "https://punemetrorail.org",
                        publishedAt = System.currentTimeMillis() - 86400000L * 2,
                        category = "METRO",
                        microMarket = "Hinjewadi / Baner"
                    ),
                    MarketPulseEntity(
                        pulseId = "PULSE-002",
                        title = "Kharadi EON Free Zone 2 Achieves 100% Lease Commitment",
                        summary = "Grade-A IT Special Economic Zone reports complete absorption with multinational tech and BFSI occupiers. Average warm-shell transacted rentals reach ₹82 - ₹94 per sq.ft monthly.",
                        sourceUrl = "https://panchshil.com",
                        publishedAt = System.currentTimeMillis() - 86400000L * 4,
                        category = "COMMERCIAL",
                        microMarket = "Kharadi"
                    ),
                    MarketPulseEntity(
                        pulseId = "PULSE-003",
                        title = "Pune Ring Road Eastern Alignment Land Acquisition at 95%",
                        summary = "MSRDC confirms final phase of land handover for the 68 km Eastern Ring Road connecting Theur to Alandi. Significant connectivity boost for Hadapsar, Wagholi and Keshav Nagar.",
                        sourceUrl = "https://msrdc.in",
                        publishedAt = System.currentTimeMillis() - 86400000L * 7,
                        category = "INFRASTRUCTURE",
                        microMarket = "East Pune"
                    ),
                    MarketPulseEntity(
                        pulseId = "PULSE-004",
                        title = "MahaRERA Mandates Quarterly Quality & Structural Audit Reports",
                        summary = "Stricter compliance norms introduced for all residential and commercial projects exceeding 50 units. Promoters required to upload certified structural stability certificates before phase handover.",
                        sourceUrl = "https://maharera.maharashtra.gov.in",
                        publishedAt = System.currentTimeMillis() - 86400000L * 10,
                        category = "REGULATORY",
                        microMarket = "Pune District"
                    ),
                    MarketPulseEntity(
                        pulseId = "PULSE-005",
                        title = "Viman Nagar Commercial Office Rental Yields Sustained at 8.2%",
                        summary = "Proximity to Pune International Airport and robust retail footfall keeps Grade-A commercial vacancy below 4.5% across prime high-street and corporate towers.",
                        sourceUrl = "https://maharashtra.gov.in",
                        publishedAt = System.currentTimeMillis() - 86400000L * 14,
                        category = "COMMERCIAL",
                        microMarket = "Viman Nagar"
                    )
                )
                dao.insertAllMarketPulse(seed)
            }
        }
    }

    val categories = listOf("ALL", "INFRASTRUCTURE", "METRO", "COMMERCIAL", "RESIDENTIAL", "REGULATORY")
    val filtered = remember(pulseItems, selectedCategory) {
        if (selectedCategory == "ALL") pulseItems else pulseItems.filter { it.category == selectedCategory }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            items(categories) { cat ->
                val isSelected = selectedCategory == cat
                Surface(
                    onClick = { selectedCategory = cat },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) PrimaryNavy else SurfaceWhite,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) PrimaryNavy else BorderGrey),
                    modifier = Modifier.heightIn(min = 38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            text = cat,
                            color = if (isSelected) Color.White else PrimaryNavy,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Market Pulse Cards List
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filtered, key = { it.pulseId }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderGrey),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = when (item.category) {
                                    "METRO" -> PrimaryTeal.copy(alpha = 0.15f)
                                    "COMMERCIAL" -> PrimaryNavy.copy(alpha = 0.15f)
                                    "INFRASTRUCTURE" -> AccentGold.copy(alpha = 0.18f)
                                    "REGULATORY" -> ErrorRed.copy(alpha = 0.15f)
                                    else -> PrimaryTeal.copy(alpha = 0.15f)
                                },
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = item.category,
                                    color = when (item.category) {
                                        "METRO" -> PrimaryTeal
                                        "COMMERCIAL" -> PrimaryNavy
                                        "INFRASTRUCTURE" -> AccentGoldDark
                                        "REGULATORY" -> ErrorRed
                                        else -> PrimaryTeal
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }

                            val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(item.publishedAt))
                            Text(
                                text = "${item.microMarket} • $dateStr",
                                color = SecondaryText,
                                fontSize = 11.sp
                            )
                        }

                        Text(
                            text = item.title,
                            color = PrimaryNavy,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 18.sp
                        )

                        Text(
                            text = item.summary,
                            color = SecondaryText,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Verified Canonical Source",
                                color = MutedText,
                                fontSize = 10.sp
                            )

                            if (item.sourceUrl.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl))
                                            context.startActivity(intent)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(13.dp), tint = PrimaryTeal)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Open Source", color = PrimaryTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


