# TLC-2 Website & Android Remaining Batches — Execution Status

This is an implementation ledger, not a declaration that all capabilities are live.

| Batch | Implemented safely in this branch | Still blocked / not live |
|---|---|---|
| 03 Central database | PostgreSQL schema, FORCE RLS on staging tables, health/API contract | No database configured, migrations unapplied, permissions not deployed |
| 04 User login | Role-selection UI, login/access preview | Real email/phone OTP, provider credentials, verified session and admin invitations |
| 05 Admin inventory | Employee inventory layout/permissions disclosure, fail-closed API | Authenticated create/edit/archive/publish; private data unavailable |
| 06 Owner/broker/developer posting | Draft form with validation and local review; deny-by-default submission endpoint | Secure uploads/storage, approval queue and real posting API |
| 07 Android ↔ Web sync | Explicit protocol and 401-gated endpoints, outbox table | Android changes, verified sync sessions, realtime updates, offline testing |
| 08 Sheet reconciliation | Read-only CSV reconciliation CLI with tests, import_run/conflict schema | Snapshot exports, approved mapping, staging migration and cutover |
| 09 Enquiries/CRM | Enquiry table contract with RLS, fail-closed public endpoint | Consent policy, secure lead capture, CRM routing, rate limiting |
| 10 QA & launch | Branch-preview build validation, noindex preview, privacy-safe guards | Real devices, complete threat model, content approval, DNS and go-live |

## Non-negotiable activation gates
1. User chooses and provisions **one secure authentication provider**, with verified sessions, invitation/approval workflows, MFA for internal admin, and role policy tests.
2. Provision **new staging PostgreSQL**, use distinct migration-owner and restricted application roles, enable backups, TLS and row-level security. Never place high-privilege DB keys in browser JavaScript.
3. Provision private image/document storage; upload virus/content-type limits, signed access, permission checking and media approval.
4. Confirm source-of-truth mapping and property code canonicalization for **both** Google Sheet IDs. Obtain reviewed dry-run reconciliation report and approved migration run.
5. Implement real writes with server-side schema validation, row-scoped permission checks, auditable optimistic concurrency, idempotent mutation and transactionally written outbox.
6. Test on Samsung S22 Ultra and second employee Android device; verify no client call history or private owner data escapes.
7. Complete legal privacy, terms, consent, anti-spam, official address and contact details. Only after content approval remove noindex and connect production domain.
8. Approval required before starting a real user import, writing to current Sheets, or changing production DNS.

## How to review UI now
- `/login`: role entry points and visible activation warning.
- `/portal?role=employee`: internal workflow preview.
- `/portal/inventory`: protected inventory design — **no private data**.
- `/portal/new-property`: draft preparation, validation and local review — **never sends data**.
- `/api/v1/system/features`: machine-readable current activation statuses.
- `/api/v1/system/health`: non-sensitive staging health.

## Security statement
A preview-looking management panel is **not proof** of functional CRM/admin integration. All write actions must stay disabled until the activation gates pass. The original public approved-listing adapter remains independent and fail-closed.
