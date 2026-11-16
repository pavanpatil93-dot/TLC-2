package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Warning
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
import com.example.data.SheetsRepository
import com.example.data.projects.DirectoryProject
import com.example.data.projects.ProjectDirectoryStore
import com.example.data.pune.CommercialProjectEntity
import com.example.data.pune.LocationEntity
import com.example.data.pune.PuneIntelligenceRepository
import com.example.model.*
import com.example.ui.components.CompactDropdownField
import com.example.ui.components.PropertyMediaUploadSection
import com.example.ui.components.SearchableDropdownField
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

private val EditNavy: Color get() = TextPrimary
private val EditTeal: Color get() = FocusOutlineColor
private val EditSoftWhite: Color get() = BackgroundSoft
private val EditBorder: Color get() = BorderGrey
private val EditSecondary: Color get() = SecondaryText

private fun sanitizeEditDecimal(raw: String): String {
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

@Composable
fun FullEditPropertyDialog(
    property: PropertyItem,
    onSave: (PropertyItem, String) -> Boolean,
    onDismiss: () -> Unit,
    onSaveReliable: ((expectedVersion: Int, changedFields: Set<String>, draft: PropertyItem, mutationId: String) -> PropertyEditOutcome)? = null,
    onUploadMediaToDrive: (suspend (propertyId: String, fileName: String, mimeType: String, bytes: ByteArray) -> Result<String>)? = null,
    onPushPropertyToSheet: (suspend (PropertyItem) -> Result<Unit>)? = null
) {
    EditPropertyScreen(
        property = property,
        onSave = onSave,
        onDismiss = onDismiss,
        onSaveReliable = onSaveReliable,
        onUploadMediaToDrive = onUploadMediaToDrive,
        onPushPropertyToSheet = onPushPropertyToSheet
    )
}

@Composable
fun EditPropertyScreen(
    property: PropertyItem,
    onSave: (PropertyItem, String) -> Boolean,
    onDismiss: () -> Unit,
    onSaveReliable: ((expectedVersion: Int, changedFields: Set<String>, draft: PropertyItem, mutationId: String) -> PropertyEditOutcome)? = null,
    onUploadMediaToDrive: (suspend (propertyId: String, fileName: String, mimeType: String, bytes: ByteArray) -> Result<String>)? = null,
    onPushPropertyToSheet: (suspend (PropertyItem) -> Result<Unit>)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
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

    var baseProperty by remember(property.id) { mutableStateOf(property) }
    var mutationId by remember(property.id, baseProperty.recordVersion) {
        mutableStateOf(UUID.randomUUID().toString())
    }

    val puneRepo = remember { PuneIntelligenceRepository.getInstance(context) }
    var masterLocations by remember { mutableStateOf<List<LocationEntity>>(emptyList()) }
    var masterCommercialProjects by remember { mutableStateOf<List<CommercialProjectEntity>>(emptyList()) }
    var directoryProjects by remember { mutableStateOf<List<DirectoryProject>>(emptyList()) }
    var showQuickAddProjectDialog by remember { mutableStateOf(false) }
    var pendingNewProjectName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        masterLocations = puneRepo.getAllLocations()
        masterCommercialProjects = puneRepo.getAllCommercialProjects()
        runCatching {
            withContext(Dispatchers.IO) { ProjectDirectoryStore.read(context) }
        }.onSuccess {
            directoryProjects = it
        }
    }

    // Core form state synchronized to baseProperty
    var project by remember(baseProperty) { mutableStateOf(baseProperty.projectName) }
    var projectIdState by remember(baseProperty) { mutableStateOf(baseProperty.projectId) }
    var developerState by remember(baseProperty) { mutableStateOf(baseProperty.builderName ?: "") }
    var location by remember(baseProperty) { mutableStateOf(baseProperty.location) }
    var propertyType by remember(baseProperty) { mutableStateOf(baseProperty.propertyType) }
    var unitNumber by remember(baseProperty) { mutableStateOf(baseProperty.unitNumber) }
    var floor by remember(baseProperty) { mutableStateOf(baseProperty.floor) }
    var carpetArea by remember(baseProperty) {
        mutableStateOf(if (baseProperty.carpetAreaSqFt > 0.0) baseProperty.carpetAreaSqFt.toInt().toString() else "")
    }
    var builtUpArea by remember(baseProperty) {
        mutableStateOf(baseProperty.builtUpAreaSqFt?.toInt()?.toString() ?: "")
    }
    var priceSale by remember(baseProperty) {
        mutableStateOf(
            baseProperty.priceSale?.toLong()?.toString()
                ?: if (!baseProperty.tab.isRental && baseProperty.priceOrRent > 0.0) baseProperty.priceOrRent.toLong().toString() else ""
        )
    }
    var rent by remember(baseProperty) {
        mutableStateOf(
            baseProperty.rent?.toLong()?.toString()
                ?: if (baseProperty.tab.isRental && baseProperty.priceOrRent > 0.0) baseProperty.priceOrRent.toLong().toString() else ""
        )
    }
    var deposit by remember(baseProperty) { mutableStateOf(baseProperty.deposit?.toLong()?.toString() ?: "") }
    var roiPercent by remember(baseProperty) { mutableStateOf(baseProperty.roiPercent?.toString() ?: "") }
    var furnishing by remember(baseProperty) { mutableStateOf(baseProperty.furnishing) }
    var possession by remember(baseProperty) { mutableStateOf(baseProperty.possession) }
    var parking by remember(baseProperty) { mutableStateOf(baseProperty.parking) }
    var workstations by remember(baseProperty) { mutableStateOf(baseProperty.workstations?.toString() ?: "") }
    var cabins by remember(baseProperty) { mutableStateOf(baseProperty.cabins?.toString() ?: "") }
    var bhkConfig by remember(baseProperty) { mutableStateOf(baseProperty.bhk ?: "") }
    var status by remember(baseProperty) { mutableStateOf(baseProperty.status) }
    var expiry by remember(baseProperty) { mutableStateOf(baseProperty.leaseExpiryDate) }
    var vacancy by remember(baseProperty) { mutableStateOf(baseProperty.upcomingVacancyDate) }
    var ownerName by remember(baseProperty) {
        mutableStateOf(baseProperty.ownerName?.takeIf { it.isNotBlank() } ?: baseProperty.brokerName ?: "")
    }
    var ownerContact by remember(baseProperty) {
        mutableStateOf(baseProperty.ownerContact?.takeIf { it.isNotBlank() } ?: baseProperty.brokerContact ?: "")
    }
    var source by remember(baseProperty) { mutableStateOf(baseProperty.sourceInfo ?: "") }
    var remarks by remember(baseProperty) { mutableStateOf(baseProperty.remarks ?: "") }
    var listingLink by remember(baseProperty) {
        mutableStateOf(baseProperty.listingLink ?: baseProperty.googleMapsUrl ?: "")
    }

    // Photos & Google Drive Attachments state
    var photosList by remember(baseProperty) { mutableStateOf(baseProperty.images) }
    var attachmentsList by remember(baseProperty) { mutableStateOf(baseProperty.attachments) }
    var videoUrlState by remember(baseProperty) { mutableStateOf(baseProperty.videoUrl) }
    var floorPlanUrlState by remember(baseProperty) { mutableStateOf(baseProperty.floorPlanUrl) }

    var statusPillExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var conflictLatestProperty by remember { mutableStateOf<PropertyItem?>(null) }
    var showUnsavedConfirm by remember { mutableStateOf(false) }
    var isSavingAndPushing by remember { mutableStateOf(false) }

    val initialCarpetText = if (baseProperty.carpetAreaSqFt > 0.0) baseProperty.carpetAreaSqFt.toInt().toString() else ""
    val initialBuiltUpText = baseProperty.builtUpAreaSqFt?.toInt()?.toString() ?: ""
    val initialSaleText = baseProperty.priceSale?.toLong()?.toString()
        ?: if (!baseProperty.tab.isRental && baseProperty.priceOrRent > 0.0) baseProperty.priceOrRent.toLong().toString() else ""
    val initialRentText = baseProperty.rent?.toLong()?.toString()
        ?: if (baseProperty.tab.isRental && baseProperty.priceOrRent > 0.0) baseProperty.priceOrRent.toLong().toString() else ""
    val initialOwnerText = baseProperty.ownerName?.takeIf { it.isNotBlank() } ?: baseProperty.brokerName ?: ""
    val initialOwnerContactText = baseProperty.ownerContact?.takeIf { it.isNotBlank() } ?: baseProperty.brokerContact ?: ""
    val initialLinkText = baseProperty.listingLink ?: baseProperty.googleMapsUrl ?: ""

    val hasUnsavedChanges = project != baseProperty.projectName ||
        location != baseProperty.location ||
        propertyType != baseProperty.propertyType ||
        unitNumber != baseProperty.unitNumber ||
        floor != baseProperty.floor ||
        carpetArea != initialCarpetText ||
        builtUpArea != initialBuiltUpText ||
        priceSale != initialSaleText ||
        rent != initialRentText ||
        deposit != (baseProperty.deposit?.toLong()?.toString() ?: "") ||
        roiPercent != (baseProperty.roiPercent?.toString() ?: "") ||
        furnishing != baseProperty.furnishing ||
        possession != baseProperty.possession ||
        parking != baseProperty.parking ||
        workstations != (baseProperty.workstations?.toString() ?: "") ||
        cabins != (baseProperty.cabins?.toString() ?: "") ||
        bhkConfig != (baseProperty.bhk ?: "") ||
        status != baseProperty.status ||
        expiry != baseProperty.leaseExpiryDate ||
        vacancy != baseProperty.upcomingVacancyDate ||
        ownerName != initialOwnerText ||
        ownerContact != initialOwnerContactText ||
        source != (baseProperty.sourceInfo ?: "") ||
        remarks != (baseProperty.remarks ?: "") ||
        listingLink != initialLinkText ||
        photosList != baseProperty.images ||
        attachmentsList != baseProperty.attachments ||
        videoUrlState != baseProperty.videoUrl ||
        floorPlanUrlState != baseProperty.floorPlanUrl

    val isPreLeased = baseProperty.tab == InventoryTab.PRE_LEASED ||
        baseProperty.transactionType.contains("Pre", ignoreCase = true)
    val isPureRental = baseProperty.tab.isRental && !isPreLeased

    val projectLookupMap = remember(directoryProjects, masterCommercialProjects, masterLocations) {
        val locById = masterLocations.associate { it.id to it.name.trim() }
        val map = LinkedHashMap<String, Pair<String, String>>()
        directoryProjects.forEach { dp ->
            if (dp.name.isNotBlank()) map[dp.name.trim()] = dp.location.trim() to dp.developer.trim()
        }
        masterCommercialProjects.forEach { cp ->
            val name = cp.name.trim()
            if (name.isNotBlank() && !map.containsKey(name)) {
                map[name] = (locById[cp.locationId] ?: "") to ""
            }
        }
        map
    }

    val projectOptions = remember(projectLookupMap, project) {
        (projectLookupMap.keys + listOfNotNull(project.trim().takeIf { it.isNotBlank() }))
            .distinct()
            .sorted() + "+ Add New Project"
    }

    val localityOptions = remember(masterLocations, location) {
        (listOfNotNull(location.trim().takeIf { it.isNotBlank() }) +
            masterLocations.map { it.name.trim() } +
            TlcStandardOptions.puneLocations +
            TlcStandardOptions.kharadiMicroMarkets)
            .filter { it.isNotBlank() }
            .distinct()
    }

    val propertyTypeOptions = remember(baseProperty.tab, propertyType) {
        val baseTypes = if (baseProperty.tab.isCommercial) {
            TlcStandardOptions.commercialPropertyTypes
        } else {
            TlcStandardOptions.residentialPropertyTypes
        }
        (listOfNotNull(propertyType.trim().takeIf { it.isNotBlank() }) + baseTypes).distinct()
    }

    val headerStatusOptions = remember {
        listOf(
            AvailabilityStatus.AVAILABLE,
            AvailabilityStatus.ON_HOLD,
            AvailabilityStatus.UNDER_NEGOTIATION,
            AvailabilityStatus.LEASED,
            AvailabilityStatus.SOLD,
            AvailabilityStatus.WITHDRAWN,
            AvailabilityStatus.NOT_CONFIRMED,
            AvailabilityStatus.VERIFICATION_PENDING
        )
    }

    fun requestClose() {
        if (isSavingAndPushing) return
        if (hasUnsavedChanges) {
            showUnsavedConfirm = true
        } else {
            onDismiss()
        }
    }

    BackHandler { requestClose() }

    fun submitSave(overrideExpectedVersion: Int = baseProperty.recordVersion) {
        if (isSavingAndPushing) return
        errorMessage = null

        if (project.trim().isBlank()) {
            errorMessage = "Project / Building name is required."
            return
        }
        if (location.trim().isBlank()) {
            errorMessage = "Location is required."
            return
        }
        val parsedCarpet = if (carpetArea.trim().isBlank()) baseProperty.carpetAreaSqFt else carpetArea.trim().toDoubleOrNull()
        if (parsedCarpet == null || !parsedCarpet.isFinite() || parsedCarpet < 0.0) {
            errorMessage = "Enter a valid non-negative Carpet Area (sq.ft)."
            return
        }
        val parsedBuiltUp = if (builtUpArea.trim().isBlank()) null else builtUpArea.trim().toDoubleOrNull()
        if (builtUpArea.trim().isNotBlank() && (parsedBuiltUp == null || !parsedBuiltUp.isFinite() || parsedBuiltUp < 0.0)) {
            errorMessage = "Enter a valid non-negative Built-up Area (sq.ft)."
            return
        }
        val parsedRent = if (rent.trim().isBlank()) null else rent.trim().toDoubleOrNull()
        if (rent.trim().isNotBlank() && (parsedRent == null || !parsedRent.isFinite() || parsedRent < 0.0)) {
            errorMessage = "Enter a valid non-negative Monthly Rent."
            return
        }
        val parsedSale = if (priceSale.trim().isBlank()) null else priceSale.trim().toDoubleOrNull()
        if (priceSale.trim().isNotBlank() && (parsedSale == null || !parsedSale.isFinite() || parsedSale < 0.0)) {
            errorMessage = "Enter a valid non-negative Sale Price."
            return
        }
        val parsedDeposit = if (deposit.trim().isBlank()) null else deposit.trim().toDoubleOrNull()
        if (deposit.trim().isNotBlank() && (parsedDeposit == null || !parsedDeposit.isFinite() || parsedDeposit < 0.0)) {
            errorMessage = "Enter a valid non-negative Security Deposit."
            return
        }
        val parsedRoi = if (roiPercent.trim().isBlank()) null else roiPercent.trim().toDoubleOrNull()
        if (roiPercent.trim().isNotBlank() && (parsedRoi == null || !parsedRoi.isFinite() || parsedRoi < 0.0)) {
            errorMessage = "Enter a valid non-negative ROI %."
            return
        }

        val primaryPrice = if (isPureRental) {
            parsedRent ?: baseProperty.priceOrRent
        } else {
            parsedSale ?: baseProperty.priceOrRent
        }

        val trimmedLink = listingLink.trim().takeIf { it.isNotBlank() }
        val isBrokerSource = source.contains("Broker", ignoreCase = true) ||
            source.contains("Channel Partner", ignoreCase = true)
        val trimmedOwner = ownerName.trim().takeIf { it.isNotBlank() }
        val trimmedPhone = ownerContact.trim().takeIf { it.isNotBlank() }

        val updated = baseProperty.copy(
            projectName = project.trim(),
            projectId = projectIdState.ifBlank { baseProperty.projectId },
            builderName = developerState.trim().takeIf { it.isNotBlank() } ?: baseProperty.builderName,
            location = location.trim(),
            propertyType = propertyType.trim().ifBlank { baseProperty.propertyType },
            unitNumber = unitNumber.trim(),
            floor = floor.trim(),
            carpetAreaSqFt = parsedCarpet,
            builtUpAreaSqFt = parsedBuiltUp,
            priceOrRent = primaryPrice,
            priceSale = parsedSale,
            rent = parsedRent,
            deposit = parsedDeposit,
            roiPercent = parsedRoi,
            furnishing = furnishing,
            possession = possession.trim(),
            parking = parking.trim(),
            workstations = workstations.trim().toIntOrNull(),
            cabins = cabins.trim().toIntOrNull(),
            bhk = if (!baseProperty.tab.isCommercial) bhkConfig.trim().takeIf { it.isNotBlank() } else baseProperty.bhk,
            status = status,
            leaseExpiryDate = expiry.trim(),
            upcomingVacancyDate = vacancy.trim(),
            ownerName = if (!isBrokerSource) trimmedOwner else baseProperty.ownerName,
            ownerContact = if (!isBrokerSource) trimmedPhone else baseProperty.ownerContact,
            brokerName = if (isBrokerSource) trimmedOwner else baseProperty.brokerName,
            brokerContact = if (isBrokerSource) trimmedPhone else baseProperty.brokerContact,
            sourceInfo = source.trim().takeIf { it.isNotBlank() },
            remarks = remarks.trim().takeIf { it.isNotBlank() },
            listingLink = trimmedLink,
            googleMapsUrl = if (trimmedLink != null && (trimmedLink.contains("maps") || trimmedLink.contains("goo.gl"))) trimmedLink else baseProperty.googleMapsUrl,
            images = photosList,
            attachments = attachmentsList,
            videoUrl = videoUrlState,
            floorPlanUrl = floorPlanUrlState
        )

        val changedFields = buildSet {
            if (updated.projectName != baseProperty.projectName) add("projectName")
            if (updated.location != baseProperty.location) add("location")
            if (updated.propertyType != baseProperty.propertyType) add("propertyType")
            if (updated.unitNumber != baseProperty.unitNumber) add("unitNumber")
            if (updated.floor != baseProperty.floor) add("floor")
            if (updated.carpetAreaSqFt != baseProperty.carpetAreaSqFt) add("carpetAreaSqFt")
            if (updated.builtUpAreaSqFt != baseProperty.builtUpAreaSqFt) add("builtUpAreaSqFt")
            if (updated.priceOrRent != baseProperty.priceOrRent) add("priceOrRent")
            if (updated.priceSale != baseProperty.priceSale) add("priceSale")
            if (updated.rent != baseProperty.rent) add("rent")
            if (updated.deposit != baseProperty.deposit) add("deposit")
            if (updated.roiPercent != baseProperty.roiPercent) add("roiPercent")
            if (updated.furnishing != baseProperty.furnishing) add("furnishing")
            if (updated.possession != baseProperty.possession) add("possession")
            if (updated.parking != baseProperty.parking) add("parking")
            if (updated.workstations != baseProperty.workstations) add("workstations")
            if (updated.cabins != baseProperty.cabins) add("cabins")
            if (updated.bhk != baseProperty.bhk) add("bhk")
            if (updated.status != baseProperty.status) add("status")
            if (updated.leaseExpiryDate != baseProperty.leaseExpiryDate) add("leaseExpiryDate")
            if (updated.upcomingVacancyDate != baseProperty.upcomingVacancyDate) add("upcomingVacancyDate")
            if (updated.ownerName != baseProperty.ownerName) add("ownerName")
            if (updated.ownerContact != baseProperty.ownerContact) add("ownerContact")
            if (updated.brokerName != baseProperty.brokerName) add("brokerName")
            if (updated.brokerContact != baseProperty.brokerContact) add("brokerContact")
            if (updated.sourceInfo != baseProperty.sourceInfo) add("sourceInfo")
            if (updated.remarks != baseProperty.remarks) add("remarks")
            if (updated.listingLink != baseProperty.listingLink) add("listingLink")
            if (updated.images != baseProperty.images) add("images")
            if (updated.attachments != baseProperty.attachments) add("attachments")
            if (updated.videoUrl != baseProperty.videoUrl) add("videoUrl")
            if (updated.floorPlanUrl != baseProperty.floorPlanUrl) add("floorPlanUrl")
        }

        if (changedFields.isEmpty()) {
            onDismiss()
            return
        }

        if (onSaveReliable != null) {
            val outcome = onSaveReliable(overrideExpectedVersion, changedFields, updated, mutationId)
            when (outcome.status) {
                PropertyEditStatus.SUCCESS, PropertyEditStatus.NO_CHANGES -> {
                    isSavingAndPushing = true
                    coroutineScope.launch {
                        val pushRes = runCatching { effectivePushPropertyToSheet(updated) }.getOrElse { Result.failure(it) }
                        isSavingAndPushing = false
                        Toast.makeText(
                            context,
                            if (pushRes.isSuccess) "Saved & synced to Master Sheet" else "Saved locally (Sheet sync queued)",
                            Toast.LENGTH_SHORT
                        ).show()
                        onDismiss()
                    }
                }
                PropertyEditStatus.CONFLICT -> {
                    conflictLatestProperty = outcome.updatedProperty
                    errorMessage = outcome.message
                }
                PropertyEditStatus.VALIDATION_ERROR,
                PropertyEditStatus.PERMISSION_DENIED,
                PropertyEditStatus.STORAGE_ERROR,
                PropertyEditStatus.NOT_FOUND -> {
                    errorMessage = outcome.message.ifBlank { "Save failed — draft preserved." }
                }
            }
        } else {
            val changeLog = "Details updated: Carpet=$parsedCarpet, Price=$primaryPrice, Status=${status.label}"
            val success = onSave(updated, changeLog)
            if (success) {
                isSavingAndPushing = true
                coroutineScope.launch {
                    val pushRes = runCatching { effectivePushPropertyToSheet(updated) }.getOrElse { Result.failure(it) }
                    isSavingAndPushing = false
                    Toast.makeText(
                        context,
                        if (pushRes.isSuccess) "Saved & synced to Master Sheet" else "Saved locally (Sheet sync queued)",
                        Toast.LENGTH_SHORT
                    ).show()
                    onDismiss()
                }
            } else {
                errorMessage = "Save failed — draft preserved on screen. Check fields or storage and retry."
            }
        }
    }

    val statusColor = when (status) {
        AvailabilityStatus.AVAILABLE -> StatusSuccessGreen
        AvailabilityStatus.ON_HOLD,
        AvailabilityStatus.UNDER_NEGOTIATION,
        AvailabilityStatus.TOKEN_RECEIVED -> StatusWarningAmber
        AvailabilityStatus.LEASED,
        AvailabilityStatus.SOLD,
        AvailabilityStatus.WITHDRAWN,
        AvailabilityStatus.NOT_AVAILABLE,
        AvailabilityStatus.NOT_CONFIRMED -> StatusDangerRed
        AvailabilityStatus.VERIFICATION_PENDING -> EditTeal
    }

    Surface(
        color = EditSoftWhite,
        modifier = Modifier
            .fillMaxSize()
            .testTag("full_edit_property_dialog")
    ) {
        Scaffold(
            containerColor = EditSoftWhite,
            topBar = {
                Surface(
                    color = SurfaceWhite,
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = { requestClose() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("edit_close_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = EditNavy
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Edit Property • ${baseProperty.propertyCode.ifBlank { baseProperty.id }}",
                                    color = EditNavy,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${baseProperty.tab.code} • v${baseProperty.recordVersion}",
                                    color = EditTeal,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Inline Availability Status Dropdown Pill
                            Box {
                                Surface(
                                    color = statusColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .heightIn(min = 40.dp)
                                        .clickable { statusPillExpanded = true }
                                        .testTag("edit_status_pill")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = status.label,
                                            color = statusColor,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Change availability status",
                                            tint = statusColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = statusPillExpanded,
                                    onDismissRequest = { statusPillExpanded = false },
                                    modifier = Modifier.background(SurfaceWhite)
                                ) {
                                    headerStatusOptions.forEach { option ->
                                        val isCurrent = option == status
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = option.label,
                                                    color = if (isCurrent) EditTeal else EditNavy,
                                                    fontSize = 12.5.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                                )
                                            },
                                            trailingIcon = if (isCurrent) {
                                                {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = EditTeal,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            } else null,
                                            onClick = {
                                                status = option
                                                statusPillExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = EditBorder)
                    }
                }
            },
            bottomBar = {
                Surface(
                    color = SurfaceWhite,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                    ) {
                        HorizontalDivider(color = EditBorder)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { requestClose() },
                                enabled = !isSavingAndPushing,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, EditBorder),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = EditNavy),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("edit_cancel_btn")
                            ) {
                                Text("Cancel", fontWeight = FontWeight.SemiBold)
                            }

                            Button(
                                onClick = { submitSave() },
                                enabled = !isSavingAndPushing,
                                modifier = Modifier
                                    .weight(1.5f)
                                    .heightIn(min = 48.dp)
                                    .testTag("edit_save_btn"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryActionOrange,
                                    contentColor = OnPrimaryActionColor
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (isSavingAndPushing) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Saving & Syncing...", fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.CloudDone,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save Changes", fontWeight = FontWeight.Bold)
                                }
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (errorMessage != null) {
                    Surface(
                        color = Color(0xFFFEE2E2),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_error_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFB91C1C), modifier = Modifier.size(18.dp))
                            Text(
                                text = errorMessage.orEmpty(),
                                color = Color(0xFF991B1B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // ── CARD 1: PROJECT, LOCALITY & UNIT (2-COLUMN) ──
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, EditBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "PROJECT, LOCALITY & UNIT",
                            color = EditTeal,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SearchableDropdownField(
                                label = "Project / Building *",
                                selectedValue = project,
                                options = projectOptions,
                                onValueSelected = { picked ->
                                    if (picked == "+ Add New Project") {
                                        pendingNewProjectName = project
                                        showQuickAddProjectDialog = true
                                    } else {
                                        project = picked
                                        val dirMatch = directoryProjects.firstOrNull {
                                            it.name.equals(picked.trim(), ignoreCase = true)
                                        }
                                        if (dirMatch != null) {
                                            projectIdState = dirMatch.id
                                            if (dirMatch.location.isNotBlank()) location = dirMatch.location
                                            if (dirMatch.developer.isNotBlank()) developerState = dirMatch.developer
                                        } else {
                                            val lookup = projectLookupMap[picked.trim()]
                                            if (lookup != null) {
                                                if (lookup.first.isNotBlank()) location = lookup.first
                                                if (lookup.second.isNotBlank()) developerState = lookup.second
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_project_input"),
                                allowAddNew = true
                            )

                            SearchableDropdownField(
                                label = "Location *",
                                selectedValue = location,
                                options = localityOptions,
                                onValueSelected = { location = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_location_input"),
                                allowAddNew = true
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Property Type",
                                selectedValue = propertyType,
                                options = propertyTypeOptions,
                                onValueSelected = { propertyType = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )

                            CompactFormInputField(
                                label = "Unit / Shop / Office No",
                                value = unitNumber,
                                onValueChange = { unitNumber = it },
                                placeholder = "e.g. 402",
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_unit_input")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Floor",
                                selectedValue = floor,
                                options = TlcStandardOptions.floors,
                                onValueSelected = { floor = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_floor_input"),
                                allowCustom = true
                            )

                            CompactDropdownField(
                                label = "Availability Status",
                                selectedValue = status.label,
                                options = TlcStandardOptions.availabilityStatuses,
                                onValueSelected = { picked -> status = AvailabilityStatus.parse(picked) },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )
                        }
                    }
                }

                // ── CARD 2: AREA & TRANSACTION-AWARE FINANCIALS (2-COLUMN) ──
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, EditBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val txBannerLabel = when {
                            isPreLeased -> "PRE-LEASED"
                            isPureRental -> "LEASE"
                            else -> "OUTRIGHT SALE"
                        }
                        Text(
                            text = "AREA & FINANCIALS ($txBannerLabel)",
                            color = EditTeal,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactFormInputField(
                                label = "Carpet Area (sq.ft) *",
                                value = carpetArea,
                                onValueChange = { carpetArea = sanitizeEditDecimal(it) },
                                placeholder = "e.g. 1250",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_carpet_input")
                            )

                            CompactFormInputField(
                                label = "Built-up Area (sq.ft)",
                                value = builtUpArea,
                                onValueChange = { builtUpArea = sanitizeEditDecimal(it) },
                                placeholder = "Optional",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_builtup_input")
                            )
                        }

                        when {
                            isPureRental -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CompactFormInputField(
                                        label = "Monthly Rent (₹)",
                                        value = rent,
                                        onValueChange = { rent = sanitizeEditDecimal(it) },
                                        placeholder = "e.g. 85000",
                                        keyboardType = KeyboardType.Decimal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("edit_rent_input")
                                    )

                                    CompactFormInputField(
                                        label = "Security Deposit (₹)",
                                        value = deposit,
                                        onValueChange = { deposit = sanitizeEditDecimal(it) },
                                        placeholder = "e.g. 255000",
                                        keyboardType = KeyboardType.Decimal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("edit_deposit_input")
                                    )
                                }
                            }
                            isPreLeased -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CompactFormInputField(
                                        label = "Sale / Outright Price (₹)",
                                        value = priceSale,
                                        onValueChange = { priceSale = sanitizeEditDecimal(it) },
                                        placeholder = "e.g. 25000000",
                                        keyboardType = KeyboardType.Decimal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("edit_sale_input")
                                    )

                                    CompactFormInputField(
                                        label = "Monthly Rent (₹)",
                                        value = rent,
                                        onValueChange = { rent = sanitizeEditDecimal(it) },
                                        placeholder = "e.g. 145000",
                                        keyboardType = KeyboardType.Decimal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("edit_rent_input")
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CompactFormInputField(
                                        label = "Security Deposit (₹)",
                                        value = deposit,
                                        onValueChange = { deposit = sanitizeEditDecimal(it) },
                                        placeholder = "Optional",
                                        keyboardType = KeyboardType.Decimal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("edit_deposit_input")
                                    )

                                    CompactFormInputField(
                                        label = "ROI %",
                                        value = roiPercent,
                                        onValueChange = { roiPercent = sanitizeEditDecimal(it) },
                                        placeholder = "e.g. 7.2",
                                        keyboardType = KeyboardType.Decimal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("edit_roi_input")
                                    )
                                }
                            }
                            else -> {
                                CompactFormInputField(
                                    label = "Sale / Outright Price (₹)",
                                    value = priceSale,
                                    onValueChange = { priceSale = sanitizeEditDecimal(it) },
                                    placeholder = "e.g. 12500000",
                                    keyboardType = KeyboardType.Decimal,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("edit_sale_input")
                                )
                            }
                        }

                        // Live Currency (Lac/Cr) & Rate Preview Box
                        val liveCarpetVal = carpetArea.trim().toDoubleOrNull() ?: 0.0
                        val liveBuiltUpVal = builtUpArea.trim().toDoubleOrNull() ?: 0.0
                        val livePrimaryAmount = if (isPureRental) {
                            rent.trim().toDoubleOrNull() ?: 0.0
                        } else {
                            priceSale.trim().toDoubleOrNull() ?: 0.0
                        }
                        val liveLoadingPct = if (liveCarpetVal > 0.0 && liveBuiltUpVal >= liveCarpetVal && liveBuiltUpVal > 0.0) {
                            CalculationUtils.calculateLoadingPercentage(liveCarpetVal, liveBuiltUpVal)
                        } else null
                        val liveCarpetRate = if (livePrimaryAmount > 0.0 && liveCarpetVal > 0.0) {
                            livePrimaryAmount / liveCarpetVal
                        } else null
                        val liveBuiltUpRate = if (livePrimaryAmount > 0.0 && liveBuiltUpVal > 0.0) {
                            livePrimaryAmount / liveBuiltUpVal
                        } else null

                        Surface(
                            color = SurfaceMuted,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, EditBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                val shortPreview = if (livePrimaryAmount > 0.0) {
                                    formatIndianShortPreview(livePrimaryAmount) + if (isPureRental) "/mo" else ""
                                } else ""
                                Text(
                                    text = if (shortPreview.isNotBlank()) "LIVE PREVIEW • $shortPreview" else "LIVE RATE & LOADING CALCULATIONS",
                                    color = EditTeal,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "Carpet Rate: ${if (liveCarpetRate != null) "${CalculationUtils.formatRateValue(liveCarpetRate)} / sq.ft." else "Not Available"}",
                                    color = EditNavy,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Built-up Rate: ${if (liveBuiltUpRate != null) "${CalculationUtils.formatRateValue(liveBuiltUpRate)} / sq.ft." else "Not Available"}" +
                                        (if (liveLoadingPct != null) String.format(Locale.US, " • Loading: %.1f%%", liveLoadingPct) else ""),
                                    color = EditSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (baseProperty.tab.isCommercial && (isPureRental || isPreLeased)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    Build02DateField("Lease expiry", expiry) { expiry = it }
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    Build02DateField("Upcoming vacancy", vacancy) { vacancy = it }
                                }
                            }
                        }
                    }
                }

                // ── CARD 3: SPECIFICATIONS (2-COLUMN COMPACT DROPDOWNS) ──
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, EditBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "SPECIFICATIONS",
                            color = EditTeal,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Furnishing",
                                selectedValue = furnishing.label,
                                options = TlcStandardOptions.furnishingTypes,
                                onValueSelected = { picked ->
                                    furnishing = FurnishingType.entries.firstOrNull {
                                        it.label.equals(picked, ignoreCase = true)
                                    } ?: furnishing
                                },
                                modifier = Modifier.weight(1f),
                                allowCustom = false
                            )

                            CompactDropdownField(
                                label = "Possession",
                                selectedValue = possession,
                                options = TlcStandardOptions.possessionTimelines,
                                onValueSelected = { possession = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_possession_input"),
                                allowCustom = true
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Parking",
                                selectedValue = parking,
                                options = TlcStandardOptions.parkingOptions,
                                onValueSelected = { parking = it },
                                modifier = Modifier.weight(1f),
                                allowCustom = true
                            )

                            if (baseProperty.tab.isCommercial) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CompactFormInputField(
                                        label = "Seats / WS",
                                        value = workstations,
                                        onValueChange = { raw -> workstations = raw.filter { it.isDigit() } },
                                        placeholder = "0",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                    CompactFormInputField(
                                        label = "Cabins",
                                        value = cabins,
                                        onValueChange = { raw -> cabins = raw.filter { it.isDigit() } },
                                        placeholder = "0",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                CompactDropdownField(
                                    label = "Configuration (BHK)",
                                    selectedValue = bhkConfig,
                                    options = TlcStandardOptions.bhkConfigs,
                                    onValueSelected = { bhkConfig = it },
                                    modifier = Modifier.weight(1f),
                                    allowCustom = true
                                )
                            }
                        }
                    }
                }

                // ── CARD 4: GOOGLE DRIVE MEDIA MANAGER ──
                PropertyMediaUploadSection(
                    photosList = photosList,
                    onPhotosChange = { photosList = it },
                    attachments = attachmentsList,
                    onAttachmentsChange = { attachmentsList = it },
                    videoUrl = videoUrlState,
                    onVideoUrlChange = { videoUrlState = it },
                    floorPlanUrl = floorPlanUrlState,
                    onFloorPlanUrlChange = { floorPlanUrlState = it },
                    propertyRecordId = baseProperty.propertyCode.ifBlank { baseProperty.id },
                    onUploadMediaToDrive = effectiveUploadMediaToDrive,
                    onDriveMediaUpdated = { updatedPhotos, updatedAttachments, updatedVideo, updatedFloorPlan ->
                        val syncedProp = baseProperty.copy(
                            images = updatedPhotos,
                            attachments = updatedAttachments,
                            videoUrl = updatedVideo,
                            floorPlanUrl = updatedFloorPlan,
                            listingLink = listingLink.trim().takeIf { it.isNotBlank() }
                                ?: updatedPhotos.firstOrNull { it.startsWith("http", ignoreCase = true) }
                                ?: baseProperty.listingLink
                        )
                        withContext(Dispatchers.Main) {
                            onSave(syncedProp, "Synced media to Google Drive")
                        }
                        withContext(Dispatchers.IO) {
                            runCatching { effectivePushPropertyToSheet(syncedProp) }
                        }
                    }
                )

                // ── CARD 5: CONFIDENTIAL OWNER & SOURCE DETAILS (2-COLUMN) ──
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, EditBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "CONFIDENTIAL INTERNAL DETAILS",
                            color = EditTeal,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactFormInputField(
                                label = "Owner / Contact Person",
                                value = ownerName,
                                onValueChange = { ownerName = it },
                                placeholder = "Owner or CP Name",
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_owner_name_input")
                            )

                            CompactFormInputField(
                                label = "Contact Number",
                                value = ownerContact,
                                onValueChange = { ownerContact = it.filter { ch -> ch.isDigit() || ch == '+' }.take(13) },
                                placeholder = "10-digit mobile",
                                keyboardType = KeyboardType.Phone,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_owner_contact_input")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownField(
                                label = "Source",
                                selectedValue = source,
                                options = (listOfNotNull(source.trim().takeIf { it.isNotBlank() }) +
                                    TlcStandardOptions.listingSources).distinct(),
                                onValueSelected = { source = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_source_input"),
                                allowCustom = true
                            )

                            CompactFormInputField(
                                label = "Listing / Maps Link",
                                value = listingLink,
                                onValueChange = { listingLink = it },
                                placeholder = "https://maps.google...",
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_link_input")
                            )
                        }

                        CompactFormInputField(
                            label = "Remarks / Highlights",
                            value = remarks,
                            onValueChange = { remarks = it },
                            placeholder = "Key highlights, lease lock-in, tenant notes...",
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_remarks_input")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showQuickAddProjectDialog) {
        var newProjLoc by remember { mutableStateOf(location.ifBlank { "Kharadi" }) }
        var newProjDev by remember { mutableStateOf(developerState) }
        AlertDialog(
            onDismissRequest = { showQuickAddProjectDialog = false },
            containerColor = SurfaceWhite,
            title = {
                Text(
                    text = "Add New Project to Directory",
                    color = EditNavy,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactFormInputField(
                        label = "Project / Building Name *",
                        value = pendingNewProjectName,
                        onValueChange = { pendingNewProjectName = it },
                        placeholder = "e.g. Panchshil Business Bay"
                    )
                    CompactDropdownField(
                        label = "Locality *",
                        selectedValue = newProjLoc,
                        options = localityOptions,
                        onValueSelected = { newProjLoc = it },
                        allowCustom = true
                    )
                    CompactFormInputField(
                        label = "Developer / Builder",
                        value = newProjDev,
                        onValueChange = { newProjDev = it },
                        placeholder = "Optional"
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanedName = pendingNewProjectName.trim()
                        if (cleanedName.isNotBlank()) {
                            runCatching {
                                ProjectDirectoryStore.create(
                                    context = context,
                                    division = if (baseProperty.tab.isCommercial) "Commercial" else "Residential",
                                    name = cleanedName,
                                    location = newProjLoc.trim(),
                                    developer = newProjDev.trim()
                                )
                            }.onSuccess { created ->
                                project = created.name
                                projectIdState = created.id
                                if (created.location.isNotBlank()) location = created.location
                                if (created.developer.isNotBlank()) developerState = created.developer
                                directoryProjects = ProjectDirectoryStore.read(context)
                            }.onFailure { err ->
                                Toast.makeText(context, err.message ?: "Unable to add project", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showQuickAddProjectDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Add Project", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showQuickAddProjectDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = EditSecondary)
                }
            }
        )
    }

    if (showUnsavedConfirm) {
        AlertDialog(
            onDismissRequest = { showUnsavedConfirm = false },
            title = { Text("Discard Unsaved Edits?", color = EditNavy, fontWeight = FontWeight.Bold) },
            text = { Text("You have unsaved changes for ${baseProperty.propertyCode}. Closing now will discard them.", color = EditSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusDangerRed, contentColor = Color.White),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("edit_discard_confirm_btn")
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUnsavedConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("edit_keep_editing_btn")
                ) {
                    Text("Keep Editing", color = EditTeal)
                }
            },
            containerColor = EditSoftWhite
        )
    }

    if (conflictLatestProperty != null) {
        val latest = conflictLatestProperty!!
        AlertDialog(
            onDismissRequest = { conflictLatestProperty = null },
            title = { Text("Another Update Exists", color = EditNavy, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This property was updated elsewhere (v${latest.recordVersion} by ${latest.updatedBy.ifBlank { "Team" }}).", color = EditSecondary, fontSize = 12.sp)
                    Text("Latest Location: ${latest.location}\nLatest Price/Rent: ${latest.priceOrRent.toLong()}", color = EditNavy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        baseProperty = latest
                        conflictLatestProperty = null
                        errorMessage = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("edit_review_latest_btn")
                ) {
                    Text("Review Latest")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { conflictLatestProperty = null },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("edit_keep_draft_btn")
                ) {
                    Text("Keep Draft", color = EditNavy)
                }
            },
            containerColor = EditSoftWhite
        )
    }
}

@Composable
fun EditFieldItem(
    label: String,
    value: String,
    fieldTag: String = "edit_field_${label.lowercase().replace(Regex("[^a-z0-9]+"), "_")}",
    onValueChange: (String) -> Unit
) {
    CompactFormInputField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag(fieldTag)
    )
}
