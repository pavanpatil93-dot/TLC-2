package com.example.inventory

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * TLC v2.2 inventory rules. Pure domain logic: no existing property schema is changed.
 * Map the existing sheet/property model to this policy at the repository boundary.
 */
enum class AvailabilityStatus {
    AVAILABLE, ON_HOLD, TOKEN_RECEIVED, SOLD, LEASED_OUT,
    WITHDRAWN, UNKNOWN, ARCHIVED
}

enum class VerificationStatus {
    VERIFIED, VERIFICATION_DUE, UNVERIFIED, CONFLICTING_INFORMATION
}

data class VerificationSnapshot(
    val availability: AvailabilityStatus,
    val verification: VerificationStatus,
    val lastConfirmedAt: Instant? = null
)

data class SharingDecision(
    val allowed: Boolean,
    val warning: String? = null
)

object InventoryVerificationPolicy {
    const val REVERIFY_AFTER_DAYS = 30L

    /** A timestamp changes only after actual confirmation, never after a view or ordinary edit. */
    fun effectiveVerificationStatus(
        snapshot: VerificationSnapshot,
        now: Instant
    ): VerificationStatus {
        if (snapshot.verification == VerificationStatus.CONFLICTING_INFORMATION) {
            return VerificationStatus.CONFLICTING_INFORMATION
        }
        val confirmed = snapshot.lastConfirmedAt
            ?: return VerificationStatus.UNVERIFIED
        if (snapshot.verification == VerificationStatus.UNVERIFIED) {
            return VerificationStatus.UNVERIFIED
        }
        return if (!confirmed.isAfter(now) &&
            !confirmed.plus(REVERIFY_AFTER_DAYS, ChronoUnit.DAYS).isAfter(now)
        ) VerificationStatus.VERIFICATION_DUE else VerificationStatus.VERIFIED
    }

    fun eligibleForActiveMatching(availability: AvailabilityStatus): Boolean =
        availability == AvailabilityStatus.AVAILABLE

    fun sharingDecision(snapshot: VerificationSnapshot, now: Instant): SharingDecision {
        when (snapshot.availability) {
            AvailabilityStatus.SOLD, AvailabilityStatus.LEASED_OUT,
            AvailabilityStatus.WITHDRAWN, AvailabilityStatus.ARCHIVED ->
                return SharingDecision(false, "Property is no longer available")
            AvailabilityStatus.UNKNOWN ->
                return SharingDecision(false, "Confirm availability before sharing")
            AvailabilityStatus.ON_HOLD, AvailabilityStatus.TOKEN_RECEIVED ->
                return SharingDecision(false, "Restricted availability: verify before sharing")
            AvailabilityStatus.AVAILABLE -> Unit
        }
        return when (effectiveVerificationStatus(snapshot, now)) {
            VerificationStatus.VERIFIED -> SharingDecision(true)
            VerificationStatus.VERIFICATION_DUE ->
                SharingDecision(true, "Verification is over 30 days old; reconfirm details")
            VerificationStatus.UNVERIFIED ->
                SharingDecision(true, "Listing has not been verified; confirm details")
            VerificationStatus.CONFLICTING_INFORMATION ->
                SharingDecision(false, "Conflicting property information requires review")
        }
    }
}

