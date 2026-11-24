# Batch 04: TLC-2 Authentication and Role Access
## Current activation status
The login UI, email-code authentication adapter, protected session cookies, server-verified roles, and RLS migration are implemented on the preview branch.
As checked today, Vercel preview has NO TLC_AUTH_URL, TLC_AUTH_ANON_KEY, or TLC_DATABASE_URL. Therefore actual login is disabled, no database accounts have been activated, and private APIs fail closed. Android, production site, and Sheets remain unchanged.

## Staging setup prerequisites
1. Create a new, isolated Supabase Auth staging project. Enable email OTP with numeric code; configure email template to include {{ .Token }} rather than only a magic link. Configure SMTP/domain, email confirmation, sender rate limits, account recovery, and abuse protections.
2. Configure TLC_AUTH_URL=https://your-staging-project.supabase.co and TLC_AUTH_ANON_KEY (public anon application key) as server-side Vercel Preview environment variables. Never use service-role keys or NEXT_PUBLIC-prefixed auth credentials.
3. Create a separate backed-up PostgreSQL staging database. Apply db/migrations/001_core.sql as migration owner. Create a restricted LOGIN role named tlc_web_runtime, with NOSUPERUSER NOBYPASSRLS and no ownership of tlc tables.
4. Apply db/migrations/002_auth_read.sql as migration owner. It grants only SELECT for the current verified identity. Review privileges and audit RLS.
5. Set TLC_DATABASE_URL for the restricted database role only in Vercel Preview. Require TLS. Do not use production database credentials.
6. After a verified employee identity exists in the staging provider, DBA or authorized admin may explicitly approve a linked account and assign roles in tlc.accounts and tlc.account_roles. No user can self-assign admin or employee privileges. Selecting the Employee tab provides only sign-in guidance.
7. Owner, broker, developer, client identity-provider signups may sign in but have zero private permissions until an approved tlc.accounts record and role are provisioned.
8. Before production activation, implement a hosted rate limiter and appropriate audit monitoring for request-code and verify-code endpoints; provider rate controls alone are not sufficient for internet exposure.

## Authentication flow
- The browser sends email and requested account category to a server endpoint; category never grants a role.
- Supabase Auth emails a numeric OTP. The browser submits code to the server; the server checks it against the real provider and sets HttpOnly SameSite=Lax Secure cookies on HTTPS.
- Access tokens are verified server-to-server with the identity provider on each protected request.
- Only then does a restricted DB transaction set a transaction-local verified subject and read active profile/roles through row-level security.
- Private property endpoints remain disabled even for approved users until Batch 05 authenticated CRUD and audit operations are implemented.
- Sessions can be refreshed with rotating provider refresh tokens and cleared by sign-out.

## Required validation after staging activation
- GET /api/v1/auth/status reports only setup flags; no credentials.
- Without a valid session, GET and POST /api/v1/admin/properties return 401, including with forged X-Role and X-User headers.
- Mutating auth endpoints reject incorrect Origin with 403.
- Invalid, forged, expired OTP or session returns 401 and grants no roles.
- A signed-in but unapproved account sees pending approval, not internal records.
- Invited employee roles are loaded from server database only; test cross-account RLS denial.
- Property GET/POST stays NOT_ACTIVATED (503) even for approved user until Batch 05.
- Confirm no secrets, owner details, CRM data or identity tokens leak in public API JSON, cache, browser storage, or logs.
- Run npm run security:batch04, npm run typecheck, npm run build; verify deployed routes and browser workflows.
- Keep Vercel deployment preview protected, and do not change production DNS or Android code.

## Testing scope
Static security checks validate source invariants only. Actual email delivery, authenticated HTTP behavior, cookie/CSRF, RLS and penetration tests require real staging credentials and database provisioning.
