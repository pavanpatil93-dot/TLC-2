package com.example.data

import com.example.model.PropertyItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Application acknowledgements, not HTTP reachability, determine sync success. */
internal object AppsScriptSyncProtocol {
    data class Check(val accepted: Boolean, val message: String, val retryable: Boolean = false)

    fun validateResponse(httpStatus: Int, response: String, request: JSONObject): Check {
        if (httpStatus !in 200..299) return Check(false, "HTTP $httpStatus; write unconfirmed", httpStatus == 429 || httpStatus >= 500)
        val body = runCatching { JSONObject(response.trim()) }.getOrNull()
            ?: return Check(false, "Endpoint did not return a JSON acknowledgement")
        if (body.optString("status").trim().lowercase() != "success") {
            return Check(false, "Endpoint did not confirm success")
        }
        val action = request.optString("action")
        if (action == "DIAGNOSTIC_PING") {
            return if (body.optString("action") == action || body.optString("status").equals("success", true)) {
                Check(true, "Diagnostic ping acknowledged")
            } else Check(false, "Diagnostic acknowledgement missing or mismatched")
        }
        // Schema v2 actions (submit_inventory, upsert_lead, log_call, log_visit, submit_daily_report)
        if (action in setOf("submit_inventory", "upsert_lead", "log_call", "log_visit", "submit_daily_report")) {
            val okFlag = if (body.has("ok")) body.optBoolean("ok", true)
                else if (body.has("success")) body.optBoolean("success", true)
                else body.optString("status").trim().lowercase() !in setOf("error", "failed", "unauthorized")
            return if (okFlag) Check(true, "Schema v2 write acknowledged ($action)")
            else Check(false, body.optString("message", body.optString("error", "Schema v2 write rejected")))
        }
        val expectedAction = when (action) {
            "ADD" -> "ADDED"
            "UPDATE" -> "UPDATED"
            "UPSERT" -> "UPSERTED"
            else -> return Check(false, "Unsupported write action")
        }
        if (action == "UPSERT") {
            val operationId = request.optString("operationId")
            if (operationId.isBlank() || body.optString("operationId") != operationId) {
                return Check(false, "Operation acknowledgement missing or mismatched")
            }
        }
        val expectedCode = request.optString("propertyCode").trim()
        val expectedRecord = request.optString("recordId").trim()
        if (expectedCode.isBlank() ||
            !body.optString("propertyCode").trim().equals(expectedCode, ignoreCase = true)) {
            return Check(false, "Property code acknowledgement missing or mismatched")
        }
        if (expectedRecord.isNotBlank() &&
            body.optString("recordId").trim() != expectedRecord) {
            return Check(false, "Permanent record acknowledgement missing or mismatched")
        }
        if (body.optString("action") != expectedAction) {
            return Check(false, "Write action acknowledgement missing or mismatched")
        }
        return Check(true, "Property write acknowledged")
    }

    fun hasInventoryResponse(response: String): Boolean {
        val text = response.trim()
        if (text.startsWith("[")) return runCatching { JSONArray(text) }.isSuccess
        val body = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (body.has("status") && body.optString("status").lowercase() !in setOf("success", "ok")) return false
        if (body.optJSONArray("properties") != null ||
            body.optJSONArray("inventory") != null ||
            body.optJSONArray("units") != null ||
            body.optJSONArray("data") != null
        ) return true
        val data = body.optJSONObject("data") ?: body.optJSONObject("properties") ?: body.optJSONObject("inventory") ?: body
        val names = listOf(
            "Commercial Lease Inventory", "Commercial Sale Inventory", "Pre-Leased Inventory",
            "Residential Lease Inventory", "Residential Sale Inventory",
            "Commercial Inventory", "Residential Inventory", "MASTER_PROPERTIES",
            "Master Inventory", "Properties", "Master Properties", "Off Lease", "Off Out",
            "Shop Lease", "Shop Out", "Pre - Leased", "Resi Rent", "Resi Resale", "Und Const"
        )
        return names.any { data.optJSONArray(it) != null }
    }

    /** Old scripts must never receive automatic queue replays. */
    fun supportsDurableQueue(response: String): Boolean {
        val body = runCatching { JSONObject(response) }.getOrNull() ?: return false
        if (body.optString("status") != "success") return false
        val capabilities = body.optJSONObject("capabilities") ?: return false
        return capabilities.optInt("queueProtocolVersion") == 1 &&
            capabilities.optBoolean("idempotentWrites") &&
            capabilities.optBoolean("operationAcknowledgements")
    }

    /** Viewing a property does not change its upstream content. All other edits do. */
    fun sameWriteSnapshot(current: PropertyItem, sent: PropertyItem): Boolean =
        current.copy(isSyncedToSheet = false, lastViewedTimestamp = 0L) ==
            sent.copy(isSyncedToSheet = false, lastViewedTimestamp = 0L)

    fun markAcknowledged(current: List<PropertyItem>, sent: PropertyItem): List<PropertyItem> =
        current.map { item ->
            if (item.id == sent.id && sameWriteSnapshot(item, sent)) {
                item.copy(isSyncedToSheet = true)
            } else item
        }

    /**
     * A failed/uncertain POST is never replayed as a GET write. Follow a normal
     * 302/303 result redirect with GET only; do not replay 307/308 POST redirects.
     */
    fun post(targetUrl: String, request: JSONObject): Pair<Boolean, String> =
        postChecked(targetUrl, request).let { it.accepted to it.message }

    fun postChecked(targetUrl: String, request: JSONObject): Check {
        val resolvedTarget = TlcSchemaV2Api.resolveEndpointUrl(targetUrl)
        var url = URL(resolvedTarget)
        val origin = url
        var method = "POST"
        val signedRequest = JSONObject(request.toString()).apply {
            if (!has("api_key")) {
                val key = TlcSchemaV2Api.resolveApiKey()
                if (key.isNotBlank()) put("api_key", key)
            }
        }
        val payload = signedRequest.toString().toByteArray(Charsets.UTF_8)
        repeat(5) {
            var connection: HttpURLConnection? = null
            try {
                connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.requestMethod = method
                connection.setRequestProperty("User-Agent", "TLC-1")
                if (method == "POST") {
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connection.doOutput = true
                    connection.outputStream.use { it.write(payload) }
                }
                val status = connection.responseCode
                if (status in 200..299) {
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val check = validateResponse(status, response, request)
                    return check
                }
                if (status == 301 || status == 302 || status == 303) {
                    val location = connection.getHeaderField("Location")
                        ?: return Check(false, "Redirect has no result location; write unconfirmed")
                    val next = URL(url, location)
                    val sameOrigin = next.protocol == origin.protocol &&
                        next.host == origin.host && next.port == origin.port
                    val googleResult = origin.protocol == "https" &&
                        origin.host == "script.google.com" && next.protocol == "https" &&
                        next.host == "script.googleusercontent.com"
                    if (!sameOrigin && !googleResult) {
                        return Check(false, "Unexpected result redirect; write unconfirmed")
                    }
                    url = next
                    method = "GET"
                } else {
                    return Check(false, "HTTP $status; write unconfirmed and not replayed", status == 429 || status >= 500)
                }
            } catch (_: Exception) {
                return Check(false, "Network request failed; write unconfirmed", retryable = true)
            } finally {
                connection?.disconnect()
            }
        }
        return Check(false, "Too many result redirects; write unconfirmed")
    }
}

