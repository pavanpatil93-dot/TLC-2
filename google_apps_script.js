/**
 * TLC-1 Phase 2 — FAIL-CLOSED deployment placeholder.
 *
 * DO NOT deploy the legacy script: it recommended anonymous access to private
 * owner/contact inventory and accepted unauthenticated property writes.
 *
 * This placeholder intentionally does not read or mutate the Master Sheet.
 * It is NOT an authentication implementation. Before production deployment,
 * implement server-side Google identity verification, admin authorization,
 * exact three-tab schema mapping, per-record revision checks, conflict review,
 * audit logging, recoverable backups, and explicit rollout approval.
 *
 * The prior implementation remains available in Git history for review.
 */
function disabledResponse_() {
  return ContentService.createTextOutput(JSON.stringify({
    status: 'disabled',
    error: 'TLC-1 Phase 2 sync is disabled until verified backup and authenticated admin-only deployment.'
  })).setMimeType(ContentService.MimeType.JSON);
}

function doGet(e) {
  return disabledResponse_();
}

function doPost(e) {
  return disabledResponse_();
}
