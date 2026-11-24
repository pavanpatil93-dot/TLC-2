# TLC-2 Android ↔ Website Sync — Batch 07 handoff

**State: contract prepared, Android production integration NOT activated.**

## Required architecture
- One canonical property UUID, permanent legacy source identifiers, server-owned monotonically increasing version.
- Public website publishes only records that satisfy approval + verification + available + unarchived.
- Android must authenticate with a cryptographically verified employee session. Device ID, PIN or self-declared user role are NOT authentication.
- **All mutation APIs remain 401/503 until verified login, staging database policies, and permission checks pass.**

## Proposed Android outbox event
```json
{
  "schema": "tlc.property.change.v1",
  "device_public_id": "device-generated-nonsecret-uuid",
  "idempotency_key": "unique-mutation-uuid",
  "property_id": "server-assigned-uuid",
  "base_version": 7,
  "operation": "update",
  "changes": {"carpet_sqft": 1000},
  "occurred_at": "2026-11-24T07:00:00Z"
}
```
Never transmit owners' contacts, CRM information or call recordings through public listing endpoints.

## Conflict protocol
1. Android sends mutation with `idempotency_key` and `base_version` plus verified bearer session.
2. Server enforces account scope, property authority, input allowlist, and transaction permissions.
3. Mutation transaction checks version with a conditional `UPDATE ... WHERE version=:base_version`.
4. If 0 rows, return **409 VERSION_CONFLICT** with safely scoped current version and require review. Never overwrite silently.
5. On success, write audit event and change_outbox record atomically before commit; respond with new version.
6. Device acknowledges outbox IDs only after local database transaction succeeds. Replay is idempotent.
7. Deleted listings are archived/tombstoned; consumers must not resurrect an old offline copy.
8. User can retry connectivity failures; never retry invalid role or data-validation rejections indefinitely.

## Replay and rollout
- Start with isolated staging accounts and a **synthetic inventory**.
- Build integration tests for offline edits, simultaneous writes, duplicate event replay, expired auth, publish/unpublish, user role changes, and conflict recovery.
- Shadow-read comparison before enabling new source-of-truth.
- Migrate Android `OneTapSyncCoordinator` only after reconciling legacy Google Sheet ID `1VJruPx1GTOMDziafPYK6ihW1v1DGrqTWO2vHk_oNReQ` with website Sheet ID `1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0`.
- No Android APK or live sync change has been made in this batch.
