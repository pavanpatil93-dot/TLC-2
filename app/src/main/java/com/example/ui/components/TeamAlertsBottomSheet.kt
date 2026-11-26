package com.example.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notifications.AlertTargetType
import com.example.notifications.TeamAlertChannel
import com.example.notifications.TeamAlertItem
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamAlertsBottomSheet(
    alerts: List<TeamAlertItem>,
    onDismiss: () -> Unit,
    onAlertClick: (TeamAlertItem) -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenAlertSettings: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val unreadCount = alerts.count { !it.isRead }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundSoft,
        contentColor = TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = BorderGrey) },
        modifier = Modifier.testTag("team_alerts_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PrimaryActionOrange.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Team Alerts",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            if (unreadCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(PrimaryActionOrange)
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$unreadCount new",
                                        color = OnPrimaryActionColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Last 48 hours · Tap any alert to open record",
                            color = SecondaryText,
                            fontSize = 11.sp
                        )
                    }
                }

                IconButton(
                    onClick = {
                        onDismiss()
                        onOpenAlertSettings()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("team_alerts_sound_settings_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Alert Sounds & Vocal Alerts Settings",
                        tint = PrimaryActionOrange,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (unreadCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onMarkAllRead,
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .testTag("team_alerts_mark_all_read_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = null,
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Mark all read",
                            color = PrimaryActionOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (alerts.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SurfaceWhite,
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsNone,
                            contentDescription = null,
                            tint = SecondaryText,
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = "All caught up",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Team activity from the last 48 hours (new live properties, leads & handovers, completed site visits, closed units, tenant issues, and due follow-ups) appears here automatically every 3 minutes.",
                            color = SecondaryText,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onOpenAlertSettings()
                            },
                            border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("team_alerts_empty_settings_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Configure Alert Sounds & Vocal Alerts",
                                color = PrimaryActionOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(alerts, key = { it.id }) { alert ->
                        TeamAlertFeedCard(
                            alert = alert,
                            onClick = { onAlertClick(alert) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamAlertFeedCard(
    alert: TeamAlertItem,
    onClick: () -> Unit
) {
    val (accentColor, icon) = alertChannelVisual(alert.channel)
    val relativeTime = DateUtils.getRelativeTimeSpanString(
        alert.timestampMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

    val cardBg = if (alert.isRead) SurfaceWhite else PrimaryActionOrange.copy(alpha = 0.06f)
    val borderColor = if (alert.isRead) BorderGrey else PrimaryActionOrange.copy(alpha = 0.38f)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("team_alert_item_${alert.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = alert.channel.displayName,
                    tint = accentColor,
                    modifier = Modifier.size(19.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(accentColor.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (alert.groupedCount >= 3) "${alert.channel.shortBadge} · ${alert.groupedCount}" else alert.channel.shortBadge,
                                color = accentColor,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                        if (!alert.isRead) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryActionOrange)
                            )
                        }
                    }

                    Text(
                        text = relativeTime,
                        color = SecondaryText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Text(
                    text = alert.title,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (alert.isRead) FontWeight.SemiBold else FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = alert.message,
                    color = SecondaryText,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                val actionLabel = when (alert.targetType) {
                    AlertTargetType.PROPERTY -> "Open Property"
                    AlertTargetType.LEAD -> "Open CRM Lead"
                    AlertTargetType.TENANT -> "Open Tenant"
                    AlertTargetType.NONE -> null
                }
                if (actionLabel != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = actionLabel,
                            color = PrimaryActionOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun alertChannelVisual(channel: TeamAlertChannel): Pair<Color, ImageVector> = when (channel) {
    TeamAlertChannel.NEW_LIVE_PROPERTY -> StatusSuccessGreen to Icons.Default.Apartment
    TeamAlertChannel.NEW_LEAD_HANDOVER -> PrimaryActionOrange to Icons.Default.PersonAdd
    TeamAlertChannel.SITE_VISIT_COMPLETED -> Color(0xFF2563EB) to Icons.Default.EventAvailable
    TeamAlertChannel.UNIT_CLOSED -> ErrorRed to Icons.Default.Sell
    TeamAlertChannel.TENANT_ISSUE -> StatusWarningAmber to Icons.Default.ReportProblem
    TeamAlertChannel.FOLLOW_UP_DUE -> Color(0xFF7C3AED) to Icons.Default.PhoneCallback
}
