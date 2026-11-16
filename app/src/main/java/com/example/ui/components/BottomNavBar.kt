package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * 5 Primary Navigation Destinations with HOME centered:
 * INVENTORY | PROJECTS | HOME | CALLS | CRM
 */
enum class PortalNavTab(
    val label: String,
    val icon: ImageVector,
    val testTag: String
) {
    INVENTORY("Inventory", Icons.Default.Business, "nav_tab_inventory"),
    PROJECTS("Projects", Icons.Default.Apartment, "nav_tab_projects"),
    HOME("Home", Icons.Default.Home, "nav_tab_home"),
    CALLS("Calls", Icons.Default.Call, "nav_tab_calls"),
    CRM("CRM", Icons.Default.Groups, "nav_tab_crm");

    companion object {
        val DASHBOARD = HOME
        val MATCHES = INVENTORY
    }
}

@Composable
fun PortalBottomNavBar(
    selectedTab: PortalNavTab,
    onTabSelected: (PortalNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SurfaceWhite,
        tonalElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(0.8.dp, BorderGrey)
            .testTag("portal_bottom_nav_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 4.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PortalNavTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onTabSelected(tab) }
                        .padding(vertical = 4.dp)
                        .testTag(tab.testTag)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) SelectedContainerColor else Color.Transparent
                            )
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) FocusOutlineColor else Color.Transparent,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (isSelected) PrimaryActionOrange else SecondaryText,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = tab.label,
                        color = if (isSelected) TextPrimary else SecondaryText,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
