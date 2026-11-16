package com.example.model

import java.util.Locale

/**
 * Shared master registry contract for Ascend commercial and Aurea residential.
 * A project ID is stable even if a project's display name changes.
 * This module does not read or write inventory or Google Sheets.
 */
enum class ProjectPortfolio { COMMERCIAL, RESIDENTIAL, MIXED }

data class MasterProject(
    val projectId: String,
    val name: String,
    val location: String,
    val portfolio: ProjectPortfolio,
    val developer: String = "",
    val address: String = "",
    val aliases: List<String> = emptyList(),
    val mahaRera: String = "",
    val googleMapsUrl: String = "",
    val possession: String = "",
    val source: String = "",
    val reviewStatus: String = "Needs Review"
)

object MasterProjectRules {
    fun normalized(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    fun canCreate(project: MasterProject): Boolean =
        project.projectId.isNotBlank() &&
        project.name.isNotBlank() &&
        project.location.isNotBlank()

    fun matchingCandidates(
        projects: List<MasterProject>,
        name: String,
        location: String
    ): List<MasterProject> {
        val n = normalized(name)
        val l = normalized(location)
        if (n.isBlank() || l.isBlank()) return emptyList()
        return projects.filter { project ->
            normalized(project.location) == l &&
                (normalized(project.name) == n ||
                    project.aliases.any { normalized(it) == n })
        }
    }

    fun resolveById(projects: List<MasterProject>, projectId: String): MasterProject? =
        projects.firstOrNull { it.projectId == projectId && projectId.isNotBlank() }
}

