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
            val hasRetryable = queue.isNotEmpty() && PendingSyncQueue.hasRetryableWrites(queue)
            val rawEndpoint = applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .getString("google_sheet_url", "")?.trim().orEmpty()
            val endpoint = rawEndpoint.ifBlank { SheetsRepository.DEFAULT_MASTER_SHEET_URL }
            if (!BackgroundSyncScheduler.validEndpoint(endpoint)) {
                return@withContext Result.success()
            }

            // Never start the screen repository's persistent polling loops in a worker.
            val repository = SheetsRepository(applicationContext, startForegroundSync = false)
            val deadline = System.currentTimeMillis() + 2 * 60 * 1000L
            try {
                withTimeout(120000L) {
                    var writeResult = ListenableWorker.Result.success()
                    if (hasRetryable) {
                        if (PendingSyncQueue.nextDue(queue, System.currentTimeMillis()) == null) {
                            return@withTimeout ListenableWorker.Result.retry()
                        }
                        writeResult = toWorkResult(
                            repository.retryPendingWrites(
                                deadlineMillis = deadline,
                                maxOperations = 3
                            )
                        )
                    }
                    // Every 15 minutes (or when stale), silently pull Master Sheet datasets to local filesDir cache and run team alert diff
                    if (repository.isSyncStale()) {
                        runCatching { repository.syncWithGoogleSheetNow() }
                    }
                    writeResult
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

