package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.projects.DirectoryProject
import com.example.data.projects.ProjectDirectoryStore
import com.example.inventory.InventoryQuality
import com.example.model.*
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

// -----------------------------------------------------------------------------
// 1. FACEBOOK MARKETPLACE FREE AD GENERATOR (PRIVACY-SAFE)
// -----------------------------------------------------------------------------

data class FbMarketplaceAdDraft(
    val title: String,
    val priceNumeric: String,
    val priceShortInr: String,
    val description: String
)

fun buildFbMarketplaceAdDraft(
    property: PropertyItem,
    employeeName: String,
    employeeMobile: String
): FbMarketplaceAdDraft {
    val txType = StandardTransactionType.fromString(property.transactionType, property.tab)
    val isRental = txType == StandardTransactionType.LEASE

    val areaSqFt = property.carpetAreaSqFt
    val areaPrefix = if (areaSqFt > 0.0) "${CalculationUtils.formatNumber(areaSqFt)} sq.ft. " else ""

    val unitType = when {
        !property.bhk.isNullOrBlank() && InventoryQuality.isMeaningfulText(property.bhk) -> property.bhk.trim()
        property.propertyType.isNotBlank() && InventoryQuality.isMeaningfulText(property.propertyType) -> property.propertyType.trim()
        property.isCommercial() -> "Commercial Space"
        else -> "Residence"
    }

    val txAction = when (txType) {
        StandardTransactionType.LEASE -> "for Lease"
        StandardTransactionType.PRE_LEASED_SALE -> "Pre-Leased Investment"
        StandardTransactionType.SALE -> "for Sale"
    }

    val cleanLocation = property.location.trim().takeIf {
        InventoryQuality.isMeaningfulText(it) && !it.equals("Unverified Location", ignoreCase = true)
    }.orEmpty()
    val cleanProject = property.projectName.trim().takeIf { InventoryQuality.isMeaningfulText(it) }.orEmpty()

    val title = buildString {
        append(areaPrefix)
        append(unitType)
        append(" ")
        append(txAction)
        if (cleanLocation.isNotBlank()) {
            append(" in ")
            append(cleanLocation)
        }
        if (cleanProject.isNotBlank()) {
            append(" | ")
            append(cleanProject)
        }
    }.trim()

    val amount = if (isRental) {
        (property.rent ?: 0.0).takeIf { it > 0.0 }
            ?: property.priceOrRent.takeIf { it > 0.0 }
            ?: (property.priceSale ?: 0.0).takeIf { it > 0.0 }
            ?: 0.0
    } else {
        (property.priceSale ?: 0.0).takeIf { it > 0.0 }
            ?: property.priceOrRent.takeIf { it > 0.0 }
            ?: (property.rent ?: 0.0).takeIf { it > 0.0 }
            ?: 0.0
    }

    val priceShortInr = if (amount > 0.0) {
        CalculationUtils.formatIndianCurrency(amount, isMonthly = isRental)
    } else {
        "Price on Request"
    }
    val priceNumeric = if (amount > 0.0) kotlin.math.round(amount).toLong().toString() else ""

    val safeName = employeeName.trim().ifBlank { "TLC Executive" }
    val safePhone = employeeMobile.trim().ifBlank { "9766880099" }

    val description = buildString {
        append("📍 Project / Micro-Market: ")
        append(listOf(cleanProject, cleanLocation).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "Pune Prime Corridor" })
        append("\n")

        if (property.carpetAreaSqFt > 0.0) {
            append("📐 Carpet Area: ${CalculationUtils.formatNumber(property.carpetAreaSqFt)} sq.ft.\n")
        }
        val bua = property.builtUpAreaSqFt ?: property.builtUpArea.takeIf { it > 0.0 }
        if (bua != null && bua > 0.0) {
            append("🏢 Built-up Area: ${CalculationUtils.formatNumber(bua)} sq.ft.\n")
        }
        append("💰 ${if (isRental) "Monthly Rent" else "Offer Price"}: $priceShortInr\n")

        val depositAmt = property.deposit ?: 0.0
        if (isRental && depositAmt > 0.0) {
            append("🔐 Security Deposit: ${CalculationUtils.formatIndianCurrency(depositAmt, isMonthly = false)}\n")
        }
        if (txType == StandardTransactionType.PRE_LEASED_SALE && (property.rent ?: 0.0) > 0.0 && (property.priceSale ?: 0.0) > 0.0) {
            val yieldPct = (((property.rent ?: 0.0) * 12.0) / (property.priceSale ?: 1.0)) * 100.0
            append("📈 Rental Yield: ${String.format(Locale.US, "%.2f", yieldPct)}% p.a.\n")
        }

        append("🪑 Furnishing: ${property.furnishing.label}\n")

        val parkingInfo = property.parking.takeIf { InventoryQuality.isMeaningfulText(it) }
            ?: property.parkingCount?.takeIf { it > 0 }?.let { "$it Reserved Parking" }
        if (!parkingInfo.isNullOrBlank()) {
            append("🚗 Parking: $parkingInfo\n")
        }

        val possessionInfo = property.possession.trim().takeIf { InventoryQuality.isMeaningfulText(it) } ?: "Immediate / Ready"
        append("🔑 Possession: $possessionInfo\n")

        val highlights = buildList {
            property.workstations?.takeIf { it > 0 }?.let { add("$it Workstations") }
            property.cabins?.takeIf { it > 0 }?.let { add("$it Cabins") }
            property.conferenceRooms?.takeIf { it > 0 }?.let { add("$it Conference Room(s)") }
            if (property.isMainRoadFacing) add("Main Road Facing")
            property.facing.takeIf { InventoryQuality.isMeaningfulText(it) }?.let { add("$it Facing") }
            property.amenities.filter { InventoryQuality.isMeaningfulText(it) }.take(6).forEach { add(it) }
        }.distinct()

        if (highlights.isNotEmpty()) {
            append("\n✨ Key Highlights:\n")
            highlights.forEach { append("• $it\n") }
        }

        append("\nContact: $safeName · $safePhone\n")
        append("The Leasing Company | Verified Pune Real Estate")
    }

    return FbMarketplaceAdDraft(
        title = title,
        priceNumeric = priceNumeric,
        priceShortInr = priceShortInr,
        description = description
    )
}

