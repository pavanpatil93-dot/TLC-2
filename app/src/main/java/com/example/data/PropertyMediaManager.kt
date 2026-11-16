package com.example.data

import android.content.Context
import android.util.Log
import com.example.model.PropertyMediaMetadata
import com.example.model.PropertyMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object PropertyMediaManager {

    private const val TAG = "PropertyMediaManager"
    private const val METADATA_FILE_NAME = "property_media_metadata.json"

    fun loadAllMediaMetadata(context: Context): List<PropertyMediaMetadata> {
        val file = File(context.filesDir, METADATA_FILE_NAME)
        if (!file.exists()) return emptyList()

        return try {
            val jsonString = file.readText()
            val array = JSONArray(jsonString)
            val list = mutableListOf<PropertyMediaMetadata>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(deserializeMedia(obj))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error loading media metadata: ${e.message}", e)
            emptyList()
        }
    }

    fun loadMediaForProperty(context: Context, propertyRecordId: String): List<PropertyMediaMetadata> {
        return loadAllMediaMetadata(context)
            .filter { it.propertyRecordId == propertyRecordId }
            .sortedBy { it.displayOrder }
    }

    fun saveMediaMetadata(context: Context, metadataList: List<PropertyMediaMetadata>): Boolean {
        return try {
            val file = File(context.filesDir, METADATA_FILE_NAME)
            val array = JSONArray()
            metadataList.forEach { item ->
                array.put(serializeMedia(item))
            }
            file.writeText(array.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving media metadata: ${e.message}", e)
            false
        }
    }

    fun addOrUpdateMediaForProperty(
        context: Context,
        propertyRecordId: String,
        photoPaths: List<String>,
        videoUrl: String? = null
    ) {
        val allMetadata = loadAllMediaMetadata(context).toMutableList()
        allMetadata.removeAll { it.propertyRecordId == propertyRecordId }

        photoPaths.forEachIndexed { index, path ->
            allMetadata.add(
                PropertyMediaMetadata(
                    mediaId = "media_${propertyRecordId}_img_$index",
                    propertyRecordId = propertyRecordId,
                    mediaType = PropertyMediaType.PHOTO,
                    storageReferenceOrUrl = path,
                    displayOrder = index,
                    isCoverPhoto = (index == 0)
                )
            )
        }

        if (!videoUrl.isNullOrBlank()) {
            allMetadata.add(
                PropertyMediaMetadata(
                    mediaId = "media_${propertyRecordId}_vid",
                    propertyRecordId = propertyRecordId,
                    mediaType = if (videoUrl.contains("http", ignoreCase = true)) PropertyMediaType.VIDEO_URL else PropertyMediaType.VIDEO,
                    storageReferenceOrUrl = videoUrl,
                    displayOrder = photoPaths.size,
                    isCoverPhoto = false
                )
            )
        }

        saveMediaMetadata(context, allMetadata)
    }

    private fun serializeMedia(item: PropertyMediaMetadata): JSONObject {
        val obj = JSONObject()
        obj.put("mediaId", item.mediaId)
        obj.put("propertyRecordId", item.propertyRecordId)
        obj.put("mediaType", item.mediaType.name)
        obj.put("storageReferenceOrUrl", item.storageReferenceOrUrl)
        obj.put("displayOrder", item.displayOrder)
        obj.put("isCoverPhoto", item.isCoverPhoto)
        obj.put("uploadedDate", item.uploadedDate)
        obj.put("fileSizeFormatted", item.fileSizeFormatted)
        return obj
    }

    private fun deserializeMedia(obj: JSONObject): PropertyMediaMetadata {
        val mediaTypeName = obj.optString("mediaType", PropertyMediaType.PHOTO.name)
        val mediaType = try { PropertyMediaType.valueOf(mediaTypeName) } catch (_: Exception) { PropertyMediaType.PHOTO }

        return PropertyMediaMetadata(
            mediaId = obj.optString("mediaId", UUID.randomUUID().toString()),
            propertyRecordId = obj.optString("propertyRecordId", ""),
            mediaType = mediaType,
            storageReferenceOrUrl = obj.optString("storageReferenceOrUrl", ""),
            displayOrder = obj.optInt("displayOrder", 0),
            isCoverPhoto = obj.optBoolean("isCoverPhoto", false),
            uploadedDate = obj.optString("uploadedDate", ""),
            fileSizeFormatted = obj.optString("fileSizeFormatted", "")
        )
    }
}

