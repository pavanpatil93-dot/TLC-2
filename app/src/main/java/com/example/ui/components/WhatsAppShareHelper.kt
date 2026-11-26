package com.example.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.PropertyMediaManager
import com.example.model.AttachmentType
import com.example.model.PropertyItem
import com.example.model.PropertyMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Combined Property Photo + Details WhatsApp Sharing Engine.
 *
 * - Collects local & downloadable photos from PropertyMediaManager, property.images, and property.attachments.
 * - Converts local files / content URIs / remote image URLs into FileProvider content URIs.
 * - Sends 1 photo via ACTION_SEND or multiple photos via ACTION_SEND_MULTIPLE (image MIME type,
 *   EXTRA_STREAM + EXTRA_TEXT + FLAG_GRANT_READ_URI_PERMISSION) targeting com.whatsapp
 *   with fallback to com.whatsapp.w4b or Android share chooser.
 * - Automatically copies the full message to ClipboardManager with a toast backup whenever
 *   sending photo(s) with a message longer than 1,000 characters.
 */
object WhatsAppShareHelper {

    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val LONG_CAPTION_CLIPBOARD_THRESHOLD = 1000
    const val CLIPBOARD_BACKUP_TOAST = "Full details copied to clipboard as backup"
    private const val SHARE_CACHE_SUBDIR = "wa_share_photos"
    private const val MAX_PHOTOS_PER_SHARE = 10

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Collects deduplicated photo sources (local file paths, content:// URIs, or http(s):// URLs)
     * attached to the given properties in Room/local storage & metadata stores.
     */
    suspend fun collectPropertyPhotoSources(
        context: Context,
        properties: List<PropertyItem>
    ): List<String> = withContext(Dispatchers.IO) {
        val seen = LinkedHashSet<String>()
        properties.forEach { prop ->
            val recKey = prop.recordId.ifBlank { prop.id }
            if (recKey.isNotBlank()) {
                runCatching { PropertyMediaManager.loadMediaForProperty(context, recKey) }
                    .getOrDefault(emptyList())
                    .filter { it.mediaType == PropertyMediaType.PHOTO }
                    .sortedWith(compareByDescending<com.example.model.PropertyMediaMetadata> { it.isCoverPhoto }.thenBy { it.displayOrder })
                    .forEach { media ->
                        val ref = media.storageReferenceOrUrl.trim()
                        if (ref.isNotBlank() && isUsablePhotoSource(ref)) {
                            seen.add(ref)
                        }
                    }
            }
            prop.images.forEach { img ->
                val clean = img.trim()
                if (clean.isNotBlank() && isUsablePhotoSource(clean)) {
                    seen.add(clean)
                }
            }
            prop.attachments
                .filter { att ->
                    att.mimeType.startsWith("image/", ignoreCase = true) ||
                        att.localUriOrPath.endsWith(".jpg", ignoreCase = true) ||
                        att.localUriOrPath.endsWith(".jpeg", ignoreCase = true) ||
                        att.localUriOrPath.endsWith(".png", ignoreCase = true) ||
                        att.localUriOrPath.endsWith(".webp", ignoreCase = true)
                }
                .forEach { att ->
                    val clean = att.localUriOrPath.trim()
                    if (clean.isNotBlank() && isUsablePhotoSource(clean)) {
                        seen.add(clean)
                    }
                }
        }
        seen.take(MAX_PHOTOS_PER_SHARE).toList()
    }

    private fun isUsablePhotoSource(source: String): Boolean {
        if (source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true) ||
            source.startsWith("content://", ignoreCase = true)
        ) {
            return true
        }
        val file = File(source)
        return file.exists() && file.isFile && file.length() > 0L
    }

    /**
     * Prepares FileProvider content URIs for all provided photo sources.
     * Downloads remote URLs or copies external content URIs into app cacheDir so
     * FileProvider always grants valid read permissions to WhatsApp.
     */
    suspend fun prepareShareablePhotoUris(
        context: Context,
        photoSources: List<String>
    ): List<Uri> = withContext(Dispatchers.IO) {
        val authority = "${context.packageName}.fileprovider"
        val cacheDir = File(context.cacheDir, SHARE_CACHE_SUBDIR).apply { mkdirs() }
        val uris = mutableListOf<Uri>()

        for (source in photoSources.take(MAX_PHOTOS_PER_SHARE)) {
            val resolvedFile = resolveToShareableLocalFile(context, source, cacheDir) ?: continue
            runCatching {
                FileProvider.getUriForFile(context, authority, resolvedFile)
            }.getOrNull()?.let { uris.add(it) }
        }
        uris
    }

    private fun resolveToShareableLocalFile(
        context: Context,
        source: String,
        cacheDir: File
    ): File? {
        return when {
            source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> {
                downloadImageToCache(source, cacheDir)
            }
            source.startsWith("content://", ignoreCase = true) -> {
                copyContentUriToCache(context, Uri.parse(source), cacheDir)
            }
            else -> {
                val file = File(source)
                if (!file.exists() || !file.isFile || file.length() <= 0L) return null
                // If file is already inside filesDir or cacheDir, FileProvider can serve it directly
                val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                val filesRoot = runCatching { context.filesDir.canonicalPath }.getOrDefault(context.filesDir.absolutePath)
                val cacheRoot = runCatching { context.cacheDir.canonicalPath }.getOrDefault(context.cacheDir.absolutePath)
                if (canonicalPath.startsWith(filesRoot) || canonicalPath.startsWith(cacheRoot)) {
                    file
                } else {
                    val dest = File(cacheDir, "local_${sha1Short(canonicalPath)}_${file.name.takeLast(32)}")
                    runCatching {
                        file.copyTo(dest, overwrite = true)
                        dest
                    }.getOrNull()
                }
            }
        }
    }

