package com.example.callintelligence

import org.junit.Assert.*
import org.junit.Test

class GeoMatchEngineTest {
    private val kharadi = GeoPoint(18.551, 73.942)

    @Test fun exactMatchScores100() {
        val candidate = MatchCandidate("P1", kharadi, 100000.0, 1000.0, "Furnished", "Kharadi")
        val result = GeoMatchEngine.match(MatchRequirement(kharadi, 100000.0, 1000.0, "Furnished", "Kharadi"), listOf(candidate))
        assertEquals(1, result.size)
        assertEquals(100, result.first().score)
    }

    @Test fun excludesOutsideTwoKm() {
        val distant = MatchCandidate("P2", GeoPoint(18.60, 73.99), 100000.0, 1000.0, "Furnished", "Kharadi")
        assertTrue(GeoMatchEngine.match(MatchRequirement(kharadi, 100000.0, 1000.0, "Furnished", "Kharadi"), listOf(distant)).isEmpty())
    }

    @Test fun missingFieldsCannotReceivePerfectScore() {
        val candidate = MatchCandidate("P3", kharadi, null, null, null, null)
        val result = GeoMatchEngine.match(MatchRequirement(kharadi, 100000.0, 1000.0, "Furnished", "Kharadi"), listOf(candidate))
        assertEquals(30, result.single().score)
    }

    @Test fun invalidCoordinatesAreExcluded() {
        val candidate = MatchCandidate("P4", GeoPoint(999.0, 73.9), null, null, null, null)
        assertTrue(GeoMatchEngine.match(MatchRequirement(kharadi, null, null, null, null), listOf(candidate)).isEmpty())
    }
}

