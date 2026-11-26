package com.example.data

import android.content.Context
import android.os.Build
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Generates and persists a stable per-device identity (`deviceTag` + human-friendly `deviceName`)
 * so simultaneous edits/creations on multiple phones (e.g. Samsung S21 Ultra & S22 Ultra signed
 * into the same account) produce globally collision-free IDs and display cross-device provenance.
 */
object DeviceIdentityManager {
    private const val PREFS_NAME = "tlc_device_identity_v1"
    private const val KEY_DEVICE_TAG = "device_tag"
    private const val KEY_DEVICE_SUFFIX = "device_suffix"
    private const val KEY_DEVICE_NAME = "device_name"

    private const val SUFFIX_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val secureRandom = SecureRandom()
    private val lastTimestamp = AtomicLong(0L)

    /**
     * Returns a human-friendly device name such as `"S21 Ultra"` or `"S22 Ultra"`.
     */
    fun getFriendlyDeviceName(context: Context? = null, modelOverride: String? = null): String {
        val rawModel = (modelOverride ?: Build.MODEL.orEmpty()).trim()
        val resolved = resolveFriendlyDeviceName(rawModel)
        if (context != null && modelOverride == null) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_DEVICE_NAME, null)?.trim()
            if (!saved.isNullOrBlank()) return saved
            prefs.edit().putString(KEY_DEVICE_NAME, resolved).apply()
        }
        return resolved
    }

    /**
     * Maps common Samsung Galaxy Ultra / Android model codes to concise human-friendly names.
     */
    fun resolveFriendlyDeviceName(model: String?): String {
        val raw = model?.trim().orEmpty()
        if (raw.isBlank()) return "Android Phone"
        val upper = raw.uppercase(Locale.ENGLISH)
        return when {
            upper.startsWith("SM-G998") || upper.contains("S21 ULTRA") -> "S21 Ultra"
            upper.startsWith("SM-G996") || upper.contains("S21+") -> "S21+"
            upper.startsWith("SM-G991") || upper.contains("S21") -> "S21"
            upper.startsWith("SM-S908") || upper.contains("S22 ULTRA") -> "S22 Ultra"
            upper.startsWith("SM-S906") || upper.contains("S22+") -> "S22+"
            upper.startsWith("SM-S901") || upper.contains("S22") -> "S22"
            upper.startsWith("SM-S918") || upper.contains("S23 ULTRA") -> "S23 Ultra"
            upper.startsWith("SM-S928") || upper.contains("S24 ULTRA") -> "S24 Ultra"
            upper.startsWith("SM-S938") || upper.contains("S25 ULTRA") -> "S25 Ultra"
            else -> raw.take(24)
        }
    }

    /**
     * Maps device model to a compact 3–4 char prefix (e.g. `S21U`, `S22U`, `PXL9`).
     */
    fun resolveShortModelCode(model: String?): String {
        val raw = model?.trim().orEmpty()
        val upper = raw.uppercase(Locale.ENGLISH)
        return when {
            upper.startsWith("SM-G998") || upper.contains("S21 ULTRA") -> "S21U"
            upper.startsWith("SM-G996") || upper.contains("S21+") -> "S21P"
            upper.startsWith("SM-G991") || upper.contains("S21") -> "S21"
            upper.startsWith("SM-S908") || upper.contains("S22 ULTRA") -> "S22U"
            upper.startsWith("SM-S906") || upper.contains("S22+") -> "S22P"
            upper.startsWith("SM-S901") || upper.contains("S22") -> "S22"
            upper.startsWith("SM-S918") || upper.contains("S23 ULTRA") -> "S23U"
            upper.startsWith("SM-S928") || upper.contains("S24 ULTRA") -> "S24U"
            upper.startsWith("SM-S938") || upper.contains("S25 ULTRA") -> "S25U"
            else -> {
                val cleaned = upper.replace(Regex("[^A-Z0-9]"), "")
                when {
                    cleaned.length >= 4 -> cleaned.takeLast(4)
                    cleaned.isNotEmpty() -> cleaned
                    else -> "DEV"
                }
            }
        }
    }

    /**
     * Returns the persistent unique device tag for this installation, formatted as
     * `<SHORT_CODE><4_CHAR_SUFFIX>` (e.g., `S21U7K3M`, `S22U4B9Q`).
     */
    @Synchronized
    fun getDeviceTag(context: Context?, modelOverride: String? = null): String {
        val shortCode = resolveShortModelCode(modelOverride ?: Build.MODEL)
        if (context == null) {
            return "$shortCode${generateRandomSuffix()}"
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (modelOverride == null) {
            val existing = prefs.getString(KEY_DEVICE_TAG, null)?.trim()
            if (!existing.isNullOrBlank()) return existing
        }
        val suffix = prefs.getString(KEY_DEVICE_SUFFIX, null)?.trim()?.takeIf { it.length == 4 }
            ?: generateRandomSuffix().also { generated ->
                prefs.edit().putString(KEY_DEVICE_SUFFIX, generated).apply()
            }
        val tag = "$shortCode$suffix"
        if (modelOverride == null) {
            prefs.edit()
                .putString(KEY_DEVICE_TAG, tag)
                .putString(KEY_DEVICE_NAME, resolveFriendlyDeviceName(Build.MODEL))
                .apply()
        }
        return tag
    }

    /**
     * Strictly monotonic millisecond timestamp so rapid sequential creates on the same phone
     * never produce colliding IDs even within the same millisecond.
     */
    private fun nextMonotonicTimestamp(nowMillis: Long = System.currentTimeMillis()): Long {
        while (true) {
            val prev = lastTimestamp.get()
            val next = if (nowMillis > prev) nowMillis else prev + 1L
            if (lastTimestamp.compareAndSet(prev, next)) return next
        }
    }

    /**
     * Collision-free Property ID: e.g., `COM-S21U7K3M-1732400000123` or `RES-S22U4B9Q-1732400000124`.
     */
    fun generatePropertyId(
        context: Context?,
        isCommercial: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        deviceTagOverride: String? = null
    ): String {
        val prefix = if (isCommercial) "COM" else "RES"
        val tag = deviceTagOverride?.trim()?.takeIf { it.isNotBlank() } ?: getDeviceTag(context)
        val ts = nextMonotonicTimestamp(nowMillis)
        return "$prefix-$tag-$ts"
    }

    /**
     * Collision-free CRM Lead ID: e.g., `CRM-S22U4B9Q-1732400000125`.
     */
    fun generateCrmLeadId(
        context: Context?,
        nowMillis: Long = System.currentTimeMillis(),
        deviceTagOverride: String? = null
    ): String {
        val tag = deviceTagOverride?.trim()?.takeIf { it.isNotBlank() } ?: getDeviceTag(context)
        val ts = nextMonotonicTimestamp(nowMillis)
        return "CRM-$tag-$ts"
    }

    /**
     * Collision-free Call Log ID: e.g., `CALL-S21U7K3M-1732400000126`.
     */
    fun generateCallLogId(
        context: Context?,
        nowMillis: Long = System.currentTimeMillis(),
        deviceTagOverride: String? = null
    ): String {
        val tag = deviceTagOverride?.trim()?.takeIf { it.isNotBlank() } ?: getDeviceTag(context)
        val ts = nextMonotonicTimestamp(nowMillis)
        return "CALL-$tag-$ts"
    }

    /**
     * Returns true when a synced record's `deviceName` belongs to a different phone than the current device.
     */
    fun isFromOtherDevice(recordDeviceName: String?, currentDeviceName: String): Boolean {
        val cleanRecord = recordDeviceName?.trim().orEmpty()
        val cleanCurrent = currentDeviceName.trim()
        if (cleanRecord.isBlank() || cleanCurrent.isBlank()) return false
        return !cleanRecord.equals(cleanCurrent, ignoreCase = true)
    }

    private fun generateRandomSuffix(length: Int = 4): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(SUFFIX_ALPHABET[secureRandom.nextInt(SUFFIX_ALPHABET.length)])
        }
        return sb.toString()
    }
}
