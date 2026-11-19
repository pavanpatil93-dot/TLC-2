package com.example.notifications

import org.json.JSONObject

enum class TeamAlertChannel(
    val channelId: String,
    val displayName: String,
    val description: String,
    val shortBadge: String
) {
    NEW_LIVE_PROPERTY(
        channelId = "tlc_ch_new_live_property",
        displayName = "New Live Property Added",
        description = "Alerts when a teammate adds or publishes a new live inventory property.",
        shortBadge = "New Property"
    ),
    NEW_LEAD_HANDOVER(
        channelId = "tlc_ch_new_lead_handover",
        displayName = "New Lead / Team Handover",
        description = "Alerts for new CRM leads and call or lead team handovers.",
        shortBadge = "Lead / Handover"
    ),
    SITE_VISIT_COMPLETED(
        channelId = "tlc_ch_site_visit_completed",
        displayName = "Site Visit Completed",
        description = "Alerts when a teammate completes a client site visit.",
        shortBadge = "Site Visit"
    ),
    UNIT_CLOSED(
        channelId = "tlc_ch_unit_closed",
        displayName = "Unit Marked Sold / Leased Out",
        description = "Alerts when an inventory unit is marked Sold or Leased Out.",
        shortBadge = "Unit Closed"
    ),
    TENANT_ISSUE(
        channelId = "tlc_ch_tenant_issue",
        displayName = "Tenant Issue Logged",
        description = "Alerts when a new tenant maintenance or lease issue is logged.",
        shortBadge = "Tenant Issue"
    ),
    FOLLOW_UP_DUE(
        channelId = "tlc_ch_follow_up_due",
        displayName = "Follow-up Reminder Due",
        description = "Reminders when a scheduled client follow-up is due.",
        shortBadge = "Follow-up Due"
    );

    companion object {
        fun fromChannelId(id: String?): TeamAlertChannel =
            entries.firstOrNull { it.channelId == id || it.name == id } ?: NEW_LIVE_PROPERTY
    }
}

enum class VocalAlertMode(val label: String, val subtitle: String) {
    BLUETOOTH_DRIVING_ONLY(
        label = "Bluetooth / Driving Only",
        subtitle = "Speaks alerts only when connected to Bluetooth audio or car mode (Recommended)"
    ),
    ALWAYS_ON(
        label = "Always On",
        subtitle = "Speaks alerts on phone speaker or headset (still respects Silent & Quiet Hours)"
    ),
    OFF(
        label = "Off",
        subtitle = "Disable spoken alerts; visual & custom sound notifications stay active"
    );

    companion object {
        fun fromString(raw: String?): VocalAlertMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: BLUETOOTH_DRIVING_ONLY
    }
}

enum class AlertTargetType {
    PROPERTY,
    LEAD,
    TENANT,
    NONE;

    companion object {
        fun fromString(raw: String?): AlertTargetType =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

data class TeamAlertItem(
    val id: String,
    val channel: TeamAlertChannel,
    val title: String,
    val message: String,
    val vocalText: String,
    val actorName: String = "",
    val targetType: AlertTargetType = AlertTargetType.NONE,
    val targetId: String = "",
    val timestampMillis: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val groupedCount: Int = 1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("channel", channel.channelId)
        put("title", title)
        put("message", message)
        put("vocalText", vocalText)
        put("actorName", actorName)
        put("targetType", targetType.name)
        put("targetId", targetId)
        put("timestampMillis", timestampMillis)
        put("isRead", isRead)
        put("groupedCount", groupedCount)
    }

    companion object {
        fun fromJson(obj: JSONObject): TeamAlertItem = TeamAlertItem(
            id = obj.optString("id", ""),
            channel = TeamAlertChannel.fromChannelId(obj.optString("channel", "")),
            title = obj.optString("title", ""),
            message = obj.optString("message", ""),
            vocalText = obj.optString("vocalText", ""),
            actorName = obj.optString("actorName", ""),
            targetType = AlertTargetType.fromString(obj.optString("targetType", "")),
            targetId = obj.optString("targetId", ""),
            timestampMillis = obj.optLong("timestampMillis", 0L),
            isRead = obj.optBoolean("isRead", false),
            groupedCount = obj.optInt("groupedCount", 1).coerceAtLeast(1)
        )
    }
}
