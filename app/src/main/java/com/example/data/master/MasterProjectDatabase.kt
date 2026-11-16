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
        MarketPulseEntity::class
    ],
    version = 2,
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

        fun getDatabase(context: Context): MasterProjectDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MasterProjectDatabase::class.java,
                    "master_project_inventory.db"
                )
                .addMigrations(MIGRATION_1_2)
                .addCallback(SeedCallback(context))
                .build()
                INSTANCE = instance
                instance
            }
        }

        private class SeedCallback(private val context: Context) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(Dispatchers.IO).launch {
                    val dao = getDatabase(context).masterProjectDao()
                    // Seed initial rich Pune Real Estate projects
                    val initialProjects = listOf(
                        ProjectEntity(
                            projectId = "CP-0001",
                            projectName = "World Trade Center (WTC)",
                            projectType = ProjectType.COMMERCIAL,
                            developerName = "Panchshil Realty",
                            city = "Pune",
                            location = "East Pune",
                            microMarket = "Kharadi",
                            category = "IT Park / Offices",
                            reraNumber = "P52100001234",
                            possessionDate = "Ready to Move",
                            status = ProjectStatus.READY,
                            verificationStatus = VerificationStatus.VERIFIED
                        ),
                        ProjectEntity(
                            projectId = "CP-0002",
                            projectName = "EON Free Zone Phase 2",
                            projectType = ProjectType.COMMERCIAL,
                            developerName = "Panchshil Realty",
                            city = "Pune",
                            location = "East Pune",
                            microMarket = "Kharadi",
                            category = "SEZ Office Towers",
                            reraNumber = "P52100005678",
                            possessionDate = "Ready to Move",
                            status = ProjectStatus.READY,
                            verificationStatus = VerificationStatus.VERIFIED
                        ),
                        ProjectEntity(
                            projectId = "CP-0003",
                            projectName = "Gera Imperium Alpha",
                            projectType = ProjectType.COMMERCIAL,
                            developerName = "Gera Developments",
                            city = "Pune",
                            location = "East Pune",
                            microMarket = "Kharadi",
                            category = "Commercial Offices & Retail",
                            reraNumber = "P52100011223",
                            possessionDate = "Ready to Move",
                            status = ProjectStatus.READY,
                            verificationStatus = VerificationStatus.VERIFIED
                        ),
                        ProjectEntity(
                            projectId = "CP-0004",
                            projectName = "Phoenix Marketcity High Street",
                            projectType = ProjectType.COMMERCIAL,
                            developerName = "The Phoenix Mills Ltd",
                            city = "Pune",
                            location = "East Pune",
                            microMarket = "Viman Nagar",
                            category = "Retail Showrooms",
                            reraNumber = "P52100022334",
                            possessionDate = "Ready to Move",
                            status = ProjectStatus.READY,
                            verificationStatus = VerificationStatus.VERIFIED
                        ),
                        ProjectEntity(
                            projectId = "RP-0001",
                            projectName = "Kasturi Apostrophe",
                            projectType = ProjectType.RESIDENTIAL,
                            developerName = "Kasturi Developments",
                            city = "Pune",
                            location = "West Pune",
                            microMarket = "Wakad",
                            category = "3 BHK Luxury Apartments",
                            reraNumber = "P52100012399",
                            possessionDate = "December 2026",
                            status = ProjectStatus.UNDER_CONSTRUCTION,
                            verificationStatus = VerificationStatus.VERIFIED
                        ),
                        ProjectEntity(
                            projectId = "RP-0002",
                            projectName = "Kumar Primavera",
                            projectType = ProjectType.RESIDENTIAL,
                            developerName = "Kumar Properties",
                            city = "Pune",
                            location = "East Pune",
                            microMarket = "Kalyani Nagar",
                            category = "2 & 3 BHK Apartments",
                            reraNumber = "P52100009876",
                            possessionDate = "Ready to Move",
                            status = ProjectStatus.READY,
                            verificationStatus = VerificationStatus.VERIFIED
                        ),
                        ProjectEntity(
                            projectId = "RP-0003",
                            projectName = "Godrej Infinity",
                            projectType = ProjectType.RESIDENTIAL,
                            developerName = "Godrej Properties",
                            city = "Pune",
                            location = "East Pune",
                            microMarket = "Keshav Nagar",
                            category = "2 & 3 BHK Residences",
                            reraNumber = "P52100004511",
                            possessionDate = "Ready to Move",
                            status = ProjectStatus.READY,
                            verificationStatus = VerificationStatus.VERIFIED
                        )
                    )

                    initialProjects.forEach { dao.insertProject(it) }

                    // Seed sample unit inventory
                    val initialUnits = listOf(
                        PropertyUnitEntity(
                            unitId = "UNIT-CP-001",
                            projectId = "CP-0001",
                            propertyCode = "OL-KHD-401",
                            towerBuilding = "Tower 4",
                            unitNumber = "801",
                            floor = 8,
                            areaSqFt = 2400.0,
                            pricing = 36000000.0,
                            rentAmount = 168000.0,
                            tab = "Off Lease",
                            furnishing = "Fully Furnished",
                            status = "Available",
                            ownerName = "Rajesh Singhania",
                            ownerContact = "9822011223"
                        ),
                        PropertyUnitEntity(
                            unitId = "UNIT-CP-002",
                            projectId = "CP-0003",
                            propertyCode = "OL-KHD-305",
                            towerBuilding = "Wing A",
                            unitNumber = "305",
                            floor = 3,
                            areaSqFt = 1450.0,
                            pricing = 22500000.0,
                            rentAmount = 95000.0,
                            tab = "Pre - Leased",
                            furnishing = "Semi Furnished",
                            status = "Available",
                            ownerName = "Anil Deshmukh",
                            ownerContact = "9850123456"
                        ),
                        PropertyUnitEntity(
                            unitId = "UNIT-RP-001",
                            projectId = "RP-0001",
                            propertyCode = "RR-WKD-1202",
                            towerBuilding = "Building B",
                            unitNumber = "1202",
                            floor = 12,
                            areaSqFt = 1320.0,
                            pricing = 17500000.0,
                            rentAmount = 45000.0,
                            tab = "Resi Rent",
                            furnishing = "Semi Furnished",
                            status = "Available",
                            ownerName = "Dr. Prakash Kulkarni",
                            ownerContact = "9860012345"
                        )
                    )

                    initialUnits.forEach { dao.insertUnit(it) }
                }
            }
        }
    }
}
