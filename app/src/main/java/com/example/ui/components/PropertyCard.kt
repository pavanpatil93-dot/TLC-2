package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.inventory.InventoryQuality
import com.example.model.*
import com.example.ui.theme.*

val LocalPropertyMapHandler = compositionLocalOf<((PropertyItem) -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertyCard(
    property: PropertyItem,
    isInternalMode: Boolean,
    isFavorite: Boolean,
    isInComparison: Boolean,
    onCardClick: () -> Unit,
    onEditClick: (() -> Unit)? = null,
    onToggleFavorite: () -> Unit,
    onToggleCompare: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onStatusChange: (AvailabilityStatus) -> Unit = {},
    onViewMapClick: (() -> Unit)? = null,
    compact: Boolean = false,
    isInShortlist: Boolean = false,
    onToggleShortlist: (() -> Unit)? = null,
    matchBadgeLabel: String? = null,
    isSelectMode: Boolean = false,
    isSelectedForShare: Boolean = false,
    onToggleSelectForShare: (() -> Unit)? = null,
    onSaveVerification: ((AvailabilityStatus, String, String) -> Unit)? = null,
    onVerificationSwitchChange: ((Boolean) -> Unit)? = null,
    linkedUnitCount: Int = 1
) {
    val context = LocalContext.current
    val mapHandler = LocalPropertyMapHandler.current
    var moreMenuExpanded by remember(property.id) { mutableStateOf(false) }
    var showCompletenessSheet by remember(property.id) { mutableStateOf(false) }
    var showVerifySheet by remember(property.id) { mutableStateOf(false) }
    var showMissingRequiredSheet by remember(property.id) { mutableStateOf(false) }
    val selectedRateBasis = AreaRateBasis.CARPET

    val stdTx = remember(property.transactionType, property.tab) {
        StandardTransactionType.fromString(property.transactionType, property.tab)
    }

    val primaryAmount = remember(property) { CalculationUtils.primaryAmountForProperty(property) }
    val priceDisplay = remember(primaryAmount, stdTx) {
        if (primaryAmount > 0.0) {
            CalculationUtils.formatIndianCurrency(
                amount = primaryAmount,
                isMonthly = stdTx == StandardTransactionType.LEASE
            )
        } else {
            "Price Pending"
        }
    }

    val rateDisplay = remember(property, selectedRateBasis) {
        CalculationUtils.formatRateForBasis(property, selectedRateBasis, includeBasisSuffix = true)
    }

    val isVerifiedLive = property.verificationStatus == VerificationStatus.VERIFIED &&
        property.archiveStatus != ArchiveStatus.ARCHIVED
    val requiredMissingFields = remember(property) {
        InventoryQuality.minimumRequiredMissingFields(property)
    }

    val verificationAgeState = remember(property) {
        getVerificationAgeState(property, validityDays = 60)
    }

    val verificationBadgeLabel = when {
        property.archiveStatus == ArchiveStatus.ARCHIVED -> "Archived"
        verificationAgeState == VerificationAgeState.CURRENT -> "Live · Verified"
        verificationAgeState == VerificationAgeState.DUE -> "Recheck 60d+"
        else -> "Pending"
    }

    val verificationBadgeColor = when {
        property.archiveStatus == ArchiveStatus.ARCHIVED -> SecondaryText
        verificationAgeState == VerificationAgeState.CURRENT -> StatusSuccessGreen
        verificationAgeState == VerificationAgeState.DUE -> StatusWarningAmber
        else -> StatusDangerRed
    }

    val detailedCompleteness = remember(property) {
        CalculationUtils.computeDetailedCompleteness(property)
    }

    val completionColor = when {
        requiredMissingFields.isEmpty() && detailedCompleteness.percent >= 80 -> StatusSuccessGreen
        requiredMissingFields.isEmpty() -> StatusWarningAmber
        else -> StatusDangerRed
    }

    val callPropertyContact: () -> Unit = {
        val number = property.ownerContact?.takeIf { InventoryQuality.isMeaningfulPhone(it) }
            ?: property.brokerContact?.takeIf { InventoryQuality.isMeaningfulPhone(it) }
            .orEmpty()
        if (number.isNotBlank()) {
            runCatching {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                context.startActivity(intent)
            }.onFailure {
                Toast.makeText(context, "Unable to launch dialer", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "No valid contact number recorded", Toast.LENGTH_SHORT).show()
        }
    }

    val openPropertyInMaps: () -> Unit = {
        when {
            onViewMapClick != null -> onViewMapClick()
            mapHandler != null -> mapHandler(property)
            else -> {
                val rawUrl = property.googleMapsUrl?.takeIf { it.startsWith("http") }
                val hasCoords = property.lat != null && property.lng != null &&
                    !(property.lat == 0.0 && property.lng == 0.0)
                val targetUri = when {
                    rawUrl != null -> Uri.parse(rawUrl)
                    hasCoords -> Uri.parse("geo:${property.lat},${property.lng}?q=${property.lat},${property.lng}(${Uri.encode(property.projectName.ifBlank { property.location })})")
                    else -> {
                        val query = listOf(property.projectName, property.location, "Pune")
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                        Uri.parse("geo:0,0?q=${Uri.encode(query)}")
                    }
                }
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, targetUri))
                }.onFailure {
                    Toast.makeText(context, "No map application installed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelectedForShare) 1.5.dp else 1.dp,
            color = if (isSelectedForShare) PrimaryActionOrange else BorderGrey
        ),
        elevation = CardDefaults.cardElevation(1.5.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isSelectMode && onToggleSelectForShare != null) {
                    onToggleSelectForShare()
                } else {
                    onCardClick()
                }
            }
            .testTag("property_card_${property.id}")
    ) {
        val availabilityColor = when (property.status) {
            AvailabilityStatus.AVAILABLE -> StatusSuccessGreen
            AvailabilityStatus.SOLD, AvailabilityStatus.LEASED, AvailabilityStatus.NOT_AVAILABLE, AvailabilityStatus.WITHDRAWN -> StatusDangerRed
            else -> StatusWarningAmber
        }

        val moreMenuControl: @Composable () -> Unit = {
            Box {
                IconButton(
                    onClick = { moreMenuExpanded = true },
                    modifier = Modifier
                        .size(width = if (compact) 38.dp else 42.dp, height = 48.dp)
                        .background(SurfaceMuted, RoundedCornerShape(10.dp))
                        .border(1.dp, BorderGrey, RoundedCornerShape(10.dp))
                        .testTag("property_card_more_${property.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Actions",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = moreMenuExpanded,
                    onDismissRequest = { moreMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("View Details") },
                        onClick = {
                            moreMenuExpanded = false
                            onCardClick()
                        },
                        leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null, tint = PrimaryActionOrange) }
                    )
                    if (onEditClick != null) {
                        DropdownMenuItem(
                            text = { Text("Edit Property") },
                            onClick = {
                                moreMenuExpanded = false
                                onEditClick()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = PrimaryActionOrange) }
                        )
                    }
                    if (onSaveVerification != null) {
                        DropdownMenuItem(
                            text = { Text("Detailed Verification Log") },
                            onClick = {
                                moreMenuExpanded = false
                                showVerifySheet = true
                            },
                            leadingIcon = { Icon(Icons.Default.Verified, contentDescription = null, tint = PrimaryActionOrange) }
                        )
                    }
                    if (onToggleShortlist != null) {
                        DropdownMenuItem(
                            text = { Text(if (isInShortlist) "Remove from Shortlist" else "Add to Shortlist") },
                            onClick = {
                                moreMenuExpanded = false
                                onToggleShortlist()
                            },
                            leadingIcon = { Icon(Icons.Default.PlaylistAddCheck, contentDescription = null, tint = PrimaryActionOrange) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (isSelectedForShare) "Deselect for Multi-Share" else "Select for Multi-Share") },
                        onClick = {
                            moreMenuExpanded = false
                            onToggleSelectForShare?.invoke()
                        },
                        leadingIcon = { Icon(Icons.Default.Checklist, contentDescription = null, tint = PrimaryActionOrange) }
                    )
                }
            }
        }

        if (compact) {
            // ── 2-COLUMN GRID CARD REDESIGN ──
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Row 1: Compact ● Available dot on left + PropertyCode pill on right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(availabilityColor)
                        )
                        Text(
                            text = property.status.label,
                            color = availabilityColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val codeText = property.propertyCode.ifBlank { property.id.take(8) }
                        if (codeText.isNotBlank()) {
                            Surface(
                                color = SelectedSurfaceContainer,
                                shape = RoundedCornerShape(5.dp)
                            ) {
                                Text(
                                    text = codeText,
                                    color = FocusOutlineColor,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (isSelectMode && onToggleSelectForShare != null) {
                            Checkbox(
                                checked = isSelectedForShare,
                                onCheckedChange = { onToggleSelectForShare() },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Row 2: Full-width bold Project Name (maxLines = 2, no status badges on this line)
                Text(
                    text = property.projectName.ifBlank { "Project Not Mentioned" },
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                // Row 3: Compact 1-line subtitle (e.g., "Office • Lease • Kharadi")
                val shortType = StandardPropertyType.fromString(property.propertyType)
                    .label
                    .ifBlank { property.propertyType.ifBlank { "Property" } }
                val shortTx = when (stdTx) {
                    StandardTransactionType.LEASE -> "Lease"
                    StandardTransactionType.PRE_LEASED_SALE -> "Pre-Leased"
                    StandardTransactionType.SALE -> "Sale"
                }
                val shortLocation = property.location.substringBefore(",").trim()
                val compactSubtitle = listOf(shortType, shortTx, shortLocation)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")

                Text(
                    text = compactSubtitle,
                    color = SecondaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Row 4: Short-form INR Price + Carpet Area
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("card_price_block"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = priceDisplay,
                        color = PrimaryActionOrange,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("card_price")
                    )
                    if (property.carpetAreaSqFt > 0.0) {
                        Text(
                            text = "${property.carpetAreaSqFt.toInt()} sq.ft",
                            color = SecondaryText,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Row 5: Thin 4.dp Completeness Progress Bar with compact percentage (omit "Need X details")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showCompletenessSheet = true }
                        .padding(vertical = 2.dp)
                        .testTag("property_completeness_bar_${property.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { detailedCompleteness.percent / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = completionColor,
                        trackColor = BorderGrey
                    )
                    Text(
                        text = "${detailedCompleteness.percent}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = completionColor,
                        maxLines = 1
                    )
                }

                HorizontalDivider(color = BorderSubtle)

                // Row 6: Single clean icon row [ 📞 ] [ 💬 ] [ ⋮ ] (no Edit, no Switch)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = callPropertyContact,
                        modifier = Modifier
                            .size(width = 40.dp, height = 48.dp)
                            .background(SurfaceMuted, RoundedCornerShape(10.dp))
                            .border(1.dp, BorderGrey, RoundedCornerShape(10.dp))
                            .testTag("property_card_call_${property.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call Property Contact",
                            tint = StatusSuccessGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onShareWhatsApp,
                        modifier = Modifier
                            .size(width = 40.dp, height = 48.dp)
                            .background(SurfaceMuted, RoundedCornerShape(10.dp))
                            .border(1.dp, BorderGrey, RoundedCornerShape(10.dp))
                            .testTag("property_card_whatsapp_${property.id}")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                            contentDescription = "Share via WhatsApp",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    moreMenuControl()
                }
            }
        } else {
            // ── FULL LIST CARD LAYOUT ──
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Building/Project + Availability & Verification Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = property.projectName.ifBlank { "Project Not Mentioned" },
                            color = TextPrimary,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val txLabel = when (stdTx) {
                            StandardTransactionType.LEASE -> "Lease"
                            StandardTransactionType.PRE_LEASED_SALE -> "Pre-Leased"
                            StandardTransactionType.SALE -> "Sale"
                        }
                        val propType = property.propertyType.ifBlank { "Property" }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "$propType · $txLabel",
                                color = PrimaryActionOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (linkedUnitCount > 1) {
                                Surface(
                                    color = SelectedSurfaceContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "$linkedUnitCount units",
                                        color = FocusOutlineColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = availabilityColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = property.status.label,
                                color = availabilityColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            color = verificationBadgeColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = verificationBadgeLabel,
                                color = verificationBadgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        if (isSelectMode && onToggleSelectForShare != null) {
                            Checkbox(
                                checked = isSelectedForShare,
                                onCheckedChange = { onToggleSelectForShare() }
                            )
                        }
                    }
                }

                // Location & Unit/Floor row
                val locationSub = buildList {
                    if (property.propertyCode.isNotBlank()) add(property.propertyCode)
                    if (property.location.isNotBlank()) add(property.location)
                    if (property.unitNumber.isNotBlank()) add("Unit ${property.unitNumber}")
                    else if (property.floor.isNotBlank()) add("Flr ${property.floor}")
                }.joinToString(" · ")

                if (locationSub.isNotBlank()) {
                    Text(
                        text = locationSub,
                        color = SecondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Full-width Price & Area Block (ensures 1-line price without cramped wrapping)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("card_price_block"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = priceDisplay,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("card_price")
                        )
                        if (primaryAmount > 0.0 && property.carpetAreaSqFt > 0.0) {
                            Text(
                                text = rateDisplay,
                                fontSize = 11.sp,
                                color = PrimaryActionOrange,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    val areaStr = buildList {
                        if (property.carpetAreaSqFt > 0.0) add("${property.carpetAreaSqFt.toInt()} Carpet")
                        if (property.builtUpAreaSqFt != null && property.builtUpAreaSqFt > 0.0) {
                            add("${property.builtUpAreaSqFt.toInt()} BUA")
                        }
                        if (property.possession.isNotBlank()) add(property.possession)
                    }.joinToString(" · ")

                    if (areaStr.isNotBlank()) {
                        Text(
                            text = areaStr,
                            fontSize = 11.5.sp,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Compact Completeness / Missing Required Details strip
                Surface(
                    color = SurfaceMuted,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCompletenessSheet = true }
                        .testTag("property_completeness_bar_${property.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            LinearProgressIndicator(
                                progress = { detailedCompleteness.percent / 100f },
                                modifier = Modifier
                                    .width(58.dp)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = completionColor,
                                trackColor = BorderGrey
                            )
                            Text(
                                text = if (requiredMissingFields.isEmpty()) {
                                    "100% Ready (${detailedCompleteness.percent}%)"
                                } else {
                                    "Need ${requiredMissingFields.size} details"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = completionColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Details ›",
                            fontSize = 10.5.sp,
                            color = PrimaryActionOrange,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                HorizontalDivider(color = BorderSubtle)

                // Clean List Card Bottom Row: [ 📞 ] [ 💬 ] [ 📍 ] [ 🟢 Availability Toggle ] [ ⋮ ]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Call Icon Button
                    IconButton(
                        onClick = callPropertyContact,
                        modifier = Modifier
                            .size(width = 44.dp, height = 48.dp)
                            .background(SurfaceMuted, RoundedCornerShape(10.dp))
                            .border(1.dp, BorderGrey, RoundedCornerShape(10.dp))
                            .testTag("property_card_call_${property.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call Property Contact",
                            tint = StatusSuccessGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 2. WhatsApp Logo Button
                    IconButton(
                        onClick = onShareWhatsApp,
                        modifier = Modifier
                            .size(width = 44.dp, height = 48.dp)
                            .background(SurfaceMuted, RoundedCornerShape(10.dp))
                            .border(1.dp, BorderGrey, RoundedCornerShape(10.dp))
                            .testTag("property_card_whatsapp_${property.id}")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                            contentDescription = "Share via WhatsApp",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 3. Google Map Logo Button
                    IconButton(
                        onClick = openPropertyInMaps,
                        modifier = Modifier
                            .size(width = 44.dp, height = 48.dp)
                            .background(SurfaceMuted, RoundedCornerShape(10.dp))
                            .border(1.dp, BorderGrey, RoundedCornerShape(10.dp))
                            .testTag("property_card_map_${property.id}")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google_maps_logo),
                            contentDescription = "Open Location in Maps",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 4. Live / Verification Toggle
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 2.dp)
                    ) {
                        Switch(
                            checked = isVerifiedLive,
                            onCheckedChange = { targetLive ->
                                if (targetLive) {
                                    if (requiredMissingFields.isNotEmpty()) {
                                        showMissingRequiredSheet = true
                                    } else if (onVerificationSwitchChange != null) {
                                        onVerificationSwitchChange(true)
                                    } else if (onSaveVerification != null) {
                                        showVerifySheet = true
                                    }
                                } else {
                                    onVerificationSwitchChange?.invoke(false)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnPrimaryActionColor,
                                checkedTrackColor = StatusSuccessGreen,
                                uncheckedThumbColor = TextPrimary,
                                uncheckedTrackColor = SurfaceMuted,
                                uncheckedBorderColor = BorderGrey
                            ),
                            modifier = Modifier
                                .semantics {
                                    contentDescription = if (isVerifiedLive) "Verified Live" else "Verify and Publish Live"
                                }
                                .testTag("property_card_verify_switch_${property.id}")
                        )
                    }

                    // 5. More Actions Menu
                    moreMenuControl()
                }
            }
        }
    }

    // ── MISSING 100% REQUIRED DETAILS DIALOG/SHEET ──
    if (showMissingRequiredSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMissingRequiredSheet = false },
            containerColor = SurfaceWhite
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Complete 100% Required Details to Go Live",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary
                )
                Text(
                    text = "Fill these mandatory fields before verifying and publishing to Live Sheet:",
                    fontSize = 12.5.sp,
                    color = SecondaryText
                )
                HorizontalDivider(color = BorderGrey)
                requiredMissingFields.forEach { field ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusDangerRed, modifier = Modifier.size(18.dp))
                        Text(field, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { showMissingRequiredSheet = false },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Close", color = TextPrimary)
                    }
                    if (onEditClick != null) {
                        Button(
                            onClick = {
                                showMissingRequiredSheet = false
                                onEditClick()
                            },
                            modifier = Modifier
                                .weight(1.5f)
                                .heightIn(min = 48.dp)
                                .testTag("property_complete_missing_btn_${property.id}"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryActionOrange,
                                contentColor = OnPrimaryActionColor
                            )
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Complete Details", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ── TAPPABLE COMPLETENESS BREAKDOWN SHEET ──
    if (showCompletenessSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCompletenessSheet = false },
            containerColor = SurfaceWhite
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Data Completeness", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                    Text("${detailedCompleteness.percent}%", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = completionColor)
                }

                if (requiredMissingFields.isNotEmpty()) {
                    Text("Required to Go Live (${requiredMissingFields.size})", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = StatusDangerRed)
                    requiredMissingFields.forEach { field ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusDangerRed, modifier = Modifier.size(16.dp))
                            Text(field, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                    }
                    HorizontalDivider(color = BorderGrey)
                }

                Text("Completed Fields (${detailedCompleteness.completedFields.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                if (detailedCompleteness.completedFields.isEmpty()) {
                    Text("None recorded", fontSize = 12.sp, color = SecondaryText)
                } else {
                    detailedCompleteness.completedFields.forEach { field ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccessGreen, modifier = Modifier.size(16.dp))
                            Text(field, fontSize = 13.sp, color = TextPrimary)
                        }
                    }
                }

                Text("Additional Applicable Fields (${detailedCompleteness.missingFields.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                if (detailedCompleteness.missingFields.isEmpty()) {
                    Text("All applicable fields completed.", fontSize = 12.sp, color = StatusSuccessGreen, fontWeight = FontWeight.Bold)
                } else {
                    detailedCompleteness.missingFields.forEach { field ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = StatusWarningAmber, modifier = Modifier.size(16.dp))
                            Text(field, fontSize = 13.sp, color = TextPrimary)
                        }
                    }
                }

                if (onEditClick != null) {
                    Button(
                        onClick = {
                            showCompletenessSheet = false
                            onEditClick()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        )
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Edit Property to Complete", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ── VERIFY PROPERTY BOTTOM SHEET ──
    if (showVerifySheet) {
        VerifyPropertyBottomSheet(
            property = property,
            onDismiss = { showVerifySheet = false },
            onSave = { updatedStatus, method, notes ->
                showVerifySheet = false
                onSaveVerification?.invoke(updatedStatus, method, notes)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyPropertyBottomSheet(
    property: PropertyItem,
    onDismiss: () -> Unit,
    onSave: (AvailabilityStatus, String, String) -> Unit
) {
    val context = LocalContext.current
    var selectedStatus by remember { mutableStateOf(property.status) }
    var verificationMethod by remember { mutableStateOf("") } // Starts unselected ("Select Verification Method *")
    var verificationNotes by remember { mutableStateOf("") }
    var expandedMethodDropdown by remember { mutableStateOf(false) }
    var expandedStatusDropdown by remember { mutableStateOf(false) }

    val methods = listOf("Owner Call", "Site Visit", "Broker Confirmation", "Document Check")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Verify Property Availability", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                }
            }

            Text(
                "Current Status: ${property.status.label} · Last Verified: ${property.lastVerifiedDateClean.ifBlank { "Recent" }}",
                fontSize = 13.sp,
                color = SecondaryText
            )

            // Status Dropdown
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Updated Availability Status", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                ExposedDropdownMenuBox(
                    expanded = expandedStatusDropdown,
                    onExpandedChange = { expandedStatusDropdown = !expandedStatusDropdown }
                ) {
                    OutlinedTextField(
                        value = selectedStatus.label,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedStatusDropdown) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedStatusDropdown,
                        onDismissRequest = { expandedStatusDropdown = false }
                    ) {
                        AvailabilityStatus.entries.forEach { status ->
                            DropdownMenuItem(
                                text = { Text(status.label) },
                                onClick = {
                                    selectedStatus = status
                                    expandedStatusDropdown = false
                                }
                            )
                        }
                    }
                }
            }

            // Verification Method Dropdown (Unselected by default)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Verification Method *", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                ExposedDropdownMenuBox(
                    expanded = expandedMethodDropdown,
                    onExpandedChange = { expandedMethodDropdown = !expandedMethodDropdown }
                ) {
                    OutlinedTextField(
                        value = verificationMethod.ifBlank { "Select Verification Method *" },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedMethodDropdown) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedMethodDropdown,
                        onDismissRequest = { expandedMethodDropdown = false }
                    ) {
                        methods.forEach { method ->
                            DropdownMenuItem(
                                text = { Text(method) },
                                onClick = {
                                    verificationMethod = method
                                    expandedMethodDropdown = false
                                }
                            )
                        }
                    }
                }
            }

            // Notes field
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Verification Notes / Remarks", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                OutlinedTextField(
                    value = verificationNotes,
                    onValueChange = { verificationNotes = it },
                    placeholder = { Text("Enter verification details...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    maxLines = 3
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Cancel", color = TextPrimary)
                }

                Button(
                    onClick = {
                        if (verificationMethod.isBlank()) {
                            Toast.makeText(context, "Please select a verification method", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        onSave(selectedStatus, verificationMethod, verificationNotes)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Save Verification", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
