package com.example.model

/**
 * Project inventory statuses are separate for sale and lease.
 * This avoids treating a leased-out unit as sold, or hiding a unit
 * still available for sale. No existing inventory records are mutated.
 */
enum class ProjectSaleStatus { AVAILABLE, SOLD, NOT_AVAILABLE, NOT_LISTED, UNKNOWN }
enum class ProjectLeaseStatus { AVAILABLE, LEASED_OUT, NOT_AVAILABLE, NOT_LISTED, UNKNOWN }

data class ProjectUnitAvailability(
    val propertyId: String,
    val projectId: String,
    val saleStatus: ProjectSaleStatus = ProjectSaleStatus.UNKNOWN,
    val leaseStatus: ProjectLeaseStatus = ProjectLeaseStatus.UNKNOWN
)

data class ProjectInventorySummary(
    val totalUnits: Int,
    val availableForSale: Int,
    val availableForLease: Int,
    val sold: Int,
    val leasedOut: Int,
    val saleUnavailable: Int,
    val leaseUnavailable: Int,
    val needsReview: Int
)

object ProjectInventorySummaryRules {
    fun summarize(projectId: String, units: List<ProjectUnitAvailability>): ProjectInventorySummary {
        val matches = units.filter { it.projectId == projectId && it.propertyId.isNotBlank() }
            .distinctBy { it.propertyId }
        return ProjectInventorySummary(
            totalUnits = matches.size,
            availableForSale = matches.count { it.saleStatus == ProjectSaleStatus.AVAILABLE },
            availableForLease = matches.count { it.leaseStatus == ProjectLeaseStatus.AVAILABLE },
            sold = matches.count { it.saleStatus == ProjectSaleStatus.SOLD },
            leasedOut = matches.count { it.leaseStatus == ProjectLeaseStatus.LEASED_OUT },
            saleUnavailable = matches.count { it.saleStatus == ProjectSaleStatus.NOT_AVAILABLE },
            leaseUnavailable = matches.count { it.leaseStatus == ProjectLeaseStatus.NOT_AVAILABLE },
            needsReview = matches.count {
                it.saleStatus == ProjectSaleStatus.UNKNOWN ||
                    it.leaseStatus == ProjectLeaseStatus.UNKNOWN
            }
        )
    }
}

