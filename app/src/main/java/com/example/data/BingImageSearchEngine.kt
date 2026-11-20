package com.example.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.model.OnlineProjectPhotoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

object BingImageSearchEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val TAG = "ProjectPhotoSearch"

    /**
     * Fetches building photo candidates using free exact-quoted Bing Images search
     * (zero API keys or billing required).
     */
    suspend fun searchProjectPhotos(
        projectName: String,
        location: String,
        customQuery: String? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): List<OnlineProjectPhotoCandidate> = withContext(Dispatchers.IO) {
        val cleanProject = projectName.trim()
        val cleanLoc = location.substringBefore(",").trim()

        searchBingImages(
            projectName = cleanProject,
            location = cleanLoc,
            customQuery = customQuery
        )
    }

    suspend fun searchBingImages(
        projectName: String,
        location: String,
        customQuery: String? = null
    ): List<OnlineProjectPhotoCandidate> = withContext(Dispatchers.IO) {
        val cleanProject = projectName.trim()
        val cleanLoc = location.substringBefore(",").trim()

        // Exact-quoted query with no generic "building" keyword
        val quotedDefaultQuery = if (cleanLoc.isNotBlank()) {
            "\"$cleanProject\" \"$cleanLoc\" Pune"
        } else {
            "\"$cleanProject\" Pune"
        }

        val query = customQuery?.trim()?.takeIf { it.isNotBlank() } ?: quotedDefaultQuery
        val encodedQ = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.bing.com/images/async?q=$encodedQ&first=0&count=15&adlt=moderate"

        val candidates = mutableListOf<OnlineProjectPhotoCandidate>()
        val seenUrls = mutableSetOf<String>()

        try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .build()

            val rawHtml = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) "" else response.body?.string().orEmpty()
            }

            if (rawHtml.isNotBlank()) {
                val html = rawHtml
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&#039;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")

                val murlRegex = Regex(
                    "murl\":\"(https?://[^\"]+?)\"|murl=\"(https?://[^\"]+?)\"|murl='(https?://[^']+?)'",
                    RegexOption.IGNORE_CASE
                )
                for (match in murlRegex.findAll(html)) {
                    val rawUrl = match.groupValues[1]
                        .ifBlank { match.groupValues[2].ifBlank { match.groupValues[3] } }
                        .trim()
                    if (rawUrl.isNotBlank() && isAcceptableImageUrl(rawUrl) && seenUrls.add(rawUrl)) {
                        candidates.add(
                            OnlineProjectPhotoCandidate(
                                title = cleanProject,
                                imageUrl = rawUrl,
                                thumbnailUrl = rawUrl,
                                source = "Bing Images"
                            )
                        )
                        if (candidates.size >= 12) break
                    }
                }

                if (candidates.size < 4) {
                    val fallbackRegex = Regex(
                        "imgurl\":\"(https?://[^\"]+?)\"|turl\":\"(https?://[^\"]+?)\"",
                        RegexOption.IGNORE_CASE
                    )
                    for (match in fallbackRegex.findAll(html)) {
                        val rawUrl = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
                        if (rawUrl.isNotBlank() && isAcceptableImageUrl(rawUrl) && seenUrls.add(rawUrl)) {
                            candidates.add(
                                OnlineProjectPhotoCandidate(
                                    title = cleanProject,
                                    imageUrl = rawUrl,
                                    thumbnailUrl = rawUrl,
                                    source = "Bing Images"
                                )
                            )
                            if (candidates.size >= 12) break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bing image search failed: ${e.message}")
        }

        candidates
    }

    private fun isAcceptableImageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ENGLISH)
        if (lower.contains("svg") || lower.contains("logo") || lower.contains("icon") || lower.contains("avatar")) return false
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.contains(".jpg?") || lower.contains(".jpeg?") || lower.contains(".png?") || lower.contains(".webp?")
    }

    suspend fun downloadAndResizeImageBytes(imageUrl: String, maxMegaBytes: Double = 1.0): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val bytes = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()
            } ?: return@withContext null
            if (bytes.isEmpty()) return@withContext null

            val targetBytes = (maxMegaBytes * 1024 * 1024).toLong()
            if (bytes.size <= targetBytes) return@withContext bytes

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext bytes
            var quality = 90
            var stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            var outBytes = stream.toByteArray()

            while (outBytes.size > targetBytes && quality > 30) {
                quality -= 15
                stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                outBytes = stream.toByteArray()
            }
            outBytes
        } catch (e: Exception) {
            Log.e(TAG, "Image download/resize error: ${e.message}")
            null
        }
    }
}
