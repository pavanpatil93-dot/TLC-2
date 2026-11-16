package com.example.inventory

import com.example.model.*
import com.example.model.AvailabilityStatus
import com.example.model.VerificationStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VerificationQueueSection(val title: String) {
    NEW_RECORDS("New Property Records"),
    PENDING_VERIFICATION("Pending Verification"),
    UPCOMING_VACANCIES("Upcoming Vacancies"),
    RECENTLY_VERIFIED("Recently Verified Inventory"),
    NEEDING_REVERIFICATION("Records Needing Reverification"),
    MISSING_CRITICAL_INFO("Missing Critical Information"),
    DUPLICATE_REVIEW("Duplicate Review"),
    APPROVAL_QUEUE("Approval Queue")
}

enum class DailyAlertCategory(val label: String) {
    PENDING_VERIFICATION("Pending Verification"),
    UPCOMING_VACANCY("Upcoming Vacancy"),
    STALE_REVERIFICATION("Stale Record"),
    MISSING_DETAILS("Missing Information"),
    APPROVAL_REQUIRED("Approval Queue")
}

data class DailyVerificationAlert(
    val alertId: String,
    val category: DailyAlertCategory,
    val propertyId: String,
    val propertyCode: String,
    val title: String,
    val message: String,
    val signature: String,
    val dateKey: String
)

data class DashboardVerificationQueues(
    val newRecords: List<PropertyItem>,
    val pendingVerification: List<PropertyItem>,
    val upcomingVacancies: List<PropertyItem>,
    val recentlyVerified: List<PropertyItem>,
    val needingReverification: List<PropertyItem>,
    val missingCriticalInfo: List<PropertyItem>,
    val duplicateReview: List<PropertyItem>,
    val approvalQueue: List<PropertyItem>
)

/**
 * Pure domain workflow & daily alert deduplication engine for Home Dashboard Verification Lifecycle:
 * New / Upcoming -> Pending Verification -> Authorized Review -> Verified -> Eligible for Inventory Results.
 */
object VerificationWorkflowEngine {

    fun isVerifiedForInventoryResults(property: PropertyItem): Boolean {
        return property.archiveStatus != ArchiveStatus.ARCHIVED &&
            property.verificationStatus == VerificationStatus.VERIFIED
    }

    fun hasCriticalMissingInfo(property: PropertyItem): Boolean {
        val missingCode = property.propertyCode.isBlank()
        val missingLocation = property.location.isBlank()
        val missingProject = property.projectName.isBlank()
        val missingPrice = (PropertyMatchingEngine.getPriceForEvaluation(property) ?: 0.0) <= 0.0
        val missingCarpet = property.carpetAreaSqFt <= 0.0
        return missingCode || missingLocation || missingProject || missingPrice || missingCarpet
    }

    fun isRecentlyVerified(property: PropertyItem, nowMillis: Long, recentWindowDays: Int = 7): Boolean {
        if (property.verificationStatus != VerificationStatus.VERIFIED) return false
        val verifMillis = PropertyMatchingEngine.parseVerificationMillis(property)
        if (verifMillis <= 0L) return false
        val diffDays = (nowMillis - verifMillis) / (1000L * 60L * 60L * 24L)
        return diffDays in 0..recentWindowDays
    }

    fun needsReverification(property: PropertyItem, validityDays: Int = 30, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (property.verificationStatus != VerificationStatus.VERIFIED) return false
        val verifMillis = PropertyMatchingEngine.parseVerificationMillis(property)
        if (verifMillis <= 0L) return true
        val diffDays = (nowMillis - verifMillis) / (1000L * 60L * 60L * 24L)
        return diffDays > validityDays
    }

    fun isAwaitingAuthorizedReview(property: PropertyItem): Boolean {
        val remarks = property.internalRemarks.orEmpty().lowercase()
        return property.verificationStatus == VerificationStatus.PENDING &&
            (remarks.contains("awaiting_approval") ||
                remarks.contains("authorized_review") ||
                property.status == AvailabilityStatus.VERIFICATION_PENDING ||
                PropertyMatchingEngine.isUpcomingVacancy(property))
    }

