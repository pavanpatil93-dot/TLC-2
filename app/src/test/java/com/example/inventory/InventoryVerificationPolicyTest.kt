package com.example.inventory

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class InventoryVerificationPolicyTest {
    private val now = Instant.parse("2026-10-16T00:00:00Z")

    @Test fun verifiedAvailableCanBeSharedAndMatched() {
        val s = VerificationSnapshot(AvailabilityStatus.AVAILABLE, VerificationStatus.VERIFIED, now)
        assertTrue(InventoryVerificationPolicy.eligibleForActiveMatching(s.availability))
        assertTrue(InventoryVerificationPolicy.sharingDecision(s, now).allowed)
    }

    @Test fun thirtyDayExpiryWarnsButDoesNotBlockSharing() {
        val s = VerificationSnapshot(AvailabilityStatus.AVAILABLE, VerificationStatus.VERIFIED,
            now.minus(30, ChronoUnit.DAYS))
        assertEquals(VerificationStatus.VERIFICATION_DUE,
            InventoryVerificationPolicy.effectiveVerificationStatus(s, now))
        val decision = InventoryVerificationPolicy.sharingDecision(s, now)
        assertTrue(decision.allowed)
        assertNotNull(decision.warning)
    }

    @Test fun closedStatusesCannotBeMatchedOrShared() {
        for (status in listOf(AvailabilityStatus.SOLD, AvailabilityStatus.LEASED_OUT,
            AvailabilityStatus.WITHDRAWN, AvailabilityStatus.ARCHIVED)) {
            assertFalse(InventoryVerificationPolicy.eligibleForActiveMatching(status))
            assertFalse(InventoryVerificationPolicy.sharingDecision(
                VerificationSnapshot(status, VerificationStatus.VERIFIED, now), now).allowed)
        }
    }

    @Test fun unknownAndConflictingInformationCannotBeShared() {
        assertFalse(InventoryVerificationPolicy.sharingDecision(
            VerificationSnapshot(AvailabilityStatus.UNKNOWN, VerificationStatus.VERIFIED, now), now).allowed)
        assertFalse(InventoryVerificationPolicy.sharingDecision(
            VerificationSnapshot(AvailabilityStatus.AVAILABLE,
                VerificationStatus.CONFLICTING_INFORMATION, now), now).allowed)
    }
}

