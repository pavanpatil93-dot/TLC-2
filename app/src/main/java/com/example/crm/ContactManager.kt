package com.example.crm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PropertyItem
import org.json.JSONArray

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.SheetsRepository
import com.example.model.UserProfile
import com.example.ui.theme.*

enum class CallableContactType(val label: String) {
    OWNER("Owner"),
    BROKER("Broker"),
    EMPLOYEE("Assigned Executive"),
    DESK("Desk Contact")
}

data class CallableContact(
    val contactType: CallableContactType,
    val displayName: String,
    val rawNumber: String,
    val dialNumber: String,
    val extension: String = "",
    val combinedCategoryLabel: String = contactType.label
) {
    val key: String get() = "${contactType.name}_$dialNumber"
}

object ContactUtils {

    private val KNOWN_PLACEHOLDER_NUMBERS = setOf(
        "0000000000",
        "1234567890",
        "9999999999",
        "9876543210"
    )

    private val PLACEHOLDER_WORD_REGEX = Regex("""(?i)\b(n/?a|tbd|null|none|unknown|not\s+mentioned)\b""")
    private val EXTENSION_SUFFIX_REGEX = Regex("""(?i)(?:^|[\s,\)\-]|(?<=\d))(?:ext\.?|x)\s*(\d{1,6})\s*$""")
    private val LETTER_TOUCHING_DIGIT_REGEX = Regex("""(?:\d[a-zA-Z]|[a-zA-Z]\d)""")

    @Volatile
    private var lastDialLaunchTimeMs: Long = 0L

