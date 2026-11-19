package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.UserSettingsManager
import com.example.model.*
import com.example.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ShareAudienceMode(val label: String) {
    CLIENT("Client"),
    BROKER("Broker"),
    FB_MARKETPLACE("FB Marketplace Ad")
}

object WhatsAppIntentRouter {
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    fun buildShareIntent(context: Context, text: String): Intent {
        val pm = context.packageManager
        val hasPersonal = canResolvePackage(pm, WHATSAPP_PACKAGE, text)
        val hasBusiness = canResolvePackage(pm, WHATSAPP_BUSINESS_PACKAGE, text)

        return when {
            hasPersonal && hasBusiness -> {
                val pIntent = createPlainTextSendIntent(text, WHATSAPP_PACKAGE)
                val bIntent = createPlainTextSendIntent(text, WHATSAPP_BUSINESS_PACKAGE)
                Intent.createChooser(pIntent, "Share to WhatsApp").apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(bIntent))
                }
            }
            hasPersonal -> createPlainTextSendIntent(text, WHATSAPP_PACKAGE)
            hasBusiness -> createPlainTextSendIntent(text, WHATSAPP_BUSINESS_PACKAGE)
            else -> {
                val plainIntent = createPlainTextSendIntent(text, null)
                Intent.createChooser(plainIntent, "Share Listing")
            }
        }
    }

    fun launchWhatsAppSafely(context: Context, text: String, recipientPhone: String? = null, onError: (String) -> Unit) {
        val cleanPhone = recipientPhone?.filter { it.isDigit() }?.takeIf { it.length >= 10 }
        if (!cleanPhone.isNullOrBlank()) {
            val phone = if (cleanPhone.length == 10) "91$cleanPhone" else cleanPhone
            val encoded = Uri.encode(text)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=$encoded"))
            val launched = runCatching { context.startActivity(intent) }.isSuccess
            if (launched) return
        }

        val pm = context.packageManager
        val hasPersonal = canResolvePackage(pm, WHATSAPP_PACKAGE, text)
        val hasBusiness = canResolvePackage(pm, WHATSAPP_BUSINESS_PACKAGE, text)

        val intent = when {
            hasPersonal && hasBusiness -> {
                val pIntent = createPlainTextSendIntent(text, WHATSAPP_PACKAGE)
                val bIntent = createPlainTextSendIntent(text, WHATSAPP_BUSINESS_PACKAGE)
                Intent.createChooser(pIntent, "Share to WhatsApp").apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(bIntent))
                }
            }
            hasPersonal -> createPlainTextSendIntent(text, WHATSAPP_PACKAGE)
            hasBusiness -> createPlainTextSendIntent(text, WHATSAPP_BUSINESS_PACKAGE)
            else -> {
                // Fallback copy to clipboard and toast
                runCatching {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Property Share", text))
                }
                onError("WhatsApp is not installed — message copied to clipboard")
                return
            }
        }

        try {
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Property Share", text))
            }
            onError("WhatsApp is not installed — message copied to clipboard")
        } catch (_: Exception) {
            onError("Unable to open WhatsApp")
        }
    }

    private fun createPlainTextSendIntent(text: String, targetPackage: String?): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!targetPackage.isNullOrBlank()) {
                setPackage(targetPackage)
            }
        }
    }

    private fun canResolvePackage(pm: PackageManager, packageName: String, text: String): Boolean {
        val probe = createPlainTextSendIntent(text, packageName)
        return runCatching {
            @Suppress("DEPRECATION")
            pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppShareDialog(
    property: PropertyItem? = null,
    properties: List<PropertyItem> = emptyList(),
    project: UnderConstProject? = null,
    customMessage: String? = null,
    recipientPhone: String? = null,
    senderName: String? = null,
    userSettingsManager: UserSettingsManager? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val normalizedProperties = remember(property, properties) {
        val list = if (properties.isNotEmpty()) properties else listOfNotNull(property)
        list.distinctBy { it.id }
    }

    var audienceMode by rememberSaveable { mutableStateOf(ShareAudienceMode.CLIENT) }
    var conciseMode by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val userProfile = userSettingsManager?.userProfile?.collectAsState()?.value
    val employeeName = senderName ?: userProfile?.username?.takeIf { it.isNotBlank() } ?: "TLC Executive"
    val employeeMobile = userProfile?.mobileNumber.orEmpty()

    // Generate template message
    val generatedText = remember(normalizedProperties, project, customMessage, audienceMode, conciseMode, employeeName, employeeMobile) {
        when {
            !customMessage.isNullOrBlank() -> customMessage
            normalizedProperties.isNotEmpty() -> buildMultiPropertyShareMessage(normalizedProperties, audienceMode, conciseMode, employeeName, employeeMobile)
            project != null -> buildProjectShareMessage(project, employeeName, employeeMobile)
            else -> "For details & site visits: 📞 9766880099 | 9766668099"
        }
    }

    var shareText by remember(generatedText) { mutableStateOf(generatedText) }
    var copiedConfirmed by remember { mutableStateOf(false) }
    var feedbackError by remember { mutableStateOf<String?>(null) }
    var isGeneratingPdf by remember { mutableStateOf(false) }

    // Live Leak Guard: check if shareText contains any sensitive owner/broker info from normalizedProperties
    val hasSensitiveLeak = remember(shareText, normalizedProperties) {
        normalizedProperties.any { prop ->
            val owner = prop.ownerName?.trim().orEmpty()
            val ownerPh = prop.ownerContact?.trim().orEmpty()
            val broker = prop.brokerName?.trim().orEmpty()
            val brokerPh = prop.brokerContact?.trim().orEmpty()
            (owner.isNotBlank() && owner.length > 2 && shareText.contains(owner, true)) ||
                (ownerPh.isNotBlank() && ownerPh.length > 5 && shareText.contains(ownerPh, true)) ||
                (broker.isNotBlank() && broker.length > 2 && shareText.contains(broker, true)) ||
                (brokerPh.isNotBlank() && brokerPh.length > 5 && shareText.contains(brokerPh, true))
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundSoft,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextPrimary) },
        modifier = Modifier.testTag("whatsapp_share_dialog")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "WhatsApp Share Workflow",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (normalizedProperties.size > 1) "${normalizedProperties.size} Properties Selected" else "Client-Safe Professional Pitch",
                        color = PrimaryActionOrange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Audience Mode Selector & Concise Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = SurfaceMuted,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    val availableModes = remember(normalizedProperties.size) {
                        if (normalizedProperties.isNotEmpty()) {
                            ShareAudienceMode.entries
                        } else {
                            listOf(ShareAudienceMode.CLIENT, ShareAudienceMode.BROKER)
                        }
                    }
                    Row(modifier = Modifier.padding(2.dp)) {
                        availableModes.forEach { mode ->
                            val selected = audienceMode == mode
                            Surface(
                                color = if (selected) PrimaryActionOrange else Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .clickable { audienceMode = mode }
                                    .testTag("share_mode_tab_${mode.name.lowercase()}")
                            ) {
                                Text(
                                    text = mode.label,
                                    color = if (selected) OnPrimaryActionColor else TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                if (audienceMode != ShareAudienceMode.FB_MARKETPLACE && (normalizedProperties.size >= 3 || shareText.length > 1500)) {
                    FilterChipItem(
                        label = "Concise Mode",
                        isSelected = conciseMode,
                        onClick = { conciseMode = !conciseMode }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (audienceMode == ShareAudienceMode.FB_MARKETPLACE && normalizedProperties.isNotEmpty()) {
                FbMarketplaceAdPanel(
                    property = normalizedProperties.first(),
                    employeeName = employeeName,
                    employeeMobile = employeeMobile,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Upper 1-Tap Action Bar right below Client | Broker toggle (above Editable Message Preview)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BackgroundSoft)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Property Share", shareText))
                            copiedConfirmed = true
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .weight(0.85f)
                            .heightIn(min = 48.dp)
                            .testTag("whatsapp_share_copy_btn"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, FocusOutlineColor)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = FocusOutlineColor, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = FocusOutlineColor, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            if (isGeneratingPdf) return@OutlinedButton
                            isGeneratingPdf = true
                            feedbackError = null
                            coroutineScope.launch {
                                val result = TlcProposalPdfGenerator.generateAndShareProposalPdf(
                                    context = context,
                                    properties = normalizedProperties,
                                    project = project,
                                    shareBodyText = shareText,
                                    employeeName = employeeName,
                                    employeeMobile = employeeMobile
                                )
                                isGeneratingPdf = false
                                if (result.isFailure) {
                                    feedbackError = result.exceptionOrNull()?.localizedMessage ?: "Failed to generate PDF proposal"
                                }
                            }
                        },
                        enabled = !isGeneratingPdf,
                        modifier = Modifier
                            .weight(1.35f)
                            .heightIn(min = 48.dp)
                            .testTag("export_branded_pdf_proposal_btn"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, PrimaryActionOrange)
                    ) {
                        if (isGeneratingPdf) {
                            CircularProgressIndicator(
                                color = PrimaryActionOrange,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isGeneratingPdf) "Preparing PDF..." else "Share PDF Proposal",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = {
                            WhatsAppIntentRouter.launchWhatsAppSafely(context, shareText, recipientPhone) { err ->
                                feedbackError = err
                            }
                        },
                        modifier = Modifier
                            .weight(1.1f)
                            .heightIn(min = 48.dp)
                            .testTag("whatsapp_share_send_btn"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        )
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("WhatsApp", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }
                }

                if (copiedConfirmed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Message copied to clipboard successfully.",
                        color = StatusSuccessGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (feedbackError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = feedbackError.orEmpty(),
                        color = StatusDangerRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Employee Mobile Missing Warning
                if (employeeMobile.isBlank()) {
                    Surface(
                        color = Color(0xFFFEF3C7),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.8.dp, Color(0xFFF59E0B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Employee mobile missing in Settings — using desk fallback 9766880099",
                            color = Color(0xFF92400E),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // Sensitive Data Leak Guard Banner
                if (hasSensitiveLeak) {
                    Surface(
                        color = Color(0xFFFEF2F2),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Warning: Edited text appears to contain a saved owner/source contact.",
                                color = Color(0xFF991B1B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // Editable Message Preview
                OutlinedTextField(
                    value = shareText,
                    onValueChange = { shareText = it },
                    label = { Text("Editable Message Preview") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 180.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite,
                        focusedBorderColor = FocusOutlineColor,
                        unfocusedBorderColor = BorderGrey,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

private fun buildMultiPropertyShareMessage(
    properties: List<PropertyItem>,
    audience: ShareAudienceMode,
    concise: Boolean,
    employeeName: String,
    employeeMobile: String
): String = buildString {
    append("🏢 *Property Options* (${properties.size} ${if (properties.size == 1) "Property" else "Properties"})\n\n")
    properties.forEachIndexed { index, prop ->
        val cleanProjectTitle = prop.projectName.ifBlank { prop.propertyType }
        append("${index + 1}. *$cleanProjectTitle* (${prop.propertyCode})\n")
        if (prop.location.isNotBlank() && !prop.location.equals("Unverified Location", ignoreCase = true)) {
            append("• Location: ${prop.location}\n")
        }
        append("• Type: ${prop.propertyType} (${prop.transactionType.ifBlank { prop.tab.title }})\n")
        if (prop.carpetAreaSqFt > 0.0) {
            append("• Carpet Area: ${CalculationUtils.formatNumber(prop.carpetAreaSqFt)} sq.ft\n")
        }

        if (!concise) {
            if (prop.builtUpArea > 0.0) append("• Built-up Area: ${CalculationUtils.formatNumber(prop.builtUpArea)} sq.ft\n")
            if (prop.furnishing != FurnishingType.UNFURNISHED) append("• Furnishing: ${prop.furnishing.label}\n")
            if (prop.possession.isNotBlank()) append("• Possession: ${prop.possession}\n")
            if (audience == ShareAudienceMode.BROKER) {
                append("• Status: ${prop.status.label}\n")
            }
        }

        val resolvedAmount = if (prop.tab.isRental) {
            (prop.rent ?: 0.0).takeIf { it > 0.0 }
                ?: prop.priceOrRent.takeIf { it > 0.0 }
                ?: (prop.priceSale ?: 0.0).takeIf { it > 0.0 }
                ?: 0.0
        } else {
            (prop.priceSale ?: 0.0).takeIf { it > 0.0 }
                ?: prop.priceOrRent.takeIf { it > 0.0 }
                ?: (prop.rent ?: 0.0).takeIf { it > 0.0 }
                ?: 0.0
        }
        val priceText = if (resolvedAmount > 0.0) {
            if (prop.tab.isRental) {
                "${CalculationUtils.formatIndianCurrency(resolvedAmount)} / month"
            } else {
                CalculationUtils.formatIndianCurrency(resolvedAmount)
            }
        } else {
            "Rent / Price on Request"
        }
        append("• Price / Rent: $priceText\n")

        val rateText = CalculationUtils.formatRateForBasis(prop, AreaRateBasis.CARPET)
        if (rateText != "Not Available" && !rateText.contains("₹0") && !concise) {
            append("• Rate: $rateText\n")
        }

        if (prop.tab == InventoryTab.PRE_LEASED && (prop.rent ?: 0.0) > 0.0 && (prop.priceSale ?: 0.0) > 0.0) {
            val annualRent = (prop.rent ?: 0.0) * 12.0
            val yield = (annualRent / (prop.priceSale ?: 1.0)) * 100.0
            append("• Gross Annual Rental Yield: ${String.format(java.util.Locale.US, "%.2f", yield)}%\n")
        }

        append("\n")
    }

    append("Contact POC: $employeeName\n")
    append("Mobile: ${employeeMobile.ifBlank { "9766880099" }} (TLC Real Estate)")
}

object TlcProposalPdfGenerator {

    /**
     * Draws The Leasing Company's exact 6-polygon symmetrical double-wing crest at (leftX, topY)
     * using viewport coordinates 1000x250 scaled smoothly to targetWidthPt.
     */
    fun drawTlcLogoVectorMark(
        canvas: Canvas,
        leftX: Float = 36f,
        topY: Float = 26f,
        targetWidthPt: Float = 84f,
        tintColor: Int = android.graphics.Color.parseColor("#111827")
    ) {
        val scale = targetWidthPt / 1000f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(leftX, topY)
        }

        val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tintColor
            style = Paint.Style.FILL
        }

        fun addWingPath(build: Path.() -> Unit) {
            val path = Path().apply {
                build()
                close()
                transform(matrix)
            }
            canvas.drawPath(path, crestPaint)
        }

        // Left Wing
        addWingPath {
            moveTo(0f, 0f)
            lineTo(92.3f, 61.0f)
            lineTo(336.7f, 80.4f)
            lineTo(497.5f, 250.0f)
            lineTo(382.8f, 22.3f)
        }
        addWingPath {
            moveTo(117.2f, 78.9f)
            lineTo(179.6f, 122.0f)
            lineTo(371.6f, 135.4f)
            lineTo(335.4f, 96.7f)
        }
        addWingPath {
            moveTo(213.2f, 139.9f)
            lineTo(276.8f, 184.5f)
            lineTo(425.2f, 192.0f)
            lineTo(389.0f, 153.3f)
        }

        // Right Wing
        addWingPath {
            moveTo(1000.0f, 0f)
            lineTo(617.2f, 22.3f)
            lineTo(502.5f, 250.0f)
            lineTo(663.3f, 80.4f)
            lineTo(907.7f, 61.0f)
        }
        addWingPath {
            moveTo(882.8f, 78.9f)
            lineTo(664.6f, 96.7f)
            lineTo(628.4f, 135.4f)
            lineTo(820.4f, 122.0f)
        }
        addWingPath {
            moveTo(786.8f, 139.9f)
            lineTo(611.0f, 153.3f)
            lineTo(574.8f, 192.0f)
            lineTo(723.2f, 184.5f)
        }
    }

    fun generateProposalPdfFile(
        context: Context,
        properties: List<PropertyItem>,
        project: UnderConstProject?,
        shareBodyText: String,
        employeeName: String,
        employeeMobile: String,
        coverBitmap: Bitmap? = null
    ): File {
        val pdfDocument = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val serifTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#111827")
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
            textSize = 15.5f
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#E96932")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 9.5f
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#202629")
            textSize = 10.5f
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#566168")
            textSize = 9.5f
        }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#D7DCD9")
            strokeWidth = 1f
        }

        // Page 1 Header: Symmetrical Double-Wing Crest + Serif "THE LEASING COMPANY" at top-left
        drawTlcLogoVectorMark(canvas = canvas, leftX = 36f, topY = 28f, targetWidthPt = 84f)
        canvas.drawText("THE LEASING COMPANY", 132f, 41f, serifTitlePaint)
        canvas.drawText(
            "CLIENT PROPERTY PROPOSAL • PUNE COMMERCIAL & RESIDENTIAL",
            132f,
            55f,
            subtitlePaint
        )
        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())
        canvas.drawText("Date: $dateStr", 455f, 41f, mutedPaint)
        canvas.drawLine(36f, 68f, pageWidth - 36f, 68f, dividerPaint)

        // Hero Cover Photo or Clean Navy & Gold Editorial Header Banner on Page 1
        val primaryProp = properties.firstOrNull()
        val bannerTitle = project?.projectName?.takeIf { it.isNotBlank() }
            ?: primaryProp?.projectName?.takeIf { it.isNotBlank() }
            ?: primaryProp?.propertyType?.takeIf { it.isNotBlank() }
            ?: "Curated Real Estate Proposal"
        val bannerSub = listOfNotNull(
            project?.location?.takeIf { it.isNotBlank() } ?: primaryProp?.location?.takeIf { it.isNotBlank() },
            primaryProp?.propertyCode?.takeIf { it.isNotBlank() } ?: project?.projectCode?.takeIf { it.isNotBlank() }
        ).joinToString(" • ")

        val bannerLeft = 36f
        val bannerTop = 80f
        val bannerRight = pageWidth - 36f
        val bannerWidth = bannerRight - bannerLeft

        var y = if (coverBitmap != null && coverBitmap.width > 0 && coverBitmap.height > 0) {
            val bannerHeight = 176f
            val bannerRect = RectF(bannerLeft, bannerTop, bannerRight, bannerTop + bannerHeight)
            val clipPath = Path().apply { addRoundRect(bannerRect, 10f, 10f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clipPath)

            // Center-crop scale preserves original aspect ratio with zero stretching or clipping distortion
            val scale = maxOf(
                bannerWidth / coverBitmap.width.toFloat(),
                bannerHeight / coverBitmap.height.toFloat()
            )
            val scaledW = coverBitmap.width * scale
            val scaledH = coverBitmap.height * scale
            val dx = bannerRect.left + (bannerWidth - scaledW) / 2f
            val dy = bannerRect.top + (bannerHeight - scaledH) / 2f
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(coverBitmap, matrix, bitmapPaint)

            // Dark gradient scrim along bottom edge for readable caption
            val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, bannerRect.bottom - 52f, 0f, bannerRect.bottom,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.argb(205, 11, 27, 43),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(bannerRect.left, bannerRect.bottom - 52f, bannerRect.right, bannerRect.bottom, scrimPaint)

            val captionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textSize = 13.5f
            }
            val captionSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#EAD17A")
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = 9.5f
            }
            canvas.drawText(bannerTitle, bannerRect.left + 14f, bannerRect.bottom - 22f, captionTitlePaint)
            if (bannerSub.isNotBlank()) {
                canvas.drawText(bannerSub, bannerRect.left + 14f, bannerRect.bottom - 9f, captionSubPaint)
            }
            canvas.restore()

            val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#D4AF37")
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
            }
            canvas.drawRoundRect(bannerRect, 10f, 10f, framePaint)
            bannerRect.bottom + 20f
        } else {
            // Clean Luxury Navy & Gold Editorial Fallback Header when no photo is available
            val bannerHeight = 62f
            val bannerRect = RectF(bannerLeft, bannerTop, bannerRight, bannerTop + bannerHeight)
            val fallbackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#0B1B2B")
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(bannerRect, 10f, 10f, fallbackBgPaint)

            val goldAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#D4AF37")
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(
                RectF(bannerRect.left, bannerRect.top, bannerRect.left + 5f, bannerRect.bottom),
                3f, 3f, goldAccentPaint
            )

            val fallbackTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textSize = 14f
            }
            val fallbackSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#D4AF37")
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = 9.5f
            }
            canvas.drawText(bannerTitle, bannerRect.left + 16f, bannerRect.top + 26f, fallbackTitlePaint)
            if (bannerSub.isNotBlank()) {
                canvas.drawText(bannerSub, bannerRect.left + 16f, bannerRect.top + 44f, fallbackSubPaint)
            }
            bannerRect.bottom + 20f
        }

        val maxLineWidth = pageWidth - 72f

        val lines = shareBodyText.split("\n")
        for (rawLine in lines) {
            val cleanLine = rawLine.replace("*", "")
            val wrapped = wrapTextToWidth(cleanLine, bodyPaint, maxLineWidth)
            if (wrapped.isEmpty()) {
                y += 8f
                continue
            }
            for (segment in wrapped) {
                if (y > pageHeight - 72f) {
                    drawFooter(canvas, pageWidth, pageHeight, employeeName, employeeMobile, dividerPaint, mutedPaint)
                    pdfDocument.finishPage(page)
                    pageNumber += 1
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 44f
                }
                canvas.drawText(segment, 36f, y, bodyPaint)
                y += 15f
            }
        }

        drawFooter(canvas, pageWidth, pageHeight, employeeName, employeeMobile, dividerPaint, mutedPaint)
        pdfDocument.finishPage(page)

        val outDir = File(context.cacheDir, "proposals").apply { mkdirs() }
        val fileTag = project?.projectCode?.ifBlank { null }
            ?: properties.firstOrNull()?.propertyCode?.ifBlank { null }
            ?: "TLC"
        val outFile = File(outDir, "TLC_Proposal_${fileTag}_${System.currentTimeMillis()}.pdf")
        FileOutputStream(outFile).use { fos ->
            pdfDocument.writeTo(fos)
        }
        pdfDocument.close()
        return outFile
    }

    suspend fun generateAndShareProposalPdf(
        context: Context,
        properties: List<PropertyItem>,
        project: UnderConstProject?,
        shareBodyText: String,
        employeeName: String,
        employeeMobile: String
    ): Result<File> = runCatching {
        val coverBitmap = resolveBestCoverBitmap(context, properties, project)
        val pdfFile = withContext(Dispatchers.IO) {
            generateProposalPdfFile(
                context = context,
                properties = properties,
                project = project,
                shareBodyText = shareBodyText,
                employeeName = employeeName,
                employeeMobile = employeeMobile,
                coverBitmap = coverBitmap
            )
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "The Leasing Company — Property Proposal")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Branded PDF Proposal").apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        withContext(Dispatchers.Main) {
            context.startActivity(chooser)
        }
        pdfFile
    }

    private fun drawFooter(
        canvas: Canvas,
        pageWidth: Int,
        pageHeight: Int,
        employeeName: String,
        employeeMobile: String,
        dividerPaint: Paint,
        mutedPaint: Paint
    ) {
        val footerY = pageHeight - 38f
        canvas.drawLine(36f, footerY - 14f, pageWidth - 36f, footerY - 14f, dividerPaint)
        canvas.drawText(
            "Prepared by $employeeName • ${employeeMobile.ifBlank { "9766880099" }} • The Leasing Company",
            36f,
            footerY,
            mutedPaint
        )
    }

    private fun wrapTextToWidth(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (paint.measureText(candidate) <= maxWidth) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}


private fun buildProjectShareMessage(
    project: UnderConstProject,
    employeeName: String,
    employeeMobile: String
): String = buildString {
    append("🏢 *${project.projectName.uppercase()}*")
    if (project.location.isNotBlank()) append("\n📍 *Location:* ${project.location}")
    if (project.possessionYear.isNotBlank()) append("\n🔑 *Possession:* ${project.possessionYear}")
    if (project.startingPrice.isFinite() && project.startingPrice > 0.0) {
        append("\n💰 *Starting From:* ${CalculationUtils.formatIndianCurrency(project.startingPrice)}")
    }
    if (project.configurations.isNotEmpty()) {
        append("\n\n📐 *Available Configurations:*")
        project.configurations.forEach { cfg ->
            val area = if (cfg.carpetAreaSqFt > 0) "${cfg.carpetAreaSqFt.toInt()} sq.ft" else "Area N/A"
            val price = if (cfg.price > 0) CalculationUtils.formatIndianCurrency(cfg.price) else "Price on request"
            append("\n• ${cfg.configName} ($area) — $price")
        }
    }
    append("\n📌 *Status:* ${project.status.label}")
    append("\n\nContact POC: $employeeName\n")
    append("Mobile: ${employeeMobile.ifBlank { "9766880099" }} (TLC Real Estate)")
}
