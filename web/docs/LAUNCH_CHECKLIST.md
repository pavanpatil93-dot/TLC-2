# Batch 10 — Public Launch Gate

**Preview-first; no automatic production cutover.**

- [ ] Run `npm test`, `npm run typecheck`, `npm run build`; inspect security and dependency warnings.
- [ ] Manually verify desktop/mobile search, multi-select, possession, project cards and inaccessible sample listing labels.
- [ ] Verify POST to submission/sync/admin endpoints returns 401 or 503 to anonymous/forged requests.
- [ ] Confirm no private owner, employee, developer, brokerage or CRM information is present in HTML, JS, client API or logs.
- [ ] Confirm only owner-authorized properties are published; sold/withdrawn properties are removed.
- [ ] Confirm verified server authentication, account approvals, MFA, CSRF/session protections and RLS policies.
- [ ] Exercise permissions with owner, broker, employee and admin users; verify cross-account denial.
- [ ] Verify media owner permission, upload scanning, safe extensions, size limits and signed URLs.
- [ ] Restore a staging backup. Test duplicate imports, rollback, change outbox replay and offline Android conflicts.
- [ ] Agree on privacy statement, cookies policy, legal disclaimers, property accuracy and lead consent retention.
- [ ] Test site visit and enquiry lead routing end-to-end, including duplicate CRM checks.
- [ ] Obtain production business owner approval for the exact domain, data set, and publication policy.
- [ ] Remove preview `noindex`, generate sitemap, verify canonical URLs only after approval.
- [ ] Save rollback deployment / DNS settings, then transfer Hostinger DNS in a scheduled maintenance window.

**Do not interpret a READY Vercel preview as authorization to publish private inventory or switch the business domain.**
