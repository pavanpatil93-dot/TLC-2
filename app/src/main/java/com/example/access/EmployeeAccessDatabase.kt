package com.example.access

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "tlc_employees",
    indices = [
        Index(value = ["loginMobile"], unique = true)
    ]
)
data class EmployeeEntity(
    @PrimaryKey
    val employeeId: String,
    val employeeName: String,
    val loginMobile: String,
    val loginEmail: String?,
    val role: EmployeeAccessRole,
    val businessScopes: List<BusinessDivisionScope>,
    val reportsToEmployeeId: String?,
    val jobTitle: String?,
    val isActive: Boolean,
    val accessStatus: EmployeeAccessStatus,
    val identityStatus: EmployeeIdentityStatus,
    val authorizationSource: AuthorizationSource,
    val activeBusinessView: ActiveBusinessView,
    val pinEnabled: Boolean,
    val pinCredentialRef: String?,
    val pinFailedAttempts: Int,
    val pinLockedUntil: Long?,
    val approvedDeskContacts: List<String>,
    val recordVersion: Int
) {
    fun toDomain(): EmployeeProfile = EmployeeProfile(
        employeeId = employeeId,
        employeeName = employeeName,
        loginMobile = loginMobile,
        loginEmail = loginEmail,
        role = role,
        businessScopes = businessScopes,
        reportsToEmployeeId = reportsToEmployeeId,
        jobTitle = jobTitle,
        isActive = isActive,
        accessStatus = accessStatus,
        identityStatus = identityStatus,
        authorizationSource = authorizationSource,
        activeBusinessView = activeBusinessView,
        pinEnabled = pinEnabled,
        pinCredentialRef = pinCredentialRef,
        pinFailedAttempts = pinFailedAttempts,
        pinLockedUntil = pinLockedUntil,
        approvedDeskContacts = approvedDeskContacts.ifEmpty { APPROVED_DESK_CONTACTS },
        recordVersion = recordVersion
    )

    companion object {
        fun fromDomain(profile: EmployeeProfile): EmployeeEntity = EmployeeEntity(
            employeeId = profile.employeeId,
            employeeName = profile.employeeName,
            loginMobile = profile.loginMobile,
            loginEmail = profile.loginEmail,
            role = profile.role,
            businessScopes = profile.businessScopes,
            reportsToEmployeeId = profile.reportsToEmployeeId,
            jobTitle = profile.jobTitle,
            isActive = profile.isActive,
            accessStatus = profile.accessStatus,
            identityStatus = profile.identityStatus,
            authorizationSource = profile.authorizationSource,
            activeBusinessView = profile.activeBusinessView,
            pinEnabled = profile.pinEnabled,
            pinCredentialRef = profile.pinCredentialRef,
            pinFailedAttempts = profile.pinFailedAttempts,
            pinLockedUntil = profile.pinLockedUntil,
            approvedDeskContacts = profile.approvedDeskContacts.ifEmpty { APPROVED_DESK_CONTACTS },
            recordVersion = profile.recordVersion
        )
    }
}

@Entity(
    tableName = "tlc_access_audit_events",
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["actorEmployeeId"])
    ]
)
data class AccessAuditEventEntity(
    @PrimaryKey
    val eventId: String,
    val actorEmployeeId: String,
    val actorRole: EmployeeAccessRole,
    val action: String,
    val entityId: String,
    val businessDivision: String,
    val occurredAt: String,
    val result: AuditResult
) {
    fun toDomain(): AuditEventContract = AuditEventContract(
        eventId = eventId,
        actorEmployeeId = actorEmployeeId,
        actorRole = actorRole,
        action = action,
        entityId = entityId,
        businessDivision = businessDivision,
        occurredAt = occurredAt,
        result = result
    )

    companion object {
        fun fromDomain(event: AuditEventContract): AccessAuditEventEntity = AccessAuditEventEntity(
            eventId = event.eventId,
            actorEmployeeId = event.actorEmployeeId,
            actorRole = event.actorRole,
            action = event.action,
            entityId = event.entityId,
            businessDivision = event.businessDivision,
            occurredAt = event.occurredAt,
            result = event.result
        )
    }
}

