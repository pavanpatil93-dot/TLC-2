package com.example.data

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Dedicated client for the TLC Master Sheet (Schema v2 / v3) Google Apps Script Web App API.
 *
 * Transport & Auth rules:
 * - ALL reads and writes route through the configured `TlcWebApp.gs` `/exec` URL (`GET ?tabName=<encodedTabName>`
 *   or `POST ?tabName=<encodedTabName>`) — never direct `docs.google.com/spreadsheets/...` URLs.
 * - Uses OkHttp with HTTP 302 redirect handling for Apps Script `/exec` -> `script.googleusercontent.com`.
 * - Surfaces actionable HTTP 401 / 403 hints: `"Check Apps Script deployment: Execute as 'Me', Who has access 'Anyone'."`
 */
object TlcSchemaV2Api {
    private const val TAG = "TlcSchemaV2Api"
    const val AUTH_401_403_HINT =
        "Check Apps Script deployment: Execute as 'Me', Who has access 'Anyone'."

    /** New Schema v2/v3 Google Apps Script Web App base URL (`/exec`). */
    const val BASE_WEB_APP_URL =
        "https://script.google.com/macros/s/AKfycbxv27LN55uQg2ehxIf_4jN9rHdQganq8nKZz0OabGEhrfigkB2bfJb6FXMhnfqcWBw/exec"

    /** Authoritative TLC Master sheet identity used for sync verification and diagnostics. */
    const val EXPECTED_SPREADSHEET_NAME = "TLC Master sheet"
    const val EXPECTED_SPREADSHEET_ID = "1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0"

    const val TAB_COMMERCIAL_CRM = "Commercial CRM"
    const val TAB_RESIDENTIAL_CRM = "Residential CRM"
    const val TAB_COMMERCIAL_PROJECTS = "Commercial Projects"
    const val TAB_RESIDENTIAL_PROJECTS = "Residential Projects"
    const val TAB_COMMERCIAL_BROKERS = "Commercial Brokers"
    const val TAB_RESIDENTIAL_BROKERS = "Residential Brokers"
    const val TAB_VENDORS = "Vendors"
    const val TAB_TEAM_USERS = "Team & Users"
    const val TAB_CALL_LOGS = "Call Logs"
    const val TAB_TENANTS = "Tenants"
    const val TAB_DAILY_REPORTS = "Daily Reports"
    const val TAB_APPROVALS_AUDIT_LOG = "Approvals & Audit Log"

    /** Exact canonical worksheet names in `TLC Master sheet` (`1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0`). */
    val CANONICAL_SYNC_TABS: List<String> = TlcInventoryTabV2.ALL_FIVE_TABS + listOf(
        TAB_COMMERCIAL_CRM,
        TAB_RESIDENTIAL_CRM,
        TAB_COMMERCIAL_PROJECTS,
        TAB_RESIDENTIAL_PROJECTS,
        TAB_COMMERCIAL_BROKERS,
        TAB_RESIDENTIAL_BROKERS,
        TAB_VENDORS,
        TAB_TEAM_USERS,
        TAB_CALL_LOGS,
        TAB_TENANTS
    )

    /** Additional operational/audit tabs reconciled on sync when present in the sheet. */
    val OPERATIONAL_RECONCILE_TABS: List<String> = listOf(
        TAB_DAILY_REPORTS,
        TAB_APPROVALS_AUDIT_LOG
    )

    /** Legacy v1 script deployment fragment — automatically migrated to BASE_WEB_APP_URL if found in prefs. */
    const val DEPRECATED_V1_SCRIPT_ID =
        "AKfycbxLZh3AEON1wweJc3jBWjjWOTPltAmGIgVqRtFCqX9kmzz7JEeMoilTMG8u9X3RiU8_"

    private const val PLACEHOLDER_KEY = "MY_TLC_API_KEY"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** OkHttp GET client following Apps Script HTTP 302 redirects cleanly. */
    private val getHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** OkHttp POST client handling Apps Script 302 redirect to `script.googleusercontent.com` via GET without replaying POST. */
    private val postHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

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

    data class TlcTabSyncResponse(
        val tabName: String,
        val isStatusOk: Boolean,
        val rawJson: JSONObject,
        val rowCount: Int,
        val spreadsheetName: String? = null,
        val spreadsheetId: String? = null
    )

