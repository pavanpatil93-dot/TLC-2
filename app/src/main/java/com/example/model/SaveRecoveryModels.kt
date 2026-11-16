package com.example.model

/**
 * Group 02 — Standardized Save, Pending Sync, Storage Health, and SHA-256 Backup/Restore models.
 */
enum class SaveEntityType {
    PROPERTY,
    PROJECT,
    CLIENT,
    LEAD,
    CALL_LOG,
    FOLLOW_UP,
    SITE_VISIT,
    EMPLOYEE,
    SETTINGS
}

enum class SaveStatus {
    SAVED_LOCAL,
    SAVED_AND_QUEUED,
    VALIDATION_ERROR,
    PERMISSION_DENIED,
    CONFLICT,
    STORAGE_ERROR
}

data class SaveMutationResult<T>(
    val status: SaveStatus,
    val mutationId: String,
    val entityType: SaveEntityType,
    val recordId: String,
    val data: T? = null,
    val newVersion: Int? = null,
    val message: String,
    val userActionHint: String? = null,
    val retryable: Boolean = false
)

enum class PendingSyncOpStatus {
    PENDING,
    IN_FLIGHT,
    BLOCKED,
    RETRYABLE_ERROR,
    CONFLICT,
    ACKNOWLEDGED
}

enum class PendingSyncAction {
    UPSERT,
    DELETE,
    STATUS_CHANGE
}

data class PendingSyncOp(
    val opId: String,
    val mutationId: String,
    val entityType: SaveEntityType,
    val entityId: String,
    val action: PendingSyncAction,
    val payloadJson: String,
    val expectedVersion: Int,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val status: PendingSyncOpStatus = PendingSyncOpStatus.PENDING,
    val lastError: String? = null
) {
    companion object {
        const val MIN_BACKOFF_SECONDS = 30L
        const val MAX_BACKOFF_SECONDS = 3600L

        /**
         * Bounded exponential backoff: min(30 * 2^(attemptCount - 1), 3600) seconds + deterministic/bounded jitter.
         */
        fun computeBackoffMs(attemptCount: Int, jitterMs: Long = 0L): Long {
            val exp = (attemptCount - 1).coerceIn(0, 10)
            val delaySeconds = (MIN_BACKOFF_SECONDS * (1L shl exp)).coerceAtMost(MAX_BACKOFF_SECONDS)
            return delaySeconds * 1000L + jitterMs.coerceIn(0L, 5000L)
        }
    }
}

enum class StorageHealthState {
    HEALTHY,
    RECOVERED_FROM_BACKUP,
    CORRUPT_REQUIRES_ACTION
}

data class StorageHealthReport(
    val state: StorageHealthState = StorageHealthState.HEALTHY,
    val corruptFilePath: String? = null,
    val quarantinedFilePath: String? = null,
    val hasValidBackup: Boolean = false,
    val backupRecordCount: Int = 0,
    val message: String = ""
)

enum class RestoreMode(val label: String) {
    MERGE_MISSING("Merge Missing Records"),
    REVIEW_CONFLICTS("Merge & Review Conflicts"),
    FULL_REPLACE("Full Replace (Admin Only)")
}

data class BackupManifest(
    val backupFormatVersion: Int = 1,
    val applicationId: String = "com.aistudio.ascendaurea.iigmlc",
    val createdAtIso: String,
    val createdByEmployeeId: String,
    val propertyCount: Int,
    val crmLeadCount: Int,
    val projectCount: Int,
    val pendingSyncCount: Int,
    val payloadSha256: String
)

data class RestorePreviewResult(
    val isValid: Boolean,
    val manifest: BackupManifest? = null,
    val errorMessage: String? = null,
    val newPropertiesCount: Int = 0,
    val conflictingPropertiesCount: Int = 0,
    val matchingPropertiesCount: Int = 0,
    val newCrmLeadsCount: Int = 0,
    val newProjectsCount: Int = 0,
    val requiresAdminForFullReplace: Boolean = true
)

data class RestoreOutcome(
    val success: Boolean,
    val mode: RestoreMode = RestoreMode.MERGE_MISSING,
    val restoredPropertiesCount: Int = 0,
    val mergedPropertiesCount: Int = 0,
    val skippedConflictsCount: Int = 0,
    val rolledBackOnFailure: Boolean = false,
    val preRestoreSnapshotId: String? = null,
    val message: String
)
