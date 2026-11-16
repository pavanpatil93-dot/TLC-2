package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MasterSyncReport
import com.example.data.MasterSyncState
import com.example.data.TabSyncStatus
import com.example.ui.theme.*

private val SheetNavy: Color get() = TextPrimary
private val SheetTeal: Color get() = PrimaryActionOrange
private val SheetSoftWhite: Color get() = BackgroundSoft
private val SheetBorder: Color get() = BorderGrey
private val SheetSecondary: Color get() = SecondaryText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncResultBottomSheet(
    report: MasterSyncReport,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetSoftWhite,
        dragHandle = { BottomSheetDefaults.DragHandle(color = SheetNavy.copy(alpha = 0.3f)) },
        modifier = Modifier.testTag("sync_result_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Master Sheet Refresh",
                        color = SheetNavy,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Spreadsheet: 1VJruPx1...oNReQ",
                        color = SheetSecondary,
                        fontSize = 11.sp
                    )
                }

                val (badgeBg, badgeFg, badgeText) = when (report.state) {
                    MasterSyncState.SUCCESS -> Triple(Color(0xFFD1FAE5), Color(0xFF065F46), "SUCCESS")
                    MasterSyncState.PARTIAL -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), "PARTIAL")
                    MasterSyncState.OFFLINE -> Triple(Color(0xFFE2E8F0), Color(0xFF475569), "OFFLINE")
                    MasterSyncState.RUNNING -> Triple(Color(0xFFDBEAFE), Color(0xFF1E40AF), "SYNCING")
                    else -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), "FAILED")
                }

                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.testTag("sync_status_badge")
                ) {
                    Text(
                        text = badgeText,
                        color = badgeFg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Summary Message
            Text(
                text = report.summaryMessage,
                color = SheetNavy,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SyncMetricCard("Added", report.totalAdded, Color(0xFF10B981), Modifier.weight(1f))
                SyncMetricCard("Updated", report.totalUpdated, Color(0xFF3B82F6), Modifier.weight(1f))
                SyncMetricCard("Skipped", report.totalSkipped, SheetSecondary, Modifier.weight(1f))
            }

            // Tab breakdown
            Text(
                text = "WORKSHEET BREAKDOWN",
                color = SheetSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, SheetBorder, RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                report.tabResults.forEach { tab ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tab.worksheetName,
                                color = SheetNavy,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (tab.errorMessage != null) {
                                Text(
                                    text = tab.errorMessage,
                                    color = Color(0xFFEF4444),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        val tabLabel = when (tab.status) {
                            TabSyncStatus.SUCCESS -> "+${tab.addedCount} / ~${tab.updatedCount}"
                            TabSyncStatus.FAILED -> "Failed"
                            TabSyncStatus.SKIPPED_UNAUTHORIZED -> "No access"
                            TabSyncStatus.RUNNING -> "Refreshing..."
                            TabSyncStatus.PENDING -> "Pending"
                        }
                        val tabColor = when (tab.status) {
                            TabSyncStatus.SUCCESS -> Color(0xFF10B981)
                            TabSyncStatus.FAILED -> Color(0xFFEF4444)
                            else -> SheetSecondary
                        }
                        Text(
                            text = tabLabel,
                            color = tabColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = SheetTeal, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("sync_result_dismiss_btn")
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SyncMetricCard(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SheetBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = SheetSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(count.toString(), color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
