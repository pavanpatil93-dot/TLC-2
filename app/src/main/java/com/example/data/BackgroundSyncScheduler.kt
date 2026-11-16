package com.example.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.net.URI
import java.util.concurrent.TimeUnit

internal object BackgroundSyncScheduler {
    const val IMMEDIATE_WORK = "tlc-pending-sync-once"
    const val PERIODIC_WORK = "tlc-pending-sync-periodic"
    const val TAG = "tlc-pending-sync"

    fun validEndpoint(endpoint: String): Boolean {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "script.google.com"
    }

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    fun immediateRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<PendingSyncWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            // No property data, phone numbers or endpoint tokens enter WorkManager Data.
            .build()

    fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<PendingSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

    fun ensureScheduled(context: Context): Boolean {
        val endpoint = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("google_sheet_url", "").orEmpty()
        if (!validEndpoint(endpoint)) return false
        val queue = PropertiesLocalDatabase.loadPendingWrites(context)
        if (!PendingSyncQueue.hasRetryableWrites(queue)) return false
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, immediateRequest())
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
        return true
    }
}

