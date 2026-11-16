package com.example.data

import android.content.Context
import com.example.access.EmployeeAccessRole
import com.example.access.EmployeeProfile
import com.example.data.master.ApprovalRequestEntity
import com.example.data.master.AuditEventEntity
import com.example.data.master.MasterProjectDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class SensitiveAction(val actionCode: String, val label: String) {
    DELETE_RECORD("DELETE_RECORD", "Delete Record"),
    BULK_EDIT("BULK_EDIT", "Bulk Edit Inventory"),
    EXPORT_PRIVATE_CONTACTS("EXPORT_PRIVATE_CONTACTS", "Export Confidential Contacts"),
    UPDATE_PRIVATE_CONTACT("UPDATE_PRIVATE_CONTACT", "Update Confidential Contact")
}

enum class SensitiveActionDecision {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY
}

enum class ApprovalFsmState {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    CONFLICT
}

/**
 * Group 11: Approvals, Confidentiality & Audit Log Manager.
 * Implements strict role-based policy enforcement, immutable approval FSM with self-approval guards,
 * formula-injection sanitization for CSV/contact exports, and tamper-evident SHA-256 audit chaining.
 */
class ApprovalAndAuditManager(private val context: Context) {

    private val dao = MasterProjectDatabase.getDatabase(context).masterProjectDao()
    private val auditLock = Mutex()
    private var lastAuditHash: String = "GENESIS_TLC1_AUDIT_LOG_ROOT"

    val pendingApprovalRequests: Flow<List<ApprovalRequestEntity>> = dao.observePendingApprovalRequests()
    val allApprovalRequests: Flow<List<ApprovalRequestEntity>> = dao.observeAllApprovalRequests()
    val pendingApprovalCount: Flow<Int> = dao.getPendingApprovalCount()
    val recentAuditEvents: Flow<List<AuditEventEntity>> = dao.observeRecentAuditEvents(100)

    /**
     * Evaluate sensitive action policy: [ALLOW | REQUIRE_APPROVAL | DENY]
     */
    fun evaluatePolicy(action: SensitiveAction, employee: EmployeeProfile?): SensitiveActionDecision {
        if (employee == null) return SensitiveActionDecision.DENY

        return when (employee.role) {
            EmployeeAccessRole.ADMIN -> SensitiveActionDecision.ALLOW

            EmployeeAccessRole.COMMERCIAL_MANAGER,
            EmployeeAccessRole.RESIDENTIAL_MANAGER,
            EmployeeAccessRole.INVENTORY_MANAGER -> {
                when (action) {
                    SensitiveAction.UPDATE_PRIVATE_CONTACT -> SensitiveActionDecision.ALLOW
                    SensitiveAction.BULK_EDIT -> SensitiveActionDecision.ALLOW
                    SensitiveAction.DELETE_RECORD -> SensitiveActionDecision.REQUIRE_APPROVAL
                    SensitiveAction.EXPORT_PRIVATE_CONTACTS -> SensitiveActionDecision.REQUIRE_APPROVAL
                }
            }

            EmployeeAccessRole.SALES_EXECUTIVE -> {
                when (action) {
                    SensitiveAction.UPDATE_PRIVATE_CONTACT -> SensitiveActionDecision.REQUIRE_APPROVAL
                    SensitiveAction.DELETE_RECORD -> SensitiveActionDecision.REQUIRE_APPROVAL
                    SensitiveAction.BULK_EDIT -> SensitiveActionDecision.REQUIRE_APPROVAL
                    SensitiveAction.EXPORT_PRIVATE_CONTACTS -> SensitiveActionDecision.REQUIRE_APPROVAL
                }
            }

            EmployeeAccessRole.READ_ONLY -> SensitiveActionDecision.DENY
        }
    }

    /**
     * Submit a sensitive action for Admin approval.
     */
    suspend fun submitApprovalRequest(
        action: SensitiveAction,
        entityType: String,
        targetId: String,
        requestedBy: EmployeeProfile,
        reason: String,
        payloadJson: String = ""
    ): ApprovalRequestEntity = withContext(Dispatchers.IO) {
        val requestId = "APPR-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6).uppercase(Locale.ENGLISH)}"
        val entity = ApprovalRequestEntity(
            requestId = requestId,
            actionType = action.actionCode,
            entityType = entityType,
            targetId = targetId,
            requestedBy = requestedBy.employeeId,
            requestedByName = requestedBy.employeeName,
            requestedAt = System.currentTimeMillis(),
            status = ApprovalFsmState.PENDING.name,
            reason = reason.trim(),
            payloadJson = payloadJson
        )
        dao.insertApprovalRequest(entity)

        logAuditEvent(
            actionType = "APPROVAL_REQUESTED",
            entityType = entityType,
            targetId = targetId,
            employee = requestedBy,
            details = "Requested ${action.label} for $targetId. Reason: $reason"
        )

        entity
    }

