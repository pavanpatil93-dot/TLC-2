package com.example.data

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Dedicated client for the TLC Master Sheet (Schema v2) Google Apps Script Web App API.
 *
 * Auth rules:
 * - GET requests pass `api_key=<BuildConfig.TLC_API_KEY>` as a query parameter.
 * - POST requests include `"api_key": "<BuildConfig.TLC_API_KEY>"` in the JSON body.
 * - POST requests handle Google Apps Script 302 redirects cleanly (`instanceFollowRedirects = false`,
 *   following the redirect `Location` to `script.googleusercontent.com` via GET without replaying POST).
 */
object TlcSchemaV2Api {
    private const val TAG = "TlcSchemaV2Api"

    /** New Schema v2 Google Apps Script Web App base URL. */
    const val BASE_WEB_APP_URL =
        "https://script.google.com/macros/s/AKfycbxv27LN55uQg2ehxIf_4jN9rHdQganq8nKZz0OabGEhrfigkB2bfJb6FXMhnfqcWBw/exec"

    /** Legacy v1 script deployment fragment — automatically migrated to BASE_WEB_APP_URL if found in prefs. */
    const val DEPRECATED_V1_SCRIPT_ID =
        "AKfycbxLZh3AEON1wweJc3jBWjjWOTPltAmGIgVqRtFCqX9kmzz7JEeMoilTMG8u9X3RiU8_"

    private const val PLACEHOLDER_KEY = "MY_TLC_API_KEY"

    fun resolveEndpointUrl(overrideUrl: String? = null): String {
        val candidate = overrideUrl?.trim().orEmpty()
        if (candidate.isBlank() || candidate.contains(DEPRECATED_V1_SCRIPT_ID, ignoreCase = true)) {
            return BASE_WEB_APP_URL
        }
        return if (candidate.startsWith("https://") && candidate.contains("script.google.com", ignoreCase = true)) {
            candidate
        } else {
            BASE_WEB_APP_URL
        }
    }

    fun resolveApiKey(): String {
        val key = runCatching { BuildConfig.TLC_API_KEY }.getOrDefault("").trim()
        if (key.isBlank() || key.equals(PLACEHOLDER_KEY, ignoreCase = true)) {
            Log.e(TAG, "TLC_API_KEY is missing. Set TLC_API_KEY in the Secrets panel in AI Studio.")
            return ""
        }
        return key
    }

    private fun requireApiKey(): Result<String> {
        val key = resolveApiKey()
        return if (key.isBlank()) {
            Result.failure(
                IllegalStateException(
                    "TLC_API_KEY is not configured. Please set TLC_API_KEY in the AI Studio Secrets panel and rebuild."
                )
            )
        } else {
            Result.success(key)
        }
    }

    fun buildGetUrl(
        baseUrl: String = BASE_WEB_APP_URL,
        action: String,
        params: Map<String, String?> = emptyMap()
    ): Result<String> {
        val apiKey = requireApiKey().getOrElse { return Result.failure(it) }
        val cleanBase = resolveEndpointUrl(baseUrl)
        val sep = if (cleanBase.contains("?")) "&" else "?"
        val query = buildList {
            add("action=${URLEncoder.encode(action, "UTF-8")}")
            params.forEach { (k, v) ->
                val cleanVal = v?.trim().orEmpty()
                if (cleanVal.isNotBlank()) {
                    add("${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(cleanVal, "UTF-8")}")
                }
            }
            add("api_key=${URLEncoder.encode(apiKey, "UTF-8")}")
        }.joinToString("&")
        return Result.success("$cleanBase$sep$query")
    }

    // ── GET ENDPOINTS ──────────────────────────────────────────────────────────

    suspend fun fetchBootstrap(baseUrl: String = BASE_WEB_APP_URL): Result<TlcBootstrapPayload> =
        withContext(Dispatchers.IO) {
            val url = buildGetUrl(baseUrl, action = "bootstrap").getOrElse { return@withContext Result.failure(it) }
            executeGetJson(url).mapCatching { root ->
                validateServerOk(root)
                TlcBootstrapPayload.fromJson(root)
            }
        }

    suspend fun fetchLeadsRaw(
        baseUrl: String = BASE_WEB_APP_URL,
        vertical: String,
        assignedRm: String? = null,
        zone: String? = null,
        temperature: String? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val url = buildGetUrl(
            baseUrl = baseUrl,
            action = "leads",
            params = mapOf(
                "vertical" to vertical,
                "assigned_rm" to assignedRm,
                "zone" to zone,
                "temperature" to temperature
            )
        ).getOrElse { return@withContext Result.failure(it) }
        executeGetJson(url).mapCatching { root ->
            validateServerOk(root)
            root
        }
    }