    /**
     * Step 1 of upcoming/new approval lifecycle: submits record for Authorized Review (still NOT shown in verified Inventory).
     */
    fun submitForAuthorizedReview(property: PropertyItem, submittedBy: String, dateStr: String): PropertyItem {
        val existingRemarks = property.internalRemarks.orEmpty()
        val tag = if (existingRemarks.contains("authorized_review", true)) existingRemarks else "$existingRemarks [authorized_review]".trim()
        return property.copy(
            verificationStatus = VerificationStatus.PENDING,
            status = AvailabilityStatus.VERIFICATION_PENDING,
            internalRemarks = tag,
            updatedBy = submittedBy,
            updatedAt = dateStr
        )
    }

    /**
     * Final authorization step: only privileged roles (Admin / Commercial / Residential Manager)
     * can approve & mark Verified so the record becomes eligible for normal Inventory results.
     */
    fun authorizeAndVerify(
        property: PropertyItem,
        reviewerRole: UserRole,
        reviewerName: String,
        dateStr: String,
        targetAvailability: AvailabilityStatus = AvailabilityStatus.AVAILABLE
    ): PropertyItem? {
        if (reviewerRole == UserRole.READ_ONLY || reviewerRole == UserRole.OWNER || reviewerRole == UserRole.BROKER) {
            return null
        }
        val cleanRemarks = property.internalRemarks
            ?.replace("[authorized_review]", "")
            ?.replace("awaiting_approval", "")
            ?.trim()
            ?.ifBlank { null }

        return property.copy(
            verificationStatus = VerificationStatus.VERIFIED,
            status = targetAvailability,
            lastVerifiedDateClean = dateStr,
            lastVerificationDate = dateStr,
            verifiedBy = reviewerName,
            internalRemarks = cleanRemarks,
            updatedBy = reviewerName,
            updatedAt = dateStr
        )
    }

    fun buildQueues(
        properties: List<PropertyItem>,
        validityDays: Int = 30,
        nowMillis: Long = System.currentTimeMillis()
    ): DashboardVerificationQueues {
        val active = properties.filter { it.archiveStatus != ArchiveStatus.ARCHIVED }

        val newRecords = active.filter {
            !it.isSyncedToSheet && it.verificationStatus == VerificationStatus.PENDING
        }

        val pendingVerification = active.filter {
            it.verificationStatus == VerificationStatus.PENDING ||
                it.status == AvailabilityStatus.VERIFICATION_PENDING ||
                it.status == AvailabilityStatus.NOT_CONFIRMED
        }

        val upcomingVacancies = active.filter {
            PropertyMatchingEngine.isUpcomingVacancy(it)
        }

        val recentlyVerified = active.filter {
            isRecentlyVerified(it, nowMillis)
        }

        val needingReverification = active.filter {
            needsReverification(it, validityDays, nowMillis)
        }

        val missingCriticalInfo = active.filter {
            hasCriticalMissingInfo(it)
        }

        val duplicateReview = findPotentialDuplicateGroups(active)

        val approvalQueue = active.filter {
            isAwaitingAuthorizedReview(it)
        }

        return DashboardVerificationQueues(
            newRecords = newRecords,
            pendingVerification = pendingVerification,
            upcomingVacancies = upcomingVacancies,
            recentlyVerified = recentlyVerified,
            needingReverification = needingReverification,
            missingCriticalInfo = missingCriticalInfo,
            duplicateReview = duplicateReview,
            approvalQueue = approvalQueue
        )
    }

