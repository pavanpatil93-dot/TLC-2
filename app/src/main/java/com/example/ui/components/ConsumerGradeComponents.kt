package com.example.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PropertyItem
import com.example.ui.theme.*
import kotlinx.coroutines.delay

// =========================================================================
// 1. NETFLIX-STYLE "SMART RECENT PROPERTY HISTORY" TRAY
// =========================================================================
@Composable
fun NetflixRecentPropertiesTray(
    recentProperties: List<PropertyItem>,
    onPropertyClick: (PropertyItem) -> Unit,
    onQuickWhatsAppPitch: (PropertyItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (recentProperties.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(ChampagneGold)
                )
                Text(
                    text = "CONTINUE RE-PITCHING",
                    color = BrandGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }
            Text(
                text = "${recentProperties.size} viewed recently",
                color = TextSecondaryDark,
                fontSize = 10.sp
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(recentProperties.take(10), key = { "recent_${it.id}" }) { prop ->
                Card(
                    modifier = Modifier
                        .width(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(12.dp))
                        .clickable { onPropertyClick(prop) },
                    colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = prop.propertyCode,
                                color = BrandGold,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = StatusAvailable.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = prop.status.label,
                                    color = StatusAvailable,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = prop.projectName,
                            color = TextPrimaryDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = "${prop.location} • ${prop.carpetAreaSqFt.toInt()} sq.ft",
                            color = TextSecondaryDark,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = com.example.model.CalculationUtils.formatIndianCurrency(prop.priceOrRent, prop.tab.isRental),
                                color = ChampagneGold,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )

                            // Quick Pitch Button
                            IconButton(
                                onClick = { onQuickWhatsAppPitch(prop) },
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(StatusAvailable.copy(alpha = 0.15f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "WhatsApp Pitch",
                                    tint = StatusAvailable,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 2. ZOMATO-STYLE LIVE DEAL MILESTONE TRACKER
// =========================================================================
val DealMilestones = listOf(
    "New Inquiry",
    "Shortlisted",
    "Site Visit Scheduled",
    "Negotiation",
    "Token / Agreement Closed"
)

@Composable
fun ZomatoDealMilestoneTracker(
    currentStage: String,
    onStageSelect: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val currentIndex = DealMilestones.indexOfFirst { it.equals(currentStage, ignoreCase = true) }.let {
        if (it == -1) 0 else it
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DEAL PIPELINE MILESTONE",
                    color = BrandGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
                Text(
                    text = "Stage ${currentIndex + 1} of ${DealMilestones.size}",
                    color = TextSecondaryDark,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Horizontal step connector & badges
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(DealMilestones.size) { index ->
                    val isCompleted = index < currentIndex
                    val isCurrent = index == currentIndex
                    val stageName = DealMilestones[index]

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = when {
                            isCurrent -> ChampagneGold
                            isCompleted -> StatusAvailable
                            else -> BrandCharcoalDark
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when {
                                isCurrent -> ChampagneGold
                                isCompleted -> StatusAvailable
                                else -> BrandCharcoalBorder
                            }
                        ),
                        modifier = Modifier
                            .clickable(enabled = onStageSelect != null) {
                                onStageSelect?.invoke(stageName)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isCompleted) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isCurrent) Color.White else TextMutedDark)
                                )
                            }
                            Text(
                                text = stageName,
                                color = if (isCurrent || isCompleted) Color.White else TextSecondaryDark,
                                fontSize = 10.5.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 3. WHATSAPP BUSINESS-STYLE QUICK-SNIPPET / TEMPLATE REPLY HUB
// =========================================================================
data class PitchTemplate(
    val trigger: String,
    val title: String,
    val microMarket: String,
    val templateBody: String
)

val EastPuneQuickSnippets = listOf(
    PitchTemplate(
        trigger = "/kharadi_office",
        title = "Kharadi Corporate Office Lease",
        microMarket = "Kharadi (EON / WTC Hub)",
        templateBody = "Hello! Aurea & Ascend Commercial presents Grade-A Managed IT Office Space in Kharadi (near EON Free Zone / WTC). Fully furnished with 100+ ergonomic workstations, executive boardrooms, 100% DG power backup, and prime arterial connectivity. Available for immediate occupancy. Would you like to schedule an on-site walkthrough this afternoon?"
    ),
    PitchTemplate(
        trigger = "/kalyani_resale",
        title = "Kalyani Nagar Luxury Residence",
        microMarket = "Kalyani Nagar",
        templateBody = "Greetings from Aurea Living LLP! We have an exclusive off-market 3.5 BHK luxury residence in central Kalyani Nagar. Spacious carpet area, private deck overlooking green foliage, 2 reserved basement parking slots, and modern clubhouse amenities. Clean titles & ready possession. Shall I share the full digital brochure & walkthrough video?"
    ),
    PitchTemplate(
        trigger = "/kp_retail",
        title = "Koregaon Park Prime High-Street",
        microMarket = "Koregaon Park (Lane 5-7)",
        templateBody = "Ascend Commercial Solutions presents a rare high-street retail/F&B commercial space with prominent road frontage in Koregaon Park. High footfall catchment, 3-phase commercial power, and dedicated valet drop-off. Ideal for boutique showrooms and premium brands. When is a convenient time for a confidential review?"
    ),
    PitchTemplate(
        trigger = "/magarpatta_commercial",
        title = "Magarpatta / Hadapsar Pre-Leased",
        microMarket = "Magarpatta / Hadapsar",
        templateBody = "High-ROI Pre-Leased Commercial Unit in Magarpatta Cybercity vicinity with AAA-rated MNC tenant, 9-year lease lock-in, and 8.5% net rental yield. Zero broker dispute guarantee from Ascend Commercial Solutions. Let's connect for financial tear-sheet and cash-flow model."
    ),
    PitchTemplate(
        trigger = "/viman_rent",
        title = "Viman Nagar Commercial / Resi",
        microMarket = "Viman Nagar (Airport Belt)",
        templateBody = "Prime property available near Phoenix Marketcity & Pune Airport in Viman Nagar. Unmatched transit connectivity, 24/7 security, and ready infrastructure. Contact Aurea & Ascend for immediate verification & site visit coordination."
    )
)

@Composable
fun QuickSnippetReplyHub(
    onSelectSnippet: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(searchQuery) {
        if (searchQuery.isBlank()) EastPuneQuickSnippets
        else EastPuneQuickSnippets.filter {
            it.trigger.contains(searchQuery, ignoreCase = true) ||
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.microMarket.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = ChampagneGold)
                        Text("Quick-Snippet Reply Hub", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimaryDark)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark)
                    }
                }
                Text("Pre-approved East Pune pitch templates (type / in chat)", fontSize = 11.sp, color = TextSecondaryDark)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter by /trigger or location...", fontSize = 12.sp, color = TextMutedDark) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BrandGold, modifier = Modifier.size(16.dp)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = BrandCharcoalDark,
                        unfocusedContainerColor = BrandCharcoalDark,
                        focusedBorderColor = BrandGold,
                        unfocusedBorderColor = BrandCharcoalBorder,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered) { snippet ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(0.8.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    onSelectSnippet(snippet.templateBody)
                                    Toast.makeText(context, "Snippet loaded!", Toast.LENGTH_SHORT).show()
                                },
                            colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = snippet.trigger,
                                        color = BrandGold,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = snippet.microMarket,
                                        color = CommercialBlue,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(snippet.title, color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = snippet.templateBody,
                                    color = TextSecondaryDark,
                                    fontSize = 10.5.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = BrandCharcoalCard,
        shape = RoundedCornerShape(16.dp)
    )
}

// =========================================================================
// 4. BLINKIT-STYLE INSTANT FILTERING CHIPS (LOWER THUMB-ZONE)
// =========================================================================
@Composable
fun BlinkitInstantFilterChips(
    selectedFilter: String?,
    onSelectFilter: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val eastPuneChips = listOf(
        "Kharadi",
        "Kalyani Nagar",
        "Koregaon Park",
        "Viman Nagar",
        "Magarpatta",
        "Mundhwa",
        "Ready Office",
        "Pre-Leased",
        "Luxury 3BHK+",
        "Upcoming Vacancies"
    )

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            val isSelected = selectedFilter == null
            FilterChip(
                selected = isSelected,
                onClick = { onSelectFilter(null) },
                label = { Text("⚡ All East Pune", fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BrandGold,
                    selectedLabelColor = Color.White,
                    containerColor = BrandCharcoalSurface,
                    labelColor = TextSecondaryDark
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = if (isSelected) BrandGold else BrandCharcoalBorder
                )
            )
        }

        items(eastPuneChips) { chip ->
            val isSelected = selectedFilter == chip
            FilterChip(
                selected = isSelected,
                onClick = { onSelectFilter(if (isSelected) null else chip) },
                label = { Text(chip, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = BrandGold,
                    selectedLabelColor = Color.White,
                    containerColor = BrandCharcoalSurface,
                    labelColor = TextSecondaryDark
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = if (isSelected) BrandGold else BrandCharcoalBorder
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactStandardDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowCustom: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.height(44.dp)
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 10.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .height(44.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            ),
            singleLine = true,
            maxLines = 1
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = 12.sp) },
                    onClick = {
                        onValueSelected(option)
                        expanded = false
                    }
                )
            }
            if (allowCustom) {
                DropdownMenuItem(
                    text = { Text("+ Custom...", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) },
                    onClick = {
                        expanded = false
                        showCustomDialog = true
                    }
                )
            }
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Enter Custom $label") },
            text = {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = { Text(label) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (customInput.isNotBlank()) {
                        onValueSelected(customInput.trim())
                    }
                    showCustomDialog = false
                    customInput = ""
                }) {
                    Text("Add & Select")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableStandardDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredOptions = remember(searchQuery, options) {
        if (searchQuery.isBlank()) options
        else options.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    Surface(
        onClick = { expanded = true },
        shape = RoundedCornerShape(10.dp),
        color = SurfaceWhite,
        border = BorderStroke(
            width = if (expanded) 1.5.dp else 1.dp,
            color = if (expanded) PrimaryActionOrange else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .heightIn(min = 52.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = selectedValue.ifBlank { "Tap to search & select..." },
                    fontSize = 13.sp,
                    fontWeight = if (selectedValue.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                    color = if (selectedValue.isNotBlank()) TextPrimary else SecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select $label",
                tint = SecondaryText
            )
        }
    }

    if (expanded) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val searchFocusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        LaunchedEffect(expanded) {
            delay(140)
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        }

        ModalBottomSheet(
            onDismissRequest = {
                expanded = false
                searchQuery = ""
            },
            sheetState = sheetState,
            containerColor = SurfaceWhite
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(
                        onClick = {
                            expanded = false
                            searchQuery = ""
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = SecondaryText)
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Type project name or ID...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredOptions) { option ->
                        val isSelected = option == selectedValue
                        Surface(
                            onClick = {
                                onValueSelected(option)
                                expanded = false
                                searchQuery = ""
                            },
                            color = if (isSelected) PrimaryActionOrange.copy(alpha = 0.12f) else SurfaceMuted,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) PrimaryActionOrange.copy(alpha = 0.5f) else BorderSubtle
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = option,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) PrimaryActionOrange else TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = PrimaryActionOrange,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (filteredOptions.isEmpty()) {
                        item {
                            Text(
                                text = "No matching projects found",
                                fontSize = 12.5.sp,
                                color = SecondaryText,
                                modifier = Modifier.padding(vertical = 18.dp, horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    CompactStandardDropdown(
        label = label,
        selectedValue = selectedValue,
        options = options,
        onValueSelected = onValueSelected,
        modifier = modifier,
        allowCustom = true
    )
}

@Composable
fun SearchableDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SearchableStandardDropdown(
        label = label,
        selectedValue = selectedValue,
        options = options,
        onValueSelected = onValueSelected,
        modifier = modifier
    )
}



