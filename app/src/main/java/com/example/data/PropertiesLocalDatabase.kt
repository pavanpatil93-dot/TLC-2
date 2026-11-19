package com.example.data

import android.content.Context
import android.util.Log
import com.example.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PropertiesLocalDatabase {

    private const val TAG = "PropertiesLocalDatabase"
    private const val DB_FILE_NAME = "real_estate_inventory_db.json"

    private const val LEGACY_DB_FILE_NAME = "ascend_aurea_inventory_db.json"

    @Volatile
    internal var testSimulateWriteFailure: Boolean = false

    fun computeSha256(content: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    internal data class InventoryState(
        val properties: List<PropertyItem>,
        val pending: List<PendingSyncWrite>
    )

    private fun parseStateText(raw: String): InventoryState {
        val envelope = if (raw.trim().startsWith("[")) null else JSONObject(raw)
        if (envelope != null) require(envelope.getInt("storageFormat") == 3) {
            "Unsupported inventory storage format"
        }
        val values = envelope?.getJSONArray("properties") ?: JSONArray(raw)
        val properties = (0 until values.length()).map {
            deserializeProperty(values.getJSONObject(it))
        }
        val writes = envelope?.optJSONArray("pendingSync") ?: JSONArray()
        val pending = (0 until writes.length()).map { index ->
            val entry = writes.getJSONObject(index)
            PendingSyncWrite(
                operationId = entry.getString("operationId"),
                property = deserializeProperty(entry.getJSONObject("property")),
                createdAt = entry.optLong("createdAt", 0L),
                attempts = entry.optInt("attempts", 0),
                nextAttemptAt = entry.optLong("nextAttemptAt", 0L),
                state = runCatching { PendingWriteState.valueOf(entry.optString("state", "READY")) }.getOrDefault(PendingWriteState.REVIEW),
                lastError = entry.optString("lastError", "")
            )
        }
        val legacyReview = if (envelope == null) properties.mapIndexedNotNull { index, property ->
            if (!property.isSyncedToSheet && !values.getJSONObject(index).has("isSyncedToSheet")) {
                PendingSyncWrite("legacy-review-" + property.id, property, 0L,
                    state = PendingWriteState.REVIEW,
                    lastError = "Legacy upstream status unknown; review before sending")
            } else null
        } else emptyList()
        return InventoryState(properties, pending + legacyReview)
    }

    private fun readState(context: Context): InventoryState {
        val file = File(context.filesDir, DB_FILE_NAME)
        val legacy = File(context.filesDir, LEGACY_DB_FILE_NAME)
        val atomic = android.util.AtomicFile(file)
        val hasCurrent = file.exists() || File(context.filesDir, "$DB_FILE_NAME.bak").exists()
        val raw = when {
            hasCurrent -> atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            legacy.exists() && legacy.length() > 0 -> legacy.readText()
            else -> return InventoryState(emptyList(), emptyList())
        }
        // Legacy array files are read without rewriting them.
        return parseStateText(raw)
    }

    @Synchronized
    fun inspectStorageHealth(context: Context): StorageHealthReport {
        val file = File(context.filesDir, DB_FILE_NAME)
        val bakFile = File(context.filesDir, "$DB_FILE_NAME.bak")
        val legacy = File(context.filesDir, LEGACY_DB_FILE_NAME)
        if (!file.exists() && !bakFile.exists() && !legacy.exists()) {
            return StorageHealthReport(state = StorageHealthState.HEALTHY, message = "Clean storage")
        }
        val primaryOk = if (file.exists()) {
            runCatching { parseStateText(file.readText()) }.isSuccess
        } else {
            !bakFile.exists() // if no primary and no bak, maybe legacy exists
        }
        if (primaryOk) {
            if (!file.exists() && legacy.exists()) {
                val legacyOk = runCatching { parseStateText(legacy.readText()) }.isSuccess
                if (!legacyOk) {
                    return StorageHealthReport(
                        state = StorageHealthState.CORRUPT_REQUIRES_ACTION,
                        corruptFilePath = legacy.absolutePath,
                        hasValidBackup = false,
                        message = "Corrupt legacy storage detected. Choose a recovery action."
                    )
                }
            }
            return StorageHealthReport(state = StorageHealthState.HEALTHY, message = "Storage healthy")
        }
        // Primary is missing or corrupt while primary or .bak exists
        val backupState = if (bakFile.exists()) {
            runCatching { parseStateText(bakFile.readText()) }.getOrNull()
        } else null
        val corruptPath = if (file.exists()) file.absolutePath else null
        return StorageHealthReport(
            state = StorageHealthState.CORRUPT_REQUIRES_ACTION,
            corruptFilePath = corruptPath,
            hasValidBackup = backupState != null,
            backupRecordCount = backupState?.properties?.size ?: 0,
            message = if (backupState != null) {
                "Corrupt inventory file detected. Valid backup (${backupState.properties.size} properties) is available."
            } else {
                "Corrupt inventory file detected and no automatic backup is readable. Quarantine and reset or restore a manual backup."
            }
        )
    }

    @Synchronized
    fun quarantineCorruptPrimaryAndRestoreBackup(context: Context): StorageHealthReport {
        val file = File(context.filesDir, DB_FILE_NAME)
        val bakFile = File(context.filesDir, "$DB_FILE_NAME.bak")
        val backupState = if (bakFile.exists()) {
            runCatching { parseStateText(bakFile.readText()) }.getOrNull()
        } else null
        if (backupState == null) {
            return inspectStorageHealth(context).copy(
                message = "No valid backup snapshot found to restore."
            )
        }
        var quarantinedPath: String? = null
        if (file.exists()) {
            val quarantine = File(context.filesDir, "$DB_FILE_NAME.corrupt.${System.currentTimeMillis()}.json")
            if (!file.renameTo(quarantine)) {
                quarantine.writeText(file.readText())
                file.delete()
            }
            quarantinedPath = quarantine.absolutePath
        }
        val written = writeState(context, backupState)
        return if (written) {
            StorageHealthReport(
                state = StorageHealthState.RECOVERED_FROM_BACKUP,
                corruptFilePath = file.absolutePath,
                quarantinedFilePath = quarantinedPath,
                hasValidBackup = true,
                backupRecordCount = backupState.properties.size,
                message = "Corrupt file preserved at quarantine; restored ${backupState.properties.size} records from backup."
            )
        } else {
            inspectStorageHealth(context)
        }
    }

    @Synchronized
    fun quarantineCorruptPrimaryAndResetFresh(context: Context): StorageHealthReport {
        val file = File(context.filesDir, DB_FILE_NAME)
        val bakFile = File(context.filesDir, "$DB_FILE_NAME.bak")
        var quarantinedPath: String? = null
        if (file.exists()) {
            val quarantine = File(context.filesDir, "$DB_FILE_NAME.corrupt.${System.currentTimeMillis()}.json")
            if (!file.renameTo(quarantine)) {
                quarantine.writeText(file.readText())
                file.delete()
            }
            quarantinedPath = quarantine.absolutePath
        }
        if (bakFile.exists()) {
            val bakQuarantine = File(context.filesDir, "$DB_FILE_NAME.bak.corrupt.${System.currentTimeMillis()}.json")
            bakFile.renameTo(bakQuarantine)
        }
        writeState(context, InventoryState(emptyList(), emptyList()))
        return StorageHealthReport(
            state = StorageHealthState.HEALTHY,
            quarantinedFilePath = quarantinedPath,
            message = "Corrupt storage quarantined safely; started with clean local database."
        )
    }

    @Synchronized
    fun loadProperties(context: Context): List<PropertyItem> = try {
        val primary = readState(context).properties
        val loaded = if (primary.isNotEmpty()) primary else OfflineDiskCache.loadInventorySplits(context)
        com.example.inventory.PropertyPrecomputedCache.warmUp(loaded)
        loaded
    } catch (error: Exception) {
        Log.e(TAG, "Cannot read inventory; original file retained", error)
        OfflineDiskCache.loadInventorySplits(context).also {
            com.example.inventory.PropertyPrecomputedCache.warmUp(it)
        }
    }

    @Synchronized
    internal fun loadPendingWrites(context: Context): List<PendingSyncWrite> =
        readState(context).pending

    @Synchronized
    fun clearAll(context: Context) {
        android.util.AtomicFile(File(context.filesDir, DB_FILE_NAME)).delete()
        File(context.filesDir, LEGACY_DB_FILE_NAME).delete()
        File(context.filesDir, "pending_sync_ops_v1.json").delete()
        com.example.inventory.PropertyPrecomputedCache.clear()
    }

    @Synchronized
    fun saveProperties(context: Context, properties: List<PropertyItem>): Boolean = try {
        val old = readState(context)
        // A background ACK may be newer than an open screen's cached sync flag.
        // Keep that ACK only when the entire business snapshot still matches.
        val prepared = properties.map { incoming ->
            val persisted = old.properties.firstOrNull { it.id == incoming.id }
            if (!incoming.isSyncedToSheet && persisted?.isSyncedToSheet == true &&
                AppsScriptSyncProtocol.sameWriteSnapshot(incoming, persisted)) {
                incoming.copy(isSyncedToSheet = true)
            } else incoming
        }
        com.example.inventory.PropertyPrecomputedCache.warmUp(prepared)
        val pending = PendingSyncQueue.reconcile(prepared, old.pending, System.currentTimeMillis())
        val saved = writeState(context, InventoryState(prepared, pending))
        if (saved) {
            runCatching { OfflineDiskCache.saveInventorySplits(context, prepared) }
            runCatching { BackgroundSyncScheduler.ensureScheduled(context) }
                .onFailure { Log.e(TAG, "Background scheduling unavailable; queue retained", it) }
        }
        saved
    } catch (error: Exception) {
        Log.e(TAG, "Cannot save inventory/queue; previous data retained", error)
        false
    }

    private fun writeState(context: Context, state: InventoryState): Boolean {
        return try {
            val properties = JSONArray().apply { state.properties.forEach { put(serializeProperty(it)) } }
            val pending = JSONArray().apply {
                state.pending.forEach { entry ->
                    put(JSONObject().apply {
                        put("operationId", entry.operationId)
                        put("property", serializeProperty(entry.property))
                        put("createdAt", entry.createdAt)
                        put("attempts", entry.attempts)
                        put("nextAttemptAt", entry.nextAttemptAt)
                        put("state", entry.state.name)
                        put("lastError", entry.lastError)
                    })
                }
            }
            val envelope = JSONObject().apply {
                put("storageFormat", 3)
                put("properties", properties)
                put("pendingSync", pending)
            }
            val atomic = android.util.AtomicFile(File(context.filesDir, DB_FILE_NAME))
            val stream = atomic.startWrite()
            try {
                if (testSimulateWriteFailure) {
                    throw java.io.IOException("Simulated storage write failure")
                }
                stream.write(envelope.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "Atomic inventory/queue save failed", error)
            false
        }
    }

    @Synchronized
    internal fun recoverPendingWrites(context: Context): Boolean {
        val state = readState(context)
        val pending = PendingSyncQueue.reconcile(state.properties, state.pending, System.currentTimeMillis())
        return if (pending == state.pending) true else writeState(context, state.copy(pending = pending))
    }

    @Synchronized
    internal fun beginPendingWrite(context: Context, operationId: String, now: Long): PendingSyncWrite? {
        val state = readState(context)
        val candidate = PendingSyncQueue.nextDue(state.pending, now) ?: return null
        if (candidate.operationId != operationId) return null
        val started = PendingSyncQueue.begin(candidate, now)
        return if (writeState(context, state.copy(pending = state.pending.map {
                if (it.operationId == operationId) started else it
            }))) started else null
    }

    @Synchronized
    internal fun failPendingWrite(
        context: Context, operationId: String, retryable: Boolean, message: String
    ): Boolean {
        val state = readState(context)
        return writeState(context, state.copy(pending = state.pending.map {
            if (it.operationId == operationId) PendingSyncQueue.failed(it, retryable, message, System.currentTimeMillis()) else it
        }))
    }

    @Synchronized
    internal fun acknowledgePendingWrite(context: Context, operationId: String): Boolean {
        val state = readState(context)
        val entry = state.pending.firstOrNull { it.operationId == operationId } ?: return false
        val properties = AppsScriptSyncProtocol.markAcknowledged(state.properties, entry.property)
        // Remove only the acknowledged operation, never a newer pending edit.
        return writeState(context, state.copy(properties = properties,
            pending = state.pending.filterNot { it.operationId == operationId }))
    }

    @Synchronized
    internal fun acknowledgePropertySynced(context: Context, property: PropertyItem): Boolean {
        val state = readState(context)
        val properties = AppsScriptSyncProtocol.markAcknowledged(state.properties, property)
        val remainingPending = state.pending.filterNot { pending ->
            pending.property.id == property.id ||
                (property.recordId.isNotBlank() && pending.property.recordId.equals(property.recordId, ignoreCase = true)) ||
                (property.propertyCode.isNotBlank() && pending.property.propertyCode.equals(property.propertyCode, ignoreCase = true))
        }
        return writeState(context, state.copy(properties = properties, pending = remainingPending))
    }

    internal fun pendingWritePayload(entry: PendingSyncWrite): JSONObject =
        serializeProperty(entry.property).apply {
            put("action", "UPSERT")
            put("operationId", entry.operationId)
            put("subType", entry.property.tab.title)
            put("source", entry.property.sourceInfo ?: "")
            put("Type of POC", entry.property.pocType)
            put("furnishing", entry.property.furnishing.label)
            put("status", entry.property.status.label)
        }

    private val KNOWN_PROPERTY_KEYS = setOf(
        "storageVersion", "updatedBy", "updatedAt", "lastPriceConfirmationDate",
        "ownerConfirmationStatus", "leaseExpiryDate", "upcomingVacancyDate",
        "sameUnitGroupId", "duplicateDecisions", "id", "propertyCode", "date",
        "location", "projectName", "unitNumber", "floor", "propertyType", "tab",
        "carpetAreaSqFt", "builtUpAreaSqFt", "priceOrRent", "priceSale", "rent",
        "deposit", "roiPercent", "possession", "furnishing", "status",
        "ownerName", "ownerContact", "sourceInfo", "remarks", "listingLink",
        "lastVerificationDate", "internalRemarks", "images", "parking",
        "amenities", "maintenance", "lat", "lng", "floorPlanUrl", "videoUrl",
        "brochureUrl", "attachments", "workstations", "cabins", "conferenceRooms",
        "frontageFeet", "floorLevel", "ceilingHeightFeet", "isMainRoadFacing",
        "bhk", "balconies", "builderName", "monthlyRentalIncome", "tenantName",
        "leaseDurationYears", "lockInPeriodYears", "escalationPercent",
        "escalationIntervalYears", "editHistory", "isSyncedToSheet",
        "lastViewedTimestamp", "brokerName", "brokerContact", "rowNumber",
        "portfolio", "transactionType", "googleMapsUrl", "latitude", "longitude",
        "verificationStatus", "lastVerifiedDateClean", "verifiedBy", "recordId",
        "syncUpdatedAt", "archiveStatus", "isAreaEstimated",
        "projectId", "maintenanceBasis", "maintenanceIncluded", "parkingCount",
        "lockInMonths", "escalationIntervalMonths", "createdAt", "recordVersion",
        "unmappedColumns", "fullAddress", "city", "locationPrecision",
        "physicalUnitKey", "hasDualSaleAndLeaseOffer", "saleOfferStatus",
        "leaseOfferStatus", "saleSourceInfo", "leaseSourceInfo", "Type of POC", "pocType"
    )

    internal fun serializePropertyJson(item: PropertyItem): JSONObject = serializeProperty(item)

    private fun serializeProperty(item: PropertyItem): JSONObject {
        val obj = JSONObject()
        obj.put("storageVersion", 2)
        obj.put("updatedBy", item.updatedBy)
        obj.put("updatedAt", item.updatedAt)
        obj.put("lastPriceConfirmationDate", item.lastPriceConfirmationDate)
        obj.put("ownerConfirmationStatus", item.ownerConfirmationStatus)
        obj.put("leaseExpiryDate", item.leaseExpiryDate)
        obj.put("upcomingVacancyDate", item.upcomingVacancyDate)
        obj.put("sameUnitGroupId", item.sameUnitGroupId)
        obj.put("duplicateDecisions", JSONObject(item.duplicateDecisions))
        obj.put("id", item.id)
        obj.put("propertyCode", item.propertyCode)
        obj.put("date", item.date)
        obj.put("location", item.location)
        obj.put("projectName", item.projectName)
        obj.put("unitNumber", item.unitNumber)
        obj.put("floor", item.floor)
        obj.put("propertyType", item.propertyType)
        obj.put("tab", item.tab.name)
        obj.put("carpetAreaSqFt", item.carpetAreaSqFt)
        item.builtUpAreaSqFt?.let { obj.put("builtUpAreaSqFt", it) } ?: obj.remove("builtUpAreaSqFt")
        obj.put("priceOrRent", item.priceOrRent)
        item.priceSale?.let { obj.put("priceSale", it) } ?: obj.remove("priceSale")
        item.rent?.let { obj.put("rent", it) } ?: obj.remove("rent")
        item.deposit?.let { obj.put("deposit", it) } ?: obj.remove("deposit")
        item.roiPercent?.let { obj.put("roiPercent", it) } ?: obj.remove("roiPercent")
        obj.put("possession", item.possession)
        obj.put("furnishing", item.furnishing.name)
        obj.put("status", item.status.name)
        item.ownerName?.let { obj.put("ownerName", it) } ?: obj.remove("ownerName")
        item.ownerContact?.let { obj.put("ownerContact", it) } ?: obj.remove("ownerContact")
        item.sourceInfo?.let { obj.put("sourceInfo", it) } ?: obj.remove("sourceInfo")
        item.remarks?.let { obj.put("remarks", it) } ?: obj.remove("remarks")
        item.listingLink?.let { obj.put("listingLink", it) } ?: obj.remove("listingLink")
        obj.put("lastVerificationDate", item.lastVerificationDate)
        item.internalRemarks?.let { obj.put("internalRemarks", it) } ?: obj.remove("internalRemarks")

        // Images array
        val imagesArray = JSONArray()
        item.images.forEach { imagesArray.put(it) }
        obj.put("images", imagesArray)
        obj.put("parking", item.parking)
        obj.put("amenities", JSONArray(item.amenities))
        item.maintenance?.let { obj.put("maintenance", it) } ?: obj.remove("maintenance")
        item.lat?.let { obj.put("lat", it) } ?: obj.remove("lat")
        item.lng?.let { obj.put("lng", it) } ?: obj.remove("lng")

        item.floorPlanUrl?.let { obj.put("floorPlanUrl", it) } ?: obj.remove("floorPlanUrl")
        item.videoUrl?.let { obj.put("videoUrl", it) } ?: obj.remove("videoUrl")
        item.brochureUrl?.let { obj.put("brochureUrl", it) } ?: obj.remove("brochureUrl")

        // Attachments array
        val attachArray = JSONArray()
        item.attachments.forEach { att ->
            val attObj = JSONObject()
            attObj.put("id", att.id)
            attObj.put("name", att.name)
            attObj.put("type", att.type.name)
            attObj.put("localUriOrPath", att.localUriOrPath)
            attObj.put("fileSizeBytes", att.fileSizeBytes)
            attObj.put("uploadDate", att.uploadDate)
            attObj.put("mimeType", att.mimeType)
            attachArray.put(attObj)
        }
        obj.put("attachments", attachArray)

        item.workstations?.let { obj.put("workstations", it) } ?: obj.remove("workstations")
        item.cabins?.let { obj.put("cabins", it) } ?: obj.remove("cabins")
        item.conferenceRooms?.let { obj.put("conferenceRooms", it) } ?: obj.remove("conferenceRooms")
        item.frontageFeet?.let { obj.put("frontageFeet", it) } ?: obj.remove("frontageFeet")
        item.floorLevel?.let { obj.put("floorLevel", it) } ?: obj.remove("floorLevel")
        item.ceilingHeightFeet?.let { obj.put("ceilingHeightFeet", it) } ?: obj.remove("ceilingHeightFeet")
        obj.put("isMainRoadFacing", item.isMainRoadFacing)
        item.bhk?.let { obj.put("bhk", it) } ?: obj.remove("bhk")
        item.balconies?.let { obj.put("balconies", it) } ?: obj.remove("balconies")
        item.builderName?.let { obj.put("builderName", it) } ?: obj.remove("builderName")
        item.monthlyRentalIncome?.let { obj.put("monthlyRentalIncome", it) } ?: obj.remove("monthlyRentalIncome")
        item.tenantName?.let { obj.put("tenantName", it) } ?: obj.remove("tenantName")
        item.leaseDurationYears?.let { obj.put("leaseDurationYears", it) } ?: obj.remove("leaseDurationYears")
        item.lockInPeriodYears?.let { obj.put("lockInPeriodYears", it) } ?: obj.remove("lockInPeriodYears")
        item.escalationPercent?.let { obj.put("escalationPercent", it) } ?: obj.remove("escalationPercent")
        item.escalationIntervalYears?.let { obj.put("escalationIntervalYears", it) } ?: obj.remove("escalationIntervalYears")
        obj.put("editHistory", JSONArray().apply {
            item.editHistory.forEach { history ->
                put(JSONObject().apply {
                    put("historyId", history.historyId)
                    put("timestamp", history.timestamp)
                    put("summary", history.summary)
                    put("snapshot", serializeSnapshot(history.snapshot))
                })
            }
        })
        obj.put("isSyncedToSheet", item.isSyncedToSheet)
        obj.put("lastViewedTimestamp", item.lastViewedTimestamp)
        item.brokerName?.let { obj.put("brokerName", it) } ?: obj.remove("brokerName")
        item.brokerContact?.let { obj.put("brokerContact", it) } ?: obj.remove("brokerContact")
        obj.put("rowNumber", item.rowNumber)

        // 11 Standard Master Management Columns
        obj.put("portfolio", item.portfolio)
        obj.put("transactionType", item.transactionType)
        item.googleMapsUrl?.let { obj.put("googleMapsUrl", it) } ?: obj.remove("googleMapsUrl")
        item.latitude?.let { obj.put("latitude", it) } ?: obj.remove("latitude")
        item.longitude?.let { obj.put("longitude", it) } ?: obj.remove("longitude")
        obj.put("verificationStatus", item.verificationStatus.name)
        obj.put("lastVerifiedDateClean", item.lastVerifiedDateClean)
        item.verifiedBy?.let { obj.put("verifiedBy", it) } ?: obj.remove("verifiedBy")
        obj.put("recordId", item.recordId)
        obj.put("syncUpdatedAt", item.syncUpdatedAt)
        obj.put("archiveStatus", item.archiveStatus.name)
        obj.put("isAreaEstimated", item.isAreaEstimated)

        // Prompt 05 additive versioned edit fields
        obj.put("projectId", item.projectId)
        obj.put("maintenanceBasis", item.maintenanceBasis.ifBlank { "UNKNOWN" })
        item.maintenanceIncluded?.let { obj.put("maintenanceIncluded", it) } ?: obj.remove("maintenanceIncluded")
        item.parkingCount?.let { obj.put("parkingCount", it) } ?: obj.remove("parkingCount")
        item.lockInMonths?.let { obj.put("lockInMonths", it) } ?: obj.remove("lockInMonths")
        item.escalationIntervalMonths?.let { obj.put("escalationIntervalMonths", it) } ?: obj.remove("escalationIntervalMonths")
        obj.put("createdAt", item.createdAt)
        obj.put("recordVersion", item.recordVersion)
        obj.put("fullAddress", item.fullAddress)
        obj.put("city", item.city)
        obj.put("locationPrecision", item.locationPrecision.name)
        obj.put("physicalUnitKey", item.physicalUnitKey)
        obj.put("hasDualSaleAndLeaseOffer", item.hasDualSaleAndLeaseOffer)
        item.saleOfferStatus?.let { obj.put("saleOfferStatus", it.name) } ?: obj.remove("saleOfferStatus")
        item.leaseOfferStatus?.let { obj.put("leaseOfferStatus", it.name) } ?: obj.remove("leaseOfferStatus")
        item.saleSourceInfo?.let { obj.put("saleSourceInfo", it) } ?: obj.remove("saleSourceInfo")
        item.leaseSourceInfo?.let { obj.put("leaseSourceInfo", it) } ?: obj.remove("leaseSourceInfo")
        obj.put("Type of POC", item.pocType)
        if (item.unmappedColumns.isNotEmpty()) {
            obj.put("unmappedColumns", JSONObject(item.unmappedColumns))
            item.unmappedColumns.forEach { (key, value) ->
                if (key !in KNOWN_PROPERTY_KEYS) {
                    obj.put(key, value)
                }
            }
        }

        return obj
    }

    internal fun deserializePropertyJson(obj: JSONObject): PropertyItem = deserializeProperty(obj)

    private fun deserializeProperty(obj: JSONObject): PropertyItem {
        val tabName = obj.optString("tab", InventoryTab.OFF_LEASE.name)
        val tab = try { InventoryTab.valueOf(tabName) } catch (_: Exception) { InventoryTab.OFF_LEASE }

        val furnishingName = obj.optString("furnishing", FurnishingType.UNFURNISHED.name)
        val furnishing = try { FurnishingType.valueOf(furnishingName) } catch (_: Exception) { FurnishingType.UNFURNISHED }

        val statusName = obj.optString("status", AvailabilityStatus.NOT_CONFIRMED.name)
        val status = try { AvailabilityStatus.valueOf(statusName) } catch (_: Exception) { AvailabilityStatus.NOT_CONFIRMED }

        val images = mutableListOf<String>()
        val imgArray = obj.optJSONArray("images")
        if (imgArray != null) {
            for (i in 0 until imgArray.length()) {
                images.add(imgArray.getString(i))
            }
        }

        val attachments = mutableListOf<PropertyAttachment>()
        val attArray = obj.optJSONArray("attachments")
        if (attArray != null) {
            for (i in 0 until attArray.length()) {
                val attObj = attArray.getJSONObject(i)
                val typeName = attObj.optString("type", AttachmentType.DOCUMENT.name)
                val type = try { AttachmentType.valueOf(typeName) } catch (_: Exception) { AttachmentType.DOCUMENT }
                attachments.add(
                    PropertyAttachment(
                        id = attObj.optString("id"),
                        name = attObj.optString("name", "Document"),
                        type = type,
                        localUriOrPath = attObj.optString("localUriOrPath"),
                        fileSizeBytes = attObj.optLong("fileSizeBytes", 0L),
                        uploadDate = attObj.optString("uploadDate", "13 Oct 2026"),
                        mimeType = attObj.optString("mimeType", "application/pdf")
                    )
                )
            }
        }

        val propCode = obj.getString("propertyCode")
        val fallbackRecordId = "AST-${propCode.filter { it.isDigit() }.padStart(6, '0').takeLast(6).ifBlank { "000001" }}"

        val verifStatus = try {
            VerificationStatus.valueOf(obj.optString("verificationStatus", VerificationStatus.PENDING.name))
        } catch (_: Exception) {
            VerificationStatus.PENDING
        }

        val archiveSt = try {
            ArchiveStatus.valueOf(obj.optString("archiveStatus", ArchiveStatus.ACTIVE.name))
        } catch (_: Exception) {
            ArchiveStatus.ACTIVE
        }

        val preservedUnmapped = linkedMapOf<String, String>()
        obj.optJSONObject("unmappedColumns")?.let { unmappedObj ->
            val keys = unmappedObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!unmappedObj.isNull(key)) {
                    preservedUnmapped[key] = unmappedObj.opt(key)?.toString() ?: ""
                }
            }
        }
        val allKeys = obj.keys()
        while (allKeys.hasNext()) {
            val key = allKeys.next()
            if (key !in KNOWN_PROPERTY_KEYS && !obj.isNull(key)) {
                preservedUnmapped[key] = obj.opt(key)?.toString() ?: ""
            }
        }

        return PropertyItem(
            id = obj.getString("id"),
            propertyCode = propCode,
            date = obj.optString("date", ""),
            location = obj.optString("location", ""),
            projectName = obj.optString("projectName", ""),
            unitNumber = obj.optString("unitNumber", ""),
            floor = obj.optString("floor", ""),
            propertyType = obj.optString("propertyType", ""),
            tab = tab,
            carpetAreaSqFt = obj.optDouble("carpetAreaSqFt", 0.0),
            builtUpAreaSqFt = optionalDouble(obj, "builtUpAreaSqFt"),
            priceOrRent = obj.optDouble("priceOrRent", 0.0),
            priceSale = optionalDouble(obj, "priceSale"),
            rent = optionalDouble(obj, "rent"),
            deposit = optionalDouble(obj, "deposit"),
            roiPercent = optionalDouble(obj, "roiPercent"),
            possession = obj.optString("possession", ""),
            furnishing = furnishing,
            status = status,
            updatedBy = obj.optString("updatedBy", ""),
            updatedAt = obj.optString("updatedAt", ""),
            lastPriceConfirmationDate = obj.optString("lastPriceConfirmationDate", ""),
            ownerConfirmationStatus = obj.optString("ownerConfirmationStatus", "Not Mentioned"),
            leaseExpiryDate = obj.optString("leaseExpiryDate", ""),
            upcomingVacancyDate = obj.optString("upcomingVacancyDate", ""),
            sameUnitGroupId = obj.optString("sameUnitGroupId", ""),
            duplicateDecisions = obj.optJSONObject("duplicateDecisions")?.let { d -> d.keys().asSequence().associateWith { d.optString(it) } } ?: emptyMap(),
            ownerName = optionalString(obj, "ownerName"),
            ownerContact = optionalString(obj, "ownerContact"),
            sourceInfo = optionalString(obj, "sourceInfo"),
            remarks = optionalString(obj, "remarks"),
            listingLink = optionalString(obj, "listingLink"),
            lastVerificationDate = obj.optString("lastVerificationDate", ""),
            internalRemarks = optionalString(obj, "internalRemarks"),
            images = images,
            parking = obj.optString("parking", ""),
            amenities = obj.optJSONArray("amenities")?.let { values ->
                (0 until values.length()).map { values.getString(it) }
            } ?: emptyList(),
            maintenance = optionalDouble(obj, "maintenance"),
            lat = optionalDouble(obj, "lat"),
            lng = optionalDouble(obj, "lng"),
            floorPlanUrl = optionalString(obj, "floorPlanUrl"),
            videoUrl = optionalString(obj, "videoUrl"),
            brochureUrl = optionalString(obj, "brochureUrl"),
            attachments = attachments,
            workstations = optionalInt(obj, "workstations"),
            cabins = optionalInt(obj, "cabins"),
            conferenceRooms = optionalInt(obj, "conferenceRooms"),
            frontageFeet = optionalDouble(obj, "frontageFeet"),
            floorLevel = optionalString(obj, "floorLevel"),
            ceilingHeightFeet = optionalDouble(obj, "ceilingHeightFeet"),
            isMainRoadFacing = obj.optBoolean("isMainRoadFacing", false),
            bhk = optionalString(obj, "bhk"),
            balconies = optionalInt(obj, "balconies"),
            builderName = optionalString(obj, "builderName"),
            monthlyRentalIncome = optionalDouble(obj, "monthlyRentalIncome"),
            tenantName = optionalString(obj, "tenantName"),
            leaseDurationYears = optionalInt(obj, "leaseDurationYears"),
            lockInPeriodYears = optionalInt(obj, "lockInPeriodYears"),
            escalationPercent = optionalDouble(obj, "escalationPercent"),
            escalationIntervalYears = optionalInt(obj, "escalationIntervalYears"),
            editHistory = obj.optJSONArray("editHistory")?.let { values ->
                (0 until values.length()).map { index ->
                    val entry = values.getJSONObject(index)
                    PropertyEditHistory(
                        historyId = entry.optString("historyId", ""),
                        timestamp = entry.optString("timestamp", ""),
                        summary = entry.optString("summary", ""),
                        snapshot = deserializeSnapshot(entry.getJSONObject("snapshot"))
                    )
                }
            } ?: emptyList(),
            // Old files contain no proof of an upstream acknowledgement.
            isSyncedToSheet = obj.optBoolean("isSyncedToSheet", false),
            lastViewedTimestamp = obj.optLong("lastViewedTimestamp", 0L),
            brokerName = optionalString(obj, "brokerName"),
            brokerContact = optionalString(obj, "brokerContact"),
            rowNumber = obj.optInt("rowNumber", 0),
            // 11 Standard Master Management Columns
            portfolio = obj.optString("portfolio", if (tab.brand == Brand.RESIDENTIAL) "Residential" else "Commercial"),
            transactionType = obj.optString("transactionType", if (tab.isRental) "Lease" else "Sale"),
            googleMapsUrl = optionalString(obj, "googleMapsUrl"),
            latitude = optionalDouble(obj, "latitude")
                ?: if (obj.optInt("storageVersion", 1) < 2) optionalDouble(obj, "lat") else null,
            longitude = optionalDouble(obj, "longitude")
                ?: if (obj.optInt("storageVersion", 1) < 2) optionalDouble(obj, "lng") else null,
            verificationStatus = verifStatus,
            lastVerifiedDateClean = obj.optString("lastVerifiedDateClean", ""),
            verifiedBy = optionalString(obj, "verifiedBy"),
            recordId = obj.optString("recordId", fallbackRecordId),
            syncUpdatedAt = obj.optString("syncUpdatedAt", ""),
            archiveStatus = archiveSt,
            isAreaEstimated = obj.optBoolean("isAreaEstimated", false),
            projectId = obj.optString("projectId", ""),
            maintenanceBasis = obj.optString("maintenanceBasis", "UNKNOWN").ifBlank { "UNKNOWN" },
            maintenanceIncluded = optionalBoolean(obj, "maintenanceIncluded"),
            parkingCount = optionalInt(obj, "parkingCount"),
            lockInMonths = optionalInt(obj, "lockInMonths"),
            escalationIntervalMonths = optionalInt(obj, "escalationIntervalMonths"),
            createdAt = obj.optString("createdAt", ""),
            recordVersion = obj.optInt("recordVersion", 0),
            unmappedColumns = preservedUnmapped,
            fullAddress = obj.optString("fullAddress", ""),
            city = obj.optString("city", ""),
            locationPrecision = runCatching {
                LocationPrecision.valueOf(obj.optString("locationPrecision", LocationPrecision.UNKNOWN.name))
            }.getOrDefault(LocationPrecision.UNKNOWN),
            physicalUnitKey = obj.optString("physicalUnitKey", ""),
            hasDualSaleAndLeaseOffer = obj.optBoolean("hasDualSaleAndLeaseOffer", false),
            saleOfferStatus = obj.optString("saleOfferStatus").takeIf { it.isNotBlank() }?.let { runCatching { AvailabilityStatus.valueOf(it) }.getOrNull() },
            leaseOfferStatus = obj.optString("leaseOfferStatus").takeIf { it.isNotBlank() }?.let { runCatching { AvailabilityStatus.valueOf(it) }.getOrNull() },
            saleSourceInfo = optionalString(obj, "saleSourceInfo"),
            leaseSourceInfo = optionalString(obj, "leaseSourceInfo"),
            pocType = normalizePocType(obj.optString("Type of POC").ifBlank { obj.optString("pocType") })
        )
    }

    private fun optionalDouble(obj: JSONObject, key: String): Double? =
        if (obj.isNull(key)) null else obj.optDouble(key).takeIf { it.isFinite() }

    private fun optionalInt(obj: JSONObject, key: String): Int? =
        if (obj.isNull(key)) null else obj.optInt(key)

    private fun optionalString(obj: JSONObject, key: String): String? =
        if (obj.isNull(key)) null else obj.optString(key)

    private fun optionalBoolean(obj: JSONObject, key: String): Boolean? =
        if (obj.isNull(key)) null else obj.optBoolean(key)

    private fun serializeSnapshot(snapshot: PropertyItemSnapshot): JSONObject =
        JSONObject().apply {
            put("carpetAreaSqFt", snapshot.carpetAreaSqFt)
            put("builtUpAreaSqFt", snapshot.builtUpAreaSqFt ?: JSONObject.NULL)
            put("priceOrRent", snapshot.priceOrRent)
            put("priceSale", snapshot.priceSale ?: JSONObject.NULL)
            put("rent", snapshot.rent ?: JSONObject.NULL)
            put("deposit", snapshot.deposit ?: JSONObject.NULL)
            put("roiPercent", snapshot.roiPercent ?: JSONObject.NULL)
            put("furnishing", snapshot.furnishing.name)
            put("possession", snapshot.possession)
            put("status", snapshot.status.name)
            put("ownerName", snapshot.ownerName ?: JSONObject.NULL)
            put("ownerContact", snapshot.ownerContact ?: JSONObject.NULL)
            put("remarks", snapshot.remarks ?: JSONObject.NULL)
        }

    private fun deserializeSnapshot(obj: JSONObject): PropertyItemSnapshot =
        PropertyItemSnapshot(
            carpetAreaSqFt = obj.optDouble("carpetAreaSqFt", 0.0),
            builtUpAreaSqFt = optionalDouble(obj, "builtUpAreaSqFt"),
            priceOrRent = obj.optDouble("priceOrRent", 0.0),
            priceSale = optionalDouble(obj, "priceSale"),
            rent = optionalDouble(obj, "rent"),
            deposit = optionalDouble(obj, "deposit"),
            roiPercent = optionalDouble(obj, "roiPercent"),
            furnishing = runCatching {
                FurnishingType.valueOf(obj.optString("furnishing"))
            }.getOrDefault(FurnishingType.UNFURNISHED),
            possession = obj.optString("possession", ""),
            status = runCatching {
                AvailabilityStatus.valueOf(obj.optString("status"))
            }.getOrDefault(AvailabilityStatus.NOT_CONFIRMED),
            ownerName = optionalString(obj, "ownerName"),
            ownerContact = optionalString(obj, "ownerContact"),
            remarks = optionalString(obj, "remarks")
        )

    fun getDefaultSampleProperties(): List<PropertyItem> {
        return listOf(
            PropertyItem(
                id = "sample_prop_1",
                propertyCode = "OL-KHD-101",
                date = "13/10/2026",
                location = "Kharadi, Pune",
                projectName = "World Trade Center (WTC)",
                unitNumber = "Tower 4, 8th Floor",
                floor = "High Floor",
                propertyType = "Office",
                tab = InventoryTab.OFF_LEASE,
                carpetAreaSqFt = 2400.0,
                priceOrRent = 168000.0,
                deposit = 1008000.0,
                possession = "Immediate",
                furnishing = FurnishingType.FULLY_FURNISHED,
                status = AvailabilityStatus.AVAILABLE,
                ownerName = "Rajesh Singhania",
                ownerContact = "9822011223",
                remarks = "Fully furnished plug & play corporate office with 24 workstations, 3 cabins, and conference room.",
                verificationStatus = VerificationStatus.VERIFIED,
                archiveStatus = ArchiveStatus.ACTIVE,
                portfolio = "Commercial",
                transactionType = "Lease"
            ),
            PropertyItem(
                id = "sample_prop_2",
                propertyCode = "SL-VMN-202",
                date = "13/10/2026",
                location = "Viman Nagar, Pune",
                projectName = "Phoenix Marketcity Retail",
                unitNumber = "Ground Floor, Shop 14",
                floor = "Ground Floor",
                propertyType = "Shop",
                tab = InventoryTab.SHOP_LEASE,
                carpetAreaSqFt = 950.0,
                priceOrRent = 190000.0,
                deposit = 1140000.0,
                possession = "Immediate",
                furnishing = FurnishingType.BARE_SHELL,
                status = AvailabilityStatus.AVAILABLE,
                ownerName = "Suresh Mehta",
                ownerContact = "9822188220",
                remarks = "Prime high-street frontage retail shop with excellent footfall.",
                verificationStatus = VerificationStatus.VERIFIED,
                archiveStatus = ArchiveStatus.ACTIVE,
                portfolio = "Commercial",
                transactionType = "Lease"
            ),
            PropertyItem(
                id = "sample_prop_3",
                propertyCode = "PL-KHD-303",
                date = "13/10/2026",
                location = "Kharadi, Pune",
                projectName = "Gera Imperium Alpha",
                unitNumber = "Office 305",
                floor = "Mid Floor",
                propertyType = "Office",
                tab = InventoryTab.PRE_LEASED,
                carpetAreaSqFt = 1450.0,
                priceOrRent = 22500000.0,
                roiPercent = 7.8,
                possession = "Immediate",
                furnishing = FurnishingType.SEMI_FURNISHED,
                status = AvailabilityStatus.AVAILABLE,
                ownerName = "Anil Deshmukh",
                ownerContact = "9850123456",
                remarks = "Pre-leased to multinational tech tenant with 3-year lock-in. 7.8% net ROI.",
                verificationStatus = VerificationStatus.VERIFIED,
                archiveStatus = ArchiveStatus.ACTIVE,
                portfolio = "Commercial",
                transactionType = "Pre-Leased Sale"
            ),
            PropertyItem(
                id = "sample_prop_4",
                propertyCode = "RR-WKD-404",
                date = "13/10/2026",
                location = "Wakad, Pune",
                projectName = "Kasturi Apostrophe",
                unitNumber = "Building B, 1202",
                floor = "High Floor",
                propertyType = "Apartment",
                tab = InventoryTab.RESI_RENT,
                carpetAreaSqFt = 1320.0,
                priceOrRent = 45000.0,
                deposit = 200000.0,
                bhk = "3 BHK",
                possession = "Immediate",
                furnishing = FurnishingType.SEMI_FURNISHED,
                status = AvailabilityStatus.AVAILABLE,
                ownerName = "Dr. Prakash Kulkarni",
                ownerContact = "9860012345",
                remarks = "Sprawling 3 BHK luxury apartment with club view and 2 covered parking spaces.",
                verificationStatus = VerificationStatus.VERIFIED,
                archiveStatus = ArchiveStatus.ACTIVE,
                portfolio = "Residential",
                transactionType = "Lease"
            ),
            PropertyItem(
                id = "sample_prop_5",
                propertyCode = "RS-Kalyani-505",
                date = "13/10/2026",
                location = "Kalyani Nagar, Pune",
                projectName = "Kumar Primavera",
                unitNumber = "A Wing, 401",
                floor = "Mid Floor",
                propertyType = "Apartment",
                tab = InventoryTab.RESI_RESALE,
                carpetAreaSqFt = 1650.0,
                priceOrRent = 18500000.0,
                bhk = "3 BHK",
                possession = "Immediate",
                furnishing = FurnishingType.FULLY_FURNISHED,
                status = AvailabilityStatus.AVAILABLE,
                ownerName = "Vikramaditya Rao",
                ownerContact = "9881122334",
                remarks = "Exquisitely renovated 3 BHK resale in prime Kalyani Nagar residential enclave.",
                verificationStatus = VerificationStatus.VERIFIED,
                archiveStatus = ArchiveStatus.ACTIVE,
                portfolio = "Residential",
                transactionType = "Sale"
            )
        )
    }
}

