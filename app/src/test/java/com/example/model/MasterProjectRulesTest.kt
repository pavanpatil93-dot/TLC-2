package com.example.model

import org.junit.Assert.*
import org.junit.Test

class MasterProjectRulesTest {
    private val alpha = MasterProject(
        projectId = "PRJ-001",
        name = "Gera Imperium Alpha",
        location = "Kharadi",
        portfolio = ProjectPortfolio.COMMERCIAL,
        aliases = listOf("GIA")
    )

    @Test fun matchesNameOrAliasOnlyWithinSameLocation() {
        assertEquals(listOf(alpha), MasterProjectRules.matchingCandidates(listOf(alpha), " GIA ", "KHARADI"))
        assertEquals(listOf(alpha), MasterProjectRules.matchingCandidates(listOf(alpha), "gera  imperium alpha", "kharadi"))
        assertTrue(MasterProjectRules.matchingCandidates(listOf(alpha), "GIA", "Baner").isEmpty())
    }

    @Test fun requiresStableIdentityNameAndLocation() {
        assertTrue(MasterProjectRules.canCreate(alpha))
        assertFalse(MasterProjectRules.canCreate(alpha.copy(projectId = "")))
        assertFalse(MasterProjectRules.canCreate(alpha.copy(name = "")))
    }

    @Test fun identityDoesNotDependOnDisplayName() {
        assertEquals("PRJ-001", MasterProjectRules.resolveById(listOf(alpha.copy(name = "Renamed Project")), "PRJ-001")?.projectId)
    }
}

