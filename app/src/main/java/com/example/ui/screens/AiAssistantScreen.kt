package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.*
import com.example.model.*
import com.example.ui.components.PropertyCard
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AiAssistantScreen(
    inventory: List<PropertyItem>,
    isInternalMode: Boolean,
    onSaveNewProperty: (PropertyItem) -> Unit,
    onPropertyClick: (PropertyItem) -> Unit,
    onShareWhatsApp: (PropertyItem) -> Unit,
    onOpenClientMatcher: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val aiService = remember { GeminiRealEstateService() }

    var selectedSubTab by remember { mutableStateOf(0) } // 0: Natural Search, 1: WhatsApp/Insta Parser, 2: Client Matching, 3: Pitch Generator

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .testTag("ai_assistant_screen")
    ) {
        // AI Hub Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BrandGold, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI PROPERTY SEARCH",
                            color = TextPrimaryDark,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Text(
                        text = "GEMINI-POWERED INVENTORY MATCHING • REAL MASTER DATA ONLY",
                        color = BrandGold,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sub-tabs row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = listOf("AI Search", "Msg Parser", "Client Matcher", "Pitch Gen")
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedSubTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) BrandGold else BrandCharcoalSurface)
                            .border(0.8.dp, if (isSelected) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
                            .clickable { selectedSubTab = index }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) BrandCharcoalDark else TextSecondaryDark,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        when (selectedSubTab) {
            0 -> NaturalSearchSection(
                aiService = aiService,
                inventory = inventory,
                isInternalMode = isInternalMode,
                onPropertyClick = onPropertyClick,
                onShareWhatsApp = onShareWhatsApp,
                onOpenClientMatcher = onOpenClientMatcher
            )
            1 -> WhatsAppParserSection(
                aiService = aiService,
                onSave = { draft ->
                    val newProp = PropertyItem(
                        id = "prop_ai_${System.currentTimeMillis()}",
                        propertyCode = "AI-${(100..999).random()}",
                        tab = draft.tab,
                        projectName = draft.projectName.ifBlank { "Commercial Center" },
                        location = draft.location.ifBlank { "Pune" },
                        unitNumber = draft.unitNumber.ifBlank { "Available Unit" },
                        carpetAreaSqFt = draft.carpetAreaSqFt,
                        priceOrRent = draft.priceOrRent,
                        deposit = draft.deposit,
                        furnishing = draft.furnishing,
                        status = AvailabilityStatus.NOT_CONFIRMED,
                        ownerName = draft.contactName,
                        ownerContact = draft.contactPhone,
                        internalRemarks = draft.remarks
                    )
                    onSaveNewProperty(newProp)
                    Toast.makeText(context, "Saved to inventory as NOT CONFIRMED!", Toast.LENGTH_SHORT).show()
                }
            )
            2 -> ClientMatcherSection(
                aiService = aiService,
                inventory = inventory,
                onPropertyClick = onPropertyClick,
                onShareWhatsApp = onShareWhatsApp
            )
            3 -> PitchGeneratorSection(
                aiService = aiService,
                inventory = inventory
            )
        }
    }
}

