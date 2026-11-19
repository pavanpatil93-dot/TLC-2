package com.example.crm

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

enum class TlcContactRolePrefix {
    CLIENT_LEAD,
    INVESTOR,
    OWNER,
    BROKER_CP,
    DEVELOPER_SALES,
    SOURCING_MANAGER,
    TENANT,
    OPS_VENDOR
}

data class GoogleContactSaveRequest(
    val rolePrefix: TlcContactRolePrefix,
    val rawName: String,
    val phone: String,
    val projectOrUnit: String = "",
    val areaOrFirm: String = "",
    val subRole: String = "",
    val referenceId: String = "",
    val extraNotes: String = ""
)

data class GoogleContactSyncOutcome(
    val attempted: Boolean,
    val succeeded: Boolean,
    val updatedExisting: Boolean = false,
    val formattedDisplayName: String = "",
    val reason: String = ""
)

/**
 * Batch 18: Auto-Save Linked Callers to Google Contacts with Standard TLC Prefixes:
 * - Client Lead: "CL - <Name>"
 * - Investor: "INV - <Name>"
 * - Owner: "O - <Name> - <Project/Unit>"
 * - Broker / CP: "CP - <Name> - <Area/Firm>"
 * - Developer Sales Team: "BLD - <Name> - <Project>"
 * - Sourcing Manager: "SM - <Name> - <Project>"
 * - Tenant: "TNT - <Name> - <Project/Unit>"
 * - Ops / Vendor: "OPS - <SubRole> - <Name>"
 *
 * Checks existing contacts by normalized 10-digit number before inserting to prevent duplicates,
 * and stores Property ID / Lead ID / "TLC App" tag in the Google Contact's Notes field.
 */
object GoogleContactsSyncHelper {

    fun hasContactsPermission(context: Context): Boolean {
        val writeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        return writeGranted && readGranted
    }

