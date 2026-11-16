package com.example.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.model.AvailabilityStatus
import com.example.model.PropertyItem
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingSyncQueueTest {
    private lateinit var root: File
    private lateinit var context: Context
    private fun property(id: String = "one") = PropertyItem(
        id = id, propertyCode = "OL-TEST-$id", recordId = "COM-TEST-$id",
        location = "Kharadi", priceOrRent = 80000.0, isSyncedToSheet = false
    )

    @Before fun setUp() {
        root = java.nio.file.Files.createTempDirectory("tlc-pending-sync").toFile()
        val app = ApplicationProvider.getApplicationContext<Context>()
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                app.getSharedPreferences(root.name + "_" + name, mode)
        }
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun queue() = PropertiesLocalDatabase.loadPendingWrites(context)
    private fun save(vararg properties: PropertyItem) {
        assertTrue(PropertiesLocalDatabase.saveProperties(context, properties.toList()))
    }
    private fun blockStagingFile() {
        val staging = File(root, "real_estate_inventory_db.json.new")
        assertTrue(staging.mkdir())
        File(staging, "blocker").writeText("test")
    }

    @Test fun propertyAndQueueAreSavedTogetherAndSurviveReload() {
        val original = property()
        save(original)
        val queued = queue().single()
        assertEquals(original, PropertiesLocalDatabase.loadProperties(context).single())
        assertEquals(original, queued.property)
        assertEquals(queued, PropertiesLocalDatabase.loadPendingWrites(context).single())
        val file = JSONObject(File(root, "real_estate_inventory_db.json").readText())
        assertEquals(3, file.getInt("storageFormat"))
        assertEquals(1, file.getJSONArray("properties").length())
        assertEquals(1, file.getJSONArray("pendingSync").length())
    }

    @Test fun failedAtomicSaveDoesNotLoseExistingInventoryOrInventQueueEntry() {
        val old = property("old").copy(isSyncedToSheet = true)
        save(old)
        blockStagingFile()
        assertFalse(PropertiesLocalDatabase.saveProperties(context, listOf(old, property("new"))))
        assertEquals(listOf(old), PropertiesLocalDatabase.loadProperties(context))
        assertTrue(queue().isEmpty())
    }

    @Test fun unattemptedEditsCoalesceWithNewIdForNewContent() {
        val old = property()
        save(old)
        val oldId = queue().single().operationId
        val updated = old.copy(priceOrRent = 90000.0)
        save(updated)
        val entry = queue().single()
        assertNotEquals(oldId, entry.operationId)
        assertEquals(updated, entry.property)
        assertEquals(0, entry.attempts)
    }

    @Test fun attemptedSnapshotIsImmutableAndOldAckDoesNotClearNewEdit() {
        val old = property()
        save(old)
        val first = queue().single()
        val started = PropertiesLocalDatabase.beginPendingWrite(
            context, first.operationId, System.currentTimeMillis())!!
        val updated = old.copy(priceOrRent = 90000.0)
        save(updated)
        assertEquals(2, queue().size)
        assertEquals(started.property, queue().first().property)
        val latestId = queue().last().operationId
        assertTrue(PropertiesLocalDatabase.acknowledgePendingWrite(context, first.operationId))
        assertEquals(updated, PropertiesLocalDatabase.loadProperties(context).single())
        assertEquals(latestId, queue().single().operationId)
        assertFalse(PropertiesLocalDatabase.loadProperties(context).single().isSyncedToSheet)
    }

    @Test fun transientFailurePreservesOperationIdAndBackoffAcrossReload() {
        save(property())
        val first = queue().single()
        val started = PropertiesLocalDatabase.beginPendingWrite(
            context, first.operationId, System.currentTimeMillis())!!
        assertEquals(1, started.attempts)
        assertTrue(PropertiesLocalDatabase.failPendingWrite(
            context, first.operationId, true, "Network unavailable"))
        val retry = queue().single()
        assertEquals(first.operationId, retry.operationId)
        assertEquals(1, retry.attempts)
        assertEquals(PendingWriteState.READY, retry.state)
        assertNull(PendingSyncQueue.nextDue(queue(), retry.nextAttemptAt - 1))
        val next = PropertiesLocalDatabase.beginPendingWrite(
            context, retry.operationId, retry.nextAttemptAt)!!
        assertEquals(first.operationId, next.operationId)
        assertEquals(2, next.attempts)
        assertEquals(first.property, next.property)
    }

    @Test fun processDeathRetainsInFlightLeaseAndStableRetryId() {
        save(property())
        val first = queue().single()
        val now = System.currentTimeMillis()
        val started = PropertiesLocalDatabase.beginPendingWrite(context, first.operationId, now)!!
        assertEquals(PendingWriteState.IN_FLIGHT, queue().single().state)
        assertNull(PendingSyncQueue.nextDue(queue(), started.nextAttemptAt - 1))
        val retry = PropertiesLocalDatabase.beginPendingWrite(
            context, first.operationId, started.nextAttemptAt)!!
        assertEquals(first.operationId, retry.operationId)
        assertEquals(2, retry.attempts)
    }

    @Test fun permanentFailureBlocksLaterWritesForThatPropertyOnly() {
        val original = property()
        save(original)
        val first = queue().single()
        PropertiesLocalDatabase.beginPendingWrite(context, first.operationId, System.currentTimeMillis())
        assertTrue(PropertiesLocalDatabase.failPendingWrite(
            context, first.operationId, false, "Conflict requires review"))
        save(original.copy(priceOrRent = 90000.0), property("other"))
        assertEquals(PendingWriteState.REVIEW, queue().first().state)
        val due = PendingSyncQueue.nextDue(queue(), Long.MAX_VALUE)!!
        assertEquals("other", due.property.id)
    }

    @Test fun failedAckSaveKeepsBothOperationAndPropertyPending() {
        save(property())
        val first = queue().single()
        PropertiesLocalDatabase.beginPendingWrite(context, first.operationId, System.currentTimeMillis())
        blockStagingFile()
        assertFalse(PropertiesLocalDatabase.acknowledgePendingWrite(context, first.operationId))
        assertEquals(first.operationId, queue().single().operationId)
        assertFalse(PropertiesLocalDatabase.loadProperties(context).single().isSyncedToSheet)
    }

    @Test fun successfulAckMarksOnlyMatchingPropertyAndRemovesOnlyItsOperation() {
        save(property(), property("other"))
        val entry = queue().first()
        PropertiesLocalDatabase.beginPendingWrite(context, entry.operationId, System.currentTimeMillis())
        assertTrue(PropertiesLocalDatabase.acknowledgePendingWrite(context, entry.operationId))
        val loaded = PropertiesLocalDatabase.loadProperties(context)
        assertTrue(loaded.first { it.id == entry.property.id }.isSyncedToSheet)
        assertFalse(loaded.first { it.id == "other" }.isSyncedToSheet)
        assertEquals("other", queue().single().property.id)
    }

    @Test fun corruptedStorageCannotBeOverwrittenByAQueueSave() {
        val file = File(root, "real_estate_inventory_db.json")
        file.writeText("{broken")
        assertFalse(PropertiesLocalDatabase.saveProperties(context, listOf(property())))
        assertEquals("{broken", file.readText())
        assertTrue(runCatching { queue() }.isFailure)
    }

    @Test fun legacyUnknownSyncStatusRequiresReviewWithoutChangingSource() {
        val file = File(root, "real_estate_inventory_db.json")
        val raw = """[{"id":"old","propertyCode":"OL-OLD"}]"""
        file.writeText(raw)
        assertEquals(PendingWriteState.REVIEW, queue().single().state)
        assertNull(PendingSyncQueue.nextDue(queue(), Long.MAX_VALUE))
        assertEquals(raw, file.readText())
    }

    @Test fun legacyKnownPendingRecordCanBeRecoveredIntoDurableQueue() {
        File(root, "real_estate_inventory_db.json").writeText(
            """[{"id":"old","propertyCode":"OL-OLD","isSyncedToSheet":false}]"""
        )
        assertTrue(PropertiesLocalDatabase.recoverPendingWrites(context))
        val entry = queue().single()
        assertEquals(PendingWriteState.READY, entry.state)
        assertTrue(PropertiesLocalDatabase.recoverPendingWrites(context))
        assertEquals(entry.operationId, queue().single().operationId)
    }

    @Test fun replayRequiresExplicitServerCapabilitiesAndMatchingOperationId() {
        assertFalse(AppsScriptSyncProtocol.supportsDurableQueue("""{"status":"success"}"""))
        assertFalse(AppsScriptSyncProtocol.supportsDurableQueue(
            """{"status":"success","capabilities":{"queueProtocolVersion":1,"idempotentWrites":true}}"""))
        assertTrue(AppsScriptSyncProtocol.supportsDurableQueue(
            """{"status":"success","capabilities":{"queueProtocolVersion":1,
                "idempotentWrites":true,"operationAcknowledgements":true}}"""))
        save(property())
        val request = PropertiesLocalDatabase.pendingWritePayload(queue().single())
        val response = JSONObject().apply {
            put("status", "success"); put("action", "UPSERTED")
            put("propertyCode", request.getString("propertyCode"))
            put("recordId", request.getString("recordId"))
            put("operationId", request.getString("operationId"))
        }
        assertTrue(AppsScriptSyncProtocol.validateResponse(200, response.toString(), request).accepted)
        response.put("operationId", "unrelated-operation")
        assertFalse(AppsScriptSyncProtocol.validateResponse(200, response.toString(), request).accepted)
    }

    @Test fun availabilityAndPhotoEditsBecomeDurablePendingChanges() {
        val original = property().copy(isSyncedToSheet = true)
        save(original)
        val repository = SheetsRepository(context)
        repository.updateAvailability(original.id, AvailabilityStatus.NOT_AVAILABLE)
        repository.addPropertyPhoto(original.id, "content://test/new-photo")
        val entry = queue().single()
        assertEquals(AvailabilityStatus.NOT_AVAILABLE, entry.property.status)
        assertEquals(listOf("content://test/new-photo"), entry.property.images)
        assertFalse(entry.property.isSyncedToSheet)
        assertEquals(1, repository.pendingSyncCount.value)
    }

    @Test fun deletingLocalPropertyDoesNotAutomaticallyDeleteRemoteRecords() {
        save(property())
        save()
        assertTrue(queue().isEmpty())
        save(property())
        val entry = queue().single()
        PropertiesLocalDatabase.beginPendingWrite(context, entry.operationId, System.currentTimeMillis())
        save()
        assertEquals(PendingWriteState.REVIEW, queue().single().state)
        assertEquals(entry.operationId, queue().single().operationId)
    }

    @Test fun atomicBackupRestoresQueueMetadataWithInventory() {
        save(property())
        val entry = queue().single()
        PropertiesLocalDatabase.beginPendingWrite(context, entry.operationId, System.currentTimeMillis())
        val before = queue().single()
        val file = File(root, "real_estate_inventory_db.json")
        assertTrue(file.renameTo(File(root, "real_estate_inventory_db.json.bak")))
        assertEquals(before, queue().single())
        assertEquals(listOf(property()), PropertiesLocalDatabase.loadProperties(context))
    }

    @Test fun backoffIsBoundedAndPermanentErrorsAreNotRetryable() {
        assertEquals(30000L, PendingSyncQueue.retryDelay(1))
        assertEquals(60000L, PendingSyncQueue.retryDelay(2))
        assertEquals(6 * 60 * 60 * 1000L, PendingSyncQueue.retryDelay(100))
        val request = JSONObject("""{"action":"UPSERT","operationId":"one","propertyCode":"OL-1"}""")
        assertTrue(AppsScriptSyncProtocol.validateResponse(503, "", request).retryable)
        assertFalse(AppsScriptSyncProtocol.validateResponse(401, "", request).retryable)
        assertFalse(AppsScriptSyncProtocol.validateResponse(200, """{"status":"conflict"}""", request).retryable)
    }
}

