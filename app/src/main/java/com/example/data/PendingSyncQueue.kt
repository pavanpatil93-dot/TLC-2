package com.example.data

import android.content.Context
import android.util.AtomicFile
import com.example.model.PendingSyncAction
import com.example.model.PendingSyncOp
import com.example.model.PendingSyncOpStatus
import com.example.model.PropertyItem
import com.example.model.SaveEntityType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class PendingDrainResult {
    EMPTY, COMPLETE, WAITING, REVIEW, UNCONFIGURED, UNSUPPORTED, RETRYABLE_FAILURE, STORAGE_FAILURE
}

internal enum class PendingWriteState { READY, IN_FLIGHT, REVIEW }

internal data class PendingSyncWrite(
    val operationId: String,
    val property: PropertyItem,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val state: PendingWriteState = PendingWriteState.READY,
    val lastError: String = ""
)

/** Queue entries contain immutable snapshots; an attempted operation ID is never reused for changed content. */
internal object PendingSyncQueue {
    fun reconcile(
        properties: List<PropertyItem>, existing: List<PendingSyncWrite>, now: Long
    ): List<PendingSyncWrite> {
        val ids = properties.map { it.id }.toSet()
        val syncedIds = properties.filter { it.isSyncedToSheet }.map { it.id }.toSet()
        val queue = existing.mapNotNull { entry ->
            when {
                entry.property.id in syncedIds && entry.attempts == 0 -> null
                entry.property.id in syncedIds -> entry.copy(state = PendingWriteState.REVIEW,
                    lastError = "Record refreshed; previous remote outcome requires review")
                entry.property.id in ids -> entry
                entry.attempts == 0 -> null
                else -> entry.copy(state = PendingWriteState.REVIEW,
                    lastError = "Local property removed; remote outcome requires review")
            }
        }.toMutableList()
        properties.filter { !it.isSyncedToSheet }.forEach { property ->
            val related = queue.filter { it.property.id == property.id }
            if (related.lastOrNull()?.let {
                    AppsScriptSyncProtocol.sameWriteSnapshot(it.property, property)
                } == true) return@forEach
            // Unsent edits can be coalesced; attempted snapshots must retain their IDs.
            queue.removeAll { it.property.id == property.id && it.attempts == 0 &&
                it.state == PendingWriteState.READY }
            queue.add(PendingSyncWrite(UUID.randomUUID().toString(),
                property.copy(isSyncedToSheet = false), now))
        }
        return queue
    }

    fun hasRetryableWrites(queue: List<PendingSyncWrite>): Boolean =
        queue.groupBy { it.property.id }.values.any { it.first().state != PendingWriteState.REVIEW }

    fun nextDue(queue: List<PendingSyncWrite>, now: Long): PendingSyncWrite? {
        val seen = mutableSetOf<String>()
        return queue.firstOrNull { entry ->
            val firstForProperty = seen.add(entry.property.id)
            firstForProperty && entry.state != PendingWriteState.REVIEW &&
                entry.nextAttemptAt <= now
        }
    }

    fun begin(entry: PendingSyncWrite, now: Long): PendingSyncWrite =
        entry.copy(attempts = entry.attempts + 1, state = PendingWriteState.IN_FLIGHT,
            nextAttemptAt = now + maxOf(120000L, retryDelay(entry.attempts + 1)), lastError = "")

    fun failed(entry: PendingSyncWrite, retryable: Boolean, message: String, now: Long): PendingSyncWrite =
        entry.copy(state = if (retryable) PendingWriteState.READY else PendingWriteState.REVIEW,
            nextAttemptAt = now + retryDelay(entry.attempts), lastError = message)

    fun retryDelay(attempts: Int): Long =
        (30000L * (1L shl (attempts - 1).coerceIn(0, 20))).coerceAtMost(6 * 60 * 60 * 1000L)
}

/**
 * Group 02 — General-purpose FIFO per-entity durable PendingSyncOp store with
 * mutationId deduplication, bounded exponential backoff + jitter, auth blocking, and conflict review.
 */
object PendingSyncOpStore {
    private const val FILE_NAME = "pending_sync_ops_v1.json"

