# TLC-2 web + Android: automated implementation report (staging branch)

**Branch**: feature/tlc2-auto-batches-04-10-20261124  
**Source**: TLC-2 main/feature web code and TLC-1 project transfer handover.  
**Safety**: No production domain, Android main, live master Sheet, account credentials or customer records changed.

## Batch 04 — Verified accounts
Code: passwordless email OTP UI (/login); approved-account workspace (/dashboard); Supabase browser auth client; server-side JWT signature verification against configured HTTPS JWKS with exact issuer/audience; identity mapped to active accounts and DB role memberships through transaction-local RLS subject. Disabled until identity provider, accounts and staging DB exist. **Do not use name+mobile alone to authorize property writes.**

## Batch 05 — Internal property management
Code: /dashboard/properties, new and edit pages; authenticated CRUD routes using parametrized SQL; optimistic version check; audit and outbox in one transaction; hard archive instead of deletion; published records cannot be edited in the staging API. Publishing endpoint is explicitly disabled because owner publication authority evidence and release rules are not yet implemented. One approved project ID is needed for project linkage; this is not auto-generated from free text.

## Batch 06 — Owner / Developer / Broker intake
Code: /post-property, /dashboard/submissions, /api/v1/submissions. Requires pre-approved verified accounts; all submissions are staged privately. No auto-publish. Media upload remains disabled until private object storage and moderation approvals exist.

## Batch 07 — Android interoperability
Code: /api/v1/sync/changes?after=N authenticated, cursor-ordered version feed (requires RLS policy and database deployment). Android -> server sync POST returns 503 intentionally until idempotent operation replay and conflict resolution are tested with the real mobile Room schema. Current app uses OneTapSyncCoordinator against historical Sheet 1VJruPx1GTOMDziafPYK6ihW1v1DGrqTWO2vHk_oNReQ, whereas web reads 1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0. These sources cannot be silently merged. Real two-way sync is **not active**.

## Batch 08 — Historical inventory migration
Code: read-only CSV reconciliation script and unit tests. Counts missing / duplicate property IDs without publishing any owner/contact data. Requires source authority mapping, backup, staged import, row-level reconciliation and DBA-reviewed migration. No real data migration executed.

## Batch 09 — Public enquiries / CRM
Code: /enquire, CAPTCHA-gated POST /api/v1/enquiries; /dashboard/enquiries access controlled via accounts and RLS; new private enquiries schema. Service is disabled until Turnstile, database roles and privacy/consent requirements are provisioned. No fabricated client records and no public PII leaks.

## Batch 10 — QA / production readiness
Code: preview robots.txt disallow, existing noindex metadata, node:tests for CSV audit, TypeScript/Vercel build. Extra UAT needed: 4 roles, forbidden requests, duplicate writes, cache invalidation, on-device offline recovery, images/floor plans permissions, Sheets reconciliation, browser/mobile screens and rollback rehearsals.

## Activation gates
1. Owner selects/provisions verified Supabase Auth project (or equivalent OIDC issuer/JWKS). Admin manually invites known users. Add only approved **preview** environment variables.
2. DBA creates a **new** staging PostgreSQL 15+ with restricted runtime role (not owner/BYPASSRLS); review/apply 001, 002, 003 migrations and verify policies/grants.
3. Reconcile the old and new Google Sheet master IDs, 5+ inventory/project tabs and property-code contracts; run a dry-run audit with approved exports.
4. Establish actual owner authority/consent document and evidence storage per public listing. Only then implement protected Publish/Unpublish.
5. Configure separate private photo/floorplan storage, signed upload, virus/content validation and explicit media publication approval.
6. Implement Android durable outbox WorkManager adapter with signed user access tokens, idempotency and version conflict resolution; validate on Samsung device while online/offline.
7. Configure CAPTCHA, HTTPS host binding, CRM owner assignment, spam and privacy controls; test real opt-in enquiry.
8. Perform penetration/privacy tests and mobile/desktop UAT; authorize main merge and live domain change explicitly.

**Release acceptance**: source-control code presence ≠ working production features. Verified preview builds are necessary, but not sufficient. Never claim activation or instantaneous sync without real end-to-end tests.
