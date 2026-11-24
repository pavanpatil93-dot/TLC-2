# Public Website Release Gate

This scaffold deliberately exposes **zero** private inventory records by default.

## Source
- TLC Master Sheet `1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0`.
- Reads ONLY five approved inventory worksheets, NEVER CRM, personnel, owner contact sheets or audit logs.
- Server-side service account requires Sheet Viewer permission. Never ship credentials in browser JavaScript.

## Three independent publication gates
1. Server environment variable `TLC_PUBLIC_APPROVED_IDS` names the worksheet AND exact `Unit ID`.
2. Property row `Inventory Status` must be exactly `Available`.
3. Property row `Verification Status` must be exactly `Verified` or `Verified Confirmed`.

On absent configuration, data-source error, failed eligibility, or missing fields, the API publishes **zero** records. This is intentionally conservative.

## Public allowlist
The API responds ONLY with: id, title, location, type, carpet square feet, asking rent/sale amount, transaction, and safe condition/configuration summary. Do not publish `Remarks`, `WhatsApp Pitch`, owner information, developer contacts, brokerage, source, CRM or phone data. Individual unit IDs may still identify a property; publish only with owner approval.

## Publication checks
- Validate authority/approval of each listing, especially direct-owner consent.
- Check duplicate property codes, combined units, available vs sold/leased.
- Confirm prices and areas; remove withdrawn records.
- Check the full request/response, including browser network tab and error responses.
- Run access/security and spam tests before enabling form submissions.
- Keep robots noindex until legal pages, branding, image permissions and production checks are complete.

## Future phases
Team/broker accounts require independent identity verification, server-side authorization, scoped query predicates, audit events, and secure lead submission workflows. This public route is **not** an authenticated broker/CRM backend.