@Entity(tableName = "tlc_employee_session")
data class EmployeeSessionEntity(
    @PrimaryKey
    val sessionSlot: String = "PRIMARY",
    val activeEmployeeId: String?,
    val activeBusinessView: ActiveBusinessView,
    val isPinLocked: Boolean,
    val inventoryViewMode: String = "LIST",
    val signedInAtEpochMs: Long = 0L
)

class EmployeeAccessTypeConverters {
    @TypeConverter
    fun fromRole(role: EmployeeAccessRole): String = role.name

    @TypeConverter
    fun toRole(raw: String): EmployeeAccessRole =
        EmployeeAccessRole.entries.firstOrNull { it.name == raw } ?: EmployeeAccessRole.READ_ONLY

    @TypeConverter
    fun fromAccessStatus(status: EmployeeAccessStatus): String = status.name

    @TypeConverter
    fun toAccessStatus(raw: String): EmployeeAccessStatus =
        EmployeeAccessStatus.entries.firstOrNull { it.name == raw } ?: EmployeeAccessStatus.PENDING

    @TypeConverter
    fun fromIdentityStatus(status: EmployeeIdentityStatus): String = status.name

    @TypeConverter
    fun toIdentityStatus(raw: String): EmployeeIdentityStatus =
        EmployeeIdentityStatus.entries.firstOrNull { it.name == raw } ?: EmployeeIdentityStatus.LOCAL_PROFILE

    @TypeConverter
    fun fromAuthSource(source: AuthorizationSource): String = source.name

    @TypeConverter
    fun toAuthSource(raw: String): AuthorizationSource =
        AuthorizationSource.entries.firstOrNull { it.name == raw } ?: AuthorizationSource.NONE

    @TypeConverter
    fun fromActiveBusinessView(view: ActiveBusinessView): String = view.name

    @TypeConverter
    fun toActiveBusinessView(raw: String): ActiveBusinessView =
        ActiveBusinessView.entries.firstOrNull { it.name == raw } ?: ActiveBusinessView.ASCEND_COMMERCIAL

    @TypeConverter
    fun fromAuditResult(result: AuditResult): String = result.name

    @TypeConverter
    fun toAuditResult(raw: String): AuditResult =
        AuditResult.entries.firstOrNull { it.name == raw } ?: AuditResult.FAILED

    @TypeConverter
    fun fromScopes(scopes: List<BusinessDivisionScope>): String =
        scopes.joinToString(",") { it.name }

    @TypeConverter
    fun toScopes(raw: String): List<BusinessDivisionScope> =
        if (raw.isBlank()) {
            emptyList()
        } else {
            raw.split(",").mapNotNull { token ->
                BusinessDivisionScope.entries.firstOrNull { it.name == token.trim() }
            }
        }

    @TypeConverter
    fun fromStringList(values: List<String>): String = values.joinToString("|")

    @TypeConverter
    fun toStringList(raw: String): List<String> =
        if (raw.isBlank()) emptyList() else raw.split("|").filter { it.isNotBlank() }
}

@Dao
interface EmployeeDao {
    @Query("SELECT * FROM tlc_employees ORDER BY employeeName ASC")
    fun observeAll(): Flow<List<EmployeeEntity>>

    @Query("SELECT * FROM tlc_employees ORDER BY employeeName ASC")
    suspend fun getAll(): List<EmployeeEntity>

    @Query("SELECT * FROM tlc_employees WHERE employeeId = :employeeId LIMIT 1")
    suspend fun findById(employeeId: String): EmployeeEntity?

