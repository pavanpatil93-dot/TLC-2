package com.example.ai

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.crm.CallRecordingMatcher
import com.example.crm.CrmCallManager
import com.example.crm.CrmCallRecord
import com.example.crm.IgnoredNumbersManager
import com.example.crm.MatchedCallRecording
import com.example.data.DeviceIdentityManager
import com.example.data.SheetsRepository
import com.example.data.master.CallAnalysisEntity
import com.example.data.master.MasterProjectDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class BatchCallAnalysisProgress(
    val isRunning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val succeeded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val currentCaller: String = "",
    val lastError: String? = null
)

/**
 * Coordinates WorkManager batch audio analysis (3 concurrent calls at a time on Dispatchers.IO),
 * single-call AI summarize persistence, microphone dictation analysis, and automatic dual-phone
 * sync to the `Call Logs` tab in TLC Master sheet.
 */
object BatchCallAnalysisCoordinator {
    private const val TAG = "BatchCallAnalysis"
    const val UNIQUE_WORK_NAME = "tlc_batch_call_audio_analysis_v44"
    const val KEY_CALL_IDS = "target_call_ids"

    private val _progress = MutableStateFlow(BatchCallAnalysisProgress())
    val progress: StateFlow<BatchCallAnalysisProgress> = _progress.asStateFlow()

    internal fun updateProgress(state: BatchCallAnalysisProgress) {
        _progress.value = state
    }

    fun enqueueBatchAnalysis(context: Context, callIds: List<String>) {
        if (callIds.isEmpty()) return
        _progress.value = BatchCallAnalysisProgress(
            isRunning = true,
            completed = 0,
            total = callIds.size,
            currentCaller = "Preparing ${callIds.size} call(s)..."
        )
        val inputData = Data.Builder()
            .putStringArray(KEY_CALL_IDS, callIds.toTypedArray())
            .build()
        val request = OneTimeWorkRequestBuilder<BatchCallAnalysisWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(inputData)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelBatchAnalysis(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        _progress.value = _progress.value.copy(
            isRunning = false,
            currentCaller = "Cancelled"
        )
    }

    /**
     * Runs native audio analysis for a single call, saves [CallAnalysisEntity] into Room DB,
     * updates the local [CrmCallRecord], and pushes role/summary/followUp/language/deviceName
     * to the `Call Logs` tab so the second phone sees it immediately.
     */
    suspend fun analyzeSingleCallAndSync(
        context: Context,
        call: CrmCallRecord,
        recording: MatchedCallRecording
    ): Result<CallAnalysisEntity> = withContext(Dispatchers.IO) {
        val analysisRes = CallAudioAiAnalyzer.analyzeAudioRecording(context, call, recording)
        val result = analysisRes.getOrElse { return@withContext Result.failure(it) }
        val entity = persistAndPushAnalysis(
            context = context,
            call = call,
            result = result,
            recordingFilename = recording.fileName
        )
        Result.success(entity)
    }

    /**
     * Runs structured extraction on a voice-dictated transcript (from `gemini-3.5-transcribe`),
     * stores it in Room DB (`call_analysis_master`), and syncs to `Call Logs`.
     */
    suspend fun analyzeDictatedTranscriptAndSync(
        context: Context,
        call: CrmCallRecord,
        transcript: String
    ): Result<CallAnalysisEntity> = withContext(Dispatchers.IO) {
        val analysisRes = CallAudioAiAnalyzer.analyzeTranscriptText(call, transcript)
        val result = analysisRes.getOrElse { return@withContext Result.failure(it) }
        val entity = persistAndPushAnalysis(
            context = context,
            call = call,
            result = result,
            recordingFilename = call.recordingFilename.orEmpty()
        )
        Result.success(entity)
    }

    internal suspend fun persistAndPushAnalysis(
        context: Context,
        call: CrmCallRecord,
        result: CallAudioAnalysisResult,
        recordingFilename: String
    ): CallAnalysisEntity = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val deviceName = DeviceIdentityManager.getFriendlyDeviceName(appCtx)
        val entity = result.toEntity(
            call = call,
            deviceName = deviceName,
            recordingFilename = recordingFilename,
            reviewStatus = "PENDING_REVIEW"
        )
        val dao = MasterProjectDatabase.getDatabase(appCtx).masterProjectDao()
        dao.upsertCallAnalysis(entity)

        // Enrich local CrmCallRecord with clean English summary & follow-up without auto-creating CRM/Inventory records
        val summaryText = result.formattedBulletsSummary().ifBlank { call.notes }
        val followUpStamp = listOf(result.followUp.date, result.followUp.time)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val updatedCall = call.copy(
            notes = summaryText,
            nextFollowUpDateTime = followUpStamp.ifBlank { call.nextFollowUpDateTime },
            actionRequired = result.followUp.nextAction.ifBlank { call.actionRequired },
            recordingFilename = recordingFilename.ifBlank { call.recordingFilename },
            deviceName = deviceName,
            syncStatus = "PENDING",
            updatedAt = System.currentTimeMillis()
        )
        CrmCallManager.addOrUpdateCall(appCtx, updatedCall)

        // Push enriched Call Log (with AI Role, AI Summary, Follow-Up, Detected Language, Device Name) to TLC Master sheet
        runCatching {
            val repo = SheetsRepository(appCtx, startForegroundSync = false)
            val pushRes = repo.pushCallLogToMasterSheet(updatedCall, analysisOverride = entity)
            if (pushRes.isSuccess) {
                dao.markCallAnalysisSynced(entity.callLogId)
                return@withContext entity.copy(syncedToSheet = true)
            }
        }.onFailure { err ->
            Log.w(TAG, "Dual-phone Call Logs sheet push deferred for ${call.id}: ${err.message}")
        }
        entity
    }
}

class BatchCallAnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val appCtx = applicationContext
        val requestedIds = inputData.getStringArray(BatchCallAnalysisCoordinator.KEY_CALL_IDS)
            ?.toSet()
            .orEmpty()

        val dao = MasterProjectDatabase.getDatabase(appCtx).masterProjectDao()
        val alreadyAnalyzedIds = dao.getAnalyzedCallIdsSync().toSet()
        val allCalls = CrmCallManager.readCalls(appCtx)

        val candidateCalls = allCalls.filter { call ->
            (requestedIds.isEmpty() || call.id in requestedIds) &&
                CallAudioPreFilter.evaluateEligibility(
                    durationSeconds = call.durationSeconds,
                    callType = call.callType,
                    phoneNumber = call.phoneNumber,
                    callerName = call.callerName,
                    leadStatus = call.leadStatus,
                    isIgnoredOrPersonal = IgnoredNumbersManager.isIgnoredOrInternal(appCtx, call.phoneNumber),
                    aiAnalyzedAt = if (call.id in alreadyAnalyzedIds) 1L else null
                ) == AudioPreFilterSkipReason.NONE
        }

        if (candidateCalls.isEmpty()) {
            BatchCallAnalysisCoordinator.updateProgress(
                BatchCallAnalysisProgress(
                    isRunning = false,
                    completed = 0,
                    total = 0,
                    currentCaller = "All eligible calls are already analyzed."
                )
            )
            return@withContext Result.success()
        }

        val outcomes = CallRecordingMatcher.matchRecordingOutcomesForCallsOnDemand(appCtx, candidateCalls)
        val queueWithAudio = candidateCalls.mapNotNull { call ->
            outcomes[call.id]?.matched?.let { rec -> call to rec }
        }
        val missingAudioCount = candidateCalls.size - queueWithAudio.size

        if (queueWithAudio.isEmpty()) {
            BatchCallAnalysisCoordinator.updateProgress(
                BatchCallAnalysisProgress(
                    isRunning = false,
                    completed = 0,
                    total = 0,
                    skipped = missingAudioCount,
                    lastError = "No matched call recordings found for eligible calls. Link your recordings folder or use Dictate Voice Note."
                )
            )
            return@withContext Result.success()
        }

        val total = queueWithAudio.size
        val completedCount = AtomicInteger(0)
        val succeededCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val semaphore = Semaphore(permits = 3) // Process 3 calls concurrently without saturating network/memory
        val latestErrorRef = java.util.concurrent.atomic.AtomicReference<String?>(null)

        BatchCallAnalysisCoordinator.updateProgress(
            BatchCallAnalysisProgress(
                isRunning = true,
                completed = 0,
                total = total,
                skipped = missingAudioCount,
                currentCaller = queueWithAudio.first().first.callerName.ifBlank { queueWithAudio.first().first.phoneNumber }
            )
        )

        coroutineScope {
            queueWithAudio.map { (call, recording) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (isStopped) return@withPermit
                        val callerLabel = call.callerName.ifBlank { call.phoneNumber }
                        BatchCallAnalysisCoordinator.updateProgress(
                            BatchCallAnalysisProgress(
                                isRunning = true,
                                completed = completedCount.get(),
                                total = total,
                                succeeded = succeededCount.get(),
                                skipped = missingAudioCount,
                                failed = failedCount.get(),
                                currentCaller = callerLabel,
                                lastError = latestErrorRef.get()
                            )
                        )
                        val res = BatchCallAnalysisCoordinator.analyzeSingleCallAndSync(appCtx, call, recording)
                        if (res.isSuccess) {
                            succeededCount.incrementAndGet()
                        } else {
                            failedCount.incrementAndGet()
                            latestErrorRef.set(res.exceptionOrNull()?.message)
                        }
                        val done = completedCount.incrementAndGet()
                        BatchCallAnalysisCoordinator.updateProgress(
                            BatchCallAnalysisProgress(
                                isRunning = done < total && !isStopped,
                                completed = done,
                                total = total,
                                succeeded = succeededCount.get(),
                                skipped = missingAudioCount,
                                failed = failedCount.get(),
                                currentCaller = callerLabel,
                                lastError = latestErrorRef.get()
                            )
                        )
                    }
                }
            }.awaitAll()
        }

        BatchCallAnalysisCoordinator.updateProgress(
            BatchCallAnalysisProgress(
                isRunning = false,
                completed = completedCount.get(),
                total = total,
                succeeded = succeededCount.get(),
                skipped = missingAudioCount,
                failed = failedCount.get(),
                currentCaller = "Finished analyzing ${succeededCount.get()} of $total call(s)",
                lastError = latestErrorRef.get()
            )
        )
        Result.success()
    }
}
