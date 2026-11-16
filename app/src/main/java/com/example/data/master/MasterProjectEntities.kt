package com.example.data.master

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ProjectType {
    COMMERCIAL,
    RESIDENTIAL
}

enum class ProjectStatus {
    PRE_LAUNCH,
    UNDER_CONSTRUCTION,
    READY,
    COMPLETED
}

enum class VerificationStatus {
    VERIFIED,
    PARTIALLY_VERIFIED,
    UNVERIFIED
}

@Entity(
    tableName = "projects_master",
    indices = [
        Index(value = ["projectName"]),
        Index(value = ["projectType"]),
        Index(value = ["microMarket"]),
        Index(value = ["city"])
    ]
)
data class ProjectEntity(
    @PrimaryKey
    val projectId: String, // "CP-0001" or "RP-0001"
    val projectName: String,
    val projectType: ProjectType,
    val developerName: String,
    val city: String = "Pune",
    val location: String = "East Pune", // e.g., East Pune, West Pune, etc.
    val microMarket: String, // e.g., Kharadi, Viman Nagar, Wakad
    val category: String, // Office, Retail, IT Park, 2BHK, 3BHK, Villa
    val reraNumber: String? = null,
    val possessionDate: String? = null,
    val status: ProjectStatus = ProjectStatus.READY,
    val verificationStatus: VerificationStatus = VerificationStatus.VERIFIED,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "unit_inventory_master",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["projectId"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["propertyCode"]),
        Index(value = ["status"])
    ]
)
data class PropertyUnitEntity(
    @PrimaryKey
    val unitId: String,
    val projectId: String,
    val propertyCode: String,
    val towerBuilding: String? = null,
    val unitNumber: String,
    val floor: Int = 1,
    val areaSqFt: Double = 1000.0,
    val pricing: Double = 5000000.0,
    val rentAmount: Double? = null,
    val tab: String = "Off Lease", // InventoryTab code
    val furnishing: String = "Fully Furnished",
    val status: String = "Available", // AvailabilityStatus
    val ownerName: String? = null,
    val ownerContact: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class ProjectWithUnits(
    @androidx.room.Embedded
    val project: ProjectEntity,
    @androidx.room.Relation(
        parentColumn = "projectId",
        entityColumn = "projectId"
    )
    val units: List<PropertyUnitEntity>
)

@Entity(
    tableName = "project_aliases",
    indices = [Index(value = ["projectId"]), Index(value = ["aliasName"])]
)
data class ProjectAliasEntity(
    @PrimaryKey
    val aliasId: String,
    val projectId: String,
    val aliasName: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "project_phases",
    indices = [Index(value = ["projectId"])]
)
data class ProjectPhaseEntity(
    @PrimaryKey
    val phaseId: String,
    val projectId: String,
    val phaseName: String,
    val reraNumber: String? = null,
    val completionDate: String? = null,
    val status: String = "UNDER_CONSTRUCTION",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "project_towers",
    indices = [Index(value = ["projectId"]), Index(value = ["phaseId"])]
)
data class ProjectTowerEntity(
    @PrimaryKey
    val towerId: String,
    val projectId: String,
    val phaseId: String? = null,
    val towerName: String,
    val totalFloors: Int = 1,
    val status: String = "READY",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "project_configs",
    indices = [Index(value = ["projectId"])]
)
data class ProjectConfigEntity(
    @PrimaryKey
    val configId: String,
    val projectId: String,
    val configType: String,
    val startingAreaSqFt: Double = 0.0,
    val startingPrice: Double = 0.0,
    val observedDate: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "property_bookmarks",
    indices = [Index(value = ["employeeId"]), Index(value = ["propertyId"])]
)
data class PropertyBookmarkEntity(
    @PrimaryKey
    val bookmarkId: String,
    val employeeId: String,
    val propertyId: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "duplicate_reviews",
    indices = [
        Index(value = ["propertyId1"]),
        Index(value = ["propertyId2"]),
        Index(value = ["decision"])
    ]
)
data class DuplicateReviewEntity(
    @PrimaryKey
    val reviewId: String,
    val propertyId1: String,
    val propertyId2: String,
    val score: Int = 0,
    val decision: String = "UNREVIEWED", // UNREVIEWED, KEEP_SEPARATE, LINK_SAME_UNIT, REVIEW_LATER
    val reviewedBy: String? = null,
    val reviewedAt: Long? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "approval_requests",
    indices = [
        Index(value = ["status"]),
        Index(value = ["targetId"]),
        Index(value = ["requestedBy"])
    ]
)
data class ApprovalRequestEntity(
    @PrimaryKey
    val requestId: String,
    val actionType: String, // DELETE_RECORD, BULK_EDIT, EXPORT_PRIVATE_CONTACTS, UPDATE_PRIVATE_CONTACT
    val entityType: String, // PROPERTY, CRM_CLIENT, PROJECT
    val targetId: String,
    val requestedBy: String,
    val requestedByName: String,
    val requestedAt: Long = System.currentTimeMillis(),
    val status: String = "PENDING", // PENDING, APPROVED, REJECTED, CANCELLED, EXPIRED, CONFLICT
    val reviewedBy: String? = null,
    val reviewedByName: String? = null,
    val reviewedAt: Long? = null,
    val reason: String = "",
    val rejectionReason: String? = null,
    val payloadJson: String = ""
)

@Entity(
    tableName = "audit_events",
    indices = [
        Index(value = ["eventTimestamp"]),
        Index(value = ["actionType"]),
        Index(value = ["targetId"])
    ]
)
data class AuditEventEntity(
    @PrimaryKey
    val eventId: String,
    val eventTimestamp: Long = System.currentTimeMillis(),
    val actionType: String,
    val entityType: String,
    val targetId: String,
    val employeeId: String,
    val employeeName: String,
    val details: String,
    val isTamperEvident: Boolean = true,
    val hash: String = ""
)

@Entity(
    tableName = "market_pulse_feed",
    indices = [
        Index(value = ["publishedAt"]),
        Index(value = ["category"])
    ]
)
data class MarketPulseEntity(
    @PrimaryKey
    val pulseId: String,
    val title: String,
    val summary: String,
    val sourceUrl: String,
    val publishedAt: Long = System.currentTimeMillis(),
    val category: String = "INFRASTRUCTURE",
    val microMarket: String = "",
    val isCached: Boolean = true
)


