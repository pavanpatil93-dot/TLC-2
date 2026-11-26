package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access.ActiveBusinessView
import com.example.model.ActiveBrand
import com.example.model.Brand
import com.example.model.BrandLogos
import com.example.ui.theme.*

@Composable
fun PortalTopBar(
    selectedBrand: Brand?,
    onSelectBrand: (Brand?) -> Unit,
    brandLogos: BrandLogos,
    onOpenDrawer: () -> Unit,
    onOpenAddProperty: () -> Unit,
    onOpenImport: () -> Unit,
    comparisonCount: Int,
    onOpenComparison: () -> Unit,
    onNavigateHome: () -> Unit,
    activeBrandScope: ActiveBrand? = null,
    canSwitchBrand: Boolean = true,
    onSelectActiveBrand: ((ActiveBrand) -> Unit)? = null,
    screenTitle: String? = null,
    activeBusinessView: ActiveBusinessView? = activeBrandScope?.let { ActiveBusinessView.fromActiveBrand(it) },
    availableBusinessViews: List<ActiveBusinessView> = ActiveBusinessView.entries,
    onSelectBusinessView: ((ActiveBusinessView) -> Unit)? = null,
    employeeDisplayName: String? = null,
    onOpenProfile: (() -> Unit)? = null,
    unreadAlertCount: Int = 0,
    onOpenAlerts: (() -> Unit)? = null,
    topSyncChipState: com.example.data.TopSyncChipState? = null,
    onTapSyncChip: (() -> Unit)? = null
) {
    Surface(
        color = BackgroundSoft,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.8.dp, BorderGrey)
            .testTag("portal_top_bar")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            // Row 1: Hamburger + Brand Logo + Screen Title / The Leasing Company + Profile Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceTint)
                            .border(0.8.dp, BorderGrey, RoundedCornerShape(10.dp))
                            .testTag("hamburger_menu_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Navigation Sidebar",
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val customLogo = when (selectedBrand) {
                        Brand.COMMERCIAL -> brandLogos.commercialLogoUri
                        Brand.RESIDENTIAL -> brandLogos.residentialLogoUri
                        null -> null
                    }

                    Box(modifier = Modifier.clickable { onNavigateHome() }) {
                        BrandLogoView(
                            brand = selectedBrand,
                            customLogoUri = customLogo,
                            size = 36.dp,
                            modifier = Modifier.width(62.dp).height(34.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable { onNavigateHome() }
                    ) {
                        Text(
                            text = screenTitle ?: when (selectedBrand) {
                                Brand.COMMERCIAL -> "Commercial"
                                Brand.RESIDENTIAL -> "Residential"
                                null -> TLC_APP_DISPLAY_NAME
                            },
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val divisionSubtitle = when (activeBusinessView) {
                            ActiveBusinessView.ASCEND_COMMERCIAL -> "The Leasing Company • Commercial"
                            ActiveBusinessView.AUREA_RESIDENTIAL -> "The Leasing Company • Residential"
                            ActiveBusinessView.ALL_BUSINESSES -> "The Leasing Company • Both Divisions"
                            null -> "The Leasing Company"
                        }
                        Text(
                            text = divisionSubtitle,
                            color = SecondaryText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isDesktop = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
                    if (comparisonCount > 0) {
                        Surface(
                            onClick = onOpenComparison,
                            shape = RoundedCornerShape(8.dp),
                            color = SelectedContainerColor,
                            border = BorderStroke(1.dp, FocusOutlineColor),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("topbar_compare_badge")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    "Compare ($comparisonCount)",
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    if (isDesktop) {
                        IconButton(
                            onClick = onOpenImport,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceWhite)
                                .border(0.8.dp, BorderGrey, RoundedCornerShape(10.dp))
                                .testTag("upload_inventory_top_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = "Sync Inventory Sheet",
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onOpenAddProperty,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PrimaryActionOrange)
                                .testTag("add_property_top_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Property",
                                tint = OnPrimaryActionColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Subtle Multi-Device Sync Status Chip (tapping forces immediate Push + Pull)
                    if (topSyncChipState != null && onTapSyncChip != null) {
                        val (chipBg, chipBorder, chipContentColor, chipLabel) = when (topSyncChipState.status) {
                            com.example.data.TopSyncStatus.SYNCED -> Quadruple(
                                SuccessGreen.copy(alpha = 0.12f),
                                SuccessGreen.copy(alpha = 0.45f),
                                SuccessGreen,
                                "Synced"
                            )
                            com.example.data.TopSyncStatus.SYNCING -> Quadruple(
                                PrimaryTeal.copy(alpha = 0.12f),
                                PrimaryTeal.copy(alpha = 0.45f),
                                PrimaryTeal,
                                "Syncing..."
                            )
                            com.example.data.TopSyncStatus.OFFLINE_PENDING -> Quadruple(
                                WarningAmber.copy(alpha = 0.16f),
                                WarningAmber.copy(alpha = 0.6f),
                                WarningAmber,
                                if (topSyncChipState.pendingCount > 0) "Offline (${topSyncChipState.pendingCount} pending)" else "Offline"
                            )
                        }
                        Surface(
                            onClick = onTapSyncChip,
                            shape = RoundedCornerShape(10.dp),
                            color = chipBg,
                            border = BorderStroke(1.dp, chipBorder),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("topbar_sync_status_chip")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                if (topSyncChipState.status == com.example.data.TopSyncStatus.SYNCING) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.8.dp,
                                        color = chipContentColor
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(chipContentColor)
                                    )
                                }
                                Text(
                                    text = chipLabel,
                                    color = chipContentColor,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Notification Bell with unread badge (48.dp touch target)
                    if (onOpenAlerts != null) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("topbar_notification_bell_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = onOpenAlerts,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SurfaceTint)
                                    .border(
                                        0.8.dp,
                                        if (unreadAlertCount > 0) PrimaryActionOrange.copy(alpha = 0.6f) else BorderGrey,
                                        RoundedCornerShape(10.dp)
                                    )
                            ) {
                                Icon(
                                    imageVector = if (unreadAlertCount > 0) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                                    contentDescription = if (unreadAlertCount > 0) "Team Alerts ($unreadAlertCount unread)" else "Team Alerts",
                                    tint = if (unreadAlertCount > 0) PrimaryActionOrange else TextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (unreadAlertCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 4.dp, end = 4.dp)
                                        .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                        .clip(CircleShape)
                                        .background(ErrorRed)
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (unreadAlertCount > 99) "99+" else unreadAlertCount.toString(),
                                        color = androidx.compose.ui.graphics.Color.White,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Profile Action Button (48.dp touch target)
                    if (onOpenProfile != null) {
                        val initials = remember(employeeDisplayName) {
                            employeeDisplayName
                                ?.trim()
                                ?.split(Regex("\\s+"))
                                ?.filter { it.isNotBlank() }
                                ?.take(2)
                                ?.joinToString("") { it.first().uppercaseChar().toString() }
                                ?.ifBlank { null }
                        }
                        Surface(
                            onClick = onOpenProfile,
                            shape = RoundedCornerShape(10.dp),
                            color = SurfaceTint,
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("topbar_profile_btn")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(PrimaryActionOrange),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (initials != null) {
                                        Text(
                                            text = initials,
                                            color = OnPrimaryActionColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = "Profile and Settings",
                                            tint = OnPrimaryActionColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (!employeeDisplayName.isNullOrBlank()) {
                                    Text(
                                        text = employeeDisplayName.substringBefore(" "),
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