@Composable
fun NaturalSearchSection(
    aiService: GeminiRealEstateService,
    inventory: List<PropertyItem>,
    isInternalMode: Boolean,
    onPropertyClick: (PropertyItem) -> Unit,
    onShareWhatsApp: (PropertyItem) -> Unit,
    onOpenClientMatcher: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember {
        mutableStateOf("Fully furnished office in Kharadi, 1,500–2,000 sq.ft. carpet, monthly rent under ₹2 lakh, ready to move.")
    }
    var isSearching by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<NaturalSearchResult?>(null) }
    var activeRequirement by remember { mutableStateOf<ExtractedClientRequirement?>(null) }

    val sampleQueries = listOf(
        "Fully furnished office in Kharadi, 1,500–2,000 sq.ft. carpet, monthly rent under ₹2 lakh, ready to move.",
        "3 BHK luxury apartment in Baner or Viman Nagar under ₹2 Cr with 2 car parks",
        "Pre-leased commercial shop in Hinjewadi or Balewadi with ROI above 7%"
    )

    fun performSearch() {
        if (searchQuery.isBlank()) return
        isSearching = true
        coroutineScope.launch {
            val extracted = aiService.extractRequirementsWithGemini(searchQuery)
            activeRequirement = extracted
            searchResult = aiService.executeNaturalSearch(extracted, inventory)
            isSearching = false
        }
    }

    // Perform initial search on mount
    LaunchedEffect(Unit) {
        performSearch()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("natural_search_section")
    ) {
        // Section Header
        Text(
            text = "AI PROPERTY SEARCH",
            color = TextPrimaryDark,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Describe your client's requirement. AI will find suitable properties from your inventory.",
            color = TextSecondaryDark,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Large Multi-line Text Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .testTag("ai_search_input"),
            placeholder = {
                Text(
                    text = "Example: Fully furnished office in Kharadi, 1,500–2,000 sq.ft. carpet, monthly rent under ₹2 lakh, ready to move.",
                    color = TextMutedDark,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = BrandCharcoalSurface,
                unfocusedContainerColor = BrandCharcoalSurface,
                focusedBorderColor = BrandGold,
                unfocusedBorderColor = BrandCharcoalBorder,
                focusedTextColor = TextPrimaryDark,
                unfocusedTextColor = TextPrimaryDark
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Quick sample chips & action buttons
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(sampleQueries) { sample ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandCharcoalSurface)
                        .border(0.8.dp, BrandCharcoalBorder, RoundedCornerShape(12.dp))
                        .clickable {
                            searchQuery = sample
                            performSearch()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = sample.take(28) + "...",
                        color = BrandGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    searchQuery = ""
                    searchResult = null
                    activeRequirement = null
                },
                modifier = Modifier.testTag("clear_search_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondaryDark),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, BrandCharcoalBorder),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear Search", fontSize = 11.sp)
            }

            Button(
                onClick = { performSearch() },
                modifier = Modifier.testTag("find_properties_button"),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                shape = RoundedCornerShape(8.dp),
                enabled = !isSearching && searchQuery.isNotBlank()
            ) {
                if (isSearching) {
                    CircularProgressIndicator(color = BrandCharcoalDark, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Interpreting...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Find Properties", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Active Extracted Filters Banner
        if (activeRequirement != null) {
            val req = activeRequirement!!
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BrandGold.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .testTag("extracted_criteria_card"),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BrandGold, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "EXTRACTED CRITERIA",
                                color = BrandGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(BrandCharcoalDark)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(req.extractionSource, color = TextMutedDark, fontSize = 9.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = req.toCriteriaSummary(),
                        color = TextPrimaryDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Transfer to Step 6 Client Matcher Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                onOpenClientMatcher?.invoke(searchQuery)
                            },
                            modifier = Modifier.testTag("open_in_client_matcher_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandGold),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.PersonSearch, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open in Client Matcher", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Vague Query Warning Banner
        if (searchResult?.isVague == true) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03))
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Your request is broad. Refine location or budget for tighter results.",
                        color = Color(0xFFFEF3C7),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Search Results List
        if (searchResult != null) {
            val res = searchResult!!

            if (res.exactMatches.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${res.exactMatches.size} Matching Inventory Properties",
                        color = BrandGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(res.exactMatches) { prop ->
                        PropertyCard(
                            property = prop,
                            isInternalMode = isInternalMode,
                            isFavorite = false,
                            isInComparison = false,
                            onCardClick = { onPropertyClick(prop) },
                            onToggleFavorite = {},
                            onToggleCompare = {},
                            onShareWhatsApp = { onShareWhatsApp(prop) }
                        )
                    }
                }
            } else {
                // Zero Matches Handling
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 80.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, StatusUnconfirmed.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                        colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.SearchOff, contentDescription = null, tint = StatusUnconfirmed, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "No exact matches in active inventory.",
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Showing close alternative properties from your Master Inventory:",
                                color = TextSecondaryDark,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (res.alternatives.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Recommended Alternatives from Inventory (${res.alternatives.size})",
                            color = BrandGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        res.alternatives.forEach { alt ->
                            PropertyCard(
                                property = alt,
                                isInternalMode = isInternalMode,
                                isFavorite = false,
                                isInComparison = false,
                                onCardClick = { onPropertyClick(alt) },
                                onToggleFavorite = {},
                                onToggleCompare = {},
                                onShareWhatsApp = { onShareWhatsApp(alt) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WhatsAppParserSection(
    aiService: GeminiRealEstateService,
    onSave: (ExtractedPropertyDraft) -> Unit
) {
    var rawText by remember { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    var extractedDraft by remember { mutableStateOf<ExtractedPropertyDraft?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val sampleMessage = "Off for lease in Kharadi EON zone. 1450 sqft fully furnished office with 20 workstations, 2 cabins and conference room. Rent 1.10 Lac per month, 6 months deposit. Available ready to move. Call Rajesh 9822019988"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 80.dp)
    ) {
        Text(
            text = "Paste Raw WhatsApp Message or Instagram Caption",
            color = TextPrimaryDark,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "AI automatically extracts Category Tab, carpet area, rent/sale price, furnishing, and contact details into a reviewable draft.",
            color = TextSecondaryDark,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = rawText,
            onValueChange = { rawText = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            placeholder = { Text("Paste WhatsApp listing here...", color = TextMutedDark, fontSize = 12.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = BrandCharcoalSurface,
                unfocusedContainerColor = BrandCharcoalSurface,
                focusedBorderColor = BrandGold,
                unfocusedBorderColor = BrandCharcoalBorder,
                focusedTextColor = TextPrimaryDark,
                unfocusedTextColor = TextPrimaryDark
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { rawText = sampleMessage }) {
                Text("Insert Sample Message", color = BrandGold, fontSize = 11.sp)
            }

            Button(
                onClick = {
                    if (rawText.isNotBlank()) {
                        isExtracting = true
                        coroutineScope.launch {
                            val result = aiService.parsePropertyMessage(rawText)
                            extractedDraft = result
                            isExtracting = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                shape = RoundedCornerShape(8.dp),
                enabled = !isExtracting && rawText.isNotBlank()
            ) {
                if (isExtracting) {
                    CircularProgressIndicator(color = BrandCharcoalDark, modifier = Modifier.size(16.dp))
                } else {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Extract Listing", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (extractedDraft != null) {
            Spacer(modifier = Modifier.height(16.dp))
            val draft = extractedDraft!!

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BrandGold.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "REVIEW EXTRACTED DRAFT",
                            color = BrandGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(StatusUnconfirmed.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Defaults to Not Confirmed", color = StatusUnconfirmed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text("Source: ${draft.confidenceNotes}", color = TextMutedDark, fontSize = 10.sp)
                    HorizontalDivider(color = BrandCharcoalBorder)

                    Text("Category: ${draft.tab.title}", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Project: ${draft.projectName}", color = TextPrimaryDark, fontSize = 12.sp)
                    Text("Location: ${draft.location}", color = TextPrimaryDark, fontSize = 12.sp)
                    Text("Carpet Area: ${draft.carpetAreaSqFt.toInt()} sq.ft", color = TextPrimaryDark, fontSize = 12.sp)
                    Text("Commercials: ${CalculationUtils.formatIndianCurrency(draft.priceOrRent, draft.tab.isRental)}", color = BrandGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Furnishing: ${draft.furnishing.label}", color = TextPrimaryDark, fontSize = 12.sp)
                    if (draft.contactPhone.isNotBlank()) {
                        Text("Contact: ${draft.contactName} (${draft.contactPhone})", color = TextSecondaryDark, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            onSave(draft)
                            extractedDraft = null
                            rawText = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
                    ) {
                        Text("Approve & Save to Google Sheets Inventory", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ClientMatcherSection(
    aiService: GeminiRealEstateService,
    inventory: List<PropertyItem>,
    onPropertyClick: (PropertyItem) -> Unit,
    onShareWhatsApp: (PropertyItem) -> Unit
) {
    var reqText by remember { mutableStateOf("Client looking for 3 BHK in Baner or Viman Nagar with budget up to 2 Cr ready possession") }
    var matches by remember { mutableStateOf<List<ClientMatchResult>>(emptyList()) }

    LaunchedEffect(reqText) {
        matches = aiService.matchClientRequirements(reqText, inventory)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Client Requirement Matcher", color = TextPrimaryDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Paste incoming buyer/tenant inquiries to match best inventory instantly.", color = TextSecondaryDark, fontSize = 11.sp)

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = reqText,
            onValueChange = { reqText = it },
            modifier = Modifier.fillMaxWidth().height(90.dp),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = BrandCharcoalSurface,
                unfocusedContainerColor = BrandCharcoalSurface,
                focusedBorderColor = BrandGold,
                unfocusedBorderColor = BrandCharcoalBorder,
                focusedTextColor = TextPrimaryDark,
                unfocusedTextColor = TextPrimaryDark
            )
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text("Ranked Inventory Matches (${matches.size})", color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(matches) { match ->
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, BrandCharcoalBorder, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(match.property.projectName, color = TextPrimaryDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(StatusAvailable.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("${match.matchScorePercent}% Match", color = StatusAvailable, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Text("${match.property.location} • ${CalculationUtils.formatIndianCurrency(match.property.priceOrRent, match.property.tab.isRental)}", color = BrandGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

                        Spacer(modifier = Modifier.height(6.dp))
                        match.matchingReasons.forEach { reason ->
                            Text("✓ $reason", color = TextSecondaryDark, fontSize = 11.sp)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = { onPropertyClick(match.property) },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("View Listing", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PitchGeneratorSection(
    aiService: GeminiRealEstateService,
    inventory: List<PropertyItem>
) {
    val context = LocalContext.current
    var selectedProperty by remember { mutableStateOf(inventory.firstOrNull()) }
    var generatedPitch by remember { mutableStateOf("") }

    LaunchedEffect(selectedProperty) {
        if (selectedProperty != null) {
            generatedPitch = aiService.generatePropertyPitch(selectedProperty!!)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 80.dp)
    ) {
        Text("AI Property Pitch Generator", color = TextPrimaryDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Generates branded, client-safe WhatsApp promotional blurbs.", color = TextSecondaryDark, fontSize = 11.sp)

        Spacer(modifier = Modifier.height(12.dp))

        Text("Select Inventory Record:", color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        inventory.take(6).forEach { prop ->
            val isSelected = selectedProperty?.id == prop.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) BrandGold.copy(alpha = 0.2f) else BrandCharcoalSurface)
                    .border(0.8.dp, if (isSelected) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
                    .clickable { selectedProperty = prop }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${prop.propertyCode}: ${prop.projectName}", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(CalculationUtils.formatIndianCurrency(prop.priceOrRent, prop.tab.isRental), color = BrandGold, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text("Generated Branded Copy:", color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(BrandCharcoalSurface)
                .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Text(generatedPitch, color = Color(0xFFF1F5F9), fontSize = 12.sp, lineHeight = 18.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Pitch", generatedPitch))
                Toast.makeText(context, "Copied pitch to clipboard!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Copy Generated Pitch", fontWeight = FontWeight.Bold)
        }
    }
}