    suspend fun fetchInventoryRaw(
        baseUrl: String = BASE_WEB_APP_URL,
        zone: String? = null,
        role: String = "RM"
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val normalizedRole = if (role.equals("Admin", ignoreCase = true)) "Admin" else "RM"
        val url = buildGetUrl(
            baseUrl = baseUrl,
            action = "inventory",
            params = mapOf(
                "zone" to zone,
                "role" to normalizedRole
            )
        ).getOrElse { return@withContext Result.failure(it) }
        executeGetJson(url).mapCatching { root ->
            validateServerOk(root)
            root
        }
    }

    suspend fun fetchVisits(
        baseUrl: String = BASE_WEB_APP_URL,
        assignedRm: String? = null
    ): Result<List<TlcSiteVisitRecord>> = withContext(Dispatchers.IO) {
        val url = buildGetUrl(
            baseUrl = baseUrl,
            action = "visits",
            params = mapOf("assigned_rm" to assignedRm)
        ).getOrElse { return@withContext Result.failure(it) }
        executeGetJson(url).mapCatching { root ->
            validateServerOk(root)
            val arr = extractPrimaryArray(root, "visits", "Site Visits", "data", "records")
            buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(TlcSiteVisitRecord.fromJson(obj))
                    }
                }
            }
        }
    }

    suspend fun fetchDashboard(baseUrl: String = BASE_WEB_APP_URL): Result<TlcDashboardKpis> =
        withContext(Dispatchers.IO) {
            val url = buildGetUrl(baseUrl, action = "dashboard").getOrElse { return@withContext Result.failure(it) }
            executeGetJson(url).mapCatching { root ->
                validateServerOk(root)
                TlcDashboardKpis.fromJson(root)
            }
        }

    // ── POST ENDPOINTS (JSON BODY WITH action + api_key + 302 REDIRECT HANDLING) ──

    suspend fun upsertLead(
        baseUrl: String = BASE_WEB_APP_URL,
        vertical: String,
        record: JSONObject
    ): Result<JSONObject> = postAction(
        baseUrl = baseUrl,
        payload = JSONObject().apply {
            put("action", "upsert_lead")
            put("vertical", if (vertical.contains("res", ignoreCase = true)) "residential" else "commercial")
            put("record", record)
        }
    )

    suspend fun logCall(
        baseUrl: String = BASE_WEB_APP_URL,
        record: JSONObject
    ): Result<JSONObject> = postAction(
        baseUrl = baseUrl,
        payload = JSONObject().apply {
            put("action", "log_call")
            put("record", record)
        }
    )

    suspend fun logVisit(
        baseUrl: String = BASE_WEB_APP_URL,
        record: JSONObject
    ): Result<JSONObject> = postAction(
        baseUrl = baseUrl,
        payload = JSONObject().apply {
            put("action", "log_visit")
            put("record", record)
        }
    )

    suspend fun submitDailyReport(
        baseUrl: String = BASE_WEB_APP_URL,
        record: JSONObject
    ): Result<JSONObject> = postAction(
        baseUrl = baseUrl,
        payload = JSONObject().apply {
            put("action", "submit_daily_report")
            put("record", record)
        }
    )

    suspend fun submitInventory(
        baseUrl: String = BASE_WEB_APP_URL,
        tab: String,
        submittedBy: String,
        record: JSONObject
    ): Result<JSONObject> = postAction(
        baseUrl = baseUrl,
        payload = JSONObject().apply {
            put("action", "submit_inventory")
            put("tab", tab)
            put("submitted_by", submittedBy)
            put("record", record)
        }
    )

    suspend fun postAction(
        baseUrl: String = BASE_WEB_APP_URL,
        payload: JSONObject
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val apiKey = requireApiKey().getOrElse { return@withContext Result.failure(it) }
        val signedPayload = JSONObject(payload.toString()).apply {
            put("api_key", apiKey)
        }
        executePostWithGasRedirect(resolveEndpointUrl(baseUrl), signedPayload).mapCatching { root ->
            validateServerOk(root)
            root
        }
    }

    // ── LOW-LEVEL TRANSPORT WITH GAS 302 REDIRECT SUPPORT ───────────────────────

    fun executeGetJson(targetUrl: String): Result<JSONObject> {
        var url = URL(targetUrl.trim())
        val origin = url
        repeat(6) {
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 25_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "TLC-1-Android")
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                    return parseJsonBody(text, code)
                }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val location = conn.getHeaderField("Location")
                        ?: return Result.failure(IOException("Apps Script GET redirect missing Location header."))
                    val next = URL(url, location)
                    if (!isAllowedGasRedirect(origin, next)) {
                        return Result.failure(IOException("Blocked unexpected Apps Script redirect host: ${next.host}"))
                    }
                    url = next
                } else {
                    val errText = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull()
                    return Result.failure(IOException("Apps Script GET returned HTTP $code${errText?.take(180)?.let { ": $it" }.orEmpty()}"))
                }
            } catch (e: Exception) {
                return Result.failure(e)
            } finally {
                conn?.disconnect()
            }
        }
        return Result.failure(IOException("Too many redirects on Apps Script GET request."))
    }

    fun executePostWithGasRedirect(targetUrl: String, payload: JSONObject): Result<JSONObject> {
        var url = URL(targetUrl.trim())
        val origin = url
        var method = "POST"
        val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)

        repeat(6) {
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 25_000
                    requestMethod = method
                    setRequestProperty("User-Agent", "TLC-1-Android")
                    setRequestProperty("Accept", "application/json")
                    if (method == "POST") {
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        doOutput = true
                        outputStream.use { it.write(bodyBytes) }
                    }
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                    return parseJsonBody(text, code)
                }
                if (code == 301 || code == 302 || code == 303) {
                    val location = conn.getHeaderField("Location")
                        ?: return Result.failure(IOException("Apps Script POST redirect missing Location header."))
                    val next = URL(url, location)
                    if (!isAllowedGasRedirect(origin, next)) {
                        return Result.failure(IOException("Blocked unexpected Apps Script redirect host: ${next.host}"))
                    }
                    // Google Apps Script returns 302 to script.googleusercontent.com; fetch final JSON result via GET
                    url = next
                    method = "GET"
                } else {
                    val errText = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull()
                    return Result.failure(IOException("Apps Script POST returned HTTP $code${errText?.take(180)?.let { ": $it" }.orEmpty()}"))
                }
            } catch (e: Exception) {
                return Result.failure(e)
            } finally {
                conn?.disconnect()
            }
        }
        return Result.failure(IOException("Too many redirects on Apps Script POST request."))
    }

    private fun isAllowedGasRedirect(origin: URL, next: URL): Boolean {
        val sameOrigin = next.protocol == origin.protocol &&
            next.host.equals(origin.host, ignoreCase = true) &&
            next.port == origin.port
        val googleResult = origin.protocol == "https" &&
            origin.host.equals("script.google.com", ignoreCase = true) &&
            next.protocol == "https" &&
            (next.host.equals("script.googleusercontent.com", ignoreCase = true) ||
                next.host.endsWith(".googleusercontent.com", ignoreCase = true))
        return sameOrigin || googleResult
    }

    private fun parseJsonBody(text: String, code: Int): Result<JSONObject> {
        if (text.startsWith("<!DOCTYPE", ignoreCase = true) || text.startsWith("<html", ignoreCase = true)) {
            return Result.failure(
                IOException("Apps Script Web App returned HTML (HTTP $code). Verify deployment access and api_key.")
            )
        }
        if (text.startsWith("[")) {
            val arr = runCatching { JSONArray(text) }.getOrNull()
                ?: return Result.failure(IOException("Invalid JSON array from Apps Script (HTTP $code)."))
            return Result.success(JSONObject().apply {
                put("status", "success")
                put("data", arr)
            })
        }
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Result.failure(IOException("Invalid JSON response from Apps Script (HTTP $code)."))
        return Result.success(obj)
    }

    private fun validateServerOk(root: JSONObject) {
        val statusStr = root.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val hasExplicitFailure = statusStr == "error" ||
            statusStr == "failed" ||
            statusStr == "unauthorized" ||
            (root.has("ok") && !root.optBoolean("ok", true)) ||
            (root.has("success") && !root.optBoolean("success", true))
        if (hasExplicitFailure) {
            val msg = root.optString("message", "").ifBlank {
                root.optString("error", "").ifBlank { "Apps Script API request failed ($statusStr)." }
            }
            throw IllegalStateException(msg)
        }
    }

    fun extractPrimaryArray(root: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) {
            root.optJSONArray(k)?.let { return it }
        }
        val nested = root.optJSONObject("data")
        if (nested != null) {
            for (k in keys) {
                nested.optJSONArray(k)?.let { return it }
            }
        }
        return null
    }
}
