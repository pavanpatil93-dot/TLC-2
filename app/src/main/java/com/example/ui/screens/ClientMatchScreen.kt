package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.example.ai.ClientMatchCategory
import com.example.ai.GeminiRealEstateService
import com.example.model.*
import com.example.ui.components.PropertyCard
import com.example.ui.theme.*

@Composable
fun ClientMatchScreen(
    tenantInventory: List<PropertyItem>,
    isInternalMode: Boolean,
    onPropertyClick: (PropertyItem) -> Unit,
    onShareWhatsApp: (PropertyItem) -> Unit,
    onViewMatchesOnMap: ((List<PropertyItem>) -> Unit)? = null,
    initialRequirement: String? = null,
    brandName: String = "REAL ESTATE INVENTORY PORTAL",
    contactNumbers: String = "9766880099 | 9766668099"
) {
    val context = LocalContext.current
    val aiService = remember { GeminiRealEstateService() }

    var requirementInput by remember(initialRequirement) {
        mutableStateOf(initialRequirement?.ifBlank { null } ?: "Client needs a fully furnished office in Kharadi, 1,000 to 1,500 carpet, rent under ₹1 lakh, minimum 15 workstations, immediate possession.")
    }
    var hasSearched by remember { mutableStateOf(false) }
    var matchCategories by remember { mutableStateOf<List<ClientMatchCategory>>(emptyList()) }
    var selectedShortlist by remember { mutableStateOf<Set<String>>(emptySet()) }

    // WhatsApp Content Generator Drawer / Section
    var showWhatsAppGenerator by remember { mutableStateOf(false) }
    var promptStyle by remember { mutableStateOf("Make a premium short WhatsApp message") }
    var generatedWhatsAppText by remember { mutableStateOf("") }

    // Run client matching on inventory
    fun runMatching() {
        if (requirementInput.isNotBlank()) {
            matchCategories = aiService.matchClientRequirementsCategorized(requirementInput, tenantInventory)
            hasSearched = true
        }
    }

    LaunchedEffect(requirementInput) {
        matchCategories = emptyList()
        selectedShortlist = emptySet()
        hasSearched = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .testTag("client_match_screen")
    ) {
        // Module Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PersonSearch, contentDescription = null, tint = BrandGold, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "FIND PROPERTY FOR CLIENT",
                            color = TextPrimaryDark,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Text(
                        text = "SEARCHING PRIVATE INVENTORY: $brandName",
                        color = BrandGold,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }

                if (selectedShortlist.isNotEmpty()) {
                    Button(
                        onClick = {
                            val selectedProps = tenantInventory.filter { selectedShortlist.contains(it.id) }
                            generatedWhatsAppText = aiService.generateCustomWhatsAppPitch(
                                properties = selectedProps,
                                promptStyle = promptStyle,
                                companyName = brandName,
                                companyContact = contactNumbers
                            )
                            showWhatsAppGenerator = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pitch (${selectedShortlist.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Requirement Input Box
            OutlinedTextField(
                value = requirementInput,
                onValueChange = { requirementInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                placeholder = { Text("Paste incoming client requirement text...", color = TextMutedDark, fontSize = 11.sp) },
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

            Spacer(modifier = Modifier.height(6.dp))
            Button(onClick = { runMatching() }, enabled = requirementInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Find Matching Properties")
            }
            if (hasSearched) {
                Text(if (matchCategories.isEmpty()) "No matching properties found. Try changing your requirement."
                    else "${matchCategories.sumOf { it.results.size }} results • Check match reasons before sharing",
                    color = TextPrimaryDark, fontSize = 12.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SampleReqChip(
                    label = "Office 1k sqft",
                    onClick = { requirementInput = "Client needs fully furnished office in Kharadi, 1000-1500 sqft, rent under 1 lakh, ready to move." }
                )
                SampleReqChip(
                    label = "Pre-Leased ROI",
                    onClick = { requirementInput = "Investor looking for pre-leased commercial property in Magarpatta or Baner with ROI above 7.5%." }
                )
                SampleReqChip(
                    label = "Luxury 3 BHK",
                    onClick = { requirementInput = "High net-worth family looking for luxury 3 or 4 BHK in Baner or Viman Nagar with budget up to 2 Cr." }
                )
            }

            if (onViewMatchesOnMap != null && matchCategories.any { it.results.isNotEmpty() }) {
                val matchedProperties = remember(matchCategories) {
                    matchCategories.flatMap { it.results.map { r -> r.property } }.distinctBy { it.id }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onViewMatchesOnMap(matchedProperties) },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandCharcoalCard, contentColor = BrandGold),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold),
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("View ${matchedProperties.size} Matches on Map", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Categorized Match Results
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            matchCategories.forEach { category ->
                item {
                    Text(
                        text = category.categoryName.uppercase(),
                        color = when {
                            category.categoryName.startsWith("Exact") -> StatusAvailable
                            category.categoryName.startsWith("Close") -> BrandGold
                            else -> TextSecondaryDark
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                items(category.results, key = { it.property.id }) { match ->
                    val isShortlisted = selectedShortlist.contains(match.property.id)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (isShortlisted) BrandGold else BrandCharcoalBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onPropertyClick(match.property) },
                        colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(BrandCharcoalCard)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(match.property.propertyCode, color = BrandGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(match.property.projectName, color = TextPrimaryDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (match.matchScorePercent >= 80) StatusAvailable.copy(alpha = 0.2f) else BrandGold.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("${match.matchScorePercent}% Match", color = if (match.matchScorePercent >= 80) StatusAvailable else BrandGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${match.property.location} • ${match.property.carpetAreaSqFt.toInt()} sq.ft • ${CalculationUtils.formatIndianCurrency(match.property.priceOrRent, match.property.tab.isRental)}",
                                color = TextSecondaryDark,
                                fontSize = 12.sp
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            match.matchingReasons.forEach { r ->
                                Text("✓ $r", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Shortlist Toggle
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            selectedShortlist = if (isShortlisted) {
                                                selectedShortlist - match.property.id
                                            } else {
                                                selectedShortlist + match.property.id
                                            }
                                        }
                                        .padding(vertical = 4.dp, horizontal = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isShortlisted) Icons.Filled.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isShortlisted) BrandGold else TextMutedDark,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isShortlisted) "Shortlisted" else "Add to Shortlist", color = TextSecondaryDark, fontSize = 11.sp)
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = { onPropertyClick(match.property) },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark),
                                        border = androidx.compose.foundation.BorderStroke(0.8.dp, BrandCharcoalBorder)
                                    ) {
                                        Text("View", fontSize = 11.sp)
                                    }

                                    Button(
                                        onClick = { onShareWhatsApp(match.property) },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Share", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // AI WHATSAPP CONTENT GENERATOR MODAL
    if (showWhatsAppGenerator) {
        val selectedProps = tenantInventory.filter { selectedShortlist.contains(it.id) }

        AlertDialog(
            onDismissRequest = { showWhatsAppGenerator = false },
            title = {
                Text("AI WhatsApp Content Generator", color = TextPrimaryDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Select Tone / Style Preset:", color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    val presets = listOf(
                        "Make a premium short WhatsApp message",
                        "Write for commercial brokers",
                        "Make a luxury residential project pitch",
                        "Create a concise investor-focused pre-leased property message"
                    )

                    presets.forEach { style ->
                        val isSel = promptStyle == style
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) BrandGold.copy(alpha = 0.2f) else BrandCharcoalCard)
                                .clickable {
                                    promptStyle = style
                                    generatedWhatsAppText = aiService.generateCustomWhatsAppPitch(
                                        properties = selectedProps,
                                        promptStyle = style,
                                        companyName = brandName,
                                        companyContact = contactNumbers
                                    )
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(style, color = if (isSel) BrandGold else TextSecondaryDark, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Generated Preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandCharcoalSurface)
                            .border(1.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(generatedWhatsAppText, color = TextPrimaryDark, fontSize = 13.sp, lineHeight = 19.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick Modifiers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        QuickToneButton("Shorter") {
                            promptStyle += " shorter"
                            generatedWhatsAppText = aiService.generateCustomWhatsAppPitch(selectedProps, promptStyle, brandName, contactNumbers)
                        }
                        QuickToneButton("More Professional") {
                            promptStyle += " more professional"
                            generatedWhatsAppText = aiService.generateCustomWhatsAppPitch(selectedProps, promptStyle, brandName, contactNumbers)
                        }
                        QuickToneButton("More Premium") {
                            promptStyle += " luxury premium"
                            generatedWhatsAppText = aiService.generateCustomWhatsAppPitch(selectedProps, promptStyle, brandName, contactNumbers)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, generatedWhatsAppText)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share WhatsApp Pitch via"))
                        showWhatsAppGenerator = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
                ) {
                    Text("Share to WhatsApp", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("WhatsApp Pitch", generatedWhatsAppText))
                        Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Copy", color = BrandGold)
                }
            },
            containerColor = BrandCharcoalSurface
        )
    }
}

@Composable
fun QuickToneButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BrandCharcoalCard)
            .border(0.8.dp, BrandCharcoalBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = TextSecondaryDark, fontSize = 10.sp)
    }
}

@Composable
fun SampleReqChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BrandCharcoalSurface)
            .border(0.8.dp, BrandCharcoalBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = BrandGold, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}


