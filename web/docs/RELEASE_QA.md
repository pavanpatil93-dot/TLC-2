# TLC-2 04–10 Release QA Matrix

| Area | Required acceptance | Current |
|---|---|---|
| Branding/search | TLC logo/theme, compact mobile/desktop search | Implemented preview |
| Identity | Email OTP, verified JWT, active account, 401/403 guard | Code staged; provider unconfigured |
| Employee inventory | Read + draft create, edit version check, archive, audit | Code staged; DB not deployed |
| Publication | Explicit owner authority, verified/available, approval, public media | Disabled by design |
| Partners | Verified owner/broker intake, approval queue, no auto-publish | Code staged; DB unconfigured |
| Android | two-way offline outbox, idempotency, conflicts, Samsung testing | Not integrated |
| Migration | Old/new Sheets match, dedup, backups, row totals | Dry-run script only |
| CRM | CAPTCHA, consent, private lead storage, employee access | Code staged; captcha unconfigured |
| Site | noindex, no client PII, responsive, rollback | Preview built; UAT pending |

## Negative security tests
- No bearer token -> 401 for private endpoints.
- Client-provided X-Role/X-User-Id headers alone never grant access.
- Expired, wrong-audience or wrong-issuer JWT -> 401.
- Inactive/suspended account or missing database RLS policies -> 401/503, never fall back to role in browser.
- Broker POST creates only a private submission, not a published property.
- Employee creates a draft with verification pending and not confirmed availability.
- Version mismatch -> 409; duplicate property code -> 409.
- Public requests never receive contact/party, CRM, audit or staging payloads.
- CAPTCHA missing/incorrect -> 403/422 or 503, never accept PII.
- Database unavailable -> fail closed.
- Published property edit/archive/delete unauthorized -> denied.
- Mobile offline retry does not duplicate operations and does not silently overwrite.
