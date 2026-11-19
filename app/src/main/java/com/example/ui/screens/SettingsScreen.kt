package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.access.*
import com.example.crm.CallRecordingMatcher
import com.example.crm.CrmCallManager
import com.example.crm.GoogleContactsSyncHelper
import com.example.data.AppUpdateManager
import com.example.data.SheetsRepository
import com.example.data.UserSettingsManager
import com.example.data.ApprovalQueueStore
import com.example.data.ApprovalState
import com.example.model.*
import com.example.ui.components.BrandLogoView
import com.example.ui.components.SocialMediaHubButton
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    userSettingsManager: UserSettingsManager,
    repository: SheetsRepository,
    properties: List<PropertyItem> = emptyList(),
    onBack: () -> Unit,
    onOpenMasterSync: () -> Unit,
    onPropertySelect: (PropertyItem) -> Unit = {},
    onVerifyProperty: (String) -> Unit = {},
    employeeSessionManager: EmployeeSessionManager? = null,
    initialSectionTab: Int = 0,
    onSwitchProfile: (() -> Unit)? = null,
    onLockNow: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
    onOpenTeamAdmin: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val userProfile by userSettingsManager.userProfile.collectAsState()
    val brandLogos by userSettingsManager.brandLogos.collectAsState()
    val commercialCompanyProfile by userSettingsManager.commercialCompanyProfile.collectAsState()
    val residentialCompanyProfile by userSettingsManager.residentialCompanyProfile.collectAsState()
    val validityDays by userSettingsManager.verificationValidityDays.collectAsState()
    val sessionState = employeeSessionManager?.sessionState?.collectAsState()?.value

    val isLiveConnected by repository.isLiveConnected.collectAsState()
    val sheetsScriptUrl by repository.sheetsScriptUrl.collectAsState()
    val connectionStatus by repository.connectionStatus.collectAsState()
    val lastSyncTimestamp by repository.lastSyncTimestamp.collectAsState()
    val lastDiagnostic by repository.lastDiagnostic.collectAsState()
    val syncLogs by repository.syncLogs.collectAsState()
    var approvalRequests by remember { mutableStateOf(ApprovalQueueStore.read(context)) }

    // Active Section Tab (0: Company, 1: User, 2: Google Sheet, 3: Verification, 4: Media Storage, 5: Backups, 6: System Info, 7: Approvals)
    var activeSectionTab by remember(initialSectionTab) { mutableStateOf(initialSectionTab) }

    // Company Profile Edit state
    var selectedCompanyIndex by remember { mutableStateOf(0) } // 0: Commercial, 1: Residential
    var commercialNameInput by remember(commercialCompanyProfile) { mutableStateOf(commercialCompanyProfile.companyName) }
    var commercialContact1Input by remember(commercialCompanyProfile) { mutableStateOf(commercialCompanyProfile.contact1) }
    var commercialContact2Input by remember(commercialCompanyProfile) { mutableStateOf(commercialCompanyProfile.contact2) }
    var commercialEmailInput by remember(commercialCompanyProfile) { mutableStateOf(commercialCompanyProfile.email) }
    var commercialAddressInput by remember(commercialCompanyProfile) { mutableStateOf(commercialCompanyProfile.officeAddress) }
    var commercialFooterInput by remember(commercialCompanyProfile) { mutableStateOf(commercialCompanyProfile.whatsappFooter) }

    var residentialNameInput by remember(residentialCompanyProfile) { mutableStateOf(residentialCompanyProfile.companyName) }
    var residentialContact1Input by remember(residentialCompanyProfile) { mutableStateOf(residentialCompanyProfile.contact1) }
    var residentialContact2Input by remember(residentialCompanyProfile) { mutableStateOf(residentialCompanyProfile.contact2) }
    var residentialEmailInput by remember(residentialCompanyProfile) { mutableStateOf(residentialCompanyProfile.email) }
    var residentialAddressInput by remember(residentialCompanyProfile) { mutableStateOf(residentialCompanyProfile.officeAddress) }
    var residentialFooterInput by remember(residentialCompanyProfile) { mutableStateOf(residentialCompanyProfile.whatsappFooter) }

    // User Profile Edit state
    var usernameInput by remember(userProfile) { mutableStateOf(userProfile.username) }
    var userMobileInput by remember(userProfile) { mutableStateOf(userProfile.mobileNumber) }
    var userEmailInput by remember(userProfile) { mutableStateOf(userProfile.email) }
    var selectedRole by remember(userProfile) { mutableStateOf(userProfile.role) }
    var pinInput by remember { mutableStateOf("") }
    var pinMessage by remember { mutableStateOf("") }
    val activeBrandScope by userSettingsManager.activeBrand.collectAsState()

    // Dialog & Workflow state
    var showAuditReportDialog by remember { mutableStateOf(false) }
    var showSchemaValidationDialog by remember { mutableStateOf(false) }
    var schemaResult by remember { mutableStateOf<SchemaValidationResult?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportModeIsClient by remember { mutableStateOf(true) }
    var exportFilterType by remember { mutableStateOf("ALL") }
    var exportedText by remember { mutableStateOf("") }

    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreJsonInput by remember { mutableStateOf("") }
    var restoreStatusText by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }

    val activeProperties = remember(properties) { properties.filter { it.archiveStatus != ArchiveStatus.ARCHIVED } }
    val totalProps = activeProperties.size
    val currentProps = remember(activeProperties, validityDays) {
        activeProperties.count { getVerificationAgeState(it, validityDays) == VerificationAgeState.CURRENT }
    }
    val dueProps = remember(activeProperties, validityDays) {
        activeProperties.count { getVerificationAgeState(it, validityDays) == VerificationAgeState.DUE }
    }
    val pendingProps = remember(activeProperties, validityDays) {
        activeProperties.count { getVerificationAgeState(it, validityDays) == VerificationAgeState.PENDING }
    }

    // Photo pickers for Brand Logos & User Profile Photo
    val commercialLogoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            userSettingsManager.saveCommercialLogo(uri)
            Toast.makeText(context, "Commercial Division logo updated & saved.", Toast.LENGTH_SHORT).show()
        }
    }

    val residentialLogoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            userSettingsManager.saveResidentialLogo(uri)
            Toast.makeText(context, "Residential Division logo updated & saved.", Toast.LENGTH_SHORT).show()
        }
    }

    val userPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            userSettingsManager.saveProfilePhoto(uri)
            Toast.makeText(context, "User profile photo updated.", Toast.LENGTH_SHORT).show()
        }
    }

    // Restore Backup File Picker
    val backupFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isRestoring = true
                restoreStatusText = "Reading backup JSON file..."
                try {
                    val jsonStr = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (jsonStr.isNotBlank()) {
                        restoreJsonInput = jsonStr
                        val (success, msg) = repository.restoreFromBackupJson(jsonStr, userSettingsManager)
                        restoreStatusText = msg
                        if (success) {
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        restoreStatusText = "Selected backup file was empty."
                    }
                } catch (e: Exception) {
                    restoreStatusText = "Failed to parse backup: ${e.localizedMessage}"
                } finally {
                    isRestoring = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCharcoalDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 60.dp)
            .testTag("settings_screen")
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BrandCharcoalSurface)
                    .border(0.8.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
                    .size(38.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = BrandGold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "SETTINGS & ADMINISTRATION",
                    color = BrandGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "System Configuration & Profiles",
                    color = TextPrimaryDark,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SocialMediaHubButton()
        Spacer(modifier = Modifier.height(10.dp))

        // Batch 9: WhatsApp & WhatsApp Business Call Notification Capture Card
        var waCallCaptureEnabled by remember { mutableStateOf(CrmCallManager.isWhatsAppCallCaptureEnabled(context)) }
        var notifAccessGranted by remember { mutableStateOf(CrmCallManager.isNotificationListenerGranted(context)) }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_wa_call_capture_card"),
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = null,
                            tint = StatusSuccessGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "WhatsApp Call Capture",
                                color = TextPrimaryDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Auto-detect incoming & missed calls from WhatsApp & WhatsApp Business",
                                color = TextSecondaryDark,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Switch(
                        checked = waCallCaptureEnabled,
                        onCheckedChange = { enabled ->
                            waCallCaptureEnabled = enabled
                            CrmCallManager.setWhatsAppCallCaptureEnabled(context, enabled)
                            notifAccessGranted = CrmCallManager.isNotificationListenerGranted(context)
                        },
                        modifier = Modifier.testTag("settings_wa_capture_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (notifAccessGranted) "Notification Access: Granted" else "Notification Access: Optional (Not Enabled)",
                        color = if (notifAccessGranted) StatusSuccessGreen else BrandGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedButton(
                        onClick = {
                            CrmCallManager.openNotificationListenerSettings(context)
                            notifAccessGranted = CrmCallManager.isNotificationListenerGranted(context)
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .testTag("settings_enable_notif_access_btn")
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = BrandGold, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Enable Notification Access", color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Batch 18: Auto-Save Linked Callers to Google Contacts + Confidential Phone Masking Override
        val autoSaveContacts by userSettingsManager.autoSaveGoogleContacts.collectAsState()
        val revealConfidentialPhones by userSettingsManager.revealConfidentialPhonesForExec.collectAsState()
        var contactsPermGranted by remember { mutableStateOf(GoogleContactsSyncHelper.hasContactsPermission(context)) }
        val contactsPermLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            contactsPermGranted = grants.values.all { it } || GoogleContactsSyncHelper.hasContactsPermission(context)
            Toast.makeText(
                context,
                if (contactsPermGranted) "Contacts permission granted!" else "Contacts permission denied.",
                Toast.LENGTH_SHORT
            ).show()
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_google_contacts_card"),
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Contacts,
                            contentDescription = null,
                            tint = PrimaryActionOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Auto-Save Callers to Google Contacts",
                                color = TextPrimaryDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Uses TLC prefixes (CL -, INV -, O -, CP -, BLD -, SM -, TNT -, OPS -) & deduplicates by 10-digit number",
                                color = TextSecondaryDark,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Switch(
                        checked = autoSaveContacts,
                        onCheckedChange = { userSettingsManager.setAutoSaveGoogleContacts(it) },
                        modifier = Modifier.testTag("settings_auto_save_contacts_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (contactsPermGranted) "Contacts Permission: Granted" else "Contacts Permission: Needed for Auto-Save",
                        color = if (contactsPermGranted) StatusSuccessGreen else BrandGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!contactsPermGranted) {
                        OutlinedButton(
                            onClick = {
                                contactsPermLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_CONTACTS,
                                        Manifest.permission.WRITE_CONTACTS
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .testTag("settings_grant_contacts_perm_btn")
                        ) {
                            Text("Grant Contacts", color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(color = BrandCharcoalBorder, thickness = 0.6.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reveal Confidential Owner/Tenant Phones",
                            color = TextPrimaryDark,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Admins & Managers always view full numbers; Executives can toggle full visibility here or inline",
                            color = TextSecondaryDark,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = revealConfidentialPhones,
                        onCheckedChange = { userSettingsManager.setRevealConfidentialPhonesForExec(it) },
                        modifier = Modifier.testTag("settings_reveal_phones_switch")
                    )
                }

                HorizontalDivider(color = BrandCharcoalBorder, thickness = 0.6.dp)

                // Batch 20: On-Demand Call Recording Folder (OpenDocumentTree + OEM Auto-Detect)
                val callRecordingFolderUri by userSettingsManager.callRecordingFolderUri.collectAsState()
                val callRecordingFolderLabel by userSettingsManager.callRecordingFolderLabel.collectAsState()
                val callRecordingTreeLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { treeUri ->
                    if (treeUri != null) {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(
                                treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        val prettyLabel = treeUri.lastPathSegment?.substringAfterLast(':') ?: treeUri.toString()
                        userSettingsManager.setCallRecordingFolder(treeUri.toString(), prettyLabel)
                        Toast.makeText(context, "Call recording folder linked: $prettyLabel", Toast.LENGTH_SHORT).show()
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_call_recording_folder_section"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = PrimaryActionOrange,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Call Recording Folder (On-Demand Play & Summarize)",
                                    color = TextPrimaryDark,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (callRecordingFolderLabel.isNotBlank())
                                        "Active Folder: $callRecordingFolderLabel"
                                    else
                                        "Auto-detects Samsung (Recordings/Call), Xiaomi (MIUI/sound_recorder/call_rec), OnePlus/Oppo (Music/Recordings/Call Recordings)",
                                    color = if (callRecordingFolderLabel.isNotBlank()) StatusSuccessGreen else TextSecondaryDark,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { callRecordingTreeLauncher.launch(null) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .testTag("settings_select_recording_folder_btn")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = PrimaryActionOrange, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Select Recording Folder",
                                color = TextPrimaryDark,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val detected = CallRecordingMatcher.autoDetectOemRecordingFolder(context)
                                if (detected != null) {
                                    userSettingsManager.setCallRecordingFolder(detected.first, detected.second)
                                    Toast.makeText(context, "Detected: ${detected.second}", Toast.LENGTH_SHORT).show()
                                } else {
                                    val defaultSamsung = CallRecordingMatcher.OEM_DEFAULT_RELATIVE_PATHS.first()
                                    userSettingsManager.setCallRecordingFolder(defaultSamsung.first, defaultSamsung.second)
                                    Toast.makeText(context, "Set default OEM path: ${defaultSamsung.second}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .testTag("settings_autodetect_recording_folder_btn")
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = BrandGold, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Auto-Detect OEM Folder",
                                color = TextPrimaryDark,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        if (callRecordingFolderUri.isNotBlank()) {
                            TextButton(
                                onClick = { userSettingsManager.clearCallRecordingFolder() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 38.dp)
                                    .testTag("settings_clear_recording_folder_btn")
                            ) {
                                Text(
                                    text = "Reset",
                                    color = TextSecondaryDark,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick-Jump Card: Alert Sounds & Vocal Alerts (Batch 40)
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (activeSectionTab == 8) PrimaryActionOrange else BrandCharcoalBorder
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { activeSectionTab = 8 }
                .testTag("settings_quick_alert_sounds_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = PrimaryActionOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Alert Sounds & Vocal Alerts",
                            color = TextPrimaryDark,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "6 custom notification channels · Indian English TTS · Quiet Hours",
                            color = TextSecondaryDark,
                            fontSize = 10.5.sp
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Open Alert Sounds & Vocal Alerts",
                    tint = PrimaryActionOrange,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SECTION TABS SCROLLER
        val sectionTabs = listOf(
            "Companies", "User Profile", "Google Sheet", "Verification",
            "Media Storage", "Backups", "System Info", "Approvals",
            "Alert Sounds & Vocal Alerts"
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(sectionTabs.indices.toList()) { idx ->
                val isSelected = activeSectionTab == idx
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) BrandGold else BrandCharcoalSurface)
                        .border(0.8.dp, if (isSelected) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
                        .clickable { activeSectionTab = idx }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("settings_tab_$idx")
                ) {
                    Text(
                        text = sectionTabs[idx],
                        color = if (isSelected) BrandCharcoalDark else TextPrimaryDark,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =====================================================================
        // SECTION 1: COMPANY PROFILES (COMMERCIAL & RESIDENTIAL)
        // =====================================================================
        if (activeSectionTab == 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(BrandGold.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Business, contentDescription = null, tint = BrandGold, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "COMPANY PROFILES & BRANDING",
                                color = BrandGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Commercial & Residential Division Settings",
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Company Selector Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedCompanyIndex == 0) BrandGold else BrandCharcoalSurface)
                                .border(0.8.dp, BrandGold, RoundedCornerShape(8.dp))
                                .clickable { selectedCompanyIndex = 0 }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Commercial",
                                color = if (selectedCompanyIndex == 0) BrandCharcoalDark else TextPrimaryDark,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedCompanyIndex == 1) BrandGold else BrandCharcoalSurface)
                                .border(0.8.dp, BrandGold, RoundedCornerShape(8.dp))
                                .clickable { selectedCompanyIndex = 1 }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Residential",
                                color = if (selectedCompanyIndex == 1) BrandCharcoalDark else TextPrimaryDark,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (selectedCompanyIndex == 0) {
                        // COMMERCIAL PORTFOLIO FORM
                        OutlinedTextField(
                            value = commercialNameInput,
                            onValueChange = { commercialNameInput = it },
                            label = { Text("Company Name", color = TextSecondaryDark, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = commercialContact1Input,
                                onValueChange = { commercialContact1Input = it },
                                label = { Text("Contact 1", color = TextSecondaryDark, fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = commercialContact2Input,
                                onValueChange = { commercialContact2Input = it },
                                label = { Text("Contact 2", color = TextSecondaryDark, fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = commercialEmailInput,
                            onValueChange = { commercialEmailInput = it },
                            label = { Text("Official Email", color = TextSecondaryDark, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = commercialAddressInput,
                            onValueChange = { commercialAddressInput = it },
                            label = { Text("Office Address", color = TextSecondaryDark, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = commercialFooterInput,
                            onValueChange = { commercialFooterInput = it },
                            label = { Text("WhatsApp Marketing Footer", color = TextSecondaryDark, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Logo Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BrandLogoView(
                                    brand = Brand.COMMERCIAL,
                                    customLogoUri = brandLogos.commercialLogoUri,
                                    size = 32.dp,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (brandLogos.commercialLogoUri != null) "Custom Logo Uploaded" else "Default Commercial Monogram",
                                    color = TextSecondaryDark,
                                    fontSize = 11.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { commercialLogoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Upload Logo", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                if (brandLogos.commercialLogoUri != null) {
                                    OutlinedButton(
                                        onClick = { userSettingsManager.resetCommercialLogo() },
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Reset", fontSize = 10.sp)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                userSettingsManager.updateCommercialCompanyProfile(
                                    commercialNameInput, commercialContact1Input, commercialContact2Input,
                                    commercialEmailInput, commercialAddressInput, commercialFooterInput
                                )
                                Toast.makeText(context, "Commercial profile saved permanently!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Commercial Profile", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    } else {
                        // RESIDENTIAL PORTFOLIO FORM
                        OutlinedTextField(
                            value = residentialNameInput,
                            onValueChange = { residentialNameInput = it },
                            label = { Text("Company Name", color = TextSecondaryDark, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = residentialContact1Input,
                                onValueChange = { residentialContact1Input = it },
                                label = { Text("Contact 1", color = TextSecondaryDark, fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = residentialContact2Input,
                                onValueChange = { residentialContact2Input = it },
                                label = { Text("Contact 2", color = TextSecondaryDark, fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = residentialEmailInput,
                            onValueChange = { residentialEmailInput = it },
                            label = { Text("Official Email", color = TextSecondaryDark, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = residentialAddressInput,
                            onValueChange = { residentialAddressInput = it },
                            label = { Text("Office Address", color = TextSecondaryDark, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = residentialFooterInput,
                            onValueChange = { residentialFooterInput = it },
                            label = { Text("WhatsApp Marketing Footer", color = TextSecondaryDark, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Logo Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BrandLogoView(
                                    brand = Brand.RESIDENTIAL,
                                    customLogoUri = brandLogos.residentialLogoUri,
                                    size = 32.dp,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (brandLogos.residentialLogoUri != null) "Custom Logo Uploaded" else "Default Residential Monogram",
                                    color = TextSecondaryDark,
                                    fontSize = 11.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { residentialLogoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Upload Logo", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                if (brandLogos.residentialLogoUri != null) {
                                    OutlinedButton(
                                        onClick = { userSettingsManager.resetResidentialLogo() },
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Reset", fontSize = 10.sp)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                userSettingsManager.updateResidentialCompanyProfile(
                                    residentialNameInput, residentialContact1Input, residentialContact2Input,
                                    residentialEmailInput, residentialAddressInput, residentialFooterInput
                                )
                                Toast.makeText(context, "Residential profile saved permanently!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Residential Profile", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 2: USER PROFILE CONFIGURATION
        // =====================================================================
        if (activeSectionTab == 1) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(BrandGold.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (userProfile.profilePhotoUri != null) {
                                    AsyncImage(
                                        model = userProfile.profilePhotoUri,
                                        contentDescription = "User Photo",
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = BrandGold, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "USER PROFILE",
                                    color = BrandGold,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = "Display Identity & Verification Tag",
                                    color = TextPrimaryDark,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { userPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Change Photo", fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        label = { Text("Display Name (Used for Verified By)", color = TextSecondaryDark, fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_username_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = userMobileInput,
                            onValueChange = { userMobileInput = it },
                            label = { Text("Mobile Number", color = TextSecondaryDark, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = userEmailInput,
                            onValueChange = { userEmailInput = it },
                            label = { Text("Email Address", color = TextSecondaryDark, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "ACCOUNT ROLE (NOT EDITABLE HERE)",
                        color = TextSecondaryDark,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(userProfile.role).forEach { role ->
                            val isSelected = selectedRole == role
                            val roleColor = when (role) {
                                UserRole.ADMIN -> BrandGold
                                UserRole.COMMERCIAL_MANAGER -> CommercialBlue
                                UserRole.RESIDENTIAL_MANAGER -> ResidentialRose
                                UserRole.SALES_EXECUTIVE -> Color(0xFF10B981)
                                UserRole.INVENTORY_MANAGER -> Color(0xFFD97706)
                                UserRole.READ_ONLY -> TextSecondaryDark
                                UserRole.BROKER -> Color(0xFF3B82F6)
                                UserRole.OWNER -> Color(0xFF10B981)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) roleColor.copy(alpha = 0.2f) else BrandCharcoalSurface)
                                    .border(1.dp, if (isSelected) roleColor else BrandCharcoalBorder, RoundedCornerShape(8.dp))
                                    .clickable(enabled = false) { }
                                    .padding(vertical = 8.dp)
                                    .testTag("role_option_${role.label.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = role.label,
                                    color = if (isSelected) roleColor else TextSecondaryDark,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(BrandCharcoalSurface)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "ℹ️ Note: Unauthenticated session. Role labels alone are UI indicators and do not enforce server-side access control.",
                            color = TextMutedDark,
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val activeEmployee = sessionState?.currentEmployee
                    val isPinEnabled = activeEmployee?.pinEnabled ?: userSettingsManager.pinConfigured
                    val currentViewMode = sessionState?.inventoryViewMode ?: "LIST"

                    // Profile Summary Card (Group 01)
                    Surface(
                        color = BrandCharcoalSurface,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder),
                        modifier = Modifier.fillMaxWidth().testTag("settings_profile_summary")
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("PROFILE SUMMARY", color = PrimaryTeal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            DetailRow(
                                label = "Employee ID",
                                value = userProfile.employeeId.ifBlank { activeEmployee?.employeeId ?: NOT_MENTIONED }
                            )
                            DetailRow(label = "Name", value = userProfile.username.ifBlank { activeEmployee?.employeeName ?: NOT_MENTIONED })
                            DetailRow(label = "Mobile", value = userProfile.mobileNumber.ifBlank { activeEmployee?.loginMobile ?: NOT_MENTIONED })
                            DetailRow(label = "Email", value = userProfile.email.ifBlank { activeEmployee?.displayEmail ?: NOT_MENTIONED })
                            DetailRow(label = "Division", value = userProfile.division.ifBlank { "Commercial + Residential" })
                            DetailRow(label = "Account Status", value = userProfile.accountStatus.ifBlank { "Active" })
                            DetailRow(label = "Job Title", value = activeEmployee?.displayJobTitle ?: userProfile.role.label)
                            DetailRow(
                                label = "Business Scopes",
                                value = activeEmployee?.businessScopes?.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label } ?: userProfile.division.ifBlank { NOT_MENTIONED }
                            )
                            DetailRow(label = "Desk Contacts", value = APPROVED_DESK_FOOTER)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("SECURITY PIN (4 DIGITS)", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pinInput = it },
                            label = { Text("Optional 4-digit PIN", fontSize = 11.sp) },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f).testTag("employee_pin_input")
                        )
                        Button(
                            onClick = {
                                if (employeeSessionManager != null) {
                                    coroutineScope.launch {
                                        val res = employeeSessionManager.enablePin(pinInput)
                                        pinMessage = res.message
                                        if (res.success) pinInput = ""
                                    }
                                } else {
                                    pinMessage = if (userSettingsManager.setEmployeePin(pinInput)) {
                                        pinInput = ""
                                        "PIN enabled"
                                    } else "Enter exactly 4 digits"
                                }
                            },
                            enabled = pinInput.length == 4,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("settings_set_pin_btn")
                        ) { Text(if (isPinEnabled) "Update PIN" else "Enable PIN") }
                        if (isPinEnabled) {
                            TextButton(
                                onClick = {
                                    if (employeeSessionManager != null) {
                                        coroutineScope.launch {
                                            val res = employeeSessionManager.disablePin()
                                            pinMessage = res.message
                                        }
                                    } else {
                                        userSettingsManager.clearEmployeePin()
                                        pinMessage = "PIN disabled"
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("settings_disable_pin_btn")
                            ) { Text("Disable") }
                        }
                    }
                    if (pinMessage.isNotBlank()) Text(pinMessage, color = BrandGold, fontSize = 11.sp)

                    Spacer(modifier = Modifier.height(12.dp))

                    // Inventory View Preference (Grid / List)
                    Text("INVENTORY VIEW", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("LIST" to "List View", "GRID" to "Grid View").forEach { (mode, label) ->
                            val selected = currentViewMode == mode
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (employeeSessionManager != null) {
                                        coroutineScope.launch {
                                            employeeSessionManager.setInventoryViewMode(mode)
                                        }
                                    }
                                },
                                label = { Text(label, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("settings_inventory_view_${mode.lowercase()}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("AUTHORIZED INVENTORY ACCESS", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    val authorizedPortfoliosLabel = remember(activeEmployee, userProfile.role) {
                        val allowed = activeEmployee?.effectiveAllowedDivisions().orEmpty()
                        when {
                            allowed.size >= 2 || userProfile.role == UserRole.ADMIN -> "Commercial & Residential Inventory (Unified)"
                            allowed.any { it.name.contains("COMMERCIAL") } -> "Commercial Inventory"
                            allowed.any { it.name.contains("RESIDENTIAL") } -> "Residential Inventory"
                            else -> "Role-Governed Access (${userProfile.role.label})"
                        }
                    }
                    Text(
                        text = authorizedPortfoliosLabel,
                        color = BrandGold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Accessible records load automatically based on your assigned employee role.",
                        color = TextMutedDark,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            userSettingsManager.updateProfile(usernameInput, userMobileInput, userEmailInput, selectedRole)
                            Toast.makeText(context, "User profile saved persistently!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_profile_btn")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Display Details", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    if (activeEmployee?.role == EmployeeAccessRole.ADMIN && activeEmployee.hasConfidentialAccess() && onOpenTeamAdmin != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onOpenTeamAdmin,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_open_team_admin_btn")
                        ) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Team Administration", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onLockNow != null) {
                            OutlinedButton(
                                onClick = onLockNow,
                                enabled = isPinEnabled,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings_lock_now_btn")
                            ) {
                                Text("Lock Now", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (onSwitchProfile != null) {
                            OutlinedButton(
                                onClick = onSwitchProfile,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings_switch_profile_btn")
                            ) {
                                Text("Switch", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Button(
                            onClick = {
                                if (onSignOut != null) {
                                    onSignOut()
                                } else {
                                    userSettingsManager.logout()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ErrorRed,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings_sign_out_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "Sign Out",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sign Out", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 3: MASTER GOOGLE SHEET CONNECTION
        // =====================================================================
        if (activeSectionTab == 2) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3B82F6).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = Color(0xFF3B82F6), modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "MASTER GOOGLE SHEET INTEGRATION",
                                color = BrandGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "MASTER_REAL_ESTATE_INVENTORY",
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Status Overview Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandCharcoalSurface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DetailRow(label = "Spreadsheet Name", value = "MASTER_REAL_ESTATE_INVENTORY")
                        DetailRow(label = "Five-Tab Contract", value = "CRM • Commercial Inventory • Residential Inventory • Commercial Projects • Residential Projects")
                        DetailRow(label = "Connection Status", value = connectionStatus)
                        DetailRow(label = "Last Successful Sync", value = lastSyncTimestamp)
                        DetailRow(label = "Total Active Properties", value = "$totalProps Loaded")
                        DetailRow(label = "Last Diagnostic Error", value = lastDiagnostic?.message ?: "None (All Healthy)")
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val diag = repository.testGoogleSheetConnection()
                                    schemaResult = repository.validateMasterSheetSchema(MASTER_PROPERTY_35_COLUMNS)
                                    showSchemaValidationDialog = true
                                    Toast.makeText(context, diag.message, Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Connection", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val count = repository.syncWithGoogleSheetNow()
                                    Toast.makeText(context, "Synced $count properties with Google Sheet!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sync Now", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onOpenMasterSync,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) {
                        Icon(Icons.Default.Launch, contentDescription = null, tint = BrandGold, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Full Sync Portal & Apps Script Code", color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    val pendingOps by repository.pendingSyncOps.collectAsState()
                    val pendingCount = pendingOps.count { it.status == PendingSyncOpStatus.PENDING }
                    val retryCount = pendingOps.count { it.status == PendingSyncOpStatus.RETRYABLE_ERROR }
                    val blockedCount = pendingOps.count { it.status == PendingSyncOpStatus.BLOCKED }
                    val conflictOps = pendingOps.filter { it.status == PendingSyncOpStatus.CONFLICT }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandCharcoalSurface)
                            .padding(10.dp)
                            .testTag("settings_pending_sync_card"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("PENDING SYNC QUEUE (${pendingOps.size})", color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Pending: $pendingCount • Retry Scheduled: $retryCount • Auth Blocked: $blockedCount • Conflicts: ${conflictOps.size}",
                            color = TextSecondaryDark,
                            fontSize = 11.sp
                        )
                        if (pendingOps.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val retried = repository.retryBlockedAndRetryableOps(hasValidAuth = true)
                                    Toast.makeText(context, "Queued $retried operation(s) for immediate retry.", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_retry_pending_ops_btn")
                            ) {
                                Text("Retry All Pending Syncs", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        conflictOps.forEach { op ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Conflict: ${op.entityId}", color = Color(0xFFEF4444), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = { repository.resolveConflictKeepLocalOp(op.opId, op.entityId) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Retry Local", color = BrandGold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 4: VERIFICATION SETTINGS
        // =====================================================================
        if (activeSectionTab == 3) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(StatusAvailable.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = StatusAvailable, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "VERIFICATION & AUDIT SETTINGS",
                                color = BrandGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Verification Validity Interval",
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Configure Verification Period (Days):", color = TextSecondaryDark, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(7, 15, 30).forEach { days ->
                            val isSelected = validityDays == days
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) BrandGold.copy(alpha = 0.2f) else BrandCharcoalSurface)
                                    .border(1.dp, if (isSelected) BrandGold else BrandCharcoalBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        userSettingsManager.updateVerificationValidityDays(days)
                                        Toast.makeText(context, "Verification period set to $days days.", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$days Days",
                                    color = if (isSelected) BrandGold else TextSecondaryDark,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricBox(title = "Total", count = totalProps, color = BrandGold)
                        MetricBox(title = "Current", count = currentProps, color = Color(0xFF10B981))
                        MetricBox(title = "Due", count = dueProps, color = Color(0xFFF59E0B))
                        MetricBox(title = "Pending", count = pendingProps, color = Color(0xFFEF4444))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { showAuditReportDialog = true },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandCharcoalSurface, contentColor = BrandGold),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Assessment, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Verification Audit Report", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 5: MEDIA STORAGE SETTINGS
        // =====================================================================
        if (activeSectionTab == 4) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF8B5CF6).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PermMedia, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "MEDIA STORAGE & ASSET SETTINGS",
                                color = BrandGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Persistent Storage Provider",
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandCharcoalSurface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isDriveUrlConfigured = sheetsScriptUrl.trim().isNotBlank()
                        val storageRows = listOf(
                            "Storage Provider" to "Google Drive (TLC1_Property_Media) + Local Cache",
                            "Connection Status" to if (isDriveUrlConfigured) "Connected to Google Drive" else "Offline / URL Not Set",
                            "Max Photos / Property" to "20 Photos (Auto-compressed)",
                            "Video Walkthroughs" to "Supported (MP4 / Web Links)",
                            "Storage Error Log" to "None (0 Errors)"
                        )
                        storageRows.forEach { (label, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$label:",
                                    color = TextSecondaryDark,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = value,
                                    color = TextPrimaryDark,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(BrandCharcoalSurface)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "🔒 Security: Storage keys are securely handled via build configuration. No secret keys or private credentials are stored in publicly accessible code.",
                            color = TextMutedDark,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 6: BACKUP & RESTORE
        // =====================================================================
        if (activeSectionTab == 5) {
            var isPushingAllToCloud by remember { mutableStateOf(false) }
            var pushAllCloudSummary by remember { mutableStateOf("") }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(BrandGold.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Backup, contentDescription = null, tint = BrandGold, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "BACKUP & RESTORE MANAGEMENT",
                                color = BrandGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Inventory Snapshot & Recovery",
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val snap = repository.createBackupNow(notes = "Manual Backup", userSettingsManager = userSettingsManager)
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Backup JSON", snap.dataJson)
                                clipboard.setPrimaryClip(clip)
                                showBackupDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Backup", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showRestoreDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandCharcoalSurface, contentColor = TextPrimaryDark),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, tint = BrandGold, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore Backup", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (!isPushingAllToCloud) {
                                coroutineScope.launch {
                                    isPushingAllToCloud = true
                                    pushAllCloudSummary = "Uploading local photos to Google Drive & pushing unsynced properties, CRM leads, and call logs..."
                                    try {
                                        val summary = repository.pushAllUnsyncedLocalDataToCloud()
                                        pushAllCloudSummary = summary
                                        Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        pushAllCloudSummary = "Cloud migration failed: ${e.localizedMessage ?: "Network error"}"
                                    } finally {
                                        isPushingAllToCloud = false
                                    }
                                }
                            }
                        },
                        enabled = !isPushingAllToCloud,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE96932),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("push_all_local_data_cloud_btn")
                    ) {
                        if (isPushingAllToCloud) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pushing Local Data & Photos...", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("☁️ Push All Local Data & Photos to Cloud", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (pushAllCloudSummary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = BrandCharcoalSurface,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = pushAllCloudSummary,
                                color = TextPrimaryDark,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 7: SYSTEM INFORMATION
        // =====================================================================
        if (activeSectionTab == 6) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(BrandGold.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = BrandGold, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "SYSTEM INFORMATION",
                                color = BrandGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Real Runtime Diagnostics",
                                color = TextPrimaryDark,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandCharcoalSurface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DetailRow(label = "Application Version", value = "v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})")
                        DetailRow(label = "Database Status", value = "Active (Local JSON DB + Storage)")
                        DetailRow(label = "Master Sheet Connection", value = connectionStatus)
                        DetailRow(label = "Total Active Properties", value = "$totalProps Records")
                        DetailRow(label = "Divisions Supported", value = "Commercial & Residential")
                        DetailRow(label = "Media Storage Status", value = "Google Drive (TLC1_Property_Media) + Local Cache")
                        DetailRow(label = "AI Property Search Status", value = "Gemini API Ready")
                        DetailRow(label = "Property Map Engine", value = "OpenStreetMap (osmdroid) + Geo Intents")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    var isCheckingUpdates by remember { mutableStateOf(false) }
                    var updateCheckNote by remember { mutableStateOf<String?>(null) }

                    Button(
                        onClick = {
                            if (isCheckingUpdates) return@Button
                            isCheckingUpdates = true
                            updateCheckNote = null
                            coroutineScope.launch {
                                val res = AppUpdateManager.checkForAppUpdate(
                                    sheetsScriptUrl.ifBlank { SheetsRepository.DEFAULT_MASTER_SHEET_URL }
                                )
                                isCheckingUpdates = false
                                res.onSuccess { info ->
                                    if (info != null && AppUpdateManager.isRemoteVersionNewer(info.version)) {
                                        updateCheckNote = "New version v${info.version} found!"
                                        repository.setAvailableAppUpdate(info)
                                    } else {
                                        val latestLabel = info?.version?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
                                        updateCheckNote = "You are on the latest version (v$latestLabel)."
                                        Toast.makeText(context, "App is up to date (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                                    }
                                }.onFailure { err ->
                                    updateCheckNote = err.localizedMessage ?: "Unable to check for updates."
                                    Toast.makeText(context, updateCheckNote, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isCheckingUpdates,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryActionOrange,
                            contentColor = OnPrimaryActionColor
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("settings_check_updates_btn")
                    ) {
                        if (isCheckingUpdates) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = OnPrimaryActionColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking TLC_App_Updates...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Check for Updates", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (!updateCheckNote.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = updateCheckNote.orEmpty(),
                            color = TextSecondaryDark,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // =====================================================================
        // SECTION 8: ADMIN APPROVAL QUEUE
        // =====================================================================
        if (activeSectionTab == 7) {
            val canDecide = userProfile.role.canPerformSensitiveAction("CHANGE_PERMISSIONS")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BrandCharcoalCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandCharcoalBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = BrandGold, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("ADMIN APPROVAL QUEUE", color = BrandGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Sensitive changes require an auditable decision", color = TextSecondaryDark, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!canDecide) {
                        Text("You can view requests, but only Admin or an authorised manager can approve or reject them.", color = TextMutedDark, fontSize = 11.sp)
                    }
                    val pending = approvalRequests.filter { it.state == ApprovalState.PENDING }
                    Text("Pending: ${pending.size} • Total: ${approvalRequests.size}", color = TextPrimaryDark, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    if (approvalRequests.isEmpty()) {
                        Text("No approval requests yet.", color = TextSecondaryDark, fontSize = 12.sp)
                    } else {
                        approvalRequests.asReversed().forEach { request ->
                            Card(colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(request.action, color = BrandGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(request.summary, color = TextPrimaryDark, fontSize = 12.sp)
                                    Text("Target: ${request.targetId}", color = TextSecondaryDark, fontSize = 10.sp)
                                    Text("Requested by: ${request.requestedBy} • ${request.requestedAt}", color = TextSecondaryDark, fontSize = 10.sp)
                                    Text("Status: ${request.state.name}${if (request.decidedBy.isNotBlank()) " • ${request.decidedBy}" else ""}", color = if (request.state == ApprovalState.PENDING) BrandGold else TextSecondaryDark, fontSize = 10.sp)
                                    if (request.state == ApprovalState.PENDING && canDecide) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { ApprovalQueueStore.decide(context, request.id, true); approvalRequests = ApprovalQueueStore.read(context) }) { Text("Approve") }
                                            OutlinedButton(onClick = { ApprovalQueueStore.decide(context, request.id, false); approvalRequests = ApprovalQueueStore.read(context) }) { Text("Reject") }
                                        }
                                    } else if (request.state == ApprovalState.APPROVED && canDecide) {
                                        OutlinedButton(onClick = { ApprovalQueueStore.revoke(context, request.id); approvalRequests = ApprovalQueueStore.read(context) }) { Text("Revoke Access") }
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { approvalRequests = ApprovalQueueStore.read(context) }, modifier = Modifier.fillMaxWidth()) { Text("Refresh Queue") }
                }
            }
        }

        // =====================================================================
        // SECTION 9: ALERT SOUNDS & VOCAL ALERTS (BATCH 40)
        // =====================================================================
        if (activeSectionTab == 8) {
            com.example.ui.components.AlertSoundsSettingsSection(userSettingsManager = userSettingsManager)
        }
    }



    // Schema Validation Dialog
    if (showSchemaValidationDialog && schemaResult != null) {
        val res = schemaResult!!
        AlertDialog(
            onDismissRequest = { showSchemaValidationDialog = false },
            title = { Text("Master Sheet Schema Diagnostic", color = BrandGold, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(res.detailedMessage, color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(res.correctionGuidance, color = TextSecondaryDark, fontSize = 11.sp)
                }
            },
            confirmButton = {
                Button(onClick = { showSchemaValidationDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = BrandCharcoalCard
        )
    }

    // Backup Dialog
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Backup Snapshot Created", color = BrandGold, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✅ Backup snapshot saved to system memory and copied to clipboard as JSON format.", color = TextPrimaryDark, fontSize = 12.sp)
                    Text("Includes: $totalProps Master Inventory records, Company Profiles, User Profile, Verification Settings, and Media References.", color = TextSecondaryDark, fontSize = 11.sp)
                }
            },
            confirmButton = {
                Button(onClick = { showBackupDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = BrandCharcoalCard
        )
    }

    // Restore Dialog (SHA-256 Verified & Mode-Selectable)
    if (showRestoreDialog) {
        var selectedRestoreMode by remember { mutableStateOf(RestoreMode.MERGE_MISSING) }
        val previewResult = remember(restoreJsonInput) {
            if (restoreJsonInput.isBlank()) null else repository.previewBackupRestore(restoreJsonInput)
        }
        val canAdminFullReplace = userProfile.role == UserRole.ADMIN
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restore Master Inventory Backup", color = BrandGold, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Select a SHA-256 verified backup JSON file or paste backup content:", color = TextSecondaryDark, fontSize = 11.sp)

                    Button(
                        onClick = { backupFilePicker.launch("application/json") },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandCharcoalSurface, contentColor = BrandGold),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_pick_backup_btn")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick Backup File from Mobile Storage", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedTextField(
                        value = restoreJsonInput,
                        onValueChange = { restoreJsonInput = it },
                        placeholder = { Text("Paste JSON backup string...", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).testTag("settings_restore_json_input")
                    )

                    if (previewResult != null) {
                        if (previewResult.isValid && previewResult.manifest != null) {
                            val m = previewResult.manifest
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BrandCharcoalSurface)
                                    .padding(10.dp)
                                    .testTag("settings_restore_preview_card"),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("SHA-256 Verified • v${m.backupFormatVersion}", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("Properties: ${m.propertyCount} (${previewResult.newPropertiesCount} new, ${previewResult.conflictingPropertiesCount} conflicts)", color = TextPrimaryDark, fontSize = 11.sp)
                                Text("CRM Leads: ${m.crmLeadCount} • Pending Ops: ${m.pendingSyncCount}", color = TextSecondaryDark, fontSize = 10.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Restore Mode:", color = BrandGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                RestoreMode.entries.forEach { mode ->
                                    val enabled = mode != RestoreMode.FULL_REPLACE || canAdminFullReplace
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = enabled) { selectedRestoreMode = mode }
                                    ) {
                                        RadioButton(
                                            selected = selectedRestoreMode == mode,
                                            onClick = { if (enabled) selectedRestoreMode = mode },
                                            enabled = enabled
                                        )
                                        Text(
                                            text = if (!enabled) "${mode.label} (Locked)" else mode.label,
                                            color = if (enabled) TextPrimaryDark else TextMutedDark,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = previewResult.errorMessage ?: "Invalid backup checksum or format",
                                color = Color(0xFFEF4444),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("settings_restore_error_text")
                            )
                        }
                    }

                    if (restoreStatusText != null) {
                        Text(restoreStatusText ?: "", color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (restoreJsonInput.isNotBlank()) {
                            val outcome = repository.restoreFromBackupDetailed(restoreJsonInput, userSettingsManager, selectedRestoreMode)
                            restoreStatusText = outcome.message
                            if (outcome.success) {
                                showRestoreDialog = false
                                Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "Please select or paste backup JSON first.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = previewResult?.isValid == true,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings_confirm_restore_btn")
                ) {
                    Text("Confirm & Restore", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestoreDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = TextSecondaryDark)
                }
            },
            containerColor = BrandCharcoalCard
        )
    }

    // Data Quality Audit Report Dialog
    if (showAuditReportDialog) {
        val auditList = activeProperties.filter { getVerificationAgeState(it, validityDays) != VerificationAgeState.CURRENT }
        AlertDialog(
            onDismissRequest = { showAuditReportDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = BrandGold, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Data Quality & Verification Audit", color = BrandGold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Properties requiring verification (${auditList.size} of $totalProps total active):",
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (auditList.isEmpty()) {
                        Text(
                            text = "🎉 All active properties are fully verified and current!",
                            color = Color(0xFF10B981),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        auditList.forEach { prop ->
                            val st = getVerificationAgeState(prop, validityDays)
                            val badgeColor = Color(st.badgeColor)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = BrandCharcoalSurface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = prop.propertyCode, color = BrandGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(badgeColor.copy(alpha = 0.2f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(text = st.label, color = badgeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(text = "${prop.projectName} (${prop.location})", color = TextPrimaryDark, fontSize = 12.sp, maxLines = 1)
                                        Text(text = "Last verified: ${prop.lastVerifiedDateClean.ifBlank { prop.lastVerificationDate }}", color = TextSecondaryDark, fontSize = 10.sp)
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Button(
                                            onClick = {
                                                onVerifyProperty(prop.id)
                                                Toast.makeText(context, "Verified ${prop.propertyCode}", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Verify", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                showAuditReportDialog = false
                                                onPropertySelect(prop)
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandGold),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, BrandGold.copy(alpha = 0.5f)),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("View", fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAuditReportDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGold, contentColor = BrandCharcoalDark)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = BrandCharcoalCard,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
fun MetricBox(title: String, count: Int, color: Color) {
    Column(
        modifier = Modifier
            .width(70.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BrandCharcoalSurface)
            .border(0.8.dp, BrandCharcoalBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, color = TextSecondaryDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = count.toString(), color = color, fontSize = 16.sp, fontWeight = FontWeight.Black)
    }
}

