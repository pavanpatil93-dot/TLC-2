package com.example.ui.shortlist

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.example.ai.AiPitchTone
import com.example.ai.GeminiProductivityEngine
import com.example.model.AvailabilityStatus
import com.example.model.CalculationUtils
import com.example.model.PropertyItem
import com.example.model.StandardTransactionType
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * UI state for the multi-property WhatsApp shortlist flow.
 * Preserves selection order via [selectedIds] and keeps [previewText] synchronized.
 */
data class ShortlistState(
    val selectedIds: List<String> = emptyList(),
    val selectedProperties: List<PropertyItem> = emptyList(),
    val employeeName: String = "",
    val previewText: String = "",
    val isCustomPreviewEdited: Boolean = false,
    val isReviewSheetOpen: Boolean = false
) {
    val selectedCount: Int get() = selectedProperties.size
}

/**
 * Area-based pricing metrics calculated strictly from valid positive numbers.
 * Never assumes loading percentages when built-up area is absent.
 */
data class ShortlistAreaRates(
    val transactionType: StandardTransactionType,
    val primaryAmount: Double?,
    val isRentalPrimary: Boolean,
    val ratePerCarpetSqFt: Double?,
    val ratePerBuiltUpSqFt: Double?,
    val preLeasedMonthlyRent: Double?
)

/**
 * Privacy-safe shortlist message builder & area-rate calculator.
 * Only reads allowlisted public fields; owner, broker, source, pipeline stage notes,
 * closure reasons, and internal notes are strictly excluded.
 */
object ShortlistMessageFormatter {
    const val DESK_NUMBERS = "9766880099 | 9766668099"

    private val englishSymbols = DecimalFormatSymbols(Locale.ENGLISH)
    private val areaDecimalFormat = DecimalFormat("#,##0.##", englishSymbols)

    fun calculateAreaRates(property: PropertyItem): ShortlistAreaRates {
        val txType = StandardTransactionType.fromString(property.transactionType, property.tab)
        val isRentalPrimary = txType == StandardTransactionType.LEASE

        val monthlyRent = when (txType) {
            StandardTransactionType.LEASE -> {
                property.rent?.takeIf { it.isFinite() && it > 0.0 }
                    ?: property.monthlyRentalIncome?.takeIf { it.isFinite() && it > 0.0 }
                    ?: property.priceOrRent.takeIf { it.isFinite() && it > 0.0 }
            }
            StandardTransactionType.PRE_LEASED_SALE -> {
                property.monthlyRentalIncome?.takeIf { it.isFinite() && it > 0.0 }
                    ?: property.rent?.takeIf { it.isFinite() && it > 0.0 }
            }
            StandardTransactionType.SALE -> null
        }

        val salePrice = when (txType) {
            StandardTransactionType.SALE, StandardTransactionType.PRE_LEASED_SALE -> {
                property.priceSale?.takeIf { it.isFinite() && it > 0.0 }
                    ?: property.priceOrRent.takeIf { it.isFinite() && it > 0.0 }
            }
            StandardTransactionType.LEASE -> null
        }

        val primaryAmount = if (isRentalPrimary) monthlyRent else salePrice
        val carpet = property.carpetAreaSqFt.takeIf { it.isFinite() && it > 0.0 }
        val builtUp = property.builtUpAreaSqFt?.takeIf { it.isFinite() && it > 0.0 }

        val carpetRate = if (primaryAmount != null && carpet != null) {
            (primaryAmount / carpet).takeIf { it.isFinite() && it > 0.0 }
        } else null

        val builtUpRate = if (primaryAmount != null && builtUp != null) {
            (primaryAmount / builtUp).takeIf { it.isFinite() && it > 0.0 }
        } else null

        return ShortlistAreaRates(
            transactionType = txType,
            primaryAmount = primaryAmount,
            isRentalPrimary = isRentalPrimary,
            ratePerCarpetSqFt = carpetRate,
            ratePerBuiltUpSqFt = builtUpRate,
            preLeasedMonthlyRent = if (txType == StandardTransactionType.PRE_LEASED_SALE) monthlyRent else null
        )
    }