@Composable
fun FbMarketplaceAdPanel(
    property: PropertyItem,
    employeeName: String,
    employeeMobile: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val draft = remember(property.id, property.updatedAt, employeeName, employeeMobile) {
        buildFbMarketplaceAdDraft(property, employeeName, employeeMobile)
    }

    var titleText by remember(draft) { mutableStateOf(draft.title) }
    var priceText by remember(draft) { mutableStateOf(draft.priceNumeric.ifBlank { draft.priceShortInr }) }
    var descriptionText by remember(draft) { mutableStateOf(draft.description) }
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    fun copyToClipboard(label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, value))
        copiedLabel = label
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    val hasSensitiveLeak = remember(titleText, descriptionText, property) {
        val combined = "$titleText\n$descriptionText"
        val owner = property.ownerName?.trim().orEmpty()
        val ownerPh = property.ownerContact?.trim().orEmpty()
        val broker = property.brokerName?.trim().orEmpty()
        val brokerPh = property.brokerContact?.trim().orEmpty()
        (owner.length > 2 && combined.contains(owner, ignoreCase = true)) ||
            (ownerPh.length > 5 && combined.contains(ownerPh, ignoreCase = true)) ||
            (broker.length > 2 && combined.contains(broker, ignoreCase = true)) ||
            (brokerPh.length > 5 && combined.contains(brokerPh, ignoreCase = true))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = SelectedSurfaceContainer,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, PrimaryActionOrange.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(18.dp))
                Text(
                    text = "Client-Safe FB Listing • Contact locked to ${employeeName.ifBlank { "TLC Executive" }} (${employeeMobile.ifBlank { "9766880099" }})",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (hasSensitiveLeak) {
            Surface(
                color = StatusDangerRed.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, StatusDangerRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Warning: Edited text contains saved owner/broker contact details.",
                    color = StatusDangerRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        if (copiedLabel != null) {
            Text(
                text = "Copied $copiedLabel to clipboard — ready to paste in Facebook Marketplace.",
                color = StatusSuccessGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Title + 1-Tap Copy
        OutlinedTextField(
            value = titleText,
            onValueChange = { titleText = it },
            label = { Text("Marketplace Listing Title") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceWhite,
                unfocusedContainerColor = SurfaceWhite,
                focusedBorderColor = FocusOutlineColor,
                unfocusedBorderColor = BorderGrey
            )
        )
        OutlinedButton(
            onClick = { copyToClipboard("Title", titleText) },
            border = BorderStroke(1.dp, PrimaryActionOrange),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("fb_ad_copy_title_btn")
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Copy Title", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
        }

        // Price + 1-Tap Copy (Short INR + Numeric Paste Value)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = priceText,
                onValueChange = { priceText = it },
                label = { Text("Price (${draft.priceShortInr})") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceWhite,
                    unfocusedContainerColor = SurfaceWhite,
                    focusedBorderColor = FocusOutlineColor,
                    unfocusedBorderColor = BorderGrey
                )
            )
            OutlinedButton(
                onClick = { copyToClipboard("Price", priceText.ifBlank { draft.priceShortInr }) },
                border = BorderStroke(1.dp, PrimaryActionOrange),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(min = 52.dp)
                    .testTag("fb_ad_copy_price_btn")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy Price", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        // Description + 1-Tap Copy
        OutlinedTextField(
            value = descriptionText,
            onValueChange = { descriptionText = it },
            label = { Text("Marketplace Description") },
            minLines = 7,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceWhite,
                unfocusedContainerColor = SurfaceWhite,
                focusedBorderColor = FocusOutlineColor,
                unfocusedBorderColor = BorderGrey
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { copyToClipboard("Description", descriptionText) },
                border = BorderStroke(1.dp, PrimaryActionOrange),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("fb_ad_copy_desc_btn")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy Description", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Button(
                onClick = {
                    val fullAd = "$titleText\nPrice: ${draft.priceShortInr}\n\n$descriptionText"
                    copyToClipboard("Full Ad", fullAd)
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, titleText)
                        putExtra(Intent.EXTRA_TEXT, fullAd)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(sendIntent, "Share FB Marketplace Ad"))
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("fb_ad_share_btn")
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share Full Ad", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FbMarketplaceAdModalSheet(
    property: PropertyItem,
    employeeName: String,
    employeeMobile: String,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundSoft,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextPrimary) },
        modifier = Modifier.testTag("fb_marketplace_ad_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "FB Marketplace Free Ad",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${property.propertyCode.ifBlank { property.id }} • Ready-to-Paste Format",
                        color = PrimaryActionOrange,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                }
            }
            Spacer(Modifier.height(10.dp))
            FbMarketplaceAdPanel(
                property = property,
                employeeName = employeeName,
                employeeMobile = employeeMobile,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

// -----------------------------------------------------------------------------
// 2. SHARED COVER PHOTO RESOLVER (PROPERTY + LINKED PROJECT DIRECTORY + DRIVE)
// -----------------------------------------------------------------------------

suspend fun resolveBestCoverBitmap(
    context: Context,
    properties: List<PropertyItem>,
    project: UnderConstProject? = null
): Bitmap? = withContext(Dispatchers.IO) {
    val candidateUrls = buildList {
        properties.forEach { prop ->
            prop.images.filter { it.isNotBlank() }.forEach { add(it.trim()) }
        }
        project?.photoGallery?.filter { it.isNotBlank() }?.forEach { add(it.trim()) }

        // Linked Project Directory cover photo fallback
        val dirProjects: List<DirectoryProject> = runCatching { ProjectDirectoryStore.read(context) }.getOrDefault(emptyList())
        if (project != null) {
            dirProjects.firstOrNull {
                it.id.equals(project.id, ignoreCase = true) ||
                    it.name.equals(project.projectName, ignoreCase = true)
            }?.let { dp ->
                if (dp.coverImageUrl.isNotBlank()) add(dp.coverImageUrl.trim())
                dp.galleryImageUrls.filter { it.isNotBlank() }.forEach { add(it.trim()) }
            }
        }
        properties.forEach { prop ->
            dirProjects.firstOrNull { it.matchesProperty(prop) }?.let { dp ->
                if (dp.coverImageUrl.isNotBlank()) add(dp.coverImageUrl.trim())
                dp.galleryImageUrls.filter { it.isNotBlank() }.forEach { add(it.trim()) }
            }
        }
    }.distinct()

    for (rawUrl in candidateUrls) {
        val loaded = decodeBitmapSafely(context, rawUrl)
        if (loaded != null && loaded.width > 16 && loaded.height > 16) {
            return@withContext loaded
        }
        // If Google Drive uc?export=view link failed, try Drive thumbnail endpoint
        val driveId = extractGoogleDriveFileId(rawUrl)
        if (driveId != null) {
            val thumbUrl = "https://drive.google.com/thumbnail?id=$driveId&sz=w1200"
            val thumbLoaded = decodeBitmapSafely(context, thumbUrl)
            if (thumbLoaded != null && thumbLoaded.width > 16 && thumbLoaded.height > 16) {
                return@withContext thumbLoaded
            }
        }
    }
    null
}

private fun extractGoogleDriveFileId(url: String): String? {
    if (!url.contains("drive.google.com", ignoreCase = true)) return null
    Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)?.let { return it }
    Regex("""/file/d/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)?.let { return it }
    return null
}

private suspend fun decodeBitmapSafely(context: Context, source: String): Bitmap? = withContext(Dispatchers.IO) {
    // 1. Direct local file decode if file:// or raw file path
    if (source.startsWith("file://", ignoreCase = true) || source.startsWith("/")) {
        val cleanPath = source.removePrefix("file://")
        val f = File(cleanPath)
        if (f.exists() && f.length() > 0L) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeFile(f.absolutePath, opts)
            }.getOrNull()?.let { return@withContext it }
        }
    }

    // 2. Coil ImageLoader (handles HTTP/HTTPS, content:// URIs, memory/disk caches) with hardware disabled
    val coilBitmap = runCatching {
        val request = ImageRequest.Builder(context)
            .data(source)
            .allowHardware(false)
            .size(1200, 900)
            .build()
        val result = context.imageLoader.execute(request)
        if (result is SuccessResult) {
            val drawable = result.drawable
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val bmp = drawable.bitmap
                if (bmp.config == Bitmap.Config.HARDWARE) {
                    bmp.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    bmp
                }
            } else {
                val w = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(1200)
                val h = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(900)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { out ->
                    val c = Canvas(out)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(c)
                }
            }
        } else null
    }.getOrNull()

    coilBitmap
}

// -----------------------------------------------------------------------------
// 3. LUXURY NAVY & GOLD WHATSAPP STATUS FLYER GENERATOR (9:16 & 4:5)
// -----------------------------------------------------------------------------

enum class FlyerAspectRatio(val label: String, val widthPx: Int, val heightPx: Int) {
    STATUS_9_16("9:16 Status", 1080, 1920),
    POST_4_5("4:5 Post", 1080, 1350)
}

object LuxuryStatusFlyerGenerator {
    private const val NAVY_DARK = 0xFF07121E.toInt()
    private const val NAVY_BG = 0xFF0B1B2B.toInt()
    private const val NAVY_CARD = 0xFF10253A.toInt()
    private const val GOLD_PRIMARY = 0xFFD4AF37.toInt()
    private const val GOLD_SOFT = 0xFFEAD17A.toInt()
    private const val IVORY_TEXT = 0xFFF8F7F4.toInt()
    private const val MUTED_LIGHT = 0xFFB8C4D0.toInt()

    fun renderFlyerBitmap(
        property: PropertyItem,
        aspectRatio: FlyerAspectRatio,
        coverBitmap: Bitmap?,
        employeeName: String,
        employeeMobile: String
    ): Bitmap {
        val w = aspectRatio.widthPx
        val h = aspectRatio.heightPx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val isTall = aspectRatio == FlyerAspectRatio.STATUS_9_16

        // 1. Luxury Navy Background Gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(NAVY_DARK, NAVY_BG, 0xFF0D2136.toInt(), NAVY_DARK),
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // 2. Double Gold Architectural Border Frame
        val outerMargin = 34f
        val innerMargin = 48f
        val outerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD_PRIMARY
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        val innerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(115, 212, 175, 55)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRoundRect(
            RectF(outerMargin, outerMargin, w - outerMargin, h - outerMargin),
            24f, 24f, outerBorderPaint
        )
        canvas.drawRoundRect(
            RectF(innerMargin, innerMargin, w - innerMargin, h - innerMargin),
            18f, 18f, innerBorderPaint
        )

        // 3. Top Header: Gold Double-Wing TLC Logo + Serif "THE LEASING COMPANY"
        val logoWidth = 180f
        val logoHeight = logoWidth * 0.25f
        val logoTop = if (isTall) 82f else 66f
        TlcProposalPdfGenerator.drawTlcLogoVectorMark(
            canvas = canvas,
            leftX = (w - logoWidth) / 2f,
            topY = logoTop,
            targetWidthPt = logoWidth,
            tintColor = GOLD_PRIMARY
        )

        val brandTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD_PRIMARY
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = if (isTall) 36f else 32f
            letterSpacing = 0.14f
            textAlign = Paint.Align.CENTER
        }
        val brandY = logoTop + logoHeight + 48f
        canvas.drawText("THE LEASING COMPANY", w / 2f, brandY, brandTitlePaint)

        val tagLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD_SOFT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 18f
            letterSpacing = 0.22f
            textAlign = Paint.Align.CENTER
        }
        val tagLineY = brandY + 28f
        canvas.drawText("PRIME PUNE COMMERCIAL & RESIDENTIAL", w / 2f, tagLineY, tagLinePaint)

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(160, 212, 175, 55)
            strokeWidth = 1.5f
        }
        canvas.drawLine(w * 0.24f, tagLineY + 18f, w * 0.76f, tagLineY + 18f, rulePaint)

        // 4. Property / Project Cover Photo Frame
        val photoLeft = 82f
        val photoRight = w - 82f
        val photoWidth = photoRight - photoLeft
        val photoTop = tagLineY + (if (isTall) 44f else 28f)
        val photoHeight = if (isTall) 620f else 410f
        val photoRect = RectF(photoLeft, photoTop, photoRight, photoTop + photoHeight)

        val photoFrameBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NAVY_CARD
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(photoRect, 22f, 22f, photoFrameBgPaint)

        val clipPath = Path().apply { addRoundRect(photoRect, 22f, 22f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clipPath)

        if (coverBitmap != null && coverBitmap.width > 0 && coverBitmap.height > 0) {
            val scale = maxOf(
                photoWidth / coverBitmap.width.toFloat(),
                photoHeight / coverBitmap.height.toFloat()
            )
            val scaledW = coverBitmap.width * scale
            val scaledH = coverBitmap.height * scale
            val dx = photoRect.left + (photoWidth - scaledW) / 2f
            val dy = photoRect.top + (photoHeight - scaledH) / 2f
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(coverBitmap, matrix, bitmapPaint)

            // Bottom subtle dark vignette on photo for crisp overlay badge
            val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, photoRect.bottom - 140f, 0f, photoRect.bottom,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.argb(210, 7, 18, 30),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(photoRect.left, photoRect.bottom - 140f, photoRect.right, photoRect.bottom, scrimPaint)
        } else {
            // Luxury Navy & Gold architectural crest fallback when no photo exists
            val accentDiagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(40, 212, 175, 55)
                strokeWidth = 2f
            }
            canvas.drawLine(photoRect.left, photoRect.bottom, photoRect.right, photoRect.top, accentDiagPaint)
            canvas.drawLine(photoRect.left, photoRect.top, photoRect.right, photoRect.bottom, accentDiagPaint)
            TlcProposalPdfGenerator.drawTlcLogoVectorMark(
                canvas = canvas,
                leftX = w / 2f - 130f,
                topY = photoRect.centerY() - 55f,
                targetWidthPt = 260f,
                tintColor = android.graphics.Color.argb(165, 212, 175, 55)
            )
            val fallbackCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = GOLD_SOFT
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                textSize = 26f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "Exclusive Curated Listing",
                w / 2f,
                photoRect.centerY() + 62f,
                fallbackCapPaint
            )
        }
        canvas.restore()

        // Gold border around photo frame
        val photoBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD_PRIMARY
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(photoRect, 22f, 22f, photoBorderPaint)

        // Transaction Badge Pill overlapping bottom-left of photo
        val txType = StandardTransactionType.fromString(property.transactionType, property.tab)
        val badgeText = when (txType) {
            StandardTransactionType.LEASE -> "FOR LEASE • COMMERCIAL / PRIME"
            StandardTransactionType.PRE_LEASED_SALE -> "PRE-LEASED INVESTMENT"
            StandardTransactionType.SALE -> "FOR OUTRIGHT SALE"
        }.let {
            if (!property.isCommercial() && txType == StandardTransactionType.LEASE) "FOR RENT • LUXURY LIVING"
            else if (!property.isCommercial() && txType == StandardTransactionType.SALE) "FOR SALE • LUXURY LIVING"
            else it
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD_PRIMARY
            style = Paint.Style.FILL
        }
        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NAVY_DARK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 20f
            letterSpacing = 0.08f
        }
        val badgeW = badgeTextPaint.measureText(badgeText) + 42f
        val badgeRect = RectF(photoRect.left + 22f, photoRect.bottom - 58f, photoRect.left + 22f + badgeW, photoRect.bottom - 16f)
        canvas.drawRoundRect(badgeRect, 10f, 10f, badgePaint)
        canvas.drawText(badgeText, badgeRect.left + 21f, badgeRect.centerY() + 7f, badgeTextPaint)

        // 5. Serif Property Headline & Micro-Market
        val headlineText = property.projectName.trim()
            .takeIf { InventoryQuality.isMeaningfulText(it) }
            ?: property.propertyType.ifBlank { "Signature Pune Property" }
        val cleanLocation = property.location.trim()
            .takeIf { InventoryQuality.isMeaningfulText(it) && !it.equals("Unverified Location", ignoreCase = true) }
            ?: "Pune Prime Corridor"
        val unitAndType = buildList {
            if (property.propertyType.isNotBlank() && InventoryQuality.isMeaningfulText(property.propertyType)) add(property.propertyType)
            if (!property.bhk.isNullOrBlank() && InventoryQuality.isMeaningfulText(property.bhk)) add(property.bhk)
            add(cleanLocation)
        }.distinct().joinToString("  •  ")

        val headlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = IVORY_TEXT
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = if (isTall) 50f else 42f
            textAlign = Paint.Align.CENTER
        }
        val maxContentW = w - 168f
        val headlineLines = wrapFlyerText(headlineText, headlinePaint, maxContentW).take(2)
        var cursorY = photoRect.bottom + (if (isTall) 68f else 50f)
        headlineLines.forEach { line ->
            canvas.drawText(line, w / 2f, cursorY, headlinePaint)
            cursorY += if (isTall) 56f else 46f
        }

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED_LIGHT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = if (isTall) 27f else 24f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            wrapFlyerText(unitAndType, subPaint, maxContentW).firstOrNull().orEmpty(),
            w / 2f,
            cursorY + 2f,
            subPaint
        )
        cursorY += if (isTall) 46f else 34f

        // 6. Luxury 2x2 Gold Spec Box (Carpet Area, Furnishing, Possession, Price in short INR)
        val footerHeight = if (isTall) 168f else 136f
        val footerTop = h - outerMargin - footerHeight
        val boxTop = cursorY + 6f
        val boxBottom = minOf(footerTop - 30f, boxTop + (if (isTall) 380f else 260f))
        val boxRect = RectF(82f, boxTop, w - 82f, maxOf(boxTop + 210f, boxBottom))

        val specBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NAVY_CARD
            style = Paint.Style.FILL
        }
        val specBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(150, 212, 175, 55)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(boxRect, 20f, 20f, specBgPaint)
        canvas.drawRoundRect(boxRect, 20f, 20f, specBorderPaint)

        // Crosshair dividers inside 2x2 grid
        val midX = boxRect.centerX()
        val midY = boxRect.centerY()
        canvas.drawLine(midX, boxRect.top + 18f, midX, boxRect.bottom - 18f, rulePaint)
        canvas.drawLine(boxRect.left + 22f, midY, boxRect.right - 22f, midY, rulePaint)

        val carpetText = if (property.carpetAreaSqFt > 0.0) {
            "${CalculationUtils.formatNumber(property.carpetAreaSqFt)} sq.ft."
        } else {
            "On Request"
        }
        val furnishingText = property.furnishing.label
        val possessionText = property.possession.trim().takeIf { InventoryQuality.isMeaningfulText(it) } ?: "Immediate"

        val isMonthly = txType == StandardTransactionType.LEASE
        val amount = if (isMonthly) {
            (property.rent ?: 0.0).takeIf { it > 0.0 }
                ?: property.priceOrRent.takeIf { it > 0.0 }
                ?: (property.priceSale ?: 0.0).takeIf { it > 0.0 }
                ?: 0.0
        } else {
            (property.priceSale ?: 0.0).takeIf { it > 0.0 }
                ?: property.priceOrRent.takeIf { it > 0.0 }
                ?: (property.rent ?: 0.0).takeIf { it > 0.0 }
                ?: 0.0
        }
        val priceText = if (amount > 0.0) {
            CalculationUtils.formatIndianCurrency(amount, isMonthly = isMonthly)
        } else {
            "On Request"
        }
        val priceLabel = if (isMonthly) "MONTHLY RENT" else "OFFER PRICE"

        drawSpecCell(canvas, boxRect.left, boxRect.top, midX, midY, "CARPET AREA", carpetText, isHighlighted = false, isTall = isTall)
        drawSpecCell(canvas, midX, boxRect.top, boxRect.right, midY, "FURNISHING", furnishingText, isHighlighted = false, isTall = isTall)
        drawSpecCell(canvas, boxRect.left, midY, midX, boxRect.bottom, "POSSESSION", possessionText, isHighlighted = false, isTall = isTall)
        drawSpecCell(canvas, midX, midY, boxRect.right, boxRect.bottom, priceLabel, priceText, isHighlighted = true, isTall = isTall)

        // Highlights micro-strip on 9:16 if room remains
        if (isTall && boxRect.bottom + 54f < footerTop) {
            val specsSummary = buildList {
                property.workstations?.takeIf { it > 0 }?.let { add("$it Seats") }
                property.cabins?.takeIf { it > 0 }?.let { add("$it Cabins") }
                property.parking.takeIf { InventoryQuality.isMeaningfulText(it) }?.let { add("Parking: $it") }
                property.amenities.filter { InventoryQuality.isMeaningfulText(it) }.take(3).forEach { add(it) }
            }.distinct().take(4).joinToString("  •  ")
            if (specsSummary.isNotBlank()) {
                val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = GOLD_SOFT
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    textSize = 23f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(
                    wrapFlyerText(specsSummary, hlPaint, maxContentW).firstOrNull().orEmpty(),
                    w / 2f,
                    (boxRect.bottom + footerTop) / 2f + 8f,
                    hlPaint
                )
            }
        }

        // 7. Gold Footer Bar with Logged-In Employee's Name & Mobile ONLY
        val safeName = employeeName.trim().ifBlank { "TLC Executive" }
        val safePhone = employeeMobile.trim().ifBlank { "9766880099" }
        val footerRect = RectF(outerMargin, footerTop, w - outerMargin, h - outerMargin)
        val footerPath = Path().apply {
            addRoundRect(
                footerRect,
                floatArrayOf(0f, 0f, 0f, 0f, 22f, 22f, 22f, 22f),
                Path.Direction.CW
            )
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                footerRect.left, footerRect.top, footerRect.right, footerRect.bottom,
                intArrayOf(0xFFC79D27.toInt(), GOLD_PRIMARY, 0xFFE5C558.toInt(), GOLD_PRIMARY),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(footerPath, footerPaint)

        val footerNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NAVY_DARK
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = if (isTall) 32f else 28f
        }
        val footerPhonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NAVY_DARK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = if (isTall) 34f else 29f
        }
        val footerCaptionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A2D42.toInt()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 18f
            letterSpacing = 0.08f
        }

        canvas.drawText("PRIVATE VIEWING & DETAILS", footerRect.left + 44f, footerRect.top + (if (isTall) 46f else 38f), footerCaptionPaint)
        canvas.drawText(safeName, footerRect.left + 44f, footerRect.top + (if (isTall) 92f else 76f), footerNamePaint)
        canvas.drawText("📞 $safePhone", footerRect.left + 44f, footerRect.top + (if (isTall) 136f else 112f), footerPhonePaint)

        val codeTag = property.propertyCode.ifBlank { property.id }
        if (codeTag.isNotBlank()) {
            val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = NAVY_DARK
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = 20f
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("REF: $codeTag", footerRect.right - 44f, footerRect.centerY() + 8f, codePaint)
        }

        return bmp
    }

    private fun drawSpecCell(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        label: String,
        value: String,
        isHighlighted: Boolean,
        isTall: Boolean
    ) {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GOLD_SOFT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = if (isTall) 19f else 17f
            letterSpacing = 0.12f
            textAlign = Paint.Align.CENTER
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isHighlighted) GOLD_PRIMARY else IVORY_TEXT
            typeface = Typeface.create(if (isHighlighted) Typeface.SERIF else Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = if (isHighlighted) (if (isTall) 38f else 32f) else (if (isTall) 30f else 26f)
            textAlign = Paint.Align.CENTER
        }
        val cellMaxW = (right - left) - 32f
        val clippedVal = wrapFlyerText(value, valuePaint, cellMaxW).firstOrNull() ?: value
        canvas.drawText(label, cx, cy - (if (isTall) 14f else 10f), labelPaint)
        canvas.drawText(clippedVal, cx, cy + (if (isTall) 28f else 24f), valuePaint)
    }

    private fun wrapFlyerText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
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

    suspend fun saveFlyerToMediaStore(
        context: Context,
        bitmap: Bitmap,
        propertyCode: String,
        aspectRatio: FlyerAspectRatio
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val safeCode = propertyCode.ifBlank { "TLC" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val fileName = "TLC_Flyer_${safeCode}_${aspectRatio.name}_${System.currentTimeMillis()}.png"
            val pngBytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TLC Flyers")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Unable to create MediaStore image entry")
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(pngBytes)
                } ?: error("Unable to open MediaStore output stream")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                // Zero-permission fallback on Android < 10: save to internal flyers dir and return FileProvider Uri
                val outDir = File(context.cacheDir, "flyers").apply { mkdirs() }
                val outFile = File(outDir, fileName)
                FileOutputStream(outFile).use { it.write(pngBytes) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            }
        }
    }

    suspend fun shareFlyerPng(
        context: Context,
        bitmap: Bitmap,
        propertyCode: String,
        aspectRatio: FlyerAspectRatio,
        captionText: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val safeCode = propertyCode.ifBlank { "TLC" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val outDir = File(context.cacheDir, "flyers").apply { mkdirs() }
            val outFile = File(outDir, "TLC_Flyer_${safeCode}_${aspectRatio.name}_${System.currentTimeMillis()}.png")
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, captionText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Share Luxury Status Flyer").apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            withContext(Dispatchers.Main) {
                context.startActivity(chooser)
            }
            outFile
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuxuryStatusFlyerModalSheet(
    property: PropertyItem,
    employeeName: String,
    employeeMobile: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedRatio by remember { mutableStateOf(FlyerAspectRatio.STATUS_9_16) }
    var coverBitmap by remember(property.id) { mutableStateOf<Bitmap?>(null) }
    var coverResolved by remember(property.id) { mutableStateOf(false) }
    var flyerBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRendering by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }

    LaunchedEffect(property.id) {
        coverResolved = false
        coverBitmap = resolveBestCoverBitmap(context, listOf(property), null)
        coverResolved = true
    }

    LaunchedEffect(property.id, selectedRatio, coverResolved, employeeName, employeeMobile) {
        if (!coverResolved) return@LaunchedEffect
        isRendering = true
        val rendered = withContext(Dispatchers.Default) {
            LuxuryStatusFlyerGenerator.renderFlyerBitmap(
                property = property,
                aspectRatio = selectedRatio,
                coverBitmap = coverBitmap,
                employeeName = employeeName,
                employeeMobile = employeeMobile
            )
        }
        flyerBitmap = rendered
        isRendering = false
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundSoft,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextPrimary) },
        modifier = Modifier.testTag("luxury_status_flyer_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Luxury Status Flyer",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Navy & Gold PNG • Employee POC: ${employeeName.ifBlank { "TLC Executive" }}",
                        color = PrimaryActionOrange,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                }
            }

            // 9:16 Status | 4:5 Post Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FlyerAspectRatio.entries.forEach { ratio ->
                    val active = selectedRatio == ratio
                    Surface(
                        onClick = { selectedRatio = ratio },
                        color = if (active) Color(0xFF0B1B2B) else SurfaceWhite,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.5.dp, if (active) Color(0xFFD4AF37) else BorderGrey),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("flyer_ratio_${ratio.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (ratio == FlyerAspectRatio.STATUS_9_16) Icons.Default.StayPrimaryPortrait else Icons.Default.CropPortrait,
                                contentDescription = null,
                                tint = if (active) Color(0xFFD4AF37) else SecondaryText,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = ratio.label,
                                color = if (active) Color(0xFFD4AF37) else TextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Preview Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF07121E))
                    .border(1.dp, BorderGrey, RoundedCornerShape(14.dp))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                val bmp = flyerBitmap
                if (isRendering || bmp == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFFD4AF37))
                        Text("Rendering Navy & Gold Flyer...", color = Color(0xFFEAD17A), fontSize = 12.sp)
                    }
                } else {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Luxury Navy and Gold Property Flyer",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("flyer_preview_image")
                    )
                }
            }

            // Bottom Save PNG + Share Sheet Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val bmp = flyerBitmap ?: return@OutlinedButton
                        isExporting = true
                        coroutineScope.launch {
                            val res = LuxuryStatusFlyerGenerator.saveFlyerToMediaStore(
                                context = context,
                                bitmap = bmp,
                                propertyCode = property.propertyCode.ifBlank { property.id },
                                aspectRatio = selectedRatio
                            )
                            isExporting = false
                            res.onSuccess { uri ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    Toast.makeText(context, "Flyer PNG saved to Pictures/TLC Flyers!", Toast.LENGTH_LONG).show()
                                } else {
                                    val chooser = Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "image/png"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        "Save or Share Flyer PNG"
                                    )
                                    context.startActivity(chooser)
                                }
                            }.onFailure { err ->
                                Toast.makeText(context, err.localizedMessage ?: "Failed to save flyer", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isRendering && !isExporting && flyerBitmap != null,
                    border = BorderStroke(1.dp, FocusOutlineColor),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("flyer_save_btn")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = FocusOutlineColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save PNG", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }

                Button(
                    onClick = {
                        val bmp = flyerBitmap ?: return@Button
                        isExporting = true
                        val safeName = employeeName.ifBlank { "TLC Executive" }
                        val safePhone = employeeMobile.ifBlank { "9766880099" }
                        val caption = "${property.projectName.ifBlank { property.propertyType }} • ${property.location}\nFor details & site visit: $safeName ($safePhone)"
                        coroutineScope.launch {
                            val res = LuxuryStatusFlyerGenerator.shareFlyerPng(
                                context = context,
                                bitmap = bmp,
                                propertyCode = property.propertyCode.ifBlank { property.id },
                                aspectRatio = selectedRatio,
                                captionText = caption
                            )
                            isExporting = false
                            res.onFailure { err ->
                                Toast.makeText(context, err.localizedMessage ?: "Unable to share flyer", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isRendering && !isExporting && flyerBitmap != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryActionOrange,
                        contentColor = OnPrimaryActionColor
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .heightIn(min = 48.dp)
                        .testTag("flyer_share_btn")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share Status Flyer", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }
            }
        }
    }
}
