package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.DecimalFormat
import java.util.UUID

object FileStorageManager {

    private const val PHOTOS_DIR = "property_photos"
    private const val DOCS_DIR = "property_docs"
    private const val MAX_PHOTO_DIMENSION = 2048
    private const val PHOTO_JPEG_QUALITY = 85

    fun getPhotosDirectory(context: Context): File {
        val dir = File(context.filesDir, PHOTOS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getDocsDirectory(context: Context): File {
        val dir = File(context.filesDir, DOCS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Saves and optimizes a photo from an Android Uri into permanent app storage.
     * Supports JPG, PNG, WEBP, HEIC.
     */
    fun savePhotoFromUri(context: Context, sourceUri: Uri): String {
        val photosDir = getPhotosDirectory(context)
        val targetFile = File(photosDir, "img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")

        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(sourceUri)
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, boundsOptions)
            inputStream?.close()

            // Calculate sample size for downscaling if dimensions exceed MAX_PHOTO_DIMENSION
            var sampleSize = 1
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth > MAX_PHOTO_DIMENSION || origHeight > MAX_PHOTO_DIMENSION) {
                val halfWidth = origWidth / 2
                val halfHeight = origHeight / 2
                while ((halfWidth / sampleSize) >= MAX_PHOTO_DIMENSION || (halfHeight / sampleSize) >= MAX_PHOTO_DIMENSION) {
                    sampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            inputStream = context.contentResolver.openInputStream(sourceUri)
            val bitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)

            if (bitmap != null) {
                val orientation = context.contentResolver.openInputStream(sourceUri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                    }
                }
                val oriented = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                FileOutputStream(targetFile).use { out ->
                    check(oriented.compress(Bitmap.CompressFormat.JPEG, PHOTO_JPEG_QUALITY, out)) { "Photo could not be saved" }
                    out.flush()
                }
                if (oriented !== bitmap) oriented.recycle()
                bitmap.recycle()
            } else {
                // Fallback direct copy if image couldn't be decoded via bitmap
                inputStream?.close()
                inputStream = context.contentResolver.openInputStream(sourceUri)
                inputStream?.use { input ->
                    FileOutputStream(targetFile).use { out ->
                        input.copyTo(out)
                    }
                }
            }
        } finally {
            inputStream?.close()
        }

        return targetFile.absolutePath
    }

    /**
     * Saves a camera photo bitmap directly to permanent app storage.
     */
    fun savePhotoFromBitmap(context: Context, bitmap: Bitmap): String {
        val photosDir = getPhotosDirectory(context)
        val targetFile = File(photosDir, "cam_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")

        FileOutputStream(targetFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_JPEG_QUALITY, out)
            out.flush()
        }
        return targetFile.absolutePath
    }

    /**
     * Creates a temporary file and returns a FileProvider Uri for high-resolution Camera capture.
     */
    fun createCameraTempFileUri(context: Context): Pair<File, Uri> {
        val photosDir = getPhotosDirectory(context)
        val file = File(photosDir, "cam_raw_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Pair(file, uri)
    }

    /**
     * Saves a document (PDF, floor plan, video, etc.) permanently to app internal storage.
     * Returns Pair(absolutePath, sizeInBytes).
     */
    fun saveDocumentFromUri(context: Context, sourceUri: Uri, originalName: String): Pair<String, Long> {
        val docsDir = getDocsDirectory(context)
        val safeName = originalName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val targetFile = File(docsDir, "doc_${System.currentTimeMillis()}_$safeName")

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(targetFile).use { out ->
                input.copyTo(out)
            }
        }

        return Pair(targetFile.absolutePath, targetFile.length())
    }

    /**
     * Query display name and size from content Uri
     */
    fun getFileNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = "Document"
        var size: Long = 0
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: "Document"
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {}

        if (name == "Document") {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "Attachment_${System.currentTimeMillis()}"
        }
        return Pair(name, size)
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> "${DecimalFormat("#0.0").format(mb)} MB"
            else -> "${DecimalFormat("#0").format(kb)} KB"
        }
    }

    /**
     * Resolves a local file path into a secure FileProvider Uri for viewing or sharing.
     */
    fun getShareableUri(context: Context, filePath: String): Uri {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.parse(filePath)
            }
        } catch (_: Exception) {
            Uri.parse(filePath)
        }
    }
}