    fun buildTabGetUrl(
        baseUrl: String = BASE_WEB_APP_URL,
        tabName: String,
        role: String = "RM"
    ): Result<String> {
        val apiKey = resolveApiKey()
        val cleanBase = resolveEndpointUrl(baseUrl)
        val sep = if (cleanBase.contains("?")) "&" else "?"
        val normalizedRole = if (role.equals("Admin", ignoreCase = true)) "Admin" else "RM"
        val query = buildList {
            add("tabName=${URLEncoder.encode(tabName.trim(), "UTF-8")}")
            add("role=${URLEncoder.encode(normalizedRole, "UTF-8")}")
            if (apiKey.isNotBlank()) {
                add("api_key=${URLEncoder.encode(apiKey, "UTF-8")}")
            }
        }.joinToString("&")
        return Result.success("$cleanBase$sep$query")
    }

    fun buildTabPostUrl(
        baseUrl: String = BASE_WEB_APP_URL,
        tabName: String
    ): String {
        val cleanBase = resolveEndpointUrl(baseUrl)
        val sep = if (cleanBase.contains("?")) "&" else "?"
        return "$cleanBase${sep}tabName=${URLEncoder.encode(tabName.trim(), "UTF-8")}"
    }

    /**
     * Queries a single worksheet via the Apps Script Web App `/exec` URL
     * (`GET ?tabName=<encodedTabName>&role=<role>&api_key=<key>`).
     * Verifies explicit `status == "OK"` (or `"success"` / `ok == true`) before callers
     * may reconcile/delete missing local rows.
     */
    suspend fun fetchTabRaw(
        baseUrl: String = BASE_WEB_APP_URL,
        tabName: String,
        role: String = "RM"
    ): Result<TlcTabSyncResponse> = withContext(Dispatchers.IO) {
        val url = buildTabGetUrl(
            baseUrl = baseUrl,
            tabName = tabName,
            role = role
        ).getOrElse { return@withContext Result.failure(it) }
        executeGetJson(url).mapCatching { root ->
            validateServerOk(root)
            val statusOk = isValidOkStatus(root)
            val (sheetName, sheetId) = extractSpreadsheetMetadata(root)
            val arr = extractPrimaryArray(root, tabName, "rows", "records", "inventory", "items", "data")
            TlcTabSyncResponse(
                tabName = tabName,
                isStatusOk = statusOk,
                rawJson = root,
                rowCount = arr?.length() ?: 0,
                spreadsheetName = sheetName,
                spreadsheetId = sheetId
            )
        }
    }