    fun formatPrivacySafeShortlist(
        properties: List<PropertyItem>,
        employeeName: String
    ): String {
        val cleanEmployee = employeeName.trim().takeIf { name ->
            name.isNotBlank() &&
                !name.equals("Unregistered", true) &&
                !name.equals("Read-Only User", true) &&
                !name.equals("User", true)
        }

        val footerLines = buildList {
            if (cleanEmployee != null) add(cleanEmployee)
            add(DESK_NUMBERS)
        }

        val uniqueProperties = properties.distinctBy { it.id }
        if (uniqueProperties.isEmpty()) {
            return footerLines.joinToString("\n")
        }

        val sections = uniqueProperties.mapIndexed { index, property ->
            formatAllowlistedPropertyBlock(index = index + 1, property = property)
        }

        return buildString {
            append("*PROPERTY SHORTLIST (${uniqueProperties.size})*")
            append("\n\n")
            append(sections.joinToString("\n\n────────────\n\n"))
            append("\n\n")
            append(footerLines.joinToString("\n"))
        }
    }

    private fun formatAllowlistedPropertyBlock(index: Int, property: PropertyItem): String {
        val rates = calculateAreaRates(property)

        val code = property.propertyCode.trim().ifBlank { property.tab.code }
        val project = property.projectName.trim().ifBlank { "Not Mentioned" }
        val projectIdSuffix = property.projectId.trim().takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
        val location = buildList {
            property.location.trim().takeIf { it.isNotBlank() }?.let { add(it) }
            property.effectiveMicroMarket?.takeIf { !it.equals(property.location, ignoreCase = true) }?.let { add(it) }
        }.joinToString(" · ").ifBlank { "Not Mentioned" }
        val carpet = property.carpetAreaSqFt.takeIf { it.isFinite() && it > 0.0 }
        val builtUp = property.builtUpAreaSqFt?.takeIf { it.isFinite() && it > 0.0 }
        val furnishing = property.furnishing.label.ifBlank { "Not Mentioned" }
        val status = property.status.label

        val lines = mutableListOf<String>()
        lines.add("*$index. $code · $project$projectIdSuffix*")
        lines.add("Location: $location")

        val areaParts = buildList {
            if (carpet != null) add("Carpet ${areaDecimalFormat.format(carpet)} sq.ft")
            if (builtUp != null) add("Built-up ${areaDecimalFormat.format(builtUp)} sq.ft")
        }
        lines.add("Area: ${if (areaParts.isNotEmpty()) areaParts.joinToString(" | ") else "Not Mentioned"}")
        lines.add("Furnishing: $furnishing")

        val commercialSpecs = buildList {
            property.attachedPantry?.takeIf { it.isNotBlank() }?.let { add("Pantry: $it") }
            property.attachedWashroom?.takeIf { it.isNotBlank() }?.let { add("Washroom: $it") }
            property.effectiveFrontageFt?.takeIf { it > 0.0 }?.let { add("Frontage: ${areaDecimalFormat.format(it)} ft") }
            property.effectiveClearHeightFt?.takeIf { it > 0.0 }?.let { add("Clear Height: ${areaDecimalFormat.format(it)} ft") }
            property.truckAccess?.takeIf { it.isNotBlank() }?.let { add("Truck Access: $it") }
            property.powerLoad?.takeIf { it.isNotBlank() }?.let { add("Power Load: $it") }
        }
        if (commercialSpecs.isNotEmpty()) {
            lines.add("Specs: ${commercialSpecs.joinToString(" | ")}")
        }
        property.targetPossessionDate?.takeIf { it.isNotBlank() }?.let {
            lines.add("Target Possession: $it")
        }

        val priceLabel = if (rates.isRentalPrimary) "Monthly Rent" else "Sale Price"
        val formattedPrice = rates.primaryAmount?.let { amount ->
            CalculationUtils.formatIndianCurrency(amount, isMonthly = rates.isRentalPrimary)
        } ?: "Not Mentioned"
        lines.add("$priceLabel: $formattedPrice")

        if (rates.preLeasedMonthlyRent != null) {
            lines.add(
                "Monthly Rental Income: ${
                    CalculationUtils.formatIndianCurrency(rates.preLeasedMonthlyRent, isMonthly = true)
                }"
            )
        }
        val grossRoi = property.roiPercent?.takeIf { it > 0.0 }
            ?: com.example.data.TlcPropertyCalculator.calculateGrossRoiPercent(
                rates.preLeasedMonthlyRent ?: property.rent ?: property.monthlyRentalIncome,
                rates.primaryAmount.takeIf { !rates.isRentalPrimary }
            )
        if (property.effectiveIsPreLeased && grossRoi != null) {
            lines.add(String.format(java.util.Locale.US, "Gross ROI: %.2f%% p.a.", grossRoi))
        }

