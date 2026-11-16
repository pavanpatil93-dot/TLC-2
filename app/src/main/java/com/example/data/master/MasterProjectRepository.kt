package com.example.data.master

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MasterProjectRepository(context: Context) {
    private val dao = MasterProjectDatabase.getDatabase(context).masterProjectDao()

    val allProjectsWithUnits: Flow<List<ProjectWithUnits>> = dao.observeAllProjectsWithUnits()
    val commercialProjectsWithUnits: Flow<List<ProjectWithUnits>> = dao.observeProjectsByTypeWithUnits(ProjectType.COMMERCIAL)
    val residentialProjectsWithUnits: Flow<List<ProjectWithUnits>> = dao.observeProjectsByTypeWithUnits(ProjectType.RESIDENTIAL)
    val allUnits: Flow<List<PropertyUnitEntity>> = dao.observeAllUnits()

    fun observeUnitsForProject(projectId: String): Flow<List<PropertyUnitEntity>> {
        return dao.observeUnitsForProject(projectId)
    }

    suspend fun getProjectById(projectId: String): ProjectEntity? = withContext(Dispatchers.IO) {
        dao.getProjectById(projectId)
    }

    suspend fun registerProject(
        projectName: String,
        projectType: ProjectType,
        developerName: String,
        city: String = "Pune",
        location: String = "East Pune",
        microMarket: String,
        category: String,
        reraNumber: String? = null,
        possessionDate: String? = null,
        status: ProjectStatus = ProjectStatus.READY,
        verificationStatus: VerificationStatus = VerificationStatus.VERIFIED
    ): ProjectEntity = withContext(Dispatchers.IO) {
        val existing = dao.getAllProjectsSync()
        val prefix = if (projectType == ProjectType.COMMERCIAL) "CP-" else "RP-"
        val count = existing.count { it.projectType == projectType } + 1
        val projectId = String.format("%s%04d", prefix, count)

        val newProject = ProjectEntity(
            projectId = projectId,
            projectName = projectName.trim(),
            projectType = projectType,
            developerName = developerName.trim(),
            city = city,
            location = location,
            microMarket = microMarket.trim(),
            category = category.trim(),
            reraNumber = reraNumber?.trim(),
            possessionDate = possessionDate?.trim(),
            status = status,
            verificationStatus = verificationStatus,
            createdAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis()
        )
        dao.insertProject(newProject)
        newProject
    }

    suspend fun addUnitToProject(
        projectId: String,
        propertyCode: String,
        towerBuilding: String?,
        unitNumber: String,
        floor: Int,
        areaSqFt: Double,
        pricing: Double,
        rentAmount: Double?,
        tab: String,
        furnishing: String,
        status: String,
        ownerName: String?,
        ownerContact: String?
    ): PropertyUnitEntity = withContext(Dispatchers.IO) {
        val unitId = "UNIT-${System.currentTimeMillis()}-${(1000..9999).random()}"
        val unit = PropertyUnitEntity(
            unitId = unitId,
            projectId = projectId,
            propertyCode = propertyCode,
            towerBuilding = towerBuilding,
            unitNumber = unitNumber,
            floor = floor,
            areaSqFt = areaSqFt,
            pricing = pricing,
            rentAmount = rentAmount,
            tab = tab,
            furnishing = furnishing,
            status = status,
            ownerName = ownerName,
            ownerContact = ownerContact,
            createdAt = System.currentTimeMillis()
        )
        dao.insertUnit(unit)
        unit
    }

    suspend fun findSimilarProject(name: String): ProjectEntity? = withContext(Dispatchers.IO) {
        val candidates = detectDuplicates(name)
        candidates.firstOrNull()?.project
    }

    suspend fun detectDuplicates(name: String, microMarket: String? = null): List<DuplicateCandidate> = withContext(Dispatchers.IO) {
        val cleanName = normalizeProjectName(name)
        if (cleanName.isBlank()) return@withContext emptyList()

        val allProjects = dao.getAllProjectsSync()
        val results = mutableListOf<DuplicateCandidate>()

        for (project in allProjects) {
            val projName = normalizeProjectName(project.projectName)
            val nameSim = similarityRatio(cleanName, projName)

            // Check aliases
            val aliases = dao.getAliasesForProject(project.projectId)
            val aliasMaxSim = aliases.maxOfOrNull { similarityRatio(cleanName, normalizeProjectName(it.aliasName)) } ?: 0.0

            val bestSim = maxOf(nameSim, aliasMaxSim)
            val marketMatch = microMarket == null || project.microMarket.equals(microMarket.trim(), ignoreCase = true)

            // 0.85 similarity threshold or exact micro-market high similarity
            if (bestSim >= 0.85 || (bestSim >= 0.75 && marketMatch)) {
                results.add(DuplicateCandidate(project = project, similarity = bestSim))
            }
        }
        results.sortedByDescending { it.similarity }
    }

    suspend fun addProjectAlias(projectId: String, aliasName: String): ProjectAliasEntity = withContext(Dispatchers.IO) {
        val alias = ProjectAliasEntity(
            aliasId = "ALIAS-${System.currentTimeMillis()}-${(100..999).random()}",
            projectId = projectId,
            aliasName = aliasName.trim()
        )
        dao.insertAlias(alias)
        alias
    }

    fun observeAliases(projectId: String): Flow<List<ProjectAliasEntity>> = dao.observeAliasesForProject(projectId)
    fun observePhases(projectId: String): Flow<List<ProjectPhaseEntity>> = dao.observePhasesForProject(projectId)
    fun observeTowers(projectId: String): Flow<List<ProjectTowerEntity>> = dao.observeTowersForProject(projectId)
    fun observeConfigs(projectId: String): Flow<List<ProjectConfigEntity>> = dao.observeConfigsForProject(projectId)

    suspend fun addPhase(
        projectId: String,
        phaseName: String,
        reraNumber: String? = null,
        completionDate: String? = null,
        status: String = "UNDER_CONSTRUCTION"
    ): ProjectPhaseEntity = withContext(Dispatchers.IO) {
        val phase = ProjectPhaseEntity(
            phaseId = "PHASE-${System.currentTimeMillis()}-${(100..999).random()}",
            projectId = projectId,
            phaseName = phaseName.trim(),
            reraNumber = reraNumber?.trim(),
            completionDate = completionDate?.trim(),
            status = status
        )
        dao.insertPhase(phase)
        phase
    }

    suspend fun addTower(
        projectId: String,
        phaseId: String? = null,
        towerName: String,
        totalFloors: Int = 1,
        status: String = "READY"
    ): ProjectTowerEntity = withContext(Dispatchers.IO) {
        val tower = ProjectTowerEntity(
            towerId = "TOWER-${System.currentTimeMillis()}-${(100..999).random()}",
            projectId = projectId,
            phaseId = phaseId,
            towerName = towerName.trim(),
            totalFloors = totalFloors,
            status = status
        )
        dao.insertTower(tower)
        tower
    }

    suspend fun addConfig(
        projectId: String,
        configType: String,
        startingAreaSqFt: Double,
        startingPrice: Double,
        observedDate: String = ""
    ): ProjectConfigEntity = withContext(Dispatchers.IO) {
        val config = ProjectConfigEntity(
            configId = "CFG-${System.currentTimeMillis()}-${(100..999).random()}",
            projectId = projectId,
            configType = configType.trim(),
            startingAreaSqFt = startingAreaSqFt,
            startingPrice = startingPrice,
            observedDate = observedDate.trim()
        )
        dao.insertConfig(config)
        config
    }

    suspend fun archiveProject(projectId: String) = withContext(Dispatchers.IO) {
        val proj = dao.getProjectById(projectId) ?: return@withContext
        dao.updateProject(proj.copy(isArchived = true, lastUpdatedAt = System.currentTimeMillis()))
    }

    // Property Bookmarks
    fun observeBookmarks(employeeId: String): Flow<List<PropertyBookmarkEntity>> =
        dao.observeBookmarksForEmployee(employeeId)

    fun isPropertyBookmarked(employeeId: String, propertyId: String): Flow<Boolean> =
        dao.isPropertyBookmarked(employeeId, propertyId)

    suspend fun toggleBookmark(employeeId: String, propertyId: String, note: String = "") = withContext(Dispatchers.IO) {
        val bookmarkId = "$employeeId:$propertyId"
        val existing = dao.isPropertyBookmarked(employeeId, propertyId)
        // Insert or delete
        val bookmark = PropertyBookmarkEntity(
            bookmarkId = bookmarkId,
            employeeId = employeeId,
            propertyId = propertyId,
            note = note
        )
        dao.insertBookmark(bookmark)
    }

    suspend fun removeBookmark(employeeId: String, propertyId: String) = withContext(Dispatchers.IO) {
        dao.deleteBookmark(employeeId, propertyId)
    }

    fun calculateRollup(units: List<PropertyUnitEntity>): ProjectInventoryRollup {
        val total = units.size
        val available = units.count { it.status.equals("Available", ignoreCase = true) }
        val leased = units.count { it.status.equals("Leased", ignoreCase = true) }
        val sold = units.count { it.status.equals("Sold", ignoreCase = true) }
        val prices = units.map { it.pricing }.filter { it > 0.0 }
        val minP = prices.minOrNull() ?: 0.0
        val maxP = prices.maxOrNull() ?: 0.0
        val avgRate = if (units.isNotEmpty()) {
            units.filter { it.areaSqFt > 0.0 && it.pricing > 0.0 }.map { it.pricing / it.areaSqFt }.average()
        } else 0.0

        val tabs = units.groupBy { it.tab }.mapValues { it.value.size }

        return ProjectInventoryRollup(
            totalUnits = total,
            availableUnits = available,
            leasedUnits = leased,
            soldUnits = sold,
            minPrice = minP,
            maxPrice = maxP,
            avgRatePerSqFt = if (avgRate.isNaN()) 0.0 else avgRate,
            tabsCount = tabs
        )
    }

    fun generateSuggestedPropertyCode(tab: String, microMarket: String, unitNumber: String): String {
        val tabCode = when (tab.trim().lowercase()) {
            "off lease", "office lease" -> "OL"
            "off out", "office sale", "office outright" -> "OO"
            "shop lease" -> "SL"
            "shop out", "shop sale" -> "SO"
            "pre - leased", "pre-leased" -> "PL"
            "resi rent", "residential rent" -> "RR"
            "resi resale", "residential resale" -> "RS"
            "und const", "under construction" -> "UC"
            else -> tab.take(2).uppercase()
        }

        val marketCode = when {
            microMarket.contains("kharadi", true) -> "KHD"
            microMarket.contains("viman", true) -> "VMN"
            microMarket.contains("wakad", true) -> "WKD"
            microMarket.contains("baner", true) -> "BNR"
            microMarket.contains("hinjewadi", true) -> "HJW"
            microMarket.contains("kalyani", true) -> "KLN"
            microMarket.contains("magarpatta", true) -> "MPT"
            microMarket.contains("hadapsar", true) -> "HDP"
            microMarket.contains("balewadi", true) -> "BLW"
            microMarket.contains("koregaon", true) -> "KP"
            microMarket.isNotBlank() -> microMarket.filter { it.isLetter() }.take(3).uppercase()
            else -> "PUN"
        }

        val cleanUnit = unitNumber.filter { it.isLetterOrDigit() }.ifBlank { "101" }
        return "$tabCode-$marketCode-$cleanUnit"
    }

    companion object {
        fun normalizeProjectName(name: String): String =
            name.trim().lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ")

        fun similarityRatio(s1: String, s2: String): Double {
            if (s1 == s2) return 1.0
            val maxLen = maxOf(s1.length, s2.length)
            if (maxLen == 0) return 1.0
            val dist = levenshteinDistance(s1, s2)
            return (1.0 - (dist.toDouble() / maxLen)).coerceIn(0.0, 1.0)
        }

        fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
            val lhsLength = lhs.length
            val rhsLength = rhs.length
            var cost = IntArray(lhsLength + 1) { it }
            var newCost = IntArray(lhsLength + 1) { 0 }
            for (i in 1..rhsLength) {
                newCost[0] = i
                for (j in 1..lhsLength) {
                    val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                    newCost[j] = minOf(
                        cost[j] + 1,
                        newCost[j - 1] + 1,
                        cost[j - 1] + match
                    )
                }
                val swap = cost
                cost = newCost
                newCost = swap
            }
            return cost[lhsLength]
        }
    }
}

data class DuplicateCandidate(
    val project: ProjectEntity,
    val similarity: Double
)

enum class DuplicateResolutionAction {
    USE_EXISTING,
    CREATE_SEPARATE,
    CANCEL
}

data class ProjectInventoryRollup(
    val totalUnits: Int,
    val availableUnits: Int,
    val leasedUnits: Int,
    val soldUnits: Int,
    val minPrice: Double,
    val maxPrice: Double,
    val avgRatePerSqFt: Double,
    val tabsCount: Map<String, Int>
)

