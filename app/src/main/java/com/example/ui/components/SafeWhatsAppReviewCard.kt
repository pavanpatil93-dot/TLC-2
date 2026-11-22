package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.ConfidenceTier
import com.example.ai.ExtractedField
import com.example.ai.ExtractedPropertyDraft
import com.example.ai.WhatsAppExtractionBatchResult
import com.example.ai.WhatsAppMessageClassification
import com.example.model.PropertyItem
import com.example.ui.theme.*

private val ConfidenceHighGreen = Color(0xFF16A34A)
private val ConfidenceReviewAmber = Color(0xFFD97706)
private val ConfidenceConfirmRed = Color(0xFFDC2626)

@Composable
fun SafeWhatsAppReviewPanel(
    result: WhatsAppExtractionBatchResult,
    onCheckDuplicates: (String, String, String) -> List<PropertyItem>,
    onConfirmApplyDraft: (ExtractedPropertyDraft) -> Unit,
    onRouteClientRequirementToCrm: ((String) -> Unit)?,
    onSwitchToManualEntry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedDraftIndex by remember(result) { mutableIntStateOf(0) }
    val activeDraft = result.drafts.getOrNull(selectedDraftIndex)
    var flaggedConfirmed by remember(result, selectedDraftIndex) { mutableStateOf(false) }

    val draftDuplicates = remember(activeDraft) {
        if (activeDraft != null && activeDraft.projectName.isNotBlank() && activeDraft.unitNumber.isNotBlank()) {
            onCheckDuplicates("", activeDraft.projectName, activeDraft.unitNumber)
        } else {
            emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("whatsapp_safe_review_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Offline / AI fallback notice (graceful degradation) ──
        if (!result.errorMessage.isNullOrBlank()) {
            Surface(
                color = Color(0xFFFFF7ED),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ConfidenceReviewAmber.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = "Offline parser active",
                        tint = ConfidenceReviewAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = result.errorMessage,
                        fontSize = 11.5.sp,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Step A: Classification Banner (Blocks CLIENT_REQUIREMENT & UNRELATED) ──
        if (result.blocksAddPropertyForm) {
            val isRequirement = result.classification == WhatsAppMessageClassification.CLIENT_REQUIREMENT
            val accent = if (isRequirement) PrimaryActionOrange else ConfidenceConfirmRed
            Surface(
                color = SurfaceWhite,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.5.dp, accent.copy(alpha = 0.65f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("whatsapp_blocked_classification_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isRequirement) Icons.Default.PersonSearch else Icons.Default.Block,
                            contentDescription = result.classification.label,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isRequirement) {
                                    "Client Requirement Detected"
                                } else {
                                    "Unrelated Message — Not a Listing"
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Blocked from auto-populating Property Inventory (${result.classificationConfidence}% confidence).",
                                fontSize = 11.5.sp,
                                color = SecondaryText
                            )
                        }
                    }

                    Text(
                        text = result.classificationReason,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isRequirement && onRouteClientRequirementToCrm != null) {
                            Button(
                                onClick = { onRouteClientRequirementToCrm(result.rawMessage) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryActionOrange,
                                    contentColor = OnPrimaryActionColor
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("route_requirement_to_crm_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = "Route to CRM",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Route to CRM Instead", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = onSwitchToManualEntry,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("blocked_switch_manual_btn")
                        ) {
                            Text("Manual Entry", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
            return
        }

        // ── Classification Header Badge for Allowed / Reviewable Classes ──
        val headerAccent = when (result.classification) {
            WhatsAppMessageClassification.PROPERTY_LISTING -> ConfidenceHighGreen
            WhatsAppMessageClassification.EXISTING_PROPERTY_UPDATE -> ConfidenceReviewAmber
            else -> ConfidenceConfirmRed
        }
        Surface(
            color = headerAccent.copy(alpha = 0.08f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, headerAccent.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (result.classification) {
                        WhatsAppMessageClassification.PROPERTY_LISTING -> Icons.Default.Verified
                        WhatsAppMessageClassification.EXISTING_PROPERTY_UPDATE -> Icons.Default.Update
                        else -> Icons.Default.Warning
                    },
                    contentDescription = result.classification.label,
                    tint = headerAccent,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${result.classification.label} • ${result.drafts.size} ${if (result.drafts.size == 1) "property" else "properties"} found",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = result.classificationReason,
                        fontSize = 11.sp,
                        color = SecondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── Multi-Property Switcher (if > 1 property detected) ──
        if (result.drafts.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("multi_property_switcher"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                result.drafts.forEachIndexed { idx, draft ->
                    val selected = idx == selectedDraftIndex
                    val chipSummary = listOfNotNull(
                        draft.propertyType.takeIf { it.isNotBlank() } ?: draft.tab.code,
                        draft.projectName.takeIf { it.isNotBlank() } ?: draft.location.takeIf { it.isNotBlank() }
                    ).joinToString(" • ").ifBlank { "Draft" }
                    FilterChip(
                        selected = selected,
                        onClick = { selectedDraftIndex = idx },
                        label = {
                            Text(
                                text = "#${idx + 1}: $chipSummary",
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryActionOrange.copy(alpha = 0.15f),
                            selectedLabelColor = PrimaryActionOrange
                        ),
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .testTag("multi_property_chip_$idx")
                    )
                }
            }
        }

        if (activeDraft == null) {
            OutlinedButton(
                onClick = onSwitchToManualEntry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("No extractable property found — Enter Manually", color = TextPrimary)
            }
            return
        }

        // ── Active Draft Review Card ──
        Surface(
            color = SurfaceWhite,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderGrey),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("whatsapp_draft_review_card")
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
                    Text(
                        text = "Extracted Fields Review",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Surface(
                        color = PrimaryActionOrange.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = activeDraft.tab.code,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryActionOrange,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // Confidence Legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ConfidenceLegendDot(ConfidenceHighGreen, "90-100 High")
                    ConfidenceLegendDot(ConfidenceReviewAmber, "70-89 Review")
                    ConfidenceLegendDot(ConfidenceConfirmRed, "<70 Confirm")
                }

                HorizontalDivider(color = BorderGrey.copy(alpha = 0.7f))

                // Original snippet evidence box
                if (activeDraft.rawSnippet.isNotBlank()) {
                    Surface(
                        color = BackgroundSoft,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.5.dp, BorderGrey),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Source Message Snippet",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = SecondaryText
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = activeDraft.rawSnippet,
                                fontSize = 11.5.sp,
                                color = TextPrimary,
                                fontStyle = FontStyle.Italic,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Duplicate warning inside review
                if (draftDuplicates.isNotEmpty()) {
                    Surface(
                        color = ConfidenceConfirmRed.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ConfidenceConfirmRed.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Potential Duplicate",
                                tint = ConfidenceConfirmRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Possible duplicate in inventory: ${draftDuplicates.first().propertyCode} (${draftDuplicates.first().projectName} ${draftDuplicates.first().unitNumber})",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ConfidenceConfirmRed
                            )
                        }
                    }
                }

                // Review Warnings
                if (activeDraft.reviewWarnings.isNotEmpty()) {
                    Surface(
                        color = Color(0xFFFEF2F2),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ConfidenceConfirmRed.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Requires Confirmation",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = ConfidenceConfirmRed
                            )
                            activeDraft.reviewWarnings.forEach { warning ->
                                Text(
                                    text = "• $warning",
                                    fontSize = 11.5.sp,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }

                // Field-level Evidence Rows
                if (activeDraft.extractedFields.isEmpty()) {
                    Text(
                        text = "No confident fields extracted. Fill details manually.",
                        fontSize = 12.sp,
                        color = SecondaryText
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        activeDraft.extractedFields.forEach { (label, field) ->
                            ExtractedFieldEvidenceRow(label = label, field = field)
                        }
                    }
                }

                // Missing Required Fields List
                if (activeDraft.missingFields.isNotEmpty()) {
                    Surface(
                        color = Color(0xFFFFFBEB),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ConfidenceReviewAmber.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Missing Fields (left blank — enter manually):",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ConfidenceReviewAmber
                            )
                            Text(
                                text = activeDraft.missingFields.joinToString(" • "),
                                fontSize = 11.5.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // Mandatory Flagged-Fields Confirmation Checkbox
                val needsExplicitFlagAck = activeDraft.hasFlaggedFields
                if (needsExplicitFlagAck) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = flaggedConfirmed,
                            onCheckedChange = { flaggedConfirmed = it },
                            modifier = Modifier.testTag("confirm_flagged_fields_checkbox")
                        )
                        Text(
                            text = "I reviewed the amber/red flagged fields and evidence before filling the form.",
                            fontSize = 11.5.sp,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Action Row: Confirm & Fill Form (NEVER auto-saves!) + Manual Entry
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onSwitchToManualEntry,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderGrey),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("review_manual_entry_btn")
                    ) {
                        Text("Manual", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { onConfirmApplyDraft(activeDraft) },
                        enabled = !needsExplicitFlagAck || flaggedConfirmed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("confirm_populate_form_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Confirm and Populate Form",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (result.drafts.size > 1) {
                                "Confirm & Fill Property #${selectedDraftIndex + 1}"
                            } else {
                                "Confirm & Populate Form"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfidenceLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            color = color,
            shape = RoundedCornerShape(50),
            modifier = Modifier.size(8.dp)
        ) {}
        Text(text = label, fontSize = 10.sp, color = SecondaryText, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ExtractedFieldEvidenceRow(
    label: String,
    field: ExtractedField<String>
) {
    val badgeColor = when (field.tier) {
        ConfidenceTier.HIGH -> ConfidenceHighGreen
        ConfidenceTier.REVIEW -> ConfidenceReviewAmber
        ConfidenceTier.CONFIRM_REQUIRED -> ConfidenceConfirmRed
    }
    Surface(
        color = BackgroundSoft.copy(alpha = 0.65f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.5.dp, badgeColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondaryText
                )
                Surface(
                    color = badgeColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "${field.confidence}% • ${field.tier.label}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = field.value.orEmpty(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            if (!field.evidence.isNullOrBlank()) {
                Text(
                    text = "Evidence: \"${field.evidence}\"",
                    fontSize = 10.5.sp,
                    fontStyle = FontStyle.Italic,
                    color = SecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
