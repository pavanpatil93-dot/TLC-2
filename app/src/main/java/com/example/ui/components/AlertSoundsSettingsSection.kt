package com.example.ui.components

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.UserSettingsManager
import com.example.notifications.TeamAlertChannel
import com.example.notifications.TeamNotificationManager
import com.example.notifications.TeamVocalAlertManager
import com.example.notifications.VocalAlertMode
import com.example.ui.theme.*

@Composable
fun AlertSoundsSettingsSection(
    userSettingsManager: UserSettingsManager
) {
    val context = LocalContext.current
    val vocalMode by userSettingsManager.vocalAlertMode.collectAsState()
    var notifPermissionGranted by remember {
        mutableStateOf(TeamNotificationManager.hasNotificationPermission(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifPermissionGranted = granted || TeamNotificationManager.hasNotificationPermission(context)
        if (!notifPermissionGranted) {
            Toast.makeText(
                context,
                "Notification permission denied. You can enable it anytime in Android Settings.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            TeamNotificationManager.ensureChannelsCreated(context)
            Toast.makeText(context, "Team Alert notifications enabled", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        TeamNotificationManager.ensureChannelsCreated(context)
        notifPermissionGranted = TeamNotificationManager.hasNotificationPermission(context)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_alert_sounds_section")
    ) {
        // 1. Android 13+ Notification Permission Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
            border = BorderStroke(
                1.dp,
                if (notifPermissionGranted) StatusSuccessGreen.copy(alpha = 0.45f) else PrimaryActionOrange.copy(alpha = 0.65f)
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (notifPermissionGranted) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                        contentDescription = null,
                        tint = if (notifPermissionGranted) StatusSuccessGreen else PrimaryActionOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "TEAM SYNC & NOTIFICATION STATUS",
                        color = BrandGold,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                }

                Text(
                    text = if (notifPermissionGranted) {
                        "Notifications are active. Foreground sync checks every 3 min while open; WorkManager checks every 15 min in background. Self-edits are never alerted, and 3+ items auto-group into one summary."
                    } else {
                        "Android notification permission is currently off. Enable it to receive live team alerts and custom sounds for new properties, leads, site visits, closed units, and tenant issues."
                    },
                    color = TextSecondaryDark,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )

                if (!notifPermissionGranted) {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                TeamNotificationManager.openAppNotificationSettings(context)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("settings_enable_notifications_btn")
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Team Notifications", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2. Smart Vocal Alerts (TextToSpeech en-IN)
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
            border = BorderStroke(1.dp, BrandCharcoalBorder),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "SMART VOCAL ALERTS (en-IN)",
                                color = BrandGold,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "Short 2-3 sec spoken alerts via Android TextToSpeech",
                                color = TextSecondaryDark,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                }

                // Auto-Mute Guards Info Strip
                val isQuietNow = remember { TeamVocalAlertManager.isQuietHoursIst() }
                val isBtNow = remember { TeamVocalAlertManager.isBluetoothOrDrivingActive(context) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BrandCharcoalSurface)
                        .border(0.8.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.NightsStay,
                        contentDescription = null,
                        tint = if (isQuietNow) StatusWarningAmber else TextSecondaryDark,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = buildString {
                            append("Auto-muted on Silent/Vibrate & Quiet Hours (9:30 PM – 8:30 AM IST).")
                            if (isBtNow) append(" Bluetooth/Driving audio detected.")
                        },
                        color = TextSecondaryDark,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp
                    )
                }

                // 3 Vocal Alert Mode Options
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VocalAlertMode.entries.forEach { mode ->
                        val selected = vocalMode == mode
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) PrimaryActionOrange.copy(alpha = 0.14f) else BrandCharcoalSurface
                                )
                                .border(
                                    width = if (selected) 1.2.dp else 0.8.dp,
                                    color = if (selected) PrimaryActionOrange else BrandCharcoalBorder,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    userSettingsManager.setVocalAlertMode(mode)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("settings_vocal_mode_${mode.name.lowercase()}")
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { userSettingsManager.setVocalAlertMode(mode) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = PrimaryActionOrange,
                                    unselectedColor = TextSecondaryDark
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.label,
                                    color = TextPrimaryDark,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = mode.subtitle,
                                    color = TextSecondaryDark,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val msg = TeamVocalAlertManager.speakTestAlert(context)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("settings_test_vocal_alert_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = PrimaryActionOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Test Indian English Vocal Alert",
                        color = PrimaryActionOrange,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 3. Six Dedicated Notification Channels (Per-Channel Custom Sound Picker)
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
            border = BorderStroke(1.dp, BrandCharcoalBorder),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = BrandGold,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "CUSTOM ALERT SOUND CHANNELS (6)",
                            color = BrandGold,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Assign any custom .mp3 or phone tone per alert category via Android Notification Channels",
                            color = TextSecondaryDark,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                TeamAlertChannel.entries.forEach { channel ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandCharcoalSurface)
                            .border(0.8.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = channel.displayName,
                                color = TextPrimaryDark,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = channel.description,
                                color = TextSecondaryDark,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                TeamNotificationManager.openChannelSoundSettings(context, channel)
                            },
                            border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.65f)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("settings_change_sound_${channel.name.lowercase()}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Change Sound",
                                color = PrimaryActionOrange,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}