    @Query("SELECT * FROM tlc_employees WHERE loginMobile = :normalizedMobile LIMIT 1")
    suspend fun findByNormalizedMobile(normalizedMobile: String): EmployeeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: EmployeeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EmployeeEntity)

    @Query(
        """
        UPDATE tlc_employees
        SET employeeName = :employeeName,
            loginEmail = :loginEmail,
            role = :role,
            businessScopes = :businessScopes,
            reportsToEmployeeId = :reportsToEmployeeId,
            jobTitle = :jobTitle,
            isActive = :isActive,
            accessStatus = :accessStatus,
            identityStatus = :identityStatus,
            authorizationSource = :authorizationSource,
            activeBusinessView = :activeBusinessView,
            recordVersion = :nextVersion
        WHERE employeeId = :employeeId AND recordVersion = :expectedVersion
        """
    )
    suspend fun updateWithOptimisticLock(
        employeeId: String,
        expectedVersion: Int,
        nextVersion: Int,
        employeeName: String,
        loginEmail: String?,
        role: EmployeeAccessRole,
        businessScopes: List<BusinessDivisionScope>,
        reportsToEmployeeId: String?,
        jobTitle: String?,
        isActive: Boolean,
        accessStatus: EmployeeAccessStatus,
        identityStatus: EmployeeIdentityStatus,
        authorizationSource: AuthorizationSource,
        activeBusinessView: ActiveBusinessView
    ): Int

    @Query("UPDATE tlc_employees SET activeBusinessView = :view, recordVersion = recordVersion + 1 WHERE employeeId = :employeeId")
    suspend fun updateActiveBusinessView(employeeId: String, view: ActiveBusinessView)

    @Query(
        """
        UPDATE tlc_employees
        SET pinEnabled = :enabled,
            pinCredentialRef = :credRef,
            pinFailedAttempts = 0,
            pinLockedUntil = NULL,
            recordVersion = recordVersion + 1
        WHERE employeeId = :employeeId
        """
    )
    suspend fun updatePinCredential(employeeId: String, enabled: Boolean, credRef: String?)

    @Query("UPDATE tlc_employees SET pinFailedAttempts = :attempts, pinLockedUntil = :lockedUntil WHERE employeeId = :employeeId")
    suspend fun updatePinAttempts(employeeId: String, attempts: Int, lockedUntil: Long?)

    @Query("SELECT COUNT(*) FROM tlc_employees WHERE role = 'ADMIN' AND accessStatus = 'ACTIVE' AND isActive = 1")
    suspend fun countActiveAdmins(): Int
}

@Dao
interface AccessAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AccessAuditEventEntity)

    @Query("SELECT * FROM tlc_access_audit_events ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AccessAuditEventEntity>>

    @Query("SELECT * FROM tlc_access_audit_events ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<AccessAuditEventEntity>
}

@Dao
interface EmployeeSessionDao {
    @Query("SELECT * FROM tlc_employee_session WHERE sessionSlot = 'PRIMARY' LIMIT 1")
    suspend fun getSession(): EmployeeSessionEntity?

    @Query("SELECT * FROM tlc_employee_session WHERE sessionSlot = 'PRIMARY' LIMIT 1")
    fun observeSession(): Flow<EmployeeSessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: EmployeeSessionEntity)

    @Query("UPDATE tlc_employee_session SET activeEmployeeId = NULL, isPinLocked = 0 WHERE sessionSlot = 'PRIMARY'")
    suspend fun clearActiveEmployee()
}

@Database(
    entities = [
        EmployeeEntity::class,
        AccessAuditEventEntity::class,
        EmployeeSessionEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(EmployeeAccessTypeConverters::class)
abstract class EmployeeAccessDatabase : RoomDatabase() {
    abstract fun employeeDao(): EmployeeDao
    abstract fun accessAuditDao(): AccessAuditDao
    abstract fun sessionDao(): EmployeeSessionDao

    companion object {
        @Volatile
        private var instance: EmployeeAccessDatabase? = null

        fun get(context: Context): EmployeeAccessDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EmployeeAccessDatabase::class.java,
                    "tlc_employee_access_v1.db"
                ).build().also { instance = it }
            }

        fun createInMemory(context: Context): EmployeeAccessDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                EmployeeAccessDatabase::class.java
            ).allowMainThreadQueries().build()
    }
}