    private fun downloadImageToCache(url: String, cacheDir: File): File? {
        val ext = when {
            url.contains(".png", ignoreCase = true) -> "png"
            url.contains(".webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        val target = File(cacheDir, "remote_${sha1Short(url)}.$ext")
        if (target.exists() && target.length() > 100L) return target

        return runCatching {
            val request = Request.Builder().url(url).get().build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val bytes = body.bytes()
                if (bytes.size < 64) return null
                FileOutputStream(target).use { it.write(bytes) }
                target
            }
        }.getOrNull()
    }

    private fun copyContentUriToCache(context: Context, uri: Uri, cacheDir: File): File? {
        val target = File(cacheDir, "uri_${sha1Short(uri.toString())}.jpg")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            if (target.exists() && target.length() > 0L) target else null
        }.getOrNull()
    }

    private fun sha1Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(14)
    }

    /**
     * Builds the Android Intent for combined Photo(s) + Text sharing.
     * - 1 photo  -> Intent.ACTION_SEND
     * - 2+ photos -> Intent.ACTION_SEND_MULTIPLE
     */
    fun buildPhotoAndTextShareIntent(
        context: Context,
        message: String,
        photoUris: List<Uri>,
        targetPackage: String? = null
    ): Intent {
        require(photoUris.isNotEmpty()) { "photoUris must not be empty" }

        val intent = if (photoUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, photoUris.first())
                putExtra(Intent.EXTRA_TEXT, message)
                clipData = ClipData.newRawUri("Property Photo", photoUris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(photoUris))
                putExtra(Intent.EXTRA_TEXT, message)
                val clip = ClipData.newRawUri("Property Photos", photoUris.first())
                photoUris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                clipData = clip
            }
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!targetPackage.isNullOrBlank()) {
            intent.setPackage(targetPackage)
        }
        return intent
    }

    /**
     * Launches WhatsApp with combined Photo(s) + Details, or Text-only when [photoUris] is empty.
     * Automatically copies full details to ClipboardManager if sending photos with a message
     * longer than 1,000 characters.
     */
    fun shareToWhatsApp(
        context: Context,
        message: String,
        photoUris: List<Uri> = emptyList(),
        recipientPhone: String? = null,
        onError: (String) -> Unit = {}
    ): Boolean {
        if (photoUris.isEmpty()) {
            WhatsAppIntentRouter.launchWhatsAppSafely(
                context = context,
                text = message,
                recipientPhone = recipientPhone,
                onError = onError
            )
            return true
        }

        // Long caption safeguard: backup full formatted message to clipboard
        if (message.length > LONG_CAPTION_CLIPBOARD_THRESHOLD) {
            copyToClipboard(context, message)
            Toast.makeText(context, CLIPBOARD_BACKUP_TOAST, Toast.LENGTH_LONG).show()
        }

        val pm = context.packageManager
        val personalIntent = buildPhotoAndTextShareIntent(context, message, photoUris, WHATSAPP_PACKAGE)
        val businessIntent = buildPhotoAndTextShareIntent(context, message, photoUris, WHATSAPP_BUSINESS_PACKAGE)
        val hasPersonal = canResolve(pm, personalIntent)
        val hasBusiness = canResolve(pm, businessIntent)

        val launchIntent: Intent = when {
            hasPersonal && hasBusiness -> {
                grantUriPermissionsForPackage(context, WHATSAPP_PACKAGE, photoUris)
                grantUriPermissionsForPackage(context, WHATSAPP_BUSINESS_PACKAGE, photoUris)
                Intent.createChooser(personalIntent, "Share Photo & Details on WhatsApp").apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(businessIntent))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            hasPersonal -> {
                grantUriPermissionsForPackage(context, WHATSAPP_PACKAGE, photoUris)
                personalIntent
            }
            hasBusiness -> {
                grantUriPermissionsForPackage(context, WHATSAPP_BUSINESS_PACKAGE, photoUris)
                businessIntent
            }
            else -> {
                val genericIntent = buildPhotoAndTextShareIntent(context, message, photoUris, null)
                Intent.createChooser(genericIntent, "Share Property Photo & Details").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        if (context !is Activity) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(launchIntent)
            true
        } catch (_: ActivityNotFoundException) {
            copyToClipboard(context, message)
            onError("WhatsApp is not installed — full details copied to clipboard")
            false
        } catch (e: Exception) {
            onError("Unable to share photo & details: ${e.localizedMessage ?: "Unknown error"}")
            false
        }
    }

    /**
     * Direct 1-tap WhatsApp chat URL for a specific 10-digit Indian mobile number
     * (`https://wa.me/91<10DigitMobile>?text=...`).
     */
    fun launchDirectBrokerWaChat(
        context: Context,
        mobileNumber: String,
        message: String,
        onError: (String) -> Unit = {}
    ): Boolean {
        val digits = mobileNumber.filter { it.isDigit() }
        val last10 = when {
            digits.length >= 10 -> digits.takeLast(10)
            else -> {
                onError("Invalid broker mobile number")
                return false
            }
        }
        val url = "https://wa.me/91$last10?text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            WhatsAppIntentRouter.launchWhatsAppSafely(
                context = context,
                text = message,
                recipientPhone = last10,
                onError = onError
            )
            true
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Property Details", text))
        }
    }

    private fun grantUriPermissionsForPackage(context: Context, pkg: String, uris: List<Uri>) {
        uris.forEach { uri ->
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun canResolve(pm: PackageManager, intent: Intent): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
    }
}
