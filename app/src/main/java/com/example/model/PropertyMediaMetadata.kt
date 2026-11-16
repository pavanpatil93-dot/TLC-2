package com.example.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class PropertyMediaType(val displayName: String) {
    PHOTO("Photo"),
    VIDEO("Video File"),
    VIDEO_URL("Video Link"),
    FLOOR_PLAN("Floor Plan"),
    PDF_BROCHURE("Brochure")
}

data class PropertyMediaMetadata(
    val mediaId: String = UUID.randomUUID().toString(),
    val propertyRecordId: String, // Links to stable property ID / propertyCode
    val mediaType: PropertyMediaType = PropertyMediaType.PHOTO,
    val storageReferenceOrUrl: String, // Absolute local path or HTTPS URL
    val displayOrder: Int = 0,
    val isCoverPhoto: Boolean = false,
    val uploadedDate: String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
    val fileSizeFormatted: String = ""
)

