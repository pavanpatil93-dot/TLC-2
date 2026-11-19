package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.AppUpdateInfo
import com.example.data.AppUpdateManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AppUpdateDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isDownloading by remember(updateInfo) { mutableStateOf(false) }
    var downloadProgress by remember(updateInfo) { mutableIntStateOf(0) }
    var statusMessage by remember(updateInfo) { mutableStateOf<String?>(null) }
    var errorMessage by remember(updateInfo) { mutableStateOf<String?>(null) }
    var needsInstallPermission by remember(updateInfo) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        containerColor = SurfaceWhite,
        shape = RoundedCornerShape(16.dp),
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = "App Update Available",
                tint = PrimaryActionOrange,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Update Available (v${updateInfo.version})",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "A newer build of The Leasing Company is ready in TLC_App_Updates.",
                    color = SecondaryText,
                    fontSize = 13.sp
                )
                Surface(
                    color = BackgroundSoft,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Installed: v${BuildConfig.VERSION_NAME}  ->  New: v${updateInfo.version}",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Package: ${updateInfo.fileName}",
                            color = SecondaryText,
                            fontSize = 11.sp
                        )
                    }
                }

                if (isDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = statusMessage ?: "Downloading APK...",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "$downloadProgress%",
                                color = PrimaryActionOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("app_update_progress_pct")
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (downloadProgress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = PrimaryActionOrange,
                            trackColor = BorderGrey
                        )
                    }
                }

                if (needsInstallPermission) {
                    Text(
                        text = "Allow 'Install unknown apps' for The Leasing Company in Android Settings, then return and tap Update Now.",
                        color = StatusWarningAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage.orEmpty(),
                        color = ErrorRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isDownloading) return@Button
                    errorMessage = null
                    if (!AppUpdateManager.canRequestPackageInstalls(context)) {
                        needsInstallPermission = true
                        AppUpdateManager.launchInstallPermissionSettings(context)
                        Toast.makeText(
                            context,
                            "Enable 'Allow from this source', then tap Update Now again.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }
                    needsInstallPermission = false
                    isDownloading = true
                    downloadProgress = 0
                    statusMessage = "Downloading ${updateInfo.fileName}..."
                    coroutineScope.launch {
                        val result = AppUpdateManager.downloadApkWithProgress(
                            context = context,
                            downloadUrl = updateInfo.downloadUrl,
                            onProgress = { pct -> downloadProgress = pct }
                        )
                        isDownloading = false
                        result.onSuccess { apkFile ->
                            statusMessage = "Opening Android installer..."
                            val launched = AppUpdateManager.launchApkInstall(context, apkFile)
                            if (launched) {
                                onDismiss()
                            } else {
                                needsInstallPermission = true
                            }
                        }.onFailure { err ->
                            errorMessage = err.localizedMessage ?: "Download failed. Please retry."
                        }
                    }
                },
                enabled = !isDownloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryActionOrange,
                    contentColor = OnPrimaryActionColor
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("app_update_now_btn")
            ) {
                Text(
                    text = if (isDownloading) "Downloading $downloadProgress%" else "Update Now",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!isDownloading) onDismiss() },
                enabled = !isDownloading,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("app_update_later_btn")
            ) {
                Text("Later", color = SecondaryText)
            }
        }
    )
}
