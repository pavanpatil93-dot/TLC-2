package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.model.PropertyItem
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundSyncSchedulingTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManager
    private var executions = 0

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        PropertiesLocalDatabase.clearAll(context)
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context, workerClassName: String, parameters: WorkerParameters
            ): ListenableWorker? = if (workerClassName == PendingSyncWorker::class.java.name) {
                object : Worker(appContext, parameters) {
                    override fun doWork(): Result {
                        executions++
                        return Result.success()
                    }
                }
            } else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(factory)
            .build())
        manager = WorkManager.getInstance(context)
        executions = 0
        assertTrue(PropertiesLocalDatabase.saveProperties(context, listOf(PropertyItem(
            id = "scheduling-test", propertyCode = "OL-SCHED-1", recordId = "COM-SCHED-1",
            location = "Kharadi", isSyncedToSheet = false
        ))))
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putString("google_sheet_url", "https://script.google.com/macros/s/test-only/exec")
            .commit()
    }

    @After fun tearDown() {
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        PropertiesLocalDatabase.clearAll(context)
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun repeatedSchedulingKeepsSingleActiveJobsAndTheirIds() {
        assertTrue(BackgroundSyncScheduler.ensureScheduled(context))
        val first = manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.IMMEDIATE_WORK)
            .get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
        assertTrue(BackgroundSyncScheduler.ensureScheduled(context))
        assertTrue(BackgroundSyncScheduler.ensureScheduled(context))
        val immediate = manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.IMMEDIATE_WORK)
            .get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }
        val periodic = manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.PERIODIC_WORK)
            .get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }
        assertEquals(1, immediate.size)
        assertEquals(first.id, immediate.single().id)
        assertEquals(1, periodic.size)
        assertEquals(WorkInfo.State.ENQUEUED, immediate.single().state)
        assertEquals(0, executions)
    }

    @Test fun scheduledWorkWaitsForNetworkConstraintBeforeExecution() {
        assertTrue(BackgroundSyncScheduler.ensureScheduled(context))
        val work = manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.IMMEDIATE_WORK)
            .get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
        assertEquals(0, executions)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(work.id)
        assertEquals(WorkInfo.State.SUCCEEDED,
            manager.getWorkInfoById(work.id).get(10, TimeUnit.SECONDS)!!.state)
        assertEquals(1, executions)
        // The fake worker never performs a live request or acknowledges inventory.
        assertEquals(1, PropertiesLocalDatabase.loadPendingWrites(context).size)
        assertFalse(PropertiesLocalDatabase.loadProperties(context).single().isSyncedToSheet)
    }

    @Test fun masterAutoSyncSchedulesEvery30MinEvenWhenPendingQueueEmpty() {
        PropertiesLocalDatabase.clearAll(context)
        assertTrue(PropertiesLocalDatabase.loadPendingWrites(context).isEmpty())
        assertTrue(BackgroundSyncScheduler.ensureMasterAutoSyncScheduled(context))
        val works = manager.getWorkInfosForUniqueWork(BackgroundSyncScheduler.MASTER_AUTO_SYNC_WORK)
            .get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }
        assertEquals(1, works.size)
        assertEquals(WorkInfo.State.ENQUEUED, works.single().state)
        assertEquals(30L, BackgroundSyncScheduler.AUTO_SYNC_INTERVAL_MINUTES)
    }

    @Test fun offlineDiskCachePersistsAllNineDatasetsIncludingTenants() {
        val sampleTenants = listOf(
            OfflineDiskCache.TenantCacheRecord(
                id = "TEN-101",
                tenantName = "Acme Fintech Pvt Ltd",
                propertyCode = "OL-KHD-101",
                projectName = "Gera Imperium Alpha",
                location = "Kharadi",
                monthlyRent = "85000",
                leaseStartDate = "2024-06-01",
                leaseExpiryDate = "2027-05-31",
                lockInPeriod = "18 Months",
                contactNumber = "9876543210",
                notes = "Locked-in tenant"
            )
        )
        OfflineDiskCache.saveTenants(context, sampleTenants)
        val loaded = OfflineDiskCache.loadTenants(context)
        assertEquals(1, loaded.size)
        assertEquals("TEN-101", loaded.single().id)
        assertEquals("Acme Fintech Pvt Ltd", loaded.single().tenantName)
        assertEquals("OL-KHD-101", loaded.single().propertyCode)
        assertEquals("2027-05-31", loaded.single().leaseExpiryDate)
    }

    @Test fun repositoryStalenessCheckAndSyncedAgoFormattingWork() {
        val repo = SheetsRepository(context, startForegroundSync = false, deferInitialDiskLoad = true)
        try {
            assertTrue(repo.isSyncStale())
            assertEquals("Not synced yet", SheetsRepository.formatSyncedAgoLabel(0L, 1_000_000L))

            val now = 1_700_000_000_000L
            repo.markSuccessfulSyncNow(now)
            assertFalse(repo.isSyncStale(maxAgeMs = SheetsRepository.AUTO_SYNC_INTERVAL_MS, nowMillis = now + 5 * 60_000L))
            assertTrue(repo.isSyncStale(maxAgeMs = SheetsRepository.AUTO_SYNC_INTERVAL_MS, nowMillis = now + 31 * 60_000L))
            assertEquals("Synced just now", SheetsRepository.formatSyncedAgoLabel(now, now + 20_000L))
            assertEquals("Synced 12m ago", SheetsRepository.formatSyncedAgoLabel(now, now + 12 * 60_000L))
            assertEquals("Synced 2h ago", SheetsRepository.formatSyncedAgoLabel(now, now + 2 * 60 * 60_000L))
        } finally {
            repo.close()
        }
    }

    @Test fun propertyPrecomputedCachePrecomputesInrPriceCompletenessAndSearchBlob() {
        com.example.inventory.PropertyPrecomputedCache.clear()
        val sample = PropertyItem(
            id = "prop_pre_1",
            propertyCode = "OL-PRE-1",
            projectName = "World Trade Center",
            location = "Kharadi, Pune",
            unitNumber = "405",
            floor = "4th",
            propertyType = "Office",
            tab = com.example.model.InventoryTab.OFF_LEASE,
            carpetAreaSqFt = 1200.0,
            priceOrRent = 125000.0,
            rent = 125000.0,
            ownerName = "Rajesh Sharma",
            ownerContact = "9876501234",
            status = com.example.model.AvailabilityStatus.AVAILABLE,
            verificationStatus = com.example.model.VerificationStatus.VERIFIED
        )
        val cached = com.example.inventory.PropertyPrecomputedCache.get(sample)
        assertTrue(cached.priceDisplay.contains("1.25 Lac"))
        assertTrue(cached.searchBlobLower.contains("world trade center"))
        assertTrue(cached.searchBlobLower.contains("kharadi"))
        assertTrue(cached.detailedCompleteness.percent > 0)
        assertSame(cached, com.example.inventory.PropertyPrecomputedCache.get(sample))
    }
}

