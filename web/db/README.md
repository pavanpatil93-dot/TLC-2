# TLC-2 Central Database — Batch 03 staging foundation

## Status
This repository contains a **schema and API contract**, not a running production database.
No credentials have been configured; no real data has been imported or modified.
The public website keeps its existing approval-only Google Sheets read adapter.
Android `main`, `www.theleasingcompany.in` and live Google Sheets are untouched.

## Staging deployment (manual approval required)
1. Create a **new non-production PostgreSQL 15+** database with backups and encrypted connections.
2. Create separate migration-owner and restricted application database roles.
3. Review `db/migrations/001_core.sql` with a qualified database administrator. Apply ONLY to the empty staging database using migration-owner privileges.
4. Confirm that `tlc` schema grants are private. The migration enables **FORCE ROW LEVEL SECURITY** and supplies **no allow policies**. Restricted application roles must not be owners or have BYPASSRLS.
5. Configure `TLC_DATABASE_URL` for Vercel **Preview only** after approval; store server-side, never `NEXT_PUBLIC_*`.
6. Use `GET /api/v1/system/health` to check connectivity. This endpoint exposes no records or secrets.
7. Confirm `GET /api/v1/admin/properties` and `POST /api/v1/admin/properties` return **401** until Batch 04 identity verification is implemented. Fake headers must not authenticate.
8. Before enabling writes, add audited transactions, verified principal binding, enforceable RBAC/RLS policies, concurrency `version` checks, idempotency, outbox and integration tests.

## Data contract
- One UUID per logical property, with preserved `property_code` and separate unique legacy identifiers.
- `projects` are separate from `properties` (one-to-many).
- Distinct `availability_status`, `verification_status` and `publication_status`.
- Property can be `published` only with explicit approval, verification and available status.
- Client/broker/owner submitted changes are staged in `property_submissions`, never automatically published.
- `parties` contains private owner / broker / developer contact details; public listing adapters must not query these.
- Media starts private until separate approval.
- `audit_events`, `idempotency_keys` and `change_outbox` are foundations for secure bidirectional sync.

## Cross-system mapping
Legacy Android `PropertyItem` enums include `OFF_LEASE`, `OFF_OUT`, `SHOP_LEASE`, `SHOP_OUT`, `PRE_LEASED`, `RESI_RENT`, `RESI_RESALE`, `UND_CONST`.
Map those into normalized `business_segment`, `asset_type`, `transaction_type`. Keep legacy IDs in `legacy_identifiers`.
Current Android `OneTapSyncCoordinator` still points to old Sheet `1VJruPx1GTOMDziafPYK6ihW1v1DGrqTWO2vHk_oNReQ`. Public web adapter references current Sheet `1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0`. This discrepancy must be explicitly reconciled before Batch 08 migration. Do NOT replace app ID or merge data blindly.
Do not import placeholder/demo records as live real properties.

## Batch boundaries
Batch 03: schema + safely denying API surface + readiness contract.
Batch 04: actual verified login/session/role mapping.
Batch 05: authenticated write services, concurrency and audit transactions.
Batch 07: Android outbox and live bidirectional synchronization.
Batch 08: reconciled legacy migration and reporting integration.