    /**
     * Decide on an approval request.
     * Enforces self-approval guard: the requesting employee CANNOT approve their own request!
     */
    suspend fun decideApprovalRequest(
        requestId: String,
        approver: EmployeeProfile,
        approved: Boolean,
        rejectionReason: String? = null
    ): Result<ApprovalRequestEntity> = withContext(Dispatchers.IO) {
        val request = dao.getApprovalRequestById(requestId)
            ?: return@withContext Result.failure(IllegalArgumentException("Approval request not found: $requestId"))

        if (request.status != ApprovalFsmState.PENDING.name) {
            return@withContext Result.failure(IllegalStateException("Request $requestId is already ${request.status}"))
        }

        // Self-approval guard
        if (request.requestedBy == approver.employeeId) {
            return@withContext Result.failure(IllegalStateException("Self-approval is strictly prohibited. An independent admin must review this request."))
        }

        // Only Admin or Manager can approve
        if (approver.role != EmployeeAccessRole.ADMIN &&
            approver.role != EmployeeAccessRole.COMMERCIAL_MANAGER &&
            approver.role != EmployeeAccessRole.RESIDENTIAL_MANAGER
        ) {
            return@withContext Result.failure(SecurityException("Only Administrators or Managers are authorized to review approvals."))
        }

        val now = System.currentTimeMillis()
        val newState = if (approved) ApprovalFsmState.APPROVED else ApprovalFsmState.REJECTED
        val updated = request.copy(
            status = newState.name,
            reviewedBy = approver.employeeId,
            reviewedByName = approver.employeeName,
            reviewedAt = now,
            rejectionReason = if (!approved) rejectionReason?.trim().orEmpty().ifBlank { "Rejected by reviewer" } else null
        )

        dao.updateApprovalRequest(updated)

        logAuditEvent(
            actionType = if (approved) "APPROVAL_GRANTED" else "APPROVAL_REJECTED",
            entityType = request.entityType,
            targetId = request.targetId,
            employee = approver,
            details = "${if (approved) "Approved" else "Rejected"} ${request.actionType} for ${request.targetId} requested by ${request.requestedByName}.${if (!approved && !rejectionReason.isNullOrBlank()) " Reason: $rejectionReason" else ""}"
        )

        Result.success(updated)
    }

    /**
     * Cancel an unapproved request by the original requester.
     */
    suspend fun cancelApprovalRequest(
        requestId: String,
        employee: EmployeeProfile
    ): Result<ApprovalRequestEntity> = withContext(Dispatchers.IO) {
        val request = dao.getApprovalRequestById(requestId)
            ?: return@withContext Result.failure(IllegalArgumentException("Request not found"))

        if (request.requestedBy != employee.employeeId && employee.role != EmployeeAccessRole.ADMIN) {
            return@withContext Result.failure(SecurityException("Cannot cancel request created by another employee"))
        }

        if (request.status != ApprovalFsmState.PENDING.name) {
            return@withContext Result.failure(IllegalStateException("Only PENDING requests can be cancelled"))
        }

        val updated = request.copy(
            status = ApprovalFsmState.CANCELLED.name,
            reviewedBy = employee.employeeId,
            reviewedByName = employee.employeeName,
            reviewedAt = System.currentTimeMillis(),
            rejectionReason = "Cancelled by requester"
        )
        dao.updateApprovalRequest(updated)

        logAuditEvent(
            actionType = "APPROVAL_CANCELLED",
            entityType = request.entityType,
            targetId = request.targetId,
            employee = employee,
            details = "Cancelled ${request.actionType} request $requestId"
        )

        Result.success(updated)
    }

    /**
     * Export confidential contacts with spreadsheet formula injection protection.
     * Sanitizes values starting with '=', '+', '-', '@', '\t', '\r' by prepending a single quote.
     */
    fun sanitizeCsvCell(raw: String?): String {
        if (raw == null) return ""
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val dangerousChars = setOf('=', '+', '-', '@', '\t', '\r')
        val neutralized = if (trimmed.first() in dangerousChars) {
            "'$trimmed"
        } else {
            trimmed
        }

        // Escape quotes
        val escaped = neutralized.replace("\"", "\"\"")
        return if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    /**
     * Log a tamper-evident audit event with SHA-256 chain hash.
     */
    suspend fun logAuditEvent(
        actionType: String,
        entityType: String,
        targetId: String,
        employee: EmployeeProfile,
        details: String
    ): AuditEventEntity = withContext(Dispatchers.IO) {
        auditLock.withLock {
            val eventId = "AUDIT-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6).uppercase(Locale.ENGLISH)}"
            val timestamp = System.currentTimeMillis()

            val rawChain = "$lastAuditHash|$timestamp|$actionType|$entityType|$targetId|${employee.employeeId}|$details"
            val newHash = sha256(rawChain)
            lastAuditHash = newHash

            val entity = AuditEventEntity(
                eventId = eventId,
                eventTimestamp = timestamp,
                actionType = actionType,
                entityType = entityType,
                targetId = targetId,
                employeeId = employee.employeeId,
                employeeName = employee.employeeName,
                details = details,
                isTamperEvident = true,
                hash = newHash
            )

            dao.insertAuditEvent(entity)
            entity
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
