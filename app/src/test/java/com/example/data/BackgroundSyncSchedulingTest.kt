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
}

