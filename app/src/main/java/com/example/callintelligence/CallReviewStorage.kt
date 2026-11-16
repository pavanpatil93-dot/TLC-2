package com.example.callintelligence

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Staged, opt-in call review storage. Not registered in the existing Room database yet.
 * No phone number, transcript, or recording is collected automatically.
 */
@Entity(tableName = "tlc_call_reviews", indices = [
    Index("createdAtEpochMs"), Index("followUpAtEpochMs"), Index("contactReference")
])
data class CallReviewEntity(
    @PrimaryKey val reviewId: String,
    val createdAtEpochMs: Long,
    val callEndedAtEpochMs: Long?,
    val direction: String = "UNKNOWN",
    val callerType: String = "UNKNOWN",
    val division: String = "PERSONAL",
    val contactReference: String? = null,
    val projectId: String? = null,
    val propertyCode: String? = null,
    val leadReference: String? = null,
    val summary: String = "",
    val actionNotes: String = "",
    val followUpAtEpochMs: Long? = null,
    val recordingUri: String? = null,
    val transcriptionStatus: String = "NOT_REQUESTED",
    val whatsappDraft: String? = null,
    val whatsappApprovalStatus: String = "NOT_REQUESTED",
    val reviewStatus: String = "PENDING",
    @androidx.room.ColumnInfo(defaultValue = "''") val updatedBy: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''") val updatedAt: String = ""
)

@Dao
interface CallReviewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(review: CallReviewEntity)

    @Query("SELECT * FROM tlc_call_reviews ORDER BY createdAtEpochMs DESC")
    fun observeReviews(): Flow<List<CallReviewEntity>>

    @Query("SELECT * FROM tlc_call_reviews WHERE reviewId = :reviewId LIMIT 1")
    suspend fun findById(reviewId: String): CallReviewEntity?

    @Query("SELECT * FROM tlc_call_reviews WHERE followUpAtEpochMs IS NOT NULL AND reviewStatus != 'CLOSED' ORDER BY followUpAtEpochMs ASC")
    fun observePendingFollowUps(): Flow<List<CallReviewEntity>>
}