    fun canonicalTenDigitIndianKey(rawToken: String?): String? {
        if (rawToken.isNullOrBlank()) return null
        val digits = rawToken.trim().filter { it.isDigit() }
        val tenDigits = when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
            digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
            digits.length == 13 && digits.startsWith("910") -> digits.substring(3)
            digits.length == 14 && digits.startsWith("0091") -> digits.substring(4)
            else -> return null
        }
        if (tenDigits.length != 10) return null
        if (tenDigits in KNOWN_PLACEHOLDER_NUMBERS) return null
        if (tenDigits.all { it == '0' }) return null
        return tenDigits
    }

    fun parseAndSanitizeCallableNumbers(rawField: String?): List<Pair<String, String>> {
        if (rawField.isNullOrBlank()) return emptyList()

        val primarySegments = rawField.split(',', ';', '|', '\n', '\r')
        val candidateTokens = mutableListOf<String>()
        for (segment in primarySegments) {
            val s = segment.trim()
            if (s.isEmpty()) continue
            if (s.contains("://") || s.contains(':') || s.contains('?') || s.contains('&') || s.contains('=')) {
                continue
            }
            if (s.contains('/')) {
                s.split('/').forEach { part ->
                    val p = part.trim()
                    if (p.isNotEmpty()) candidateTokens.add(p)
                }
            } else {
                candidateTokens.add(s)
            }
        }

        val seenKeys = LinkedHashMap<String, Pair<String, String>>()
        for (token in candidateTokens) {
            if (token.isBlank()) continue
            if (token.any { it == '*' || it == '#' || it == ':' || it == '/' || it == '?' || it == '&' || it == '=' }) {
                continue
            }
            if (PLACEHOLDER_WORD_REGEX.containsMatchIn(token)) continue

            var extension = ""
            var base = token.trim()
            val extMatch = EXTENSION_SUFFIX_REGEX.find(base)
            if (extMatch != null) {
                extension = extMatch.groupValues[1]
                base = base.substring(0, extMatch.range.first).trim()
            }

            if (base.isEmpty()) continue
            if (PLACEHOLDER_WORD_REGEX.containsMatchIn(base)) continue
            if (LETTER_TOUCHING_DIGIT_REGEX.containsMatchIn(base)) continue

            val plusCount = base.count { it == '+' }
            if (plusCount > 1 || (plusCount == 1 && !base.trimStart().startsWith("+"))) {
                continue
            }

            val canonical10 = canonicalTenDigitIndianKey(base) ?: continue
            val dialNumber = "+91$canonical10"
            val existing = seenKeys[canonical10]
            if (existing == null) {
                seenKeys[canonical10] = dialNumber to extension
            } else if (existing.second.isBlank() && extension.isNotBlank()) {
                seenKeys[canonical10] = existing.first to extension
            }
        }
        return seenKeys.values.toList()
    }

    fun launchSafeDialer(
        context: Context,
        propertyId: String,
        option: CallableContact,
        repository: SheetsRepository,
        activeUser: UserProfile,
        isInternalMode: Boolean,
        onError: (String) -> Unit
    ) {
        val now = System.currentTimeMillis()
        if (now - lastDialLaunchTimeMs < 1000L) {
            return
        }

        val propertyExists = repository.properties.value.any { it.id == propertyId }
        if (!propertyExists) {
            onError("Property is no longer available.")
            return
        }

        val authorizedContacts = repository.resolveAuthorizedCallableContacts(
            propertyId = propertyId,
            activeUser = activeUser,
            isInternalMode = isInternalMode
        )
        val verifiedOption = authorizedContacts.firstOrNull { it.dialNumber == option.dialNumber }
        if (verifiedOption == null) {
            onError("Selected contact is no longer authorized or available.")
            return
        }

        lastDialLaunchTimeMs = now

        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", verifiedOption.dialNumber, null)).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val launched = runCatching {
            context.startActivity(dialIntent)
        }.isSuccess

        if (launched) {
            val categoryLabel = verifiedOption.combinedCategoryLabel.ifBlank { verifiedOption.contactType.label }
            CrmCallManager.logActivity(
                context = context,
                entityId = propertyId,
                type = "CALL_DIALER_OPENED",
                summary = "Dialer opened ($categoryLabel)",
                details = "",
                author = activeUser.username.trim().ifBlank { activeUser.role.label }
            )
        } else {
            onError("No phone dialer app available. Use Copy Number below.")
        }
    }

    fun copyAuthorizedNumber(
        context: Context,
        option: CallableContact,
        onResult: (String) -> Unit
    ) {
        val copied = runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@runCatching false
            val clip = ClipData.newPlainText("Property Contact", option.dialNumber)
            clipboard.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
        if (copied) {
            onResult("Number copied")
        } else {
            onResult("Unable to copy number")
        }
    }

    fun normalizePhoneNumber(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
            digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
            digits.length > 10 -> digits.takeLast(10)
            else -> digits
        }
    }

    fun formatDisplayPhone(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val clean = normalizePhoneNumber(raw)
        return if (clean.length == 10) {
            "+91 ${clean.substring(0, 5)} ${clean.substring(5)}"
        } else raw.trim()
    }

    fun extractContactFromUri(context: Context, contactUri: Uri): Pair<String, String>? {
        return try {
            // Case 1: Phone URI directly (e.g. ACTION_PICK Phone.CONTENT_URI)
            val directCursor = runCatching {
                context.contentResolver.query(
                    contactUri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null,
                    null,
                    null
                )
            }.getOrNull()
            directCursor?.use { c ->
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numIdx >= 0 && c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else ""
                    val number = c.getString(numIdx).orEmpty()
                    val normalized = normalizePhoneNumber(number)
                    if (normalized.isNotBlank() || name.isNotBlank()) {
                        return Pair(name.trim(), normalized)
                    }
                }
            }

            // Case 2: Contact Lookup URI (ActivityResultContracts.PickContact)
            var contactId = ""
            var contactName = ""
            context.contentResolver.query(
                contactUri,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
                    val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    if (idIdx >= 0) contactId = c.getString(idIdx).orEmpty()
                    if (nameIdx >= 0) contactName = c.getString(nameIdx).orEmpty()
                }
            }

            var pickedNumber = ""
            if (contactId.isNotBlank()) {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )?.use { pCur ->
                    while (pCur.moveToNext()) {
                        val idx = pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val candidate = if (idx >= 0) normalizePhoneNumber(pCur.getString(idx)) else ""
                        if (candidate.length >= 10) {
                            pickedNumber = candidate
                            break
                        } else if (pickedNumber.isBlank() && candidate.isNotBlank()) {
                            pickedNumber = candidate
                        }
                    }
                }
            }

            if (contactName.isNotBlank() || pickedNumber.isNotBlank()) {
                Pair(contactName.trim(), pickedNumber)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun rememberPickContactCallback(
    onContactPicked: (name: String, cleanPhone10: String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val pickContactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        if (uri != null) {
            val extracted = ContactUtils.extractContactFromUri(context, uri)
            if (extracted != null) {
                val (pickedName, cleanPhone) = extracted
                if (pickedName.isNotBlank() || cleanPhone.isNotBlank()) {
                    onContactPicked(pickedName, cleanPhone)
                } else {
                    Toast.makeText(context, "Selected contact has no phone number", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Unable to read selected contact number", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pickContactLauncher.launch(null)
        } else {
            Toast.makeText(context, "Contacts permission required to pick a phone number", Toast.LENGTH_SHORT).show()
        }
    }
    return {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            pickContactLauncher.launch(null)
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseContactModalSheet(
    property: PropertyItem,
    contacts: List<CallableContact>,
    canEditContact: Boolean = false,
    onEditContact: (() -> Unit)? = null,
    onCallContact: (CallableContact, (String) -> Unit) -> Unit,
    onCopyContact: (CallableContact, (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val navy = TextPrimary
    val teal = PrimaryActionOrange
    val softWhite = BackgroundSoft
    val borderColor = BorderGrey

    var feedbackMessage by remember(property.id) { mutableStateOf<String?>(null) }
    var feedbackIsError by remember(property.id) { mutableStateOf(false) }

    val hasPrivateContacts = remember(contacts) {
        contacts.any { it.contactType != CallableContactType.DESK }
    }

    val headerSubtitle = remember(property.propertyCode, property.projectName) {
        listOf(
            property.propertyCode.trim(),
            property.projectName.trim().ifBlank { "Property" }
        ).filter { it.isNotBlank() }.joinToString(" • ")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = softWhite,
        contentColor = navy,
        modifier = Modifier.testTag("choose_contact_modal_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Contact Property",
                        color = navy,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = headerSubtitle,
                        color = SecondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = navy)
                }
            }

            HorizontalDivider(color = borderColor)

            if (feedbackMessage != null) {
                Surface(
                    color = if (feedbackIsError) ErrorRedSubtle else SelectedSurfaceContainer,
                    border = BorderStroke(1.dp, if (feedbackIsError) StatusDangerRed else FocusOutlineColor),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("choose_contact_feedback_banner")
                ) {
                    Text(
                        text = feedbackMessage.orEmpty(),
                        color = if (feedbackIsError) StatusDangerRed else navy,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (!hasPrivateContacts) {
                Surface(
                    color = SurfaceWhite,
                    border = BorderStroke(1.dp, borderColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("no_private_contact_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "No callable private contact available",
                            color = navy,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (canEditContact && onEditContact != null) {
                            OutlinedButton(
                                onClick = onEditContact,
                                border = BorderStroke(1.dp, FocusOutlineColor),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = FocusOutlineColor),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("choose_contact_edit_btn")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Edit Contact", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            if (!hasPrivateContacts && contacts.isNotEmpty()) {
                Text(
                    text = "CALL DESK",
                    color = SecondaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
            }

            contacts.forEach { contact ->
                Surface(
                    color = SurfaceWhite,
                    border = BorderStroke(1.dp, borderColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_option_${contact.key}")
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
                            Surface(
                                color = SelectedSurfaceContainer,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(0.8.dp, FocusOutlineColor.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = contact.combinedCategoryLabel.uppercase(),
                                    color = FocusOutlineColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            if (contact.extension.isNotBlank()) {
                                Surface(
                                    color = WarningAmberSubtle,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(0.8.dp, StatusWarningAmber)
                                ) {
                                    Text(
                                        text = "Ext: ${contact.extension}",
                                        color = StatusWarningAmber,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = contact.displayName.ifBlank { "Not Mentioned" },
                                color = navy,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = ContactUtils.formatDisplayPhone(contact.dialNumber),
                                color = SecondaryText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    feedbackMessage = null
                                    feedbackIsError = false
                                    onCallContact(contact) { err ->
                                        feedbackIsError = true
                                        feedbackMessage = err
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
                                    .testTag("contact_call_btn_${contact.key}")
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (contact.contactType == CallableContactType.DESK) "Call Desk" else "Call",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    onCopyContact(contact) { msg ->
                                        feedbackIsError = msg != "Number copied"
                                        feedbackMessage = msg
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                border = BorderStroke(1.dp, navy.copy(alpha = 0.35f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = navy),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("contact_copy_btn_${contact.key}")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(1.dp, borderColor),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SurfaceWhite,
                    contentColor = navy
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("choose_contact_cancel_btn")
            ) {
                Text("Cancel", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

data class ContactMatch(
    val role: String,
    val name: String,
    val phone: String,
    val detail: String
)

fun findDuplicateContacts(
    context: Context,
    phoneInput: String?,
    allProperties: List<PropertyItem> = emptyList()
): List<ContactMatch> {
    val clean = ContactUtils.normalizePhoneNumber(phoneInput)
    if (clean.length < 7) return emptyList()

    val results = mutableListOf<ContactMatch>()

    // 1. Check CRM leads
    runCatching {
        val raw = com.example.data.auditedCrmPreferences(context, "crm_development_v1", Context.MODE_PRIVATE)
            .getString("leads", "[]") ?: "[]"
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val leadPhone = ContactUtils.normalizePhoneNumber(item.optString("phone"))
            if (leadPhone == clean && clean != "0000000000") {
                results.add(
                    ContactMatch(
                        role = "Client Lead",
                        name = item.optString("name", "Unnamed Client"),
                        phone = item.optString("phone"),
                        detail = "${item.optString("segment")} • ${item.optString("status")} • ${item.optString("requirement")}"
                    )
                )
            }
        }
    }

    // 2. Check Property Owners
    for (prop in allProperties) {
        val ownerPhone = ContactUtils.normalizePhoneNumber(prop.ownerContact)
        if (ownerPhone == clean && clean.isNotBlank() && clean != "0000000000") {
            results.add(
                ContactMatch(
                    role = "Property Owner",
                    name = prop.ownerName.orEmpty().ifBlank { "Owner" },
                    phone = prop.ownerContact.orEmpty(),
                    detail = "Property ${prop.propertyCode} (${prop.projectName})"
                )
            )
        }
    }

    return results.distinctBy { "${it.role}_${it.name}_${it.phone}" }
}

@Composable
fun ReusableContactPickerField(
    name: String,
    onNameChange: (String) -> Unit,
    phone: String,
    onPhoneChange: (String) -> Unit,
    labelPrefix: String = "Contact",
    allProperties: List<PropertyItem> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val duplicates = remember(phone, allProperties) {
        findDuplicateContacts(context, phone, allProperties)
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                ContactUtils.extractContactFromUri(context, uri)?.let { (pickedName, pickedPhone) ->
                    if (pickedName.isNotBlank() && name.isBlank()) {
                        onNameChange(pickedName)
                    }
                    if (pickedPhone.isNotBlank()) {
                        onPhoneChange(pickedPhone)
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$labelPrefix Information",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            TextButton(
                onClick = {
                    val pickIntent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                    contactPickerLauncher.launch(pickIntent)
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContactPhone,
                    contentDescription = "Pick from device contacts",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text("Pick from Contacts", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("$labelPrefix Name *") },
            placeholder = { Text("Full name / Company") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            label = { Text("$labelPrefix Mobile Number *") },
            placeholder = { Text("10-digit mobile number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        if (duplicates.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFEF2F2))
                    .border(1.dp, Color(0xFFFCA5A5), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Duplicate warning",
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Existing Contact Found (${duplicates.size}):",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color(0xFF991B1B)
                        )
                    }
                    duplicates.take(2).forEach { match ->
                        Text(
                            text = "• ${match.name} (${match.role}) — ${match.detail}",
                            fontSize = 10.5.sp,
                            color = Color(0xFF7F1D1D),
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

