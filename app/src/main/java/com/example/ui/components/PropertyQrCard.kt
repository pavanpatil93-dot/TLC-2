package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.model.PropertyItem
import com.example.model.WhatsAppShareTemplates
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.io.FileOutputStream

object PropertyQrGenerator {

    fun publicQrPayload(property: PropertyItem): String = buildString {
        append("Property Code: ${property.propertyCode.ifBlank { property.tab.code }}\n")
        append("Project: ${property.projectName.ifBlank { "Ascend / Aurea Property" }}\n")
        append("Location: ${property.location.ifBlank { "Pune" }}\n")
        if (property.carpetAreaSqFt > 0) {
            append("Carpet Area: ${property.carpetAreaSqFt.toInt()} sq.ft\n")
        }
        val isRental = property.tab.isRental
        append("${if (isRental) "Rent" else "Price"}: ${property.priceOrRent}\n")
        append("Status: ${property.status.label}\n")
        append("Contact: ${WhatsAppShareTemplates.DESK_CONTACTS}")
    }

    /**
     * Renders a QR bitmap with strictly enforced >= 4 modules quiet zone.
     */
    fun createQrBitmap(payload: String, size: Int = 720): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 4, // Strict quiet zone requirement
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    }

    /**
     * Decode validation test before export. Confirms scan integrity.
     */
    fun validateQrDecode(bitmap: Bitmap, expectedPayload: String): Boolean {
        return runCatching {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(binaryBitmap)
            result.text == expectedPayload
        }.getOrDefault(false)
    }

    fun saveQrToCache(context: Context, bitmap: Bitmap, propertyCode: String): File {
        val imagesFolder = File(context.cacheDir, "qr_cards").apply { mkdirs() }
        val file = File(imagesFolder, "QR_${propertyCode.replace("/", "_")}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }
}

@Composable
fun PropertyQrCard(property: PropertyItem) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { show = true },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("generate_qr_card_btn")
    ) {
        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Generate Property QR Card", fontWeight = FontWeight.Bold)
    }

    if (show) {
        val payload = remember(property) { PropertyQrGenerator.publicQrPayload(property) }
        val qrBitmap = remember(payload) { PropertyQrGenerator.createQrBitmap(payload) }
        val isValidated = remember(qrBitmap, payload) { PropertyQrGenerator.validateQrDecode(qrBitmap, payload) }

        AlertDialog(
            onDismissRequest = { show = false },
            title = {
                Text(
                    text = "Property QR Card · ${property.propertyCode.ifBlank { property.tab.code }}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Public property QR code",
                        modifier = Modifier
                            .size(240.dp)
                            .padding(4.dp)
                            .testTag("property_qr_image")
                    )

                    if (isValidated) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                            Text(
                                text = "Scan-verified (≥4 modules quiet zone)",
                                color = Color(0xFF10B981),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Text(
                        text = "Contains public property specs and official desk contacts. Owner, broker and internal remarks are strictly excluded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = PropertyQrGenerator.saveQrToCache(context, qrBitmap, property.propertyCode.ifBlank { "PROP" })
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_TEXT, "Property QR Card: ${property.propertyCode} · ${property.projectName}")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share QR Card"))
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("share_qr_card_btn")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share QR")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { show = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("close_qr_dialog_btn")
                ) {
                    Text("Close")
                }
            }
        )
    }
}
