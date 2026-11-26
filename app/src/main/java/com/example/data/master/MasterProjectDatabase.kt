package com.example.data.master

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ProjectEntity::class,
        PropertyUnitEntity::class,
        ProjectAliasEntity::class,
        ProjectPhaseEntity::class,
        ProjectTowerEntity::class,
        ProjectConfigEntity::class,
        PropertyBookmarkEntity::class,
        DuplicateReviewEntity::class,
        ApprovalRequestEntity::class,
        AuditEventEntity::class,
        MarketPulseEntity::class,
        CallLogEntity::class,
        BrokerEntity::class,
        CallAnalysisEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class MasterProjectDatabase : RoomDatabase() {

    abstract fun masterProjectDao(): MasterProjectDao

    companion object {
        @Volatile
        private var INSTANCE: MasterProjectDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. project_aliases
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `project_aliases` (
                        `aliasId` TEXT NOT NULL PRIMARY KEY,
                        `projectId` TEXT NOT NULL,
                        `aliasName` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_aliases_projectId` ON `project_aliases`(`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_aliases_aliasName` ON `project_aliases`(`aliasName`)")

                // 2. project_phases
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `project_phases` (
                        `phaseId` TEXT NOT NULL PRIMARY KEY,
                        `projectId` TEXT NOT NULL,
                        `phaseName` TEXT NOT NULL,
                        `reraNumber` TEXT,
                        `completionDate` TEXT,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_phases_projectId` ON `project_phases`(`projectId`)")

                // 3. project_towers
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `project_towers` (
                        `towerId` TEXT NOT NULL PRIMARY KEY,
                        `projectId` TEXT NOT NULL,
                        `phaseId` TEXT,
                        `towerName` TEXT NOT NULL,
                        `totalFloors` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_towers_projectId` ON `project_towers`(`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_towers_phaseId` ON `project_towers`(`phaseId`)")

                // 4. project_configs
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `project_configs` (
                        `configId` TEXT NOT NULL PRIMARY KEY,
                        `projectId` TEXT NOT NULL,
                        `configType` TEXT NOT NULL,
                        `startingAreaSqFt` REAL NOT NULL,
                        `startingPrice` REAL NOT NULL,
                        `observedDate` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_configs_projectId` ON `project_configs`(`projectId`)")

                // 5. property_bookmarks
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `property_bookmarks` (
                        `bookmarkId` TEXT NOT NULL PRIMARY KEY,
                        `employeeId` TEXT NOT NULL,
                        `propertyId` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_property_bookmarks_employeeId` ON `property_bookmarks`(`employeeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_property_bookmarks_propertyId` ON `property_bookmarks`(`propertyId`)")

                // 6. duplicate_reviews
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `duplicate_reviews` (
                        `reviewId` TEXT NOT NULL PRIMARY KEY,
                        `propertyId1` TEXT NOT NULL,
                        `propertyId2` TEXT NOT NULL,
                        `score` INTEGER NOT NULL,
                        `decision` TEXT NOT NULL,
                        `reviewedBy` TEXT,
                        `reviewedAt` INTEGER,
                        `notes` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_duplicate_reviews_propertyId1` ON `duplicate_reviews`(`propertyId1`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_duplicate_reviews_propertyId2` ON `duplicate_reviews`(`propertyId2`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_duplicate_reviews_decision` ON `duplicate_reviews`(`decision`)")

                // 7. approval_requests
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `approval_requests` (
                        `requestId` TEXT NOT NULL PRIMARY KEY,
                        `actionType` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `requestedBy` TEXT NOT NULL,
                        `requestedByName` TEXT NOT NULL,
                        `requestedAt` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `reviewedBy` TEXT,
                        `reviewedByName` TEXT,
                        `reviewedAt` INTEGER,
                        `reason` TEXT NOT NULL,
                        `rejectionReason` TEXT,
                        `payloadJson` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_requests_status` ON `approval_requests`(`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_requests_targetId` ON `approval_requests`(`targetId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_requests_requestedBy` ON `approval_requests`(`requestedBy`)")

                // 8. audit_events
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audit_events` (
                        `eventId` TEXT NOT NULL PRIMARY KEY,
                        `eventTimestamp` INTEGER NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `employeeId` TEXT NOT NULL,
                        `employeeName` TEXT NOT NULL,
                        `details` TEXT NOT NULL,
                        `isTamperEvident` INTEGER NOT NULL,
                        `hash` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_eventTimestamp` ON `audit_events`(`eventTimestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_actionType` ON `audit_events`(`actionType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_targetId` ON `audit_events`(`targetId`)")

                // 9. market_pulse_feed
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `market_pulse_feed` (
                        `pulseId` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `publishedAt` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `microMarket` TEXT NOT NULL,
                        `isCached` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_market_pulse_feed_publishedAt` ON `market_pulse_feed`(`publishedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_market_pulse_feed_category` ON `market_pulse_feed`(`category`)")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existingCols = mutableSetOf<String>()
                db.query("PRAGMA table_info(`unit_inventory_master`)").use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIdx >= 0) {
                            existingCols.add(cursor.getString(nameIdx))
                        }
                    }
                }
                val columnsToAdd = listOf(
                    "microMarket" to "TEXT",
                    "attachedPantry" to "TEXT",
                    "attachedWashroom" to "TEXT",
                    "frontageFt" to "REAL",
                    "clearHeightFt" to "REAL",
                    "truckAccess" to "TEXT",
                    "powerLoad" to "TEXT",
                    "targetPossessionDate" to "TEXT"
                )
                columnsToAdd.forEach { (colName, sqlType) ->
                    if (colName !in existingCols) {
                        db.execSQL("ALTER TABLE `unit_inventory_master` ADD COLUMN `$colName` $sqlType DEFAULT NULL")
                    }
                }
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `call_logs_master` (
                        `callId` TEXT NOT NULL PRIMARY KEY,
                        `timestamp` INTEGER NOT NULL,
                        `callerName` TEXT NOT NULL,
                        `phoneNumber` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `roleOrLinkedId` TEXT NOT NULL,
                        `linkedTitle` TEXT NOT NULL,
                        `notesOrAiSummary` TEXT NOT NULL,
                        `employeeName` TEXT NOT NULL,
                        `deviceName` TEXT DEFAULT NULL,
                        `recordingFilename` TEXT DEFAULT NULL,
                        `syncStatus` TEXT NOT NULL DEFAULT 'PENDING',
                        `updatedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_logs_master_timestamp` ON `call_logs_master`(`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_logs_master_syncStatus` ON `call_logs_master`(`syncStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_logs_master_phoneNumber` ON `call_logs_master`(`phoneNumber`)")

                // Defensive column guard if table existed from an earlier build without deviceName/syncStatus
                val existingCols = mutableSetOf<String>()
                db.query("PRAGMA table_info(`call_logs_master`)").use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIdx >= 0) existingCols.add(cursor.getString(nameIdx))
                    }
                }
                if ("deviceName" !in existingCols) {
                    db.execSQL("ALTER TABLE `call_logs_master` ADD COLUMN `deviceName` TEXT DEFAULT NULL")
                }
                if ("recordingFilename" !in existingCols) {
                    db.execSQL("ALTER TABLE `call_logs_master` ADD COLUMN `recordingFilename` TEXT DEFAULT NULL")
                }
                if ("syncStatus" !in existingCols) {
                    db.execSQL("ALTER TABLE `call_logs_master` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'PENDING'")
                }
                if ("updatedAt" !in existingCols) {
                    db.execSQL("ALTER TABLE `call_logs_master` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `brokers_master` (
                        `brokerId` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL DEFAULT '',
                        `phone` TEXT NOT NULL DEFAULT '',
                        `mobileLast10` TEXT NOT NULL DEFAULT '',
                        `division` TEXT NOT NULL DEFAULT 'Commercial',
                        `firmName` TEXT NOT NULL DEFAULT '',
                        `operatingAreas` TEXT NOT NULL DEFAULT '',
                        `specialization` TEXT NOT NULL DEFAULT '',
                        `confidence` TEXT NOT NULL DEFAULT '',
                        `notes` TEXT NOT NULL DEFAULT '',
                        `sourceTab` TEXT NOT NULL DEFAULT 'Commercial Brokers',
                        `updatedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_brokers_master_mobileLast10` ON `brokers_master`(`mobileLast10`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_brokers_master_division` ON `brokers_master`(`division`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_brokers_master_sourceTab` ON `brokers_master`(`sourceTab`)")
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `call_analysis_master` (
                        `callLogId` TEXT NOT NULL PRIMARY KEY,
                        `callerName` TEXT NOT NULL DEFAULT '',
                        `phoneNumber` TEXT NOT NULL DEFAULT '',
                        `callTimestamp` INTEGER NOT NULL DEFAULT 0,
                        `durationSeconds` INTEGER NOT NULL DEFAULT 0,
                        `callerRole` TEXT NOT NULL DEFAULT 'PERSONAL_NON_BUSINESS',
                        `detectedLanguage` TEXT NOT NULL DEFAULT 'English',
                        `summaryBulletsJson` TEXT NOT NULL DEFAULT '[]',
                        `extractedClientLeadJson` TEXT NOT NULL DEFAULT '',
                        `extractedPropertyUpdateJson` TEXT NOT NULL DEFAULT '',
                        `extractedTenantNoteJson` TEXT NOT NULL DEFAULT '',
                        `followUpDate` TEXT NOT NULL DEFAULT '',
                        `followUpTime` TEXT NOT NULL DEFAULT '',
                        `followUpNextAction` TEXT NOT NULL DEFAULT '',
                        `suggestedWhatsAppDraft` TEXT NOT NULL DEFAULT '',
                        `confidence` INTEGER NOT NULL DEFAULT 0,
                        `needsReview` INTEGER NOT NULL DEFAULT 0,
                        `modelUsed` TEXT NOT NULL DEFAULT '',
                        `reviewStatus` TEXT NOT NULL DEFAULT 'PENDING_REVIEW',
                        `aiAnalyzedAt` INTEGER NOT NULL DEFAULT 0,
                        `deviceName` TEXT NOT NULL DEFAULT '',
                        `recordingFilename` TEXT NOT NULL DEFAULT '',
                        `rawTranscript` TEXT NOT NULL DEFAULT '',
                        `syncedToSheet` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_analysis_master_callerRole` ON `call_analysis_master`(`callerRole`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_analysis_master_reviewStatus` ON `call_analysis_master`(`reviewStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_analysis_master_aiAnalyzedAt` ON `call_analysis_master`(`aiAnalyzedAt`)")
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existingCols = mutableSetOf<String>()
                db.query("PRAGMA table_info(`brokers_master`)").use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIdx >= 0) existingCols.add(cursor.getString(nameIdx))
                    }
                }
                val columnsToAdd = listOf(
                    "phoneType" to "TEXT NOT NULL DEFAULT ''",
                    "alternateMobiles" to "TEXT NOT NULL DEFAULT ''",
                    "verificationStatus" to "TEXT NOT NULL DEFAULT ''"
                )
                columnsToAdd.forEach { (colName, sqlDef) ->
                    if (colName !in existingCols) {
                        db.execSQL("ALTER TABLE `brokers_master` ADD COLUMN `$colName` $sqlDef")
                    }
                }
            }
        }

        fun getDatabase(context: Context): MasterProjectDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MasterProjectDatabase::class.java,
                    "master_project_inventory.db"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
