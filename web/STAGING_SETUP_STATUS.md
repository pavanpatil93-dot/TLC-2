# TLC2-Staging activation status — 2026-11-24

## Confirmed completed
- Supabase organization: The Leasing Company
- New isolated staging project: `TLC2-Staging`
- Supabase project ref: `cphnghmxnylcmpusmnvl`
- Region: Mumbai, `ap-south-1`
- Supabase creation cost quote: $0/month, Free organization
- Project status: `ACTIVE_HEALTHY`; PostgreSQL 17
- Migration `tlc_core_001`: applied successfully; 11 TLC tables
- Migration `tlc_restricted_role_no_login`: applied
- Migration `tlc_auth_read_permissions_002`: applied
- All 11 TLC base tables have RLS enabled and forced.
- RLS policies: exactly 2 account self-read policies; no public inventory access policy.
- Restricted role `tlc_web_runtime` has no SUPERUSER, BYPASSRLS, CREATEDB, CREATEROLE, or LOGIN; can SELECT on accounts and account_roles only under RLS.
- Preview-branch Vercel variables configured: `TLC_AUTH_URL`, `TLC_AUTH_ANON_KEY`. Both are encrypted, Preview-only and scoped to `feature/tlc2-web-mvp`.

## Intentionally incomplete / blocked
- Restricted role remains `NOLOGIN` until an authenticated administrator provisions a strong password using Supabase's secure SQL/password workflow.
- No database connection string has been provisioned: `TLC_DATABASE_URL` is **unset**.
- Browser OTP endpoints and form are deliberately **disabled until both Auth and database are configured**.
- No TLC employee, owner, broker or client accounts have been provisioned; all inventory APIs remain fail-closed.
- Supabase authentication email template, SMTP, provider rate limiting and live OTP delivery have not been verified.
- Vercel previously returned API deployment daily quota error; a newer preview deployment may not be available until reset.
- Existing TLC2-Development project, Android app, Master Sheet and public domain have not been modified.

## Remaining protected action
To finish the connection, generate and set a long random password **inside Supabase's trusted project dashboard** for `tlc_web_runtime`, enable LOGIN, and obtain the exact region-specific session-pooler connection parameters (do NOT guess the pooler host). Set `TLC_DATABASE_URL` as an encrypted Preview-only variable, bound to `feature/tlc2-web-mvp`, using the restricted role. Do not paste secrets in chat or commit them to Git.

The restricted role must keep `NOSUPERUSER NOBYPASSRLS` and only narrowly scoped SELECT grants. Test connection, transaction-local `tlc.auth_subject`, identity verification, and RLS denial before allowing any private route. Do not enable Add / Edit / Delete / Publish until Batch 05 write transactions pass security and approval tests.