    private fun findPotentialDuplicateGroups(properties: List<PropertyItem>): List<PropertyItem> {
        val codeCounts = properties.filter { it.propertyCode.isNotBlank() }
            .groupBy { it.propertyCode.trim().uppercase(Locale.ENGLISH) }
        val unitKeyCounts = properties.filter { it.projectName.isNotBlank() && it.unitNumber.isNotBlank() }
            .groupBy { "${it.projectName.trim().lowercase()}|${it.unitNumber.trim().lowercase()}" }

        return properties.filter { prop ->
            val codeDup = prop.propertyCode.isNotBlank() &&
                (codeCounts[prop.propertyCode.trim().uppercase(Locale.ENGLISH)]?.size ?: 0) > 1
            val unitDup = prop.projectName.isNotBlank() && prop.unitNumber.isNotBlank() &&
                (unitKeyCounts["${prop.projectName.trim().lowercase()}|${prop.unitNumber.trim().lowercase()}"]?.size ?: 0) > 1
            codeDup || unitDup
        }
    }
}

/**
 * Generates daily alerts without creating duplicate repeat alerts for unchanged records on the same day.
 */
class DailyAlertTracker {
    private val emittedSignaturesByDate = mutableMapOf<String, MutableSet<String>>()

    fun generateDailyAlerts(
        properties: List<PropertyItem>,
        validityDays: Int = 30,
        nowMillis: Long = System.currentTimeMillis()
    ): List<DailyVerificationAlert> {
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(nowMillis))
        val emittedToday = emittedSignaturesByDate.getOrPut(dateKey) { mutableSetOf() }
        val queues = VerificationWorkflowEngine.buildQueues(properties, validityDays, nowMillis)
        val newAlerts = mutableListOf<DailyVerificationAlert>()

        fun tryEmit(category: DailyAlertCategory, prop: PropertyItem, title: String, message: String) {
            val stateFingerprint = "${category.name}|${prop.id}|${prop.verificationStatus.name}|${prop.status.name}|${prop.upcomingVacancyDate}|${prop.leaseExpiryDate}|${prop.lastVerifiedDateClean}|${prop.priceOrRent}|${prop.carpetAreaSqFt}"
            if (emittedToday.add(stateFingerprint)) {
                newAlerts.add(
                    DailyVerificationAlert(
                        alertId = "ALERT-$dateKey-${category.name}-${prop.id}",
                        category = category,
                        propertyId = prop.id,
                        propertyCode = prop.propertyCode.ifBlank { prop.id },
                        title = title,
                        message = message,
                        signature = stateFingerprint,
                        dateKey = dateKey
                    )
                )
            }
        }

        queues.pendingVerification.forEach { prop ->
            tryEmit(
                DailyAlertCategory.PENDING_VERIFICATION,
                prop,
                "Pending Verification: ${prop.propertyCode}",
                "${prop.projectName} (${prop.location}) requires verification before appearing in inventory."
            )
        }
        queues.upcomingVacancies.forEach { prop ->
            val dateInfo = prop.upcomingVacancyDate.ifBlank { prop.leaseExpiryDate }
            tryEmit(
                DailyAlertCategory.UPCOMING_VACANCY,
                prop,
                "Upcoming Vacancy: ${prop.propertyCode}",
                "${prop.projectName} • Date: $dateInfo"
            )
        }
        queues.needingReverification.forEach { prop ->
            tryEmit(
                DailyAlertCategory.STALE_REVERIFICATION,
                prop,
                "Reverification Due: ${prop.propertyCode}",
                "${prop.projectName} was last verified on ${prop.lastVerifiedDateClean.ifBlank { prop.lastVerificationDate }}."
            )
        }
        queues.missingCriticalInfo.forEach { prop ->
            tryEmit(
                DailyAlertCategory.MISSING_DETAILS,
                prop,
                "Missing Details: ${prop.propertyCode.ifBlank { prop.projectName }}",
                "Missing mandatory area, price, or location details."
            )
        }
        queues.approvalQueue.forEach { prop ->
            tryEmit(
                DailyAlertCategory.APPROVAL_REQUIRED,
                prop,
                "Approval Queue: ${prop.propertyCode}",
                "${prop.projectName} is awaiting manager/admin verification approval."
            )
        }

        return newAlerts
    }
}
