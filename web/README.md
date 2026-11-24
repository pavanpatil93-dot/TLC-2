# TLC-2 Web MVP (isolated)

This is a separately deployable **Next.js** application in the existing Android repository. The root Android build is not modified.

## Local testing

```bash
cd web
npm install
npm run typecheck
npm run build
npm run dev
```

## Vercel

Create a **new** Vercel project pointing to `pavanpatil93-dot/TLC-2` and set **Root Directory = web**. Do **not** link the existing production `the-leasing-company` deployment or attach `theleasingcompany.in` until backend security and QA pass. Run `npm run build` in Vercel.

## Security and deliberate limitations

- `/api/public-properties` fails closed, returning no listings. Never connect raw spreadsheet data to this route without server-side field allowlisting, publication approval, permission controls, rate limiting and validation.
- No client/broker accounts, payment, CRM or Android call-history features are enabled.
- Marketing pages have `noindex` until content, approved inventory, legal terms and production checks are ready.
- Do not put API secrets in `NEXT_PUBLIC_*` environment variables or commit credentials.
- The existing Android sync still points to the old master sheet. Migration requires explicit schema mapping for five inventory tabs and should be tested independently.

## Launch checklist

1. Confirm the public listing field schema and whitelist only permitted fields.
2. Build and secure a backend data adapter for new TLC Master Sheet `1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0`.
3. Verify duplicate resolution, availability, owner privacy and public publishing approval.
4. Add broker/team authentication and server-side RBAC in later phases.
5. Deploy a preview, test, then separately configure Hostinger DNS and Vercel domains.

## Batch 04 — Login preview
- Open /login and /dashboard; login is intentionally disabled until staging Auth and DB are provisioned.
- The server validates one-time email codes, issues HttpOnly cookies and resolves database roles only after verified identity.
- Private /api/v1/admin/properties remains fail-closed, even for verified users, until Batch 05.
- See BATCH_04_AUTH_SETUP.md for provider, database, RLS and security-test prerequisites.

## Updated login workflow (staging)

- The public /login screen asks **only for Email and Password**. No account type is requested before sign-in.
- A successful password sign-in verifies credentials against Supabase Auth, creates HttpOnly SameSite=Lax cookies, and opens /dashboard.
- Only after a verified sign-in can the user choose **Broker, Owner, Client, Tenant, or Developer**.
- This choice is saved as a *self-declared onboarding preference* in Supabase Auth user_metadata, not as an approved tlc.account_roles entry. It must never be used to grant property management permissions.
- Existing approved TLC employees continue to receive server-authorized employee roles; picking an onboarding type never changes them.
- Tenant is included in the restricted staging database account-kind and role constraints via migration 003.
- If the staging Postgres role remains NOLOGIN or TLC_DATABASE_URL is unset, approved TLC data access remains blocked. Identity sign-in and onboarding preference can be used without enabling private inventory.
- There is no public password registration or password reset flow in this batch; account provisioning must be completed separately.
- Run npm run security:login, npm run security:batch04, npm run typecheck, npm run build.
