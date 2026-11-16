package com.example.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class PendingSyncWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!PropertiesLocalDatabase.recoverPendingWrites(applicationContext)) {
                return@withContext Result.retry()
            }
            val queue = PropertiesLocalDatabase.loadPendingWrites(applicationContext)
            if (queue.isEmpty() || !PendingSyncQueue.hasRetryableWrites(queue)) {
                return@withContext Result.success()
            }
            val endpoint = applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getString("google_sheet_url", "").orEmpty()
            if (!BackgroundSyncScheduler.validEndpoint(endpoint)) {
                return@withContext Result.success()
            }
            if (PendingSyncQueue.nextDue(queue, System.currentTimeMillis()) == null) {
                return@withContext Result.retry()
            }
            // Never start the screen repository's persistent polling loops in a worker.
            val repository = SheetsRepository(applicationContext, startForegroundSync = false)
            // Leave room for a bounded in-progress HTTP request before Android's
            // worker execution limit. Remaining operations stay durable for retry.
            val deadline = System.currentTimeMillis() + 2 * 60 * 1000L
            try {
                withTimeout(120000L) {
                    toWorkResult(repository.retryPendingWrites(
                        deadlineMillis = deadline, maxOperations = 3))
                }
            } finally {
                repository.close()
            }
        } catch (_: TimeoutCancellationException) {
            Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Android may stop work when constraints change. Durable leases/IDs remain.
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    internal companion object {
        fun toWorkResult(outcome: PendingDrainResult): ListenableWorker.Result =
            when (outcome) {
                PendingDrainResult.EMPTY, PendingDrainResult.COMPLETE,
                PendingDrainResult.REVIEW, PendingDrainResult.UNCONFIGURED,
                PendingDrainResult.UNSUPPORTED -> ListenableWorker.Result.success()
                PendingDrainResult.WAITING, PendingDrainResult.RETRYABLE_FAILURE,
                PendingDrainResult.STORAGE_FAILURE -> ListenableWorker.Result.retry()
            }
    }
}