    fun formatTlcContactDisplayName(request: GoogleContactSaveRequest): String {
        val cleanName = request.rawName.trim()
            .ifBlank { "Contact" }
            .replace(Regex("^(CL|INV|O|CP|BLD|SM|TNT|OPS)\\s*-\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        val projectUnit = request.projectOrUnit.trim()
        val areaFirm = request.areaOrFirm.trim()
        val subRole = request.subRole.trim().ifBlank { "Ops" }

        return when (request.rolePrefix) {
            TlcContactRolePrefix.CLIENT_LEAD -> "CL - $cleanName"
            TlcContactRolePrefix.INVESTOR -> "INV - $cleanName"
            TlcContactRolePrefix.OWNER -> listOf("O", cleanName, projectUnit)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            TlcContactRolePrefix.BROKER_CP -> listOf("CP", cleanName, areaFirm)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            TlcContactRolePrefix.DEVELOPER_SALES -> listOf("BLD", cleanName, projectUnit)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            TlcContactRolePrefix.SOURCING_MANAGER -> listOf("SM", cleanName, projectUnit)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            TlcContactRolePrefix.TENANT -> listOf("TNT", cleanName, projectUnit)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            TlcContactRolePrefix.OPS_VENDOR -> listOf("OPS", subRole, cleanName)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
        }
    }

    fun buildTlcContactNotes(request: GoogleContactSaveRequest): String {
        val parts = mutableListOf("TLC App")
        if (request.referenceId.isNotBlank()) {
            parts.add("Ref: ${request.referenceId.trim()}")
        }
        if (request.projectOrUnit.isNotBlank()) {
            parts.add("Unit/Project: ${request.projectOrUnit.trim()}")
        }
        if (request.areaOrFirm.isNotBlank()) {
            parts.add("Firm/Area: ${request.areaOrFirm.trim()}")
        }
        if (request.extraNotes.isNotBlank()) {
            parts.add(request.extraNotes.trim().take(240))
        }
        return parts.joinToString(" | ")
    }

    suspend fun upsertContactIfPermitted(
        context: Context,
        request: GoogleContactSaveRequest
    ): GoogleContactSyncOutcome = withContext(Dispatchers.IO) {
        val clean10 = ContactUtils.normalizePhoneNumber(request.phone)
        if (clean10.length < 7 || request.rawName.isBlank()) {
            return@withContext GoogleContactSyncOutcome(
                attempted = false,
                succeeded = false,
                reason = "Name or phone missing"
            )
        }
        if (!hasContactsPermission(context)) {
            return@withContext GoogleContactSyncOutcome(
                attempted = false,
                succeeded = false,
                reason = "WRITE_CONTACTS permission not granted"
            )
        }

        val displayName = formatTlcContactDisplayName(request)
        val noteText = buildTlcContactNotes(request)
        val e164Phone = if (clean10.length == 10) "+91$clean10" else request.phone.trim()

        runCatching {
            val resolver = context.contentResolver
            val existingRawContactId = findRawContactIdByPhone(resolver, clean10)
            val ops = ArrayList<ContentProviderOperation>()

            if (existingRawContactId != null) {
                // Update existing contact name without creating a duplicate
                val updatedNameCount = updateMimeRow(
                    resolver = resolver,
                    rawContactId = existingRawContactId,
                    mimeType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    valueColumn = ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    newValue = displayName,
                    ops = ops
                )
                if (!updatedNameCount) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, existingRawContactId)
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                            )
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                            .build()
                    )
                }

                // Update or insert Note with Property/Lead ID & "TLC App" tag
                val updatedNoteCount = updateMimeRow(
                    resolver = resolver,
                    rawContactId = existingRawContactId,
                    mimeType = ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                    valueColumn = ContactsContract.CommonDataKinds.Note.NOTE,
                    newValue = noteText,
                    ops = ops
                )
                if (!updatedNoteCount) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, existingRawContactId)
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                            )
                            .withValue(ContactsContract.CommonDataKinds.Note.NOTE, noteText)
                            .build()
                    )
                }

                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                GoogleContactSyncOutcome(
                    attempted = true,
                    succeeded = true,
                    updatedExisting = true,
                    formattedDisplayName = displayName,
                    reason = "Updated existing contact"
                )
            } else {
                // Insert new contact
                val rawIdx = 0
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, e164Phone)
                        .withValue(
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        )
                        .build()
                )
                if (request.areaOrFirm.isNotBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
                            )
                            .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, request.areaOrFirm.trim())
                            .build()
                    )
                }
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIdx)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Note.NOTE, noteText)
                        .build()
                )
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                GoogleContactSyncOutcome(
                    attempted = true,
                    succeeded = true,
                    updatedExisting = false,
                    formattedDisplayName = displayName,
                    reason = "Created new Google Contact"
                )
            }
        }.getOrElse { err ->
            GoogleContactSyncOutcome(
                attempted = true,
                succeeded = false,
                formattedDisplayName = displayName,
                reason = err.localizedMessage ?: "Contact save failed"
            )
        }
    }

    private fun findRawContactIdByPhone(
        resolver: android.content.ContentResolver,
        targetLast10: String
    ): Long? {
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
            arrayOf("%${targetLast10.takeLast(7)}%"),
            null
        )?.use { cursor ->
            val rawIdIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val num = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else ""
                val norm = ContactUtils.normalizePhoneNumber(num)
                if (norm == targetLast10 && rawIdIdx >= 0) {
                    return cursor.getLong(rawIdIdx)
                }
            }
        }
        return null
    }

    private fun updateMimeRow(
        resolver: android.content.ContentResolver,
        rawContactId: Long,
        mimeType: String,
        valueColumn: String,
        newValue: String,
        ops: ArrayList<ContentProviderOperation>
    ): Boolean {
        val exists = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), mimeType),
            null
        )?.use { c -> c.moveToFirst() } == true

        if (exists) {
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(rawContactId.toString(), mimeType)
                    )
                    .withValue(valueColumn, newValue)
                    .build()
            )
        }
        return exists
    }
}
