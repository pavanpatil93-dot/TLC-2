package com.example.sync

/**
 * TLC-2 Android ↔ Website API contract, staged only.
 * This code performs no networking, storage, call recording, or live Sheet updates.
 * Activate only after signed-in user identity, server authorization and staging tests.
 */
enum class TlcMutationOperation { CREATE, UPDATE, ARCHIVE, PUBLISH, UNPUBLISH }

data class TlcPendingMutation(
    val operationId: String,
    val devicePublicId: String,
    val propertyId: String?,
    val baseVersion: Long?,
    val operation: TlcMutationOperation,
    val changedFields: Map<String, String>
) {
    fun isStructurallyValid(): Boolean =
        operationId.isNotBlank() &&
            devicePublicId.isNotBlank() &&
            (operation == TlcMutationOperation.CREATE || (!propertyId.isNullOrBlank() && baseVersion != null && baseVersion > 0)) &&
            changedFields.keys.none { it.isBlank() }
}

sealed interface TlcMutationResult {
    data class Accepted(val remoteVersion: Long, val changeEventId: Long): TlcMutationResult
    data class VersionConflict(val currentRemoteVersion: Long): TlcMutationResult
    data object Unauthorized: TlcMutationResult
    data object Forbidden: TlcMutationResult
    data object OfflineRetry: TlcMutationResult
    data object InvalidData: TlcMutationResult
}

object TlcSyncPolicy {
    /** Avoid rewriting a newer server version with a stale offline edit. */
    fun canApply(baseVersion: Long?, currentVersion: Long): Boolean =
        baseVersion != null && baseVersion > 0 && baseVersion == currentVersion

    /** Only retry transport failures or temporary server unavailability; not validation/permission failures. */
    fun shouldRetry(result: TlcMutationResult): Boolean = result is TlcMutationResult.OfflineRetry

    /** No automatic publication by externally submitted, unverified records. */
    fun mayAutoPublish(isVerified: Boolean, isAvailable: Boolean, isExplicitlyApproved: Boolean): Boolean =
        isVerified && isAvailable && isExplicitlyApproved
}
