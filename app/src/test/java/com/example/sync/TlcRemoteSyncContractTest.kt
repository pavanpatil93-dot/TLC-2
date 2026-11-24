package com.example.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlcRemoteSyncContractTest {
    @Test fun staleMutationIsRejected() {
        assertFalse(TlcSyncPolicy.canApply(3,4))
        assertTrue(TlcSyncPolicy.canApply(4,4))
        assertFalse(TlcSyncPolicy.canApply(null,4))
    }

    @Test fun unauthorizedMutationNeverRetriesAutomatically() {
        assertFalse(TlcSyncPolicy.shouldRetry(TlcMutationResult.Unauthorized))
        assertFalse(TlcSyncPolicy.shouldRetry(TlcMutationResult.Forbidden))
        assertFalse(TlcSyncPolicy.shouldRetry(TlcMutationResult.InvalidData))
        assertTrue(TlcSyncPolicy.shouldRetry(TlcMutationResult.OfflineRetry))
    }

    @Test fun externalRecordsNeverPublishWithoutAllThreeGates() {
        assertFalse(TlcSyncPolicy.mayAutoPublish(false,true,true))
        assertFalse(TlcSyncPolicy.mayAutoPublish(true,false,true))
        assertFalse(TlcSyncPolicy.mayAutoPublish(true,true,false))
        assertTrue(TlcSyncPolicy.mayAutoPublish(true,true,true))
    }

    @Test fun invalidPendingMutationIsRejected() {
        val missingVersion = TlcPendingMutation("op1","device1","unit1",null,TlcMutationOperation.UPDATE,mapOf("rent" to "20000"))
        assertFalse(missingVersion.isStructurallyValid())
        val valid = missingVersion.copy(baseVersion=1)
        assertTrue(valid.isStructurallyValid())
    }
}
