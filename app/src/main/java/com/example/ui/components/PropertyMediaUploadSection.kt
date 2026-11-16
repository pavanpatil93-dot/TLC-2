package com.example.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.FileStorageManager
import com.example.data.PropertyMediaManager
import com.example.data.SheetsRepository
import com.example.model.AttachmentType
import com.example.model.PropertyAttachment
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val MAX_PHOTOS_PER_PROPERTY = 20
private const val MAX_DRIVE_DIRECT_UPLOAD_BYTES = 15L * 1024L * 1024L

private fun isRemoteDriveOrHttpUrl(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
}

@Composable
fun PropertyMediaUploadSection(
    photosList: List<String>,
    onPhotosChange: (List<String>) -> Unit,
    attachments: List<PropertyAttachment>,
    onAttachmentsChange: (List<PropertyAttachment>) -> Unit,
    videoUrl: String? = null,
    onVideoUrlChange: (String?) -> Unit = {},
    floorPlanUrl: String? = null,
    onFloorPlanUrlChange: (String?) -> Unit = {},
    propertyRecordId: String = "",
    onUploadMediaToDrive: (suspend (propertyId: String, fileName: String, mimeType: String, bytes: ByteArray) -> Result<String>)? = null,
    onDriveMediaUpdated: (suspend (
        updatedPhotos: List<String>,
        updatedAttachments: List<PropertyAttachment>,
        updatedVideoUrl: String?,
        updatedFloorPlanUrl: String?
    ) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fallbackRepository = remember(context) { SheetsRepository(context.applicationContext) }

    val effectiveUploadToDrive: suspend (String, String, String, ByteArray) -> Result<String> =
        remember(onUploadMediaToDrive, fallbackRepository) {
            onUploadMediaToDrive ?: { propId, fileName, mimeType, bytes ->
                fallbackRepository.uploadMediaToDrive(propId, fileName, mimeType, bytes)
            }
        }

    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0f) }
    var uploadStatusText by remember { mutableStateOf("") }
    var uploadError by remember { mutableStateOf<String?>(null) }

    var showVideoDialog by remember { mutableStateOf(false) }
    var videoInput by remember { mutableStateOf(videoUrl ?: "") }

    var photoIndexToReplace by remember { mutableStateOf<Int?>(null) }
    var tempCameraFileUri by remember { mutableStateOf<Uri?>(null) }
    var tempCameraFilePath by remember { mutableStateOf<String?>(null) }

    var pendingAttachmentType by remember { mutableStateOf(AttachmentType.DOCUMENT) }
    val effectivePropertyId = remember(propertyRecordId) {
        propertyRecordId.ifBlank { "PROP-${System.currentTimeMillis()}" }
    }

    fun syncMediaMetadata(updatedPhotos: List<String>, updatedVideo: String? = videoUrl) {
        if (propertyRecordId.isNotBlank()) {
            PropertyMediaManager.addOrUpdateMediaForProperty(
                context = context,
                propertyRecordId = propertyRecordId,
                photoPaths = updatedPhotos,
                videoUrl = updatedVideo
            )
        }
    }

    suspend fun uploadLocalFileOrFallback(
        localPath: String,
        fileName: String,
        mimeType: String
    ): Pair<String, Boolean> {
        val cleanPath = localPath.removePrefix("file://")
        val file = File(cleanPath)
        if (!file.exists() || file.length() <= 0L || file.length() > MAX_DRIVE_DIRECT_UPLOAD_BYTES) {
            return localPath to false
        }
        return withContext(Dispatchers.IO) {
            val bytes = file.readBytes()
            val firstAttempt = effectiveUploadToDrive(effectivePropertyId, fileName, mimeType, bytes)
            val finalResult = if (firstAttempt.isSuccess) {
                firstAttempt
            } else {
                delay(350)
                effectiveUploadToDrive(effectivePropertyId, fileName, mimeType, bytes)
            }
            finalResult.fold(
                onSuccess = { driveUrl -> driveUrl to true },
                onFailure = { err ->
                    withContext(Dispatchers.Main) {
                        uploadError = "Offline / Drive sync queued in Local Cache (${err.localizedMessage ?: "Network unavailable"}). Tap Retry when online."
                    }
                    localPath to false
                }
            )
        }
    }

    val pendingLocalPhotosCount = remember(photosList) {
        photosList.count { it.isNotBlank() && !isRemoteDriveOrHttpUrl(it) }
    }
    val pendingLocalAttachmentsCount = remember(attachments) {
        attachments.count { it.localUriOrPath.isNotBlank() && !isRemoteDriveOrHttpUrl(it.localUriOrPath) }
    }

    fun retryPendingDriveUploads() {
        if (isUploading) return
        coroutineScope.launch {
            isUploading = true
            uploadError = null
            uploadProgress = 0.15f
            uploadStatusText = "Uploading to Google Drive..."
            try {
                var driveUploaded = 0
                val updatedPhotos = photosList.mapIndexed { idx, path ->
                    if (path.isNotBlank() && !isRemoteDriveOrHttpUrl(path)) {
                        uploadStatusText = "Uploading to Google Drive (${idx + 1}/${photosList.size})..."
                        val (resolved, ok) = uploadLocalFileOrFallback(
                            localPath = path,
                            fileName = "photo_${System.currentTimeMillis()}_${idx + 1}.jpg",
                            mimeType = "image/jpeg"
                        )
                        if (ok) driveUploaded++
                        resolved
                    } else {
                        path
                    }
                }
                uploadProgress = 0.65f
                var updatedFloorPlan = floorPlanUrl
                var updatedVideo = videoUrl
                val updatedAttachments = attachments.mapIndexed { idx, att ->
                    if (att.localUriOrPath.isNotBlank() && !isRemoteDriveOrHttpUrl(att.localUriOrPath)) {
                        uploadStatusText = "Uploading ${att.name} to Google Drive..."
                        val (resolved, ok) = uploadLocalFileOrFallback(
                            localPath = att.localUriOrPath,
                            fileName = att.name,
                            mimeType = att.mimeType.ifBlank { "application/pdf" }
                        )
                        if (ok) {
                            driveUploaded++
                            if (att.type == AttachmentType.FLOOR_PLAN) updatedFloorPlan = resolved
                            if (att.type == AttachmentType.VIDEO) updatedVideo = resolved
                        }
                        att.copy(localUriOrPath = resolved)
                    } else {
                        att
                    }
                }
                uploadProgress = 1f
                onPhotosChange(updatedPhotos)
                onAttachmentsChange(updatedAttachments)
                if (updatedFloorPlan != floorPlanUrl) onFloorPlanUrlChange(updatedFloorPlan)
                if (updatedVideo != videoUrl) onVideoUrlChange(updatedVideo)
                syncMediaMetadata(updatedPhotos, updatedVideo)
                if (driveUploaded > 0) {
                    onDriveMediaUpdated?.invoke(updatedPhotos, updatedAttachments, updatedVideo, updatedFloorPlan)
                    Toast.makeText(context, "Synced $driveUploaded file(s) to Google Drive!", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isUploading = false
            }
        }
    }

    // Multi-photo Gallery Picker Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_PHOTOS_PER_PROPERTY)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            if (photosList.size + uris.size > MAX_PHOTOS_PER_PROPERTY) {
                Toast.makeText(context, "Maximum $MAX_PHOTOS_PER_PROPERTY photos allowed per property. Some images were trimmed.", Toast.LENGTH_LONG).show()
            }
            val allowedUris = uris.take(MAX_PHOTOS_PER_PROPERTY - photosList.size)
            if (allowedUris.isEmpty()) return@rememberLauncherForActivityResult

            coroutineScope.launch {
                isUploading = true
                uploadError = null
                uploadProgress = 0.1f
                uploadStatusText = "Uploading to Google Drive..."

                try {
                    val finalUrls = mutableListOf<String>()
                    var driveCount = 0
                    allowedUris.forEachIndexed { index, uri ->
                        uploadProgress = 0.1f + (0.35f * (index + 1) / allowedUris.size)
                        val localPath = withContext(Dispatchers.IO) {
                            FileStorageManager.savePhotoFromUri(context, uri)
                        }
                        uploadProgress = 0.45f + (0.5f * (index + 1) / allowedUris.size)
                        uploadStatusText = "Uploading to Google Drive (${index + 1}/${allowedUris.size})..."
                        val (resolvedUrl, uploadedToDrive) = uploadLocalFileOrFallback(
                            localPath = localPath,
                            fileName = "photo_${System.currentTimeMillis()}_${index + 1}.jpg",
                            mimeType = "image/jpeg"
                        )
                        if (uploadedToDrive) driveCount++
                        finalUrls.add(resolvedUrl)
                    }
                    uploadProgress = 1f
                    uploadStatusText = if (driveCount > 0) {
                        "Uploaded $driveCount photo(s) to Google Drive!"
                    } else {
                        "Queued ${finalUrls.size} photo(s) in Local Cache"
                    }
                    delay(120)
                    val newPhotoList = photosList + finalUrls
                    onPhotosChange(newPhotoList)
                    syncMediaMetadata(newPhotoList)
                    onDriveMediaUpdated?.invoke(newPhotoList, attachments, videoUrl, floorPlanUrl)
                    Toast.makeText(
                        context,
                        if (driveCount > 0) "Uploaded $driveCount photo(s) to Google Drive" else "Saved locally; queued for Google Drive sync",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    uploadError = "Upload failed: ${e.localizedMessage}"
                    Toast.makeText(context, "Error saving photos: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isUploading = false
                }
            }
        }
    }

    // Replace Specific Photo Launcher
    val replacePhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val idx = photoIndexToReplace
        if (uri != null && idx != null) {
            coroutineScope.launch {
                isUploading = true
                uploadError = null
                uploadStatusText = "Uploading to Google Drive..."
                uploadProgress = 0.4f
                try {
                    val localPath = withContext(Dispatchers.IO) {
                        FileStorageManager.savePhotoFromUri(context, uri)
                    }
                    uploadProgress = 0.75f
                    uploadStatusText = "Uploading to Google Drive..."
                    val (resolvedUrl, _) = uploadLocalFileOrFallback(
                        localPath = localPath,
                        fileName = "photo_replace_${System.currentTimeMillis()}.jpg",
                        mimeType = "image/jpeg"
                    )
                    uploadProgress = 1f
                    val mutable = photosList.toMutableList()
                    if (idx in mutable.indices) {
                        mutable[idx] = resolvedUrl
                        onPhotosChange(mutable)
                        syncMediaMetadata(mutable)
                        onDriveMediaUpdated?.invoke(mutable, attachments, videoUrl, floorPlanUrl)
                    }
                    Toast.makeText(context, "Photo #${idx + 1} updated!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    uploadError = "Failed to replace photo: ${e.localizedMessage}"
                } finally {
                    isUploading = false
                    photoIndexToReplace = null
                }
            }
        } else {
            photoIndexToReplace = null
        }
    }

    // Camera High-Res Photo Launcher
    val fullCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val rawPath = tempCameraFilePath
        if (success && rawPath != null) {
            coroutineScope.launch {
                if (photosList.size >= MAX_PHOTOS_PER_PROPERTY) {
                    Toast.makeText(context, "Maximum limit of $MAX_PHOTOS_PER_PROPERTY photos reached.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                isUploading = true
                uploadError = null
                uploadStatusText = "Uploading to Google Drive..."
                uploadProgress = 0.5f
                try {
                    val (resolvedUrl, uploadedToDrive) = uploadLocalFileOrFallback(
                        localPath = rawPath,
                        fileName = "cam_${System.currentTimeMillis()}.jpg",
                        mimeType = "image/jpeg"
                    )
                    uploadProgress = 1f
                    val newList = photosList + resolvedUrl
                    onPhotosChange(newList)
                    syncMediaMetadata(newList)
                    onDriveMediaUpdated?.invoke(newList, attachments, videoUrl, floorPlanUrl)
                    Toast.makeText(
                        context,
                        if (uploadedToDrive) "Camera photo uploaded to Google Drive!" else "Camera photo queued for Drive sync!",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    uploadError = "Error saving camera photo: ${e.localizedMessage}"
                } finally {
                    isUploading = false
                }
            }
        }
    }

    // Video File Picker Launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isUploading = true
                uploadError = null
                uploadStatusText = "Uploading to Google Drive..."
                uploadProgress = 0.35f
                try {
                    val (fileName, _) = FileStorageManager.getFileNameAndSize(context, uri)
                    val (savedPath, savedSize) = withContext(Dispatchers.IO) {
                        FileStorageManager.saveDocumentFromUri(context, uri, fileName)
                    }
                    val mime = context.contentResolver.getType(uri) ?: "video/mp4"
                    uploadProgress = 0.7f
                    uploadStatusText = "Uploading to Google Drive..."
                    val (resolvedUrl, uploadedToDrive) = uploadLocalFileOrFallback(
                        localPath = savedPath,
                        fileName = fileName,
                        mimeType = mime
                    )
                    uploadProgress = 1f
                    val videoAtt = PropertyAttachment(
                        id = UUID.randomUUID().toString(),
                        name = fileName,
                        type = AttachmentType.VIDEO,
                        localUriOrPath = resolvedUrl,
                        fileSizeBytes = savedSize,
                        mimeType = mime
                    )
                    val updatedAttachments = attachments + videoAtt
                    onAttachmentsChange(updatedAttachments)
                    onVideoUrlChange(resolvedUrl)
                    syncMediaMetadata(photosList, updatedVideo = resolvedUrl)
                    onDriveMediaUpdated?.invoke(photosList, updatedAttachments, resolvedUrl, floorPlanUrl)
                    Toast.makeText(
                        context,
                        if (uploadedToDrive) "Video uploaded to Google Drive!" else "Video queued for Drive sync!",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    uploadError = "Failed to save video: ${e.localizedMessage}"
                } finally {
                    isUploading = false
                }
            }
        }
    }

    // Document / PDF Picker Launcher
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isUploading = true
                uploadError = null
                uploadStatusText = "Uploading to Google Drive..."
                uploadProgress = 0.35f
                try {
                    val (fileName, _) = FileStorageManager.getFileNameAndSize(context, uri)
                    val (savedPath, savedSize) = withContext(Dispatchers.IO) {
                        FileStorageManager.saveDocumentFromUri(context, uri, fileName)
                    }
                    val mime = context.contentResolver.getType(uri) ?: "application/pdf"
                    uploadProgress = 0.7f
                    uploadStatusText = "Uploading to Google Drive..."
                    val (resolvedUrl, uploadedToDrive) = uploadLocalFileOrFallback(
                        localPath = savedPath,
                        fileName = fileName,
                        mimeType = mime
                    )
                    uploadProgress = 1f
                    val newAtt = PropertyAttachment(
                        id = UUID.randomUUID().toString(),
                        name = fileName,
                        type = pendingAttachmentType,
                        localUriOrPath = resolvedUrl,
                        fileSizeBytes = savedSize,
                        mimeType = mime
                    )
                    val updatedAttachments = attachments + newAtt
                    onAttachmentsChange(updatedAttachments)
                    val updatedFloorPlan = if (pendingAttachmentType == AttachmentType.FLOOR_PLAN) {
                        onFloorPlanUrlChange(resolvedUrl)
                        resolvedUrl
                    } else {
                        floorPlanUrl
                    }
                    onDriveMediaUpdated?.invoke(photosList, updatedAttachments, videoUrl, updatedFloorPlan)
                    Toast.makeText(
                        context,
                        if (uploadedToDrive) "${pendingAttachmentType.displayName} uploaded to Google Drive!" else "${pendingAttachmentType.displayName} queued for Drive sync!",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    uploadError = "Failed to save document: ${e.localizedMessage}"
                } finally {
                    isUploading = false
                }
            }
        }
    }

    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderGrey),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("property_media_upload_section")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GOOGLE DRIVE PHOTOS & BROCHURES",
                        color = FocusOutlineColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp
                    )
                    Text(
                        text = "Tap any photo to set Cover • ${photosList.size}/$MAX_PHOTOS_PER_PROPERTY photos, ${attachments.size} docs",
                        color = SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (pendingLocalPhotosCount + pendingLocalAttachmentsCount > 0 && !isUploading) {
                    TextButton(
                        onClick = { retryPendingDriveUploads() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry Drive Upload", color = PrimaryActionOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Surface(
                        color = SelectedSurfaceContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${photosList.size} Photos",
                            color = FocusOutlineColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Compact 2-Column Action Strip: Upload Photos | Take Photo
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (photosList.size >= MAX_PHOTOS_PER_PROPERTY) {
                            Toast.makeText(context, "Maximum limit of $MAX_PHOTOS_PER_PROPERTY photos reached.", Toast.LENGTH_SHORT).show()
                        } else {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("choose_photos_btn")
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("+ Upload Photos", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        if (photosList.size >= MAX_PHOTOS_PER_PROPERTY) {
                            Toast.makeText(context, "Maximum limit of $MAX_PHOTOS_PER_PROPERTY photos reached.", Toast.LENGTH_SHORT).show()
                        } else {
                            try {
                                val (file, uri) = FileStorageManager.createCameraTempFileUri(context)
                                tempCameraFilePath = file.absolutePath
                                tempCameraFileUri = uri
                                fullCameraLauncher.launch(uri)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Unable to launch camera: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = BorderStroke(1.dp, BorderGrey),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("take_photo_camera_btn")
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = FocusOutlineColor, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Camera", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }

            // Document Action Row: PDF Brochure / Floor Plan | Doc / Video
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        pendingAttachmentType = AttachmentType.PDF_BROCHURE
                        documentPickerLauncher.launch("application/pdf")
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("upload_pdf_brochure_btn")
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Brochure PDF", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = {
                        pendingAttachmentType = AttachmentType.FLOOR_PLAN
                        documentPickerLauncher.launch("*/*")
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("upload_floor_plan_btn")
                ) {
                    Icon(Icons.Default.Architecture, contentDescription = null, tint = FocusOutlineColor, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Floor Plan", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = { showVideoDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(0.9f)
                        .height(48.dp)
                        .testTag("attach_video_btn")
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Video", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

            // Upload Status & Progress Indicator
            if (isUploading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceMuted)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            uploadStatusText,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${(uploadProgress * 100).toInt()}%", color = FocusOutlineColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { uploadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = PrimaryActionOrange,
                        trackColor = BorderGrey
                    )
                }
            }

            // Error / Info banner
            if (uploadError != null && !isUploading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ErrorRed.copy(alpha = 0.08f))
                        .border(1.dp, ErrorRed.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(uploadError.orEmpty(), color = ErrorRed, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { uploadError = null }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss upload notice", tint = ErrorRed, modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Photos Horizontal Gallery with Cover Photo Badges & Actions
            if (photosList.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(photosList) { index, photoUrl ->
                        val isCover = index == 0
                        Box(
                            modifier = Modifier
                                .size(98.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    width = if (isCover) 2.dp else 1.dp,
                                    color = if (isCover) PrimaryActionOrange else BorderGrey,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .background(SurfaceMuted)
                        ) {
                            AsyncImage(
                                model = photoUrl,
                                contentDescription = "Photo ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        if (!isCover) {
                                            val mutable = photosList.toMutableList()
                                            val item = mutable.removeAt(index)
                                            mutable.add(0, item)
                                            onPhotosChange(mutable)
                                            syncMediaMetadata(mutable)
                                            Toast.makeText(context, "Set as Cover Photo!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            )

                            if (isCover) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(PrimaryActionOrange)
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text("COVER", color = OnPrimaryActionColor, fontSize = 8.sp, fontWeight = FontWeight.Black)
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black.copy(alpha = 0.72f))
                                        .clickable {
                                            val mutable = photosList.toMutableList()
                                            val item = mutable.removeAt(index)
                                            mutable.add(0, item)
                                            onPhotosChange(mutable)
                                            syncMediaMetadata(mutable)
                                            Toast.makeText(context, "Set as Cover Photo!", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text("SET COVER", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (photoUrl.startsWith("https://drive.google.com", ignoreCase = true)) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(SuccessGreen.copy(alpha = 0.88f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("DRIVE", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(Color.Black.copy(alpha = 0.72f))
                                    .clickable {
                                        val mutable = photosList.toMutableList()
                                        mutable.removeAt(index)
                                        onPhotosChange(mutable)
                                        syncMediaMetadata(mutable)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Delete Photo", tint = Color.White, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }

            // Attached Documents & Video List
            if (attachments.isNotEmpty() || !videoUrl.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!videoUrl.isNullOrBlank()) {
                        Surface(
                            color = SurfaceMuted,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.SmartDisplay, contentDescription = null, tint = FocusOutlineColor, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Walkthrough Video", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(videoUrl, color = SecondaryText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        onVideoUrlChange(null)
                                        syncMediaMetadata(photosList, updatedVideo = null)
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove Video", tint = SecondaryText, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    attachments.forEach { att ->
                        Surface(
                            color = SurfaceMuted,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = when (att.type) {
                                            AttachmentType.PDF_BROCHURE -> Icons.Default.PictureAsPdf
                                            AttachmentType.FLOOR_PLAN -> Icons.Default.Architecture
                                            AttachmentType.DOCUMENT -> Icons.Default.Description
                                            AttachmentType.VIDEO -> Icons.Default.Videocam
                                        },
                                        contentDescription = null,
                                        tint = Color(att.type.badgeColor),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = att.name,
                                            color = TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val isDrive = att.localUriOrPath.startsWith("https://drive.google.com", ignoreCase = true)
                                        Text(
                                            text = "${att.type.displayName} • ${if (isDrive) "Google Drive" else FileStorageManager.formatFileSize(att.fileSizeBytes)}",
                                            color = if (isDrive) SuccessGreen else SecondaryText,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onAttachmentsChange(attachments.filter { it.id != att.id }) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Attachment", tint = ErrorRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showVideoDialog) {
        AlertDialog(
            onDismissRequest = { showVideoDialog = false },
            containerColor = SurfaceWhite,
            title = { Text("Attach Video Walkthrough", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Upload a video file or paste a Drive/YouTube link:", color = SecondaryText, fontSize = 12.sp)

                    OutlinedButton(
                        onClick = {
                            showVideoDialog = false
                            videoPickerLauncher.launch("video/*")
                        },
                        border = BorderStroke(1.dp, FocusOutlineColor),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, tint = FocusOutlineColor, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick Video from Device", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedTextField(
                        value = videoInput,
                        onValueChange = { videoInput = it },
                        label = { Text("Or Paste Video URL", fontSize = 11.sp) },
                        placeholder = { Text("https://drive.google.com/...", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val savedVideo = videoInput.trim().ifBlank { null }
                        onVideoUrlChange(savedVideo)
                        syncMediaMetadata(photosList, updatedVideo = savedVideo)
                        showVideoDialog = false
                        Toast.makeText(context, "Video link saved!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryActionOrange, contentColor = OnPrimaryActionColor)
                ) {
                    Text("Save Link", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showVideoDialog = false }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }
}
