package com.example.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.crm.ContactUtils
import com.example.crm.rememberPickContactCallback
import com.example.data.FileStorageManager
import com.example.data.SheetsRepository
import com.example.inventory.InventoryQuality
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class InlinePocType(val label: String) {
    OWNER("Property Owner"),
    BROKER("Broker"),
    DEVELOPER("Developer Sales Team")
}

@Composable
fun PocTypeChipSelector(
    selectedPocType: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Type of POC",
    testTagPrefix: String = "poc_type"
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = SecondaryText
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            POC_TYPE_OPTIONS.forEach { option ->
                val selected = normalizePocType(selectedPocType).equals(option, ignoreCase = true)
                val tagSuffix = option.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(option) },
                    label = {
                        Text(
                            text = option,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryActionOrange.copy(alpha = 0.16f),
                        selectedLabelColor = PrimaryActionOrange
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("${testTagPrefix}_$tagSuffix")
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataCompletenessInlineSheet(
    property: PropertyItem,
    initiallyPromptGoLive: Boolean = false,
    onDismiss: () -> Unit,
    onSaveProperty: (updatedProperty: PropertyItem, promoteToLive: Boolean) -> Unit,
    onUploadPhoto: (suspend (propertyId: String, fileName: String, mimeType: String, bytes: ByteArray) -> Result<String>)? = null,
    onOpenFullEdit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val fallbackRepo = remember(context) { SheetsRepository(context.applicationContext, startForegroundSync = false) }
    val effectiveUpload: suspend (String, String, String, ByteArray) -> Result<String> = remember(onUploadPhoto, fallbackRepo) {
        onUploadPhoto ?: { propId, fileName, mimeType, bytes ->
            fallbackRepo.uploadMediaToDrive(propId, fileName, mimeType, bytes)
        }
    }

    // Freeze initial missing fields so inputs remain visible while user edits them
    val initialRequiredMissing = remember(property.id) {
        InventoryQuality.minimumRequiredMissingFields(property)
    }
    val initialDetailedMissing = remember(property.id) {
        CalculationUtils.computeDetailedCompleteness(property).missingFields
    }
    val allEditableTargets = remember(property.id) {
        (initialRequiredMissing + initialDetailedMissing).distinct()
    }

    // Editable fields initialized from property
    var projectName by remember(property.id) { mutableStateOf(property.projectName) }
    var location by remember(property.id) { mutableStateOf(property.location) }
    var unitNumber by remember(property.id) { mutableStateOf(property.unitNumber) }
    var floor by remember(property.id) { mutableStateOf(property.floor) }
    var propertyType by remember(property.id) { mutableStateOf(property.propertyType) }
    var transactionType by remember(property.id) { mutableStateOf(property.transactionType) }
    var availabilityStatus by remember(property.id) { mutableStateOf(property.status) }
    var carpetAreaInput by remember(property.id) {
        mutableStateOf(if (property.carpetAreaSqFt > 0.0) property.carpetAreaSqFt.toInt().toString() else "")
    }
    val stdTxInitial = remember(property.id) { StandardTransactionType.fromString(property.transactionType, property.tab) }
    var priceInput by remember(property.id) {
        val raw = if (stdTxInitial == StandardTransactionType.LEASE) {
            property.rent?.takeIf { it > 0.0 } ?: property.priceOrRent
        } else {
            property.priceSale?.takeIf { it > 0.0 } ?: property.priceOrRent
        }
        mutableStateOf(if (raw > 0.0) raw.toLong().toString() else "")
    }
    var depositInput by remember(property.id) {
        val d = property.deposit ?: 0.0
        mutableStateOf(if (d > 0.0) d.toLong().toString() else "")
    }
    var roiInput by remember(property.id) {
        val r = property.roiPercent ?: 0.0
        mutableStateOf(if (r > 0.0) r.toString() else "")
    }
    var tenantName by remember(property.id) { mutableStateOf(property.tenantName.orEmpty()) }
    var possession by remember(property.id) { mutableStateOf(property.possession) }
    var furnishing by remember(property.id) { mutableStateOf(property.furnishing) }
    var bhk by remember(property.id) { mutableStateOf(property.bhk.orEmpty()) }

    var pocType by remember(property.id) {
        val explicit = normalizePocType(property.pocType)
        mutableStateOf(
            when {
                explicit == "Broker" -> InlinePocType.BROKER
                explicit == "Developer Sales Team" -> InlinePocType.DEVELOPER
                explicit == "Property Owner" -> InlinePocType.OWNER
                property.sourceInfo?.contains("broker", ignoreCase = true) == true ||
                    property.sourceInfo?.contains("channel partner", ignoreCase = true) == true ||
                    (InventoryQuality.isMeaningfulPhone(property.brokerContact) && !InventoryQuality.isMeaningfulPhone(property.ownerContact)) -> InlinePocType.BROKER
                property.sourceInfo?.contains("developer", ignoreCase = true) == true ||
                    property.sourceInfo?.contains("builder", ignoreCase = true) == true -> InlinePocType.DEVELOPER
                else -> InlinePocType.OWNER
            }
        )
    }
    var contactName by remember(property.id) {
        mutableStateOf(
            property.ownerName?.takeIf { InventoryQuality.isMeaningfulText(it) }
                ?: property.brokerName?.takeIf { InventoryQuality.isMeaningfulText(it) }
                ?: property.builderName?.takeIf { InventoryQuality.isMeaningfulText(it) }
                .orEmpty()
        )
    }
    var contactPhone by remember(property.id) {
        mutableStateOf(
            property.ownerContact?.takeIf { InventoryQuality.isMeaningfulPhone(it) }
                ?: property.brokerContact?.takeIf { InventoryQuality.isMeaningfulPhone(it) }
                .orEmpty()
        )
    }
    var photos by remember(property.id) { mutableStateOf(property.images) }
    var isUploadingPhoto by remember { mutableStateOf(false) }
    var completedExpanded by remember { mutableStateOf(false) }

    val pickContact = rememberPickContactCallback { pickedName, pickedPhone ->
        if (pickedName.isNotBlank()) contactName = pickedName
        if (pickedPhone.isNotBlank()) contactPhone = pickedPhone
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8)
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isUploadingPhoto = true
            val propId = property.recordId.ifBlank { property.id.ifBlank { "PROP-${System.currentTimeMillis()}" } }
            val addedUrls = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val savedLocal = runCatching {
                        FileStorageManager.savePhotoFromUri(context, uri)
                    }.getOrNull()
                    if (!savedLocal.isNullOrBlank()) {
                        val file = File(savedLocal)
                        val bytes = runCatching { file.readBytes() }.getOrNull()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val uploadRes = effectiveUpload(propId, file.name, "image/jpeg", bytes)
                            val finalUrl = uploadRes.getOrNull()?.takeIf { it.isNotBlank() } ?: savedLocal
                            addedUrls.add(finalUrl)
                        } else {
                            addedUrls.add(savedLocal)
                        }
                    }
                }
            }
            if (addedUrls.isNotEmpty()) {
                photos = (photos + addedUrls).distinct()
                val driveCount = addedUrls.count { it.startsWith("http", ignoreCase = true) }
                Toast.makeText(
                    context,
                    if (driveCount > 0) "Uploaded $driveCount photo(s) to Google Drive" else "Saved ${addedUrls.size} photo(s) offline",
                    Toast.LENGTH_SHORT
                ).show()
            }
            isUploadingPhoto = false
        }
    }

    fun buildUpdatedProperty(): PropertyItem {
        val cleanName = contactName.trim()
        val cleanPhone = ContactUtils.normalizePhoneNumber(contactPhone).ifBlank { contactPhone.trim() }
        val parsedArea = carpetAreaInput.toDoubleOrNull() ?: property.carpetAreaSqFt
        val parsedPrice = priceInput.toDoubleOrNull() ?: 0.0
        val parsedDeposit = depositInput.toDoubleOrNull()
        val parsedRoi = roiInput.toDoubleOrNull()
        val resolvedTx = transactionType.ifBlank { property.transactionType }
        val isLease = StandardTransactionType.fromString(resolvedTx, property.tab) == StandardTransactionType.LEASE

        return property.copy(
            projectName = projectName.trim(),
            location = location.trim(),
            unitNumber = unitNumber.trim(),
            floor = floor.trim(),
            propertyType = propertyType.trim().ifBlank { property.propertyType },
            transactionType = resolvedTx,
            status = availabilityStatus,
            carpetAreaSqFt = if (parsedArea > 0.0) parsedArea else property.carpetAreaSqFt,
            priceOrRent = if (parsedPrice > 0.0) parsedPrice else property.priceOrRent,
            rent = if (isLease && parsedPrice > 0.0) parsedPrice else property.rent,
            priceSale = if (!isLease && parsedPrice > 0.0) parsedPrice else property.priceSale,
            deposit = parsedDeposit ?: property.deposit,
            roiPercent = parsedRoi ?: property.roiPercent,
            tenantName = tenantName.trim().ifBlank { property.tenantName },
            possession = possession.trim(),
            furnishing = furnishing,
            bhk = bhk.trim().ifBlank { property.bhk },
            pocType = pocType.label,
            sourceInfo = property.sourceInfo?.trim()?.takeIf { src ->
                InventoryQuality.isMeaningfulText(src) &&
                    !src.equals("Owner", ignoreCase = true) &&
                    !src.equals("Property Owner", ignoreCase = true) &&
                    !src.equals("Broker", ignoreCase = true) &&
                    !src.equals("Developer Team Member", ignoreCase = true) &&
                    !src.equals("Developer Sales Team", ignoreCase = true)
            } ?: pocType.label,
            ownerName = if (pocType != InlinePocType.BROKER) cleanName.ifBlank { property.ownerName } else property.ownerName,
            ownerContact = if (pocType != InlinePocType.BROKER) cleanPhone.ifBlank { property.ownerContact } else property.ownerContact,
            brokerName = if (pocType == InlinePocType.BROKER) cleanName.ifBlank { property.brokerName } else property.brokerName,
            brokerContact = if (pocType == InlinePocType.BROKER) cleanPhone.ifBlank { property.brokerContact } else property.brokerContact,
            builderName = if (pocType == InlinePocType.DEVELOPER) cleanName.ifBlank { property.builderName } else property.builderName,
            images = photos
        )
    }

    val draftProperty = buildUpdatedProperty()
    val draftRequiredMissing = remember(draftProperty) {
        InventoryQuality.minimumRequiredMissingFields(draftProperty)
    }
    val draftCompleteness = remember(draftProperty) {
        CalculationUtils.computeDetailedCompleteness(draftProperty)
    }
    val draftColor = when {
        draftRequiredMissing.isEmpty() && draftCompleteness.percent >= 80 -> StatusSuccessGreen
        draftRequiredMissing.isEmpty() -> StatusWarningAmber
        else -> StatusDangerRed
    }

    val needContactSection = allEditableTargets.any {
        it in setOf("Source / Owner / POC", "Contact Number")
    } || !InventoryQuality.isMeaningfulText(property.pocType)
    val needPriceSection = allEditableTargets.any {
        it in setOf("Monthly Rent", "Asking Price", "Deposit", "Price / rent")
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (initiallyPromptGoLive) "Complete & Go Live" else "Complete Missing Details",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = property.projectName.ifBlank { property.propertyCode },
                        fontSize = 12.sp,
                        color = SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onOpenFullEdit != null) {
                    TextButton(onClick = {
                        onDismiss()
                        onOpenFullEdit()
                    }) {
                        Text("Full Edit ›", color = PrimaryActionOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = SecondaryText)
                }
            }

            // Live Progress Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (draftRequiredMissing.isEmpty()) "100% Required Ready" else "${draftRequiredMissing.size} required details remaining",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = draftColor
                    )
                    Text(
                        text = "${draftCompleteness.percent}% Complete",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = draftColor
                    )
                }
                LinearProgressIndicator(
                    progress = { draftCompleteness.percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = draftColor,
                    trackColor = BorderGrey
                )
            }

            HorizontalDivider(color = BorderGrey, modifier = Modifier.padding(top = 6.dp))

            // Scrollable Inline Controls
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (allEditableTargets.isEmpty()) {
                    Surface(
                        color = StatusSuccessGreen.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StatusSuccessGreen.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccessGreen)
                            Text(
                                text = "All required and applicable fields are complete!",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // 1. Project / Building
                if ("Project / Building" in allEditableTargets) {
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        label = { Text("Project / Building *") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 2. Location
                if ("Location" in allEditableTargets) {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("Location / Micro-Market *") },
                        placeholder = { Text("e.g. Baner, Koregaon Park, Viman Nagar") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 3. Unit / Floor
                if ("Unit / Floor" in allEditableTargets || "Unit Number" in allEditableTargets) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = unitNumber,
                            onValueChange = { unitNumber = it },
                            label = { Text("Unit / Office No *") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier.weight(1.3f)
                        )
                        OutlinedTextField(
                            value = floor,
                            onValueChange = { floor = it },
                            label = { Text("Floor") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 4. Availability Status Pill Chips
                if ("Availability Status" in allEditableTargets) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Availability Status *", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        val availChoices = listOf(
                            AvailabilityStatus.AVAILABLE to "Available",
                            AvailabilityStatus.UNDER_NEGOTIATION to "Under Negotiation",
                            AvailabilityStatus.LEASED to "Leased Out",
                            AvailabilityStatus.SOLD to "Sold Out",
                            AvailabilityStatus.ON_HOLD to "On Hold"
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(availChoices) { (statusEnum, chipLabel) ->
                                val selected = availabilityStatus == statusEnum
                                FilterChip(
                                    selected = selected,
                                    onClick = { availabilityStatus = statusEnum },
                                    label = { Text(chipLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = PrimaryActionOrange.copy(alpha = 0.16f),
                                        selectedLabelColor = PrimaryActionOrange
                                    )
                                )
                            }
                        }
                    }
                }

                // 5. Property Type
                if ("Property Type" in allEditableTargets) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Property Type *", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        val typeOptions = listOf("Office", "Shop", "Showroom", "Warehouse", "Apartment", "Villa", "Commercial Land", "Residential Plot")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(typeOptions) { t ->
                                val selected = propertyType.equals(t, ignoreCase = true)
                                FilterChip(
                                    selected = selected,
                                    onClick = { propertyType = t },
                                    label = { Text(t, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }

                // 6. Transaction Type
                if ("Transaction Type" in allEditableTargets) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Transaction Type *", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        val txOptions = listOf("Lease", "Sale", "Pre-Leased Sale")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            txOptions.forEach { tx ->
                                val selected = transactionType.equals(tx, ignoreCase = true)
                                FilterChip(
                                    selected = selected,
                                    onClick = { transactionType = tx },
                                    label = { Text(tx, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }

                // 7. Carpet Area
                if ("Carpet Area" in allEditableTargets) {
                    OutlinedTextField(
                        value = carpetAreaInput,
                        onValueChange = { ch -> carpetAreaInput = ch.filter { it.isDigit() || it == '.' } },
                        label = { Text("Carpet Area (Sq.Ft) *") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 8. Asking Price / Rent & Deposit
                if (needPriceSection) {
                    val isLeaseTx = StandardTransactionType.fromString(transactionType, property.tab) == StandardTransactionType.LEASE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val pLabel = if (isLeaseTx) "Monthly Rent (₹) *" else "Asking Price (₹) *"
                        OutlinedTextField(
                            value = priceInput,
                            onValueChange = { ch -> priceInput = ch.filter { it.isDigit() } },
                            label = { Text(pLabel) },
                            supportingText = {
                                val parsed = priceInput.toDoubleOrNull() ?: 0.0
                                if (parsed > 0.0) {
                                    Text(
                                        CalculationUtils.formatIndianCurrencyCrm(parsed, isMonthly = isLeaseTx),
                                        color = PrimaryActionOrange,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier.weight(1f)
                        )

                        if ("Deposit" in allEditableTargets || isLeaseTx) {
                            OutlinedTextField(
                                value = depositInput,
                                onValueChange = { ch -> depositInput = ch.filter { it.isDigit() } },
                                label = { Text("Deposit (₹)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 9. Pre-Leased ROI % & Tenant Name
                if ("ROI %" in allEditableTargets) {
                    OutlinedTextField(
                        value = roiInput,
                        onValueChange = { ch -> roiInput = ch.filter { it.isDigit() || it == '.' } },
                        label = { Text("ROI %") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if ("Tenant Name" in allEditableTargets) {
                    OutlinedTextField(
                        value = tenantName,
                        onValueChange = { tenantName = it },
                        label = { Text("Tenant Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 10. Possession
                if ("Possession" in allEditableTargets) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Possession *", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        val posPresets = listOf("Immediate", "1 Month", "3 Months", "Under Construction")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(posPresets) { preset ->
                                FilterChip(
                                    selected = possession.equals(preset, ignoreCase = true),
                                    onClick = { possession = preset },
                                    label = { Text(preset, fontSize = 12.sp) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = possession,
                            onValueChange = { possession = it },
                            label = { Text("Possession timeline") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 11. Furnishing
                if ("Furnishing" in allEditableTargets) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Furnishing", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        val furnOptions = listOf(
                            FurnishingType.BARE_SHELL,
                            FurnishingType.WARM_SHELL,
                            FurnishingType.SEMI_FURNISHED,
                            FurnishingType.FULLY_FURNISHED
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(furnOptions) { f ->
                                FilterChip(
                                    selected = furnishing == f,
                                    onClick = { furnishing = f },
                                    label = { Text(f.label, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }

                // 12. BHK Configuration
                if ("BHK Configuration" in allEditableTargets) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("BHK Configuration", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        val bhkOptions = listOf("1 BHK", "2 BHK", "3 BHK", "4 BHK", "4+ BHK / Villa")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(bhkOptions) { opt ->
                                FilterChip(
                                    selected = bhk.equals(opt, ignoreCase = true),
                                    onClick = { bhk = opt },
                                    label = { Text(opt, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }

                // 13. Contact Number + Contact Name + Type of POC + Pick from Contacts
                if (needContactSection) {
                    Surface(
                        color = SurfaceMuted,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Source & Contact Details *", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                TextButton(
                                    onClick = pickContact,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Contacts, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pick from Contacts", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                                }
                            }

                            PocTypeChipSelector(
                                selectedPocType = pocType.label,
                                onSelect = { chosen ->
                                    pocType = InlinePocType.entries.firstOrNull { it.label == chosen } ?: InlinePocType.OWNER
                                },
                                title = "Type of POC",
                                testTagPrefix = "inline_poc_chip"
                            )

                            OutlinedTextField(
                                value = contactName,
                                onValueChange = { contactName = it },
                                label = { Text("${pocType.label} Name *") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = contactPhone,
                                onValueChange = { ch -> contactPhone = ch.filter { it.isDigit() || it == '+' || it == ' ' }.take(15) },
                                label = { Text("Contact Mobile Number *") },
                                placeholder = { Text("10-digit mobile number") },
                                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // 14. Photos Inline Adder
                if ("Photos" in allEditableTargets) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Property Photos (${photos.size})", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            OutlinedButton(
                                enabled = !isUploadingPhoto,
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                border = BorderStroke(1.dp, PrimaryActionOrange),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.heightIn(min = 40.dp)
                            ) {
                                if (isUploadingPhoto) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PrimaryActionOrange)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Uploading...", fontSize = 12.sp, color = PrimaryActionOrange)
                                } else {
                                    Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("+ Add Photo", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange)
                                }
                            }
                        }
                        if (photos.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(photos) { url ->
                                    AsyncImage(
                                        model = url,
                                        contentDescription = "Property photo preview",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, BorderGrey, RoundedCornerShape(8.dp))
                                    )
                                }
                            }
                        }
                    }
                }

                // Collapsed Completed Fields Accordion
                Surface(
                    onClick = { completedExpanded = !completedExpanded },
                    color = SurfaceMuted,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Completed Fields (${draftCompleteness.completedFields.size})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                            Icon(
                                imageVector = if (completedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (completedExpanded) "Collapse completed fields" else "Expand completed fields",
                                tint = SecondaryText
                            )
                        }
                        AnimatedVisibility(visible = completedExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (draftCompleteness.completedFields.isEmpty()) {
                                    Text("None recorded yet", fontSize = 12.sp, color = SecondaryText)
                                } else {
                                    draftCompleteness.completedFields.forEach { field ->
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccessGreen, modifier = Modifier.size(15.dp))
                                            Text(field, fontSize = 12.5.sp, color = TextPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sticky Bottom Bar
            HorizontalDivider(color = BorderGrey)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceWhite)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(0.9f)
                        .heightIn(min = 48.dp)
                ) {
                    Text("Close", color = TextPrimary)
                }

                val canPromoteNow = initiallyPromptGoLive && draftRequiredMissing.isEmpty()
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val updated = buildUpdatedProperty()
                        onSaveProperty(updated, canPromoteNow)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.6f)
                        .heightIn(min = 48.dp)
                        .testTag("inline_completeness_save_btn_${property.id}")
                ) {
                    Icon(
                        imageVector = if (canPromoteNow) Icons.Default.RocketLaunch else Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (canPromoteNow) "Save & Go Live" else "Save & Update",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
