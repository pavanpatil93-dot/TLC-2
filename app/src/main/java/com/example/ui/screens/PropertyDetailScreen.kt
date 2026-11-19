package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.crm.CrmCallManager
import com.example.crm.LastCalledByBanner
import com.example.data.FileStorageManager
import com.example.data.SheetsRepository
import com.example.data.UserSettingsManager
import com.example.inventory.CompletenessColorTier
import com.example.inventory.InventoryQuality
import com.example.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import com.example.loi.LoiDraftFactory
import com.example.ui.components.LoiBuilderBottomSheet

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PropertyDetailScreen(
    property: PropertyItem,
    allProperties: List<PropertyItem>,
    isInternalMode: Boolean,
    isFavorite: Boolean,
    isInComparison: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleCompare: () -> Unit,
    onShareWhatsApp: () -> Unit,
    onVerifyProperty: () -> Unit,
    onStatusChange: (AvailabilityStatus) -> Unit,
    onPropertySelect: (PropertyItem) -> Unit,
    onEditProperty: (() -> Unit)? = null,
    onDeleteProperty: (() -> Unit)? = null,
    onViewOnMap: ((PropertyItem) -> Unit)? = null,
    onResolveDuplicate: ((String, String, String) -> Boolean)? = null,
    onConfirmPrice: (() -> Boolean)? = null,
    onSharePropertyWhatsApp: ((PropertyItem) -> Unit)? = null,
    resolvedMapDestination: ResolvedMapDestination? = null,
    canEditLocation: Boolean = isInternalMode && onEditProperty != null,
    onViewMapExternal: ((PropertyItem) -> Unit)? = null,
    onDirectionsExternal: ((PropertyItem) -> Unit)? = null,
    onCopyLocation: ((PropertyItem, (String) -> Unit) -> Unit)? = null,
    sheetsRepository: SheetsRepository? = null,
    onLinkOrViewTenant: ((PropertyItem) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activeRepository = remember(context, sheetsRepository) {
        sheetsRepository ?: SheetsRepository(context.applicationContext, startForegroundSync = false)
    }
    val userSettingsManager = remember(context) { UserSettingsManager(context) }
    val userProfile by userSettingsManager.userProfile.collectAsState()
    val revealConfidentialExecToggle by userSettingsManager.revealConfidentialPhonesForExec.collectAsState()
    var localRevealPhone by remember(property.id) { mutableStateOf(false) }

    // ── PERSISTENT SCROLL & PAGER STATE ──
    val listState = rememberSaveable(property.id, saver = LazyListState.Saver) { LazyListState() }
    var selectedImageIndex by rememberSaveable(property.id) { mutableStateOf(0) }
    var showFullScreenGallery by rememberSaveable(property.id) { mutableStateOf(false) }
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var showVerifyConfirmationDialog by remember { mutableStateOf(false) }
    var showCompletenessDialog by remember { mutableStateOf(false) }
    var showDetailedVerificationLogSheet by remember { mutableStateOf(false) }
    var showFetchPhotoPicker by remember { mutableStateOf(false) }
    var isSearchingOnlinePhotos by remember { mutableStateOf(false) }
    var onlinePhotoError by remember { mutableStateOf<String?>(null) }
    var onlinePhotoCandidates by remember { mutableStateOf<List<OnlineProjectPhotoCandidate>>(emptyList()) }
    var selectedOnlineCandidate by remember { mutableStateOf<OnlineProjectPhotoCandidate?>(null) }
    var detailActionsOpen by remember { mutableStateOf(false) }
    var showFbMarketplaceSheet by remember { mutableStateOf(false) }
    var showStatusFlyerSheet by remember { mutableStateOf(false) }
    var showLoiBuilderSheet by remember { mutableStateOf(false) }

    var liveImages by remember(property.id, property.images) { mutableStateOf(property.images) }
    var liveAttachments by remember(property.id, property.attachments) { mutableStateOf(property.attachments) }
    var isUploadingToDrive by remember { mutableStateOf(false) }
    var driveUploadStatusText by remember { mutableStateOf("") }

    val defaultSearchQuery = remember(property) {
        val cleanLoc = property.location.substringBefore(",").trim()
        if (cleanLoc.isNotBlank()) "${property.projectName.trim()} $cleanLoc Pune" else "${property.projectName.trim()} Pune"
    }
    var onlineSearchQuery by remember(property) { mutableStateOf(defaultSearchQuery) }

    val launchOnlinePhotoSearch: () -> Unit = {
        showFetchPhotoPicker = true
        isSearchingOnlinePhotos = true
        onlinePhotoError = null
        selectedOnlineCandidate = null
        val userCustomQuery = onlineSearchQuery.trim().takeIf {
            it.isNotBlank() && !it.equals(defaultSearchQuery.trim(), ignoreCase = true)
        }
        coroutineScope.launch {
            val res = activeRepository.searchOnlineProjectPhotos(
                projectName = property.projectName,
                location = property.location,
                customQuery = userCustomQuery,
                latitude = property.latitude,
                longitude = property.longitude,
                googleMapsUrl = property.googleMapsUrl
            )
            isSearchingOnlinePhotos = false
            res.onSuccess { list ->
                onlinePhotoCandidates = list
                if (list.isEmpty()) {
                    onlinePhotoError = "No photos found online for '$onlineSearchQuery'. Try tweaking keywords or use Google Images below."
                } else {
                    selectedOnlineCandidate = list.firstOrNull()
                }
            }.onFailure { err ->
                onlinePhotoCandidates = emptyList()
                onlinePhotoError = err.localizedMessage ?: "Online photo search failed."
            }
        }
    }

    val detailPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                isUploadingToDrive = true
                driveUploadStatusText = "Uploading to Google Drive..."
                try {
                    val propCodeOrId = property.propertyCode.ifBlank { property.id }
                    val uploadedUrls = mutableListOf<String>()
                    uris.forEachIndexed { idx, uri ->
                        driveUploadStatusText = "Uploading to Google Drive (${idx + 1}/${uris.size})..."
                        val localPath = withContext(Dispatchers.IO) {
                            FileStorageManager.savePhotoFromUri(context, uri)
                        }
                        val resolvedUrl = withContext(Dispatchers.IO) {
                            val file = File(localPath.removePrefix("file://"))
                            if (file.exists() && file.length() > 0L) {
                                activeRepository.uploadMediaToDrive(
                                    propertyId = propCodeOrId,
                                    fileName = "photo_${System.currentTimeMillis()}_${idx + 1}.jpg",
                                    mimeType = "image/jpeg",
                                    bytes = file.readBytes()
                                ).getOrDefault(localPath)
                            } else {
                                localPath
                            }
                        }
                        uploadedUrls.add(resolvedUrl)
                    }
                    val updatedPhotos = (liveImages + uploadedUrls).distinct()
                    liveImages = updatedPhotos
                    val updatedProp = property.copy(
                        images = updatedPhotos,
                        attachments = liveAttachments,
                        listingLink = property.listingLink ?: updatedPhotos.firstOrNull { it.startsWith("http", ignoreCase = true) }
                    )
                    activeRepository.updateProperty(updatedProp, "Uploaded photos to Google Drive")
                    withContext(Dispatchers.IO) {
                        activeRepository.pushPropertyToMasterSheet(updatedProp)
                    }
                    Toast.makeText(context, "Uploaded ${uploadedUrls.size} photo(s) to Google Drive & synced!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Saved locally; Google Drive sync queued.", Toast.LENGTH_SHORT).show()
                } finally {
                    isUploadingToDrive = false
                    driveUploadStatusText = ""
                }
            }
        }
    }

    val detailDocumentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isUploadingToDrive = true
                driveUploadStatusText = "Uploading to Google Drive..."
                try {
                    val propCodeOrId = property.propertyCode.ifBlank { property.id }
                    val (fileName, _) = FileStorageManager.getFileNameAndSize(context, uri)
                    val (savedPath, savedSize) = withContext(Dispatchers.IO) {
                        FileStorageManager.saveDocumentFromUri(context, uri, fileName)
                    }
                    val mime = context.contentResolver.getType(uri) ?: "application/pdf"
                    val resolvedUrl = withContext(Dispatchers.IO) {
                        val file = File(savedPath.removePrefix("file://"))
                        if (file.exists() && file.length() > 0L) {
                            activeRepository.uploadMediaToDrive(
                                propertyId = propCodeOrId,
                                fileName = fileName,
                                mimeType = mime,
                                bytes = file.readBytes()
                            ).getOrDefault(savedPath)
                        } else {
                            savedPath
                        }
                    }
                    val newAtt = PropertyAttachment(
                        id = UUID.randomUUID().toString(),
                        name = fileName,
                        type = AttachmentType.PDF_BROCHURE,
                        localUriOrPath = resolvedUrl,
                        fileSizeBytes = savedSize,
                        mimeType = mime
                    )
                    val updatedAttachments = liveAttachments + newAtt
                    liveAttachments = updatedAttachments
                    val updatedProp = property.copy(
                        images = liveImages,
                        attachments = updatedAttachments
                    )
                    activeRepository.updateProperty(updatedProp, "Uploaded PDF brochure to Google Drive")
                    withContext(Dispatchers.IO) {
                        activeRepository.pushPropertyToMasterSheet(updatedProp)
                    }
                    Toast.makeText(context, "Uploaded $fileName to Google Drive & synced!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Saved locally; Google Drive sync queued.", Toast.LENGTH_SHORT).show()
                } finally {
                    isUploadingToDrive = false
                    driveUploadStatusText = ""
                }
            }
        }
    }

    val photos = remember(property.id, liveImages) {
        liveImages.filter { it.isNotBlank() }
    }

    val pagerState = rememberPagerState(initialPage = selectedImageIndex) { photos.size }

    // Keep pagerState and selectedImageIndex synchronized
    LaunchedEffect(selectedImageIndex) {
        if (photos.isNotEmpty() && pagerState.currentPage != selectedImageIndex && selectedImageIndex in photos.indices) {
            runCatching { pagerState.scrollToPage(selectedImageIndex) }
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage in photos.indices) {
            selectedImageIndex = pagerState.currentPage
        }
    }

    // 3-second auto slideshow
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress, showFullScreenGallery, photos.size) {
        if (photos.size > 1 && !showFullScreenGallery && !pagerState.isScrollInProgress) {
            delay(3000)
            if (!pagerState.isScrollInProgress && !showFullScreenGallery && photos.size > 1) {
                val nextPage = (pagerState.currentPage + 1) % photos.size
                runCatching { pagerState.animateScrollToPage(nextPage) }
            }
        }
    }

    val completenessReport = remember(property.id) { InventoryQuality.completenessReport(property) }
    val completionColor = when (completenessReport.colorTier) {
        CompletenessColorTier.GREEN -> Color(0xFF0E9F6E)
        CompletenessColorTier.AMBER -> Color(0xFFD97706)
        CompletenessColorTier.RED -> Color(0xFFDC2626)
    }

    val isAuthorized = remember(isInternalMode, userProfile) {
        isInternalMode || userProfile.role.canPerformSensitiveAction("VIEW_CONFIDENTIAL")
    }

    val callPropertyContact: () -> Unit = {
        val number = property.ownerContact?.takeIf { it.isNotBlank() } ?: property.brokerContact.orEmpty()
        if (number.isNotBlank()) {
            runCatching {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                context.startActivity(intent)
            }.onFailure {
                Toast.makeText(context, "Unable to launch dialer", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "No contact number recorded for this property", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            // ── TOP TOOLBAR (Executive Energy Surface) ──
            Surface(
                color = SurfaceWhite,
                shadowElevation = 4.dp,
                border = BorderStroke(0.5.dp, BorderGrey),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                        Text(
                            text = "Property Details",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onEditProperty != null) {
                            IconButton(
                                onClick = onEditProperty,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("detail_top_edit_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Property",
                                    tint = PrimaryActionOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("detail_top_bookmark_btn")
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (isFavorite) "Saved" else "Save Property",
                                tint = if (isFavorite) PrimaryActionOrange else TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Box {
                            IconButton(onClick = { detailActionsOpen = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Actions", tint = TextPrimary, modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(
                                expanded = detailActionsOpen,
                                onDismissRequest = { detailActionsOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Detailed Verification Log") },
                                    onClick = {
                                        detailActionsOpen = false
                                        showDetailedVerificationLogSheet = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.History, contentDescription = null, tint = PrimaryActionOrange) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Verify Availability") },
                                    onClick = {
                                        detailActionsOpen = false
                                        showVerifyConfirmationDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Verified, contentDescription = null, tint = PrimaryActionOrange) }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isInComparison) "Remove from Comparison" else "Add to Compare") },
                                    onClick = {
                                        detailActionsOpen = false
                                        onToggleCompare()
                                    },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, tint = PrimaryActionOrange) }
                                )
                                if (isInternalMode && userProfile.isAdminOrSuperAdmin && onDeleteProperty != null) {
                                    HorizontalDivider(color = BorderSubtle)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Delete Permanently",
                                                color = StatusDangerRed,
                                                fontWeight = FontWeight.Bold
                                            )
                                        },
                                        onClick = {
                                            detailActionsOpen = false
                                            showDeleteConfirmationDialog = true
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.DeleteForever,
                                                contentDescription = null,
                                                tint = StatusDangerRed
                                            )
                                        },
                                        modifier = Modifier.testTag("property_detail_delete_permanently_menu_item")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            // ── STICKY 3-BUTTON BOTTOM ACTION BAR (pinned above system navigation insets) ──
            Surface(
                color = SurfaceWhite,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, BorderGrey),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Call Button (48dp)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StatusSuccessGreen.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StatusSuccessGreen.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(onClick = callPropertyContact)
                            .testTag("detail_bottom_call_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Call Contact",
                                tint = StatusSuccessGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 2. WhatsApp Button (48dp)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StatusSuccessGreen.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StatusSuccessGreen.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(onClick = onShareWhatsApp)
                            .testTag("detail_bottom_whatsapp_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Chat,
                                contentDescription = "WhatsApp Share",
                                tint = StatusSuccessGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 3. Primary Share / PDF Proposal Button
                    Button(
                        onClick = onShareWhatsApp,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("detail_share_whatsapp_btn"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        )
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share / PDF Proposal",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        containerColor = BackgroundSoft
    ) { paddingValues ->
        // ── CONTINUOUS VERTICAL SCROLLING PAGE (Exact 6-Section Order) ──
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .testTag("property_detail_lazy_column"),
            contentPadding = PaddingValues(top = if (photos.isEmpty()) 12.dp else 0.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── SECTION 1 — SMART PHOTO GALLERY (Only rendered when property has photos) ──
            if (photos.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(CanvasObsidianDark)
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { showFullScreenGallery = true }
                        ) { pageIndex ->
                            AsyncImage(
                                model = photos[pageIndex],
                                contentDescription = "${property.projectName} photo ${pageIndex + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        // Counter Badge (bottomEnd)
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "${selectedImageIndex + 1} / ${photos.size}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // Dot Indicators (bottomCenter)
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val maxDots = minOf(photos.size, 7)
                            for (idx in 0 until maxDots) {
                                Box(
                                    modifier = Modifier
                                        .size(if (idx == selectedImageIndex) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(if (idx == selectedImageIndex) Color.White else Color.White.copy(alpha = 0.5f))
                                )
                            }
                        }
                    }
                }
            }

            // ── SECTION 2 — PROPERTY SUMMARY CARD ──
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    elevation = CardDefaults.cardElevation(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = SelectedSurfaceContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = property.propertyCode.ifBlank { property.id },
                                    color = PrimaryActionOrange,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }

                            Surface(
                                color = completionColor,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { showCompletenessDialog = true }
                            ) {
                                Text(
                                    text = "${completenessReport.percentage}% Complete",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        val txLabel = if (property.isLeaseTab()) "for Lease" else "for Outright Sale"
                        val propType = property.propertyType.ifBlank { "Commercial / Residential Unit" }

                        Text(
                            text = "$propType $txLabel",
                            color = PrimaryActionOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = property.projectName.ifBlank { "Project Building" },
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                            Text(
                                text = buildList {
                                    if (property.unitNumber.isNotBlank()) add("Unit ${property.unitNumber}")
                                    if (property.location.isNotBlank()) add(property.location)
                                }.joinToString(", "),
                                color = SecondaryText,
                                fontSize = 13.sp
                            )
                        }

                        // Batch 20: Team "Last Called By" History Banner
                        val lastPropertyCall = remember(property.id, property.propertyCode, property.ownerContact, property.brokerContact) {
                            CrmCallManager.findLastCallForTarget(
                                context = context,
                                phones = listOfNotNull(property.ownerContact, property.brokerContact),
                                entityIds = listOf(property.id, property.propertyCode)
                            )
                        }
                        if (lastPropertyCall != null) {
                            LastCalledByBanner(
                                lastCall = lastPropertyCall,
                                testTag = "property_last_called_by_banner"
                            )
                        }

                        HorizontalDivider(color = BorderGrey)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Carpet Area", fontSize = 11.sp, color = SecondaryText)
                                Text("${CalculationUtils.formatNumber(property.carpetAreaSqFt)} sq.ft.", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                            }
                            if (property.builtUpAreaSqFt != null && property.builtUpAreaSqFt > 0.0) {
                                Column {
                                    Text("Built-up Area", fontSize = 11.sp, color = SecondaryText)
                                    Text("${CalculationUtils.formatNumber(property.builtUpAreaSqFt)} sq.ft.", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Status", fontSize = 11.sp, color = SecondaryText)
                                Text(property.status.label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PrimaryActionOrange)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showDetailedVerificationLogSheet = true },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp)
                                    .testTag("detail_verification_log_btn")
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Verification Log",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isAuthorized) {
                                Button(
                                    onClick = launchOnlinePhotoSearch,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryActionOrange.copy(alpha = 0.14f),
                                        contentColor = PrimaryActionOrange
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                        .testTag("detail_fetch_project_photo_btn")
                                ) {
                                    Icon(Icons.Default.ImageSearch, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Fetch Project Photo",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Marketing Collateral Row: FB Marketplace Ad + Luxury Navy & Gold Status Flyer
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showFbMarketplaceSheet = true },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, FocusOutlineColor.copy(alpha = 0.65f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("detail_fb_ad_btn")
                            ) {
                                Icon(Icons.Default.Campaign, contentDescription = null, tint = FocusOutlineColor, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "FB Marketplace Ad",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Button(
                                onClick = { showStatusFlyerSheet = true },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF0B1B2B),
                                    contentColor = Color(0xFFD4AF37)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFD4AF37)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("detail_status_flyer_btn")
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFD4AF37), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Status Flyer",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        val isLeasedOrPreLeased = property.status == AvailabilityStatus.LEASED ||
                            property.tab == InventoryTab.PRE_LEASED ||
                            property.transactionType.contains("Pre-Leased", ignoreCase = true) ||
                            !property.tenantName.isNullOrBlank()
                        if (isAuthorized && isLeasedOrPreLeased && onLinkOrViewTenant != null) {
                            OutlinedButton(
                                onClick = { onLinkOrViewTenant(property) },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("detail_link_view_tenant_btn")
                            ) {
                                Icon(Icons.Default.VpnKey, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "+ Link / View Tenant",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryActionOrange
                                )
                            }
                        }

                        val isLeaseOrNegotiationForLoi = remember(property, isAuthorized) {
                            isAuthorized && (
                                property.tab.isRental ||
                                    property.transactionType.contains("Lease", ignoreCase = true) ||
                                    property.transactionType.contains("Rent", ignoreCase = true) ||
                                    property.status == AvailabilityStatus.UNDER_NEGOTIATION ||
                                    property.hasDualSaleAndLeaseOffer ||
                                    (property.rent ?: 0.0) > 0.0
                                )
                        }
                        if (isLeaseOrNegotiationForLoi) {
                            Button(
                                onClick = { showLoiBuilderSheet = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryActionOrange,
                                    contentColor = OnPrimaryActionColor
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("detail_generate_loi_pdf_btn")
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Generate LOI PDF",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Linked Physical Unit Switcher (retains separate sale/lease/source listings without auto-merging)
                        val linkedUnitListings = remember(property.id, allProperties) {
                            val key = property.sameUnitGroupId.takeIf { it.isNotBlank() }
                                ?: property.physicalUnitKey.takeIf { it.isNotBlank() }
                            if (key.isNullOrBlank()) {
                                emptyList()
                            } else {
                                allProperties.filter { other ->
                                    other.id != property.id && (
                                        other.sameUnitGroupId == key ||
                                            other.physicalUnitKey == key
                                        )
                                }
                            }
                        }
                        if (linkedUnitListings.isNotEmpty()) {
                            HorizontalDivider(color = BorderGrey)
                            Text(
                                text = "Linked Listings for This Physical Unit (${linkedUnitListings.size + 1})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                linkedUnitListings.forEach { linked ->
                                    val linkedTx = StandardTransactionType.fromString(linked.transactionType, linked.tab)
                                    AssistChip(
                                        onClick = { onPropertySelect(linked) },
                                        label = {
                                            Text(
                                                "${linked.propertyCode.ifBlank { linked.id }} • ${linkedTx.label}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── SECTION 3 — SEPARATE SALE & LEASE OFFER CARDS (Dual Offer Support) ──
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("3. Offer Details & Financials", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)

                    val saleVal = CalculationUtils.effectiveSaleAmount(property)
                    val showSaleCard = saleVal > 0.0 || property.hasDualSaleAndLeaseOffer
                    val showLeaseCard = (property.rent != null && property.rent > 0.0) || property.hasDualSaleAndLeaseOffer

                    if (showSaleCard) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGrey)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Sale Offer", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                    Surface(color = StatusSuccessGreen.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                                        Text(
                                            text = property.saleOfferStatus?.label ?: property.status.label,
                                            fontSize = 11.sp,
                                            color = StatusSuccessGreen,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                val formattedSale = if (saleVal > 0.0) {
                                    CalculationUtils.formatIndianCurrency(saleVal, isMonthly = false)
                                } else {
                                    "Price Pending"
                                }
                                Text(
                                    text = "Price / Value: $formattedSale",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryActionOrange
                                )
                                if (property.carpetAreaSqFt > 0.0 && saleVal > 0.0) {
                                    val rate = saleVal / property.carpetAreaSqFt
                                    Text(
                                        text = "Rate: ${CalculationUtils.formatRateValue(rate)} / sq.ft. (Carpet)",
                                        fontSize = 12.sp,
                                        color = SecondaryText
                                    )
                                }
                            }
                        }
                    }

                    if (showLeaseCard) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGrey)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Lease Offer", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                    Surface(color = StatusInfoBlue.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                                        Text(
                                            text = property.leaseOfferStatus?.label ?: property.status.label,
                                            fontSize = 11.sp,
                                            color = StatusInfoBlue,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                val rentVal = property.rent ?: 0.0
                                val formattedRent = if (rentVal > 0.0) {
                                    CalculationUtils.formatIndianCurrency(rentVal, isMonthly = true)
                                } else {
                                    "Rent Pending"
                                }
                                Text(
                                    text = "Monthly Rent: $formattedRent",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryActionOrange
                                )
                                val depositVal = property.deposit ?: 0.0
                                if (depositVal > 0.0) {
                                    Text(
                                        text = "Deposit: ${CalculationUtils.formatIndianCurrency(depositVal, isMonthly = false)}",
                                        fontSize = 13.sp,
                                        color = TextPrimary
                                    )
                                }
                                if (!property.escalation.isNullOrBlank() || !property.lockInPeriod.isNullOrBlank()) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        if (!property.escalation.isNullOrBlank()) Text("Escalation: ${property.escalation}", fontSize = 12.sp, color = SecondaryText)
                                        if (!property.lockInPeriod.isNullOrBlank()) Text("Lock-in: ${property.lockInPeriod}", fontSize = 12.sp, color = SecondaryText)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── SECTION 4 — DIRECT 2-COLUMN SPECIFICATIONS GRID & AMENITIES ──
            item {
                val specPairs = remember(property) {
                    buildList {
                        property.furnishing.label
                            .takeIf { InventoryQuality.isMeaningfulText(it) }
                            ?.let { add("Furnishing" to it) }
                        (property.parking.takeIf { InventoryQuality.isMeaningfulText(it) }
                            ?: property.parkingCount?.takeIf { it > 0 }?.let { "$it Reserved Bays" })
                            ?.let { add("Parking" to it) }
                        property.washrooms
                            .takeIf { InventoryQuality.isMeaningfulText(it) }
                            ?.let { add("Washrooms" to it) }
                        property.pantry
                            .takeIf { InventoryQuality.isMeaningfulText(it) }
                            ?.let { add("Pantry" to it) }
                        (property.floorLevel?.takeIf { InventoryQuality.isMeaningfulText(it) }
                            ?: property.floor.takeIf { InventoryQuality.isMeaningfulText(it) })
                            ?.let { add("Flooring / Level" to it) }
                        property.facing
                            .takeIf { InventoryQuality.isMeaningfulText(it) }
                            ?.let { add("Facing" to it) }
                        property.workstations?.takeIf { it > 0 }
                            ?.let { add("Workstations" to "$it Seats") }
                        property.cabins?.takeIf { it > 0 }
                            ?.let { add("Cabins" to "$it") }
                        property.bhk?.takeIf { InventoryQuality.isMeaningfulText(it) }
                            ?.let { add("Configuration" to it) }
                        property.builderName?.takeIf { InventoryQuality.isMeaningfulText(it) }
                            ?.let { add("Developer" to it) }
                        property.possession
                            .takeIf { InventoryQuality.isMeaningfulText(it) }
                            ?.let { add("Possession" to it) }
                    }
                }

                if (specPairs.isNotEmpty() || property.amenities.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("4. Specifications & Amenities", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)

                        if (specPairs.isNotEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    specPairs.chunked(2).forEachIndexed { rowIdx, rowItems ->
                                        if (rowIdx > 0) {
                                            HorizontalDivider(color = BorderSubtle)
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            rowItems.forEach { (label, value) ->
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Text(
                                                        text = label,
                                                        fontSize = 11.sp,
                                                        color = SecondaryText,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = value,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            if (rowItems.size == 1) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (property.amenities.isNotEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Key Amenities (${property.amenities.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = TextPrimary
                                    )
                                    property.amenities.chunked(2).forEach { rowAmenities ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            rowAmenities.forEach { amen ->
                                                Row(
                                                    modifier = Modifier.weight(1f),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = PrimaryActionOrange,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Text(
                                                        text = amen,
                                                        fontSize = 12.5.sp,
                                                        color = TextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            if (rowAmenities.size == 1) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isAuthorized || liveAttachments.isNotEmpty() || !property.floorPlanUrl.isNullOrBlank() || !property.videoUrl.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGrey)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Brochures, Floor Plans & Walkthrough", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                if (isAuthorized) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                detailPhotoPicker.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            },
                                            enabled = !isUploadingToDrive,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.5f)),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f).height(40.dp)
                                        ) {
                                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("+ Drive Photo", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange, maxLines = 1)
                                        }
                                        OutlinedButton(
                                            onClick = { detailDocumentPicker.launch("application/pdf") },
                                            enabled = !isUploadingToDrive,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, FocusOutlineColor.copy(alpha = 0.5f)),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f).height(40.dp)
                                        ) {
                                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = FocusOutlineColor, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("+ Drive PDF", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = FocusOutlineColor, maxLines = 1)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = launchOnlinePhotoSearch,
                                        enabled = !isUploadingToDrive,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(40.dp)
                                    ) {
                                        Icon(Icons.Default.ImageSearch, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Fetch Project Photo Online (Applies to All Project Units)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = PrimaryActionOrange, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (isUploadingToDrive) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(SurfaceMuted)
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = PrimaryActionOrange,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = driveUploadStatusText.ifBlank { "Uploading to Google Drive..." },
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (!property.videoUrl.isNullOrBlank()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(SurfaceMuted)
                                            .clickable {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(property.videoUrl)))
                                                }.onFailure {
                                                    Toast.makeText(context, "Unable to open video link", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Default.SmartDisplay, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(18.dp))
                                            Text("Walkthrough Video", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                        }
                                        Icon(Icons.Default.OpenInNew, contentDescription = "Open Video", tint = FocusOutlineColor, modifier = Modifier.size(16.dp))
                                    }
                                }
                                liveAttachments.forEach { att ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(SurfaceMuted)
                                            .clickable {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(att.localUriOrPath)))
                                                }.onFailure {
                                                    Toast.makeText(context, "Unable to open document", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(att.type.badgeColor), modifier = Modifier.size(18.dp))
                                            Column {
                                                Text(att.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                val isDriveLink = att.localUriOrPath.startsWith("https://drive.google.com", ignoreCase = true)
                                                Text(
                                                    text = if (isDriveLink) "${att.type.displayName} • Google Drive" else att.type.displayName,
                                                    fontSize = 11.sp,
                                                    color = if (isDriveLink) StatusSuccessGreen else SecondaryText
                                                )
                                            }
                                        }
                                        Icon(Icons.Default.OpenInNew, contentDescription = "Open ${att.name}", tint = FocusOutlineColor, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── SECTION 5 — PERMISSION-GUARDED CONTACTS & OWNER/BROKER DETAILS ──
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("5. Contact & Source Details", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)

                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isAuthorized) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text("Contact Person", fontSize = 11.sp, color = SecondaryText)
                                        Text(
                                            text = property.ownerName.orEmpty().ifBlank { property.brokerName.orEmpty().ifBlank { "Confidential" } },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = TextPrimary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val resolvedPocBadge = normalizePocType(property.pocType).ifBlank {
                                            when (InventoryQuality.resolveSourceInfo(property, isAuthorized = true).type) {
                                                com.example.inventory.SourceBadgeType.CP_BROKER -> "Broker"
                                                com.example.inventory.SourceBadgeType.DEVELOPER -> "Developer Sales Team"
                                                com.example.inventory.SourceBadgeType.OWNER ->
                                                    if (!property.ownerName.isNullOrBlank()) "Property Owner" else ""
                                            }
                                        }
                                        if (resolvedPocBadge.isNotBlank()) {
                                            val badgeColor = when (resolvedPocBadge) {
                                                "Broker" -> StatusInfoBlue
                                                "Developer Sales Team" -> PrimaryActionOrange
                                                else -> StatusSuccessGreen
                                            }
                                            Surface(
                                                color = badgeColor.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(0.75.dp, badgeColor.copy(alpha = 0.4f)),
                                                modifier = Modifier.testTag("detail_poc_type_badge")
                                            ) {
                                                Text(
                                                    text = resolvedPocBadge,
                                                    color = badgeColor,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        val rawPhone = property.ownerContact.orEmpty().ifBlank { property.brokerContact.orEmpty() }
                                        val canViewFull = PhoneMaskingUtils.canViewUnmaskedConfidentialPhone(
                                            userProfile = userProfile,
                                            revealToggleOn = revealConfidentialExecToggle || localRevealPhone
                                        )
                                        val displayPhone = if (rawPhone.isBlank()) {
                                            "N/A"
                                        } else {
                                            PhoneMaskingUtils.formatPhoneForRole(rawPhone, canViewFull)
                                        }
                                        Text("Contact Phone", fontSize = 11.sp, color = SecondaryText)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = displayPhone,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = PrimaryActionOrange,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .weight(1f, fill = false)
                                                    .testTag("detail_confidential_phone_text")
                                            )
                                            if (rawPhone.isNotBlank() && !PhoneMaskingUtils.canViewUnmaskedConfidentialPhone(userProfile, false)) {
                                                IconButton(
                                                    onClick = { localRevealPhone = !localRevealPhone },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = if (canViewFull) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                        contentDescription = if (canViewFull) "Mask Phone" else "Reveal Phone",
                                                        tint = PrimaryActionOrange,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = BorderSubtle)
                            } else {
                                Text(
                                    text = "Contact details restricted. Staff permission required.",
                                    fontSize = 12.sp,
                                    color = StatusDangerRed,
                                    fontWeight = FontWeight.Medium
                                )
                                HorizontalDivider(color = BorderSubtle)
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Source", fontSize = 11.sp, color = SecondaryText)
                                Text(
                                    text = property.sourceInfo.orEmpty().ifBlank { property.ownerName.orEmpty().ifBlank { "Direct Owner" } },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // ── SECTION 6 — 60-DAY VERIFICATION HISTORY & ACTIVITY LEDGER ──
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("6. 60-Day Verification History", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)

                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Current Verification Status", fontSize = 12.sp, color = SecondaryText)
                                Text(property.verificationStatus.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = StatusSuccessGreen)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Last Verified Date", fontSize = 12.sp, color = SecondaryText)
                                Text(property.lastVerifiedDateClean.ifBlank { "Recent" }, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Audited By", fontSize = 12.sp, color = SecondaryText)
                                Text(property.verifiedBy.orEmpty().ifBlank { "Staff Reviewer" }, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                            }
                            HorizontalDivider(color = BorderGrey)
                            Text("Last 60-day status log is tracked against The Leasing Company inventory quality policy.", fontSize = 11.sp, color = SecondaryText)
                        }
                    }
                }
            }
        }
    }

    // ── FULL-SCREEN PHOTO VIEWER DIALOG ──
    if (showFullScreenGallery && photos.isNotEmpty()) {
        Dialog(
            onDismissRequest = { showFullScreenGallery = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BackHandler { showFullScreenGallery = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                var scale by remember { mutableStateOf(1f) }
                val fullScreenPagerState = rememberPagerState(initialPage = selectedImageIndex) { photos.size }

                LaunchedEffect(fullScreenPagerState.currentPage) {
                    selectedImageIndex = fullScreenPagerState.currentPage
                }

                HorizontalPager(
                    state = fullScreenPagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                            }
                        }
                ) { index ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = photos[index],
                            contentDescription = "Full screen photo ${index + 1}",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(scaleX = scale, scaleY = scale),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showFullScreenGallery = false },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${selectedImageIndex + 1} / ${photos.size}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }

    // ── DIALOGS ──
    if (showDeleteConfirmationDialog && onDeleteProperty != null) {
        val deleteDisplayTitle = listOf(
            property.projectName.ifBlank { property.propertyType },
            property.propertyCode.ifBlank { property.recordId.ifBlank { property.id } }
        ).filter { it.isNotBlank() }.distinct().joinToString(" / ")
        AlertDialog(
            onDismissRequest = { showDeleteConfirmationDialog = false },
            containerColor = SurfaceWhite,
            title = {
                Text(
                    "Delete Permanently",
                    color = StatusDangerRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Permanently delete $deleteDisplayTitle from this device and the Master Google Sheet? This cannot be undone.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmationDialog = false
                        onDeleteProperty()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusDangerRed,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("property_confirm_permanent_delete_btn")
                ) {
                    Text("Delete Permanently", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmationDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = TextPrimary)
                }
            }
        )
    }

    if (showVerifyConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showVerifyConfirmationDialog = false },
            title = { Text("Verify Availability") },
            text = { Text("Mark this property as Verified today?") },
            confirmButton = {
                Button(
                    onClick = {
                        showVerifyConfirmationDialog = false
                        onVerifyProperty()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    )
                ) {
                    Text("Confirm", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showVerifyConfirmationDialog = false }) {
                    Text("Cancel", color = TextPrimary)
                }
            }
        )
    }

    // ── DETAILED VERIFICATION LOG SHEET ──
    if (showDetailedVerificationLogSheet) {
        val ageState = remember(property) { getVerificationAgeState(property, validityDays = 60) }
        val isStrictLive = remember(property) { InventoryQuality.isStrictlyLive(property) }
        ModalBottomSheet(
            onDismissRequest = { showDetailedVerificationLogSheet = false },
            containerColor = SurfaceWhite,
            modifier = Modifier.testTag("detailed_verification_log_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Detailed Verification Log", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text(
                            text = "${property.propertyCode.ifBlank { property.id }} · ${property.projectName.ifBlank { "Unit" }}",
                            fontSize = 12.sp,
                            color = SecondaryText
                        )
                    }
                    Surface(
                        color = if (isStrictLive) StatusSuccessGreen.copy(alpha = 0.14f) else StatusWarningAmber.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isStrictLive) "Verified Live" else "Pending Review",
                            color = if (isStrictLive) StatusSuccessGreen else StatusWarningAmber,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(color = BorderGrey)

                DetailRow(label = "Availability Status", value = property.status.label)
                DetailRow(label = "Verification State", value = property.verificationStatus.label)
                DetailRow(
                    label = "60-Day Policy Status",
                    value = when (ageState) {
                        VerificationAgeState.CURRENT -> "Current (< 60 days)"
                        VerificationAgeState.DUE -> "Due for 60-Day Recheck"
                        VerificationAgeState.PENDING -> "Pending Verification"
                    }
                )
                DetailRow(label = "Last Verified Date", value = property.lastVerifiedDateClean.ifBlank { "Not yet verified" })
                DetailRow(label = "Verified By", value = property.verifiedBy.orEmpty().ifBlank { "Not recorded" })
                DetailRow(label = "Updated By", value = property.updatedBy.ifBlank { "System" })

                if (property.editHistory.isNotEmpty()) {
                    HorizontalDivider(color = BorderGrey)
                    Text("Audit & Change Trail (${property.editHistory.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                    property.editHistory.asReversed().take(20).forEach { entry ->
                        Surface(
                            color = SurfaceMuted,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.5.dp, BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(entry.summary.ifBlank { "Record updated" }, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(
                                    text = entry.timestamp,
                                    fontSize = 11.sp,
                                    color = SecondaryText
                                )
                            }
                        }
                    }
                } else {
                    HorizontalDivider(color = BorderGrey)
                    Text("No historical edits recorded yet.", fontSize = 12.sp, color = SecondaryText)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { showDetailedVerificationLogSheet = false },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Close", color = TextPrimary)
                    }
                    Button(
                        onClick = {
                            showDetailedVerificationLogSheet = false
                            showVerifyConfirmationDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        modifier = Modifier
                            .weight(1.4f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Verify Now", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ── FETCH PROJECT PHOTO PICKER MODAL ──
    if (showFetchPhotoPicker) {
        Dialog(
            onDismissRequest = { if (!isSearchingOnlinePhotos) showFetchPhotoPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                color = SurfaceWhite,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.84f)
                    .testTag("fetch_project_photo_dialog")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Fetch Project Photo", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                            Text(
                                text = "Preview & approve photo for all units in ${property.projectName.ifBlank { "this project" }}",
                                fontSize = 12.sp,
                                color = SecondaryText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { showFetchPhotoPicker = false },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                        }
                    }

                    HorizontalDivider(color = BorderGrey)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = onlineSearchQuery,
                            onValueChange = { onlineSearchQuery = it },
                            placeholder = { Text("Search query...") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = launchOnlinePhotoSearch,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.heightIn(min = 52.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Search", fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isSearchingOnlinePhotos -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(color = PrimaryActionOrange)
                                    Text("Searching online photos for ${property.projectName}...", fontSize = 13.sp, color = SecondaryText)
                                }
                            }
                            onlinePhotoError != null -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Icon(Icons.Default.ImageNotSupported, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(36.dp))
                                    Text(onlinePhotoError.orEmpty(), fontSize = 13.sp, color = TextPrimary, textAlign = TextAlign.Center)
                                    OutlinedButton(onClick = launchOnlinePhotoSearch) {
                                        Text("Retry Search", color = PrimaryActionOrange, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            else -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(onlinePhotoCandidates) { candidate ->
                                        val isSelected = selectedOnlineCandidate?.imageUrl == candidate.imageUrl
                                        Card(
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(
                                                width = if (isSelected) 2.5.dp else 1.dp,
                                                color = if (isSelected) PrimaryActionOrange else BorderGrey
                                            ),
                                            colors = CardDefaults.cardColors(containerColor = SurfaceMuted),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { selectedOnlineCandidate = candidate }
                                        ) {
                                            Column {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(118.dp)
                                                        .background(SurfaceMuted)
                                                ) {
                                                    AsyncImage(
                                                        model = candidate.thumbnailUrl,
                                                        contentDescription = candidate.title,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                    if (isSelected) {
                                                        Surface(
                                                            color = PrimaryActionOrange,
                                                            shape = CircleShape,
                                                            modifier = Modifier
                                                                .align(Alignment.TopEnd)
                                                                .padding(6.dp)
                                                                .size(24.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = "Selected",
                                                                tint = OnPrimaryActionColor,
                                                                modifier = Modifier.padding(4.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Column(
                                                    modifier = Modifier.padding(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Text(
                                                        text = candidate.title,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = TextPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = candidate.source,
                                                        fontSize = 10.sp,
                                                        color = SecondaryText,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val cleanProject = property.projectName.trim()
                            val cleanLoc = property.location.substringBefore(",").trim()
                            val browserQuery = if (onlineSearchQuery.trim().equals(defaultSearchQuery.trim(), ignoreCase = true) && cleanProject.isNotBlank()) {
                                if (cleanLoc.isNotBlank()) "\"$cleanProject\" \"$cleanLoc\" Pune" else "\"$cleanProject\" Pune"
                            } else {
                                onlineSearchQuery.trim()
                            }
                            val encodedGq = URLEncoder.encode(browserQuery, "UTF-8")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?tbm=isch&q=$encodedGq"))
                            context.startActivity(intent)
                        },
                        border = BorderStroke(1.dp, PrimaryActionOrange),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("fetch_photo_open_google_images_btn")
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Google Images", color = PrimaryActionOrange, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showFetchPhotoPicker = false },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Cancel", color = TextPrimary)
                        }
                        Button(
                            onClick = {
                                val chosen = selectedOnlineCandidate
                                if (chosen != null) {
                                    isSearchingOnlinePhotos = true
                                    coroutineScope.launch {
                                        val compressedBytes = withContext(Dispatchers.IO) {
                                            com.example.data.BingImageSearchEngine.downloadAndResizeImageBytes(chosen.imageUrl, maxMegaBytes = 1.0)
                                        }
                                        val finalUrl = if (compressedBytes != null && compressedBytes.isNotEmpty()) {
                                            val propCodeOrId = property.propertyCode.ifBlank { property.id }
                                            val uploadRes = activeRepository.uploadMediaToDrive(
                                                propertyId = propCodeOrId,
                                                fileName = "project_photo_${System.currentTimeMillis()}.jpg",
                                                mimeType = "image/jpeg",
                                                bytes = compressedBytes
                                            )
                                            uploadRes.getOrDefault(chosen.imageUrl)
                                        } else {
                                            chosen.imageUrl
                                        }

                                        isSearchingOnlinePhotos = false
                                        val count = activeRepository.applyProjectPhotoToAllMatchingUnits(
                                            projectName = property.projectName,
                                            projectId = property.projectId,
                                            photoUrl = finalUrl,
                                            targetPropertyId = property.id
                                        )
                                        liveImages = (listOf(finalUrl) + liveImages.filterNot {
                                            it.equals(finalUrl, ignoreCase = true) || it.equals(chosen.imageUrl, ignoreCase = true)
                                        }).distinct()
                                        selectedImageIndex = 0
                                        showFetchPhotoPicker = false
                                        Toast.makeText(
                                            context,
                                            "Uploaded to Drive & applied photo to ${maxOf(1, count)} unit(s) in ${property.projectName.ifBlank { "this project" }} & synced!",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            enabled = selectedOnlineCandidate != null && !isSearchingOnlinePhotos,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryActionOrange,
                                contentColor = OnPrimaryActionColor
                            ),
                            modifier = Modifier
                                .weight(1.5f)
                                .heightIn(min = 48.dp)
                                .testTag("approve_fetched_project_photo_btn")
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Approve & Apply", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    val liveEmployeeName = userProfile.username.ifBlank { "TLC Executive" }
    val liveEmployeeMobile = userProfile.mobileNumber
    val livePropertyForCollateral = remember(property, liveImages, liveAttachments) {
        property.copy(images = liveImages, attachments = liveAttachments)
    }

    if (showFbMarketplaceSheet) {
        FbMarketplaceAdModalSheet(
            property = livePropertyForCollateral,
            employeeName = liveEmployeeName,
            employeeMobile = liveEmployeeMobile,
            onDismiss = { showFbMarketplaceSheet = false }
        )
    }

    if (showStatusFlyerSheet) {
        LuxuryStatusFlyerModalSheet(
            property = livePropertyForCollateral,
            employeeName = liveEmployeeName,
            employeeMobile = liveEmployeeMobile,
            onDismiss = { showStatusFlyerSheet = false }
        )
    }

    if (showLoiBuilderSheet) {
        val crmLeadsForLoi = remember(context) {
            runCatching { readCrmLeads(context) }.getOrDefault(emptyList())
        }
        LoiBuilderBottomSheet(
            initialDraft = LoiDraftFactory.fromProperty(livePropertyForCollateral),
            availableLeads = crmLeadsForLoi,
            availableProperties = allProperties,
            onDismiss = { showLoiBuilderSheet = false }
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = SecondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value.ifBlank { "Not recorded" },
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