    @Synchronized
    fun loadAll(context: Context): List<PendingSyncOp> {
        val file = File(context.filesDir, FILE_NAME)
        val atomic = AtomicFile(file)
        val hasCurrent = file.exists() || File(context.filesDir, "$FILE_NAME.bak").exists()
        if (!hasCurrent) return emptyList()
        val raw = runCatching {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PendingSyncOp(
                    opId = obj.getString("opId"),
                    mutationId = obj.getString("mutationId"),
                    entityType = runCatching {
                        SaveEntityType.valueOf(obj.optString("entityType", SaveEntityType.PROPERTY.name))
                    }.getOrDefault(SaveEntityType.PROPERTY),
                    entityId = obj.getString("entityId"),
                    action = runCatching {
                        PendingSyncAction.valueOf(obj.optString("action", PendingSyncAction.UPSERT.name))
                    }.getOrDefault(PendingSyncAction.UPSERT),
                    payloadJson = obj.optString("payloadJson", "{}"),
                    expectedVersion = obj.optInt("expectedVersion", 0),
                    createdAt = obj.optLong("createdAt", 0L),
                    attemptCount = obj.optInt("attemptCount", 0),
                    nextAttemptAt = obj.optLong("nextAttemptAt", 0L),
                    status = runCatching {
                        PendingSyncOpStatus.valueOf(obj.optString("status", PendingSyncOpStatus.PENDING.name))
                    }.getOrDefault(PendingSyncOpStatus.PENDING),
                    lastError = if (obj.isNull("lastError")) null else obj.optString("lastError", "").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveAll(context: Context, ops: List<PendingSyncOp>): Boolean {
        return try {
            val arr = JSONArray().apply {
                ops.forEach { op ->
                    put(JSONObject().apply {
                        put("opId", op.opId)
                        put("mutationId", op.mutationId)
                        put("entityType", op.entityType.name)
                        put("entityId", op.entityId)
                        put("action", op.action.name)
                        put("payloadJson", op.payloadJson)
                        put("expectedVersion", op.expectedVersion)
                        put("createdAt", op.createdAt)
                        put("attemptCount", op.attemptCount)
                        put("nextAttemptAt", op.nextAttemptAt)
                        put("status", op.status.name)
                        if (op.lastError != null) put("lastError", op.lastError) else put("lastError", JSONObject.NULL)
                    })
                }
            }
            val atomic = AtomicFile(File(context.filesDir, FILE_NAME))
            val stream = atomic.startWrite()
            try {
                stream.write(arr.toString(2).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
                true
            } catch (e: Exception) {
                atomic.failWrite(stream)
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun enqueueOrCoalesce(context: Context, op: PendingSyncOp): Boolean {
        val current = loadAll(context).toMutableList()
        if (current.any { it.mutationId == op.mutationId && it.mutationId.isNotBlank() }) {
            return true
        }
        // Coalesce unattempted PENDING ops for same entity; never mutate IN_FLIGHT/CONFLICT ops.
        current.removeAll {
            it.entityType == op.entityType &&
                it.entityId == op.entityId &&
                it.status == PendingSyncOpStatus.PENDING &&
                it.attemptCount == 0
        }
        current.add(op)
        return saveAll(context, current)
    }

    /**
     * Strict FIFO per (entityType, entityId): if an entity has an earlier non-ACKNOWLEDGED op
     * that is IN_FLIGHT, BLOCKED, or CONFLICT, newer ops for that same entity must wait.
     */
    fun nextDue(ops: List<PendingSyncOp>, nowMs: Long): PendingSyncOp? {
        val seenEntities = mutableSetOf<Pair<SaveEntityType, String>>()
        return ops.filter { it.status != PendingSyncOpStatus.ACKNOWLEDGED }
            .firstOrNull { op ->
                val firstForEntity = seenEntities.add(op.entityType to op.entityId)
                firstForEntity &&
                    (op.status == PendingSyncOpStatus.PENDING || op.status == PendingSyncOpStatus.RETRYABLE_ERROR) &&
                    op.nextAttemptAt <= nowMs
            }
    }

    @Synchronized
    fun markInFlight(context: Context, opId: String, nowMs: Long): PendingSyncOp? {
        val current = loadAll(context)
        val due = nextDue(current, nowMs) ?: return null
        if (due.opId != opId) return null
        val updated = due.copy(
            attemptCount = due.attemptCount + 1,
            status = PendingSyncOpStatus.IN_FLIGHT,
            lastError = null
        )
        return if (saveAll(context, current.map { if (it.opId == opId) updated else it })) updated else null
    }

    @Synchronized
    fun markRetryableError(
        context: Context,
        opId: String,
        error: String,
        nowMs: Long,
        jitterMs: Long = (opId.hashCode().toLong() and 0x7FFF) % 3000L
    ): Boolean {
        val current = loadAll(context)
        val updated = current.map { op ->
            if (op.opId == opId) {
                val backoffMs = PendingSyncOp.computeBackoffMs(op.attemptCount.coerceAtLeast(1), jitterMs)
                op.copy(
                    status = PendingSyncOpStatus.RETRYABLE_ERROR,
                    nextAttemptAt = nowMs + backoffMs,
                    lastError = error
                )
            } else op
        }
        return saveAll(context, updated)
    }

    @Synchronized
    fun markBlocked(context: Context, opId: String, error: String): Boolean {
        val current = loadAll(context)
        return saveAll(context, current.map { op ->
            if (op.opId == opId) op.copy(status = PendingSyncOpStatus.BLOCKED, lastError = error) else op
        })
    }

    @Synchronized
    fun markConflict(context: Context, opId: String, error: String): Boolean {
        val current = loadAll(context)
        return saveAll(context, current.map { op ->
            if (op.opId == opId) op.copy(status = PendingSyncOpStatus.CONFLICT, lastError = error) else op
        })
    }

    @Synchronized
    fun markAcknowledged(context: Context, opId: String): Boolean {
        val current = loadAll(context)
        return saveAll(context, current.filterNot { it.opId == opId })
    }

    @Synchronized
    fun retryBlockedAndRetryable(context: Context, hasValidAuth: Boolean, nowMs: Long = System.currentTimeMillis()): Int {
        val current = loadAll(context)
        var changed = 0
        val next = current.map { op ->
            when {
                op.status == PendingSyncOpStatus.BLOCKED && hasValidAuth -> {
                    changed++
                    op.copy(status = PendingSyncOpStatus.PENDING, nextAttemptAt = nowMs, lastError = null)
                }
                op.status == PendingSyncOpStatus.RETRYABLE_ERROR -> {
                    changed++
                    op.copy(nextAttemptAt = nowMs)
                }
                else -> op
            }
        }
        if (changed > 0) saveAll(context, next)
        return changed
    }

    @Synchronized
    fun resolveConflictKeepLocal(context: Context, opId: String, newExpectedVersion: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val current = loadAll(context)
        return saveAll(context, current.map { op ->
            if (op.opId == opId) {
                op.copy(
                    expectedVersion = newExpectedVersion,
                    status = PendingSyncOpStatus.PENDING,
                    nextAttemptAt = nowMs,
                    lastError = null
                )
            } else op
        })
    }

    @Synchronized
    fun discardOp(context: Context, opId: String): Boolean {
        val current = loadAll(context)
        return saveAll(context, current.filterNot { it.opId == opId })
    }
}