        val unitSuffix = if (rates.isRentalPrimary) "/sq.ft/mo" else "/sq.ft"
        rates.ratePerCarpetSqFt?.let { rate ->
            lines.add("Carpet Rate: ${CalculationUtils.formatIndianCurrencyFull(rate)}$unitSuffix")
        }
        rates.ratePerBuiltUpSqFt?.let { rate ->
            lines.add("Built-up Rate: ${CalculationUtils.formatIndianCurrencyFull(rate)}$unitSuffix")
        }

        lines.add("Availability: $status")
        return lines.joinToString("\n")
    }
}

/**
 * Self-contained ViewModel for managing multi-property selection, ordering, rate formatting, and WhatsApp sharing.
 */
class ShortlistViewModel(
    initialInventory: List<PropertyItem> = emptyList(),
    initialEmployeeName: String = ""
) : ViewModel() {

    private var inventoryById: Map<String, PropertyItem> = initialInventory.associateBy { it.id }

    private val _state = MutableStateFlow(
        ShortlistState(
            employeeName = initialEmployeeName.trim(),
            previewText = ShortlistMessageFormatter.formatPrivacySafeShortlist(
                properties = emptyList(),
                employeeName = initialEmployeeName
            )
        )
    )
    val state: StateFlow<ShortlistState> = _state.asStateFlow()

    fun updateInventory(properties: List<PropertyItem>) {
        inventoryById = properties.associateBy { it.id }
        recomputeState { it }
    }

    fun updateEmployeeName(name: String) {
        val clean = name.trim()
        if (clean == _state.value.employeeName) return
        recomputeState { current -> current.copy(employeeName = clean, isCustomPreviewEdited = false) }
    }

    fun toggleSelection(propertyId: String) {
        val cleanId = propertyId.trim()
        if (cleanId.isBlank()) return

        recomputeState { current ->
            val nextIds = current.selectedIds.toMutableList()
            if (nextIds.contains(cleanId)) {
                nextIds.remove(cleanId)
            } else if (inventoryById.containsKey(cleanId)) {
                nextIds.add(cleanId)
            }
            current.copy(
                selectedIds = nextIds,
                isCustomPreviewEdited = false,
                isReviewSheetOpen = if (nextIds.isEmpty()) false else current.isReviewSheetOpen
            )
        }
    }

    fun removeProperty(propertyId: String) {
        val cleanId = propertyId.trim()
        recomputeState { current ->
            val nextIds = current.selectedIds.filterNot { it == cleanId }
            current.copy(
                selectedIds = nextIds,
                isCustomPreviewEdited = false,
                isReviewSheetOpen = if (nextIds.isEmpty()) false else current.isReviewSheetOpen
            )
        }
    }

    fun movePropertyUp(propertyId: String) {
        val cleanId = propertyId.trim()
        recomputeState { current ->
            val idx = current.selectedIds.indexOf(cleanId)
            if (idx <= 0) return@recomputeState current
            val nextIds = current.selectedIds.toMutableList()
            val item = nextIds.removeAt(idx)
            nextIds.add(idx - 1, item)
            current.copy(selectedIds = nextIds, isCustomPreviewEdited = false)
        }
    }

    fun movePropertyDown(propertyId: String) {
        val cleanId = propertyId.trim()
        recomputeState { current ->
            val idx = current.selectedIds.indexOf(cleanId)
            if (idx < 0 || idx >= current.selectedIds.lastIndex) return@recomputeState current
            val nextIds = current.selectedIds.toMutableList()
            val item = nextIds.removeAt(idx)
            nextIds.add(idx + 1, item)
            current.copy(selectedIds = nextIds, isCustomPreviewEdited = false)
        }
    }

    fun updatePreviewText(editedText: String) {
        _state.update { current ->
            current.copy(
                previewText = editedText,
                isCustomPreviewEdited = true
            )
        }
    }

    fun resetPreviewText() {
        recomputeState { current -> current.copy(isCustomPreviewEdited = false) }
    }

    fun clearShortlist() {
        recomputeState { current ->
            current.copy(
                selectedIds = emptyList(),
                isCustomPreviewEdited = false,
                isReviewSheetOpen = false
            )
        }
    }

    fun openReviewSheet() {
        if (_state.value.selectedProperties.isEmpty()) return
        _state.update { it.copy(isReviewSheetOpen = true) }
    }

    fun closeReviewSheet() {
        _state.update { it.copy(isReviewSheetOpen = false) }
    }

    fun copyShortlistToClipboard(context: Context): Boolean {
        val text = _state.value.previewText
        if (text.isBlank()) return false
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@runCatching false
            clipboard.setPrimaryClip(ClipData.newPlainText("Property Shortlist", text))
            true
        }.getOrDefault(false)
    }

    fun buildWhatsAppShareIntent(context: Context, text: String = _state.value.previewText): Intent {
        val pm = context.packageManager
        val hasPersonal = canResolvePackage(pm, WHATSAPP_PACKAGE, text)
        val hasBusiness = canResolvePackage(pm, WHATSAPP_BUSINESS_PACKAGE, text)

        val intent = when {
            hasPersonal && hasBusiness -> {
                val personal = createSendIntent(text, WHATSAPP_PACKAGE)
                val business = createSendIntent(text, WHATSAPP_BUSINESS_PACKAGE)
                Intent.createChooser(personal, "Share shortlist to WhatsApp").apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(business))
                }
            }
            hasPersonal -> createSendIntent(text, WHATSAPP_PACKAGE)
            hasBusiness -> createSendIntent(text, WHATSAPP_BUSINESS_PACKAGE)
            else -> Intent.createChooser(createSendIntent(text, null), "Share property shortlist")
        }

        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }

    fun shareToWhatsApp(context: Context) {
        val text = _state.value.previewText
        if (text.isBlank() || _state.value.selectedProperties.isEmpty()) return

        val intent = buildWhatsAppShareIntent(context, text)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No sharing app available on this device", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Unable to open share sheet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun recomputeState(transform: (ShortlistState) -> ShortlistState) {
        _state.update { current ->
            val updated = transform(current)
            val validIds = updated.selectedIds.distinct().filter { inventoryById.containsKey(it) }
            val resolvedProperties = validIds.mapNotNull { inventoryById[it] }
            val generatedPreview = ShortlistMessageFormatter.formatPrivacySafeShortlist(
                properties = resolvedProperties,
                employeeName = updated.employeeName
            )
            updated.copy(
                selectedIds = validIds,
                selectedProperties = resolvedProperties,
                previewText = if (updated.isCustomPreviewEdited && updated.previewText.isNotBlank()) {
                    updated.previewText
                } else {
                    generatedPreview
                },
                isReviewSheetOpen = if (resolvedProperties.isEmpty()) false else updated.isReviewSheetOpen
            )
        }
    }

    private fun createSendIntent(text: String, targetPackage: String?): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!targetPackage.isNullOrBlank()) {
                setPackage(targetPackage)
            }
        }
    }

    private fun canResolvePackage(pm: PackageManager, packageName: String, text: String): Boolean {
        val probe = createSendIntent(text, packageName)
        return runCatching {
            @Suppress("DEPRECATION")
            pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
    }

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    }
}

