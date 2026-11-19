package com.example.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.BuildConfig
import com.example.model.OnlineProjectPhotoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
    private const val MAX_PLACES_PHOTOS = 6

    private fun resolveMapsApiKey(): String {
        val raw = runCatching { BuildConfig.MAPS_API_KEY }.getOrDefault("").trim()
        if (raw.isBlank() ||
            raw.equals("MY_MAPS_API_KEY", ignoreCase = true) ||
            raw.equals("YOUR_MAPS_API_KEY", ignoreCase = true) ||
            raw.equals("MAPS_API_KEY_DEFAULT_VALUE", ignoreCase = true)
        ) {
            return ""
        }
        return raw
    }

    /**
     * Primary source: Google Places Text Search (+ Place Details for up to 6 exact building photos).
     * Secondary fallback (only when Google Places returns 0 photos): exact-quoted Bing Images search.
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

        // 1. Primary Source: Google Places Photos API (Exact Building Match)
        val placesPhotos = searchGooglePlacesPhotos(
            projectName = cleanProject,
            location = cleanLoc,
            customQuery = customQuery,
            latitude = latitude,
            longitude = longitude
        )
        if (placesPhotos.isNotEmpty()) {
            return@withContext placesPhotos
        }

        // 2. Secondary Fallback (Only if Google Places returns 0 photos): Exact-quoted Bing Images
        searchBingImages(
            projectName = cleanProject,
            location = cleanLoc,
            customQuery = customQuery
        )
    }

    private fun searchGooglePlacesPhotos(
        projectName: String,
        location: String,
        customQuery: String?,
        latitude: Double?,
        longitude: Double?
    ): List<OnlineProjectPhotoCandidate> {
        val apiKey = resolveMapsApiKey()
        if (apiKey.isBlank()) {
            Log.d(TAG, "MAPS_API_KEY not configured; falling back to quoted Bing Images.")
            return emptyList()
        }

        val defaultPlacesQuery = buildString {
            append(projectName)
            if (location.isNotBlank()) {
                append(' ')
                append(location)
            }
            if (!projectName.contains("Pune", ignoreCase = true) &&
                !location.contains("Pune", ignoreCase = true)
            ) {
                append(" Pune")
            }
        }.trim()

        val effectiveQuery = customQuery?.trim()
            ?.replace("\"", "")
            ?.takeIf { it.isNotBlank() }
            ?: defaultPlacesQuery

        if (effectiveQuery.isBlank()) return emptyList()

        return try {
            val encodedQuery = URLEncoder.encode(effectiveQuery, "UTF-8")
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val hasValidCoords = latitude != null && longitude != null &&
                latitude.isFinite() && longitude.isFinite() &&
                (latitude != 0.0 || longitude != 0.0)
            val locationParam = if (hasValidCoords) {
                "&location=$latitude,$longitude&radius=500"
            } else {
                ""
            }

            val textSearchUrl =
                "https://maps.googleapis.com/maps/api/place/textsearch/json?query=$encodedQuery$locationParam&key=$encodedKey"

            val searchReq = Request.Builder()
                .url(textSearchUrl)
                .header("Accept", "application/json")
                .build()

            val searchJson = client.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.string().orEmpty()
            }
            if (searchJson.isBlank()) return emptyList()

            val root = JSONObject(searchJson)
            val status = root.optString("status", "")
            if (status != "OK") {
                Log.w(TAG, "Google Places Text Search status=$status for query='$effectiveQuery'")
                return emptyList()
            }

            val results = root.optJSONArray("results") ?: return emptyList()
            if (results.length() == 0) return emptyList()

            // Pick the top matching place (prefer first result, or first with photos/place_id)
            val topPlace = results.optJSONObject(0) ?: return emptyList()
            val placeName = topPlace.optString("name", "").ifBlank { projectName }
            val placeId = topPlace.optString("place_id", "").trim()

            val photoRefs = linkedSetOf<String>()

            // Extract any photo_reference IDs directly from Text Search top place
            extractPhotoReferences(topPlace.optJSONArray("photos"), photoRefs, MAX_PLACES_PHOTOS)

            // Google Places Text Search returns at most 1 photo per result; fetch Place Details
            // for the top matching place_id to collect up to 6 real photos of this exact building.
            if (placeId.isNotBlank() && photoRefs.size < MAX_PLACES_PHOTOS) {
                runCatching {
                    val encodedPlaceId = URLEncoder.encode(placeId, "UTF-8")
                    val detailsUrl =
                        "https://maps.googleapis.com/maps/api/place/details/json?place_id=$encodedPlaceId&fields=name,photos&key=$encodedKey"
                    val detailsReq = Request.Builder()
                        .url(detailsUrl)
                        .header("Accept", "application/json")
                        .build()
                    val detailsJson = client.newCall(detailsReq).execute().use { resp ->
                        if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
                    }
                    if (detailsJson.isNotBlank()) {
                        val detailsRoot = JSONObject(detailsJson)
                        if (detailsRoot.optString("status") == "OK") {
                            val detailResult = detailsRoot.optJSONObject("result")
                            extractPhotoReferences(
                                detailResult?.optJSONArray("photos"),
                                photoRefs,
                                MAX_PLACES_PHOTOS
                            )
                        }
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Google Place Details photo fetch failed: ${err.message}")
                }
            }

            if (photoRefs.isEmpty()) return emptyList()

            photoRefs.take(MAX_PLACES_PHOTOS).mapIndexed { index, ref ->
                val encodedRef = URLEncoder.encode(ref, "UTF-8")
                val photoUrl =
                    "https://maps.googleapis.com/maps/api/place/photo?maxwidth=800&photoreference=$encodedRef&key=$encodedKey"
                OnlineProjectPhotoCandidate(
                    title = if (photoRefs.size > 1) "$placeName (${index + 1})" else placeName,
                    imageUrl = photoUrl,
                    thumbnailUrl = photoUrl,
                    source = "Google Places"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Places photo search failed: ${e.message}")
            emptyList()
        }
    }

    private fun extractPhotoReferences(
        photosArray: org.json.JSONArray?,
        out: MutableSet<String>,
        limit: Int
    ) {
        if (photosArray == null) return
        for (i in 0 until photosArray.length()) {
            if (out.size >= limit) break
            val photoObj = photosArray.optJSONObject(i) ?: continue
            val ref = photoObj.optString("photo_reference", "").trim()
            if (ref.isNotBlank()) {
                out.add(ref)
            }
        }
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
