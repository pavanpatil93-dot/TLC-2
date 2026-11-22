package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val version: String,
    val fileName: String,
    val downloadUrl: String
)

object AppUpdateManager {

    private const val UPDATE_APK_FILE_NAME = "tlc_update.apk"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Compares dotted semantic/numeric version strings (e.g. "1.2.0" > "1.0", "v2.1" > "1.9.5").
     */
    fun isRemoteVersionNewer(
        remoteVersion: String,
        currentVersion: String = BuildConfig.VERSION_NAME
    ): Boolean {
        val remoteParts = parseVersionParts(remoteVersion)
        val currentParts = parseVersionParts(currentVersion)
        if (remoteParts.isEmpty()) return false
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun parseVersionParts(raw: String): List<Int> {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
            .trim()
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split(".")
            .mapNotNull { part -> part.filter { it.isDigit() }.toIntOrNull() }
    }

    /**
     * Extracts `appUpdate` (`version`, `fileName`, `downloadUrl`) from a `TlcWebApp.gs` `doGet` JSON response.
     */
    fun parseAppUpdateFromJson(jsonText: String): AppUpdateInfo? {
        val trimmed = jsonText.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching {
            val root = JSONObject(trimmed)
            val dataObj = root.optJSONObject("data")
            val updateObj = root.optJSONObject("appUpdate")
                ?: dataObj?.optJSONObject("appUpdate")
                ?: return@runCatching null
            val version = updateObj.optString("version", "").trim()
            val fileName = updateObj.optString("fileName", "TLC_Update.apk").trim().ifBlank { "TLC_Update.apk" }
            val downloadUrl = updateObj.optString("downloadUrl", "").trim()
            if (version.isBlank() || downloadUrl.isBlank()) {
                null
            } else {
                AppUpdateInfo(version = version, fileName = fileName, downloadUrl = downloadUrl)
            }
        }.getOrNull()
    }

    suspend fun checkForAppUpdate(
        scriptUrl: String = TlcSchemaV2Api.BASE_WEB_APP_URL
    ): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        val endpoint = TlcSchemaV2Api.resolveEndpointUrl(scriptUrl)
        val authedUrl = TlcSchemaV2Api.buildGetUrl(endpoint, action = "bootstrap").getOrElse {
            return@withContext Result.failure(it)
        }
        runCatching {
            val request = Request.Builder()
                .url(authedUrl)
                .header("User-Agent", "TLC-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Update check failed (HTTP ${response.code})")
                }
                val body = response.body?.string().orEmpty()
                parseAppUpdateFromJson(body)
            }
        }
    }

    /**
     * Downloads the APK from Google Drive (`TLC_App_Updates`) to `cacheDir/tlc_update.apk`
     * with redirect following, virus-scan confirm handling, and 0..100 progress callbacks.
     */
    suspend fun downloadApkWithProgress(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val targetFile = File(context.cacheDir, UPDATE_APK_FILE_NAME)
            val tempFile = File(context.cacheDir, "$UPDATE_APK_FILE_NAME.part")
            if (tempFile.exists()) tempFile.delete()

            val effectiveUrl = normalizeGoogleDriveDownloadUrl(downloadUrl)
            onProgress(0)
            executeApkDownload(effectiveUrl, tempFile, onProgress, allowConfirmRetry = true)

            if (!tempFile.exists() || tempFile.length() < 1024L) {
                throw IllegalStateException("Downloaded APK file is incomplete or invalid.")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            onProgress(100)
            targetFile
        }
    }

    private fun executeApkDownload(
        url: String,
        destFile: File,
        onProgress: (Int) -> Unit,
        allowConfirmRetry: Boolean
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) TLC-Updater/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("APK download failed (HTTP ${response.code})")
            }
            val body = response.body ?: throw IllegalStateException("Empty download response.")
            val contentType = response.header("Content-Type").orEmpty().lowercase()

            // Google Drive large-file virus scan confirmation HTML fallback
            if (allowConfirmRetry && contentType.contains("text/html")) {
                val html = body.string()
                val confirmUrl = extractGoogleDriveConfirmUrl(url, html)
                    ?: throw IllegalStateException("Google Drive link requires public share access ('Anyone with link').")
                executeApkDownload(confirmUrl, destFile, onProgress, allowConfirmRetry = false)
                return
            }

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            var lastReportedPct = -1

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0L) {
                            val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct)
                            }
                        } else if (lastReportedPct < 50) {
                            val approx = ((downloadedBytes / (256 * 1024)).toInt() * 5).coerceIn(5, 90)
                            if (approx != lastReportedPct) {
                                lastReportedPct = approx
                                onProgress(approx)
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    private fun normalizeGoogleDriveDownloadUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val fileIdMatch = Regex("""drive\.google\.com/file/d/([a-zA-Z0-9_-]+)""").find(trimmed)
        if (fileIdMatch != null) {
            val id = fileIdMatch.groupValues[1]
            return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        }
        val queryIdMatch = Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(trimmed)
        if (trimmed.contains("drive.google.com", ignoreCase = true) && queryIdMatch != null) {
            val id = queryIdMatch.groupValues[1]
            return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        }
        return trimmed
    }

    private fun extractGoogleDriveConfirmUrl(originalUrl: String, html: String): String? {
        val uuidMatch = Regex("""name="uuid"\s+value="([^"]+)"""").find(html)
        val idMatch = Regex("""name="id"\s+value="([^"]+)"""").find(html)
            ?: Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(originalUrl)
        val confirmMatch = Regex("""confirm=([0-9A-Za-z_-]+)""").find(html)
        val id = idMatch?.groupValues?.getOrNull(1) ?: return null
        val confirm = confirmMatch?.groupValues?.getOrNull(1) ?: "t"
        val uuid = uuidMatch?.groupValues?.getOrNull(1)
        return buildString {
            append("https://drive.usercontent.google.com/download?id=")
            append(id)
            append("&export=download&confirm=")
            append(confirm)
            if (!uuid.isNullOrBlank()) {
                append("&uuid=")
                append(uuid)
            }
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun launchInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Checks `canRequestPackageInstalls()` and launches Android's package installer via `FileProvider`.
     * Returns `true` if installer intent was launched, or `false` if unknown-sources permission settings were opened.
     */
    fun launchApkInstall(context: Context, apkFile: File): Boolean {
        if (!canRequestPackageInstalls(context)) {
            launchInstallPermissionSettings(context)
            return false
        }
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }
}