@Composable
fun ShortlistPinnedBottomBar(
    selectedCount: Int,
    onReviewShortlist: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedCount <= 0) return

    val countLabel = if (selectedCount == 1) {
        "Selected: 1 property"
    } else {
        "Selected: $selectedCount properties"
    }

    Surface(
        color = SurfaceWhite,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, BorderGrey),
        modifier = modifier
            .fillMaxWidth()
            .testTag("shortlist_pinned_bottom_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = countLabel,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("shortlist_selected_count_text")
            )

            Text(
                text = "|",
                color = BorderGrey,
                fontSize = 14.sp
            )

            Button(
                onClick = onReviewShortlist,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("shortlist_review_btn")
            ) {
                Text(
                    text = "Review Shortlist",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Text(
                text = "|",
                color = BorderGrey,
                fontSize = 14.sp
            )

            TextButton(
                onClick = onClear,
                colors = ButtonDefaults.textButtonColors(contentColor = SecondaryText),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("shortlist_clear_btn")
            ) {
                Text(
                    text = "Clear",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortlistReviewBottomSheet(
    state: ShortlistState,
    onRemoveProperty: (String) -> Unit,
    onCopyText: () -> Boolean,
    onShareWhatsApp: () -> Unit,
    onDismiss: () -> Unit,
    onMovePropertyUp: ((String) -> Unit)? = null,
    onMovePropertyDown: ((String) -> Unit)? = null,
    onUpdatePreviewText: ((String) -> Unit)? = null,
    onResetPreviewText: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var copiedConfirmed by remember(state.previewText) { mutableStateOf(false) }
    var isEditingDraft by remember { mutableStateOf(false) }
    var selectedAiTone by remember { mutableStateOf(AiPitchTone.EXECUTIVE) }
    var isDraftingAi by remember { mutableStateOf(false) }
    var aiStatusNote by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundSoft,
        dragHandle = { BottomSheetDefaults.DragHandle(color = SecondaryText.copy(alpha = 0.4f)) },
        modifier = Modifier.testTag("shortlist_review_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Review WhatsApp Shortlist (${state.selectedCount})",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Client-safe summary • Reorder or edit before sharing",
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("shortlist_sheet_close_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close shortlist review",
                        tint = TextPrimary
                    )
                }
            }

            Text(
                text = "SELECTED PROPERTIES",
                color = SecondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .testTag("shortlist_summary_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(state.selectedProperties, key = { _, item -> item.id }) { idx, prop ->
                    ShortlistSummaryCard(
                        index = idx + 1,
                        totalCount = state.selectedProperties.size,
                        property = prop,
                        onMoveUp = if (onMovePropertyUp != null && idx > 0) {
                            { onMovePropertyUp(prop.id) }
                        } else null,
                        onMoveDown = if (onMovePropertyDown != null && idx < state.selectedProperties.lastIndex) {
                            { onMovePropertyDown(prop.id) }
                        } else null,
                        onRemove = { onRemoveProperty(prop.id) }
                    )
                }
            }

            if (onUpdatePreviewText != null && state.selectedProperties.isNotEmpty()) {
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BrandGold.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shortlist_ai_tone_bar")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = BrandGold,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Gemini Shortlist Tone",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TextPrimary
                                )
                            }
                            if (isDraftingAi) {
                                CircularProgressIndicator(
                                    color = PrimaryActionOrange,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AiPitchTone.entries.forEach { tone ->
                                FilterChip(
                                    selected = selectedAiTone == tone,
                                    onClick = {
                                        selectedAiTone = tone
                                        if (!isDraftingAi) {
                                            isDraftingAi = true
                                            aiStatusNote = null
                                            coroutineScope.launch {
                                                val draft = GeminiProductivityEngine.draftPropertySharePitch(
                                                    properties = state.selectedProperties,
                                                    tone = tone,
                                                    audienceLabel = "Client"
                                                )
                                                onUpdatePreviewText(draft.text)
                                                aiStatusNote = draft.statusNote
                                                isDraftingAi = false
                                            }
                                        }
                                    },
                                    label = { Text(tone.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                                    modifier = Modifier.testTag("shortlist_tone_${tone.name.lowercase(java.util.Locale.ENGLISH)}")
                                )
                            }
                        }
                        if (!aiStatusNote.isNullOrBlank()) {
                            Text(
                                text = aiStatusNote.orEmpty(),
                                fontSize = 10.sp,
                                color = SecondaryText
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE WHATSAPP MESSAGE PREVIEW",
                    color = SecondaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
                if (onUpdatePreviewText != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.isCustomPreviewEdited && onResetPreviewText != null) {
                            TextButton(
                                onClick = {
                                    onResetPreviewText()
                                    isEditingDraft = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.heightIn(min = 32.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reset", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        TextButton(
                            onClick = { isEditingDraft = !isEditingDraft },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .testTag("shortlist_edit_draft_toggle")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isEditingDraft) "Done Editing" else "Edit Draft",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryActionOrange
                            )
                        }
                    }
                }
            }

            if (isEditingDraft && onUpdatePreviewText != null) {
                OutlinedTextField(
                    value = state.previewText,
                    onValueChange = onUpdatePreviewText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 210.dp)
                        .testTag("shortlist_preview_box"),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite,
                        focusedBorderColor = PrimaryActionOrange,
                        unfocusedBorderColor = BorderGrey
                    )
                )
            } else {
                Surface(
                    color = SurfaceWhite,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp, max = 210.dp)
                        .testTag("shortlist_preview_box")
                ) {
                    Text(
                        text = state.previewText,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                            .testTag("shortlist_preview_text")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        val copied = onCopyText()
                        copiedConfirmed = copied
                        Toast.makeText(
                            context,
                            if (copied) "Shortlist copied to clipboard" else "Unable to copy shortlist",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SurfaceWhite,
                        contentColor = TextPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("shortlist_copy_btn")
                ) {
                    Icon(
                        imageVector = if (copiedConfirmed) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (copiedConfirmed) "Copied" else "Copy",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onShareWhatsApp,
                    enabled = state.selectedProperties.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    modifier = Modifier
                        .weight(1.4f)
                        .heightIn(min = 48.dp)
                        .testTag("shortlist_share_whatsapp_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Share to WhatsApp",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortlistSummaryCard(
    index: Int,
    totalCount: Int,
    property: PropertyItem,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onRemove: () -> Unit
) {
    val rates = remember(property) { ShortlistMessageFormatter.calculateAreaRates(property) }
    val priceText = rates.primaryAmount?.let {
        CalculationUtils.formatIndianCurrency(it, isMonthly = rates.isRentalPrimary)
    } ?: "Not Mentioned"
    val rateText = rates.ratePerCarpetSqFt?.let { rate ->
        val unit = if (rates.isRentalPrimary) "/sq.ft/mo" else "/sq.ft"
        "${CalculationUtils.formatIndianCurrencyFull(rate)}$unit"
    }

    val statusColor = when (property.status) {
        AvailabilityStatus.AVAILABLE -> StatusAvailable
        AvailabilityStatus.SOLD,
        AvailabilityStatus.LEASED,
        AvailabilityStatus.WITHDRAWN,
        AvailabilityStatus.NOT_AVAILABLE -> StatusNotAvailable
        else -> StatusCheckAvailability
    }

    Surface(
        color = SurfaceWhite,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderGrey),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shortlist_item_card_${property.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = SelectedSurfaceContainer,
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            text = "#$index ${property.propertyCode.ifBlank { property.tab.code }}",
                            color = PrimaryActionOrange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        color = statusColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            text = property.status.label,
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = property.projectName.ifBlank { "Project Not Mentioned" },
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = PrimaryActionOrange,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = property.location.ifBlank { "Location Not Mentioned" },
                        color = SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val metaSummary = buildList {
                    if (property.carpetAreaSqFt > 0.0) {
                        add("${property.carpetAreaSqFt.toInt()} sq.ft")
                    }
                    add(property.furnishing.label)
                    add(priceText)
                    if (rateText != null) add(rateText)
                }.joinToString(" • ")

                Text(
                    text = metaSummary,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (totalCount > 1 && (onMoveUp != null || onMoveDown != null)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { onMoveUp?.invoke() },
                        enabled = onMoveUp != null,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("shortlist_move_up_${property.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Move up",
                            tint = if (onMoveUp != null) TextPrimary else SecondaryText.copy(alpha = 0.35f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = { onMoveDown?.invoke() },
                        enabled = onMoveDown != null,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("shortlist_move_down_${property.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Move down",
                            tint = if (onMoveDown != null) TextPrimary else SecondaryText.copy(alpha = 0.35f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("shortlist_remove_item_${property.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove from shortlist",
                    tint = SecondaryText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
