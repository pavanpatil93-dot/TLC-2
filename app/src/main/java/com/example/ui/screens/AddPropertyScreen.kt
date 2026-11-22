package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.ExtractedPropertyDraft
import com.example.ai.GeminiProductivityEngine
import com.example.ai.GeminiRealEstateService
import com.example.ai.WhatsAppExtractionBatchResult
import com.example.data.SheetsRepository
import com.example.data.projects.DirectoryProject
import com.example.data.projects.ProjectDirectoryStore
import com.example.data.pune.CommercialProjectEntity
import com.example.data.pune.LocationEntity
import com.example.data.pune.PuneIntelligenceRepository
import com.example.inventory.CompletenessColorTier
import com.example.inventory.InventoryQuality
import com.example.model.*
import com.example.ui.components.CompactDropdownField
import com.example.ui.components.PocTypeChipSelector
import com.example.ui.components.PropertyMediaUploadSection
import com.example.ui.components.SafeWhatsAppReviewPanel
import com.example.ui.components.SearchableDropdownField
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private fun sanitizeDecimalInput(raw: String): String {
    val sb = StringBuilder()
    var dotSeen = false
    for (ch in raw) {
        if (ch.isDigit()) {
            sb.append(ch)
        } else if (ch == '.' && !dotSeen) {
            dotSeen = true
            sb.append(ch)
        }
    }
    return sb.toString()
}

private fun sanitizeIntegerInput(raw: String): String = raw.filter { it.isDigit() }

internal fun formatIndianShortPreview(amount: Double): String {
    if (amount <= 0.0) return ""
    return when {
        amount >= 10_000_000.0 -> String.format(Locale.US, "₹ %.2f Cr", amount / 10_000_000.0)
        amount >= 100_000.0 -> String.format(Locale.US, "₹ %.2f Lac", amount / 100_000.0)
        else -> CalculationUtils.formatIndianCurrencyFull(amount)
    }
}

