package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.crm.CrmCallManager
import com.example.data.OfflineDiskCache
import com.example.data.SheetsRepository
import com.example.ui.theme.BackgroundSoft
import com.example.ui.theme.BorderGrey
import com.example.ui.theme.PrimaryActionOrange
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SelectedSurfaceContainer
import com.example.ui.theme.SurfaceTint
import com.example.ui.theme.SurfaceWhite
import com.example.ui.theme.TextPrimary

val WhatsAppBrandGreen = Color(0xFF25D366)
val WhatsAppBrandDarkGreen = Color(0xFF128C7E)

data class TeamHandoverSummary(
    val senderEmployeeName: String,
    val roleLabel: String,
    val subRoleLabel: String = "",
    val callerName: String,
    val callerMobile10: String,
    val callerMobileDisplay: String = callerMobile10,
    val propertyOrProject: String = "",
    val followUpDayTime: String = "",
    val callNote: String = ""
) {
    fun formattedType(): String {
        val base = roleLabel.trim().ifBlank { "Call" }
        val sub = subRoleLabel.trim()
        return if (sub.isNotBlank() && !base.equals(sub, ignoreCase = true)) {
            "$base ($sub)"
        } else {
            base
        }
    }
}

fun buildTeamHandoverWhatsAppMessage(summary: TeamHandoverSummary): String {
    val cleanSender = summary.senderEmployeeName.trim().ifBlank { "TLC Team" }
    val cleanType = summary.formattedType().trim().ifBlank { "General Call" }
    val cleanName = summary.callerName.trim().ifBlank { "Unknown Caller" }
    val cleanMobile = CrmCallManager.last10Digits(summary.callerMobile10)
        .ifBlank { summary.callerMobile10.trim().ifBlank { "N/A" } }
    val cleanProperty = summary.propertyOrProject.trim().ifBlank { "N/A" }
    val cleanFollowUp = summary.followUpDayTime.trim().ifBlank { "None" }
    val cleanNote = summary.callNote.trim().ifBlank { "N/A" }

    return listOf(
        "*Handover from $cleanSender*",
        "*Type:* $cleanType",
        "*Name:* $cleanName",
        "*Mobile:* $cleanMobile",
        "*Property / Project:* $cleanProperty",
        "*Follow-up:* $cleanFollowUp",
        "*Note:* $cleanNote"
    ).joinToString("\n")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoverToTeamSheet(
    summary: TeamHandoverSummary,
    onDismiss: () -> Unit,
    onConfirmSendHandover: (member: OfflineDiskCache.CachedEmployeeMember, messageText: String) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var teamMembers by remember(context) {
        mutableStateOf(SheetsRepository.getSyncedActiveEmployeeDirectory(context))
    }
    LaunchedEffect(context) {
        val enriched = SheetsRepository.loadActiveEmployeeDirectoryWithRoom(context)
        if (enriched.isNotEmpty()) {
            teamMembers = enriched
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredMembers = remember(teamMembers, searchQuery) {
        val q = searchQuery.trim().lowercase()
        val ordered = teamMembers.sortedWith(
            compareBy<OfflineDiskCache.CachedEmployeeMember> {
                it.name.equals(summary.senderEmployeeName.trim(), ignoreCase = true)
            }.thenBy { it.name.lowercase() }
        )
        if (q.isBlank()) ordered
        else ordered.filter {
            it.name.lowercase().contains(q) ||
                it.role.lowercase().contains(q) ||
                it.division.lowercase().contains(q)
        }
    }

    var memberPendingMobileInput by remember { mutableStateOf<OfflineDiskCache.CachedEmployeeMember?>(null) }
    var enteredMobile by remember { mutableStateOf("") }

    val previewMessage = remember(summary) { buildTeamHandoverWhatsAppMessage(summary) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundSoft,
        contentColor = TextPrimary,
        modifier = Modifier.testTag("handover_to_team_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(WhatsAppBrandGreen.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Handover to Team via WhatsApp",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Assigns owner & logs handover automatically",
                            fontSize = 11.sp,
                            color = SecondaryText
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("handover_sheet_close_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close handover sheet",
                        tint = SecondaryText
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── 1. TOP SUMMARY CARD ────────────────────────────────────────
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("handover_summary_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = SelectedSurfaceContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = summary.formattedType(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryActionOrange,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Text(
                                text = summary.callerMobileDisplay.ifBlank { summary.callerMobile10 },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SecondaryText
                            )
                        }

                        Text(
                            text = summary.callerName.ifBlank { "Unknown Caller" },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        HandoverSummaryMetaRow(
                            label = "Property / Project",
                            value = summary.propertyOrProject.ifBlank { "N/A" }
                        )
                        HandoverSummaryMetaRow(
                            label = "Follow-up",
                            value = summary.followUpDayTime.ifBlank { "None" }
                        )
                        HandoverSummaryMetaRow(
                            label = "Note",
                            value = summary.callNote.ifBlank { "N/A" }
                        )
                    }
                }

                // ── 2. MIDDLE "SELECT TEAM MEMBER" LIST ────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Select Team Member",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${filteredMembers.size} Active",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SecondaryText
                        )
                    }

                    if (teamMembers.size > 4) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter by name, role or division...", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search team member",
                                    tint = SecondaryText,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryActionOrange,
                                unfocusedBorderColor = BorderGrey,
                                focusedContainerColor = SurfaceWhite,
                                unfocusedContainerColor = SurfaceWhite
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("handover_member_search_input")
                        )
                    }

                    if (filteredMembers.isEmpty()) {
                        Surface(
                            color = SurfaceWhite,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No matching active team members found.",
                                fontSize = 12.sp,
                                color = SecondaryText,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        filteredMembers.forEachIndexed { idx, member ->
                            val memberPhone10 = member.tenDigitMobile()
                            Surface(
                                color = SurfaceWhite,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("handover_member_card_$idx")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Initials Avatar
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(PrimaryActionOrange.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = member.initials(),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryActionOrange
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = member.name,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = member.roleAndDivisionLabel(),
                                            fontSize = 11.5.sp,
                                            color = SecondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (memberPhone10.length >= 7) {
                                            Text(
                                                text = "+91 $memberPhone10",
                                                fontSize = 10.5.sp,
                                                color = SecondaryText
                                            )
                                        } else {
                                            Text(
                                                text = "Tap Send to add WhatsApp mobile",
                                                fontSize = 10.5.sp,
                                                color = PrimaryActionOrange
                                            )
                                        }
                                    }

                                    // Green WhatsApp "Send" pill button
                                    Button(
                                        onClick = {
                                            if (memberPhone10.length >= 7) {
                                                onConfirmSendHandover(member, previewMessage)
                                            } else {
                                                memberPendingMobileInput = member
                                                enteredMobile = ""
                                            }
                                        },
                                        shape = RoundedCornerShape(24.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = WhatsAppBrandGreen,
                                            contentColor = Color.White
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("handover_send_btn_$idx")
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_whatsapp_logo),
                                            contentDescription = "Send WhatsApp handover to ${member.name}",
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Send",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 3. BOTTOM "AUTO-FILLED WHATSAPP MESSAGE" PREVIEW BOX ───────
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("handover_message_preview_box")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Auto-Filled WhatsApp Message",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = WhatsAppBrandDarkGreen
                        )
                        HorizontalDivider(color = BorderGrey.copy(alpha = 0.6f))
                        Surface(
                            color = SurfaceTint,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = previewMessage,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = TextPrimary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.heightIn(min = 8.dp))
            }
        }
    }

    // Fallback Dialog when an employee in cache doesn't yet have a synced mobile number
    memberPendingMobileInput?.let { targetMember ->
        AlertDialog(
            onDismissRequest = { memberPendingMobileInput = null },
            containerColor = SurfaceWhite,
            title = {
                Text(
                    text = "Add Mobile for ${targetMember.name}",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This team member has no mobile number cached from the Employees sheet yet. Enter their 10-digit WhatsApp number:",
                        fontSize = 12.5.sp,
                        color = SecondaryText
                    )
                    OutlinedTextField(
                        value = enteredMobile,
                        onValueChange = { raw -> enteredMobile = raw.filter { it.isDigit() }.take(10) },
                        label = { Text("10-Digit Mobile Number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = CrmCallManager.last10Digits(enteredMobile)
                        if (clean.length < 10) {
                            Toast.makeText(context, "Enter a valid 10-digit mobile number", Toast.LENGTH_SHORT).show()
                        } else {
                            val updatedMember = targetMember.copy(mobile = clean)
                            // Persist locally so subsequent handovers are 1-tap
                            val updatedDir = teamMembers.map {
                                if (it.name.equals(updatedMember.name, ignoreCase = true)) updatedMember else it
                            }
                            teamMembers = updatedDir
                            OfflineDiskCache.saveEmployeeDirectory(context, updatedDir)
                            memberPendingMobileInput = null
                            onConfirmSendHandover(updatedMember, previewMessage)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppBrandGreen, contentColor = Color.White),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Save & Send", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { memberPendingMobileInput = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HandoverSummaryMetaRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = SecondaryText
        )
        Text(
            text = value,
            fontSize = 11.5.sp,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
