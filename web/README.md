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

## Auto-batch staging branch (04–10)

The `feature/tlc2-auto-batches-04-10` branch contains account/portal UI previews, read-only property draft validation, staging SQL contract extensions, a CSV reconciliation tool, and fail-closed endpoints. It does **not** contain a working live login, owner submission service, Android sync, or CRM writes. Review [implementation status](docs/IMPLEMENTATION_STATUS.md) and [launch gate](docs/LAUNCH_CHECKLIST.md).

```bash
cd web
npm test
npm run typecheck
npm run build
# Read-only input audit; NEVER writes to master Sheets:
node scripts/reconcile-inventory.mjs path/to/export.csv
```

No production databases, Android apps or Google Sheets are changed by these files.