@Composable
fun AddPropertyScreen(
    onSaveProperty: (PropertyItem) -> Boolean,
    onCheckDuplicates: (String, String, String) -> List<PropertyItem>,
    onGenerateCode: (InventoryTab) -> String,
    onCancel: () -> Unit,
    onUploadMediaToDrive: (suspend (propertyId: String, fileName: String, mimeType: String, bytes: ByteArray) -> Result<String>)? = null,
    onPushPropertyToSheet: (suspend (PropertyItem) -> Result<Unit>)? = null,
    initialOwnerName: String = "",
    initialOwnerPhone: String = "",
    initialRemarks: String = "",
    initialPocType: String = "Property Owner",
    fromCallIntelligence: Boolean = false,
    onRouteClientRequirementToCrm: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val aiService = remember { GeminiRealEstateService() }
    val fallbackRepository = remember(context) { SheetsRepository(context.applicationContext) }

    val effectiveUploadMediaToDrive: suspend (String, String, String, ByteArray) -> Result<String> =
        remember(onUploadMediaToDrive, fallbackRepository) {
            onUploadMediaToDrive ?: { propId, fileName, mimeType, bytes ->
                fallbackRepository.uploadMediaToDrive(propId, fileName, mimeType, bytes)
            }
        }
    val effectivePushPropertyToSheet: suspend (PropertyItem) -> Result<Unit> =
        remember(onPushPropertyToSheet, fallbackRepository) {
            onPushPropertyToSheet ?: { prop ->
                fallbackRepository.pushPropertyToMasterSheet(prop)
            }
        }

    // 0 = Unified 1-Page Compact Form, 1 = AI Smart Paste
    var selectedMode by remember { mutableStateOf(0) }
    var isSavingAndPushing by remember { mutableStateOf(false) }

    val puneRepo = remember { PuneIntelligenceRepository.getInstance(context) }
    var masterLocations by remember { mutableStateOf<List<LocationEntity>>(emptyList()) }
    var masterCommercialProjects by remember { mutableStateOf<List<CommercialProjectEntity>>(emptyList()) }
    var directoryProjects by remember { mutableStateOf<List<DirectoryProject>>(emptyList()) }
    var showQuickAddProjectDialog by remember { mutableStateOf(false) }
    var pendingNewProjectName by remember { mutableStateOf("") }
    var showUnsavedExitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        masterLocations = puneRepo.getAllLocations()
        masterCommercialProjects = puneRepo.getAllCommercialProjects()
        runCatching {
            withContext(Dispatchers.IO) { ProjectDirectoryStore.read(context) }
        }.onSuccess {
            directoryProjects = it
        }
    }

    // --- Core Form State ---
    val draftId = remember { "prop_${System.currentTimeMillis()}" }
    var selectedTab by remember { mutableStateOf(InventoryTab.OFF_LEASE) }
    var formPropertyType by remember { mutableStateOf("Commercial Office") }
    var formPropertyCode by remember { mutableStateOf(onGenerateCode(InventoryTab.OFF_LEASE)) }
    LaunchedEffect(selectedTab) {
        formPropertyCode = onGenerateCode(selectedTab)
    }

    val formCategory = if (selectedTab.isCommercial) PropertyCategory.COMMERCIAL else PropertyCategory.RESIDENTIAL
    val isPreLeasedTab = selectedTab == InventoryTab.PRE_LEASED
    val isLeaseTx = selectedTab.isRental && !isPreLeasedTab
    val isSaleTx = !selectedTab.isRental && !isPreLeasedTab

    val formTransactionType = when {
        isPreLeasedTab -> "Pre-Leased Sale"
        isLeaseTx -> "Lease"
        else -> "Sale"
    }

    var formDate by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var formProjectId by remember { mutableStateOf("") }
    var formProject by remember { mutableStateOf("") }
    var formLocation by remember { mutableStateOf("") }
    var formDeveloper by remember { mutableStateOf("") }
    var formUnitNo by remember { mutableStateOf("") }
    var formFloor by remember { mutableStateOf("Ground Floor (GF)") }
    var formFurnishing by remember { mutableStateOf(FurnishingType.WARM_SHELL) }
    var formStatus by remember { mutableStateOf(AvailabilityStatus.AVAILABLE) }
    var formPossession by remember { mutableStateOf("Immediate") }
    var formParking by remember { mutableStateOf("1 Covered Car Park") }

    // Area & Financials
    var formCarpetArea by remember { mutableStateOf("") }
    var formBuiltUpArea by remember { mutableStateOf("") }
    var formRent by remember { mutableStateOf("") }
    var formPriceSale by remember { mutableStateOf("") }
    var formDeposit by remember { mutableStateOf("") }
    var formDepositPreset by remember { mutableStateOf("3 Months Rent (Standard)") }
    var formEscalation by remember { mutableStateOf("5% Yearly") }
    var formLockInPeriod by remember { mutableStateOf("36 Months") }
    var formRoi by remember { mutableStateOf("") }

    // Type-Specific Fields
    var formWorkstations by remember { mutableStateOf("") }
    var formCabins by remember { mutableStateOf("") }
    var formBhkConfig by remember { mutableStateOf("3 BHK") }

    // Confidential Owner / Source
    var formPocType by remember(initialPocType) { mutableStateOf(initialPocType.ifBlank { "Property Owner" }) }
    var formSourceType by remember { mutableStateOf("Direct Owner / Landlord") }
    var formOwnerName by remember(initialOwnerName) { mutableStateOf(initialOwnerName) }
    var formOwnerContact by remember(initialOwnerPhone) { mutableStateOf(initialOwnerPhone) }
    var formRemarks by remember(initialRemarks) { mutableStateOf(initialRemarks) }
    var formGoogleMapsLink by remember { mutableStateOf("") }

    // Google Drive Media & Documents
    var uploadedPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var uploadedAttachments by remember { mutableStateOf<List<PropertyAttachment>>(emptyList()) }
    var formVideoUrl by remember { mutableStateOf<String?>(null) }
    var formFloorPlanUrl by remember { mutableStateOf<String?>(null) }

    // AI Smart Paste + Vision Brochure Scanner + Voice Fill (Build 01 Safe Two-Step Review)
    var rawInputText by remember { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    var isScanningBrochure by remember { mutableStateOf(false) }
    var extractionBatchResult by remember { mutableStateOf<WhatsAppExtractionBatchResult?>(null) }
    var extractionErrorText by remember { mutableStateOf<String?>(null) }

    val brochurePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        selectedMode = 1
        isScanningBrochure = true
        extractionErrorText = null
        coroutineScope.launch {
            try {
                val jpegBytes = GeminiProductivityEngine.compressUriToJpegBytes(context, uri)
                if (jpegBytes == null || jpegBytes.isEmpty()) {
                    extractionErrorText = "Could not read selected brochure/screenshot image."
                } else {
                    val result = GeminiProductivityEngine.extractPropertyFromImageBytes(
                        imageBytes = jpegBytes,
                        mimeType = "image/jpeg",
                        extraCaption = rawInputText.trim()
                    )
                    extractionBatchResult = result
                    if (!result.errorMessage.isNullOrBlank()) {
                        extractionErrorText = result.errorMessage
                    } else {
                        Toast.makeText(
                            context,
                            "Brochure scanned (${result.drafts.size} option${if (result.drafts.size == 1) "" else "s"} detected) — review before filling.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                extractionErrorText = "Brochure scan failed (${e.localizedMessage ?: "error"})."
            } finally {
                isScanningBrochure = false
            }
        }
    }

    val voiceFillLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotBlank()) {
                rawInputText = spoken
                selectedMode = 1
                isExtracting = true
                extractionErrorText = null
                coroutineScope.launch {
                    try {
                        extractionBatchResult = aiService.extractWhatsAppSafely(spoken)
                    } catch (e: Exception) {
                        extractionErrorText = "Voice parsing failed (${e.localizedMessage ?: "offline"})."
                    } finally {
                        isExtracting = false
                    }
                }
            }
        }
    }

    fun triggerBrochureScan() {
        runCatching {
            brochurePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.onFailure {
            Toast.makeText(context, "Photo Picker unavailable on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerVoiceFill() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak property details (e.g., 1200 sqft office in Kharadi for lease at 95k)")
        }
        runCatching {
            voiceFillLauncher.launch(intent)
        }.onFailure {
            Toast.makeText(context, "Voice dictation not available on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    val projectLookupMap = remember(directoryProjects, masterCommercialProjects, masterLocations) {
        val locById = masterLocations.associate { it.id to it.name.trim() }
        val map = LinkedHashMap<String, Pair<String, String>>() // Name -> Pair(Location, Developer)
        directoryProjects.forEach { dp ->
            if (dp.name.isNotBlank()) {
                map[dp.name.trim()] = dp.location.trim() to dp.developer.trim()
            }
        }
        masterCommercialProjects.forEach { cp ->
            val name = cp.name.trim()
            if (name.isNotBlank() && !map.containsKey(name)) {
                map[name] = (locById[cp.locationId] ?: "") to ""
            }
        }
        map
    }

    val projectOptions = remember(projectLookupMap) {
        projectLookupMap.keys.sorted() + "+ Add New Project"
    }

    val localityOptions = remember(masterLocations) {
        (masterLocations.map { it.name.trim() } +
            TlcStandardOptions.puneLocations +
            TlcStandardOptions.kharadiMicroMarkets)
            .filter { it.isNotBlank() }
            .distinct()
    }

    val propertyTypeOptions = remember(selectedTab) {
        if (selectedTab.isCommercial) {
            TlcStandardOptions.commercialPropertyTypes
        } else {
            TlcStandardOptions.residentialPropertyTypes
        }
    }

    val carpetDouble = formCarpetArea.toDoubleOrNull() ?: 0.0
    val builtUpDouble = formBuiltUpArea.toDoubleOrNull() ?: 0.0
    val rentDouble = formRent.toDoubleOrNull() ?: 0.0
    val saleDouble = formPriceSale.toDoubleOrNull() ?: 0.0
    val depositDouble = formDeposit.toDoubleOrNull() ?: 0.0

    val autoLoadingPercent = remember(carpetDouble, builtUpDouble) {
        if (carpetDouble > 0.0 && builtUpDouble >= carpetDouble && builtUpDouble > 0.0) {
            CalculationUtils.calculateLoadingPercentage(carpetDouble, builtUpDouble)
        } else null
    }

    val autoGrossYield = remember(rentDouble, saleDouble, formRoi) {
        formRoi.toDoubleOrNull() ?: if (rentDouble > 0.0 && saleDouble > 0.0) {
            CalculationUtils.calculateGrossYield(rentDouble, saleDouble)
        } else null
    }

    val potentialDuplicates = remember(formPropertyCode, formProject, formUnitNo) {
        if (formProject.isNotBlank() && formUnitNo.isNotBlank()) {
            onCheckDuplicates(formPropertyCode, formProject, formUnitNo)
        } else emptyList()
    }

    val draftPropertyItem = remember(
        draftId, formPropertyCode, formDate, formLocation, formProject, formProjectId,
        formDeveloper, formUnitNo, formFloor, formPropertyType, selectedTab, formCategory,
        formTransactionType, carpetDouble, builtUpDouble, rentDouble, saleDouble, depositDouble,
        autoGrossYield, formPossession, formFurnishing, formStatus, formParking, formEscalation,
        formLockInPeriod, formWorkstations, formCabins, formBhkConfig, formPocType, formSourceType,
        formOwnerName, formOwnerContact, formRemarks, formGoogleMapsLink,
        uploadedPhotos, uploadedAttachments, formVideoUrl, formFloorPlanUrl
    ) {
        val lockInMonths = formLockInPeriod.filter { it.isDigit() }.toIntOrNull()
        val escalationNum = formEscalation.substringBefore("%").trim().toDoubleOrNull()
        val isBroker = formPocType.equals("Broker", ignoreCase = true) ||
            formSourceType.contains("Broker", ignoreCase = true) ||
            formSourceType.contains("Channel Partner", ignoreCase = true)
        PropertyItem(
            id = draftId,
            propertyCode = formPropertyCode.trim().ifBlank { onGenerateCode(selectedTab) },
            date = formDate,
            location = formLocation.trim(),
            projectName = formProject.trim(),
            projectId = formProjectId,
            builderName = formDeveloper.trim().ifBlank { null },
            unitNumber = formUnitNo.trim(),
            floor = formFloor.trim(),
            propertyType = formPropertyType.trim(),
            bhk = if (formCategory == PropertyCategory.RESIDENTIAL) formBhkConfig else null,
            tab = selectedTab,
            portfolio = if (selectedTab.isCommercial) "Commercial" else "Residential",
            transactionType = formTransactionType,
            carpetAreaSqFt = carpetDouble,
            builtUpAreaSqFt = builtUpDouble.takeIf { it > 0.0 },
            priceOrRent = if (isLeaseTx) rentDouble else saleDouble,
            priceSale = if (isSaleTx || isPreLeasedTab) saleDouble.takeIf { it > 0.0 } else null,
            rent = if (isLeaseTx || isPreLeasedTab) rentDouble.takeIf { it > 0.0 } else null,
            deposit = if (isLeaseTx || isPreLeasedTab) depositDouble.takeIf { it > 0.0 } else null,
            roiPercent = autoGrossYield,
            possession = formPossession.trim(),
            furnishing = formFurnishing,
            status = formStatus,
            parking = formParking.trim(),
            escalationPercent = escalationNum,
            escalationIntervalYears = if (formEscalation.contains("3 Year", ignoreCase = true)) 3 else 1,
            lockInMonths = lockInMonths,
            lockInPeriodYears = lockInMonths?.let { it / 12 },
            workstations = formWorkstations.toIntOrNull(),
            cabins = formCabins.toIntOrNull(),
            ownerName = if (!isBroker) formOwnerName.trim().ifBlank { null } else null,
            ownerContact = if (!isBroker) formOwnerContact.trim().ifBlank { null } else null,
            brokerName = if (isBroker) formOwnerName.trim().ifBlank { null } else null,
            brokerContact = if (isBroker) formOwnerContact.trim().ifBlank { null } else null,
            pocType = normalizePocType(formPocType),
            sourceInfo = listOf(formSourceType.trim(), formOwnerName.trim()).filter { it.isNotBlank() }.joinToString(" - "),
            remarks = formRemarks.trim().ifBlank { null },
            listingLink = formGoogleMapsLink.trim().ifBlank { uploadedPhotos.firstOrNull { it.startsWith("http") } },
            googleMapsUrl = formGoogleMapsLink.trim().ifBlank { null },
            images = uploadedPhotos,
            attachments = uploadedAttachments,
            videoUrl = formVideoUrl,
            floorPlanUrl = formFloorPlanUrl
        )
    }

    val draftCompleteness = remember(draftPropertyItem) {
        InventoryQuality.completenessReport(draftPropertyItem)
    }

    val hasUnsavedInput = formProject.isNotBlank() ||
        formCarpetArea.isNotBlank() ||
        formRent.isNotBlank() ||
        formPriceSale.isNotBlank() ||
        formOwnerName.isNotBlank() ||
        rawInputText.isNotBlank()

    fun handleRequestBack() {
        if (hasUnsavedInput && !isSavingAndPushing) {
            showUnsavedExitDialog = true
        } else if (!isSavingAndPushing) {
            onCancel()
        }
    }

    BackHandler { handleRequestBack() }

    fun submitSaveAndPush() {
        if (isSavingAndPushing) return
        if (formProject.isBlank() || formLocation.isBlank()) {
            Toast.makeText(context, "Select or enter Project / Building and Locality.", Toast.LENGTH_SHORT).show()
            return
        }
        if (carpetDouble <= 0.0) {
            Toast.makeText(context, "Enter a valid Carpet Area (sq.ft).", Toast.LENGTH_SHORT).show()
            return
        }
        if (isLeaseTx && rentDouble <= 0.0) {
            Toast.makeText(context, "Enter a valid Monthly Rent (₹).", Toast.LENGTH_SHORT).show()
            return
        }
        if ((isSaleTx || isPreLeasedTab) && saleDouble <= 0.0) {
            Toast.makeText(context, "Enter a valid Sale Price (₹).", Toast.LENGTH_SHORT).show()
            return
        }
        val cleanDigits = formOwnerContact.filter { it.isDigit() }
        if (cleanDigits.isNotEmpty() && cleanDigits.length != 10) {
            Toast.makeText(context, "Contact number must be 10 digits.", Toast.LENGTH_SHORT).show()
            return
        }

        isSavingAndPushing = true
        coroutineScope.launch {
            val targetPropId = draftPropertyItem.propertyCode.ifBlank { draftPropertyItem.id }
            val syncedPhotos = withContext(Dispatchers.IO) {
                draftPropertyItem.images.mapIndexed { idx, imgPath ->
                    val trimmed = imgPath.trim()
                    if (trimmed.isNotBlank() && !trimmed.startsWith("http", ignoreCase = true)) {
                        val file = File(trimmed.removePrefix("file://"))
                        if (file.exists() && file.length() > 0L) {
                            effectiveUploadMediaToDrive(
                                targetPropId,
                                "photo_${System.currentTimeMillis()}_${idx + 1}.jpg",
                                "image/jpeg",
                                file.readBytes()
                            ).getOrDefault(imgPath)
                        } else {
                            imgPath
                        }
                    } else {
                        imgPath
                    }
                }
            }

            val syncedAttachments = withContext(Dispatchers.IO) {
                draftPropertyItem.attachments.map { att ->
                    val trimmed = att.localUriOrPath.trim()
                    if (trimmed.isNotBlank() && !trimmed.startsWith("http", ignoreCase = true)) {
                        val file = File(trimmed.removePrefix("file://"))
                        if (file.exists() && file.length() > 0L) {
                            val driveUrl = effectiveUploadMediaToDrive(
                                targetPropId,
                                att.name,
                                att.mimeType.ifBlank { "application/pdf" },
                                file.readBytes()
                            ).getOrDefault(att.localUriOrPath)
                            att.copy(localUriOrPath = driveUrl)
                        } else {
                            att
                        }
                    } else {
                        att
                    }
                }
            }

            val syncedFloorPlan = syncedAttachments.firstOrNull { it.type == AttachmentType.FLOOR_PLAN }?.localUriOrPath
                ?: draftPropertyItem.floorPlanUrl
            val syncedVideo = syncedAttachments.firstOrNull { it.type == AttachmentType.VIDEO }?.localUriOrPath
                ?: draftPropertyItem.videoUrl

            val baseFinalItem = draftPropertyItem.copy(
                images = syncedPhotos,
                attachments = syncedAttachments,
                floorPlanUrl = syncedFloorPlan,
                videoUrl = syncedVideo,
                listingLink = formGoogleMapsLink.trim().ifBlank { syncedPhotos.firstOrNull { it.startsWith("http") } }
            )
            val isReadyLive = (fromCallIntelligence || initialOwnerPhone.isNotBlank()) &&
                InventoryQuality.isReadyForLiveVerification(baseFinalItem)
            val finalPropertyItem = if (isReadyLive) {
                val todayIso = java.time.LocalDate.now().toString()
                val todayReadable = java.text.SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(java.util.Date())
                baseFinalItem.copy(
                    verificationStatus = VerificationStatus.VERIFIED,
                    archiveStatus = ArchiveStatus.ACTIVE,
                    lastVerifiedDateClean = baseFinalItem.lastVerifiedDateClean.ifBlank { todayIso },
                    lastVerificationDate = baseFinalItem.lastVerificationDate.ifBlank { todayReadable }
                )
            } else {
                baseFinalItem
            }

            val savedLocally = onSaveProperty(finalPropertyItem)
            if (!savedLocally) {
                isSavingAndPushing = false
                Toast.makeText(context, "Unable to save property. Check duplicate code or fields.", Toast.LENGTH_LONG).show()
                return@launch
            }

            if (formProjectId.isNotBlank()) {
                runCatching { ProjectDirectoryStore.linkUnit(context, formProjectId, finalPropertyItem.id) }
            }

            val pushRes = runCatching { effectivePushPropertyToSheet(finalPropertyItem) }
                .getOrElse { Result.failure(it) }
            isSavingAndPushing = false
            val msg = if (pushRes.isSuccess) {
                "Saved ${finalPropertyItem.propertyCode} & pushed to Master Sheet!"
            } else {
                "Saved ${finalPropertyItem.propertyCode} locally; Sheet sync queued."
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }

    Scaffold(
        containerColor = BackgroundSoft,
        topBar = {
            Surface(
                color = SurfaceWhite,
                shadowElevation = 3.dp,
                border = BorderStroke(0.5.dp, BorderGrey),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(
                                onClick = { handleRequestBack() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("add_back_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = TextPrimary
                                )
                            }
                            Column {
                                Text(
                                    text = "Add Property",
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = "$formPropertyCode • ${selectedTab.code}",
                                    color = FocusOutlineColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }

                        val tierColor = when (draftCompleteness.colorTier) {
                            CompletenessColorTier.GREEN -> SuccessGreen
                            CompletenessColorTier.AMBER -> StatusWarningAmber
                            CompletenessColorTier.RED -> ErrorRed
                        }
                        Surface(
                            color = tierColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = "${draftCompleteness.percentage}% Ready",
                                color = tierColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Compact Mode Toggle: Standard 1-Page Form | AI Smart Paste
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ModeSwitchTab(
                            label = "Standard Form",
                            icon = Icons.Default.EditNote,
                            selected = selectedMode == 0,
                            onClick = { selectedMode = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        ModeSwitchTab(
                            label = "AI Smart Paste",
                            icon = Icons.Default.AutoAwesome,
                            selected = selectedMode == 1,
                            onClick = { selectedMode = 1 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (selectedMode == 0) {
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
                            .imePadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { handleRequestBack() },
                            enabled = !isSavingAndPushing,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("add_cancel_btn")
                        ) {
                            Text("Cancel", fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = { submitSaveAndPush() },
                            enabled = !isSavingAndPushing,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryActionOrange,
                                contentColor = OnPrimaryActionColor
                            ),
                            modifier = Modifier
                                .weight(1.7f)
                                .height(48.dp)
                                .testTag("save_manual_form_btn")
                        ) {
                            if (isSavingAndPushing) {
                                CircularProgressIndicator(
                                    color = OnPrimaryActionColor,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Saving & Syncing...", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            } else {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save & Push to Sheet", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selectedMode == 0) {
                // ── QUICK GEMINI AI ASSIST STRIP (BROCHURE SCAN + VOICE FILL) ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { triggerBrochureScan() },
                        enabled = !isScanningBrochure && !isExtracting,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, PrimaryNavy.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SurfaceWhite,
                            contentColor = PrimaryNavy
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("add_scan_brochure_btn")
                    ) {
                        Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Scan Brochure", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { triggerVoiceFill() },
                        enabled = !isScanningBrochure && !isExtracting,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SelectedSurfaceContainer,
                            contentColor = PrimaryActionOrange
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("add_voice_fill_btn")
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Voice Fill AI", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }

                // ── DUPLICATE WARNING BANNER ──
                if (potentialDuplicates.isNotEmpty()) {
                    Surface(
                        color = StatusWarningAmber.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StatusWarningAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarningAmber, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Possible duplicate unit (${potentialDuplicates.first().propertyCode} • ${potentialDuplicates.first().projectName}). Verify before saving.",
                                color = TextPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // ── MAIN 2-COLUMN UNIFIED PROPERTY FORM CARD ──
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "CLASSIFICATION & PROJECT",
                            color = FocusOutlineColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp
                        )

                        // Row 1: Category / Sheet | Property Type
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Category / Sheet *",
                                selectedValue = selectedTab.code,
                                options = TlcStandardOptions.inventoryTabs,
                                onValueSelected = { chosen ->
                                    val newTab = InventoryTab.entries.firstOrNull { it.code == chosen } ?: InventoryTab.OFF_LEASE
                                    val divisionChanged = newTab.isCommercial != selectedTab.isCommercial
                                    selectedTab = newTab
                                    if (divisionChanged) {
                                        formPropertyType = if (newTab.isCommercial) {
                                            TlcStandardOptions.commercialPropertyTypes.first()
                                        } else {
                                            TlcStandardOptions.residentialPropertyTypes.first()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )

                            CompactDropdownField(
                                label = "Property Type *",
                                selectedValue = formPropertyType,
                                options = propertyTypeOptions,
                                onValueSelected = { formPropertyType = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )
                        }

                        // Row 2: Searchable Project / Building * | Locality / Micro-Market *
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SearchableDropdownField(
                                label = "Project / Building *",
                                selectedValue = formProject,
                                options = projectOptions,
                                onValueSelected = { picked ->
                                    if (picked == "+ Add New Project") {
                                        pendingNewProjectName = formProject
                                        showQuickAddProjectDialog = true
                                    } else {
                                        formProject = picked
                                        val dirMatch = directoryProjects.firstOrNull {
                                            it.name.equals(picked.trim(), ignoreCase = true)
                                        }
                                        if (dirMatch != null) {
                                            formProjectId = dirMatch.id
                                            if (dirMatch.location.isNotBlank()) formLocation = dirMatch.location
                                            if (dirMatch.developer.isNotBlank()) formDeveloper = dirMatch.developer
                                        } else {
                                            val lookup = projectLookupMap[picked.trim()]
                                            if (lookup != null) {
                                                if (lookup.first.isNotBlank()) formLocation = lookup.first
                                                if (lookup.second.isNotBlank()) formDeveloper = lookup.second
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                allowAddNew = true
                            )

                            SearchableDropdownField(
                                label = "Locality / Micro-Market *",
                                selectedValue = formLocation,
                                options = localityOptions,
                                onValueSelected = { formLocation = it },
                                modifier = Modifier.weight(1f),
                                allowAddNew = true
                            )
                        }

                        // Row 3: Unit / Office No | Floor
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactFormInputField(
                                label = "Unit / Office No",
                                value = formUnitNo,
                                onValueChange = { formUnitNo = it },
                                placeholder = "e.g. 402 / Shop 4",
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("add_unit_input")
                            )

                            CompactDropdownField(
                                label = "Floor",
                                selectedValue = formFloor,
                                options = TlcStandardOptions.floors,
                                onValueSelected = { formFloor = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )
                        }

                        HorizontalDivider(color = BorderSubtle)

                        Text(
                            text = "AREA & FINANCIALS (${formTransactionType.uppercase(Locale.ENGLISH)})",
                            color = FocusOutlineColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp
                        )

                        // Row 4: Carpet Area | Built-up Area
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactFormInputField(
                                label = "Carpet Area (sq.ft) *",
                                value = formCarpetArea,
                                onValueChange = { formCarpetArea = sanitizeDecimalInput(it) },
                                placeholder = "e.g. 1250",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("add_carpet_input")
                            )

                            CompactFormInputField(
                                label = "Built-up Area (sq.ft)",
                                value = formBuiltUpArea,
                                onValueChange = { formBuiltUpArea = sanitizeDecimalInput(it) },
                                placeholder = "Optional",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("add_builtup_input")
                            )
                        }

                        // Row 5 (Transaction-Aware Financials)
                        when {
                            isLeaseTx -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CompactFormInputField(
                                        label = "Monthly Rent (₹) *",
                                        value = formRent,
                                        onValueChange = { raw ->
                                            formRent = sanitizeDecimalInput(raw)
                                            val rVal = formRent.toDoubleOrNull() ?: 0.0
                                            val months = formDepositPreset.takeWhile { it.isDigit() }.toIntOrNull()
                                            if (rVal > 0.0 && months != null) {
                                                formDeposit = (rVal * months).toLong().toString()
                                            }
                                        },
                                        placeholder = "e.g. 85000",
                                        keyboardType = KeyboardType.Decimal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("add_rent_input")
                                    )

                                    CompactDropdownField(
                                        label = "Security Deposit",
                                        selectedValue = formDepositPreset,
                                        options = TlcStandardOptions.depositOptions,
                                        onValueSelected = { preset ->
                                            formDepositPreset = preset
                                            val months = preset.takeWhile { it.isDigit() }.toIntOrNull()
                                            if (months != null && rentDouble > 0.0) {
                                                formDeposit = (rentDouble * months).toLong().toString()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        allowCustom = true
                                    )
                                }
                            }
                            isPreLeasedTab -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CompactFormInputField(
                                        label = "Sale Price (₹) *",
                                        value = formPriceSale,
                                        onValueChange = { formPriceSale = sanitizeDecimalInput(it) },
                                        placeholder = "e.g. 25000000",
                                        keyboardType = KeyboardType.Decimal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("add_sale_input")
                                    )

                                    CompactFormInputField(
                                        label = "Monthly Rent (₹)",
                                        value = formRent,
                                        onValueChange = { formRent = sanitizeDecimalInput(it) },
                                        placeholder = "e.g. 145000",
                                        keyboardType = KeyboardType.Decimal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("add_rent_input")
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CompactFormInputField(
                                        label = "ROI %",
                                        value = formRoi,
                                        onValueChange = { formRoi = sanitizeDecimalInput(it) },
                                        placeholder = autoGrossYield?.let { String.format(Locale.US, "%.2f", it) } ?: "e.g. 7.2",
                                        keyboardType = KeyboardType.Decimal,
                                        modifier = Modifier.weight(1f)
                                    )

                                    CompactDropdownField(
                                        label = "Security Deposit",
                                        selectedValue = formDepositPreset,
                                        options = TlcStandardOptions.depositOptions,
                                        onValueSelected = { preset ->
                                            formDepositPreset = preset
                                            val months = preset.takeWhile { it.isDigit() }.toIntOrNull()
                                            if (months != null && rentDouble > 0.0) {
                                                formDeposit = (rentDouble * months).toLong().toString()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        allowCustom = true
                                    )
                                }
                            }
                            else -> {
                                // Outright Sale / Resale / Under Construction
                                CompactFormInputField(
                                    label = "Sale Price (₹) *",
                                    value = formPriceSale,
                                    onValueChange = { formPriceSale = sanitizeDecimalInput(it) },
                                    placeholder = "e.g. 12500000 (1.25 Cr)",
                                    keyboardType = KeyboardType.Decimal,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("add_sale_input")
                                )
                            }
                        }

                        // Live Currency & Area Rate Preview Strip
                        val primaryFinancial = if (isLeaseTx) rentDouble else saleDouble
                        if (primaryFinancial > 0.0 || autoLoadingPercent != null) {
                            Surface(
                                color = SurfaceMuted,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val priceTag = buildString {
                                        if (primaryFinancial > 0.0) {
                                            append(formatIndianShortPreview(primaryFinancial))
                                            if (isLeaseTx) append("/mo")
                                            if (carpetDouble > 0.0) {
                                                append(" • ${CalculationUtils.formatRateValue(primaryFinancial / carpetDouble)}/sq.ft")
                                            }
                                        }
                                    }
                                    Text(
                                        text = priceTag.ifBlank { "Area Metrics" },
                                        color = TextPrimary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (autoLoadingPercent != null) {
                                        Text(
                                            text = String.format(Locale.US, "Load: %.1f%%", autoLoadingPercent),
                                            color = FocusOutlineColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Commercial Lease Terms Row (Lease / Pre-Leased only)
                        if (selectedTab.isCommercial && (isLeaseTx || isPreLeasedTab)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CompactDropdownField(
                                    label = "Escalation",
                                    selectedValue = formEscalation,
                                    options = TlcStandardOptions.escalationTerms,
                                    onValueSelected = { formEscalation = it },
                                    modifier = Modifier.weight(1f),
                                    allowCustom = true
                                )
                                CompactDropdownField(
                                    label = "Lock-in Period",
                                    selectedValue = formLockInPeriod,
                                    options = TlcStandardOptions.lockInPeriods,
                                    onValueSelected = { formLockInPeriod = it },
                                    modifier = Modifier.weight(1f),
                                    allowCustom = true
                                )
                            }
                        }

                        HorizontalDivider(color = BorderSubtle)

                        // Row 6: Furnishing | Possession
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Furnishing",
                                selectedValue = formFurnishing.label,
                                options = TlcStandardOptions.furnishingTypes,
                                onValueSelected = { picked ->
                                    formFurnishing = FurnishingType.entries.firstOrNull {
                                        it.label.equals(picked, ignoreCase = true)
                                    } ?: FurnishingType.WARM_SHELL
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )

                            CompactDropdownField(
                                label = "Possession",
                                selectedValue = formPossession,
                                options = TlcStandardOptions.possessionTimelines,
                                onValueSelected = { formPossession = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )
                        }

                        // Row 7: Parking | Workstations/Cabins (Commercial) OR BHK (Residential)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Parking",
                                selectedValue = formParking,
                                options = TlcStandardOptions.parkingOptions,
                                onValueSelected = { formParking = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )

                            if (selectedTab.isCommercial) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CompactFormInputField(
                                        label = "Seats / WS",
                                        value = formWorkstations,
                                        onValueChange = { formWorkstations = sanitizeIntegerInput(it) },
                                        placeholder = "0",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                    CompactFormInputField(
                                        label = "Cabins",
                                        value = formCabins,
                                        onValueChange = { formCabins = sanitizeIntegerInput(it) },
                                        placeholder = "0",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                CompactDropdownField(
                                    label = "Configuration (BHK)",
                                    selectedValue = formBhkConfig,
                                    options = TlcStandardOptions.bhkConfigs,
                                    onValueSelected = { formBhkConfig = it },
                                    modifier = Modifier.weight(1f),
                                    allowCustom = true
                                )
                            }
                        }
                    }
                }

                // ── ROW 8: COMPACT GOOGLE DRIVE PHOTOS & DOCUMENTS ──
                PropertyMediaUploadSection(
                    photosList = uploadedPhotos,
                    onPhotosChange = { uploadedPhotos = it },
                    attachments = uploadedAttachments,
                    onAttachmentsChange = { uploadedAttachments = it },
                    videoUrl = formVideoUrl,
                    onVideoUrlChange = { formVideoUrl = it },
                    floorPlanUrl = formFloorPlanUrl,
                    onFloorPlanUrlChange = { formFloorPlanUrl = it },
                    propertyRecordId = formPropertyCode.ifBlank { draftId },
                    onUploadMediaToDrive = effectiveUploadMediaToDrive
                )

                // ── ROW 9: CONFIDENTIAL OWNER & SOURCE 2-COLUMN BOX ──
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val pickAddContact = com.example.crm.rememberPickContactCallback { pickedName, cleanPhone ->
                            if (pickedName.isNotBlank()) formOwnerName = pickedName
                            if (cleanPhone.isNotBlank()) formOwnerContact = cleanPhone
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CONFIDENTIAL OWNER & SOURCE DETAILS",
                                color = FocusOutlineColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.6.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = pickAddContact,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("add_pick_from_contacts_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContactPhone,
                                    contentDescription = "Pick from Contacts",
                                    tint = PrimaryActionOrange,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Pick from Contacts",
                                    color = PrimaryActionOrange,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        PocTypeChipSelector(
                            selectedPocType = formPocType,
                            onSelect = { formPocType = it },
                            title = "Type of POC",
                            testTagPrefix = "add_poc_chip"
                        )

                        // Row 9a: Owner / POC | Contact Number
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactFormInputField(
                                label = "Owner / POC",
                                value = formOwnerName,
                                onValueChange = { formOwnerName = it },
                                placeholder = "Owner or CP Name",
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("add_owner_name_input")
                            )

                            CompactFormInputField(
                                label = "Contact Number",
                                value = formOwnerContact,
                                onValueChange = { formOwnerContact = it.filter { ch -> ch.isDigit() }.take(10) },
                                placeholder = "10-digit mobile",
                                keyboardType = KeyboardType.Phone,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("add_owner_contact_input")
                            )
                        }

                        // Row 9b: Source | Availability Status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Source",
                                selectedValue = formSourceType,
                                options = TlcStandardOptions.listingSources,
                                onValueSelected = { formSourceType = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )

                            CompactDropdownField(
                                label = "Availability Status",
                                selectedValue = formStatus.label,
                                options = TlcStandardOptions.availabilityStatuses,
                                onValueSelected = { picked ->
                                    formStatus = AvailabilityStatus.parse(picked)
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                        }

                        // Row 9c: Remarks | Google Maps Link
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactFormInputField(
                                label = "Remarks / Highlights",
                                value = formRemarks,
                                onValueChange = { formRemarks = it },
                                placeholder = "Key features, pantry, tenant...",
                                modifier = Modifier
                                    .weight(1.2f)
                                    .testTag("add_remarks_input")
                            )

                            CompactFormInputField(
                                label = "Google Maps Link",
                                value = formGoogleMapsLink,
                                onValueChange = { formGoogleMapsLink = it },
                                placeholder = "https://maps.google...",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            } else {
                // ── MODE 1: BUILD 01 SAFE WHATSAPP AI EXTRACTION ──
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Safe WhatsApp, Brochure & Voice AI Extraction",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Paste a WhatsApp listing, scan a brochure/screenshot, or dictate by voice. Gemini flags uncertain fields with evidence and never invents missing numbers.",
                            color = SecondaryText,
                            fontSize = 12.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { triggerBrochureScan() },
                                enabled = !isScanningBrochure && !isExtracting,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, PrimaryNavy),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color(0xFFF1F5F9),
                                    contentColor = PrimaryNavy
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("ai_mode_scan_brochure_btn")
                            ) {
                                if (isScanningBrochure) {
                                    CircularProgressIndicator(
                                        color = PrimaryNavy,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(15.dp)
                                    )
                                } else {
                                    Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = if (isScanningBrochure) "Scanning..." else "Scan Brochure",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                            OutlinedButton(
                                onClick = { triggerVoiceFill() },
                                enabled = !isScanningBrochure && !isExtracting,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, PrimaryActionOrange),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = SelectedSurfaceContainer,
                                    contentColor = PrimaryActionOrange
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("ai_mode_voice_fill_btn")
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Voice Dictate", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                        OutlinedTextField(
                            value = rawInputText,
                            onValueChange = {
                                rawInputText = it
                                extractionErrorText = null
                            },
                            placeholder = {
                                Text(
                                    "Paste 1 or multiple properties from WhatsApp (e.g., Office for lease at Gera Imperium Alpha, Kharadi, 1100 sqft CA, 25 WS, rent 80k...)",
                                    fontSize = 12.sp
                                )
                            },
                            minLines = 5,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_raw_input_field")
                        )

                        if (!extractionErrorText.isNullOrBlank()) {
                            Surface(
                                color = Color(0xFFFEF2F2),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = extractionErrorText.orEmpty(),
                                    color = ErrorRed,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (rawInputText.isBlank()) return@Button
                                isExtracting = true
                                extractionErrorText = null
                                coroutineScope.launch {
                                    try {
                                        extractionBatchResult = aiService.extractWhatsAppSafely(rawInputText)
                                    } catch (e: Exception) {
                                        extractionErrorText = "Extraction error (${e.localizedMessage ?: "offline"}). You can switch to Manual Entry anytime."
                                        extractionBatchResult = null
                                    } finally {
                                        isExtracting = false
                                    }
                                }
                            },
                            enabled = !isExtracting && rawInputText.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("extract_ai_btn"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryActionOrange,
                                contentColor = OnPrimaryActionColor
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isExtracting) {
                                CircularProgressIndicator(
                                    color = OnPrimaryActionColor,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = if (isExtracting) "Analyzing & Classifying..." else "Analyze & Review Before Filling",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                extractionBatchResult?.let { batchResult ->
                    Spacer(modifier = Modifier.height(10.dp))
                    SafeWhatsAppReviewPanel(
                        result = batchResult,
                        onCheckDuplicates = onCheckDuplicates,
                        onConfirmApplyDraft = { draft: ExtractedPropertyDraft ->
                            // Populate strictly from confirmed draft — clear unmentioned defaults so zero values are fabricated
                            selectedTab = draft.tab
                            if (draft.propertyType.isNotBlank()) formPropertyType = draft.propertyType
                            formProject = draft.projectName
                            val matchedDir = directoryProjects.firstOrNull {
                                draft.projectName.isNotBlank() && it.name.equals(draft.projectName, ignoreCase = true)
                            }
                            formProjectId = matchedDir?.id.orEmpty()
                            formLocation = draft.location.ifBlank { matchedDir?.location.orEmpty() }
                            formDeveloper = draft.builderName.ifBlank { matchedDir?.developer.orEmpty() }
                            formUnitNo = draft.unitNumber
                            formFloor = draft.floor
                            formPossession = draft.possession
                            formParking = draft.parking
                            formLockInPeriod = draft.lockInMonths
                            formEscalation = draft.escalationTerms
                            formBhkConfig = draft.bhkConfiguration
                            formFurnishing = if (draft.hasExplicitFurnishing) draft.furnishing else FurnishingType.UNFURNISHED

                            formCarpetArea = if (draft.carpetAreaSqFt > 0.0) {
                                draft.carpetAreaSqFt.toInt().toString()
                            } else ""
                            formBuiltUpArea = draft.builtUpAreaSqFt?.takeIf { it > 0.0 }?.toInt()?.toString().orEmpty()
                            formRent = draft.rent?.takeIf { it > 0.0 }?.toLong()?.toString().orEmpty()
                            formPriceSale = draft.priceSale?.takeIf { it > 0.0 }?.toLong()?.toString().orEmpty()
                            formDeposit = draft.deposit?.takeIf { it > 0.0 }?.toLong()?.toString().orEmpty()
                            formDepositPreset = ""
                            formRoi = draft.roiPercent?.takeIf { it > 0.0 }?.toString().orEmpty()
                            formWorkstations = draft.workstations?.toString().orEmpty()
                            formCabins = draft.cabins?.toString().orEmpty()

                            // Separate Owner vs Source/Broker — never assume sender/broker is Owner
                            if (draft.ownerPhone.isNotBlank() || draft.ownerName.isNotBlank()) {
                                formPocType = "Property Owner"
                                formSourceType = "Direct Owner / Landlord"
                                formOwnerName = draft.ownerName
                                formOwnerContact = draft.ownerPhone
                            } else if (draft.sourceBrokerPhone.isNotBlank() || draft.sourceBrokerName.isNotBlank()) {
                                formPocType = "Broker"
                                formSourceType = "Broker / Channel Partner"
                                formOwnerName = draft.sourceBrokerName
                                formOwnerContact = draft.sourceBrokerPhone
                            } else {
                                formOwnerName = ""
                                formOwnerContact = ""
                            }

                            val extraNotes = buildList {
                                if (draft.saleableAreaSqFt != null && draft.saleableAreaSqFt > 0.0) {
                                    add("SBA: ${draft.saleableAreaSqFt.toInt()} sq.ft")
                                }
                                if (draft.landAreaValue != null && draft.landAreaUnit.isNotBlank()) {
                                    add("Land Area: ${draft.landAreaValue} ${draft.landAreaUnit}")
                                }
                                if (draft.isSevenTwelveClear == true) add("7/12 Extract mentioned")
                                if (draft.conferenceRooms != null) add("Conf Rooms: ${draft.conferenceRooms}")
                                if (draft.maintenanceTerms.isNotBlank()) add("CAM: ${draft.maintenanceTerms}")
                                if (draft.tenantName.isNotBlank()) add("Tenant: ${draft.tenantName}")
                                if (draft.additionalPhones.isNotEmpty()) {
                                    add("Other Phones: ${draft.additionalPhones.joinToString(", ")}")
                                }
                                if (draft.remarks.isNotBlank()) add(draft.remarks)
                            }.distinct().joinToString("\n")
                            formRemarks = extraNotes

                            selectedMode = 0
                            Toast.makeText(
                                context,
                                "Confirmed draft populated — review & tap Save when ready.",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onRouteClientRequirementToCrm = onRouteClientRequirementToCrm,
                        onSwitchToManualEntry = { selectedMode = 0 }
                    )
                }
            }
        }
    }

    // ── QUICK "+ ADD NEW PROJECT" DIALOG ──
    if (showQuickAddProjectDialog) {
        var newDivision by remember { mutableStateOf(if (selectedTab.isCommercial) "commercial" else "residential") }
        var newProjectName by remember { mutableStateOf(pendingNewProjectName) }
        var newLocality by remember { mutableStateOf(formLocation) }
        var newDeveloper by remember { mutableStateOf("") }
        var dialogError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showQuickAddProjectDialog = false },
            title = {
                Text(
                    text = "Register New Project",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactDropdownField(
                        label = "Division",
                        selectedValue = if (newDivision == "commercial") "Commercial" else "Residential",
                        options = listOf("Commercial", "Residential"),
                        onValueSelected = {
                            newDivision = if (it == "Residential") "residential" else "commercial"
                        },
                        allowCustom = false
                    )
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("Project / Building Name *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SearchableDropdownField(
                        label = "Locality / Micro-Market *",
                        selectedValue = newLocality,
                        options = localityOptions,
                        onValueSelected = { newLocality = it },
                        allowAddNew = true
                    )
                    OutlinedTextField(
                        value = newDeveloper,
                        onValueChange = { newDeveloper = it },
                        label = { Text("Developer / Builder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (dialogError.isNotBlank()) {
                        Text(dialogError, color = ErrorRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            ProjectDirectoryStore.create(
                                context = context,
                                division = newDivision,
                                name = newProjectName,
                                location = newLocality,
                                developer = newDeveloper
                            )
                        }.onSuccess { created ->
                            directoryProjects = ProjectDirectoryStore.read(context)
                            formProjectId = created.id
                            formProject = created.name
                            formLocation = created.location
                            formDeveloper = created.developer
                            showQuickAddProjectDialog = false
                        }.onFailure {
                            dialogError = it.message ?: "Unable to register project."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Save & Select", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickAddProjectDialog = false }) {
                    Text("Cancel", color = TextPrimary)
                }
            },
            containerColor = SurfaceWhite,
            shape = RoundedCornerShape(14.dp)
        )
    }

    // ── UNSAVED CHANGES DIALOG ──
    if (showUnsavedExitDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedExitDialog = false },
            title = { Text("Discard Property Draft?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Leaving now will discard your current entries.", color = SecondaryText, fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedExitDialog = false
                        onCancel()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = Color.White)
                ) {
                    Text("Discard", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedExitDialog = false }) {
                    Text("Keep Editing", color = FocusOutlineColor)
                }
            },
            containerColor = SurfaceWhite,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

@Composable
private fun ModeSwitchTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) FocusOutlineColor else SurfaceMuted,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) FocusOutlineColor else BorderGrey),
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else TextPrimary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = if (selected) Color.White else TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactFormInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(modifier = modifier) {
        Text(
            text = label,
            color = SecondaryText,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(FocusOutlineColor),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            ),
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = if (placeholder.isNotBlank()) {
                    {
                        Text(
                            text = placeholder,
                            color = SecondaryText.copy(alpha = 0.7f),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else null,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceWhite,
                            unfocusedContainerColor = SurfaceWhite,
                            focusedBorderColor = FocusOutlineColor,
                            unfocusedBorderColor = BorderGrey
                        ),
                        shape = RoundedCornerShape(10.dp),
                        focusedBorderThickness = 1.5.dp,
                        unfocusedBorderThickness = 1.dp
                    )
                }
            )
        }
    }
}
