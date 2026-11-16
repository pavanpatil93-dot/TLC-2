package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.model.Brand
import com.example.model.BrandLogos
import com.example.model.UserProfile
import com.example.model.UserRole
import com.example.ui.theme.*

enum class DrawerDestination(
    val title: String,
    val icon: ImageVector,
    val isComingSoon: Boolean = false
) {
    INVENTORY_DASHBOARD("Inventory Dashboard", Icons.Default.Dashboard),
    CRM_WORKSPACE("CRM Workspace", Icons.Default.Groups),
    CRM_CALLS("Call Intelligence", Icons.Default.Call),
    SEARCH_PROPERTIES("Search & Filter Properties", Icons.Default.Search),
    COMMERCIAL("Commercial Portfolio", Icons.Default.Business),
    RESIDENTIAL("Residential Portfolio", Icons.Default.Apartment),
    ADD_PROPERTY("Add Property", Icons.Default.AddCircleOutline),
    CLIENT_MATCHER("Client Requirement Matcher", Icons.Default.PersonSearch),
    AI_SEARCH("AI Inventory Search", Icons.Default.AutoAwesome),
    FIND_ON_MAP("Find Properties on Map", Icons.Default.Map),
    PROJECT_DIRECTORY("Project Directory", Icons.Default.Business),
    PUNE_INTELLIGENCE("Pune Market Intelligence", Icons.Default.LocationCity),
    SAVED_PROPERTIES("Saved Properties", Icons.Default.Bookmark),
    COMPARE_PROPERTIES("Compare Properties", Icons.Default.CompareArrows),
    MASTER_SYNC("Refresh from Master Sheet", Icons.Default.CloudSync),
    TEAM_ADMIN("Team Administration", Icons.Default.AdminPanelSettings),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun AppDrawerContent(
    currentDestination: DrawerDestination,
    selectedBrand: Brand?,
    brandLogos: BrandLogos,
    userProfile: UserProfile,
    commercialCount: Int,
    residentialCount: Int,
    comparisonCount: Int,
    savedCount: Int,
    unclassifiedCount: Int,
    onNavigate: (DrawerDestination) -> Unit,
    onCloseDrawer: () -> Unit,
    crmMode: Boolean = false,
    isDesktopCompact: Boolean = false,
    onToggleDesktopCompact: (() -> Unit)? = null,
    themeMode: TlcThemeMode = TlcThemeController.themeMode,
    onThemeModeChange: ((TlcThemeMode) -> Unit)? = null
) {
    Surface(
        color = BackgroundSoft,
        modifier = Modifier
            .fillMaxHeight()
            .width(if (isDesktopCompact) 72.dp else 296.dp)
            .testTag("app_navigation_drawer")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // =================================================================
            // DRAWER HEADER — THE LEASING COMPANY
            // =================================================================
            if (!isDesktopCompact) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        BrandLogoView(
                            brand = selectedBrand,
                            customLogoUri = when (selectedBrand) {
                                Brand.COMMERCIAL -> brandLogos.commercialLogoUri
                                Brand.RESIDENTIAL -> brandLogos.residentialLogoUri
                                null -> null
                            },
                            size = 38.dp
                        )
                        Column {
                            Text(
                                text = "THE LEASING COMPANY",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.6.sp
                            )
                            Text(
                                text = if (crmMode) "CRM • COMMERCIAL & RESIDENTIAL" else "INVENTORY • COMMERCIAL & RESIDENTIAL",
                                color = PrimaryActionOrange,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onCloseDrawer,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceTint)
                            .testTag("drawer_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Sidebar",
                            tint = SecondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(color = BorderGrey, thickness = 0.8.dp)
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    BrandLogoView(
                        brand = selectedBrand,
                        customLogoUri = when (selectedBrand) {
                            Brand.COMMERCIAL -> brandLogos.commercialLogoUri
                            Brand.RESIDENTIAL -> brandLogos.residentialLogoUri
                            null -> null
                        },
                        size = 32.dp
                    )
                    if (onToggleDesktopCompact != null) {
                        IconButton(onClick = onToggleDesktopCompact, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.MenuOpen, contentDescription = "Expand", tint = PrimaryActionOrange)
                        }
                    }
                }
                HorizontalDivider(color = BorderGrey, thickness = 0.8.dp)
            }

            // =================================================================
            // SCROLLABLE NAVIGATION ITEMS + APPEARANCE SWITCHER
            // =================================================================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp, horizontal = if (isDesktopCompact) 6.dp else 12.dp)
            ) {
                if (!isDesktopCompact) {
                    // APPEARANCE SWITCHER (Light / Dark) IN NAVIGATION SIDE PANEL
                    Surface(
                        color = SurfaceWhite,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .testTag("drawer_appearance_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "APPEARANCE",
                                    color = SecondaryText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.6.sp
                                )
                                Text(
                                    text = themeMode.label,
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TlcThemeMode.entries.forEach { mode ->
                                    val selected = themeMode == mode
                                    Surface(
                                        onClick = {
                                            if (onThemeModeChange != null) {
                                                onThemeModeChange(mode)
                                            } else {
                                                TlcThemeController.updateThemeMode(mode)
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) SelectedContainerColor else SurfaceTint,
                                        border = BorderStroke(
                                            width = if (selected) 1.5.dp else 1.dp,
                                            color = if (selected) FocusOutlineColor else BorderGrey
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("drawer_theme_${mode.name.lowercase()}")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = if (mode == TlcThemeMode.LIGHT) Icons.Default.LightMode else Icons.Default.DarkMode,
                                                contentDescription = "${mode.label} Theme",
                                                tint = if (selected) PrimaryActionOrange else SecondaryText,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = mode.label,
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = if (crmMode) "CRM WORKSPACE" else "INVENTORY WORKSPACE",
                        color = PrimaryActionOrange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            onNavigate(if (crmMode) DrawerDestination.SEARCH_PROPERTIES else DrawerDestination.CRM_WORKSPACE)
                            onCloseDrawer()
                        },
                        border = BorderStroke(1.dp, BorderGrey),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SurfaceWhite,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = PrimaryActionOrange)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (crmMode) "Switch to Inventory" else "Switch to CRM",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                val adminItems = if (userProfile.role == UserRole.ADMIN) {
                    listOf(DrawerDestination.TEAM_ADMIN)
                } else {
                    emptyList()
                }
                val workspaceDestinations = if (crmMode) listOf(
                    DrawerDestination.CRM_WORKSPACE,
                    DrawerDestination.CRM_CALLS,
                    DrawerDestination.CLIENT_MATCHER,
                    DrawerDestination.PROJECT_DIRECTORY
                ) + adminItems + listOf(DrawerDestination.SETTINGS) else listOf(
                    DrawerDestination.SEARCH_PROPERTIES,
                    DrawerDestination.PROJECT_DIRECTORY,
                    DrawerDestination.SAVED_PROPERTIES,
                    DrawerDestination.COMPARE_PROPERTIES,
                    DrawerDestination.FIND_ON_MAP,
                    DrawerDestination.MASTER_SYNC
                ) + adminItems + listOf(DrawerDestination.SETTINGS)

                workspaceDestinations.forEach { destination ->
                    if (!isDesktopCompact && destination == DrawerDestination.SETTINGS) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = BorderGrey)
                        Text(
                            text = "COMMON SETTINGS & TECHNICAL",
                            color = SecondaryText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                        )
                    }
                    if (!isDesktopCompact && destination == DrawerDestination.CLIENT_MATCHER) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "CLIENT & PROPERTY MATCHING",
                            color = SecondaryText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    val isSelected = currentDestination == destination

                    val badgeText = when (destination) {
                        DrawerDestination.COMMERCIAL -> "$commercialCount"
                        DrawerDestination.RESIDENTIAL -> "$residentialCount"
                        DrawerDestination.COMPARE_PROPERTIES -> if (comparisonCount > 0) "$comparisonCount" else null
                        DrawerDestination.SAVED_PROPERTIES -> if (savedCount > 0) "$savedCount" else null
                        else -> null
                    }

                    if (isDesktopCompact) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) SelectedContainerColor else Color.Transparent)
                                .clickable {
                                    onNavigate(destination)
                                    onCloseDrawer()
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.title,
                                tint = if (isSelected) PrimaryActionOrange else SecondaryText,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        Surface(
                            onClick = {
                                onNavigate(destination)
                                onCloseDrawer()
                            },
                            color = if (isSelected) SelectedContainerColor else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            border = if (isSelected) {
                                BorderStroke(1.dp, FocusOutlineColor)
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(vertical = 2.dp)
                                .testTag("drawer_item_${destination.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) PrimaryActionOrange else SecondaryText,
                                        modifier = Modifier.size(19.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = destination.title,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (badgeText != null) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(SelectedContainerColor)
                                            .border(0.8.dp, FocusOutlineColor, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = badgeText,
                                            color = TextPrimary,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = BorderGrey, thickness = 0.8.dp)

            // =================================================================
            // BOTTOM USER PROFILE SECTION
            // =================================================================
            if (!isDesktopCompact) {
                Surface(
                    onClick = {
                        onNavigate(DrawerDestination.SETTINGS)
                        onCloseDrawer()
                    },
                    color = SurfaceTint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("drawer_user_profile_section")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(SelectedContainerColor)
                                    .border(1.5.dp, FocusOutlineColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                val initials = userProfile.username
                                    .split(" ")
                                    .filter { it.isNotBlank() }
                                    .map { it.first() }
                                    .take(2)
                                    .joinToString("")
                                    .uppercase()
                                    .ifBlank { "U" }
                                Text(
                                    text = initials,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = userProfile.username.ifBlank { "Employee" },
                                    color = TextPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = userProfile.contactInfo,
                                    color = SecondaryText,
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SelectedContainerColor)
                                .border(0.8.dp, FocusOutlineColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = userProfile.role.label.uppercase(),
                                color = TextPrimary,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 12.dp)
                        .clickable {
                            onNavigate(DrawerDestination.SETTINGS)
                            onCloseDrawer()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SelectedContainerColor)
                            .border(1.dp, FocusOutlineColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = "Profile", tint = TextPrimary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
