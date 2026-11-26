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

    @Query("SELECT * FROM unit_inventory_master")
    suspend fun getAllUnitsSync(): List<PropertyUnitEntity>

    @Query("DELETE FROM unit_inventory_master WHERE unitId = :unitId")
    suspend fun deleteUnitById(unitId: String)

    @Query("DELETE FROM unit_inventory_master WHERE unitId IN (:unitIds)")
    suspend fun deleteUnitsByIds(unitIds: List<String>)

    @Query("DELETE FROM unit_inventory_master")
    suspend fun deleteAllUnits()

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

    @Query("SELECT * FROM approval_requests")
    suspend fun getAllApprovalRequestsSync(): List<ApprovalRequestEntity>

    @Query("SELECT * FROM approval_requests WHERE requestId = :requestId LIMIT 1")
    suspend fun getApprovalRequestById(requestId: String): ApprovalRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApprovalRequest(request: ApprovalRequestEntity)

    @Update
    suspend fun updateApprovalRequest(request: ApprovalRequestEntity)

    @Query("DELETE FROM approval_requests WHERE requestId IN (:requestIds)")
    suspend fun deleteApprovalRequestsByIds(requestIds: List<String>)

    @Query("DELETE FROM approval_requests")
    suspend fun deleteAllApprovalRequests()

    @Query("SELECT COUNT(*) FROM approval_requests WHERE status = 'PENDING'")
    fun getPendingApprovalCount(): Flow<Int>

    // --- Audit Events ---
    @Query("SELECT * FROM audit_events ORDER BY eventTimestamp DESC LIMIT :limit")
    fun observeRecentAuditEvents(limit: Int = 100): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_events")
    suspend fun getAllAuditEventsSync(): List<AuditEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditEvent(event: AuditEventEntity)

    @Query("DELETE FROM audit_events WHERE eventId IN (:eventIds)")
    suspend fun deleteAuditEventsByIds(eventIds: List<String>)

    @Query("DELETE FROM audit_events")
    suspend fun deleteAllAuditEvents()

    // --- Call Logs (Two-Way Multi-Device Sync) ---
    @Query("SELECT * FROM call_logs_master ORDER BY timestamp DESC")
    fun observeAllCallLogs(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_logs_master ORDER BY timestamp DESC")
    suspend fun getAllCallLogsSync(): List<CallLogEntity>

    @Query("SELECT * FROM call_logs_master WHERE syncStatus = 'PENDING' ORDER BY updatedAt ASC")
    suspend fun getPendingCallLogsSync(): List<CallLogEntity>

    @Query("SELECT COUNT(*) FROM call_logs_master WHERE syncStatus = 'PENDING'")
    suspend fun countPendingCallLogsSync(): Int

    @Query("SELECT * FROM call_logs_master WHERE callId = :callId LIMIT 1")
    suspend fun getCallLogById(callId: String): CallLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCallLog(call: CallLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCallLogs(calls: List<CallLogEntity>)

    @Query("UPDATE call_logs_master SET syncStatus = 'SYNCED', updatedAt = :updatedAt WHERE callId = :callId")
    suspend fun markCallLogSynced(callId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM call_logs_master WHERE callId IN (:callIds)")
    suspend fun deleteCallLogsByIds(callIds: List<String>)

    @Query("DELETE FROM call_logs_master WHERE syncStatus != 'PENDING'")
    suspend fun deleteAllSyncedCallLogs()

    // --- Market Pulse Feed ---
    @Query("SELECT * FROM market_pulse_feed ORDER BY publishedAt DESC")
    fun observeMarketPulse(): Flow<List<MarketPulseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketPulse(item: MarketPulseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMarketPulse(items: List<MarketPulseEntity>)

    // --- Brokers Master (Commercial Brokers & Residential Brokers) ---
    @Query("SELECT * FROM brokers_master ORDER BY name ASC")
    fun observeAllBrokers(): Flow<List<BrokerEntity>>

    @Query("SELECT * FROM brokers_master WHERE division = :division ORDER BY name ASC")
    fun observeBrokersByDivision(division: String): Flow<List<BrokerEntity>>

    @Query("SELECT * FROM brokers_master ORDER BY name ASC")
    suspend fun getAllBrokersSync(): List<BrokerEntity>

    @Query("SELECT COUNT(*) FROM brokers_master")
    suspend fun countBrokersSync(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBrokers(brokers: List<BrokerEntity>)

    @Query("DELETE FROM brokers_master WHERE sourceTab = :sourceTab")
    suspend fun deleteBrokersBySourceTab(sourceTab: String)

    @Query("DELETE FROM brokers_master WHERE sourceTab = :sourceTab AND brokerId NOT IN (:keepIds)")
    suspend fun deleteBrokersNotInIdsForTab(sourceTab: String, keepIds: List<String>)

    // --- Batch 44: AI Call Recording Analysis & Review Queue ---
    @Query("SELECT * FROM call_analysis_master ORDER BY callTimestamp DESC, aiAnalyzedAt DESC")
    fun observeAllCallAnalyses(): Flow<List<CallAnalysisEntity>>

    @Query("SELECT * FROM call_analysis_master ORDER BY callTimestamp DESC, aiAnalyzedAt DESC")
    suspend fun getAllCallAnalysesSync(): List<CallAnalysisEntity>

    @Query("SELECT * FROM call_analysis_master WHERE callLogId = :callLogId LIMIT 1")
    suspend fun getCallAnalysisByCallId(callLogId: String): CallAnalysisEntity?

    @Query("SELECT callLogId FROM call_analysis_master WHERE aiAnalyzedAt > 0")
    suspend fun getAnalyzedCallIdsSync(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCallAnalysis(entity: CallAnalysisEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCallAnalyses(entities: List<CallAnalysisEntity>)

    @Query("UPDATE call_analysis_master SET reviewStatus = :status WHERE callLogId = :callLogId")
    suspend fun updateCallAnalysisReviewStatus(callLogId: String, status: String)

    @Query("UPDATE call_analysis_master SET syncedToSheet = 1 WHERE callLogId = :callLogId")
    suspend fun markCallAnalysisSynced(callLogId: String)
}