    /**
     * Pushes an `UPSERT` record to a specific tab via `POST ?tabName=<encodedTabName>`.
     */
    suspend fun postTabUpsert(
        baseUrl: String = BASE_WEB_APP_URL,
        tabName: String,
        record: JSONObject,
        action: String = "UPSERT",
        keyHeader: String? = null,
        keyValue: String? = null,
        submittedBy: String? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey()
        val payload = JSONObject().apply {
            put("action", action)
            put("tabName", tabName)
            put("tab", tabName)
            if (!keyHeader.isNullOrBlank()) put("keyHeader", keyHeader)
            if (!keyValue.isNullOrBlank()) put("keyValue", keyValue)
            if (!submittedBy.isNullOrBlank()) put("submitted_by", submittedBy)
            put("record", record)
            if (apiKey.isNotBlank()) put("api_key", apiKey)
        }
        val targetUrl = buildTabPostUrl(baseUrl, tabName)
        executePostWithGasRedirect(targetUrl, payload).mapCatching { root ->
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
    ): Result<JSONObject> = postTabUpsert(
        baseUrl = baseUrl,
        tabName = "Call Logs",
        record = record,
        action = "UPSERT",
        keyHeader = "Call ID",
        keyValue = record.optString("Call ID", record.optString("id", ""))
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

    // ── LOW-LEVEL OKHTTP TRANSPORT WITH GAS 302 REDIRECT & 401/403 HINTS ───────

    fun formatHttpErrorMessage(method: String, statusCode: Int, responseSnippet: String? = null): String {
        val cleanSnippet = responseSnippet?.trim()?.take(180).orEmpty()
        return if (statusCode == 401 || statusCode == 403) {
            "HTTP $statusCode: $AUTH_401_403_HINT"
        } else {
            val suffix = if (cleanSnippet.isNotBlank() && !cleanSnippet.startsWith("<")) ": $cleanSnippet" else ""
            "Apps Script $method returned HTTP $statusCode$suffix"
        }
    }

    fun executeGetJson(targetUrl: String): Result<JSONObject> {
        return try {
            val request = Request.Builder()
                .url(targetUrl.trim())
                .get()
                .header("User-Agent", "TLC-1-Android")
                .header("Accept", "application/json")
                .build()

            getHttpClient.newCall(request).execute().use { response ->
                val code = response.code
                val bodyText = response.body?.string().orEmpty().trim()
                if (code in 200..299) {
                    parseJsonBody(bodyText, code)
                } else {
                    Result.failure(IOException(formatHttpErrorMessage("GET", code, bodyText)))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun executePostWithGasRedirect(targetUrl: String, payload: JSONObject): Result<JSONObject> {
        var currentUrl = URL(targetUrl.trim())
        val origin = currentUrl
        var method = "POST"
        val jsonBody = payload.toString()

        repeat(6) {
            try {
                val builder = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "TLC-1-Android")
                    .header("Accept", "application/json")
                if (method == "POST") {
                    builder.post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                } else {
                    builder.get()
                }

                postHttpClient.newCall(builder.build()).execute().use { response ->
                    val code = response.code
                    val bodyText = response.body?.string().orEmpty().trim()
                    if (code in 200..299) {
                        return parseJsonBody(bodyText, code)
                    }
                    if (code == 301 || code == 302 || code == 303) {
                        val location = response.header("Location")
                            ?: return Result.failure(IOException("Apps Script POST redirect missing Location header."))
                        val next = URL(currentUrl, location)
                        if (!isAllowedGasRedirect(origin, next)) {
                            return Result.failure(IOException("Blocked unexpected Apps Script redirect host: ${next.host}"))
                        }
                        // Google Apps Script returns 302 to script.googleusercontent.com; fetch final JSON result via GET
                        currentUrl = next
                        method = "GET"
                    } else {
                        return Result.failure(IOException(formatHttpErrorMessage("POST", code, bodyText)))
                    }
                }
            } catch (e: Exception) {
                return Result.failure(e)
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
                IOException("Apps Script Web App returned HTML (HTTP $code). $AUTH_401_403_HINT")
            )
        }
        if (text.startsWith("[")) {
            val arr = runCatching { JSONArray(text) }.getOrNull()
                ?: return Result.failure(IOException("Invalid JSON array from Apps Script (HTTP $code)."))
            return Result.success(JSONObject().apply {
                put("status", "OK")
                put("data", arr)
            })
        }
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Result.failure(IOException("Invalid JSON response from Apps Script (HTTP $code)."))
        return Result.success(obj)
    }

    fun isValidOkStatus(root: JSONObject): Boolean {
        val statusStr = root.optString("status", "").trim().lowercase(Locale.ENGLISH)
        val hasExplicitFailure = statusStr == "error" ||
            statusStr == "failed" ||
            statusStr == "unauthorized" ||
            (root.has("ok") && !root.optBoolean("ok", true)) ||
            (root.has("success") && !root.optBoolean("success", true))
        if (hasExplicitFailure) return false
        return statusStr == "ok" ||
            statusStr == "success" ||
            (root.has("ok") && root.optBoolean("ok", false)) ||
            (root.has("success") && root.optBoolean("success", false))
    }

    fun extractSpreadsheetMetadata(root: JSONObject): Pair<String?, String?> {
        val containers = listOfNotNull(
            root,
            root.optJSONObject("meta"),
            root.optJSONObject("spreadsheet"),
            root.optJSONObject("data")
        )
        val nameKeys = listOf("spreadsheetName", "spreadsheet_name", "sheetTitle", "workbook", "workbookName", "spreadsheetTitle")
        val idKeys = listOf("spreadsheetId", "spreadsheet_id", "sheetId", "workbookId")

        var foundName: String? = null
        var foundId: String? = null
        for (obj in containers) {
            if (foundName == null) {
                foundName = nameKeys.firstNotNullOfOrNull { k ->
                    obj.optString(k, "").trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                }
            }
            if (foundId == null) {
                foundId = idKeys.firstNotNullOfOrNull { k ->
                    obj.optString(k, "").trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                }
            }
        }
        return foundName to foundId
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
