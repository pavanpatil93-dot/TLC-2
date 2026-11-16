package com.example.data.master

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MasterProjectDao {

    @Transaction
    @Query("SELECT * FROM projects_master WHERE isArchived = 0 ORDER BY projectName ASC")
    fun observeAllProjectsWithUnits(): Flow<List<ProjectWithUnits>>

    @Transaction
    @Query("SELECT * FROM projects_master WHERE projectType = :type AND isArchived = 0 ORDER BY projectName ASC")
    fun observeProjectsByTypeWithUnits(type: ProjectType): Flow<List<ProjectWithUnits>>

    @Query("SELECT * FROM projects_master WHERE projectId = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: String): ProjectEntity?

    @Transaction
    @Query("SELECT * FROM projects_master WHERE projectId = :projectId LIMIT 1")
    fun observeProjectWithUnits(projectId: String): Flow<ProjectWithUnits?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("SELECT * FROM unit_inventory_master WHERE projectId = :projectId")
    fun observeUnitsForProject(projectId: String): Flow<List<PropertyUnitEntity>>

    @Query("SELECT * FROM unit_inventory_master")
    fun observeAllUnits(): Flow<List<PropertyUnitEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnit(unit: PropertyUnitEntity)

    @Update
    suspend fun updateUnit(unit: PropertyUnitEntity)

    @Delete
    suspend fun deleteUnit(unit: PropertyUnitEntity)

    @Query("SELECT COUNT(*) FROM projects_master WHERE projectType = :type AND isArchived = 0")
    suspend fun getProjectCountByType(type: ProjectType): Int

    @Query("SELECT * FROM projects_master WHERE isArchived = 0")
    suspend fun getAllProjectsSync(): List<ProjectEntity>

    // --- Project Aliases ---
    @Query("SELECT * FROM project_aliases WHERE projectId = :projectId")
    fun observeAliasesForProject(projectId: String): Flow<List<ProjectAliasEntity>>

    @Query("SELECT * FROM project_aliases WHERE projectId = :projectId")
    suspend fun getAliasesForProject(projectId: String): List<ProjectAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(alias: ProjectAliasEntity)

    @Delete
    suspend fun deleteAlias(alias: ProjectAliasEntity)

    // --- Project Phases ---
    @Query("SELECT * FROM project_phases WHERE projectId = :projectId ORDER BY phaseName ASC")
    fun observePhasesForProject(projectId: String): Flow<List<ProjectPhaseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhase(phase: ProjectPhaseEntity)

    // --- Project Towers ---
    @Query("SELECT * FROM project_towers WHERE projectId = :projectId ORDER BY towerName ASC")
    fun observeTowersForProject(projectId: String): Flow<List<ProjectTowerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTower(tower: ProjectTowerEntity)

    // --- Project Configurations ---
    @Query("SELECT * FROM project_configs WHERE projectId = :projectId ORDER BY startingPrice ASC")
    fun observeConfigsForProject(projectId: String): Flow<List<ProjectConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: ProjectConfigEntity)

    // --- Property Bookmarks ---
    @Query("SELECT * FROM property_bookmarks WHERE employeeId = :employeeId ORDER BY createdAt DESC")
    fun observeBookmarksForEmployee(employeeId: String): Flow<List<PropertyBookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM property_bookmarks WHERE employeeId = :employeeId AND propertyId = :propertyId)")
    fun isPropertyBookmarked(employeeId: String, propertyId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: PropertyBookmarkEntity)

    @Query("DELETE FROM property_bookmarks WHERE employeeId = :employeeId AND propertyId = :propertyId")
    suspend fun deleteBookmark(employeeId: String, propertyId: String)

    // --- Duplicate Reviews ---
    @Query("SELECT * FROM duplicate_reviews ORDER BY createdAt DESC")
    fun observeDuplicateReviews(): Flow<List<DuplicateReviewEntity>>

    @Query("SELECT * FROM duplicate_reviews WHERE decision = 'UNREVIEWED' ORDER BY score DESC")
    fun observeUnreviewedDuplicates(): Flow<List<DuplicateReviewEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDuplicateReview(review: DuplicateReviewEntity)

    @Update
    suspend fun updateDuplicateReview(review: DuplicateReviewEntity)

    // --- Approval Requests ---
    @Query("SELECT * FROM approval_requests WHERE status = 'PENDING' ORDER BY requestedAt ASC")
    fun observePendingApprovalRequests(): Flow<List<ApprovalRequestEntity>>

    @Query("SELECT * FROM approval_requests ORDER BY requestedAt DESC")
    fun observeAllApprovalRequests(): Flow<List<ApprovalRequestEntity>>

    @Query("SELECT * FROM approval_requests WHERE requestId = :requestId LIMIT 1")
    suspend fun getApprovalRequestById(requestId: String): ApprovalRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApprovalRequest(request: ApprovalRequestEntity)

    @Update
    suspend fun updateApprovalRequest(request: ApprovalRequestEntity)

    @Query("SELECT COUNT(*) FROM approval_requests WHERE status = 'PENDING'")
    fun getPendingApprovalCount(): Flow<Int>

    // --- Audit Events ---
    @Query("SELECT * FROM audit_events ORDER BY eventTimestamp DESC LIMIT :limit")
    fun observeRecentAuditEvents(limit: Int = 100): Flow<List<AuditEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditEvent(event: AuditEventEntity)

    // --- Market Pulse Feed ---
    @Query("SELECT * FROM market_pulse_feed ORDER BY publishedAt DESC")
    fun observeMarketPulse(): Flow<List<MarketPulseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketPulse(item: MarketPulseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMarketPulse(items: List<MarketPulseEntity>)
}

