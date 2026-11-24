# Android ↔ TLC Web API v1 (staging proposal)

**Do not connect TLC-2 live Android sync until identity and Sheet reconciliation are complete.**

## Identity and transport
- HTTPS only, verified Bearer JWT with exact audience/issuer/JWKS; app does not ship service-account keys.
- Read role and permissions from server-side approved account, never from client role flags.
- GET /api/v1/auth/me returns approved profile and effective permissions, otherwise 401/403.
- GET /api/v1/sync/changes?after=<integer cursor> returns up to 200 version events and nextCursor, only to authorized employees.
- POST /api/v1/sync/changes is intentionally disabled (503).

## Future upload protocol (not implemented yet)
- Android Room entity and durable outbox row saved atomically.
- Operation ID is random UUID, unique per client mutation; server must scope by account and operation key.
- Upload includes property UUID, expected server version, allowed mutation fields and client operation UUID.
- Server must enforce staff role, RLS, version comparison, immutable audit entry and outbox update in one transaction.
- Retried operation with the same idempotency key and hash returns prior success; same key with changed hash fails.
- 409 conflict returns server version / allowed safe diff, never overwrites silently.
- 401 token expired -> pause retries and request sign in, do not drop outbox entries.
- 403 denied -> non-retryable, retain local record for review.
- 503 transient -> exponential backoff via WorkManager; no infinite foreground service.
- After server ACK, advance local cursor and mark operation synced. No public publishing from mobile drafts.

## Source reconciliation
Legacy Android coordinator targets Sheet 1VJruPx1GTOMDziafPYK6ihW1v1DGrqTWO2vHk_oNReQ.
Web read-only approved adapter targets Sheet 1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0.
Map exact property codes, project IDs, unit IDs, employee roles and source-of-truth ownership. Never auto-merge on project name alone.
