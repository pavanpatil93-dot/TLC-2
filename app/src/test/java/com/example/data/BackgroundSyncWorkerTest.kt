package com.example.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.model.PropertyItem
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundSyncWorkerTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setUp() {
        root = java.nio.file.Files.createTempDirectory("tlc-background-sync").toFile()
        val app = ApplicationProvider.getApplicationContext<Context>()
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences(root.name + "_" + name, mode)
        }
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun property() = PropertyItem(
        id = "test-one", propertyCode = "OL-TEST-1", recordId = "COM-TEST-1",
        location = "Kharadi", priceOrRent = 80000.0, isSyncedToSheet = false
    )
    private fun save(property: PropertyItem) {
        assertTrue(PropertiesLocalDatabase.saveProperties(context, listOf(property)))
    }
    private fun queue() = PropertiesLocalDatabase.loadPendingWrites(context)
    private fun worker() = TestListenableWorkerBuilder<PendingSyncWorker>(context).build()
    private fun configureEndpoint() {
        // Tests that configure this value exit before networking (lease/review gate).
        assertTrue(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putString("google_sheet_url", "https://script.google.com/macros/s/test-not-deployed/exec")
            .commit())
    }

    @Test fun immediateRequestRequiresNetworkAndUsesBackoffWithoutPrivateInputData() {
        val request = BackgroundSyncScheduler.immediateRequest()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30000L, request.workSpec.backoffDelayDuration)
        assertTrue(request.workSpec.input.keyValueMap.isEmpty())
        assertTrue(request.tags.contains(BackgroundSyncScheduler.TAG))
    }

    @Test fun periodicRequestUsesAndroidMinimumIntervalAndNetworkConstraint() {
        val request = BackgroundSyncScheduler.periodicRequest()
        assertEquals(TimeUnit.MINUTES.toMillis(15), request.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertTrue(request.workSpec.input.keyValueMap.isEmpty())
    }

    @Test fun emptyQueueFinishesWithoutCreatingInventory() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(PropertiesLocalDatabase.loadProperties(context).isEmpty())
        assertTrue(queue().isEmpty())
    }

    @Test fun unconfiguredEndpointLeavesDurableOperationUntouched() = runBlocking {
        val original = property()
        save(original)
        val before = queue().single()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(before, queue().single())
        assertEquals(original, PropertiesLocalDatabase.loadProperties(context).single())
    }

    @Test fun reviewBlockedChainDoesNotBecomeAnAutomaticRetryLoop() = runBlocking {
        val original = property()
        save(original)
        val first = queue().single()
        PropertiesLocalDatabase.beginPendingWrite(context, first.operationId, System.currentTimeMillis())
        PropertiesLocalDatabase.failPendingWrite(context, first.operationId, false, "Conflict")
        save(original.copy(priceOrRent = 90000.0))
        assertEquals(2, queue().size)
        assertFalse(PendingSyncQueue.hasRetryableWrites(queue()))
        configureEndpoint()
        val before = queue()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(before, queue())
    }

    @Test fun inFlightLeaseReturnsRetryWithoutReplayingBeforeDeadline() = runBlocking {
        save(property())
        val first = queue().single()
        PropertiesLocalDatabase.beginPendingWrite(context, first.operationId, System.currentTimeMillis())
        configureEndpoint()
        val before = queue().single()
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(before, queue().single())
    }

    @Test fun corruptInventoryIsRetainedAndWorkerRequestsRetry() = runBlocking {
        val file = File(root, "real_estate_inventory_db.json")
        file.writeText("{corrupt")
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals("{corrupt", file.readText())
    }

    @Test fun workerResultMappingSeparatesBlockedAndRetryableOutcomes() {
        listOf(PendingDrainResult.EMPTY, PendingDrainResult.COMPLETE, PendingDrainResult.REVIEW,
            PendingDrainResult.UNCONFIGURED, PendingDrainResult.UNSUPPORTED).forEach {
            assertEquals(ListenableWorker.Result.success(), PendingSyncWorker.toWorkResult(it))
        }
        listOf(PendingDrainResult.WAITING, PendingDrainResult.RETRYABLE_FAILURE,
            PendingDrainResult.STORAGE_FAILURE).forEach {
            assertEquals(ListenableWorker.Result.retry(), PendingSyncWorker.toWorkResult(it))
        }
    }

    @Test fun staleScreenSaveCannotRequeueAnAlreadyAcknowledgedSnapshot() {
        val staleScreen = property()
        save(staleScreen)
        val entry = queue().single()
        PropertiesLocalDatabase.beginPendingWrite(context, entry.operationId, System.currentTimeMillis())
        assertTrue(PropertiesLocalDatabase.acknowledgePendingWrite(context, entry.operationId))
        save(staleScreen)
        assertTrue(queue().isEmpty())
        assertTrue(PropertiesLocalDatabase.loadProperties(context).single().isSyncedToSheet)
    }

    @Test fun genuineNewEditAfterBackgroundAckStillGetsQueued() {
        val original = property()
        save(original)
        val entry = queue().single()
        PropertiesLocalDatabase.beginPendingWrite(context, entry.operationId, System.currentTimeMillis())
        assertTrue(PropertiesLocalDatabase.acknowledgePendingWrite(context, entry.operationId))
        val edited = original.copy(priceOrRent = 90000.0)
        save(edited)
        assertFalse(PropertiesLocalDatabase.loadProperties(context).single().isSyncedToSheet)
        assertEquals(edited, queue().single().property)
        assertNotEquals(entry.operationId, queue().single().operationId)
    }

    @Test fun schedulerRejectsMissingAndUntrustedEndpoints() {
        assertFalse(BackgroundSyncScheduler.ensureScheduled(context))
        assertFalse(BackgroundSyncScheduler.validEndpoint(""))
        assertFalse(BackgroundSyncScheduler.validEndpoint("http://script.google.com/exec"))
        assertFalse(BackgroundSyncScheduler.validEndpoint("https://script.google.com.evil.invalid/exec"))
        assertTrue(BackgroundSyncScheduler.validEndpoint("https://script.google.com/macros/s/test/exec"))
    }

    @Test fun foregroundStatusRefreshObservesBackgroundAcknowledgement() = runBlocking {
        val original = property()
        save(original)
        val repository = SheetsRepository(context, startForegroundSync = false)
        assertFalse(repository.properties.value.single().isSyncedToSheet)
        val entry = queue().single()
        PropertiesLocalDatabase.beginPendingWrite(context, entry.operationId, System.currentTimeMillis())
        assertTrue(PropertiesLocalDatabase.acknowledgePendingWrite(context, entry.operationId))
        assertEquals(PendingDrainResult.EMPTY, repository.retryPendingWrites())
        assertTrue(repository.properties.value.single().isSyncedToSheet)
        assertEquals(0, repository.pendingSyncCount.value)
    }


    @Test fun expiredDrainBudgetDoesNotStartAnotherOperation() = runBlocking {
        save(property())
        configureEndpoint()
        val repository = SheetsRepository(context, startForegroundSync = false)
        val before = queue().single()
        assertEquals(PendingDrainResult.WAITING,
            repository.retryPendingWrites(deadlineMillis = 0L))
        assertEquals(before, queue().single())
        assertEquals(0, queue().single().attempts)
    }

}

